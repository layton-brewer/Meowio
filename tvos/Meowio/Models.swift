import Foundation

struct MetaItem: Identifiable, Codable, Hashable {
    var id: String
    var type: String
    var name: String
    var poster: String?
    var background: String?
    var description: String?
    var releaseInfo: String?
    var imdbRating: String?
    var genres: [String]?
    var videos: [Video]?

    var posterURL: URL? { poster.flatMap(URL.init(string:)) }
    var backgroundURL: URL? { (background ?? poster).flatMap(URL.init(string:)) }
}

struct Video: Identifiable, Codable, Hashable {
    var id: String
    var season: Int?
    var episode: Int?
    var title: String?
    var name: String?
    var thumbnail: String?
    var released: String?

    var displayTitle: String { title ?? name ?? "Episode \(episode ?? 0)" }
    var thumbnailURL: URL? { thumbnail.flatMap(URL.init(string:)) }
}

struct CatalogResponse: Codable { var metas: [MetaItem] }
struct MetaResponse: Codable { var meta: MetaItem }

struct StreamItem: Codable, Hashable {
    var name: String?
    var title: String?
    var description: String?
    var url: String?
    var infoHash: String?
    var behaviorHints: BehaviorHints?
    /// Name of the add-on that returned this stream. Filled in by the client, never decoded.
    var addonName: String? = nil

    struct BehaviorHints: Codable, Hashable {
        var notWebReady: Bool?
        var filename: String?
        var bingeGroup: String?
    }

    enum CodingKeys: String, CodingKey {
        case name, title, description, url, infoHash, behaviorHints
    }

    var playableURL: URL? { url.flatMap(URL.init(string:)) }
    var combinedText: String {
        [name, title, description, behaviorHints?.filename].compactMap { $0 }.joined(separator: "\n")
    }
    /// Short label for the source list: what the add-on says about this stream.
    var label: String {
        let first = (name ?? title ?? "Source").replacingOccurrences(of: "\n", with: " ")
        return first.trimmingCharacters(in: .whitespaces)
    }
    var detail: String {
        (title ?? description ?? "").replacingOccurrences(of: "\n", with: "  ")
    }
}
struct StreamResponse: Codable { var streams: [StreamItem] }

struct ContinueItem: Codable, Identifiable, Hashable {
    var id: String
    var meta: MetaItem
    var videoId: String?
    var updated: Date
}

/// One finished or partly watched item, kept for watch statistics.
struct WatchRecord: Codable, Identifiable, Hashable {
    var id: String            // movie id, or episode video id
    var contentId: String     // movie id, or show id
    var title: String
    var isEpisode: Bool
    var seconds: Double
    var completed: Bool
    var at: Date
}
