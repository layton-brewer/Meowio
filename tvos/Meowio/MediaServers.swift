import SwiftUI

// Jellyfin and Emby media servers, like the Android TV app's "Media servers": sign in once, pick libraries, and
// titles you own show up as extra sources (ranked first by default) and as "Recently added" rows on Home.

struct ServerLibrary: Codable, Hashable, Identifiable {
    var id: String
    var name: String
    /// "movie" or "series".
    var kind: String
    var selected: Bool = true
}

struct ServerConnection: Codable, Hashable, Identifiable {
    var id: String
    /// "jellyfin" or "emby".
    var provider: String
    var name: String
    var address: String
    var userId: String
    var userName: String
    var token: String
    var libraries: [ServerLibrary]
    var enabled: Bool = true

    var isEmby: Bool { provider == "emby" }
    var providerTitle: String { isEmby ? "Emby" : "Jellyfin" }
    var apiRoot: String { address + (isEmby ? "/emby" : "") }
    /// What the source list shows for results from this server.
    var sourceLabel: String { "\(providerTitle) · \(name)" }
}

private struct MBItem: Decodable {
    var Id: String
    var Name: String?
    var CollectionType: String?
    var Overview: String?
    var ProductionYear: Int?
    var PremiereDate: String?
    var ProviderIds: [String: String]?
    var Genres: [String]?
    var IndexNumber: Int?
    var ParentIndexNumber: Int?
    var CommunityRating: Double?
    var ImageTags: [String: String]?
    var BackdropImageTags: [String]?
    var ParentBackdropImageTags: [String]?
    var ParentBackdropItemId: String?
    var MediaSources: [MBSource]?

    var imdb: String? {
        (ProviderIds ?? [:]).first { $0.key.lowercased() == "imdb" }?.value
    }
}

private struct MBSource: Decodable {
    var Id: String?
    var Name: String?
    var Path: String?
    var Container: String?
    var Size: Int64?
    var MediaStreams: [MBStream]?
}

private struct MBStream: Decodable {
    var kind: String?
    var Codec: String?
    var Height: Int?
    var DisplayTitle: String?
    var VideoRangeType: String?

    enum CodingKeys: String, CodingKey {
        case kind = "Type", Codec, Height, DisplayTitle, VideoRangeType
    }
}

private struct MBItems: Decodable {
    var Items: [MBItem]
}

private struct MBAuth: Decodable {
    struct User: Decodable { var Id: String; var Name: String? }
    var AccessToken: String?
    var User: User?
    var ServerId: String?
}

private struct MBPublicInfo: Decodable {
    var ServerName: String?
    var Id: String?
}

enum MediaServerError: LocalizedError {
    case badAddress, unreachable, rejected, notSupported

    var errorDescription: String? {
        switch self {
        case .badAddress: return "That server address does not look right."
        case .unreachable: return "Could not reach the server. Check the address and that this Apple TV can reach it."
        case .rejected: return "The server refused that user name or password."
        case .notSupported: return "That does not look like a Jellyfin or Emby server."
        }
    }
}

/// The Jellyfin and Emby API (they share one API family). Playback is a direct stream that mpv opens.
private enum MediaBrowserAPI {
    static let deviceId: String = {
        let d = UserDefaults.standard
        if let id = d.string(forKey: "mediaServerDeviceId") { return id }
        let id = UUID().uuidString.lowercased()
        d.set(id, forKey: "mediaServerDeviceId")
        return id
    }()

