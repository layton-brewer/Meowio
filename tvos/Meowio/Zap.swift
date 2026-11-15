import SwiftUI

// Zap: one random movie or series at a time. Zap for the next one, Play to open it and start the best source,
// Save to My List, and narrow the pool by type, year, genre, actor and rating. Same behaviour as the Android and
// desktop apps. Titles already watched are skipped. Needs a TMDB key (built in, or typed in Settings).

enum ZapMediaType: String, CaseIterable {
    case both = "Movies & series", movies = "Movies", series = "Series"
}

struct ZapGenre: Hashable {
    var name: String
    var movieId: Int?
    var tvId: Int?
}

struct ZapPerson: Hashable {
    var id: Int
    var name: String
}

struct ZapFilters: Equatable {
    var type: ZapMediaType = .both
    var yearFrom: Int?
    var yearTo: Int?
    var genres: Set<String> = []
    var people: [ZapPerson] = []
    var minRating: Double = 0
    var hideWatched = true

    var activeCount: Int {
        [type != .both, yearFrom != nil || yearTo != nil, !genres.isEmpty, !people.isEmpty, minRating > 0].filter { $0 }.count
    }
}

struct ZapTitle: Equatable {
    var tmdbId: Int
    var isSeries: Bool
    var imdbId: String
    var name: String
    var year: String?
    var overview: String
    var poster: String?
    var backdrop: String?
    var rating: Double?
    var genres: [String]
    var runtimeMinutes: Int?
    var seasons: Int?
    var cast: [String]

    var key: String { "\(isSeries ? "tv" : "movie"):\(tmdbId)" }

    var meta: MetaItem {
        MetaItem(id: imdbId, type: isSeries ? "series" : "movie", name: name, poster: poster, background: backdrop,
                 description: overview, releaseInfo: year,
                 imdbRating: rating.map { String(format: "%.1f", $0) }, genres: genres)
    }

    var metaLine: String {
        var parts = [isSeries ? "Series" : "Movie"]
        if let year { parts.append(year) }
        if let m = runtimeMinutes { parts.append(m >= 60 ? "\(m / 60)h \(m % 60)m" : "\(m)m") }
        if isSeries, let s = seasons, s > 0 { parts.append(s == 1 ? "1 season" : "\(s) seasons") }
        if let r = rating { parts.append("★ " + String(format: "%.1f", r)) }
        return parts.joined(separator: "  ·  ")
    }
}

enum ZapEmpty { case noKey, noMatches, offline }

enum ZapData {
    static let genres: [ZapGenre] = [
        ZapGenre(name: "Action", movieId: 28, tvId: 10759), ZapGenre(name: "Adventure", movieId: 12, tvId: 10759),
        ZapGenre(name: "Animation", movieId: 16, tvId: 16), ZapGenre(name: "Comedy", movieId: 35, tvId: 35),
        ZapGenre(name: "Crime", movieId: 80, tvId: 80), ZapGenre(name: "Documentary", movieId: 99, tvId: 99),
        ZapGenre(name: "Drama", movieId: 18, tvId: 18), ZapGenre(name: "Family", movieId: 10751, tvId: 10751),
        ZapGenre(name: "Fantasy", movieId: 14, tvId: 10765), ZapGenre(name: "History", movieId: 36, tvId: nil),
        ZapGenre(name: "Horror", movieId: 27, tvId: nil), ZapGenre(name: "Kids", movieId: nil, tvId: 10762),
        ZapGenre(name: "Music", movieId: 10402, tvId: nil), ZapGenre(name: "Mystery", movieId: 9648, tvId: 9648),
        ZapGenre(name: "Reality", movieId: nil, tvId: 10764), ZapGenre(name: "Romance", movieId: 10749, tvId: nil),
        ZapGenre(name: "Sci-Fi", movieId: 878, tvId: 10765), ZapGenre(name: "Thriller", movieId: 53, tvId: nil),
        ZapGenre(name: "War", movieId: 10752, tvId: 10768), ZapGenre(name: "Western", movieId: 37, tvId: 37),
    ]

