import SwiftUI

struct PosterCard: View {
    let item: MetaItem
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            AsyncImage(url: item.posterURL) { phase in
                switch phase {
                case .success(let image): image.resizable().aspectRatio(2/3, contentMode: .fill)
                default: Rectangle().fill(.ultraThinMaterial).overlay(Text(item.name).padding())
                }
            }
            .frame(width: 220, height: 330)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            Text(item.name).font(.caption).lineLimit(1).frame(width: 220, alignment: .leading)
        }
    }
}

struct Shelf: View {
    let title: String
    let items: [MetaItem]
    var body: some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 16) {
                Text(title).font(.title3.bold()).padding(.leading, 60)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 40) {
                        ForEach(items) { item in
                            NavigationLink(value: item) { PosterCard(item: item) }
                                .buttonStyle(.card)
                        }
                    }
                    .padding(.horizontal, 60)
                    .padding(.vertical, 20)
                }
            }
        }
    }
}

struct HomeView: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: SettingsStore
    @State private var movies: [MetaItem] = []
    @State private var series: [MetaItem] = []
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    Text("Meowio").font(.largeTitle.bold()).foregroundStyle(settings.theme.accent).padding(.leading, 60)
                    if settings.addons.isEmpty {
                        Text("Add a stream add-on in Settings and Meowio will pick and play sources for you.")
                            .padding(24).glass().padding(.horizontal, 60)
                    }
                    Shelf(title: "Continue Watching", items: library.continueWatching.map(\.meta))
                    Shelf(title: "Popular Movies", items: movies)
                    Shelf(title: "Popular Series", items: series)
                    if loaded && movies.isEmpty && series.isEmpty {
                        Text("Couldn't load the catalog. Check your connection.").padding(60)
                    }
                }
            }
            .background(settings.theme.background.ignoresSafeArea())
            .navigationDestination(for: MetaItem.self) { DetailView(item: $0) }
        }
        .task {
            guard !loaded else { return }
            async let m = AddonClient.catalog(type: "movie")
            async let s = AddonClient.catalog(type: "series")
            movies = await m
            series = await s
            loaded = true
        }
    }
}

struct SearchView: View {
    @EnvironmentObject var settings: SettingsStore
    @State private var query = ""
    @State private var results: [MetaItem] = []

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    Shelf(title: "Movies", items: results.filter { $0.type == "movie" })
                    Shelf(title: "Series", items: results.filter { $0.type == "series" })
                }
            }
            .background(settings.theme.background.ignoresSafeArea())
            .navigationDestination(for: MetaItem.self) { DetailView(item: $0) }
        }
        .searchable(text: $query)
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard q.count >= 2 else { results = []; return }
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            async let m = AddonClient.catalog(type: "movie", search: q)
            async let s = AddonClient.catalog(type: "series", search: q)
            results = await m + s
        }
    }
}

struct LibraryView: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: SettingsStore

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    Text("Library").font(.largeTitle.bold()).padding(.leading, 60)
                    Shelf(title: "Saved", items: library.saved)
                    Shelf(title: "Continue Watching", items: library.continueWatching.map(\.meta))
                    if library.saved.isEmpty && library.continueWatching.isEmpty {
                        Text("Save titles from their detail page and they will show up here.").padding(60)
                    }
                }
            }
            .background(settings.theme.background.ignoresSafeArea())
            .navigationDestination(for: MetaItem.self) { DetailView(item: $0) }
        }
    }
}

