import SwiftUI

/// Small shared helpers for the Simkl and MDBList stores.
enum TrackingHTTP {
    struct Reply { var status: Int; var json: Any? }

    static func send(_ url: URL, method: String = "GET", headers: [String: String] = [:], body: Any? = nil) async -> Reply {
        var r = URLRequest(url: url)
        r.httpMethod = method
        r.timeoutInterval = 20
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("Meowio", forHTTPHeaderField: "User-Agent")
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        if let body { r.httpBody = try? JSONSerialization.data(withJSONObject: body) }
        guard let result = try? await URLSession.shared.data(for: r) else { return Reply(status: 0, json: nil) }
        let status = (result.1 as? HTTPURLResponse)?.statusCode ?? 0
        return Reply(status: status, json: try? JSONSerialization.jsonObject(with: result.0))
    }

    static func iso(_ s: Any?) -> Date {
        guard let s = s as? String else { return Date() }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: s) ?? ISO8601DateFormatter().date(from: s) ?? Date()
    }

    /// Body that names a movie or an episode by IMDb id, or nil for titles that can't be matched.
    static func item(meta: MetaItem, videoId: String?) -> [String: Any]? {
        if meta.type == "movie", meta.id.hasPrefix("tt") {
            return ["movie": ["ids": ["imdb": meta.id]]]
        }
        if meta.type == "series", let ref = EpisodeRef(videoId), ref.showId.hasPrefix("tt") {
            return ["show": ["ids": ["imdb": ref.showId]], "episode": ["season": ref.season, "number": ref.episode]]
        }
        return nil
    }

    /// Body for the history endpoints (movies / shows with seasons) shared by Simkl and MDBList.
    static func historyBody(_ item: [String: Any], watched: Bool) -> [String: Any] {
        var body: [String: Any] = [:]
        if let movie = item["movie"] as? [String: Any] {
            var m = movie
            if watched { m["watched_at"] = ISO8601DateFormatter().string(from: Date()) }
            body["movies"] = [m] as [[String: Any]]
        } else if let show = item["show"] as? [String: Any], let ep = item["episode"] as? [String: Any] {
            var s = show
            let episodes: [[String: Any]] = [["number": ep["number"] ?? 0]]
            s["seasons"] = [["number": ep["season"] ?? 0, "episodes": episodes]] as [[String: Any]]
            body["shows"] = [s] as [[String: Any]]
        }
        return body
    }

    static func meta(imdb: String, type: String, title: String, year: Any?) -> MetaItem {
        var m = MetaItem(id: imdb, type: type, name: title)
        m.poster = "https://images.metahub.space/poster/medium/\(imdb)/img"
        m.background = "https://images.metahub.space/background/medium/\(imdb)/img"
        if let y = year as? Int { m.releaseInfo = String(y) }
        return m
    }
}

// MARK: - Simkl

/// Simkl for one profile: PIN sign-in (enter a code on simkl.com/pin), scrobbling, watched marks, plan to watch list
/// and watched history, like the Android TV app.
@MainActor
final class SimklStore: ObservableObject {
    struct Pin: Equatable {
        var userCode: String
        var verificationURL: String
        var expires: Date
        var interval: Int
    }

    @Published private(set) var username: String?
    @Published private(set) var pending: Pin?
    @Published private(set) var watchlist: [MetaItem] = []
    @Published private(set) var syncing = false
    @Published var message: String?

    private let store = ProfileScope.defaults
    private var accessToken: String?
    private var pollTask: Task<Void, Never>?
    private var lastScrobble: (key: String, action: String, at: Date)?

    static let api = "https://api.simkl.com"

    init() {
        accessToken = store.string(forKey: "simklAccess")
        username = store.string(forKey: "simklUser")
    }

    static var clientId: String {
        let own = UserDefaults.standard.string(forKey: "simklClientId")?.trimmingCharacters(in: .whitespaces) ?? ""
        return own.isEmpty ? BuildSecrets.simklClientId : own
    }
    static var hasBuiltInApp: Bool { !BuildSecrets.simklClientId.isEmpty }
    static var configured: Bool { !clientId.isEmpty }
    var connected: Bool { accessToken != nil }

    // MARK: Sign-in

