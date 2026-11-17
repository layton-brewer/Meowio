import Foundation

/// Turns torrent results into direct links through the user's debrid service, like Direct Debrid on the
/// Android TV app. Apple TV cannot run a torrent engine itself, so this is how torrent results play here.
enum Debrid {
    enum Failure: Error { case badKey, notCached, noFile, network }

    private static let videoExtensions: Set<String> = ["mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "mpg", "mpeg"]

    /// A direct link for [stream], or throws. Uncached torrents fail fast so failover can move on.
    static func resolve(_ stream: StreamItem, season: Int?, episode: Int?, service: DebridService, key: String) async throws -> URL {
        guard let hash = stream.infoHash?.lowercased(), !hash.isEmpty else { throw Failure.noFile }
        let token = key.trimmingCharacters(in: .whitespacesAndNewlines)
        let magnet = "magnet:?xt=urn:btih:\(hash)"
        switch service {
        case .none: throw Failure.badKey
        case .realDebrid: return try await realDebrid(magnet: magnet, stream: stream, season: season, episode: episode, key: token)
        case .torbox: return try await torbox(hash: hash, magnet: magnet, stream: stream, season: season, episode: episode, key: token)
        case .premiumize: return try await premiumize(magnet: magnet, stream: stream, season: season, episode: episode, key: token)
        }
    }

    // MARK: Code sign-in (TorBox and Premiumize, like the Android TV app)

    struct DeviceCode: Equatable {
        var deviceCode: String
        var userCode: String
        var url: String
        var interval: Int
        var qrURL: String? = nil
    }

    static func canSignIn(_ service: DebridService) -> Bool {
        service == .torbox || (service == .premiumize && !BuildSecrets.premiumizeClientId.isEmpty)
    }

    static func startSignIn(_ service: DebridService) async -> DeviceCode? {
        switch service {
        case .torbox:
            guard let (obj, _) = try? await send(get("\(tb)/user/auth/device/start?app=Meowio")),
                  let d = (obj as? [String: Any])?["data"] as? [String: Any],
                  let code = d["device_code"] as? String else { return nil }
            return DeviceCode(deviceCode: code, userCode: d["code"] as? String ?? "",
                              url: d["friendly_verification_url"] as? String ?? d["verification_url"] as? String ?? "https://tor.box/link",
                              interval: max(3, d["interval"] as? Int ?? 5), qrURL: d["verification_url_complete"] as? String)
        case .premiumize:
            guard let (obj, _) = try? await send(form("https://www.premiumize.me/token",
                                                      ["response_type": "device_code", "client_id": BuildSecrets.premiumizeClientId])),
                  let d = obj as? [String: Any], let code = d["device_code"] as? String else { return nil }
            return DeviceCode(deviceCode: code, userCode: d["user_code"] as? String ?? "",
                              url: d["verification_uri"] as? String ?? "https://www.premiumize.me/device",
                              interval: max(3, d["interval"] as? Int ?? 5), qrURL: d["verification_uri_complete"] as? String)
        default:
            return nil
        }
    }

    /// The account token once the user approves, nil while still waiting. Throws when the code is dead.
    static func pollSignIn(_ service: DebridService, _ code: DeviceCode) async throws -> String? {
        switch service {
        case .torbox:
            var r = URLRequest(url: URL(string: "\(tb)/user/auth/device/token")!)
            r.httpMethod = "POST"
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: ["device_code": code.deviceCode])
            let (obj, _) = (try? await send(r)) ?? (nil, 0)
            return ((obj as? [String: Any])?["data"] as? [String: Any])?["access_token"] as? String
        case .premiumize:
            let (obj, _) = (try? await send(form("https://www.premiumize.me/token",
                                                 ["grant_type": "device_code", "code": code.deviceCode,
                                                  "client_id": BuildSecrets.premiumizeClientId]))) ?? (nil, 0)
            let d = obj as? [String: Any]
            if let token = d?["access_token"] as? String { return token }
            if let err = d?["error"] as? String, err != "authorization_pending", err != "slow_down" { throw Failure.badKey }
            return nil
        default:
            throw Failure.badKey
        }
    }

    // MARK: Cache check

