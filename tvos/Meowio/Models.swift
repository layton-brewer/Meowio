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

    struct BehaviorHints: Codable, Hashable {
        var notWebReady: Bool?
        var filename: String?
    }

    var playableURL: URL? { url.flatMap(URL.init(string:)) }
    var combinedText: String {
        [name, title, description, behaviorHints?.filename].compactMap { $0 }.joined(separator: " ")
    }
}
struct StreamResponse: Codable { var streams: [StreamItem] }

struct Channel: Identifiable, Hashable {
    var id: String { url.absoluteString }
    var name: String
    var group: String
    var logo: URL?
    var url: URL
}

struct ContinueItem: Codable, Identifiable, Hashable {
    var id: String
    var meta: MetaItem
    var videoId: String?
    var updated: Date
}
