import SwiftUI

// Surprise Me: one random movie or series at a time. Another for the next one, Play to open it and start the best source,
// Save to My List, and narrow the pool by type, year, genre, actor and rating. Same behaviour as the Android and
// desktop apps. Titles already watched are skipped. Titles come from the catalogs of the user's own add-ons, so no
// TMDB key is needed.

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
    /// Catalogs Surprise Me may draw from, as "base|type|id" keys. Empty means automatic: every catalog on the home screen.
    var sourceKeys: Set<String> = []

    var activeCount: Int {
        [type != .both, yearFrom != nil || yearTo != nil, !genres.isEmpty, !people.isEmpty, minRating > 0, !sourceKeys.isEmpty].filter { $0 }.count
    }
}

struct ZapTitle: Equatable {
    var id: String
    var isSeries: Bool
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
    var addonBase: String?

    var key: String { "\(isSeries ? "series" : "movie"):\(id)" }

    var meta: MetaItem {
        var m = MetaItem(id: id, type: isSeries ? "series" : "movie", name: name, poster: poster, background: backdrop,
                         description: overview, releaseInfo: year,
                         imdbRating: rating.map { String(format: "%.1f", $0) }, genres: genres)
        m.addonBase = addonBase
        return m
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

enum ZapEmpty { case noSources, noMatches, offline }

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

/// One browsable catalog of one of the user's add-ons.
private struct ZapSource: Hashable {
    var base: String
    var type: String
    var catalogId: String
    var genreOptions: [String]
    var supportsSkip: Bool
    var supportsSearch: Bool
    var requiredExtras: [String: String]
    var key: String { "\(base)|\(type)|\(catalogId)" }
}

/// A shuffled supply of random titles for one set of filters, drawn from the catalogs of the user's add-ons.
/// Make a new one when the filters change; it never repeats a title.
@MainActor
final class ZapPool {
    private let filters: ZapFilters
    private let addons: [String]
    private let isWatched: (String) -> Bool
    private let isSaved: (String) -> Bool
    private var queue: [MetaItem] = []
    private var seen = Set<String>()
    private var sources: [ZapSource]?
    private var nextSkip: [String: Int] = [:]
    private var pagesFetched: [String: Int] = [:]
    private var exhausted = Set<String>()
    private var sourceTurns: [ZapSource] = []
    private var searched = Set<String>()
    private let maxPages = 10

    init(filters: ZapFilters, addons: [String], isWatched: @escaping (String) -> Bool, isSaved: @escaping (String) -> Bool = { _ in false }) {
        self.isSaved = isSaved
        self.filters = filters
        self.addons = addons
        self.isWatched = isWatched
    }

    /// The next title, or why there is none.
    func next() async -> (ZapTitle?, ZapEmpty?) {
        if sources == nil { sources = await loadSources() }
        guard let all = sources, !all.isEmpty else { return (nil, .noSources) }
        var refills = 0
        while refills <= 30 {
            while !queue.isEmpty {
                let item = queue.removeFirst()
                if !seen.insert("\(item.type):\(item.id)").inserted { continue }
                // Surprise Me finds new things: titles already in the library never come up.
                if isSaved(item.id) { continue }
                if filters.hideWatched, isWatched(item.id) { continue }
                return (title(from: item), nil)
            }
            refills += 1
            await refill(all)
        }
        return (nil, .noMatches)
    }

    private func loadSources() async -> [ZapSource] {
        let wanted: Set<String>
        switch filters.type {
        case .both: wanted = ["movie", "series"]
        case .movies: wanted = ["movie"]
        case .series: wanted = ["series"]
        }
        var found: [ZapSource] = []
        for base in addons {
            guard let man = await AddonClient.manifest(for: base), man.has("catalog") else { continue }
            for c in man.catalogs ?? [] where wanted.contains(c.type) {
                let extras = c.extra ?? []
                let requiredNames = Set(extras.filter { $0.isRequired == true }.map(\.name) + (c.extraRequired ?? []))
                var required: [String: String] = [:]
                var canRequest = true
                for name in requiredNames {
                    switch name {
                    case "search": canRequest = canRequest && !filters.people.isEmpty
                    case "skip": required["skip"] = "0"
                    default:
                        let options = extras.first { $0.name == name }?.options ?? []
                        let preferred = name == "genre" ? options.first { option in
                            filters.genres.contains { Self.sameGenre(option, $0) }
                        } : nil
                        if let value = preferred ?? options.randomElement() { required[name] = value }
                        else { canRequest = false }
                    }
                }
                guard canRequest else { continue }
                found.append(ZapSource(
                    base: base, type: c.type, catalogId: c.id,
                    genreOptions: extras.first { $0.name == "genre" }?.options ?? [],
                    supportsSkip: extras.contains { $0.name == "skip" } || (c.extraSupported ?? []).contains("skip") || requiredNames.contains("skip"),
                    supportsSearch: c.isSearchable, requiredExtras: required))
            }
        }
        var unique: [ZapSource] = []
        for s in found where !unique.contains(where: { $0.key == s.key }) { unique.append(s) }
        // Automatic uses every catalog on the home screen; a custom choice replaces that.
        let chosen = filters.sourceKeys.isEmpty ? unique : unique.filter { filters.sourceKeys.contains($0.key) }
        return chosen.isEmpty ? unique : chosen
    }

    private func refill(_ all: [ZapSource]) async {
        var added: [MetaItem] = []
        if !filters.people.isEmpty {
            for person in filters.people {
                for source in all where source.supportsSearch {
                    if !searched.insert("\(source.key)|\(person.name)").inserted { continue }
                    added += await AddonClient.page(base: source.base, type: source.type, catalogId: source.catalogId,
                                                    extra: source.requiredExtras.merging(["search": person.name]) { _, new in new }, skip: 0)
                }
            }
        } else {
            // Every installed catalog gets a turn before any is revisited.
            if sourceTurns.isEmpty { sourceTurns = all.filter { !exhausted.contains($0.key) }.shuffled() }
            for _ in 0..<min(5, sourceTurns.count) { added += await discoverPage(sourceTurns.removeFirst()) }
        }
        added.shuffle()
        for item in added where matches(item) && !seen.contains("\(item.type):\(item.id)") { queue.append(item) }
    }

    private func discoverPage(_ source: ZapSource) async -> [MetaItem] {
        guard !exhausted.contains(source.key) else { return [] }
        let skip = nextSkip[source.key] ?? 0
        var extra = source.requiredExtras
        if extra["skip"] != nil { extra["skip"] = String(skip) }
        for wanted in filters.genres {
            if let option = source.genreOptions.first(where: { Self.sameGenre($0, wanted) }) { extra["genre"] = option; break }
        }
        let items = await AddonClient.page(base: source.base, type: source.type, catalogId: source.catalogId,
                                           extra: extra, skip: skip)
        let count = (pagesFetched[source.key] ?? 0) + 1
        pagesFetched[source.key] = count
        if !source.supportsSkip || items.isEmpty || count >= maxPages { exhausted.insert(source.key) }
        else { nextSkip[source.key] = skip + items.count }
        return items
    }

    private func matches(_ item: MetaItem) -> Bool {
        if (item.poster ?? "").isEmpty, (item.background ?? "").isEmpty { return false }
        if filters.minRating > 0 {
            guard let r = Double(item.imdbRating ?? ""), r >= filters.minRating else { return false }
        }
        if filters.yearFrom != nil || filters.yearTo != nil {
            guard let y = Self.year(of: item) else { return false }
            if let from = filters.yearFrom, y < from { return false }
            if let to = filters.yearTo, y > to { return false }
        }
        let genres = item.genres ?? []
        if !filters.genres.isEmpty, !genres.isEmpty {
            for wanted in filters.genres where !genres.contains(where: { Self.sameGenre($0, wanted) }) { return false }
        }
        return true
    }

    private func title(from item: MetaItem) -> ZapTitle {
        ZapTitle(id: item.id, isSeries: item.type == "series", name: item.name,
                 year: Self.year(of: item).map(String.init), overview: item.description ?? "",
                 poster: item.poster, backdrop: item.background ?? item.poster,
                 rating: Double(item.imdbRating ?? "").flatMap { $0 > 0 ? $0 : nil },
                 genres: item.genres ?? [], runtimeMinutes: nil, seasons: nil, cast: [], addonBase: item.addonBase)
    }

    private static func year(of item: MetaItem) -> Int? {
        guard let info = item.releaseInfo,
              let range = info.range(of: "(19|20)\\d{2}", options: .regularExpression) else { return nil }
        return Int(info[range])
    }

    private static func normalize(_ g: String) -> String {
        g.lowercased().filter { $0.isLetter || $0.isNumber }.replacingOccurrences(of: "scifi", with: "sciencefiction")
    }

    private static func sameGenre(_ a: String, _ b: String) -> Bool {
        let x = normalize(a), y = normalize(b)
        return x == y || (x.count > 3 && y.count > 3 && (x.contains(y) || y.contains(x)))
    }
}

/// The filters the user last chose, kept while the app runs.
enum ZapMemory {
    private static let sourcesKey = "meowio_zap_sources"
    @MainActor static var filters: ZapFilters = {
        var f = ZapFilters()
        f.sourceKeys = Set(UserDefaults.standard.stringArray(forKey: sourcesKey) ?? [])
        return f
    }() {
        didSet { UserDefaults.standard.set(Array(filters.sourceKeys), forKey: ZapMemory.sourcesKey) }
    }
}

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
            Text("SURPRISE ME").font(.callout.bold()).foregroundStyle(settings.theme.accent)
            Text(t.name).font(.largeTitle.bold()).lineLimit(2)
            Text(t.metaLine).foregroundStyle(.secondary)
            if !t.genres.isEmpty { Text(t.genres.prefix(4).joined(separator: "  ·  ")).font(.callout).foregroundStyle(.secondary) }
            if !t.overview.isEmpty { Text(t.overview).lineLimit(4).frame(maxWidth: 1100, alignment: .leading) }
            if !t.cast.isEmpty { Text("Starring " + t.cast.prefix(4).joined(separator: ", ")).font(.callout).foregroundStyle(.secondary).lineLimit(2) }
            HStack(spacing: 24) {
                NavigationLink(value: AutoPlayRoute(item: t.meta)) { Label("Play", systemImage: "play.fill") }
                Button { Task { await zap() } } label: { Label("Another", systemImage: "shuffle") }.disabled(loading)
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
        case .noSources: return "Surprise Me draws from your add-ons' catalogs. Install a catalog add-on in Settings and try again."
        case .offline: return "Couldn't load titles. Check your connection."
        default: return "Nothing matches those filters. Try loosening them."
        }
    }

