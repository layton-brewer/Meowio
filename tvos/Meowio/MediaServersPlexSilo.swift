import SwiftUI

// Plex and Silo, next to Jellyfin and Emby, as the Android apps have them. Plex signs in with a code at plex.tv/link
// and plays the file itself; Silo signs in with a code (or a password) and plans each playback on the server.

// MARK: - Shared

/// A refused request: the HTTP status and, for Silo, the problem code it sent.
struct ServerHTTPError: Error {
    let status: Int
    let code: String?
}

enum ServerHTTP {
    static func send(_ method: String, url: URL, headers: [String: String], body: Data? = nil,
                     timeout: TimeInterval = 15) async throws -> (Data, HTTPURLResponse) {
        var r = URLRequest(url: url)
        r.httpMethod = method
        r.timeoutInterval = timeout
        for (name, value) in headers { r.setValue(value, forHTTPHeaderField: name) }
        r.httpBody = body ?? ((method == "POST" || method == "PUT") ? Data() : nil)
        let data: Data
        let response: URLResponse
        do { (data, response) = try await URLSession.shared.data(for: r) } catch { throw MediaServerError.unreachable }
        guard let http = response as? HTTPURLResponse else { throw MediaServerError.unreachable }
        guard (200..<300).contains(http.statusCode) else {
            let type = ServerJSON.parse(data).s("type")
            throw ServerHTTPError(status: http.statusCode, code: type.flatMap { $0.split(separator: "/").last.map(String.init) })
        }
        return (data, http)
    }

    static func url(_ base: String, _ path: String, _ query: [String: String?]) -> URL? {
        guard var comps = URLComponents(string: base + path) else { return nil }
        let items = query.compactMap { key, value in value.map { URLQueryItem(name: key, value: $0) } }.sorted { $0.name < $1.name }
        if !items.isEmpty { comps.queryItems = (comps.queryItems ?? []) + items }
        return comps.url
    }

    /// One id for this Apple TV, shared by every server kind.
    static var deviceId: String {
        let d = UserDefaults.standard
        if let id = d.string(forKey: "mediaServerDeviceId") { return id }
        let id = UUID().uuidString.lowercased()
        d.set(id, forKey: "mediaServerDeviceId")
        return id
    }

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1"
    }

    static func newConnectionId() -> String { String(UUID().uuidString.lowercased().prefix(8)) }

    static func fileName(_ path: String?) -> String {
        guard let path else { return "" }
        return path.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? path
    }

    static func sizeText(_ bytes: Double?) -> String {
        guard let bytes, bytes > 0 else { return "" }
        return String(format: "%.1f GB", bytes / 1_073_741_824)
    }
}

/// A forgiving view of a JSON reply: Plex sends some ids as numbers in one version and as strings in another.
struct ServerJSON {
    let raw: [String: Any]

    init(_ any: Any?) { raw = any as? [String: Any] ?? [:] }

    static func parse(_ data: Data) -> ServerJSON { ServerJSON(try? JSONSerialization.jsonObject(with: data)) }

    static func parseList(_ data: Data) -> [ServerJSON] {
        ((try? JSONSerialization.jsonObject(with: data)) as? [Any] ?? []).map(ServerJSON.init)
    }

    subscript(_ key: String) -> ServerJSON { ServerJSON(raw[key]) }

    func s(_ key: String) -> String? {
        if let v = raw[key] as? String { return v }
        if let v = raw[key] as? NSNumber { return v.stringValue }
        return nil
    }

    /// A string that is there and not empty.
    func text(_ key: String) -> String? { s(key).flatMap { $0.isEmpty ? nil : $0 } }

    func i(_ key: String) -> Int? {
        if let v = raw[key] as? NSNumber { return v.intValue }
        if let v = raw[key] as? String { return Int(v) }
        return nil
    }

    func d(_ key: String) -> Double? {
        if let v = raw[key] as? NSNumber { return v.doubleValue }
        if let v = raw[key] as? String { return Double(v) }
        return nil
    }

    func b(_ key: String) -> Bool {
        if let v = raw[key] as? Bool { return v }
        if let v = raw[key] as? NSNumber { return v.boolValue }
        return false
    }

    func list(_ key: String) -> [ServerJSON] { (raw[key] as? [Any] ?? []).map(ServerJSON.init) }

    func strings(_ key: String) -> [String] { (raw[key] as? [Any] ?? []).compactMap { $0 as? String } }

    func ints(_ key: String) -> [Int] { (raw[key] as? [Any] ?? []).compactMap { ($0 as? NSNumber)?.intValue } }
}

/// Values kept per stream link, read from any thread.
final class ServerLinkRegistry<Value> {
    private var map: [String: Value] = [:]
    private let lock = NSLock()

    func set(_ key: String, _ value: Value?) {
        lock.lock()
        map[key] = value
        if map.count > 500 { map.removeAll() }
        lock.unlock()
    }

    func get(_ key: String) -> Value? {
        lock.lock()
        defer { lock.unlock() }
        return map[key]
    }
}

/// Playback reports for Plex and Silo copies, which the player names by their stream link.
enum ExternalServerReports {
    static func handles(_ url: String) -> Bool { PlexAPI.reports(url) || SiloAPI.reports(url) }

    static func report(_ action: String, url: String, seconds: Double, paused: Bool) {
        if SiloAPI.reports(url) {
            SiloAPI.report(action, url: url, seconds: seconds, paused: paused)
        } else {
            PlexAPI.report(action, url: url, seconds: seconds, paused: paused)
        }
    }
}

// MARK: - Plex

struct PlexAddress: Hashable {
    var uri: String
    var local: Bool
    var relay: Bool
}

struct PlexServerChoice: Identifiable, Hashable {
    /// The server's machine id.
    var id: String
    var name: String
    var owned: Bool
    var token: String
    var addresses: [PlexAddress]
}

struct PlexHomeUser: Identifiable, Hashable {
    var id: String
    var uuid: String
    var name: String
    var hasPin: Bool
}

struct PlexAccount {
    var token: String
    var id: String
    var uuid: String
    var name: String
}

struct PlexPin {
    var id: Int
    var code: String
    var expires: Date
}

private struct PlexPlay {
    var address: String
    var token: String
    var ratingKey: String
    var key: String
    var durationMs: Int
    var session: String
}

enum PlexAPI {
    static let accountBase = "https://plex.tv"
    static let clientsBase = "https://clients.plex.tv"
    static let linkPage = "plex.tv/link"
    private static let plays = ServerLinkRegistry<PlexPlay>()

    static var headers: [String: String] {
        [
            "Accept": "application/json",
            "X-Plex-Product": "Meowio",
            "X-Plex-Version": ServerHTTP.appVersion,
            "X-Plex-Client-Identifier": ServerHTTP.deviceId,
            "X-Plex-Platform": "tvOS",
            "X-Plex-Device": "Apple TV",
            "X-Plex-Device-Name": "Meowio on Apple TV",
            "X-Plex-Provides": "player"
        ]
    }

    private static func call(_ method: String = "GET", _ base: String, _ path: String, query: [String: String?] = [:],
                             token: String?, extra: [String: String] = [:], timeout: TimeInterval = 15) async throws -> Data {
        guard let url = ServerHTTP.url(base, path, query) else { throw MediaServerError.badAddress }
        var h = headers.merging(extra) { _, new in new }
        if let token { h["X-Plex-Token"] = token }
        do {
            return try await ServerHTTP.send(method, url: url, headers: h, timeout: timeout).0
        } catch let error as ServerHTTPError {
            throw error.status == 401 || error.status == 403 ? MediaServerError.rejected : error
        }
    }

    // Sign-in

    static func startPin() async throws -> PlexPin {
        let reply = ServerJSON.parse(try await call("POST", accountBase, "/api/v2/pins", token: nil))
        guard let id = reply.i("id"), id > 0, let code = reply.text("code") else { throw MediaServerError.unreachable }
        let seconds = max(reply.i("expiresIn") ?? 900, 60)
        return PlexPin(id: id, code: code, expires: Date().addingTimeInterval(TimeInterval(seconds)))
    }

    /// The account token once the code was entered at plex.tv/link; nil while waiting (plex.tv can answer 404 for a moment).
    static func checkPin(_ pin: PlexPin) async -> String? {
        guard let data = try? await call(accountBase, "/api/v2/pins/\(pin.id)", token: nil) else { return nil }
        return ServerJSON.parse(data).text("authToken")
    }

