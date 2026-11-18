import SwiftUI

/// What is already in the user's debrid cloud (TorBox torrents, usenet and web downloads, Premiumize files, or
/// Real-Debrid torrents),
/// to browse and play straight away, like the Cloud tab of the Android TV library.
enum CloudLibrary {
    struct File: Identifiable, Hashable {
        var id: String
        var name: String
        var size: Int64?
        /// Premiumize sometimes lists the link right away.
        var link: String?
    }

    struct Item: Identifiable, Hashable {
        var id: String
        /// TorBox: "torrents", "usenet" or "webdl". Premiumize: "files". Real-Debrid: "rd".
        var kind: String
        var name: String
        var status: String?
        var files: [File]
    }

    private static let videoExtensions: Set<String> = ["3g2", "3gp", "avi", "divx", "flv", "m2ts", "m4v", "mkv", "mov", "mp4",
                                                       "mpeg", "mpg", "mts", "ogm", "ogv", "ts", "webm", "wmv"]

    static func isVideo(_ name: String, mime: String?) -> Bool {
        (mime ?? "").lowercased().hasPrefix("video/") || videoExtensions.contains((name as NSString).pathExtension.lowercased())
    }

    static func supports(_ service: DebridService) -> Bool { service == .torbox || service == .premiumize || service == .realDebrid }

    /// Every item with at least one video file, newest first as the service lists them. Nil when the list failed.
    static func items(service: DebridService, key: String) async -> [Item]? {
        let token = key.trimmingCharacters(in: .whitespacesAndNewlines)
        switch service {
        case .torbox:
            var all: [Item] = []
            var anyOK = false
            for kind in ["torrents", "usenet", "webdl"] {
                guard let obj = await json("https://api.torbox.app/v1/api/\(kind)/mylist?bypass_cache=true", token: token) as? [String: Any],
                      let rows = obj["data"] as? [[String: Any]] else { continue }
                anyOK = true
                for row in rows {
                    guard let id = scalar(row["id"]) else { continue }
                    let name = (row["name"] as? String) ?? id
                    let files = (row["files"] as? [[String: Any]] ?? []).compactMap { f -> File? in
                        guard let fid = scalar(f["id"]) else { return nil }
                        let full = (f["short_name"] as? String) ?? (f["name"] as? String) ?? ""
                        let shown = (full as NSString).lastPathComponent
                        guard isVideo(shown, mime: f["mimetype"] as? String) else { return nil }
                        return File(id: fid, name: shown, size: (f["size"] as? NSNumber)?.int64Value)
                    }
                    if !files.isEmpty {
                        all.append(Item(id: id, kind: kind, name: name, status: row["download_state"] as? String, files: files))
                    }
                }
            }
            return anyOK ? all : nil
        case .premiumize:
            guard let obj = await json("https://www.premiumize.me/api/item/listall", token: token) as? [String: Any],
                  (obj["status"] as? String)?.lowercased() != "error" else { return nil }
            var groups: [String: Item] = [:]
            var order: [String] = []
            for f in obj["files"] as? [[String: Any]] ?? [] {
                let path = ((f["path"] as? String) ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                let name = (f["name"] as? String) ?? (path as NSString).lastPathComponent
                guard !name.isEmpty, isVideo(name, mime: f["mime_type"] as? String) else { continue }
                let parts = path.split(separator: "/")
                let folder = parts.count > 1 ? String(parts[0]) : name
                let file = File(id: (f["id"] as? String) ?? path, name: name, size: (f["size"] as? NSNumber)?.int64Value,
                                link: f["link"] as? String)
                if groups[folder] == nil { order.append(folder); groups[folder] = Item(id: folder, kind: "files", name: folder, files: []) }
                groups[folder]?.files.append(file)
            }
            return order.compactMap { groups[$0] }.map { var i = $0; i.files.sort { $0.name < $1.name }; return i }
        case .realDebrid:
            return await realDebridItems(key: token)
        default:
            return nil
        }
    }

    /// A finished torrent's `links` are its selected files in order. The list has no file names, so torrents with
    /// several files are looked up one by one (at most 60 a refresh, to stay under Real-Debrid's rate limit).
    private static func realDebridItems(key: String) async -> [Item]? {
        let rd = "https://api.real-debrid.com/rest/1.0"
        guard let bearer = try? await Debrid.rdBearer(key),
              let rows = await json("\(rd)/torrents?limit=1000", token: bearer) as? [[String: Any]] else { return nil }
        var items: [Item] = []
        var lookups = 0
        for row in rows {
            guard let id = row["id"] as? String, (row["status"] as? String) == "downloaded" else { continue }
            let name = (row["filename"] as? String) ?? id
            let links = (row["links"] as? [String] ?? []).filter { !$0.isEmpty }
            var names: [String] = []
            var sizes: [Int64?] = []
            if links.count == 1 {
                names = [name]
                sizes = [(row["bytes"] as? NSNumber)?.int64Value]
            } else if links.count > 1, lookups < 60 {
                lookups += 1
                let files = ((await json("\(rd)/torrents/info/\(id)", token: bearer) as? [String: Any])?["files"] as? [[String: Any]] ?? [])
                    .filter { ($0["selected"] as? Int) == 1 }
                    .sorted { ($0["id"] as? Int ?? 0) < ($1["id"] as? Int ?? 0) }
                if files.count == links.count {
                    names = files.map { ((($0["path"] as? String) ?? "") as NSString).lastPathComponent }
                    sizes = files.map { ($0["bytes"] as? NSNumber)?.int64Value }
                }
            }
            let files = links.enumerated().compactMap { (i, link) -> File? in
                let shown = i < names.count ? names[i] : "\(name) (\(i + 1))"
                // Unnamed parts might be videos; let the player decide.
                guard i >= names.count || isVideo(shown, mime: nil) else { return nil }
                return File(id: link, name: shown, size: i < sizes.count ? sizes[i] : nil)
            }
            if !files.isEmpty { items.append(Item(id: id, kind: "rd", name: name, status: nil, files: files)) }
        }
        return items
    }

    /// A playable link for one file.
    static func link(for file: File, in item: Item, service: DebridService, key: String) async -> URL? {
        let token = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if let s = file.link, let url = URL(string: s) { return url }
        switch service {
        case .torbox:
            let idName = item.kind == "torrents" ? "torrent_id" : (item.kind == "usenet" ? "usenet_id" : "web_id")
            var c = URLComponents(string: "https://api.torbox.app/v1/api/\(item.kind)/requestdl")
            c?.queryItems = [URLQueryItem(name: "token", value: token), URLQueryItem(name: idName, value: item.id),
                             URLQueryItem(name: "file_id", value: file.id), URLQueryItem(name: "redirect", value: "false")]
            guard let s = c?.url?.absoluteString,
                  let link = (await json(s, token: token) as? [String: Any])?["data"] as? String else { return nil }
            return URL(string: link)
        case .premiumize:
            var c = URLComponents(string: "https://www.premiumize.me/api/item/details")
            c?.queryItems = [URLQueryItem(name: "id", value: file.id)]
            guard let s = c?.url?.absoluteString,
                  let link = (await json(s, token: token) as? [String: Any])?["link"] as? String else { return nil }
            return URL(string: link)
        case .realDebrid:
            // file.id is Real-Debrid's host link; unrestricting it gives the playable one.
            guard let bearer = try? await Debrid.rdBearer(token),
                  let u = URL(string: "https://api.real-debrid.com/rest/1.0/unrestrict/link") else { return nil }
            var r = URLRequest(url: u)
            r.httpMethod = "POST"
            r.timeoutInterval = 20
            r.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
            r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            var allowed = CharacterSet.alphanumerics
            allowed.insert(charactersIn: "-._~")
            r.httpBody = "link=\(file.id.addingPercentEncoding(withAllowedCharacters: allowed) ?? file.id)".data(using: .utf8)
            guard let (data, _) = try? await URLSession.shared.data(for: r),
                  let s = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["download"] as? String else { return nil }
            return URL(string: s)
        default:
            return nil
        }
    }

    private static func scalar(_ v: Any?) -> String? {
        if let s = v as? String, !s.isEmpty { return s }
        if let n = v as? NSNumber { return n.stringValue }
        return nil
    }

    private static func json(_ url: String, token: String) async -> Any? {
        guard let u = URL(string: url) else { return nil }
        var r = URLRequest(url: u)
        r.timeoutInterval = 20
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        guard let (data, response) = try? await URLSession.shared.data(for: r),
              ((response as? HTTPURLResponse)?.statusCode ?? 0) < 300 else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }
}

struct CloudLibraryView: View {
    @EnvironmentObject var settings: SettingsStore
    @State private var items: [CloudLibrary.Item]?
    @State private var failed = false
    @State private var query = ""

