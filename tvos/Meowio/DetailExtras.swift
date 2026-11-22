import SwiftUI

/// The extra rows Android TV's details page shows under the hero: cast with photos, the collection a film
/// belongs to, studio and network logos, season posters, episode ratings and Trakt comments.
/// Everything comes from TMDB (when a key is set) and Trakt's public API (the built-in key, no sign-in).
struct DetailExtras {
    struct Person: Hashable {
        var name: String
        var role: String
        var photo: URL?
        var crew: Bool
    }
    struct Company: Hashable {
        var name: String
        var logo: URL?
    }
    struct Comment: Identifiable, Hashable {
        var id: Int
        var user: String
        var text: String
        var spoiler: Bool
        var likes: Int
        var rating: Int?
    }

    var people: [Person] = []
    var collectionName: String?
    var collection: [MetaItem] = []
    var companies: [Company] = []
    var seasonPosters: [Int: URL] = [:]
    /// TMDB score out of 10 for each episode, keyed "season:episode".
    var episodeRatings: [String: Double] = [:]
    var comments: [Comment] = []

    static func ratingKey(_ season: Int?, _ episode: Int?) -> String { "\(season ?? 0):\(episode ?? 0)" }
}

enum DetailExtrasLoader {
    static func load(for meta: MetaItem) async -> DetailExtras {
        var extras = DetailExtras()
        let imdb = String(meta.id.split(separator: ":").first ?? "")
        guard imdb.hasPrefix("tt") else { return extras }
        let isMovie = meta.type == "movie"
        async let comments = traktComments(imdb: imdb, movie: isMovie)
        if TMDB.available,
           let found = await TMDB.get("/find/\(imdb)", ["external_source": "imdb_id"]),
           let tmdbId = ((found[isMovie ? "movie_results" : "tv_results"] as? [[String: Any]])?.first)?["id"] as? Int {
            let media = isMovie ? "movie" : "tv"
            if let details = await TMDB.get("/\(media)/\(tmdbId)", ["append_to_response": isMovie ? "credits" : "aggregate_credits"]) {
                extras.people = people(details, movie: isMovie)
                let companyRows = (details[isMovie ? "production_companies" : "networks"] as? [[String: Any]]) ?? []
                extras.companies = companyRows.prefix(6).compactMap { row in
                    guard let name = row["name"] as? String else { return nil }
                    return DetailExtras.Company(name: name, logo: TMDB.imageURL(row["logo_path"] as? String, size: "w300").flatMap(URL.init(string:)))
                }
                if isMovie, let col = details["belongs_to_collection"] as? [String: Any], let colId = col["id"] as? Int,
                   let set = await TMDB.get("/collection/\(colId)"),
                   let parts = set["parts"] as? [[String: Any]] {
                    let sorted = parts.sorted { (($0["release_date"] as? String) ?? "9") < (($1["release_date"] as? String) ?? "9") }
                    extras.collectionName = (set["name"] as? String) ?? (col["name"] as? String)
                    extras.collection = await CollectionsStore.withImdbIds(sorted, defaultMedia: "movie")
                }
                if !isMovie {
                    let seasons = (details["seasons"] as? [[String: Any]]) ?? []
                    for s in seasons {
                        guard let n = s["season_number"] as? Int,
                              let url = TMDB.imageURL(s["poster_path"] as? String, size: "w342").flatMap(URL.init(string:)) else { continue }
                        extras.seasonPosters[n] = url
                    }
                    extras.episodeRatings = await episodeRatings(tmdbId: tmdbId, seasons: seasons.compactMap { $0["season_number"] as? Int })
                }
            }
        }
        extras.comments = await comments
        return extras
    }