    /// Quick year filters: label, from, to.
    static let decades: [(String, Int?, Int?)] = [
        ("Any year", nil, nil), ("2020s", 2020, nil), ("2010s", 2010, 2019), ("2000s", 2000, 2009),
        ("1990s", 1990, 1999), ("1980s", 1980, 1989), ("1970s", 1970, 1979), ("Older", nil, 1969),
    ]
}

/// A shuffled supply of random titles for one set of filters. Make a new one when the filters change.
@MainActor
final class ZapPool {
    private struct Candidate: Hashable { var tmdbId: Int; var isSeries: Bool
        var key: String { "\(isSeries ? "tv" : "movie"):\(tmdbId)" } }

    private let filters: ZapFilters
    private let isWatched: (String) -> Bool
    private var queue: [Candidate] = []
    private var seen = Set<String>()
    private var totalPages: [Bool: Int] = [:]
    private var triedPages: [Bool: Set<Int>] = [:]
    private var creditsLoaded = false
    private var failures = 0

    init(filters: ZapFilters, isWatched: @escaping (String) -> Bool) {
        self.filters = filters
        self.isWatched = isWatched
    }

    /// The next title, or why there is none.
    func next() async -> (ZapTitle?, ZapEmpty?) {
        guard TMDB.available else { return (nil, .noKey) }
        var attempts = 0
        while attempts < 40 {
            if queue.isEmpty {
                await refill()
                if queue.isEmpty { return (nil, failures > 0 ? .offline : .noMatches) }
            }
            let candidate = queue.removeFirst()
            if !seen.insert(candidate.key).inserted { continue }
            attempts += 1
            guard let title = await loadTitle(candidate) else { continue }
            if filters.minRating > 0, (title.rating ?? 0) < filters.minRating { continue }
            if filters.hideWatched, isWatched(title.imdbId) { continue }
            return (title, nil)
        }
        return (nil, failures > 0 ? .offline : .noMatches)
    }

    private func activeTypes() -> [Bool] {
        let wanted: [Bool]
        switch filters.type {
        case .both: wanted = [false, true]
        case .movies: wanted = [false]
        case .series: wanted = [true]
        }
        let chosen = ZapData.genres.filter { filters.genres.contains($0.name) }
        // Genres are an AND filter, so a type with no id for one of them can never match.
        return wanted.filter { isSeries in chosen.allSatisfy { (isSeries ? $0.tvId : $0.movieId) != nil } }
    }

    private func refill() async {
        var added: [Candidate] = []
        if !filters.people.isEmpty {
            if !creditsLoaded {
                creditsLoaded = true
                added += await loadCredits()
            }
        } else {
            for isSeries in activeTypes() { added += await discoverPage(isSeries) }
        }
        added.shuffle()
        for c in added where !seen.contains(c.key) { queue.append(c) }
    }

    private func discoverPage(_ isSeries: Bool) async -> [Candidate] {
        var tried = triedPages[isSeries] ?? []
        let page: Int
        if let known = totalPages[isSeries] {
            let free = (1...max(1, min(known, 25))).filter { !tried.contains($0) }
            guard let pick = free.randomElement() else { return [] }
            page = pick
        } else {
            page = 1
        }
        tried.insert(page)
        triedPages[isSeries] = tried
        guard let body = await TMDB.get(isSeries ? "/discover/tv" : "/discover/movie", discoverQuery(isSeries, page)) else {
            failures += 1
            return []
        }
        totalPages[isSeries] = min(max(body["total_pages"] as? Int ?? 1, 1), 500)
        let results = body["results"] as? [[String: Any]] ?? []
        return results.compactMap { ($0["id"] as? Int).map { Candidate(tmdbId: $0, isSeries: isSeries) } }
    }

