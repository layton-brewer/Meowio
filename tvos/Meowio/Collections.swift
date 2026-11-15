import SwiftUI

// Collections: rows of folders (a studio, a franchise, a genre mix) made in the Android and phone apps and kept in
// the Meowio account. Apple TV shows them on Home and opens a folder to see its titles. They are read only here.

struct TmdbFilters: Codable, Hashable {
    var withGenres: String?
    var withoutGenres: String?
    var releaseDateGte: String?
    var releaseDateLte: String?
    var voteAverageGte: Double?
    var voteAverageLte: Double?
    var voteCountGte: Int?
    var withOriginalLanguage: String?
    var withOriginCountry: String?
    var withKeywords: String?
    var withoutKeywords: String?
    var withCompanies: String?
    var withoutCompanies: String?
    var withNetworks: String?
    var year: Int?
    var watchRegion: String?
    var withWatchProviders: String?
    var withoutWatchProviders: String?
}

struct CollectionSource: Codable, Hashable {
    /// "addon", "tmdb" or "trakt"; missing means an add-on catalog.
    var provider: String?
    var addonId: String?
    var type: String?
    var catalogId: String?
    var genre: String?
    var tmdbSourceType: String?
    var title: String?
    var tmdbId: Int?
    var traktListId: Int?
    var mediaType: String?
    var sortBy: String?
    var sortHow: String?
    var filters: TmdbFilters?
}

struct LegacyCatalogSource: Codable, Hashable {
    var addonId: String
    var type: String
    var catalogId: String
    var genre: String?
}

struct CollectionFolder: Codable, Identifiable, Hashable {
    var id: String
    var title: String
    var coverImageUrl: String?
    var coverEmoji: String?
    var tileShape: String?
    var hideTitle: Bool?
    var sources: [CollectionSource]?
    var catalogSources: [LegacyCatalogSource]?
    var heroBackdropUrl: String?

    var allSources: [CollectionSource] {
        if let sources, !sources.isEmpty { return sources }
        return (catalogSources ?? []).map {
            CollectionSource(provider: "addon", addonId: $0.addonId, type: $0.type, catalogId: $0.catalogId, genre: $0.genre)
        }
    }

    /// Tile size for the folder's shape: poster, wide, or square.
    var tileSize: CGSize {
        switch (tileShape ?? "SQUARE").lowercased() {
        case "poster": return CGSize(width: 220, height: 330)
        case "landscape", "wide": return CGSize(width: 400, height: 225)
        default: return CGSize(width: 260, height: 260)
        }
    }
}

struct MeowioCollection: Codable, Identifiable, Hashable {
    var id: String
    var title: String
    var backdropImageUrl: String?
    var pinToTop: Bool?
    var folders: [CollectionFolder]?
}

struct FolderRoute: Hashable {
    var collectionId: String
    var folderId: String
}