    private static func people(_ details: [String: Any], movie: Bool) -> [DetailExtras.Person] {
        let credits = (details[movie ? "credits" : "aggregate_credits"] as? [String: Any]) ?? [:]
        let photo: (Any?) -> URL? = { TMDB.imageURL($0 as? String, size: "w185").flatMap(URL.init(string:)) }
        let crew = ((credits["crew"] as? [[String: Any]]) ?? []).filter {
            let job = ($0["job"] as? String) ?? ((($0["jobs"] as? [[String: Any]])?.first?["job"]) as? String) ?? ""
            return job == "Director" || job == "Creator"
        }.prefix(2).compactMap { row -> DetailExtras.Person? in
            guard let name = row["name"] as? String else { return nil }
            return DetailExtras.Person(name: name, role: "Director", photo: photo(row["profile_path"]), crew: true)
        }
        let creators = ((details["created_by"] as? [[String: Any]]) ?? []).prefix(2).compactMap { row -> DetailExtras.Person? in
            guard let name = row["name"] as? String else { return nil }
            return DetailExtras.Person(name: name, role: "Creator", photo: photo(row["profile_path"]), crew: true)
        }
        let cast = ((credits["cast"] as? [[String: Any]]) ?? []).prefix(20).compactMap { row -> DetailExtras.Person? in
            guard let name = row["name"] as? String else { return nil }
            let role = (row["character"] as? String) ?? (tvRoles(row).first ?? "")
            return DetailExtras.Person(name: name, role: role, photo: photo(row["profile_path"]), crew: false)
        }
        var seen = Set<String>()
        return (creators + crew + cast).filter { seen.insert($0.name).inserted }
    }

    /// TV credits list roles under "roles" instead of "character".
    private static func tvRoles(_ row: [String: Any]) -> [String] {
        ((row["roles"] as? [[String: Any]]) ?? []).compactMap { $0["character"] as? String }.filter { !$0.isEmpty }
    }

    private static func episodeRatings(tmdbId: Int, seasons: [Int]) async -> [String: Double] {
        var out: [String: Double] = [:]
        await withTaskGroup(of: [String: Double].self) { group in
            for season in seasons.prefix(40) where season > 0 {
                group.addTask {
                    guard let s = await TMDB.get("/tv/\(tmdbId)/season/\(season)"),
                          let eps = s["episodes"] as? [[String: Any]] else { return [:] }
                    var part: [String: Double] = [:]
                    for e in eps {
                        guard let n = e["episode_number"] as? Int, let vote = e["vote_average"] as? Double,
                              vote > 0, ((e["vote_count"] as? Int) ?? 0) > 0 else { continue }
                        part[DetailExtras.ratingKey(season, n)] = vote
                    }
                    return part
                }
            }
            for await part in group { out.merge(part) { a, _ in a } }
        }
        return out
    }

    private static func traktComments(imdb: String, movie: Bool) async -> [DetailExtras.Comment] {
        guard let rows = await TraktPublic.get("/\(movie ? "movies" : "shows")/\(imdb)/comments/likes", ["limit": "20"]) as? [[String: Any]] else {
            return []
        }
        return rows.compactMap { row in
            guard let id = row["id"] as? Int, let text = row["comment"] as? String, !text.isEmpty else { return nil }
            let user = ((row["user"] as? [String: Any])?["username"] as? String) ?? "Trakt user"
            return DetailExtras.Comment(id: id, user: user, text: text, spoiler: (row["spoiler"] as? Bool) ?? false,
                                        likes: (row["likes"] as? Int) ?? 0,
                                        rating: (row["user_stats"] as? [String: Any])?["rating"] as? Int)
        }
    }
}

// MARK: - Rows