    private func discoverQuery(_ isSeries: Bool, _ page: Int) -> [String: String] {
        let chosen = ZapData.genres.filter { filters.genres.contains($0.name) }
        var ids: [Int] = []
        for g in chosen { if let id = isSeries ? g.tvId : g.movieId, !ids.contains(id) { ids.append(id) } }
        let dateKey = isSeries ? "first_air_date" : "primary_release_date"
        var q: [String: String] = [
            "sort_by": "popularity.desc", "include_adult": "false", "page": String(page), "language": "en-US",
            "vote_count.gte": (filters.yearTo ?? 3000) < 2000 ? "80" : "150",
        ]
        if filters.minRating > 0 { q["vote_average.gte"] = String(filters.minRating) }
        if !ids.isEmpty { q["with_genres"] = ids.map(String.init).joined(separator: ",") }
        if let from = filters.yearFrom { q["\(dateKey).gte"] = "\(from)-01-01" }
        if let to = filters.yearTo { q["\(dateKey).lte"] = "\(to)-12-31" }
        return q
    }

    private func loadCredits() async -> [Candidate] {
        let types = activeTypes()
        let chosen = ZapData.genres.filter { filters.genres.contains($0.name) }
        var out: [String: Candidate] = [:]
        for person in filters.people {
            guard let body = await TMDB.get("/person/\(person.id)/combined_credits") else { failures += 1; continue }
            for item in body["cast"] as? [[String: Any]] ?? [] {
                let isSeries: Bool
                switch item["media_type"] as? String {
                case "tv": isSeries = true
                case "movie": isSeries = false
                default: continue
                }
                guard types.contains(isSeries), let id = item["id"] as? Int else { continue }
                let genreIds = Set(item["genre_ids"] as? [Int] ?? [])
                if chosen.contains(where: { g in
                    guard let gid = isSeries ? g.tvId : g.movieId else { return true }
                    return !genreIds.contains(gid)
                }) { continue }
                let date = (item["release_date"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (item["first_air_date"] as? String) ?? ""
                let year = Int(date.prefix(4))
                if let from = filters.yearFrom, year == nil || year! < from { continue }
                if let to = filters.yearTo, year == nil || year! > to { continue }
                if filters.minRating > 0, (item["vote_average"] as? Double ?? 0) < filters.minRating { continue }
                if (item["vote_count"] as? Int ?? 0) < 20 { continue }
                let c = Candidate(tmdbId: id, isSeries: isSeries)
                out[c.key] = c
            }
        }
        return Array(out.values)
    }

    private func loadTitle(_ c: Candidate) async -> ZapTitle? {
        guard let body = await TMDB.get("/\(c.isSeries ? "tv" : "movie")/\(c.tmdbId)",
                                        ["append_to_response": "external_ids,credits", "language": "en-US"]) else {
            failures += 1
            return nil
        }
        let ext = body["external_ids"] as? [String: Any]
        let imdb = (body["imdb_id"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (ext?["imdb_id"] as? String) ?? ""
        guard imdb.hasPrefix("tt") else { return nil }
        let name = (body["title"] as? String) ?? (body["name"] as? String) ?? ""
        guard !name.isEmpty else { return nil }
        let date = (body["release_date"] as? String) ?? (body["first_air_date"] as? String) ?? ""
        let poster = body["poster_path"] as? String
        let backdrop = (body["backdrop_path"] as? String) ?? poster
        let genres = (body["genres"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }
        let cast = ((body["credits"] as? [String: Any])?["cast"] as? [[String: Any]] ?? []).prefix(6).compactMap { $0["name"] as? String }
        var runtime = body["runtime"] as? Int
        if runtime == nil || runtime == 0 { runtime = (body["episode_run_time"] as? [Int])?.first }
        let rating = body["vote_average"] as? Double
        return ZapTitle(tmdbId: c.tmdbId, isSeries: c.isSeries, imdbId: imdb, name: name,
                        year: date.count >= 4 ? String(date.prefix(4)) : nil,
                        overview: body["overview"] as? String ?? "",
                        poster: TMDB.imageURL(poster, size: "w500"), backdrop: TMDB.imageURL(backdrop, size: "w1280"),
                        rating: (rating ?? 0) > 0 ? rating : nil, genres: genres,
                        runtimeMinutes: (runtime ?? 0) > 0 ? runtime : nil, seasons: body["number_of_seasons"] as? Int,
                        cast: Array(cast))
    }
}

/// The filters the user last chose, kept while the app runs.
enum ZapMemory { @MainActor static var filters = ZapFilters() }

struct ZapView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    @State private var filters = ZapMemory.filters
    @State private var pool: ZapPool?
    @State private var current: ZapTitle?
    @State private var history: [ZapTitle] = []
    @State private var empty: ZapEmpty?
    @State private var loading = true
    @State private var showFilters = false

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottomLeading) {
                settings.theme.background.ignoresSafeArea()
                if let t = current {
                    Artwork(url: (t.backdrop ?? t.poster).flatMap(URL.init(string:))).ignoresSafeArea()
                    LinearGradient(colors: [.clear, settings.theme.background.opacity(0.85), settings.theme.background],
                                   startPoint: .top, endPoint: .bottom).ignoresSafeArea()
                    details(t)
                } else if !loading {
                    emptyState
                }
                if loading { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
            }
            .meowioDestinations()
        }
        .sheet(isPresented: $showFilters) {
            ZapFilterSheet(initial: filters) { next in
                showFilters = false
                guard next != filters else { return }
                filters = next
                ZapMemory.filters = next
                history = []
                empty = nil
                makePool()
                Task { await zap() }
            }
        }
        .task {
            makePool()
            await zap()
        }
    }

    private func details(_ t: ZapTitle) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("ZAP").font(.callout.bold()).foregroundStyle(settings.theme.accent)
            Text(t.name).font(.largeTitle.bold()).lineLimit(2)
            Text(t.metaLine).foregroundStyle(.secondary)
            if !t.genres.isEmpty { Text(t.genres.prefix(4).joined(separator: "  ·  ")).font(.callout).foregroundStyle(.secondary) }
            if !t.overview.isEmpty { Text(t.overview).lineLimit(4).frame(maxWidth: 1100, alignment: .leading) }
            if !t.cast.isEmpty { Text("Starring " + t.cast.prefix(4).joined(separator: ", ")).font(.callout).foregroundStyle(.secondary).lineLimit(2) }
            HStack(spacing: 24) {
                NavigationLink(value: AutoPlayRoute(item: t.meta)) { Label("Play", systemImage: "play.fill") }
                Button { Task { await zap() } } label: { Label("Zap", systemImage: "bolt.fill") }.disabled(loading)
                Button { library.toggleSaved(t.meta) } label: {
                    Label(library.isSaved(t.meta) ? "Saved" : "Save", systemImage: library.isSaved(t.meta) ? "checkmark" : "plus")
                }
                Button { showFilters = true } label: {
                    Label(filters.activeCount == 0 ? "Filters" : "Filters (\(filters.activeCount))", systemImage: "line.3.horizontal.decrease")
                }
                if !history.isEmpty {
                    Button { current = history.removeLast() } label: { Label("Previous", systemImage: "backward.fill") }
                }
            }
            .padding(.top, 8)
            if empty != nil, !loading {
                Text(empty == .offline ? "Couldn't load another title." : "That's everything for these filters.")
                    .font(.callout).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 80).padding(.bottom, 70)
    }

    private var emptyState: some View {
        VStack(spacing: 24) {
            Text(emptyText).font(.title3).multilineTextAlignment(.center)
            HStack(spacing: 24) {
                Button("Try again") { makePool(); Task { await zap() } }
                Button("Filters") { showFilters = true }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var emptyText: String {
        switch empty {
        case .noKey: return "Zap needs a TMDB key. Add one in Settings."
        case .offline: return "Couldn't reach TMDB. Check your connection."
        default: return "Nothing matches those filters. Try loosening them."
        }
    }

    private func makePool() {
        pool = ZapPool(filters: filters) { id in library.isWatched(id) }
    }

    private func zap() async {
        guard let pool, !loading || current == nil else { return }
        loading = true
        let previous = current
        let (title, reason) = await pool.next()
        if let title {
            if let previous { history.append(previous); if history.count > 30 { history.removeFirst() } }
            current = title
            empty = nil
        } else {
            empty = reason
        }
        loading = false
    }
}

struct ZapFilterSheet: View {
    @EnvironmentObject var settings: SettingsStore
    @State var draft: ZapFilters
    let initial: ZapFilters
    let apply: (ZapFilters) -> Void
    @State private var actorQuery = ""
    @State private var suggestions: [ZapPerson] = []

    init(initial: ZapFilters, apply: @escaping (ZapFilters) -> Void) {
        _draft = State(initialValue: initial)
        self.initial = initial
        self.apply = apply
    }

    private let columns = [GridItem(.adaptive(minimum: 260), spacing: 20)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                Text("Zap filters").font(.largeTitle.bold())

                section("Show") {
                    ForEach(ZapMediaType.allCases, id: \.self) { t in
                        pill(t.rawValue, draft.type == t) { draft.type = t }
                    }
                }
                section("Year") {
                    ForEach(ZapData.decades, id: \.0) { d in
                        pill(d.0, draft.yearFrom == d.1 && draft.yearTo == d.2) { draft.yearFrom = d.1; draft.yearTo = d.2 }
                    }
                }
                section("Genres (titles must match all you pick)") {
                    ForEach(ZapData.genres, id: \.name) { g in
                        let on = draft.genres.contains(g.name)
                        pill(g.name, on) { if on { draft.genres.remove(g.name) } else { draft.genres.insert(g.name) } }
                    }
                }
                Text("Actors (any of the ones you pick)").font(.headline).foregroundStyle(.secondary)
                if !draft.people.isEmpty {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 20) {
                        ForEach(draft.people, id: \.id) { p in
                            pill(p.name + "  ✕", true) { draft.people.removeAll { $0.id == p.id } }
                        }
                    }
                }
                TextField("Search for an actor", text: $actorQuery)
                if !suggestions.isEmpty {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 20) {
                        ForEach(suggestions.filter { s in !draft.people.contains { $0.id == s.id } }, id: \.id) { p in
                            pill(p.name, false) { draft.people.append(p); actorQuery = ""; suggestions = [] }
                        }
                    }
                }
                section("Minimum rating") {
                    ForEach([(0.0, "Any"), (6.0, "6+"), (7.0, "7+"), (8.0, "8+")], id: \.0) { r in
                        pill(r.1, draft.minRating == r.0) { draft.minRating = r.0 }
                    }
                }
                section("Already watched") {
                    pill("Hide watched", draft.hideWatched) { draft.hideWatched = true }
                    pill("Include watched", !draft.hideWatched) { draft.hideWatched = false }
                }
                HStack(spacing: 24) {
                    Button("Apply") { apply(draft) }
                    Button("Reset") { draft = ZapFilters() }
                    Button("Cancel") { apply(initial) }
                }
            }
            .padding(60)
        }
        .background(settings.theme.background.ignoresSafeArea())
        .task(id: actorQuery) {
            let q = actorQuery.trimmingCharacters(in: .whitespaces)
            guard q.count >= 2 else { suggestions = []; return }
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            let body = await TMDB.get("/search/person", ["query": q, "include_adult": "false"])
            if Task.isCancelled { return }
            suggestions = (body?["results"] as? [[String: Any]] ?? []).filter {
                let dept = $0["known_for_department"] as? String
                return dept == nil || dept == "Acting"
            }.prefix(8).compactMap { r in
                guard let id = r["id"] as? Int, let name = r["name"] as? String else { return nil }
                return ZapPerson(id: id, name: name)
            }
        }
    }

    private func section<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.headline).foregroundStyle(.secondary)
            LazyVGrid(columns: columns, alignment: .leading, spacing: 20) { content() }
        }
    }

    private func pill(_ label: String, _ selected: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).lineLimit(1).frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .overlay(alignment: .trailing) { if selected { Image(systemName: "checkmark").font(.caption.bold()) } }
        }
    }
}