/// The TMDB key: the one the user typed in Settings, else the one built into this build. Empty means TMDB is off.
enum TMDB {
    static var key: String {
        let own = (ProfileScope.defaults.string(forKey: "tmdbKey") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return own.isEmpty ? BuildSecrets.tmdbApiKey : own
    }
    static var available: Bool { !key.isEmpty }

    static func get(_ path: String, _ query: [String: String] = [:]) async -> [String: Any]? {
        guard available, var comps = URLComponents(string: "https://api.themoviedb.org/3\(path)") else { return nil }
        var items = [URLQueryItem(name: "api_key", value: key)]
        items += query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        comps.queryItems = items
        guard let url = comps.url else { return nil }
        var r = URLRequest(url: url)
        r.timeoutInterval = 15
        guard let (data, response) = try? await URLSession.shared.data(for: r),
              (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    static func imageURL(_ path: String?, size: String) -> String? {
        guard let path, !path.isEmpty else { return nil }
        return "https://image.tmdb.org/t/p/\(size)\(path)"
    }
}

@MainActor
final class CollectionsStore: ObservableObject {
    @Published private(set) var collections: [MeowioCollection] = []
    private let store = ProfileScope.defaults
    private var resolved: [String: [FolderSection]] = [:]
    /// The collections exactly as the account has them. Edits change this, so fields only the other apps know survive.
    private var raw: [[String: Any]] = []

    struct FolderSection: Identifiable {
        var id: String
        var title: String
        var items: [MetaItem]
    }

    init() {
        if let data = store.data(forKey: "collections"),
           let list = try? JSONDecoder().decode([MeowioCollection].self, from: data) {
            collections = list
        }
        if let data = store.data(forKey: "collectionsRaw"),
           let rows = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] {
            raw = rows
        } else if let data = store.data(forKey: "collections"),
                  let rows = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] {
            raw = rows
        }
    }

    /// Pinned collections first, then the account's order.
    var ordered: [MeowioCollection] {
        collections.filter { $0.pinToTop == true } + collections.filter { $0.pinToTop != true }
    }

    func folder(_ route: FolderRoute) -> CollectionFolder? {
        collections.first { $0.id == route.collectionId }?.folders?.first { $0.id == route.folderId }
    }

    /// Pulls this profile's collections from the Meowio account.
    func sync(account: AccountStore, profileId: Int) async {
        guard let rawRows = await account.pullCollections(profileId: profileId),
              let data = try? JSONSerialization.data(withJSONObject: rawRows),
              let list = try? JSONDecoder().decode([MeowioCollection].self, from: data) else { return }
        // An empty answer never wipes collections this device already has.
        if list.isEmpty && !collections.isEmpty { return }
        collections = list
        raw = rawRows
        resolved.removeAll()
        store.set(data, forKey: "collections")
        saveRaw()
    }

    private func saveRaw() {
        if let data = try? JSONSerialization.data(withJSONObject: raw) { store.set(data, forKey: "collectionsRaw") }
    }

    // MARK: Editing

    /// Rebuilds what Home shows from the edited rows, keeps them on this device and pushes them to the account.
    private func commit(account: AccountStore, profileId: Int) {
        if let data = try? JSONSerialization.data(withJSONObject: raw),
           let list = try? JSONDecoder().decode([MeowioCollection].self, from: data) {
            collections = list
            store.set(data, forKey: "collections")
        }
        saveRaw()
        resolved.removeAll()
        Task { _ = await account.pushCollections(raw, profileId: profileId) }
    }

    private func edit(_ collectionId: String, account: AccountStore, profileId: Int, _ change: (inout [String: Any]) -> Void) {
        guard let i = raw.firstIndex(where: { ($0["id"] as? String) == collectionId }) else { return }
        change(&raw[i])
        commit(account: account, profileId: profileId)
    }

    private func editFolders(_ collectionId: String, account: AccountStore, profileId: Int,
                             _ change: (inout [[String: Any]]) -> Void) {
        edit(collectionId, account: account, profileId: profileId) { c in
            var folders = c["folders"] as? [[String: Any]] ?? []
            change(&folders)
            c["folders"] = folders
        }
    }

    func addCollection(_ title: String, account: AccountStore, profileId: Int) {
        let t = title.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        raw.append(["id": UUID().uuidString.lowercased(), "title": t, "pinToTop": false, "folders": [[String: Any]]()])
        commit(account: account, profileId: profileId)
    }

    func renameCollection(_ id: String, to title: String, account: AccountStore, profileId: Int) {
        let t = title.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        edit(id, account: account, profileId: profileId) { $0["title"] = t }
    }

    func setPinned(_ id: String, _ pinned: Bool, account: AccountStore, profileId: Int) {
        edit(id, account: account, profileId: profileId) { $0["pinToTop"] = pinned }
    }

    func removeCollection(_ id: String, account: AccountStore, profileId: Int) {
        raw.removeAll { ($0["id"] as? String) == id }
        commit(account: account, profileId: profileId)
    }

    func addFolder(_ title: String, to collectionId: String, account: AccountStore, profileId: Int) {
        let t = title.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        editFolders(collectionId, account: account, profileId: profileId) {
            $0.append(["id": UUID().uuidString.lowercased(), "title": t, "tileShape": "POSTER", "hideTitle": false,
                       "sources": [[String: Any]]()])
        }
    }

    func renameFolder(_ route: FolderRoute, to title: String, account: AccountStore, profileId: Int) {
        let t = title.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        editFolders(route.collectionId, account: account, profileId: profileId) { folders in
            if let i = folders.firstIndex(where: { ($0["id"] as? String) == route.folderId }) { folders[i]["title"] = t }
        }
    }

    func removeFolder(_ route: FolderRoute, account: AccountStore, profileId: Int) {
        editFolders(route.collectionId, account: account, profileId: profileId) { folders in
            folders.removeAll { ($0["id"] as? String) == route.folderId }
        }
    }

    /// A folder saved by an older app lists `catalogSources`. Editing turns them into plain sources first.
    private static func normalizedSources(_ folder: [String: Any]) -> [[String: Any]] {
        let sources = folder["sources"] as? [[String: Any]] ?? []
        if !sources.isEmpty { return sources }
        return (folder["catalogSources"] as? [[String: Any]] ?? []).map { c in
            var s = c
            s["provider"] = "addon"
            return s
        }
    }

    func addSource(_ source: [String: Any], to route: FolderRoute, account: AccountStore, profileId: Int) {
        editFolders(route.collectionId, account: account, profileId: profileId) { folders in
            guard let i = folders.firstIndex(where: { ($0["id"] as? String) == route.folderId }) else { return }
            var sources = Self.normalizedSources(folders[i])
            sources.append(source)
            folders[i]["sources"] = sources
        }
    }

    func removeSource(at index: Int, from route: FolderRoute, account: AccountStore, profileId: Int) {
        editFolders(route.collectionId, account: account, profileId: profileId) { folders in
            guard let i = folders.firstIndex(where: { ($0["id"] as? String) == route.folderId }) else { return }
            var sources = Self.normalizedSources(folders[i])
            guard sources.indices.contains(index) else { return }
            sources.remove(at: index)
            folders[i]["sources"] = sources
        }
    }

    // MARK: Folder contents

    func sections(for folder: CollectionFolder, settings: SettingsStore) async -> [FolderSection] {
        let key = folder.id + "|" + settings.addons.joined(separator: ",")
        if let hit = resolved[key] { return hit }
        var out: [FolderSection] = []
        for (i, source) in folder.allSources.enumerated() {
            let items = await items(for: source, addons: settings.addons)
            if !items.isEmpty {
                out.append(FolderSection(id: "\(folder.id)-\(i)", title: Self.title(for: source), items: items))
            }
        }
        if !out.isEmpty { resolved[key] = out }
        return out
    }

    private static func title(for s: CollectionSource) -> String {
        if let t = s.title, !t.isEmpty { return t }
        return s.catalogId ?? "Titles"
    }

    private func items(for source: CollectionSource, addons: [String]) async -> [MetaItem] {
        switch (source.provider ?? "addon").lowercased() {
        case "tmdb": return await tmdbItems(source)
        case "trakt": return []
        default: return await addonItems(source, addons: addons)
        }
    }

    private func addonItems(_ s: CollectionSource, addons: [String]) async -> [MetaItem] {
        guard let addonId = s.addonId, let type = s.type, let catalogId = s.catalogId else { return [] }
        var base: String?
        for candidate in [AddonClient.cinemeta] + addons {
            if let m = await AddonClient.manifest(for: candidate), m.id == addonId { base = candidate; break }
        }
        guard let base else { return [] }
        let id = catalogId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? catalogId
        var path = "\(base)/catalog/\(type)/\(id)"
        if let genre = s.genre, !genre.isEmpty,
           let g = genre.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) { path += "/genre=\(g)" }
        let list = await AddonClient.fetch(CatalogResponse.self, from: path + ".json")?.metas ?? []
        return list.map { item in
            var m = item
            m.type = type
            if base != AddonClient.cinemeta, !m.id.hasPrefix("tt") { m.addonBase = base }
            return m
        }
    }

