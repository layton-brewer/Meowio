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
    @State private var movies: [MetaItem] = []
    @State private var series: [MetaItem] = []
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    Text("Meowio").font(.largeTitle.bold()).padding(.leading, 60)
                    Shelf(title: "Continue Watching", items: library.continueWatching.map(\.meta))
                    Shelf(title: "Popular Movies", items: movies)
                    Shelf(title: "Popular Series", items: series)
                    if loaded && movies.isEmpty && series.isEmpty {
                        Text("Couldn't load the catalog. Check your connection.").padding(60)
                    }
                }
            }
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

struct PlayRequest: Identifiable {
    let id = UUID()
    let meta: MetaItem
    let videoId: String?
    let candidates: [StreamItem]
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

    private var meta: MetaItem { full ?? item }
    private var seasons: [Int] {
        Array(Set((meta.videos ?? []).compactMap(\.season).filter { $0 > 0 })).sorted()
    }
    private var episodes: [Video] {
        (meta.videos ?? []).filter { $0.season == season }.sorted { ($0.episode ?? 0) < ($1.episode ?? 0) }
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            AsyncImage(url: meta.backgroundURL) { phase in
                if case .success(let image) = phase { image.resizable().aspectRatio(contentMode: .fill) }
                else { Color.black }
            }
            .ignoresSafeArea()
            LinearGradient(colors: [.black.opacity(0.9), .black.opacity(0.5), .black.opacity(0.9)],
                           startPoint: .leading, endPoint: .trailing).ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text(meta.name).font(.largeTitle.bold())
                    HStack(spacing: 20) {
                        if let r = meta.releaseInfo { Text(r) }
                        if let r = meta.imdbRating { Text("★ \(r)") }
                    }
                    if let d = meta.description {
                        Text(d).font(.body).lineLimit(5).frame(maxWidth: 1100, alignment: .leading)
                    }
                    if meta.type == "movie" {
                        Button { Task { await play(videoId: meta.id) } } label: {
                            Label(resolving ? "Finding best source…" : "Play", systemImage: "play.fill")
                                .padding(.horizontal, 30)
                        }
                        .disabled(resolving)
                    } else {
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
                            Button { Task { await play(videoId: ep.id) } } label: {
                                HStack {
                                    Text("\(ep.episode ?? 0). \(ep.displayTitle)")
                                    Spacer()
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
            if let first = seasons.first { season = seasons.contains(1) ? 1 : first }
            // Preload sources as soon as the title opens.
            if item.type == "movie" {
                streams.preload(type: "movie", id: item.id, addons: settings.addons)
            } else if let ep = episodes.first {
                streams.preload(type: "series", id: ep.id, addons: settings.addons)
            }
        }
        .fullScreenCover(item: $playRequest) { req in
            PlayerScreen(request: req)
        }
    }

    private func play(videoId: String) async {
        message = nil
        guard !settings.addons.isEmpty else {
            message = "Add a stream add-on in Settings first."
            return
        }
        resolving = true
        let list = await streams.candidates(type: meta.type, id: videoId, addons: settings.addons)
        resolving = false
        guard !list.isEmpty else {
            message = "No playable sources found."
            return
        }
        library.record(meta: meta, videoId: videoId)
        playRequest = PlayRequest(meta: meta, videoId: videoId, candidates: list)
        // Warm the next episode while this one plays.
        if meta.type == "series", let idx = (meta.videos ?? []).firstIndex(where: { $0.id == videoId }),
           idx + 1 < (meta.videos ?? []).count {
            streams.preload(type: "series", id: meta.videos![idx + 1].id, addons: settings.addons)
        }
    }
}