    static func account(_ token: String) async throws -> PlexAccount {
        let reply = ServerJSON.parse(try await call(accountBase, "/api/v2/user", token: token))
        guard let id = reply.s("id") else { throw MediaServerError.rejected }
        return PlexAccount(token: token, id: id, uuid: reply.s("uuid") ?? id,
                           name: reply.text("title") ?? reply.text("username") ?? "Plex")
    }

    /// The account's Plex Home users; just the account itself when it has no Plex Home.
    static func homeUsers(_ owner: PlexAccount) async -> [PlexHomeUser] {
        let data = try? await call(clientsBase, "/api/v2/home/users", token: owner.token)
        let users = (data.map { ServerJSON.parse($0).list("users") } ?? []).compactMap { u -> PlexHomeUser? in
            guard let uuid = u.text("uuid") else { return nil }
            return PlexHomeUser(id: u.s("id") ?? uuid, uuid: uuid, name: u.text("title") ?? u.text("username") ?? "User",
                                hasPin: u.b("protected"))
        }
        return users.isEmpty ? [PlexHomeUser(id: owner.id, uuid: owner.uuid, name: owner.name, hasPin: false)] : users
    }

    /// The Plex Home user's own account, or nil when the PIN is wrong.
    static func switchUser(_ owner: PlexAccount, to user: PlexHomeUser, pin: String?) async throws -> PlexAccount? {
        if user.uuid == owner.uuid && !user.hasPin { return owner }
        let entered = pin?.trimmingCharacters(in: .whitespaces)
        let data: Data
        do {
            data = try await call("POST", clientsBase, "/api/v2/home/users/\(user.uuid)/switch",
                                  query: ["pin": (entered?.isEmpty ?? true) ? nil : entered], token: owner.token)
        } catch MediaServerError.rejected {
            return nil
        } catch let error as ServerHTTPError where error.status == 422 {
            return nil
        }
        guard let token = ServerJSON.parse(data).text("authToken") else { throw MediaServerError.rejected }
        return PlexAccount(token: token, id: user.id, uuid: user.uuid, name: user.name)
    }

    /// The Plex Media Servers the token can use, each with the token it takes.
    static func servers(_ token: String) async throws -> [PlexServerChoice] {
        let data = try await call(clientsBase, "/api/v2/resources",
                                  query: ["includeHttps": "1", "includeRelay": "1", "includeIPv6": "1"], token: token)
        return ServerJSON.parseList(data).compactMap { r in
            let provides = (r.s("provides") ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            guard provides.contains("server"), let machine = r.text("clientIdentifier") else { return nil }
            let owned = r.b("owned")
            guard let serverToken = r.text("accessToken") ?? (owned ? token : nil) else { return nil }
            var addresses: [PlexAddress] = []
            for c in r.list("connections") {
                let local = c.b("local"), relay = c.b("relay")
                if var uri = c.text("uri") {
                    while uri.hasSuffix("/") { uri.removeLast() }
                    addresses.append(PlexAddress(uri: uri, local: local, relay: relay))
                }
                // Many routers refuse the *.plex.direct names of home addresses, so the plain address is tried too.
                if local, !relay, let a = c.text("address"), let port = c.i("port"), port > 0 {
                    let host = (c.b("IPv6") || a.contains(":")) ? "[\(a)]" : a
                    addresses.append(PlexAddress(uri: "http://\(host):\(port)", local: true, relay: false))
                }
            }
            var seen = Set<String>()
            addresses = addresses.filter { seen.insert($0.uri).inserted }
            return PlexServerChoice(id: machine, name: r.text("name") ?? String(machine.prefix(8)), owned: owned,
                                    token: serverToken, addresses: addresses)
        }
    }

    static func machineId(_ address: String) async -> String? {
        guard let data = try? await call(address, "/identity", token: nil, timeout: 8) else { return nil }
        return ServerJSON.parse(data)["MediaContainer"].text("machineIdentifier")
    }

    /// Tries every address at once and keeps the best one that answers as this server: the home network first, then
    /// the internet, then Plex Relay. Unencrypted addresses are only used on the home network.
    static func reach(_ server: PlexServerChoice) async -> String? {
        let candidates = server.addresses.filter { $0.uri.lowercased().hasPrefix("https://") || $0.local }
        var answered: [PlexAddress] = []
        await withTaskGroup(of: PlexAddress?.self) { group in
            for address in candidates {
                group.addTask { await machineId(address.uri) == server.id ? address : nil }
            }
            for await address in group { if let address { answered.append(address) } }
        }
        func rank(_ a: PlexAddress) -> (Int, Int) {
            (a.relay ? 2 : a.local ? 0 : 1, a.uri.lowercased().hasPrefix("https://") ? 0 : 1)
        }
        return answered.min { rank($0) < rank($1) }?.uri
    }

    // Browsing

    private static func get(_ c: ServerConnection, _ path: String, _ query: [String: String?] = [:]) async throws -> ServerJSON {
        ServerJSON.parse(try await call(c.address, path, query: query, token: c.token))["MediaContainer"]
    }

    static func libraries(_ c: ServerConnection) async throws -> [ServerLibrary] {
        try await get(c, "/library/sections").list("Directory").compactMap { d in
            guard let key = d.text("key") else { return nil }
            switch d.s("type") {
            case "movie": return ServerLibrary(id: key, name: d.text("title") ?? "Movies", kind: "movie")
            case "show": return ServerLibrary(id: key, name: d.text("title") ?? "Shows", kind: "series")
            default: return nil
            }
        }
    }

    private static func sectionQuery(_ library: ServerLibrary, size: Int) -> [String: String?] {
        ["type": library.kind == "movie" ? "1" : "2", "includeGuids": "1",
         "X-Plex-Container-Start": "0", "X-Plex-Container-Size": String(size)]
    }

    static func recent(_ c: ServerConnection, library: ServerLibrary, limit: Int) async -> [ServerItem] {
        var query = sectionQuery(library, size: limit)
        query["sort"] = "addedAt:desc"
        guard let container = try? await get(c, "/library/sections/\(library.id)/all", query) else { return [] }
        return container.list("Metadata").compactMap { item(c, $0) }
    }

    /// imdb://tt0133093 from the Guid list, or the older agents' com.plexapp.agents.imdb://tt0133093?lang=en.
    private static func imdb(_ m: ServerJSON) -> String? {
        for guid in m.list("Guid") {
            if let id = guid.s("id")?.lowercased(), id.hasPrefix("imdb://") {
                let value = String(id.dropFirst("imdb://".count))
                if value.hasPrefix("tt") { return value }
            }
        }
        if let legacy = m.s("guid")?.lowercased(), legacy.hasPrefix("com.plexapp.agents.imdb://") {
            let value = legacy.dropFirst("com.plexapp.agents.imdb://".count).split(separator: "?").first.map(String.init) ?? ""
            if value.hasPrefix("tt") { return value }
        }
        return nil
    }

    private static func image(_ c: ServerConnection, _ path: String?, width: Int, height: Int) -> String? {
        guard let path, !path.isEmpty else { return nil }
        if path.lowercased().hasPrefix("http") { return path }
        return ServerHTTP.url(c.address, "/photo/:/transcode", [
            "width": String(width), "height": String(height), "minSize": "1", "upscale": "1",
            "url": path, "X-Plex-Token": c.token
        ])?.absoluteString
    }

    private static func item(_ c: ServerConnection, _ m: ServerJSON) -> ServerItem? {
        guard let key = m.text("ratingKey") else { return nil }
        let episode = m.s("type") == "episode"
        return ServerItem(
            id: key, name: m.text("title"), overview: m.text("summary"), year: m.i("year"),
            rating: m.d("audienceRating") ?? m.d("rating"), genres: m.list("Genre").compactMap { $0.text("tag") },
            poster: image(c, m.s("thumb"), width: episode ? 640 : 400, height: episode ? 360 : 600),
            backdrop: image(c, m.s("art"), width: 1280, height: 720),
            season: m.i("parentIndex"), episode: m.i("index"), released: m.text("originallyAvailableAt"), imdb: imdb(m))
    }

    static func lookup(_ c: ServerConnection, library: ServerLibrary, imdb wanted: String, name: String?) async -> [String] {
        guard let name = name?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return [] }
        var query = sectionQuery(library, size: 50)
        query["title"] = name
        guard let container = try? await get(c, "/library/sections/\(library.id)/all", query) else { return [] }
        return container.list("Metadata").filter { imdb($0) == wanted.lowercased() }.compactMap { $0.text("ratingKey") }
    }