    // MARK: TMDB sources

    private func tmdbItems(_ s: CollectionSource) async -> [MetaItem] {
        guard TMDB.available else { return [] }
        let media = (s.mediaType ?? "movie").lowercased() == "tv" ? "tv" : "movie"
        let sort = s.sortBy ?? "popularity.desc"
        var rows: [[String: Any]] = []
        switch (s.tmdbSourceType ?? "").uppercased() {
        case "LIST":
            guard let id = s.tmdbId else { return [] }
            rows = (await TMDB.get("/list/\(id)"))?["items"] as? [[String: Any]] ?? []
        case "COLLECTION":
            guard let id = s.tmdbId else { return [] }
            rows = (await TMDB.get("/collection/\(id)"))?["parts"] as? [[String: Any]] ?? []
        case "PERSON", "DIRECTOR":
            guard let id = s.tmdbId, let credits = await TMDB.get("/person/\(id)/combined_credits") else { return [] }
            if (s.tmdbSourceType ?? "").uppercased() == "DIRECTOR" {
                rows = (credits["crew"] as? [[String: Any]] ?? []).filter { ($0["job"] as? String) == "Director" }
            } else {
                rows = credits["cast"] as? [[String: Any]] ?? []
            }
            rows = rows.filter { ($0["media_type"] as? String) == media }
            rows.sort { ($0["popularity"] as? Double ?? 0) > ($1["popularity"] as? Double ?? 0) }
        default:
            var q: [String: String] = ["sort_by": sort == "original" ? "popularity.desc" : sort]
            let f = s.filters ?? TmdbFilters()
            if let v = f.withGenres { q["with_genres"] = v }
            if let v = f.withoutGenres { q["without_genres"] = v }
            if let v = f.releaseDateGte { q[media == "tv" ? "first_air_date.gte" : "primary_release_date.gte"] = v }
            if let v = f.releaseDateLte { q[media == "tv" ? "first_air_date.lte" : "primary_release_date.lte"] = v }
            if let v = f.voteAverageGte { q["vote_average.gte"] = String(v) }
            if let v = f.voteAverageLte { q["vote_average.lte"] = String(v) }
            if let v = f.voteCountGte { q["vote_count.gte"] = String(v) }
            if let v = f.withOriginalLanguage { q["with_original_language"] = v }
            if let v = f.withOriginCountry { q["with_origin_country"] = v }
            if let v = f.withKeywords { q["with_keywords"] = v }
            if let v = f.withoutKeywords { q["without_keywords"] = v }
            if let v = f.withCompanies { q["with_companies"] = v }
            if let v = f.withoutCompanies { q["without_companies"] = v }
            if let v = f.withNetworks { q["with_networks"] = v }
            if let v = f.year { q[media == "tv" ? "first_air_date_year" : "primary_release_year"] = String(v) }
            if let v = f.watchRegion { q["watch_region"] = v }
            if let v = f.withWatchProviders { q["with_watch_providers"] = v }
            if let v = f.withoutWatchProviders { q["without_watch_providers"] = v }
            if let id = s.tmdbId {
                switch (s.tmdbSourceType ?? "").uppercased() {
                case "COMPANY": q["with_companies"] = String(id)
                case "NETWORK": q["with_networks"] = String(id)
                default: break
                }
            }
            rows = (await TMDB.get("/discover/\(media)", q))?["results"] as? [[String: Any]] ?? []
        }
        return await withImdbIds(rows.prefix(40).map { $0 }, defaultMedia: media)
    }

