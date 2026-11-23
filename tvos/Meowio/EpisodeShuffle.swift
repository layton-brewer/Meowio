import Foundation

/// Episode shuffle, like the Android, phone and desktop apps. The show's Shuffle button plays a random episode, and
/// every Next Episode in that viewing session is another random episode of the same show, never the one just played;
/// episodes already played come up again only once the rest have had a turn. Any episode can come up (no seen/unseen
/// question). There is no Stop shuffle: it ends when the user leaves the player. Movies never shuffle.
@MainActor
enum EpisodeShuffle {
    /// Episodes picked this session, per show, so the same few don't keep coming back.
    private static var shown: [String: Set<String>] = [:]

    /// A new shuffle for the show: forgets what the last one played.
    static func start(showId: String) {
        shown[showId] = nil
    }

    /// A random episode from [ordered] (specials already left out), never [current].
    static func pick(showId: String, ordered: [Video], current: String?) -> Video? {
        let candidates = ordered.filter { $0.id != current }
        guard !candidates.isEmpty else { return nil }
        var seen = shown[showId] ?? []
        if let current { seen.insert(current) }
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