    private static func leaves(_ c: ServerConnection, _ show: String) async -> [ServerJSON] {
        (try? await get(c, "/library/metadata/\(show)/allLeaves"))?.list("Metadata") ?? []
    }

    static func episodeId(_ c: ServerConnection, seriesId: String, season: Int, episode: Int) async -> String? {
        await leaves(c, seriesId).first { $0.i("parentIndex") == season && $0.i("index") == episode }?.text("ratingKey")
    }

    static func allEpisodes(_ c: ServerConnection, seriesId: String) async -> [ServerItem] {
        await leaves(c, seriesId).compactMap { item(c, $0) }
    }

    /// One stream per version of the item: the file itself, which mpv opens.
    static func streams(_ c: ServerConnection, itemId: String) async -> [StreamItem] {
        guard let m = try? await get(c, "/library/metadata/\(itemId)", ["includeGuids": "1"]).list("Metadata").first else { return [] }
        let key = m.text("key") ?? "/library/metadata/\(itemId)"
        let medias = m.list("Media")
        return medias.compactMap { media -> StreamItem? in
            guard let part = media.list("Part").first, let partId = part.s("id") else { return nil }
            let path = part.s("key").flatMap { $0.hasPrefix("/") ? $0 : nil } ?? "/library/parts/\(partId)/file"
            guard let url = ServerHTTP.url(c.address, path, ["X-Plex-Token": c.token])?.absoluteString else { return nil }
            let rawRes = (media.s("videoResolution") ?? "").lowercased()
            let res = rawRes == "4k" ? "2160p" : rawRes.isEmpty ? "" : (Int(rawRes) != nil ? "\(rawRes)p" : rawRes.uppercased())
            let codec = (media.s("videoCodec") ?? "").uppercased()
            let version = medias.count > 1 ? (media.text("title") ?? m.text("editionTitle")) : nil
            let file = ServerHTTP.fileName(part.s("file"))
            let container = (media.s("container") ?? "").lowercased()
            let audio = (media.s("audioCodec") ?? "").uppercased()
            let name = [res, codec, version ?? ""].filter { !$0.isEmpty }.joined(separator: " ")
            let hints = StreamItem.BehaviorHints(notWebReady: !["mp4", "m4v", "mov"].contains(container),
                                                 filename: file, bingeGroup: nil, proxyHeaders: nil)
            var stream = StreamItem(name: name.isEmpty ? "Server copy" : name, title: file,
                                    description: [ServerHTTP.sizeText(part.d("size")), audio, container == "matroska" ? "MKV" : container.uppercased()]
                                        .filter { !$0.isEmpty }.joined(separator: " · "),
                                    url: url, infoHash: nil, fileIdx: nil, behaviorHints: hints, addonName: c.sourceLabel)
            stream.isServer = true
            plays.set(url, PlexPlay(address: c.address, token: c.token, ratingKey: m.text("ratingKey") ?? itemId, key: key,
                                    durationMs: m.i("duration") ?? media.i("duration") ?? 0, session: UUID().uuidString.lowercased()))
            return stream
        }
    }

    // Playback reports

    static func reports(_ url: String) -> Bool { plays.get(url) != nil }

    /// Tells Plex what is playing, so it keeps watched marks and resume points in step.
    static func report(_ action: String, url: String, seconds: Double, paused: Bool) {
        guard let play = plays.get(url) else { return }
        let state = action == "stop" ? "stopped" : (paused || action == "pause") ? "paused" : "playing"
        Task {
            _ = try? await call(play.address, "/:/timeline", query: [
                "ratingKey": play.ratingKey, "key": play.key, "state": state,
                "time": String(Int(max(0, seconds) * 1000)),
                "duration": play.durationMs > 0 ? String(play.durationMs) : nil, "hasMDE": "1"
            ], token: play.token, extra: ["X-Plex-Session-Identifier": play.session], timeout: 10)
        }
    }
}

// MARK: - Silo

/// What a Silo connection keeps: the token pair and the chosen profile.
struct SiloCredential: Codable, Hashable {
    var access: String
    var refresh: String
    /// Seconds since 1970.
    var issuedAt: Double
    var expiresAt: Double
    var profileId: String
    var profileToken: String?
}

struct SiloServerInfo {
    var address: String
    var serverId: String
    var name: String
    var passwordSignIn: Bool
    var codeSignIn: Bool
    var cancelsCodes: Bool
}

struct SiloCode {
    var deviceCode: String
    var userCode: String
    var link: String
    var linkWithCode: String
    var expires: Date
    var interval: Double

    /// Silo's eight digit codes read best in two groups of four.
    var shown: String {
        let digits = userCode.filter(\.isNumber)
        return digits.count == 8 ? "\(digits.prefix(4)) \(digits.suffix(4))" : userCode
    }
}

struct SiloTokens {
    var access: String
    var refresh: String
    var issuedAt: Double
    var expiresAt: Double
    var userId: String
    var userName: String
}

struct SiloProfile: Identifiable, Hashable {
    var id: String
    var name: String
    var hasPin: Bool
    var isChild: Bool
}

enum SiloCodeStatus {
    case waiting(pollAfter: Double)
    case approved(SiloTokens)
    case ended(String)
}

enum SiloError: LocalizedError {
    case notSilo, temporaryPassword, passwordOff, tooManyTries, codeUnavailable

    var errorDescription: String? {
        switch self {
        case .notSilo: return "No Silo server answered at this address."
        case .temporaryPassword: return "Change your temporary password in Silo's web app first, then sign in again."
        case .passwordOff: return "This server has password sign-in turned off. Use a code instead."
        case .tooManyTries: return "Too many attempts. Wait a moment and try again."
        case .codeUnavailable: return "This server doesn't offer sign-in with a code."
        }
    }
}

private struct SiloPending {
    var connection: ServerConnection
    var itemId: String
    var fileId: String
}

private final class SiloActive {
    let connection: ServerConnection
    let sessionId: String
    let installationId: String
    let offset: Double
    private var sequence = 0
    private let lock = NSLock()

    init(connection: ServerConnection, sessionId: String, installationId: String, offset: Double) {
        self.connection = connection
        self.sessionId = sessionId
        self.installationId = installationId
        self.offset = offset
    }

    func next() -> Int {
        lock.lock()
        defer { lock.unlock() }
        sequence += 1
        return sequence
    }
}

/// Keeps each Silo sign-in's access token fresh: renewed once half its life has passed, from the refresh token.
actor SiloTokenKeeper {
    static let shared = SiloTokenKeeper()
    private var latest: [String: SiloCredential] = [:]
    private var renewing: [String: Task<SiloCredential?, Never>] = [:]

    func credential(for c: ServerConnection, force: Bool = false) async -> SiloCredential? {
        guard var cred = latest[c.id] ?? c.silo else { return nil }
        if let saved = c.silo, saved.issuedAt > cred.issuedAt { cred = saved }
        let now = Date().timeIntervalSince1970
        let half = max((cred.expiresAt - cred.issuedAt) / 2, 60)
        if !force, !cred.access.isEmpty, now < cred.expiresAt - half { return cred }
        if let task = renewing[c.id] { return await task.value }
        let address = c.address, id = c.id, current = cred
        let task = Task { await SiloAPI.refresh(address: address, current) }
        renewing[id] = task
        let fresh = await task.value
        renewing[id] = nil
        if let fresh {
            latest[id] = fresh
            SiloAPI.renewed(id, fresh)
            return fresh
        }
        return !force && now < cred.expiresAt ? cred : nil
    }
}

enum SiloAPI {
    static let deviceName = "Meowio on Apple TV"
    /// Saves a renewed token pair with its connection; set by the media server store.
    static var onRenew: ((String, SiloCredential) -> Void)?
    private static let pending = ServerLinkRegistry<SiloPending>()
    private static let active = ServerLinkRegistry<SiloActive>()