    /// Cinemeta and the add-ons know titles by IMDb id, so each TMDB result is looked up for one.
    private func withImdbIds(_ rows: [[String: Any]], defaultMedia: String) async -> [MetaItem] {
        var found: [Int: MetaItem] = [:]
        await withTaskGroup(of: (Int, MetaItem?).self) { group in
            for (index, row) in rows.enumerated() {
                guard let id = row["id"] as? Int else { continue }
                let media = (row["media_type"] as? String) ?? defaultMedia
                guard media == "movie" || media == "tv" else { continue }
                let name = (row["title"] as? String) ?? (row["name"] as? String) ?? ""
                let poster = TMDB.imageURL(row["poster_path"] as? String, size: "w500")
                let backdrop = TMDB.imageURL(row["backdrop_path"] as? String, size: "w1280")
                let overview = row["overview"] as? String
                let date = (row["release_date"] as? String) ?? (row["first_air_date"] as? String) ?? ""
                let rating = row["vote_average"] as? Double
                group.addTask {
                    guard let ext = await TMDB.get("/\(media)/\(id)/external_ids"),
                          let imdb = ext["imdb_id"] as? String, imdb.hasPrefix("tt") else { return (index, nil) }
                    var m = MetaItem(id: imdb, type: media == "tv" ? "series" : "movie", name: name)
                    m.poster = poster
                    m.background = backdrop
                    m.description = overview
                    m.releaseInfo = String(date.prefix(4))
                    m.imdbRating = rating.map { String(format: "%.1f", $0) }
                    return (index, m)
                }
            }
            for await (index, item) in group { if let item { found[index] = item } }
        }
        return found.keys.sorted().compactMap { found[$0] }
    }
}