    private func makePool() {
        pool = ZapPool(filters: filters, addons: settings.addons, isWatched: { id in
            library.isWatched(id) || library.records.contains { $0.contentId == id } ||
                library.continueWatching.contains { $0.meta.id == id }
        }, isSaved: { id in library.saved.contains { $0.id == id } })
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
    @State private var catalogs: [AddonClient.AddonCatalog] = []

    init(initial: ZapFilters, apply: @escaping (ZapFilters) -> Void) {
        _draft = State(initialValue: initial)
        self.initial = initial
        self.apply = apply
    }

    private let columns = [GridItem(.adaptive(minimum: 260), spacing: 20)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                Text("Surprise Me filters").font(.largeTitle.bold())

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
                Text("Actors (any of the ones you add; matched by your add-ons' search)").font(.headline).foregroundStyle(.secondary)
                if !draft.people.isEmpty {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 20) {
                        ForEach(draft.people, id: \.id) { p in
                            pill(p.name + "  ✕", true) { draft.people.removeAll { $0.name == p.name } }
                        }
                    }
                }
                TextField("Type an actor's name", text: $actorQuery)
                let typed = actorQuery.trimmingCharacters(in: .whitespaces)
                if typed.count >= 2 || !suggestions.isEmpty {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 20) {
                        if typed.count >= 2, !draft.people.contains(where: { $0.name.lowercased() == typed.lowercased() }) {
                            pill("Add \u{201C}\(typed)\u{201D}", false) { draft.people.append(ZapPerson(id: 0, name: typed)); actorQuery = ""; suggestions = [] }
                        }
                        ForEach(suggestions.filter { s in !draft.people.contains { $0.name.lowercased() == s.name.lowercased() } }, id: \.id) { p in
                            pill(p.name, false) { draft.people.append(p); actorQuery = ""; suggestions = [] }
                        }
                    }
                }
                section("Minimum rating") {
                    ForEach([(0.0, "Any"), (6.0, "6+"), (7.0, "7+"), (8.0, "8+")], id: \.0) { r in
                        pill(r.1, draft.minRating == r.0) { draft.minRating = r.0 }
                    }
                }
                section("Pull titles from") {
                    pill("Automatic (home screen catalogs)", draft.sourceKeys.isEmpty) { draft.sourceKeys = [] }
                    ForEach(catalogs, id: \.self) { c in
                        let key = c.rowId
                        pill(c.title, draft.sourceKeys.contains(key)) {
                            if draft.sourceKeys.contains(key) { draft.sourceKeys.remove(key) } else { draft.sourceKeys.insert(key) }
                        }
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
        .task { catalogs = await AddonClient.addonCatalogs(settings.addons) }
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