    func startSignIn() async {
        guard Self.configured else { message = "Simkl isn't set up yet."; return }
        message = nil
        let cid = Self.clientId
        guard let url = URL(string: "\(Self.api)/oauth/pin?client_id=\(cid)&app-name=meowio&app-version=1"),
              let obj = await TrackingHTTP.send(url).json as? [String: Any],
              let user = obj["user_code"] as? String else {
            message = "Could not reach Simkl. Check the client ID."
            return
        }
        let pin = Pin(userCode: user, verificationURL: obj["verification_url"] as? String ?? "https://simkl.com/pin",
                      expires: Date().addingTimeInterval(Double(obj["expires_in"] as? Int ?? 900)),
                      interval: max(obj["interval"] as? Int ?? 5, 3))
        pending = pin
        pollTask?.cancel()
        pollTask = Task { await poll(pin) }
    }

    func cancelSignIn() {
        pollTask?.cancel()
        pending = nil
    }

    private func poll(_ pin: Pin) async {
        while !Task.isCancelled, Date() < pin.expires {
            try? await Task.sleep(nanoseconds: UInt64(pin.interval) * 1_000_000_000)
            if Task.isCancelled { return }
            guard let url = URL(string: "\(Self.api)/oauth/pin/\(pin.userCode)?client_id=\(Self.clientId)") else { return }
            if let obj = await TrackingHTTP.send(url).json as? [String: Any],
               let token = obj["access_token"] as? String, !token.isEmpty {
                accessToken = token
                store.set(token, forKey: "simklAccess")
                pending = nil
                await loadUser()
                await sync()
                return
            }
        }
        if !Task.isCancelled { message = "The code expired. Try again." }
        pending = nil
    }

    func signOut() {
        accessToken = nil
        username = nil
        watchlist = []
        for k in ["simklAccess", "simklUser"] { store.removeObject(forKey: k) }
    }

    private func loadUser() async {
        let r = await request("/users/settings", method: "POST", body: [:])
        if let obj = r.json as? [String: Any], let user = obj["user"] as? [String: Any] {
            username = user["name"] as? String
            store.set(username, forKey: "simklUser")
        }
    }

    // MARK: HTTP

    private func request(_ path: String, method: String = "GET", body: Any? = nil) async -> TrackingHTTP.Reply {
        let sep = path.contains("?") ? "&" : "?"
        guard let url = URL(string: "\(Self.api)\(path)\(sep)client_id=\(Self.clientId)&app-name=meowio&app-version=1") else {
            return .init(status: 0, json: nil)
        }
        var headers = ["simkl-api-key": Self.clientId]
        if let accessToken { headers["Authorization"] = "Bearer \(accessToken)" }
        let r = await TrackingHTTP.send(url, method: method, headers: headers, body: body)
        if r.status == 401 { signOut(); message = "Simkl signed out. Sign in again." }
        return r
    }

    // MARK: Scrobbling and marks

    func scrobble(_ action: String, meta: MetaItem, videoId: String?, progress: Double) {
        guard connected, var body = TrackingHTTP.item(meta: meta, videoId: videoId) else { return }
        let key = videoId ?? meta.id
        if let last = lastScrobble, last.key == key, last.action == action, Date().timeIntervalSince(last.at) < 5 { return }
        lastScrobble = (key, action, Date())
        body["progress"] = (progress * 100).rounded() / 100
        Task { _ = await request("/scrobble/\(action)", method: "POST", body: body) }
    }

    func setWatched(_ watched: Bool, meta: MetaItem, videoId: String?) {
        guard connected, let item = TrackingHTTP.item(meta: meta, videoId: videoId) else { return }
        let body = TrackingHTTP.historyBody(item, watched: watched)
        Task { _ = await request(watched ? "/sync/history" : "/sync/history/remove", method: "POST", body: body) }
    }

    /// Adding puts the title on Plan to watch. Simkl has no plain remove, so removal stays local.
    func setInWatchlist(_ add: Bool, meta: MetaItem) {
        guard meta.id.hasPrefix("tt"), meta.type == "movie" || meta.type == "series" else { return }
        if add {
            if !watchlist.contains(where: { $0.id == meta.id }) { watchlist.insert(meta, at: 0) }
        } else {
            watchlist.removeAll { $0.id == meta.id }
        }
        guard connected, add else { return }
        let entry: [String: Any] = ["to": "plantowatch", "ids": ["imdb": meta.id]]
        let body: [String: Any] = meta.type == "movie" ? ["movies": [entry]] : ["shows": [entry]]
        Task { _ = await request("/sync/add-to-list", method: "POST", body: body) }
    }