    static func normalize(_ input: String) -> String? {
        var value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }
        if !value.contains("://") { value = "http://" + value }
        while value.hasSuffix("/") { value.removeLast() }
        for suffix in ["/web/index.html", "/web", "/emby"] where value.lowercased().hasSuffix(suffix) {
            value = String(value.dropLast(suffix.count))
        }
        guard let url = URL(string: value), let host = url.host, !host.isEmpty else { return nil }
        return value
    }

    static func header(token: String?) -> String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1"
        var h = "MediaBrowser Client=\"Meowio\", Device=\"Apple TV\", DeviceId=\"\(deviceId)\", Version=\"\(version)\""
        if let token { h += ", Token=\"\(token)\"" }
        return h
    }

    static func request(_ method: String = "GET", root: String, emby: Bool, path: String,
                        query: [String: String] = [:], body: Data? = nil, token: String?) async throws -> Data {
        guard var comps = URLComponents(string: root + path) else { throw MediaServerError.badAddress }
        if !query.isEmpty {
            comps.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = comps.url else { throw MediaServerError.badAddress }
        var r = URLRequest(url: url)
        r.httpMethod = method
        r.timeoutInterval = 15
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.setValue(header(token: token), forHTTPHeaderField: emby ? "X-Emby-Authorization" : "Authorization")
        if let body {
            r.httpBody = body
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let data: Data
        let response: URLResponse
        do { (data, response) = try await URLSession.shared.data(for: r) } catch { throw MediaServerError.unreachable }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 || status == 403 { throw MediaServerError.rejected }
        guard (200..<300).contains(status) else { throw MediaServerError.unreachable }
        return data
    }

    static func signIn(provider: String, address: String, user: String, password: String) async throws -> ServerConnection {
        guard let base = normalize(address) else { throw MediaServerError.badAddress }
        let emby = provider == "emby"
        let root = base + (emby ? "/emby" : "")
        let infoData = try await request(root: root, emby: emby, path: "/System/Info/Public", token: nil)
        guard let info = try? JSONDecoder().decode(MBPublicInfo.self, from: infoData) else { throw MediaServerError.notSupported }
        let payload = try? JSONSerialization.data(withJSONObject: ["Username": user, "Pw": password])
        let authData = try await request("POST", root: root, emby: emby, path: "/Users/AuthenticateByName", body: payload, token: nil)
        guard let auth = try? JSONDecoder().decode(MBAuth.self, from: authData),
              let token = auth.AccessToken, !token.isEmpty, let account = auth.User else { throw MediaServerError.rejected }
        var conn = ServerConnection(
            id: UUID().uuidString.lowercased().prefix(8).description,
            provider: provider,
            name: info.ServerName ?? URL(string: base)?.host ?? "Server",
            address: base,
            userId: account.Id,
            userName: account.Name ?? user,
            token: token,
            libraries: [])
        conn.libraries = (try? await libraries(conn)) ?? []
        return conn
    }

    static func libraries(_ c: ServerConnection) async throws -> [ServerLibrary] {
        let data: Data
        if c.isEmby {
            data = try await request(root: c.apiRoot, emby: true, path: "/Users/\(c.userId)/Views", token: c.token)
        } else {
            data = try await request(root: c.apiRoot, emby: false, path: "/UserViews", query: ["userId": c.userId], token: c.token)
        }
        let items = (try? JSONDecoder().decode(MBItems.self, from: data))?.Items ?? []
        return items.compactMap { view in
            switch (view.CollectionType ?? "").lowercased() {
            case "movies": return ServerLibrary(id: view.Id, name: view.Name ?? "Movies", kind: "movie")
            case "tvshows": return ServerLibrary(id: view.Id, name: view.Name ?? "Shows", kind: "series")
            default: return nil
            }
        }
    }

    private static func itemsPath(_ c: ServerConnection) -> (String, [String: String]) {
        c.isEmby ? ("/Users/\(c.userId)/Items", [:]) : ("/Items", ["userId": c.userId])
    }

    private static func items(_ c: ServerConnection, extra: [String: String]) async throws -> [MBItem] {
        let (path, base) = itemsPath(c)
        let data = try await request(root: c.apiRoot, emby: c.isEmby, path: path, query: base.merging(extra) { _, n in n }, token: c.token)
        return (try? JSONDecoder().decode(MBItems.self, from: data))?.Items ?? []
    }

    static func recent(_ c: ServerConnection, library: ServerLibrary, limit: Int) async -> [MBItem] {
        let extra: [String: String] = [
            "parentId": library.id, "recursive": "true",
            "includeItemTypes": library.kind == "movie" ? "Movie" : "Series",
            "sortBy": "DateCreated", "sortOrder": "Descending", "limit": String(limit),
            "fields": "ProviderIds,Overview,Genres,PremiereDate,ProductionYear",
            "imageTypeLimit": "1", "enableImageTypes": "Primary,Backdrop"
        ]
        return (try? await items(c, extra: extra)) ?? []
    }

    /// Items in [library] that are the title with this IMDb id.
    static func lookup(_ c: ServerConnection, library: ServerLibrary, imdb: String, name: String?) async -> [String] {
        var extra: [String: String] = [
            "parentId": library.id, "recursive": "true",
            "includeItemTypes": library.kind == "movie" ? "Movie" : "Series",
            "fields": "ProviderIds", "enableImages": "false", "enableUserData": "false", "limit": "20"
        ]
        if c.isEmby {
            extra["anyProviderIdEquals"] = "imdb.\(imdb)"
        } else if let name, !name.isEmpty {
            extra["searchTerm"] = name
        } else {
            return []
        }
        let found = (try? await items(c, extra: extra)) ?? []
        return found.filter { $0.imdb?.lowercased() == imdb.lowercased() }.map(\.Id)
    }

    static func episodeId(_ c: ServerConnection, seriesId: String, season: Int, episode: Int) async -> String? {
        let data = try? await request(root: c.apiRoot, emby: c.isEmby, path: "/Shows/\(seriesId)/Episodes",
                                      query: ["userId": c.userId, "season": String(season), "fields": "PremiereDate"], token: c.token)
        let list = data.flatMap { try? JSONDecoder().decode(MBItems.self, from: $0) }?.Items ?? []
        return list.first { $0.ParentIndexNumber == season && $0.IndexNumber == episode }?.Id
    }

    static func allEpisodes(_ c: ServerConnection, seriesId: String) async -> [MBItem] {
        let data = try? await request(root: c.apiRoot, emby: c.isEmby, path: "/Shows/\(seriesId)/Episodes",
                                      query: ["userId": c.userId, "fields": "Overview,PremiereDate", "imageTypeLimit": "1", "enableImageTypes": "Primary"],
                                      token: c.token)
        return data.flatMap { try? JSONDecoder().decode(MBItems.self, from: $0) }?.Items ?? []
    }

    static func detail(_ c: ServerConnection, itemId: String, fields: String) async -> MBItem? {
        let path = c.isEmby ? "/Users/\(c.userId)/Items/\(itemId)" : "/Items/\(itemId)"
        let query = c.isEmby ? ["fields": fields] : ["userId": c.userId, "fields": fields]
        guard let data = try? await request(root: c.apiRoot, emby: c.isEmby, path: path, query: query, token: c.token) else { return nil }
        return try? JSONDecoder().decode(MBItem.self, from: data)
    }

    static func imageURL(_ c: ServerConnection, itemId: String, kind: String, tag: String?, width: Int) -> String {
        var s = "\(c.apiRoot)/Items/\(itemId)/Images/\(kind)?maxWidth=\(width)&api_key=\(c.token)"
        if let tag { s += "&tag=\(tag)" }
        return s
    }

    /// One stream per media source (version) of the item, as a direct stream link mpv can open.
    static func streams(_ c: ServerConnection, itemId: String) async -> [StreamItem] {
        guard let item = await detail(c, itemId: itemId, fields: "MediaSources") else { return [] }
        var out: [StreamItem] = []
        for source in item.MediaSources ?? [] {
            guard let sid = source.Id else { continue }
            let video = (source.MediaStreams ?? []).first { ($0.kind ?? "").lowercased() == "video" }
            let height = video?.Height ?? 0
            let res = height >= 2000 ? "2160p" : height >= 1000 ? "1080p" : height >= 700 ? "720p" : height > 0 ? "480p" : ""
            let codec = (video?.Codec ?? "").uppercased()
            let file = (source.Path ?? "").split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? source.Name ?? ""
            let sizeGB = source.Size.map { String(format: "%.1f GB", Double($0) / 1_073_741_824) } ?? ""
            let name = [res, codec, video?.VideoRangeType].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
            let url = "\(c.apiRoot)/Videos/\(itemId)/stream?static=true&mediaSourceId=\(sid)&deviceId=\(deviceId)&api_key=\(c.token)"
            let container = (source.Container ?? "").lowercased()
            let hints = StreamItem.BehaviorHints(notWebReady: !["mp4", "m4v", "mov"].contains(container),
                                                 filename: file, bingeGroup: nil, proxyHeaders: nil)
            var stream = StreamItem(name: name.isEmpty ? (source.Name ?? "Server copy") : name,
                                    title: file,
                                    description: [sizeGB, container.uppercased()].filter { !$0.isEmpty }.joined(separator: " · "),
                                    url: url, infoHash: nil, fileIdx: nil, behaviorHints: hints,
                                    addonName: c.sourceLabel)
            stream.isServer = true
            out.append(stream)
        }
        return out
    }
}

/// A row of titles from a server, for Home.
struct ServerShelf: Identifiable {
    var id: String
    var title: String
    var items: [MetaItem]
}

/// The user's media servers for the active profile.
@MainActor
final class MediaServerStore: ObservableObject {
    @Published var connections: [ServerConnection] { didSet { save() } }
    /// Put the user's own copies ahead of every add-on source.
    @Published var preferServers: Bool { didSet { save() } }
    @Published var busy = false
    @Published var message: String?

    private let store = ProfileScope.defaults
    private var lookups: [String: [String]] = [:]

    init() {
        let d = ProfileScope.defaults
        if let data = d.data(forKey: "mediaServers"), let list = try? JSONDecoder().decode([ServerConnection].self, from: data) {
            connections = list
        } else {
            connections = []
        }
        preferServers = d.object(forKey: "preferServers") as? Bool ?? true
    }

    private func save() {
        if let data = try? JSONEncoder().encode(connections) { store.set(data, forKey: "mediaServers") }
        store.set(preferServers, forKey: "preferServers")
    }

    var enabled: [ServerConnection] { connections.filter(\.enabled) }
    var hasEnabled: Bool { !enabled.isEmpty }

    func signIn(provider: String, address: String, user: String, password: String) async -> Bool {
        busy = true
        message = nil
        defer { busy = false }
        do {
            let conn = try await MediaBrowserAPI.signIn(provider: provider, address: address, user: user, password: password)
            connections.removeAll { $0.address == conn.address && $0.userId == conn.userId && $0.provider == conn.provider }
            connections.append(conn)
            if conn.libraries.isEmpty { message = "Signed in, but no movie or show libraries were found." }
            return true
        } catch {
            message = (error as? LocalizedError)?.errorDescription ?? "Could not sign in."
            return false
        }
    }

    func remove(_ id: String) {
        connections.removeAll { $0.id == id }
        lookups = lookups.filter { !$0.key.hasPrefix(id) }
    }

    func refreshLibraries(_ id: String) async {
        guard let i = connections.firstIndex(where: { $0.id == id }),
              let fresh = try? await MediaBrowserAPI.libraries(connections[i]) else { return }
        let old = connections[i].libraries
        connections[i].libraries = fresh.map { lib in
            var l = lib
            if let was = old.first(where: { $0.id == lib.id }) { l.selected = was.selected }
            return l
        }
        lookups.removeAll()
    }

    func setLibrary(_ connectionId: String, _ libraryId: String, selected: Bool) {
        guard let i = connections.firstIndex(where: { $0.id == connectionId }),
              let j = connections[i].libraries.firstIndex(where: { $0.id == libraryId }) else { return }
        connections[i].libraries[j].selected = selected
        lookups.removeAll()
    }

    // MARK: Ids

    private static let prefix = "srv1:"

    static func isServerId(_ id: String) -> Bool { id.hasPrefix(prefix) }

    static func nativeId(connection: String, item: String) -> String { "\(prefix)\(connection):\(item)" }

    static func parse(_ id: String) -> (connection: String, item: String)? {
        guard isServerId(id) else { return nil }
        let body = id.dropFirst(prefix.count)
        guard let colon = body.firstIndex(of: ":") else { return nil }
        let item = String(body[body.index(after: colon)...])
        return item.isEmpty ? nil : (String(body[..<colon]), item)
    }

    // MARK: Sources

    /// Streams from the user's servers for a movie, an episode (Cinemeta id tt123:1:4) or a title that came
    /// from a server itself. Empty when no server has it.
    func streams(type: String, id: String, name: String?) async -> [StreamItem] {
        let servers = enabled
        guard !servers.isEmpty else { return [] }
        if let ref = Self.parse(id) {
            guard let conn = servers.first(where: { $0.id == ref.connection }) else { return [] }
            return await MediaBrowserAPI.streams(conn, itemId: ref.item)
        }
        let ref = EpisodeRef(id)
        let imdb = ref?.showId ?? id
        guard imdb.hasPrefix("tt") else { return [] }
        let kind = type == "movie" ? "movie" : "series"
        var out: [StreamItem] = []
        await withTaskGroup(of: [StreamItem].self) { group in
            for conn in servers {
                group.addTask { [weak self] in
                    guard let self else { return [] }
                    let ids = await self.titleIds(conn, kind: kind, imdb: imdb, name: name)
                    var found: [StreamItem] = []
                    for titleId in ids {
                        if kind == "movie" {
                            found += await MediaBrowserAPI.streams(conn, itemId: titleId)
                        } else if let ref, let ep = await MediaBrowserAPI.episodeId(conn, seriesId: titleId, season: ref.season, episode: ref.episode) {
                            found += await MediaBrowserAPI.streams(conn, itemId: ep)
                        }
                    }
                    return found
                }
            }
            for await list in group { out += list }
        }
        return out
    }

    private func titleIds(_ conn: ServerConnection, kind: String, imdb: String, name: String?) async -> [String] {
        let key = "\(conn.id)|\(kind)|\(imdb)"
        if let hit = lookups[key] { return hit }
        var ids: [String] = []
        for lib in conn.libraries where lib.selected && lib.kind == kind {
            ids += await MediaBrowserAPI.lookup(conn, library: lib, imdb: imdb, name: name)
        }
        lookups[key] = ids
        return ids
    }

    // MARK: Home rows and native titles

    func shelves() async -> [ServerShelf] {
        var out: [ServerShelf] = []
        for conn in enabled {
            for lib in conn.libraries where lib.selected {
                let items = await MediaBrowserAPI.recent(conn, library: lib, limit: 30)
                let metas = items.map { meta(from: $0, conn: conn, kind: lib.kind) }
                if !metas.isEmpty { out.append(ServerShelf(id: "\(conn.id)|\(lib.id)", title: "\(lib.name) · \(conn.name)", items: metas)) }
            }
        }
        return out
    }

    private func meta(from item: MBItem, conn: ServerConnection, kind: String) -> MetaItem {
        let id = item.imdb ?? Self.nativeId(connection: conn.id, item: item.Id)
        var m = MetaItem(id: id, type: kind, name: item.Name ?? "Untitled")
        m.poster = (item.ImageTags?["Primary"]).map { MediaBrowserAPI.imageURL(conn, itemId: item.Id, kind: "Primary", tag: $0, width: 500) }
        if let tag = item.BackdropImageTags?.first {
            m.background = MediaBrowserAPI.imageURL(conn, itemId: item.Id, kind: "Backdrop", tag: tag, width: 1280)
        }
        m.description = item.Overview
        m.releaseInfo = item.ProductionYear.map(String.init)
        m.imdbRating = item.CommunityRating.map { String(format: "%.1f", $0) }
        m.genres = item.Genres
        return m
    }

    /// Details for a title that only exists on a server (no IMDb id): artwork, and episodes for a show.
    func nativeMeta(_ item: MetaItem) async -> MetaItem? {
        guard let ref = Self.parse(item.id), let conn = enabled.first(where: { $0.id == ref.connection }) else { return nil }
        var m = item
        if item.type != "movie" {
            let episodes = await MediaBrowserAPI.allEpisodes(conn, seriesId: ref.item)
            m.videos = episodes.compactMap { ep in
                guard let s = ep.ParentIndexNumber, let n = ep.IndexNumber else { return nil }
                var v = Video(id: Self.nativeId(connection: conn.id, item: ep.Id), season: s, episode: n)
                v.title = ep.Name
                v.thumbnail = (ep.ImageTags?["Primary"]).map { MediaBrowserAPI.imageURL(conn, itemId: ep.Id, kind: "Primary", tag: $0, width: 640) }
                v.released = ep.PremiereDate
                return v
            }
        }
        return m
    }
}

// MARK: - Settings

struct MediaServersSettingsView: View {
    @EnvironmentObject var servers: MediaServerStore
    @State private var provider = "jellyfin"
    @State private var address = ""
    @State private var user = ""
    @State private var password = ""

    var body: some View {
        List {
            Section {
                ForEach(servers.connections) { conn in
                    NavigationLink {
                        MediaServerDetailView(connectionId: conn.id)
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(conn.name)
                                Text("\(conn.providerTitle) · \(conn.userName)").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(conn.enabled ? "On" : "Off").foregroundStyle(.secondary)
                        }
                    }
                }
                if servers.connections.isEmpty {
                    Text("No server added yet.").foregroundStyle(.secondary)
                }
            } header: {
                Text("Your servers")
            } footer: {
                Text("Titles on your Jellyfin or Emby server become sources that Smart play can pick, and recent additions show on Home. Sign-in details stay on this Apple TV and are not in backups.")
            }

            Section("Preferences") {
                Toggle("Play my server's copy first", isOn: $servers.preferServers)
            }

            Section("Add a server") {
                Picker("Server", selection: $provider) {
                    Text("Jellyfin").tag("jellyfin")
                    Text("Emby").tag("emby")
                }
                TextField("Address, such as 192.168.1.20:8096", text: $address)
                    .autocorrectionDisabled()
                TextField("User name", text: $user)
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                Button(servers.busy ? "Signing in…" : "Sign in and add") {
                    Task {
                        if await servers.signIn(provider: provider, address: address, user: user, password: password) {
                            address = ""
                            user = ""
                            password = ""
                        }
                    }
                }
                .disabled(servers.busy || address.isEmpty || user.isEmpty)
                if let m = servers.message { Text(m).foregroundStyle(.orange) }
            }
        }
        .navigationTitle("Media servers")
    }
}

struct MediaServerDetailView: View {
    let connectionId: String
    @EnvironmentObject var servers: MediaServerStore
    @Environment(\.dismiss) private var dismiss

    private var connection: ServerConnection? { servers.connections.first { $0.id == connectionId } }

    var body: some View {
        List {
            if let conn = connection {
                Section {
                    Toggle("Use this server", isOn: Binding(
                        get: { conn.enabled },
                        set: { on in
                            if let i = servers.connections.firstIndex(where: { $0.id == connectionId }) { servers.connections[i].enabled = on }
                        }))
                    Text(conn.address).font(.caption).foregroundStyle(.secondary)
                }
                Section("Libraries") {
                    ForEach(conn.libraries) { lib in
                        Toggle("\(lib.name) (\(lib.kind == "movie" ? "movies" : "shows"))", isOn: Binding(
                            get: { lib.selected },
                            set: { servers.setLibrary(connectionId, lib.id, selected: $0) }))
                    }
                    Button("Refresh libraries") { Task { await servers.refreshLibraries(connectionId) } }
                }
                Section {
                    Button("Remove this server", role: .destructive) {
                        servers.remove(connectionId)
                        dismiss()
                    }
                }
            }
        }
        .navigationTitle(connection?.name ?? "Server")
    }
}
