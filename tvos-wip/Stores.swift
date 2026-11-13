import Foundation
import SwiftUI

/// Continue watching, saved titles and the watch records behind the statistics.
@MainActor
final class LibraryStore: ObservableObject {
    @Published var continueWatching: [ContinueItem] = []
    @Published var saved: [MetaItem] = []
    @Published var records: [WatchRecord] = []

    init() {
        let d = UserDefaults.standard
        if let data = d.data(forKey: "continue"), let items = try? JSONDecoder().decode([ContinueItem].self, from: data) {
            continueWatching = items
        }
        if let data = d.data(forKey: "saved"), let items = try? JSONDecoder().decode([MetaItem].self, from: data) {
            saved = items
        }
        if let data = d.data(forKey: "records"), let items = try? JSONDecoder().decode([WatchRecord].self, from: data) {
            records = items
        }
    }

    private func persist() {
        let d = UserDefaults.standard
        if let data = try? JSONEncoder().encode(continueWatching) { d.set(data, forKey: "continue") }
        if let data = try? JSONEncoder().encode(saved) { d.set(data, forKey: "saved") }
        if let data = try? JSONEncoder().encode(records) { d.set(data, forKey: "records") }
    }

    func record(meta: MetaItem, videoId: String?) {
        var slim = meta
        slim.videos = nil
        continueWatching.removeAll { $0.id == meta.id }
        continueWatching.insert(ContinueItem(id: meta.id, meta: slim, videoId: videoId, updated: Date()), at: 0)
        if continueWatching.count > 30 { continueWatching.removeLast() }
        persist()
    }

    func isSaved(_ meta: MetaItem) -> Bool { saved.contains { $0.id == meta.id } }

    func toggleSaved(_ meta: MetaItem) {
        if let i = saved.firstIndex(where: { $0.id == meta.id }) {
            saved.remove(at: i)
        } else {
            var slim = meta
            slim.videos = nil
            saved.insert(slim, at: 0)
        }
        persist()
    }

    /// Stores how far an item was played, for the watch statistics.
    func recordPlay(meta: MetaItem, videoId: String?, seconds: Double, completed: Bool) {
        let itemId = videoId ?? meta.id
        let isEpisode = videoId != nil && meta.type == "series"
        records.removeAll { $0.id == itemId }
        records.append(WatchRecord(id: itemId, contentId: meta.id, title: meta.name, isEpisode: isEpisode,
                                   seconds: seconds, completed: completed, at: Date()))
        if records.count > 2000 { records.removeFirst(records.count - 2000) }
        persist()
    }
}

/// Finds and caches source candidates per title or episode so playback can start at once.
/// Preloading simply asks for the candidates early.
@MainActor
final class StreamStore: ObservableObject {
    private var cache: [String: [StreamItem]] = [:]
    private var inflight: [String: Task<[StreamItem], Never>] = [:]
    /// The release (binge group or file name) that last played for each show.
    private var lastRelease: [String: String] = [:]

    private func key(_ type: String, _ id: String, _ addons: [String]) -> String {
        "\(type)/\(id)|\(addons.joined(separator: ","))"
    }

    private func raw(type: String, id: String, addons: [String]) async -> [StreamItem] {
        let k = key(type, id, addons)
        if let hit = cache[k], !hit.isEmpty { return hit }
        if let task = inflight[k] { return await task.value }
        let task = Task<[StreamItem], Never> { await AddonClient.streams(type: type, id: id, addons: addons) }
        inflight[k] = task
        let result = await task.value
        inflight[k] = nil
        if !result.isEmpty { cache[k] = result }
        return result
    }

    /// Ranked candidates, best first.
    func candidates(type: String, id: String, parentId: String, settings: SettingsStore) async -> [StreamItem] {
        let list = await raw(type: type, id: id, addons: settings.addons)
        var ranked = SmartRanker.rank(list, isSeries: type == "series", pinned: Set(settings.pinnedSources))
        if type == "series", settings.sameRelease, let release = lastRelease[parentId],
           let i = ranked.firstIndex(where: { releaseKey($0) == release }), i > 0 {
            let match = ranked.remove(at: i)
            ranked.insert(match, at: 0)
        }
        return ranked
    }

    func preload(type: String, id: String, settings: SettingsStore) {
        guard settings.streamMode == .smart, !settings.addons.isEmpty else { return }
        let addons = settings.addons
        Task { _ = await raw(type: type, id: id, addons: addons) }
    }

    func rememberRelease(parentId: String, stream: StreamItem) {
        if let k = releaseKey(stream) { lastRelease[parentId] = k }
    }

    private func releaseKey(_ s: StreamItem) -> String? {
        if let g = s.behaviorHints?.bingeGroup, !g.isEmpty { return g }
        return nil
    }
}