    private var shown: [CloudLibrary.Item] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return (items ?? []).filter { q.isEmpty || $0.name.lowercased().contains(q) }
    }

    var body: some View {
        List {
            if items == nil && !failed { ProgressView() }
            if failed { Text("Couldn't load your \(settings.debridService.title) cloud. Check the key in Settings.").foregroundStyle(.orange) }
            if let items, items.isEmpty { Text("Nothing in your \(settings.debridService.title) cloud yet.") }
            ForEach(shown) { item in
                NavigationLink { CloudItemView(item: item) } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.name).lineLimit(2)
                        Text([item.files.count == 1 ? "1 video" : "\(item.files.count) videos", item.status]
                                .compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .searchable(text: $query)
        .navigationTitle("\(settings.debridService.title) cloud")
        .task(id: "\(settings.debridService.rawValue)|\(settings.debridKey)") {
            items = nil
            failed = false
            let found = await CloudLibrary.items(service: settings.debridService, key: settings.debridKey)
            items = found
            failed = found == nil
        }
    }
}

struct CloudItemView: View {
    @EnvironmentObject var settings: SettingsStore
    let item: CloudLibrary.Item
    @State private var playing: PlayRequest?
    @State private var resolving: String?
    @State private var message: String?

    var body: some View {
        List {
            if let message { Text(message).foregroundStyle(.orange) }
            ForEach(item.files) { file in
                Button { Task { await play(file) } } label: {
                    HStack {
                        Image(systemName: resolving == file.id ? "hourglass" : "play.fill")
                        Text(file.name).lineLimit(2)
                        Spacer()
                        if let size = file.size {
                            Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file)).foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(resolving != nil)
            }
        }
        .navigationTitle(item.name)
        .fullScreenCover(item: $playing) { req in PlayerScreen(request: req, settings: settings) }
    }

    private func play(_ file: CloudLibrary.File) async {
        message = nil
        resolving = file.id
        let url = await CloudLibrary.link(for: file, in: item, service: settings.debridService, key: settings.debridKey)
        resolving = nil
        guard let url else { message = "\(settings.debridService.title) did not give a link for this file."; return }
        let stream = StreamItem(name: file.name, url: url.absoluteString,
                                behaviorHints: .init(filename: file.name))
        // Its own id, so cloud files never mix with a title's progress or Continue Watching.
        let meta = MetaItem(id: "cloud:\(item.id):\(file.id)", type: "other", name: file.name)
        var req = PlayRequest(meta: meta, videoId: nil, title: file.name, candidates: [stream])
        req.tracked = false
        playing = req
    }
}
