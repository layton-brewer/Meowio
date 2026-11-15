import Foundation

/// A stretch of a title that can be skipped: opening, recap, ending or credits.
struct SkipSegment: Equatable {
    var start: Double
    var end: Double
    /// "intro", "recap" or "outro".
    var kind: String
    var label: String { kind == "outro" ? "Skip Credits" : (kind == "recap" ? "Skip Recap" : "Skip Intro") }
}

/// Looks up skip times from IntroDB (when this build has its address) and AniSkip (anime), like the Android apps.
enum SkipSegments {
    static func load(meta: MetaItem, videoId: String?) async -> [SkipSegment] {
        var found: [SkipSegment] = []
        let ref = EpisodeRef(videoId)
        let imdb = meta.type == "series" ? ref?.showId : meta.id
        if let imdb, imdb.hasPrefix("tt") {
            found += await introDb(imdb: imdb, season: ref?.season, episode: ref?.episode, movie: meta.type == "movie")
        }
        if meta.type == "series", let ref {
            found += await aniSkip(showId: ref.showId, episode: ref.episode)
        }
        // Earlier sources win when two overlap.
        var kept: [SkipSegment] = []
        for s in found where s.end > s.start && !kept.contains(where: { $0.start < s.end && $0.end > s.start }) { kept.append(s) }
        return kept.sorted { $0.start < $1.start }
    }

    private static func get(_ url: URL) async -> Any? {
        var r = URLRequest(url: url)
        r.timeoutInterval = 8
        r.setValue("Meowio", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await URLSession.shared.data(for: r),
              (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    private static func seconds(_ obj: [String: Any]?, _ key: String) -> Double? {
        guard let obj else { return nil }
        if let v = obj["\(key)_sec"] as? Double { return v }
        if let v = obj["\(key)_ms"] as? Double { return v / 1000 }
        return nil
    }

    private static func introDb(imdb: String, season: Int?, episode: Int?, movie: Bool) async -> [SkipSegment] {
        var base = BuildSecrets.introDbURL
        guard !base.isEmpty else { return [] }
        if !base.hasSuffix("/") { base += "/" }
        guard var c = URLComponents(string: base + "segments") else { return [] }
        var items = [URLQueryItem(name: "imdb_id", value: imdb)]
        if movie { items.append(URLQueryItem(name: "is_movie", value: "true")) }
        else if let season, let episode {
            items += [URLQueryItem(name: "season", value: String(season)), URLQueryItem(name: "episode", value: String(episode))]
        }
        c.queryItems = items
        guard let url = c.url, let obj = await get(url) as? [String: Any] else { return [] }
        func seg(_ key: String, _ kind: String) -> SkipSegment? {
            let o = obj[key] as? [String: Any]
            guard let s = seconds(o, "start"), let e = seconds(o, "end"), s >= 0, e > s else { return nil }
            return SkipSegment(start: s, end: e, kind: kind)
        }
        if movie {
            var credits = seg("outro", "outro")
            let scene = seg("post_credits", "outro")
            // Skipping credits must not also skip the post-credits scene.
            if var cr = credits, let sc = scene, sc.start < cr.end, sc.end > cr.start {
                cr.end = sc.start
                credits = cr.end > cr.start ? cr : nil
            }
            return [credits, scene].compactMap { $0 }
        }
        return [seg("intro", "intro"), seg("recap", "recap"), seg("outro", "outro")].compactMap { $0 }
    }

    private static func malId(showId: String) async -> String? {
        let parts = showId.split(separator: ":")
        if parts.count == 2, parts[0] == "mal" { return String(parts[1]) }
        guard showId.hasPrefix("tt"), let url = URL(string: "https://arm.haglund.dev/api/v2/imdb?id=\(showId)"),
              let list = await get(url) as? [[String: Any]] else { return nil }
        for entry in list {
            if let n = entry["myanimelist"] as? Int { return String(n) }
        }
        return nil
    }

    private static func aniSkip(showId: String, episode: Int) async -> [SkipSegment] {
        guard let mal = await malId(showId: showId) else { return [] }
        let q = "types[]=op&types[]=ed&types[]=recap&episodeLength=0"
        guard let url = URL(string: "https://api.aniskip.com/v2/skip-times/\(mal)/\(episode)?\(q)"),
              let obj = await get(url) as? [String: Any], obj["found"] as? Bool == true,
              let results = obj["results"] as? [[String: Any]] else { return [] }
        return results.compactMap { r in
            guard let iv = r["interval"] as? [String: Any], let s = iv["startTime"] as? Double, let e = iv["endTime"] as? Double,
                  let type = r["skipType"] as? String else { return nil }
            let kind = type.hasPrefix("ed") || type == "mixed-ed" ? "outro" : (type == "recap" ? "recap" : "intro")
            return SkipSegment(start: s, end: e, kind: kind)
        }
    }
}