    static var deviceHeaders: [String: String] {
        ["X-Device-ID": ServerHTTP.deviceId, "X-Silo-Device-Id": ServerHTTP.deviceId,
         "X-Client-Name": "Meowio", "X-Client-Version": ServerHTTP.appVersion]
    }

    private static func call(_ method: String = "GET", _ address: String, _ path: String, query: [String: String?] = [:],
                             headers: [String: String] = [:], json: Any? = nil) async throws -> (Data, HTTPURLResponse) {
        guard let url = ServerHTTP.url(address, path, query) else { throw MediaServerError.badAddress }
        var h = ["Accept": "application/json", "User-Agent": deviceName].merging(headers) { _, new in new }
        var body: Data?
        if let json {
            body = try? JSONSerialization.data(withJSONObject: json)
            h["Content-Type"] = "application/json"
        }
        return try await ServerHTTP.send(method, url: url, headers: h, body: body)
    }

    static func renewed(_ id: String, _ credential: SiloCredential) {
        DispatchQueue.main.async { onRenew?(id, credential) }
    }

    // Sign-in

    /// The server at a typed address once it answers as Silo's own API, following a move to https on the same host.
    static func findServer(_ typed: String) async throws -> SiloServerInfo {
        var value = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        while value.hasSuffix("/") { value.removeLast() }
        guard !value.isEmpty else { throw MediaServerError.badAddress }
        let sites = value.contains("://") ? [value] : ["https://" + value, "http://" + value]
        var lastError: Error = MediaServerError.unreachable
        for site in sites {
            guard let host = URL(string: site)?.host, !host.isEmpty else { throw MediaServerError.badAddress }
            do {
                let (data, response) = try await call(site, "/api/v2/system/info")
                guard ServerJSON.parse(data).i("api_major") == 2 else { throw SiloError.notSilo }
                var address = site
                if let final = response.url?.absoluteString, let r = final.range(of: "/api/v2/system/info") {
                    let moved = String(final[..<r.lowerBound])
                    if URL(string: moved)?.host == host, !(site.hasPrefix("https") && moved.hasPrefix("http:")) { address = moved }
                }
                return try await describe(address)
            } catch is ServerHTTPError {
                throw SiloError.notSilo
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    private static func describe(_ address: String) async throws -> SiloServerInfo {
        let serverId: String
        do {
            serverId = ServerJSON.parse(try await call(address, "/api/v2/system/identity").0).text("server_id") ?? address
        } catch let e as ServerHTTPError where e.status == 404 {
            serverId = address
        }
        let providers = ServerJSON.parse(try await call(address, "/api/v2/auth/providers").0)
        let branding = (try? await call(address, "/api/v2/theme/branding")).map { ServerJSON.parse($0.0) }
        let capability = (try? await call(address, "/api/v2/auth/device/capability")).map { ServerJSON.parse($0.0) }
        return SiloServerInfo(
            address: address, serverId: serverId,
            name: branding?.text("server_name") ?? "Silo",
            passwordSignIn: providers.b("password_login") || providers.list("items").contains { $0.s("mode") == "credentials" },
            codeSignIn: capability == nil || capability?.s("state") == "available",
            cancelsCodes: capability.map { $0.raw["cancel"] == nil ? true : $0.b("cancel") } ?? true)
    }

    static func startCode(_ server: SiloServerInfo) async throws -> SiloCode {
        let reply = ServerJSON.parse(try await call("POST", server.address, "/api/v2/auth/device/start",
                                                    json: ["device_name": deviceName, "device_platform": "android-tv"]).0)
        guard let device = reply.text("device_code"), let user = reply.text("user_code") else { throw SiloError.codeUnavailable }
        let link = reply.text("verification_uri") ?? server.address
        return SiloCode(deviceCode: device, userCode: user, link: link,
                        linkWithCode: reply.text("verification_uri_complete") ?? link,
                        expires: Date().addingTimeInterval(Double(max(reply.i("expires_in") ?? 900, 1))),
                        interval: Double(max(reply.i("interval") ?? 3, 1)))
    }

    static func pollCode(_ server: SiloServerInfo, _ code: SiloCode) async throws -> SiloCodeStatus {
        let reply: ServerJSON
        do {
            reply = ServerJSON.parse(try await call("POST", server.address, "/api/v2/auth/device/poll",
                                                    json: ["device_code": code.deviceCode]).0)
        } catch let e as ServerHTTPError where e.status == 404 {
            return .ended("This code ran out. Get a new one.")
        }
        let after = max(reply.d("poll_after") ?? 0, code.interval)
        switch reply.s("status") {
        case "approved":
            guard let tokens = try? tokens(reply["tokens"]) else { return .ended("This code was already used.") }
            return .approved(tokens)
        case "denied": return .ended("This sign-in was declined.")
        case "canceled": return .ended("This sign-in was cancelled.")
        case "consumed": return .ended("This code was already used.")
        case "expired": return .ended("This code ran out. Get a new one.")
        default: return .waiting(pollAfter: after)
        }
    }

    /// An abandoned code can't be approved later.
    static func cancelCode(_ server: SiloServerInfo, _ code: SiloCode) async {
        guard server.cancelsCodes else { return }
        _ = try? await call("POST", server.address, "/api/v2/auth/device/cancel", json: ["device_code": code.deviceCode])
    }

    static func passwordSignIn(_ server: SiloServerInfo, user: String, password: String) async throws -> SiloTokens {
        let reply: ServerJSON
        do {
            reply = ServerJSON.parse(try await call("POST", server.address, "/api/v2/auth/login",
                                                    json: ["username": user.trimmingCharacters(in: .whitespaces), "password": password]).0)
        } catch let e as ServerHTTPError {
            throw signInError(e)
        }
        return try tokens(reply)
    }

    private static func signInError(_ e: ServerHTTPError) -> Error {
        switch e.code {
        case "local_login_disabled": return SiloError.passwordOff
        case "password_change_required": return SiloError.temporaryPassword
        default: break
        }
        if e.status == 429 { return SiloError.tooManyTries }
        if e.status == 401 || e.status == 403 { return MediaServerError.rejected }
        return MediaServerError.unreachable
    }

    private static func tokens(_ reply: ServerJSON) throws -> SiloTokens {
        guard let access = reply.text("access_token"), let refresh = reply.text("refresh_token") else { throw MediaServerError.rejected }
        let user = reply["user"]
        if user.b("password_change_required") { throw SiloError.temporaryPassword }
        let now = Date().timeIntervalSince1970
        return SiloTokens(access: access, refresh: refresh, issuedAt: now, expiresAt: now + (reply.d("expires_in") ?? 900),
                          userId: user.s("id") ?? "", userName: user.text("username") ?? "Silo")
    }

    static func profiles(_ server: SiloServerInfo, _ tokens: SiloTokens) async throws -> [SiloProfile] {
        let reply = ServerJSON.parse(try await call(server.address, "/api/v2/profiles", headers: ["Authorization": "Bearer \(tokens.access)"]).0)
        return reply.list("items").compactMap { p in
            guard let id = p.text("id") else { return nil }
            return SiloProfile(id: id, name: p.text("name") ?? id, hasPin: p.b("has_pin"), isChild: p.b("is_child"))
        }
    }

    /// The profile's token, or nil when the PIN is wrong.
    static func verifyPin(_ server: SiloServerInfo, _ tokens: SiloTokens, _ profile: SiloProfile, pin: String) async throws -> String? {
        guard !pin.isEmpty else { return nil }
        do {
            let reply = ServerJSON.parse(try await call("POST", server.address, "/api/v2/profiles/\(profile.id)/verify-pin",
                                                        headers: ["Authorization": "Bearer \(tokens.access)"], json: ["pin": pin]).0)
            return reply.b("valid") ? reply.text("profile_token") : nil
        } catch let e as ServerHTTPError where e.status == 429 {
            throw SiloError.tooManyTries
        } catch let e as ServerHTTPError where (400..<500).contains(e.status) {
            return nil
        }
    }

    static func connection(_ server: SiloServerInfo, _ tokens: SiloTokens, _ profile: SiloProfile, profileToken: String?,
                           profileCount: Int) -> ServerConnection {
        ServerConnection(
            id: ServerHTTP.newConnectionId(), provider: "silo", name: server.name, address: server.address,
            userId: "\(tokens.userId)/\(profile.id)",
            userName: profileCount > 1 ? "\(tokens.userName) · \(profile.name)" : tokens.userName,
            token: "", libraries: [],
            silo: SiloCredential(access: tokens.access, refresh: tokens.refresh, issuedAt: tokens.issuedAt,
                                 expiresAt: tokens.expiresAt, profileId: profile.id,
                                 profileToken: profile.hasPin ? profileToken : nil))
    }

    /// Ends a sign-in, so it doesn't stay in the server's list of devices.
    static func signOut(address: String, access: String) async {
        _ = try? await call("POST", address, "/api/v2/auth/logout", headers: ["Authorization": "Bearer \(access)"])
    }

    static func signOut(_ c: ServerConnection) async {
        guard let cred = await SiloTokenKeeper.shared.credential(for: c) else { return }
        await signOut(address: c.address, access: cred.access)
    }

    static func refresh(address: String, _ cred: SiloCredential) async -> SiloCredential? {
        let started = Date().timeIntervalSince1970
        guard let data = try? await call("POST", address, "/api/v2/auth/refresh", json: ["refresh_token": cred.refresh]).0 else { return nil }
        let reply = ServerJSON.parse(data)
        guard let access = reply.text("access_token") else { return nil }
        var next = cred
        next.access = access
        next.refresh = reply.text("refresh_token") ?? cred.refresh
        next.issuedAt = started
        next.expiresAt = started + (reply.d("expires_in") ?? 900)
        return next
    }

    // Requests on a connection

    private static func auth(_ c: ServerConnection, force: Bool = false) async -> [String: String]? {
        guard let cred = await SiloTokenKeeper.shared.credential(for: c, force: force) else { return nil }
        var h = ["Authorization": "Bearer \(cred.access)", "X-Profile-Id": cred.profileId]
        if let t = cred.profileToken { h["X-Profile-Token"] = t }
        return h
    }

    /// A signed-in request; a refused token is renewed once and the request sent again.
    private static func send(_ method: String = "GET", _ c: ServerConnection, _ path: String, query: [String: String?] = [:],
                             json: Any? = nil, device: Bool = false) async throws -> ServerJSON {
        for attempt in 0..<2 {
            guard var h = await auth(c, force: attempt > 0) else { throw MediaServerError.rejected }
            if device { h.merge(deviceHeaders) { _, new in new } }
            do {
                return ServerJSON.parse(try await call(method, c.address, path, query: query, headers: h, json: json).0)
            } catch let e as ServerHTTPError where e.status == 401 && attempt == 0 {
                continue
            }
        }
        throw MediaServerError.rejected
    }

    private static func art(_ c: ServerConnection, _ url: String?) -> String? {
        guard let value = url?.trimmingCharacters(in: .whitespaces), !value.isEmpty else { return nil }
        if value.lowercased().hasPrefix("http://") || value.lowercased().hasPrefix("https://") { return value }
        return value.hasPrefix("/") ? c.address + value : nil
    }

    private static func item(_ c: ServerConnection, _ m: ServerJSON) -> ServerItem? {
        guard let id = m.text("content_id") else { return nil }
        let date = m.text("release_date") ?? m.text("first_air_date")
        return ServerItem(
            id: id, name: m.text("title"), overview: m.text("overview"),
            year: m.i("year") ?? date.flatMap { Int($0.prefix(4)) }, rating: m.d("rating_imdb"),
            genres: m.strings("genres"), poster: art(c, m.text("poster_url") ?? m.text("series_poster_url")),
            backdrop: art(c, m.text("backdrop_url") ?? m.text("series_backdrop_url")),
            season: m.i("season_number"), episode: m.i("episode_number"), released: m.text("air_date") ?? date,
            imdb: m.text("imdb_id")?.lowercased())
    }

    private static func kind(_ library: ServerLibrary) -> String { library.kind == "movie" ? "movie" : "series" }

    static func libraries(_ c: ServerConnection) async throws -> [ServerLibrary] {
        let reply = try await send(c, "/api/v2/user/libraries")
        return reply.list("items").sorted { ($0.i("sort_order") ?? 0) < ($1.i("sort_order") ?? 0) }.compactMap { l in
            guard let id = l.text("id") else { return nil }
            switch (l.s("type") ?? "").lowercased() {
            case "movies", "movie": return ServerLibrary(id: id, name: l.text("name") ?? "Movies", kind: "movie")
            case "series", "shows", "tv", "tvshows": return ServerLibrary(id: id, name: l.text("name") ?? "Shows", kind: "series")
            default: return nil
            }
        }
    }

    static func recent(_ c: ServerConnection, library: ServerLibrary, limit: Int) async -> [ServerItem] {
        let reply = try? await send(c, "/api/v2/catalog", query: [
            "source": "query", "library_id": library.id, "type": kind(library), "sort": "-added_at",
            "image_size": "medium", "limit": String(min(max(limit, 1), 200))
        ])
        return reply?.list("items").compactMap { item(c, $0) } ?? []
    }

    /// Silo names most titles after an external id, so a title is usually one read away; otherwise a title search.
    static func lookup(_ c: ServerConnection, library: ServerLibrary, imdb: String, name: String?) async -> [String] {
        let wanted = imdb.lowercased()
        let type = kind(library)
        if let m = try? await send(c, "/api/v2/catalog/items/\(type)-imdb-\(wanted)", query: ["image_size": "small"]),
           m.s("type")?.lowercased() == type, let id = m.text("content_id") {
            return [id]
        }
        guard let name = name?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return [] }
        let reply = try? await send(c, "/api/v2/catalog", query: [
            "source": "query", "library_id": library.id, "type": type, "q": name, "image_size": "small", "limit": "10"
        ])
        return (reply?.list("items") ?? []).filter { $0.text("imdb_id")?.lowercased() == wanted }.compactMap { $0.text("content_id") }
    }

    private static func seasonEpisodes(_ c: ServerConnection, _ seriesId: String, _ season: Int) async -> [ServerJSON] {
        (try? await send(c, "/api/v2/catalog/series/\(seriesId)/seasons/\(season)/episodes", query: ["image_size": "small"]))?
            .list("items").filter { $0.text("content_id") != nil } ?? []
    }

    static func episodeId(_ c: ServerConnection, seriesId: String, season: Int, episode: Int) async -> String? {
        let parts = seriesId.split(separator: "-")
        if parts.count == 3, parts[0] == "series", ["tmdb", "tvdb", "imdb"].contains(String(parts[1])) {
            let guess = "episode-\(parts[1])-\(parts[2])-\(season)-\(episode)"
            if let m = try? await send(c, "/api/v2/catalog/items/\(guess)", query: ["image_size": "small"]),
               m.s("type")?.lowercased() == "episode", m.i("season_number") == season, m.i("episode_number") == episode {
                return m.text("content_id")
            }
        }
        let matches = await seasonEpisodes(c, seriesId, season).filter { $0.i("episode_number") == episode }
        return matches.count == 1 ? matches[0].text("content_id") : nil
    }

    static func allEpisodes(_ c: ServerConnection, seriesId: String) async -> [ServerItem] {
        guard let seasons = try? await send(c, "/api/v2/catalog/series/\(seriesId)/seasons", query: ["include_artwork": "false"]) else { return [] }
        let numbers = Array(Set(seasons.list("items").compactMap { $0.i("season_number") })).sorted()
        var out: [ServerItem] = []
        for number in numbers {
            for ep in await seasonEpisodes(c, seriesId, number) {
                guard let id = ep.text("content_id") else { continue }
                out.append(ServerItem(id: id, name: ep.text("title"), overview: ep.text("overview"), year: nil, rating: nil,
                                      genres: nil, poster: art(c, ep.text("still_url")), backdrop: nil,
                                      season: ep.i("season_number") ?? number, episode: ep.i("episode_number"),
                                      released: ep.text("air_date"), imdb: nil))
            }
        }
        return out
    }

    /// One source per version of the item. Silo plans the actual playback when one is picked (see [start]).
    static func streams(_ c: ServerConnection, itemId: String) async -> [StreamItem] {
        guard let state = try? await send(c, "/api/v2/watch/\(itemId)", device: true) else { return [] }
        let versions = state.list("versions").filter { $0.text("file_id") != nil }
        return versions.compactMap { v -> StreamItem? in
            guard let fileId = v.text("file_id") else { return nil }
            var comps = URLComponents()
            comps.scheme = "silo"
            comps.host = c.id
            comps.path = "/" + itemId
            comps.queryItems = [URLQueryItem(name: "file", value: fileId)]
            guard let url = comps.url?.absoluteString else { return nil }
            let codec: String
            switch (v.s("codec_video") ?? "").lowercased() {
            case "hevc", "h265": codec = "HEVC"
            case "h264", "avc": codec = "AVC"
            default: codec = (v.s("codec_video") ?? "").uppercased()
            }
            let video = v.list("video_tracks").first
            let range: String
            if let dv = video?.i("dv_profile") { range = "DV P\(dv)" }
            else if video?.s("video_range_type")?.lowercased().contains("hdr10plus") == true { range = "HDR10+" }
            else if video?.s("video_range_type")?.uppercased() == "HLG" { range = "HLG" }
            else { range = v.b("hdr") ? "HDR10" : "" }
            let edition = versions.count > 1 ? (v.text("edition_raw") ?? "") : ""
            let name = [v.s("resolution") ?? "", codec, range, edition].filter { !$0.isEmpty }.joined(separator: " ")
            let audio = (v.list("audio_tracks").first { $0.b("default") } ?? v.list("audio_tracks").first)?.s("codec")?.uppercased() ?? ""
            let container = (v.s("container") ?? "").uppercased()
            let file = v.text("file_name") ?? ""
            let hints = StreamItem.BehaviorHints(notWebReady: true, filename: file, bingeGroup: nil, proxyHeaders: nil)
            var stream = StreamItem(name: name.isEmpty ? "Server copy" : name, title: file,
                                    description: [ServerHTTP.sizeText(v.d("file_size")), audio, container == "MATROSKA" ? "MKV" : container]
                                        .filter { !$0.isEmpty }.joined(separator: " · "),
                                    url: url, infoHash: nil, fileIdx: nil, behaviorHints: hints, addonName: c.sourceLabel)
            stream.isServer = true
            pending.set(url, SiloPending(connection: c, itemId: itemId, fileId: fileId))
            return stream
        }
    }

    // Playback

    /// A Silo source that still has to be started on the server.
    static func needsStart(_ stream: StreamItem) -> Bool {
        guard stream.isServer, let url = stream.url, url.hasPrefix("silo://") else { return false }
        return pending.get(url) != nil
    }

    static func reports(_ url: String) -> Bool { url.hasPrefix("silo://") }

    /// The server's playback installation, which every playback call carries.
    private static func installation(_ c: ServerConnection) async throws -> String {
        let doc = try await send(c, "/api/v2/playback/capabilities")
        guard doc.s("state") == "available", doc.b("allowed"), doc.ints("protocol_versions").contains(3),
              doc.strings("features").contains("sequenced_progress_v1"), let id = doc.text("installation_id") else {
            throw MediaServerError.notSupported
        }
        return id
    }

    /// Asks Silo to play the version: the original file when it can, otherwise a conversion. Returns the link and the
    /// headers it needs.
    static func start(_ stream: StreamItem) async -> (url: URL, headers: [String: String])? {
        guard let raw = stream.url, let p = pending.get(raw),
              let cred = await SiloTokenKeeper.shared.credential(for: p.connection),
              let installationId = try? await installation(p.connection) else { return nil }
        let c = p.connection
        let attempt = UUID().uuidString.lowercased()
        var body = startBody(installationId: installationId, fileId: p.fileId, profileId: cred.profileId, attemptId: attempt, legacy: false)
        var decision: ServerJSON
        do {
            decision = try await send("POST", c, "/api/v2/playback/start", json: body, device: true)
        } catch let e as ServerHTTPError where e.status == 422 || e.code == "validation_failed" {
            // Servers from before mid October 2026 want the older quality fields.
            body = startBody(installationId: installationId, fileId: p.fileId, profileId: cred.profileId, attemptId: attempt, legacy: true)
            guard let d = try? await send("POST", c, "/api/v2/playback/start", json: body, device: true) else { return nil }
            decision = d
        } catch {
            return nil
        }
        guard decision.s("outcome") == "playable", var sessionId = decision.text("session_id") else { return nil }
        var plan = decision["playback_plan"]
        guard plan["stream"].text("url") != nil else { return nil }
        // A copy remux can only be moved by asking Silo again for every seek, so another route is asked for.
        var attempted: [String] = []
        while !plan["timeline"].b("can_seek_anywhere") && plan["timeline"].raw["can_seek_anywhere"] != nil,
              (plan.s("delivery") ?? "").hasPrefix("server_remux"), (plan["source"].d("duration_seconds") ?? 0) > 0,
              let planId = plan.text("plan_id"), let key = plan.text("plan_attempt_key"), attempted.count < 3 {
            attempted.append(key)
            let replan = replanBody(start: body, plan: plan, planId: planId, key: key, attempted: attempted)
            guard let next = try? await send("POST", c, "/api/v2/playback/\(sessionId)/replan", json: replan, device: true),
                  next.s("outcome") == "playable", next["playback_plan"]["stream"].text("url") != nil else { break }
            sessionId = next.text("session_id") ?? sessionId
            plan = next["playback_plan"]
        }
        guard let link = plan["stream"].text("url") else { return nil }
        let full = link.lowercased().hasPrefix("http") ? link : (link.hasPrefix("/") ? c.address + link : "")
        guard let url = URL(string: full), let headers = await auth(c) else { return nil }
        active.set(raw, SiloActive(connection: c, sessionId: sessionId, installationId: installationId,
                                   offset: plan["timeline"].d("timeline_offset_seconds") ?? 0))
        return (url, headers)
    }

    /// Reports the position to Silo, so it keeps watched marks and resume points in step.
    static func report(_ action: String, url: String, seconds: Double, paused: Bool) {
        guard let play = active.get(url), action != "start" else { return }
        let position = max(0, seconds) + play.offset
        let path = "/api/v2/playback/\(play.sessionId)"
        if action == "stop" {
            active.set(url, nil)
            let body: [String: Any] = ["installation_id": play.installationId, "stop_id": UUID().uuidString.lowercased(),
                                       "sequence": play.next(), "position": position, "is_paused": true]
            Task {
                // Silo keeps the first stop; the same stop is sent again after a lost connection.
                for attempt in 0..<3 {
                    do {
                        _ = try await send("DELETE", play.connection, path, json: body, device: true)
                        return
                    } catch MediaServerError.unreachable {
                        try? await Task.sleep(nanoseconds: UInt64(250_000_000 * (attempt + 1)))
                    } catch {
                        return
                    }
                }
            }
            return
        }
        let body: [String: Any] = ["installation_id": play.installationId, "sequence": play.next(),
                                   "position": position, "is_paused": paused || action == "pause"]
        Task { _ = try? await send("POST", play.connection, "\(path)/progress", json: body, device: true) }
    }

    private static let directVideo = ["h264", "hevc", "av1", "vp9", "vp8", "mpeg2video", "mpeg4", "vc1"]
    private static let hlsVideo = ["h264", "hevc"]
    private static let directAudio = ["aac", "ac3", "eac3", "truehd", "mlp", "dts", "flac", "alac", "opus", "vorbis", "mp3", "mp2",
                                      "pcm_s16le", "pcm_s24le", "pcm_s16be", "pcm_s24be", "pcm_bluray", "pcm_dvd"]
    private static let hlsAudio = ["aac", "ac3", "eac3"]
    private static let directContainers = ["mkv", "matroska", "mp4", "m4v", "mov", "webm", "avi", "ts", "mpegts", "m2ts", "wmv", "asf", "flv", "ogg"]
    private static let hlsContainers = ["m3u8", "hls", "ts", "mp4"]

    private static func delivery(_ containers: [String], _ video: [String], _ audio: [String], direct: Bool) -> [String: Any] {
        var subtitles: [String: Any] = [:]
        subtitles["embedded_text"] = direct
        subtitles["sidecar_text"] = true
        subtitles["ass_styling"] = direct
        subtitles["embedded_bitmap"] = direct
        subtitles["sidecar_bitmap"] = false
        subtitles["font_attachments"] = false
        var d: [String: Any] = [:]
        d["enabled"] = true
        d["supported_on_device"] = true
        d["containers"] = containers
        d["video_codecs"] = video
        d["audio_decode_codecs"] = audio
        d["audio_passthrough_codecs"] = [String]()
        d["subtitles"] = subtitles
        d["features"] = direct ? [String]() : ["hls"]
        d["auth_header_refresh"] = false
        d["validated_claims"] = direct ? ["client_managed_dynamic_range_v1", "client_selected_audio_track_v1"] : [String]()
        d["transformations"] = [Any]()
        return d
    }

    /// mpv plays the original file whatever it holds; a plain HLS conversion is the fallback.
    private static func startBody(installationId: String, fileId: String, profileId: String, attemptId: String, legacy: Bool) -> [String: Any] {
        var hdr: [String: Any] = [:]
        hdr["hdr10"] = true
        hdr["hdr10_plus"] = true
        hdr["hlg"] = true
        hdr["dolby_vision_profiles"] = [4, 5, 7, 8]
        let videoAll = directVideo + ["dolby_vision"]
        var decode: [[String: Any]] = []
        for codec in directVideo {
            var entry: [String: Any] = [:]
            entry["codec"] = codec
            entry["hardware"] = true
            entry["bit_depths"] = [8, 10, 12]
            decode.append(entry)
        }
        var capabilities: [String: Any] = [:]
        capabilities["video_evidence"] = "exact"
        capabilities["audio_evidence"] = "exact"
        capabilities["codecs_video"] = videoAll
        capabilities["codecs_video_hardware"] = videoAll
        capabilities["codecs_audio"] = directAudio
        capabilities["containers"] = directContainers
        capabilities["max_resolution"] = "2160p"
        capabilities["hdr"] = true
        capabilities["hdr_details"] = hdr
        capabilities["video_decode"] = decode
        var output: [String: Any] = [:]
        output["hdr_details"] = hdr
        output["output_context_id"] = "1"
        var deliveries: [String: Any] = [:]
        deliveries["original_http"] = delivery(directContainers, directVideo, directAudio, direct: true)
        deliveries["hls"] = delivery(hlsContainers, hlsVideo, hlsAudio, direct: false)
        var context: [String: Any] = [:]
        context["protocol_version"] = 3
        context["form_factor"] = "tv"
        context["app_version"] = ServerHTTP.appVersion
        context["device"] = ["platform": "ios", "model": "Apple TV"]
        context["output"] = output
        context["deliveries"] = deliveries
        var body: [String: Any] = [:]
        body["installation_id"] = installationId
        body["protocol_version"] = 3
        body["client_features"] = ["playback_plan_v3", "direct_stream_resume_v1"]
        body["file_id"] = fileId
        body["profile_id"] = profileId
        body["playback_attempt_id"] = attemptId
        body["quality_preference"] = legacy ? "original" : "auto"
        body["subtitle_fidelity_preference"] = "preserve"
        body["start_position"] = 0
        body["client_capabilities"] = capabilities
        body["client_playback_context"] = context
        if legacy { body["metered"] = false }
        return body
    }

    private static func replanBody(start: [String: Any], plan: ServerJSON, planId: String, key: String, attempted: [String]) -> [String: Any] {
        var body: [String: Any] = [:]
        for k in ["installation_id", "protocol_version", "client_features", "playback_attempt_id", "quality_preference", "metered"] {
            if let v = start[k] { body[k] = v }
        }
        let replanId = UUID().uuidString.lowercased()
        body["operation"] = "failure_recovery"
        body["replan_request_id"] = replanId
        body["failed_plan_id"] = planId
        body["plan_attempt_id"] = replanId
        body["plan_attempt_key"] = key
        body["attempted_plan_keys"] = Array(attempted.suffix(16))
        body["attempt_count"] = min(max(attempted.count, 1), 8)
        body["position_seconds"] = 0
        var selected: [String: Any] = [:]
        for kind in ["audio", "subtitle"] {
            let track = plan["selected_tracks"][kind]
            guard let id = track.s("id") else { continue }
            var t: [String: Any] = ["id": id]
            if let index = track.i("index") { t["index"] = index }
            selected[kind] = t
        }
        body["selected_tracks"] = selected
        body["failure"] = ["classification": "player_failure"]
        body["client_capabilities"] = start["client_capabilities"]
        body["client_playback_context"] = start["client_playback_context"]
        return body
    }
}

// MARK: - Sign-in screens

struct PlexSignInView: View {
    @EnvironmentObject var servers: MediaServerStore
    @Environment(\.dismiss) private var dismiss

    private enum Step { case working, code, users, pin, servers, failed }

    @State private var step: Step = .working
    @State private var status = "Getting a code from Plex…"
    @State private var pin: PlexPin?
    @State private var owner: PlexAccount?
    @State private var users: [PlexHomeUser] = []
    @State private var chosenUser: PlexHomeUser?
    @State private var account: PlexAccount?
    @State private var choices: [PlexServerChoice] = []
    @State private var pinText = ""
    @State private var pinWrong = false

    var body: some View {
        content
            .meowioPageTitle("Plex")
            .task { await run() }
    }

    @ViewBuilder private var content: some View {
        switch step {
        case .code:
            VStack(spacing: 30) {
                if let qr = QRCode.image("https://\(PlexAPI.linkPage)") {
                    Image(uiImage: qr).interpolation(.none).resizable().frame(width: 260, height: 260)
                }
                Text("Scan the code, or go to").foregroundStyle(.secondary)
                Text(PlexAPI.linkPage).font(.title2.bold())
                Text(pin?.code ?? "").font(.system(size: 72, weight: .heavy, design: .monospaced)).kerning(8)
                HStack(spacing: 14) { ProgressView(); Text("Waiting for Plex…") }
                Text("Your Plex password is never entered in Meowio.").font(.caption).foregroundStyle(.secondary)
            }
            .padding(60)
        case .users:
            List {
                Section("Who's watching?") {
                    ForEach(users) { user in
                        Button {
                            chosenUser = user
                            if user.hasPin {
                                pinText = ""
                                pinWrong = false
                                step = .pin
                            } else {
                                Task { await choose(user, pin: nil) }
                            }
                        } label: {
                            HStack {
                                Text(user.name)
                                Spacer()
                                if user.hasPin { Text("PIN").foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
            }
        case .pin:
            List {
                Section("PIN for \(chosenUser?.name ?? "")") {
                    SecureField("PIN", text: $pinText)
                    Button("Continue") {
                        if let user = chosenUser { Task { await choose(user, pin: pinText) } }
                    }
                    .disabled(pinText.isEmpty)
                    if pinWrong { Text("Wrong PIN. Try again.").foregroundStyle(.orange) }
                }
            }
        case .servers:
            List {
                Section("Choose a server") {
                    ForEach(choices) { choice in
                        Button {
                            Task { await add(choice) }
                        } label: {
                            HStack {
                                Text(choice.name)
                                Spacer()
                                Text(choice.owned ? "Yours" : "Shared").foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        case .working:
            VStack(spacing: 20) {
                ProgressView()
                Text(status).foregroundStyle(.secondary)
            }
            .padding(60)
        case .failed:
            VStack(spacing: 24) {
                Text(status).foregroundStyle(.orange).multilineTextAlignment(.center)
                Button("Try again") { Task { await run() } }
            }
            .padding(60)
        }
    }

    private func fail(_ text: String) {
        status = text
        step = .failed
    }

    private func run() async {
        step = .working
        status = "Getting a code from Plex…"
        while !Task.isCancelled {
            guard let fresh = try? await PlexAPI.startPin() else {
                fail("Could not reach plex.tv. Check this Apple TV's internet connection.")
                return
            }
            pin = fresh
            step = .code
            while Date() < fresh.expires {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if Task.isCancelled { return }
                if let token = await PlexAPI.checkPin(fresh) {
                    await linked(token)
                    return
                }
            }
        }
    }

    private func linked(_ token: String) async {
        step = .working
        status = "Signing in…"
        guard let acct = try? await PlexAPI.account(token) else {
            fail("Plex did not accept that sign-in. Try again.")
            return
        }
        owner = acct
        users = await PlexAPI.homeUsers(acct)
        if users.count == 1, !users[0].hasPin {
            await choose(users[0], pin: nil)
        } else {
            step = .users
        }
    }

    private func choose(_ user: PlexHomeUser, pin: String?) async {
        guard let owner else { return }
        step = .working
        status = "Signing in…"
        do {
            guard let acct = try await PlexAPI.switchUser(owner, to: user, pin: pin) else {
                pinWrong = true
                pinText = ""
                step = .pin
                return
            }
            account = acct
            status = "Looking for your servers…"
            choices = try await PlexAPI.servers(acct.token)
            if choices.isEmpty {
                fail("No Plex Media Server is shared with this account.")
            } else if choices.count == 1 {
                await add(choices[0])
            } else {
                step = .servers
            }
        } catch {
            fail((error as? LocalizedError)?.errorDescription ?? "Could not sign in to Plex.")
        }
    }

    private func add(_ choice: PlexServerChoice) async {
        guard let account else { return }
        step = .working
        status = "Finding the best connection to \(choice.name)…"
        guard let address = await PlexAPI.reach(choice) else {
            fail("\(choice.name) did not answer. Make sure it is running and reachable from this Apple TV.")
            return
        }
        var conn = ServerConnection(id: ServerHTTP.newConnectionId(), provider: "plex", name: choice.name, address: address,
                                    userId: account.id, userName: account.name, token: choice.token, libraries: [],
                                    machineId: choice.id)
        conn.libraries = (try? await PlexAPI.libraries(conn)) ?? []
        servers.add(conn)
        dismiss()
    }
}

struct SiloSignInView: View {
    @EnvironmentObject var servers: MediaServerStore
    @Environment(\.dismiss) private var dismiss

    private enum Step { case address, working, code, password, profiles, pin, failed }

    @State private var step: Step = .address
    @State private var address = ""
    @State private var status = ""
    @State private var server: SiloServerInfo?
    @State private var code: SiloCode?
    @State private var codeTask: Task<Void, Never>?
    @State private var user = ""
    @State private var password = ""
    @State private var tokens: SiloTokens?
    @State private var profiles: [SiloProfile] = []
    @State private var chosen: SiloProfile?
    @State private var pinText = ""
    @State private var pinWrong = false
    @State private var message: String?

    var body: some View {
        content
            .meowioPageTitle("Silo")
            .onDisappear { stopCode() }
    }

    @ViewBuilder private var content: some View {
        switch step {
        case .address:
            List {
                Section {
                    TextField("https://silo.example.com", text: $address).autocorrectionDisabled()
                    Button("Continue") { Task { await find() } }.disabled(address.isEmpty)
                    if let message { Text(message).foregroundStyle(.orange) }
                } header: {
                    Text("Server address")
                } footer: {
                    Text("Enter the address you open Silo at in a browser. Sign in with a code or your Silo account.")
                }
            }
        case .code:
            VStack(spacing: 26) {
                if let code, let qr = QRCode.image(code.linkWithCode) {
                    Image(uiImage: qr).interpolation(.none).resizable().frame(width: 260, height: 260)
                }
                Text("Approve this Apple TV on \(server?.name ?? "Silo") from your phone or computer.").foregroundStyle(.secondary)
                Text(code?.link.replacingOccurrences(of: "https://", with: "") ?? "").font(.title3.bold())
                Text(code?.shown ?? "").font(.system(size: 64, weight: .heavy, design: .monospaced)).kerning(6)
                HStack(spacing: 14) { ProgressView(); Text("Waiting for approval…") }
                if server?.passwordSignIn == true {
                    Button("Use password") {
                        stopCode()
                        step = .password
                    }
                }
            }
            .padding(50)
        case .password:
            List {
                Section {
                    TextField("User name", text: $user).autocorrectionDisabled()
                    SecureField("Password", text: $password)
                    Button("Sign in") { Task { await signInWithPassword() } }.disabled(user.isEmpty || password.isEmpty)
                    if server?.codeSignIn == true {
                        Button("Use a code") { startCode() }
                    }
                    if let message { Text(message).foregroundStyle(.orange) }
                } header: {
                    Text("Sign in with your \(server?.name ?? "Silo") account")
                }
            }
        case .profiles:
            List {
                Section("Who's watching?") {
                    ForEach(profiles) { profile in
                        Button {
                            chosen = profile
                            if profile.hasPin {
                                pinText = ""
                                pinWrong = false
                                step = .pin
                            } else {
                                Task { await finish(profile, profileToken: nil) }
                            }
                        } label: {
                            HStack {
                                Text(profile.name)
                                Spacer()
                                if profile.isChild { Text("Kids").foregroundStyle(.secondary) }
                                if profile.hasPin { Text("PIN").foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
            }
        case .pin:
            List {
                Section("PIN for \(chosen?.name ?? "")") {
                    SecureField("PIN", text: $pinText)
                    Button("Continue") { Task { await checkPin() } }.disabled(pinText.isEmpty)
                    if pinWrong { Text("Wrong PIN. Try again.").foregroundStyle(.orange) }
                }
            }
        case .working:
            VStack(spacing: 20) {
                ProgressView()
                Text(status).foregroundStyle(.secondary)
            }
            .padding(60)
        case .failed:
            VStack(spacing: 24) {
                Text(status).foregroundStyle(.orange).multilineTextAlignment(.center)
                Button("Try again") {
                    message = nil
                    step = .address
                }
            }
            .padding(60)
        }
    }

    private func describe(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? "Could not sign in to Silo."
    }

    private func find() async {
        message = nil
        status = "Looking for Silo…"
        step = .working
        do {
            let found = try await SiloAPI.findServer(address)
            server = found
            if found.codeSignIn {
                startCode()
            } else if found.passwordSignIn {
                step = .password
            } else {
                status = "This server doesn't offer a sign-in this app can use."
                step = .failed
            }
        } catch {
            message = describe(error)
            step = .address
        }
    }

    private func stopCode() {
        codeTask?.cancel()
        codeTask = nil
        if let server, let code {
            self.code = nil
            Task { await SiloAPI.cancelCode(server, code) }
        }
    }

    private func startCode() {
        guard let server else { return }
        message = nil
        codeTask?.cancel()
        codeTask = Task { @MainActor in
            do {
                let fresh = try await SiloAPI.startCode(server)
                code = fresh
                step = .code
                var wait = fresh.interval
                while !Task.isCancelled, Date() < fresh.expires {
                    try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
                    if Task.isCancelled { return }
                    switch try await SiloAPI.pollCode(server, fresh) {
                    case .waiting(let after):
                        wait = after
                    case .approved(let t):
                        code = nil
                        await signedIn(t)
                        return
                    case .ended(let why):
                        code = nil
                        status = why
                        step = .failed
                        return
                    }
                }
                if !Task.isCancelled {
                    code = nil
                    status = "This code ran out. Get a new one."
                    step = .failed
                }
            } catch {
                if Task.isCancelled { return }
                if server.passwordSignIn {
                    message = describe(error)
                    step = .password
                } else {
                    status = describe(error)
                    step = .failed
                }
            }
        }
    }

    private func signInWithPassword() async {
        guard let server else { return }
        message = nil
        status = "Signing in…"
        step = .working
        do {
            let t = try await SiloAPI.passwordSignIn(server, user: user, password: password)
            password = ""
            await signedIn(t)
        } catch {
            message = describe(error)
            step = .password
        }
    }

    private func signedIn(_ t: SiloTokens) async {
        guard let server else { return }
        tokens = t
        status = "Loading profiles…"
        step = .working
        do {
            profiles = try await SiloAPI.profiles(server, t)
            if profiles.isEmpty {
                status = "This account has no profiles on \(server.name)."
                step = .failed
            } else if profiles.count == 1, !profiles[0].hasPin {
                await finish(profiles[0], profileToken: nil)
            } else {
                step = .profiles
            }
        } catch {
            status = describe(error)
            step = .failed
        }
    }

    private func checkPin() async {
        guard let server, let tokens, let profile = chosen else { return }
        do {
            if let token = try await SiloAPI.verifyPin(server, tokens, profile, pin: pinText) {
                await finish(profile, profileToken: token)
            } else {
                pinWrong = true
                pinText = ""
            }
        } catch {
            pinWrong = false
            status = describe(error)
            step = .failed
        }
    }

    private func finish(_ profile: SiloProfile, profileToken: String?) async {
        guard let server, let tokens else { return }
        status = "Loading libraries…"
        step = .working
        var conn = SiloAPI.connection(server, tokens, profile, profileToken: profileToken, profileCount: profiles.count)
        conn.libraries = (try? await SiloAPI.libraries(conn)) ?? []
        servers.add(conn)
        dismiss()
    }
}
