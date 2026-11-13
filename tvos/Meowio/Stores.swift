import Foundation
import SwiftUI

@MainActor
final class SettingsStore: ObservableObject {
    @Published var addons: [String] { didSet { save() } }
    @Published var autoPlay: Bool { didSet { save() } }
    @Published var playlistURL: String { didSet { save() } }

    init() {
        let d = UserDefaults.standard
        addons = d.stringArray(forKey: "addons") ?? []
        autoPlay = d.object(forKey: "autoPlay") as? Bool ?? true
        playlistURL = d.string(forKey: "playlistURL") ?? ""
    }

    private func save() {
        let d = UserDefaults.standard
        d.set(addons, forKey: "addons")
        d.set(autoPlay, forKey: "autoPlay")
        d.set(playlistURL, forKey: "playlistURL")
    }

    func addAddon(_ raw: String) {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("stremio://") { s = "https://" + s.dropFirst("stremio://".count) }
        if s.hasSuffix("/manifest.json") { s = String(s.dropLast("/manifest.json".count)) }
        while s.hasSuffix("/") { s.removeLast() }
        guard !s.isEmpty, URL(string: s) != nil, !addons.contains(s) else { return }
        addons.append(s)
    }
}

@MainActor
final class LibraryStore: ObservableObject {
    @Published var continueWatching: [ContinueItem] = []

    init() {
        if let data = UserDefaults.standard.data(forKey: "continue"),
           let items = try? JSONDecoder().decode([ContinueItem].self, from: data) {
            continueWatching = items
        }
    }

    func record(meta: MetaItem, videoId: String?) {
        continueWatching.removeAll { $0.id == meta.id }
        continueWatching.insert(ContinueItem(id: meta.id, meta: meta, videoId: videoId, updated: Date()), at: 0)
        if continueWatching.count > 30 { continueWatching.removeLast() }
        if let data = try? JSONEncoder().encode(continueWatching) {
            UserDefaults.standard.set(data, forKey: "continue")
        }
    }
}

/// Resolves and caches ranked stream candidates per title/episode so playback
/// can start instantly. Preloading just calls `candidates(...)` early.
@MainActor
final class StreamStore: ObservableObject {
    private var cache: [String: [StreamItem]] = [:]
    private var inflight: [String: Task<[StreamItem], Never>] = [:]

    func candidates(type: String, id: String, addons: [String]) async -> [StreamItem] {
        let key = "\(type)/\(id)|\(addons.joined(separator: ","))"
        if let hit = cache[key], !hit.isEmpty { return hit }
        if let task = inflight[key] { return await task.value }
        let task = Task<[StreamItem], Never> {
            let ranked = await AddonClient.streams(type: type, id: id, addons: addons)
            return ranked
        }
        inflight[key] = task
        let result = await task.value
        inflight[key] = nil
        cache[key] = result
        return result
    }

    func preload(type: String, id: String, addons: [String]) {
        Task { _ = await candidates(type: type, id: id, addons: addons) }
    }
}