struct DetailView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var streams: StreamStore
    let item: MetaItem
    @State private var full: MetaItem?
    @State private var season = 1
    @State private var playRequest: PlayRequest?
    @State private var resolving = false
    @State private var message: String?
    @State private var picker: PickerState?

    struct PickerState: Identifiable {
        let id = UUID()
        let videoId: String
        let title: String
        let candidates: [StreamItem]
    }

    private var meta: MetaItem { full ?? item }
    private var seasons: [Int] {
        Array(Set((meta.videos ?? []).compactMap(\.season).filter { $0 > 0 })).sorted()
    }
    private var episodes: [Video] {
        (meta.videos ?? []).filter { $0.season == season }.sorted { ($0.episode ?? 0) < ($1.episode ?? 0) }
    }
    /// Episodes in watching order, specials left out.
    private var ordered: [Video] {
        (meta.videos ?? []).filter { ($0.season ?? 0) > 0 }
            .sorted { (($0.season ?? 0), ($0.episode ?? 0)) < (($1.season ?? 0), ($1.episode ?? 0)) }
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            AsyncImage(url: meta.backgroundURL) { phase in
                if case .success(let image) = phase { image.resizable().aspectRatio(contentMode: .fill) }
                else { settings.theme.background }
            }
            .ignoresSafeArea()
            LinearGradient(colors: [settings.theme.background.opacity(0.95), settings.theme.background.opacity(0.55), settings.theme.background.opacity(0.95)],
                           startPoint: .leading, endPoint: .trailing).ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text(meta.name).font(.largeTitle.bold())
                    HStack(spacing: 20) {
                        if let r = meta.releaseInfo { Text(r) }
                        if let r = meta.imdbRating { Text("★ \(r)") }
                        if let g = meta.genres, !g.isEmpty { Text(g.prefix(3).joined(separator: " · ")) }
                    }
                    if let d = meta.description {
                        Text(d).font(.body).lineLimit(5).frame(maxWidth: 1100, alignment: .leading)
                    }
                    HStack(spacing: 24) {
                        if meta.type == "movie" {
                            Button { Task { await play(videoId: meta.id, title: meta.name) } } label: {
                                Label(resolving ? "Finding best source…" : "Play", systemImage: "play.fill")
                                    .padding(.horizontal, 30)
                            }
                            .disabled(resolving)
                        } else if let next = nextUp {
                            Button { Task { await play(videoId: next.id, title: next.displayTitle) } } label: {
                                Label(resolving ? "Finding best source…" : nextLabel(next), systemImage: "play.fill")
                                    .padding(.horizontal, 30)
                            }
                            .disabled(resolving)
                        }
                        Button { library.toggleSaved(meta) } label: {
                            Label(library.isSaved(meta) ? "Saved" : "Save", systemImage: library.isSaved(meta) ? "bookmark.fill" : "bookmark")
                        }
                    }
                    if meta.type != "movie" {
                        if seasons.count > 1 {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack {
                                    ForEach(seasons, id: \.self) { s in
                                        Button("Season \(s)") { season = s }
                                    }
                                }
                                .padding(.vertical, 10)
                            }
                        }
                        ForEach(episodes) { ep in
                            Button { Task { await play(videoId: ep.id, title: ep.displayTitle) } } label: {
                                HStack {
                                    Text("\(ep.episode ?? 0). \(ep.displayTitle)")
                                    Spacer()
                                    if watched(ep) { Image(systemName: "checkmark.circle.fill") }
                                    Image(systemName: "play.fill")
                                }
                                .padding(.horizontal, 20)
                            }
                            .disabled(resolving)
                        }
                    }
                    if let message { Text(message).foregroundStyle(.orange) }
                }
                .padding(80)
            }
        }
        .task {
            full = await AddonClient.meta(type: item.type, id: item.id)
            if let resume = library.continueWatching.first(where: { $0.id == item.id })?.videoId,
               let s = (full?.videos ?? []).first(where: { $0.id == resume })?.season, s > 0 {
                season = s
            } else if let first = seasons.first {
                season = seasons.contains(1) ? 1 : first
            }
            preloadForOpen()
        }
        .fullScreenCover(item: $playRequest) { req in
            PlayerScreen(request: req, settings: settings)
        }
        .sheet(item: $picker) { state in
            SourcePicker(state: state) { chosen in
                picker = nil
                start(videoId: state.videoId, title: state.title, candidates: chosen)
            }
        }
    }

    private func watched(_ ep: Video) -> Bool {
        library.records.contains { $0.id == ep.id && $0.completed }
    }

    /// The first episode not finished yet, or the last one watched when it is only partly seen.
    private var nextUp: Video? {
        let list = ordered
        guard !list.isEmpty else { return nil }
        if let resume = library.continueWatching.first(where: { $0.id == item.id })?.videoId,
           let i = list.firstIndex(where: { $0.id == resume }) {
            if library.records.contains(where: { $0.id == resume && $0.completed }), i + 1 < list.count { return list[i + 1] }
            return list[i]
        }
        return list.first
    }

    private func nextLabel(_ v: Video) -> String {
        let started = library.continueWatching.contains { $0.id == item.id }
        return "\(started ? "Resume" : "Play") S\(v.season ?? 0)E\(v.episode ?? 0)"
    }

    /// Finds sources early: the movie, or the first episode of every season plus the next one up.
    private func preloadForOpen() {
        if item.type == "movie" {
            streams.preload(type: "movie", id: item.id, settings: settings)
            return
        }
        if let n = nextUp { streams.preload(type: "series", id: n.id, settings: settings) }
        for s in seasons.prefix(8) {
            if let first = (meta.videos ?? []).filter({ $0.season == s }).min(by: { ($0.episode ?? 0) < ($1.episode ?? 0) }) {
                streams.preload(type: "series", id: first.id, settings: settings)
            }
        }
    }

    private func play(videoId: String, title: String) async {
        message = nil
        guard !settings.addons.isEmpty else {
            message = "Add a stream add-on in Settings first."
            return
        }
        resolving = true
        let list = await streams.candidates(type: meta.type, id: videoId, parentId: meta.id, settings: settings)
        resolving = false
        guard !list.isEmpty else {
            message = "No playable sources found."
            return
        }
        if settings.streamMode == .manual {
            picker = PickerState(videoId: videoId, title: title, candidates: list)
        } else {
            start(videoId: videoId, title: title, candidates: list)
        }
    }

    private func start(videoId: String, title: String, candidates: [StreamItem]) {
        library.record(meta: meta, videoId: meta.type == "movie" ? nil : videoId)
        playRequest = makeRequest(videoId: videoId, title: title, candidates: candidates)
    }

    private func makeRequest(videoId: String, title: String, candidates: [StreamItem]) -> PlayRequest {
        if meta.type == "series", let cur = ordered.first(where: { $0.id == videoId }) {
            return DetailView.chain(meta: meta, ordered: ordered, current: cur, candidates: candidates,
                                    streams: streams, settings: settings, library: library)
        }
        return PlayRequest(meta: meta, videoId: meta.type == "movie" ? nil : videoId, title: title, candidates: candidates)
    }

    /// Builds the request for [current] and, recursively, for the episode after it.
    @MainActor
    static func chain(meta: MetaItem, ordered: [Video], current: Video, candidates: [StreamItem],
                      streams: StreamStore, settings: SettingsStore, library: LibraryStore) -> PlayRequest {
        var req = PlayRequest(meta: meta, videoId: current.id, title: current.displayTitle, candidates: candidates)
        if let idx = ordered.firstIndex(where: { $0.id == current.id }), idx + 1 < ordered.count {
            let next = ordered[idx + 1]
            if settings.preloadNext { streams.preload(type: "series", id: next.id, settings: settings) }
            req.nextEpisode = {
                let list = await streams.candidates(type: "series", id: next.id, parentId: meta.id, settings: settings)
                guard !list.isEmpty else { return nil }
                library.record(meta: meta, videoId: next.id)
                return DetailView.chain(meta: meta, ordered: ordered, current: next, candidates: list,
                                        streams: streams, settings: settings, library: library)
            }
        }
        return req
    }
}

/// Manual mode: the user chooses from the ranked sources.
struct SourcePicker: View {
    let state: DetailView.PickerState
    let onChoose: ([StreamItem]) -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(state.candidates.prefix(40).enumerated()), id: \.offset) { i, s in
                    Button {
                        var reordered = state.candidates
                        let picked = reordered.remove(at: i)
                        reordered.insert(picked, at: 0)
                        onChoose(reordered)
                    } label: {
                        VStack(alignment: .leading) {
                            Text(s.label).font(.headline)
                            Text("\(s.addonName ?? "")  \(s.detail)").font(.caption).lineLimit(2)
                        }
                    }
                }
            }
            .navigationTitle(state.title)
        }
    }
}