// MARK: - Views

/// One row on Home for a collection: its folders as tiles.
struct CollectionShelf: View {
    let collection: MeowioCollection

    var body: some View {
        if let folders = collection.folders, !folders.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text(collection.title).font(.title3.bold()).padding(.leading, 60)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 40) {
                        ForEach(folders) { folder in
                            NavigationLink(value: FolderRoute(collectionId: collection.id, folderId: folder.id)) {
                                FolderTile(folder: folder)
                            }
                            .buttonStyle(.card)
                        }
                    }
                    .padding(.horizontal, 60)
                    .padding(.vertical, 24)
                }
                .scrollClipDisabled()
            }
        }
    }
}

struct FolderTile: View {
    let folder: CollectionFolder
    @Environment(\.isFocused) private var focused

    var body: some View {
        let size = folder.tileSize
        VStack(alignment: .leading, spacing: 10) {
            ZStack {
                if let cover = folder.coverImageUrl.flatMap(URL.init(string:)) {
                    Artwork(url: cover, placeholder: folder.title)
                } else {
                    Rectangle().fill(.white.opacity(0.1))
                    Text(folder.coverEmoji ?? String(folder.title.prefix(1))).font(.system(size: 80))
                }
            }
            .frame(width: size.width, height: size.height)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            if folder.hideTitle != true {
                Text(folder.title).font(.caption).lineLimit(1)
                    .frame(width: size.width, alignment: .leading)
                    .opacity(focused ? 1 : 0.7)
            }
        }
    }
}

/// The titles in a folder, one row per source.
struct FolderView: View {
    let route: FolderRoute
    @EnvironmentObject var collections: CollectionsStore
    @EnvironmentObject var settings: SettingsStore
    @State private var sections: [CollectionsStore.FolderSection] = []
    @State private var loading = true

    var body: some View {
        let folder = collections.folder(route)
        ZStack {
            Backdrop(url: folder?.heroBackdropUrl.flatMap(URL.init(string:)), background: settings.theme.background)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 20) {
                    Text(folder?.title ?? "Collection").font(.largeTitle.bold())
                        .padding(.leading, 60).padding(.top, 40)
                    if loading { ProgressView().padding(60) }
                    ForEach(sections) { section in
                        Shelf(title: sections.count > 1 ? section.title : "", items: section.items)
                    }
                    if !loading && sections.isEmpty {
                        Text(emptyMessage(folder))
                            .padding(24).glass().padding(.horizontal, 60)
                    }
                }
                .padding(.bottom, 80)
            }
        }
        .task {
            guard let folder else { loading = false; return }
            sections = await collections.sections(for: folder, settings: settings)
            loading = false
        }
    }

    private func emptyMessage(_ folder: CollectionFolder?) -> String {
        let usesTmdb = (folder?.allSources ?? []).contains { ($0.provider ?? "").lowercased() == "tmdb" }
        if usesTmdb && !TMDB.available {
            return "This folder uses TMDB. Add a TMDB key under Settings, Collections and trailers, to see its titles."
        }
        return "Nothing to show in this folder yet. Its add-on may not be installed on this Apple TV."
    }
}
