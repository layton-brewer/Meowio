import Foundation
import UIKit

enum AddonClient {
    static let cinemeta = "https://v3-cinemeta.strem.io"

    static func fetch<T: Decodable>(_ type: T.Type, from urlString: String) async -> T? {
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            return nil
        }
    }

    static func catalog(type: String, id: String = "top", search: String? = nil) async -> [MetaItem] {
        var path = "\(cinemeta)/catalog/\(type)/\(id)"
        if let search, let q = search.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) {
            path += "/search=\(q)"
        }
        let response = await fetch(CatalogResponse.self, from: path + ".json")
        return (response?.metas ?? []).map { item in
            var m = item
            m.type = type
            return m
        }
    }

    static func meta(type: String, id: String) async -> MetaItem? {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        var m = await fetch(MetaResponse.self, from: "\(cinemeta)/meta/\(type)/\(encoded).json")?.meta
        m?.type = type
        return m
    }

    /// Queries every configured add-on in parallel and returns playable streams, best first.
    static func streams(type: String, id: String, addons: [String]) async -> [StreamItem] {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        var all: [StreamItem] = []
        await withTaskGroup(of: [StreamItem].self) { group in
            for base in addons {
                group.addTask {
                    await fetch(StreamResponse.self, from: "\(base)/stream/\(type)/\(encoded).json")?.streams ?? []
                }
            }
            for await list in group { all.append(contentsOf: list) }
        }
        let playable = all.filter { $0.playableURL != nil && $0.behaviorHints?.notWebReady != true }
        return StreamRanker.rank(playable, wantSeason: type == "series")
    }

    static func parsePlaylist(_ text: String) -> [Channel] {
        var channels: [Channel] = []
        var name = ""
        var group = "Other"
        var logo: URL?
        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("#EXTINF") {
                name = line.components(separatedBy: ",").last ?? "Channel"
                group = attribute("group-title", in: line) ?? "Other"
                logo = attribute("tvg-logo", in: line).flatMap(URL.init(string:))
            } else if !line.isEmpty, !line.hasPrefix("#"), let url = URL(string: line) {
                channels.append(Channel(name: name.isEmpty ? "Channel" : name, group: group, logo: logo, url: url))
                name = ""
            }
        }
        return channels
    }

    private static func attribute(_ key: String, in line: String) -> String? {
        guard let range = line.range(of: "\(key)=\"") else { return nil }
        let rest = line[range.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return nil }
        let value = String(rest[..<end])
        return value.isEmpty ? nil : value
    }
}

enum StreamRanker {
    static var deviceHeight: Int {
        let h = Int(UIScreen.main.nativeBounds.height)
        return h >= 2000 ? 2160 : 1080
    }

    static func resolution(of text: String) -> Int {
        let t = text.lowercased()
        if t.contains("2160") || t.contains("4k") || t.contains("uhd") { return 2160 }
        if t.contains("1080") { return 1080 }
        if t.contains("720") { return 720 }
        if t.contains("480") { return 480 }
        return 0
    }

    static func isCached(_ s: StreamItem) -> Bool {
        let t = s.combinedText.lowercased()
        // Direct/debrid links that already carry a URL are playable; "+" marks cached debrid hits.
        if t.contains("+]") || t.contains("⚡") || t.contains("cached") || t.contains("instant") { return true }
        return false
    }

    static func score(_ s: StreamItem, wantSeason: Bool) -> Int {
        let text = s.combinedText.lowercased()
        var score = 0
        if isCached(s) { score += 1000 }
        let res = resolution(of: text)
        let target = deviceHeight
        if res == target { score += 300 }
        else if res > target { score += 150 - min((res - target) / 20, 100) }
        else if res > 0 { score += 100 - (target - res) / 20 }
        if wantSeason {
            let hasEpisodeTag = text.range(of: "s\\d{1,2}e\\d{1,3}", options: .regularExpression) != nil
            if text.contains("season") || text.contains("complete") || text.contains("pack") || !hasEpisodeTag { score += 120 }
        }
        for bad in ["cam", "hdcam", "telesync", " ts ", "screener", "scr "] where text.contains(bad) { score -= 800 }
        if text.contains("hevc") || text.contains("x265") || text.contains("h265") { score += 10 }
        if text.contains("hdr") || text.contains("dolby") { score += 10 }
        return score
    }

    static func rank(_ list: [StreamItem], wantSeason: Bool) -> [StreamItem] {
        list.sorted { score($0, wantSeason: wantSeason) > score($1, wantSeason: wantSeason) }
    }
}