    /// Which of [hashes] the service already has, so cached torrents can be tried first (like Android TV).
    /// Real-Debrid has no cache check, so nothing is reported for it.
    /// Nil when the check could not be made.
    static func cached(_ hashes: [String], service: DebridService, key: String) async -> Set<String>? {
        let token = key.trimmingCharacters(in: .whitespacesAndNewlines)
        var hits: Set<String> = []
        for start in stride(from: 0, to: hashes.count, by: 100) {
            let chunk = Array(hashes[start..<min(start + 100, hashes.count)])
            switch service {
            case .torbox:
                var r = URLRequest(url: URL(string: "\(tb)/torrents/checkcached?format=object&list_files=false")!)
                r.httpMethod = "POST"
                r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                r.setValue("application/json", forHTTPHeaderField: "Content-Type")
                r.httpBody = try? JSONSerialization.data(withJSONObject: ["hashes": chunk])
                guard let (obj, status) = try? await send(r), status < 300 else { return nil }
                if let data = (obj as? [String: Any])?["data"] as? [String: Any] { hits.formUnion(data.keys.map { $0.lowercased() }) }
            case .premiumize:
                var r = form("https://www.premiumize.me/api/cache/check", [:], bearer: token)
                var allowed = CharacterSet.alphanumerics
                allowed.insert(charactersIn: "-._~")
                r.httpBody = chunk.map { "items%5B%5D=\($0.addingPercentEncoding(withAllowedCharacters: allowed) ?? $0)" }
                    .joined(separator: "&").data(using: .utf8)
                guard let (obj, _) = try? await send(r), let flags = (obj as? [String: Any])?["response"] as? [Bool] else { return nil }
                for (h, ok) in zip(chunk, flags) where ok { hits.insert(h) }
            case .realDebrid, .none:
                return nil
            }
        }
        return hits
    }

    // MARK: File choice

    struct File {
        var id: Int
        var path: String
        var size: Int64
    }

    /// The torrent's file for this title: the add-on's file index when it names a video, otherwise the episode
    /// that matches SxxEyy, otherwise the largest video.
    static func pick(_ files: [File], stream: StreamItem, season: Int?, episode: Int?, indexBase: Int) -> File? {
        let videos = files.filter { videoExtensions.contains(($0.path as NSString).pathExtension.lowercased()) }
        guard !videos.isEmpty else { return nil }
        if let name = stream.behaviorHints?.filename?.lowercased(),
           let hit = videos.first(where: { ($0.path as NSString).lastPathComponent.lowercased() == name }) {
            return hit
        }
        if let idx = stream.fileIdx, let hit = files.first(where: { $0.id == idx + indexBase }),
           videoExtensions.contains((hit.path as NSString).pathExtension.lowercased()) {
            return hit
        }
        if let s = season, let e = episode {
            let patterns = [String(format: "s%02de%02d", s, e), String(format: "s%de%02d", s, e), "\(s)x\(String(format: "%02d", e))"]
            let matches = videos.filter { f in
                let n = f.path.lowercased().replacingOccurrences(of: " ", with: "").replacingOccurrences(of: ".", with: "")
                return patterns.contains { n.contains($0) }
            }
            if let best = matches.max(by: { $0.size < $1.size }) { return best }
        }
        return videos.max { $0.size < $1.size }
    }

    // MARK: HTTP

    private static func send(_ request: URLRequest) async throws -> (Any?, Int) {
        var r = request
        r.timeoutInterval = 20
        do {
            let (data, response) = try await URLSession.shared.data(for: r)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status == 401 || status == 403 { throw Failure.badKey }
            return (try? JSONSerialization.jsonObject(with: data), status)
        } catch let f as Failure {
            throw f
        } catch {
            throw Failure.network
        }
    }

    private static func form(_ url: String, _ fields: [String: String], bearer: String? = nil, method: String = "POST") -> URLRequest {
        var r = URLRequest(url: URL(string: url)!)
        r.httpMethod = method
        if let bearer { r.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        r.httpBody = fields.map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? $0.value)" }
            .joined(separator: "&").data(using: .utf8)
        return r
    }