    // MARK: Sync

    func sync(into library: LibraryStore? = nil) async {
        guard connected, !syncing else { return }
        syncing = true
        defer { syncing = false }
        if username == nil { await loadUser() }

        var list: [MetaItem] = []
        for (kind, type, key) in [("movies", "movie", "movie"), ("shows", "series", "show")] {
            guard let obj = await request("/sync/all-items/\(kind)/plantowatch").json as? [String: Any],
                  let items = obj[kind] as? [[String: Any]] else { continue }
            for it in items {
                guard let m = it[key] as? [String: Any], let ids = m["ids"] as? [String: Any],
                      let imdb = ids["imdb"] as? String, imdb.hasPrefix("tt") else { continue }
                list.append(TrackingHTTP.meta(imdb: imdb, type: type, title: m["title"] as? String ?? imdb, year: m["year"]))
            }
        }
        watchlist = list
        guard let library else { return }
        library.mergeSaved(list, source: "simkl")

        if let obj = await request("/sync/all-items/movies/completed").json as? [String: Any],
           let items = obj["movies"] as? [[String: Any]] {
            for it in items {
                guard let m = it["movie"] as? [String: Any], let ids = m["ids"] as? [String: Any],
                      let imdb = ids["imdb"] as? String, imdb.hasPrefix("tt") else { continue }
                let meta = TrackingHTTP.meta(imdb: imdb, type: "movie", title: m["title"] as? String ?? imdb, year: m["year"])
                library.markWatched(meta: meta, videoId: nil, notify: false, at: TrackingHTTP.iso(it["last_watched_at"]))
            }
        }
        if let obj = await request("/sync/all-items/shows?episode_watched_at=yes").json as? [String: Any],
           let items = obj["shows"] as? [[String: Any]] {
            for it in items.prefix(300) {
                guard let s = it["show"] as? [String: Any], let ids = s["ids"] as? [String: Any],
                      let imdb = ids["imdb"] as? String, imdb.hasPrefix("tt") else { continue }
                let meta = TrackingHTTP.meta(imdb: imdb, type: "series", title: s["title"] as? String ?? imdb, year: s["year"])
                for season in it["seasons"] as? [[String: Any]] ?? [] {
                    guard let sn = season["number"] as? Int, sn > 0 else { continue }
                    for ep in season["episodes"] as? [[String: Any]] ?? [] {
                        guard let en = ep["number"] as? Int else { continue }
                        library.markWatched(meta: meta, videoId: "\(imdb):\(sn):\(en)", notify: false,
                                            at: TrackingHTTP.iso(ep["watched_at"]))
                    }
                }
            }
        }
    }
}

struct SimklSettingsView: View {
    @EnvironmentObject var simkl: SimklStore
    @EnvironmentObject var library: LibraryStore
    @AppStorage("simklClientId") private var ownClientId = ""
    var body: some View {
        List {
            if simkl.connected {
                Section {
                    Text("Signed in as \(simkl.username ?? "Simkl user")")
                    Button(simkl.syncing ? "Syncing…" : "Sync now") { Task { await simkl.sync(into: library) } }
                        .disabled(simkl.syncing)
                    Button("Sign out", role: .destructive) { simkl.signOut() }
                } footer: {
                    Text("What you watch is scrobbled to Simkl, your watched history and Plan to watch list come back here, and titles you add to My List go to Plan to watch.")
                }
            } else if let pin = simkl.pending {
                Section {
                    VStack(alignment: .leading, spacing: 20) {
                        Text("On your phone or computer, go to").foregroundStyle(.secondary)
                        Text(pin.verificationURL).font(.title2.bold())
                        Text("and enter this code:").foregroundStyle(.secondary)
                        Text(pin.userCode).font(.system(size: 72, weight: .heavy, design: .monospaced)).kerning(8)
                        HStack(spacing: 14) { ProgressView(); Text("Waiting for Simkl…") }
                    }
                    .padding(.vertical, 20)
                    Button("Cancel") { simkl.cancelSignIn() }
                }
            } else {
                Section {
                    Button("Sign in to Simkl") { Task { await simkl.startSignIn() } }
                        .disabled(!SimklStore.configured)
                    if !SimklStore.hasBuiltInApp {
                        TextField("Your Simkl app client ID", text: $ownClientId)
                    }
                } footer: {
                    Text(SimklStore.configured ? "You'll get a short code to enter at simkl.com/pin."
                         : "This build has no Simkl app. Create one at simkl.com/settings/developer and paste its client ID.")
                }
            }
            if let m = simkl.message { Text(m).foregroundStyle(.secondary) }
        }
        .navigationTitle("Simkl")
    }
}

