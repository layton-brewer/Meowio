import Foundation

/// Trakt endpoints that need only the app's key, not a signed-in user: cast and crew pages and
/// related titles. They work in builds without a TMDB key, like the Android TV and phone apps.
enum TraktPublic {
    /// The Trakt app this build uses: the one the user entered in Settings, else the one built in.
    static var clientId: String {
        let own = UserDefaults.standard.string(forKey: "traktClientId")?.trimmingCharacters(in: .whitespaces) ?? ""
        return own.isEmpty ? BuildSecrets.traktClientId : own
    }
    static var available: Bool { !clientId.isEmpty }

    struct Person {
        var name: String
        var biography: String
        var photo: String?
        var knownFor: [MetaItem]
    }

    static func get(_ path: String, _ query: [String: String] = [:]) async -> Any? {
        guard available, var comps = URLComponents(string: "https://api.trakt.tv" + path) else { return nil }
        let params = query.merging(["extended": "full,images"]) { a, _ in a }
        comps.queryItems = params.map { URLQueryItem(name: $0.key, value: $0.value) }
        guard let url = comps.url else { return nil }
        var r = URLRequest(url: url)
        r.timeoutInterval = 20
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("2", forHTTPHeaderField: "trakt-api-version")
        r.setValue(clientId, forHTTPHeaderField: "trakt-api-key")
        r.setValue("Meowio", forHTTPHeaderField: "User-Agent")
        guard let result = try? await URLSession.shared.data(for: r),
              let http = result.1 as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
        return try? JSONSerialization.jsonObject(with: result.0)
    }

    /// Trakt image paths come without a scheme ("media.trakt.tv/...").
    static func imageURL(_ any: Any?) -> String? {
        guard let first = (any as? [String])?.first(where: { !$0.isEmpty }) else { return nil }
        if first.hasPrefix("https://") { return first }
        if first.hasPrefix("http://") { return "https://" + first.dropFirst(7) }
        if first.hasPrefix("//") { return "https:" + first }
        return "https://" + first
    }

    /// A title Cinemeta and the add-ons can open (IMDb id), with Trakt or Metahub art.
    static func item(_ obj: [String: Any], type: String) -> MetaItem? {
        guard let ids = obj["ids"] as? [String: Any], let imdb = ids["imdb"] as? String, imdb.hasPrefix("tt") else { return nil }
        var m = MetaItem(id: imdb, type: type, name: (obj["title"] as? String) ?? imdb)
        let images = obj["images"] as? [String: Any]
        m.poster = imageURL(images?["poster"]) ?? "https://images.metahub.space/poster/medium/\(imdb)/img"
        m.background = imageURL(images?["fanart"]) ?? "https://images.metahub.space/background/medium/\(imdb)/img"
        if let year = obj["year"] as? Int { m.releaseInfo = String(year) }
        if let overview = obj["overview"] as? String, !overview.isEmpty { m.description = overview }
        if let rating = obj["rating"] as? Double, rating > 0 { m.imdbRating = String(format: "%.1f", rating) }
        return m
    }

    /// The person's page: photo, biography and the titles they are known for (acting, or directing
    /// and writing for crew), most popular first. Talk-show guest spots are left out.
    static func person(named name: String, preferCrew: Bool) async -> Person? {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty,
              let results = await get("/search/person", ["query": trimmed, "limit": "5"]) as? [[String: Any]] else { return nil }
        let people = results.compactMap { $0["person"] as? [String: Any] }
        guard let found = people.first(where: { (($0["name"] as? String) ?? "").caseInsensitiveCompare(trimmed) == .orderedSame }) ?? people.first,
              let ids = found["ids"] as? [String: Any] else { return nil }
        guard let path = (ids["slug"] as? String) ?? (ids["trakt"] as? Int).map({ String($0) }) else { return nil }

        let detail = (await get("/people/\(path)") as? [String: Any]) ?? found
        let movies = await get("/people/\(path)/movies") as? [String: Any]
        let shows = await get("/people/\(path)/shows") as? [String: Any]

        func titles(_ credits: [String: Any]?, key: String, type: String, crew: Bool) -> [MetaItem] {
            var rows: [[String: Any]]
            if crew {
                rows = ((credits?["crew"] as? [String: Any]) ?? [:]).values.flatMap { ($0 as? [[String: Any]]) ?? [] }
            } else {
                rows = (credits?["cast"] as? [[String: Any]]) ?? []
                rows = rows.filter { row in
                    let role = ((row["character"] as? String) ?? ((row["characters"] as? [String])?.first ?? "")).lowercased()
                    return !["self", "himself", "herself", "themselves", "themself"].contains { role.hasPrefix($0) }
                }
            }
            let ranked = rows.compactMap { $0[key] as? [String: Any] }
                .sorted { (($0["votes"] as? Int) ?? 0) > (($1["votes"] as? Int) ?? 0) }
            var seen = Set<String>()
            return ranked.compactMap { item($0, type: type) }.filter { seen.insert($0.id).inserted }
        }

        let department = ((detail["known_for_department"] as? String) ?? "").lowercased()
        let crewFirst = preferCrew || (!department.isEmpty && department != "acting")
        var known = titles(movies, key: "movie", type: "movie", crew: crewFirst) + titles(shows, key: "show", type: "series", crew: crewFirst)
        if known.isEmpty {
            known = titles(movies, key: "movie", type: "movie", crew: !crewFirst) + titles(shows, key: "show", type: "series", crew: !crewFirst)
        }
        let photo = imageURL((detail["images"] as? [String: Any])?["headshot"])
            ?? imageURL((found["images"] as? [String: Any])?["headshot"])
        return Person(name: (detail["name"] as? String) ?? trimmed,
                      biography: (detail["biography"] as? String) ?? "",
                      photo: photo,
                      knownFor: Array(known.prefix(60)))
    }

    /// "More like this" for a movie or show page.
    static func related(to meta: MetaItem) async -> [MetaItem] {
        let imdb = String(meta.id.split(separator: ":").first ?? "")
        guard imdb.hasPrefix("tt") else { return [] }
        let isMovie = meta.type == "movie"
        let rows = await get("/\(isMovie ? "movies" : "shows")/\(imdb)/related", ["limit": "20"]) as? [[String: Any]] ?? []
        return rows.compactMap { item($0, type: isMovie ? "movie" : "series") }
    }
}