    private static func get(_ url: String, bearer: String? = nil) -> URLRequest {
        var r = URLRequest(url: URL(string: url)!)
        if let bearer { r.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        return r
    }

    // MARK: Real-Debrid

    private static let rd = "https://api.real-debrid.com/rest/1.0"

    private static func realDebrid(magnet: String, stream: StreamItem, season: Int?, episode: Int?, key: String) async throws -> URL {
        let (added, _) = try await send(form("\(rd)/torrents/addMagnet", ["magnet": magnet], bearer: key))
        guard let id = (added as? [String: Any])?["id"] as? String else { throw Failure.network }
        let (info, _) = try await send(get("\(rd)/torrents/info/\(id)", bearer: key))
        let files = ((info as? [String: Any])?["files"] as? [[String: Any]] ?? []).compactMap { f -> File? in
            guard let fid = f["id"] as? Int, let path = f["path"] as? String else { return nil }
            return File(id: fid, path: path, size: (f["bytes"] as? NSNumber)?.int64Value ?? 0)
        }
        guard let file = pick(files, stream: stream, season: season, episode: episode, indexBase: 1) else {
            _ = try? await send(get("\(rd)/torrents/delete/\(id)", bearer: key).with(method: "DELETE"))
            throw Failure.noFile
        }
        _ = try await send(form("\(rd)/torrents/selectFiles/\(id)", ["files": String(file.id)], bearer: key))
        // Cached torrents are ready at once. Give it a few seconds, then give up so the next source is tried.
        for _ in 0..<6 {
            let (now, _) = try await send(get("\(rd)/torrents/info/\(id)", bearer: key))
            let obj = now as? [String: Any]
            if (obj?["status"] as? String) == "downloaded", let link = (obj?["links"] as? [String])?.first {
                let (un, _) = try await send(form("\(rd)/unrestrict/link", ["link": link], bearer: key))
                if let s = (un as? [String: Any])?["download"] as? String, let url = URL(string: s) { return url }
                throw Failure.network
            }
            try? await Task.sleep(nanoseconds: 800_000_000)
        }
        _ = try? await send(get("\(rd)/torrents/delete/\(id)", bearer: key).with(method: "DELETE"))
        throw Failure.notCached
    }

    // MARK: TorBox

    private static let tb = "https://api.torbox.app/v1/api"

    private static func torbox(hash: String, magnet: String, stream: StreamItem, season: Int?, episode: Int?, key: String) async throws -> URL {
        let (cached, _) = try await send(get("\(tb)/torrents/checkcached?hash=\(hash)&format=list", bearer: key))
        let cachedList = (cached as? [String: Any])?["data"] as? [Any]
        if cachedList == nil || cachedList?.isEmpty == true { throw Failure.notCached }
        let boundary = "meowio-\(UUID().uuidString)"
        var create = URLRequest(url: URL(string: "\(tb)/torrents/createtorrent")!)
        create.httpMethod = "POST"
        create.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        create.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        create.httpBody = "--\(boundary)\r\nContent-Disposition: form-data; name=\"magnet\"\r\n\r\n\(magnet)\r\n--\(boundary)--\r\n".data(using: .utf8)
        let (made, _) = try await send(create)
        guard let data = (made as? [String: Any])?["data"] as? [String: Any],
              let tid = (data["torrent_id"] as? NSNumber)?.intValue ?? (data["id"] as? NSNumber)?.intValue else { throw Failure.network }
        for _ in 0..<6 {
            let (list, _) = try await send(get("\(tb)/torrents/mylist?id=\(tid)&bypass_cache=true", bearer: key))
            if let t = (list as? [String: Any])?["data"] as? [String: Any], let raw = t["files"] as? [[String: Any]], !raw.isEmpty {
                let files = raw.compactMap { f -> File? in
                    guard let fid = (f["id"] as? NSNumber)?.intValue else { return nil }
                    let path = (f["name"] as? String) ?? (f["short_name"] as? String) ?? ""
                    return File(id: fid, path: path, size: (f["size"] as? NSNumber)?.int64Value ?? 0)
                }
                guard let file = pick(files, stream: stream, season: season, episode: episode, indexBase: 0) else { throw Failure.noFile }
                let (dl, _) = try await send(get("\(tb)/torrents/requestdl?token=\(key)&torrent_id=\(tid)&file_id=\(file.id)&redirect=false"))
                if let s = (dl as? [String: Any])?["data"] as? String, let url = URL(string: s) { return url }
                throw Failure.network
            }
            try? await Task.sleep(nanoseconds: 800_000_000)
        }
        throw Failure.notCached
    }

    // MARK: Premiumize

    private static func premiumize(magnet: String, stream: StreamItem, season: Int?, episode: Int?, key: String) async throws -> URL {
        // Bearer works for both an API key and a device sign-in token (what the Android apps store and sync).
        let (res, _) = try await send(form("https://www.premiumize.me/api/transfer/directdl", ["src": magnet], bearer: key))
        guard let obj = res as? [String: Any] else { throw Failure.network }
        guard (obj["status"] as? String) == "success" else {
            let msg = (obj["message"] as? String ?? "").lowercased()
            throw msg.contains("api") || msg.contains("auth") ? Failure.badKey : Failure.notCached
        }
        let content = obj["content"] as? [[String: Any]] ?? []
        var links: [Int: String] = [:]
        let files = content.enumerated().compactMap { (i, f) -> File? in
            guard let path = f["path"] as? String, let link = (f["stream_link"] as? String) ?? (f["link"] as? String) else { return nil }
            links[i] = (f["link"] as? String) ?? link
            return File(id: i, path: path, size: (f["size"] as? NSNumber)?.int64Value ?? 0)
        }
        guard let file = pick(files, stream: stream, season: season, episode: episode, indexBase: 0),
              let s = links[file.id], let url = URL(string: s) else { throw Failure.noFile }
        return url
    }
}

private extension URLRequest {
    func with(method: String) -> URLRequest {
        var r = self
        r.httpMethod = method
        return r
    }
}