// MARK: - MDBList

/// MDBList for one profile: the user's own API key (mdblist.com/preferences), watchlist, watched history
/// and scrobbling.
@MainActor
final class MDBListStore: ObservableObject {
    @Published private(set) var username: String?
    @Published private(set) var watchlist: [MetaItem] = []
    @Published private(set) var syncing = false
    @Published var message: String?

    private let store = ProfileScope.defaults
    private var apiKey: String?
    private var lastScrobble: (key: String, action: String, at: Date)?

    static let api = "https://api.mdblist.com"

    init() {
        apiKey = store.string(forKey: "mdblistKey")
        username = store.string(forKey: "mdblistUser")
    }

    var connected: Bool { apiKey != nil }

    func connect(key: String) async {
        let k = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !k.isEmpty else { return }
        message = nil
        let r = await get("/user", key: k)
        guard r.status == 200, let obj = r.json as? [String: Any] else {
            message = r.status == 0 ? "Could not reach MDBList." : "MDBList did not accept that API key."
            return
        }
        apiKey = k
        username = obj["username"] as? String ?? obj["name"] as? String
        store.set(k, forKey: "mdblistKey")
        store.set(username, forKey: "mdblistUser")
    }

    func signOut() {
        apiKey = nil
        username = nil
        watchlist = []
        for k in ["mdblistKey", "mdblistUser"] { store.removeObject(forKey: k) }
    }

    private func url(_ path: String, key: String) -> URL? {
        let sep = path.contains("?") ? "&" : "?"
        return URL(string: "\(Self.api)\(path)\(sep)apikey=\(key)")
    }

    private func get(_ path: String, key: String? = nil) async -> TrackingHTTP.Reply {
        guard let k = key ?? apiKey, let u = url(path, key: k) else { return .init(status: 0, json: nil) }
        return await TrackingHTTP.send(u)
    }

    private func post(_ path: String, _ body: Any) async -> TrackingHTTP.Reply {
        guard let k = apiKey, let u = url(path, key: k) else { return .init(status: 0, json: nil) }
        return await TrackingHTTP.send(u, method: "POST", body: body)
    }

    func scrobble(_ action: String, meta: MetaItem, videoId: String?, progress: Double) {
        guard connected, var body = TrackingHTTP.item(meta: meta, videoId: videoId) else { return }
        let key = videoId ?? meta.id
        if let last = lastScrobble, last.key == key, last.action == action, Date().timeIntervalSince(last.at) < 5 { return }
        lastScrobble = (key, action, Date())
        body["progress"] = (progress * 100).rounded() / 100
        Task { _ = await post("/scrobble/\(action)", body) }
    }

    func setWatched(_ watched: Bool, meta: MetaItem, videoId: String?) {
        guard connected, let item = TrackingHTTP.item(meta: meta, videoId: videoId) else { return }
        let body = TrackingHTTP.historyBody(item, watched: watched)
        Task { _ = await post(watched ? "/sync/watched" : "/sync/watched/remove", body) }
    }

    func setInWatchlist(_ add: Bool, meta: MetaItem) {
        guard connected, meta.id.hasPrefix("tt"), meta.type == "movie" || meta.type == "series" else { return }
        let entry: [String: Any] = ["imdb": meta.id]
        let body: [String: Any] = meta.type == "movie" ? ["movies": [entry]] : ["shows": [entry]]
        if add {
            if !watchlist.contains(where: { $0.id == meta.id }) { watchlist.insert(meta, at: 0) }
        } else {
            watchlist.removeAll { $0.id == meta.id }
        }
        Task { _ = await post(add ? "/watchlist/items/add" : "/watchlist/items/remove", body) }
    }