/// Cast and crew with photos, like Android TV's cast row. Each card opens that person's page.
struct CastPhotoRow: View {
    let people: [DetailExtras.Person]
    var body: some View {
        if !people.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Cast").font(.system(size: 32, weight: .semibold))
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 36) {
                        ForEach(people, id: \.self) { person in
                            NavigationLink(value: CastRoute(name: person.name, crew: person.crew)) {
                                PersonCard(person: person)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 24)
                }
                .scrollClipDisabled()
            }
            .focusSection()
        }
    }

    private struct PersonCard: View {
        let person: DetailExtras.Person
        @Environment(\.isFocused) private var focused
        var body: some View {
            VStack(spacing: 12) {
                ZStack {
                    Circle().fill(.ultraThinMaterial)
                    if let photo = person.photo {
                        Artwork(url: photo).clipShape(Circle())
                    } else {
                        Text(String(person.name.prefix(1))).font(.system(size: 54, weight: .bold))
                    }
                }
                .frame(width: 170, height: 170)
                .overlay(Circle().stroke(.white, lineWidth: focused ? 5 : 0))
                .scaleEffect(focused ? 1.08 : 1)
                .animation(.easeOut(duration: 0.15), value: focused)
                Text(person.name).font(.system(size: 24, weight: .semibold)).lineLimit(1)
                if !person.role.isEmpty {
                    Text(person.role).font(.system(size: 20)).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .frame(width: 200)
        }
    }
}

/// Studio (films) or network (shows) logos, like Android TV's production row.
struct CompanyLogosRow: View {
    let companies: [DetailExtras.Company]
    var body: some View {
        if !companies.isEmpty {
            VStack(alignment: .leading, spacing: 16) {
                Text("Production").font(.system(size: 32, weight: .semibold))
                HStack(spacing: 28) {
                    ForEach(companies, id: \.self) { c in
                        Group {
                            if let logo = c.logo {
                                AsyncImage(url: logo) { phase in
                                    if let image = phase.image {
                                        image.resizable().aspectRatio(contentMode: .fit)
                                    } else {
                                        Text(c.name).font(.system(size: 22, weight: .semibold)).foregroundStyle(.black)
                                    }
                                }
                            } else {
                                Text(c.name).font(.system(size: 22, weight: .semibold)).foregroundStyle(.black)
                            }
                        }
                        .frame(width: 180, height: 60)
                        .padding(.horizontal, 20).padding(.vertical, 14)
                        .background(.white.opacity(0.9), in: RoundedRectangle(cornerRadius: 14))
                    }
                }
            }
        }
    }
}

/// Trakt comments, like Android TV's comments row. Spoilers stay hidden until the card is opened.
struct CommentsRow: View {
    let comments: [DetailExtras.Comment]
    @State private var open: DetailExtras.Comment?
    var body: some View {
        if !comments.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Comments").font(.system(size: 32, weight: .semibold))
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 32) {
                        ForEach(comments) { c in
                            Button { open = c } label: { CommentCard(comment: c) }
                                .buttonStyle(.focusRing)
                        }
                    }
                    .padding(.vertical, 24)
                }
                .scrollClipDisabled()
            }
            .focusSection()
            .sheet(item: $open) { c in
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Text(c.user).font(.system(size: 34, weight: .bold))
                        Text(c.text).font(.system(size: 28))
                    }
                    .padding(60)
                    .frame(maxWidth: 1200, alignment: .leading)
                }
            }
        }
    }

    private struct CommentCard: View {
        let comment: DetailExtras.Comment
        var body: some View {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text(comment.user).font(.system(size: 24, weight: .bold)).lineLimit(1)
                    Spacer()
                    if let r = comment.rating {
                        Label("\(r)/10", systemImage: "star.fill").font(.system(size: 20, weight: .semibold))
                    }
                }
                Text(comment.spoiler ? "Contains spoilers. Open to read." : comment.text)
                    .font(.system(size: 22)).foregroundStyle(.white.opacity(comment.spoiler ? 0.6 : 0.85))
                    .lineLimit(5)
                Spacer(minLength: 0)
                Label("\(comment.likes)", systemImage: "hand.thumbsup.fill").font(.system(size: 18)).foregroundStyle(.secondary)
            }
            .padding(28)
            .frame(width: 520, height: 300, alignment: .topLeading)
            .background(.ultraThinMaterial)
        }
    }
}

/// Star rating sheet: 1 to 10, saved to Trakt like the other apps' rating panel.
struct RatingSheet: View {
    let title: String
    let current: Int?
    let onRate: (Int?) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(spacing: 40) {
            Text("Rate \(title)").font(.system(size: 40, weight: .bold))
            HStack(spacing: 16) {
                ForEach(1...10, id: \.self) { n in
                    Button { onRate(n); dismiss() } label: {
                        VStack(spacing: 6) {
                            Image(systemName: (current ?? 0) >= n ? "star.fill" : "star")
                            Text("\(n)").font(.system(size: 20))
                        }
                    }
                    .buttonStyle(ChipButtonStyle(selected: current == n))
                }
            }
            if current != nil {
                Button("Remove rating", role: .destructive) { onRate(nil); dismiss() }
            }
        }
        .padding(80)
    }
}
