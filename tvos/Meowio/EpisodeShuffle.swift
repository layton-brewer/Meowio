import Foundation

/// Episode shuffle for one show, like the Android, phone and desktop apps. Turned on from the show's Shuffle button,
/// it stays on for that show (per profile) until Stop shuffle: every Next Episode is another random episode of the
/// same show, never the one just played, and episodes already played this session come up again only once the rest
/// have had a turn. Any episode can come up (no seen/unseen question). Movies never shuffle.
@MainActor
enum EpisodeShuffle {
    struct Setting: Codable, Equatable {
        var includeWatched = false
    }

    private static let key = "episodeShuffle"
    /// Episodes picked this session, per show, so the same few don't keep coming back.
    private static var shown: [String: Set<String>] = [:]

    private static func load() -> [String: Setting] {
        guard let data = ProfileScope.defaults.data(forKey: key),
              let saved = try? JSONDecoder().decode([String: Setting].self, from: data) else { return [:] }
        return saved
    }

    /// The show's shuffle setting, or nil when shuffle is off for it.
    static func setting(for showId: String) -> Setting? {
        showId.isEmpty ? nil : load()[showId]
    }

    static func set(_ setting: Setting?, for showId: String) {
        guard !showId.isEmpty else { return }
        var all = load()
        all[showId] = setting
        if let data = try? JSONEncoder().encode(all) { ProfileScope.defaults.set(data, forKey: key) }
        if setting == nil { shown[showId] = nil }
    }

    static func recordPlayed(_ videoId: String, showId: String) {
        shown[showId, default: []].insert(videoId)
    }

    /// A random episode from [ordered] (specials already left out), never [current].
    static func pick(showId: String, ordered: [Video], current: String?, includeWatched: Bool,
                     isWatched: (String) -> Bool) -> Video? {
        let candidates = ordered.filter { $0.id != current && (includeWatched || !isWatched($0.id)) }
        guard !candidates.isEmpty else { return nil }
        var seen = shown[showId] ?? []
        var pool = candidates.filter { !seen.contains($0.id) }
        if pool.isEmpty {
            // Everything has had a turn: start a new round.
            seen = current.map { [$0] } ?? []
            pool = candidates
        }
        guard let chosen = pool.randomElement() else { return nil }
        seen.insert(chosen.id)
        shown[showId] = seen
        return chosen
    }
}