    private static func metas(_ obj: Any?) -> [MetaItem] {
        var rows: [[String: Any]] = []
        if let o = obj as? [String: Any] {
            rows = (o["movies"] as? [[String: Any]] ?? []).map { var r = $0; r["mediatype"] = "movie"; return r }
                 + (o["shows"] as? [[String: Any]] ?? []).map { var r = $0; r["mediatype"] = "show"; return r }
        } else if let a = obj as? [[String: Any]] { rows = a }
        return rows.compactMap { r in
            let imdb = (r["imdb_id"] as? String) ?? ((r["ids"] as? [String: Any])?["imdb"] as? String) ?? ""
            guard imdb.hasPrefix("tt") else { return nil }
            let kind = (r["mediatype"] as? String) ?? (r["type"] as? String) ?? "movie"
            return TrackingHTTP.meta(imdb: imdb, type: kind == "show" || kind == "series" ? "series" : "movie",
                                     title: r["title"] as? String ?? imdb, year: r["release_year"] ?? r["year"])
        }
    }

    func sync(into library: LibraryStore? = nil) async {
        guard connected, !syncing else { return }
        syncing = true
        defer { syncing = false }
        let list = Self.metas(await get("/watchlist/items?limit=200").json)
        watchlist = list
        guard let library else { return }
        library.mergeSaved(list, source: "mdblist")
        if let obj = await get("/sync/watched").json as? [String: Any] {
            for it in obj["movies"] as? [[String: Any]] ?? [] {
                let m = it["movie"] as? [String: Any] ?? it
                guard let ids = m["ids"] as? [String: Any], let imdb = ids["imdb"] as? String, imdb.hasPrefix("tt") else { continue }
                let meta = TrackingHTTP.meta(imdb: imdb, type: "movie", title: m["title"] as? String ?? imdb, year: m["year"])
                library.markWatched(meta: meta, videoId: nil, notify: false, at: TrackingHTTP.iso(it["last_watched_at"]))
            }
            for it in (obj["shows"] as? [[String: Any]] ?? []).prefix(300) {
                let s = it["show"] as? [String: Any] ?? it
                guard let ids = s["ids"] as? [String: Any], let imdb = ids["imdb"] as? String, imdb.hasPrefix("tt") else { continue }
                let meta = TrackingHTTP.meta(imdb: imdb, type: "series", title: s["title"] as? String ?? imdb, year: s["year"])
                for season in it["seasons"] as? [[String: Any]] ?? [] {
                    guard let sn = season["number"] as? Int, sn > 0 else { continue }
                    for ep in season["episodes"] as? [[String: Any]] ?? [] {
                        guard let en = ep["number"] as? Int else { continue }
                        library.markWatched(meta: meta, videoId: "\(imdb):\(sn):\(en)", notify: false,
                                            at: TrackingHTTP.iso(ep["last_watched_at"] ?? ep["watched_at"]))
                    }
                }
            }
        }
    }
}

struct MDBListSettingsView: View {
    @EnvironmentObject var mdb: MDBListStore
    @EnvironmentObject var library: LibraryStore
    @State private var key = ""
    var body: some View {
        List {
            if mdb.connected {
                Section {
                    Text("Connected as \(mdb.username ?? "MDBList user")")
                    Button(mdb.syncing ? "Syncing…" : "Sync now") { Task { await mdb.sync(into: library) } }
                        .disabled(mdb.syncing)
                    Button("Disconnect", role: .destructive) { mdb.signOut() }
                } footer: {
                    Text("What you watch is scrobbled to MDBList, your watched history and watchlist come back here, and My List follows your MDBList watchlist.")
                }
            } else {
                Section {
                    SecureField("MDBList API key", text: $key)
                    Button("Connect") { Task { await mdb.connect(key: key); key = ""; await mdb.sync(into: library) } }
                        .disabled(key.trimmingCharacters(in: .whitespaces).isEmpty)
                } footer: {
                    Text("Copy your API key from mdblist.com/preferences. It stays on this device.")
                }
            }
            if let m = mdb.message { Text(m).foregroundStyle(.secondary) }
        }
        .navigationTitle("MDBList")
    }
}
