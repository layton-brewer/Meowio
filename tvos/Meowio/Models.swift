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
    /// Add-on that listed this title, for titles that are not on Cinemeta. Filled in by the client.
    var addonBase: String?
    /// Trailer links some metadata sources list (YouTube ids).
    var trailers: [TrailerRef]?
    var trailerStreams: [TrailerRef]?
    /// Title logo art and running time, when the metadata source lists them (Cinemeta does).
    var logo: String?
    var runtime: String?

    var logoURL: URL? { logo.flatMap(URL.init(string:)) }
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
    /// Episode synopsis; add-ons use either key.
    var overview: String?
    var description: String?

    var synopsis: String? { (overview ?? description).flatMap { $0.isEmpty ? nil : $0 } }

    var displayTitle: String { title ?? name ?? "Episode \(episode ?? 0)" }
    var thumbnailURL: URL? { thumbnail.flatMap(URL.init(string:)) }
}

/// Add-ons are loose about types (a rating or year sent as a number, a missing type), so titles decode leniently and a
/// catalog keeps every entry it can read instead of failing as a whole.
extension MetaItem {
    enum CodingKeys: String, CodingKey {
        case id, type, name, poster, background, description, releaseInfo, imdbRating, genres, videos, addonBase
        case trailers, trailerStreams, logo, runtime
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.flexString(.id), !id.isEmpty else {
            throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "missing id")
        }
        self.id = id
        type = c.flexString(.type) ?? ""
        name = c.flexString(.name) ?? ""
        poster = c.flexString(.poster)
        background = c.flexString(.background)
        description = c.flexString(.description)
        releaseInfo = c.flexString(.releaseInfo)
        imdbRating = c.flexString(.imdbRating)
        genres = c.lossy(String.self, .genres)
        videos = c.lossy(Video.self, .videos)
        addonBase = c.flexString(.addonBase)
        trailers = c.lossy(TrailerRef.self, .trailers)
        trailerStreams = c.lossy(TrailerRef.self, .trailerStreams)
        logo = c.flexString(.logo)
        runtime = c.flexString(.runtime)
    }
}

extension Video {
    enum CodingKeys: String, CodingKey {
        case id, season, episode, title, name, thumbnail, released, overview, description
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.flexString(.id), !id.isEmpty else {
            throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "missing id")
        }
        self.id = id
        season = c.flexInt(.season)
        episode = c.flexInt(.episode)
        title = c.flexString(.title)
        name = c.flexString(.name)
        thumbnail = c.flexString(.thumbnail)
        released = c.flexString(.released)
        overview = c.flexString(.overview)
        description = c.flexString(.description)
    }
}

/// Decodes one array entry, or nothing when that entry is malformed.
struct LossyEntry<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
}

extension KeyedDecodingContainer {
    /// A string, or a number written as a string.
    func flexString(_ key: Key) -> String? {
        if let s = try? decodeIfPresent(String.self, forKey: key) { return s }
        if let i = try? decodeIfPresent(Int.self, forKey: key) { return String(i) }
        if let d = try? decodeIfPresent(Double.self, forKey: key) { return String(d) }
        return nil
    }

    func flexInt(_ key: Key) -> Int? {
        if let i = try? decodeIfPresent(Int.self, forKey: key) { return i }
        if let s = try? decodeIfPresent(String.self, forKey: key) { return Int(s) }
        if let d = try? decodeIfPresent(Double.self, forKey: key) { return Int(d) }
        return nil
    }

    /// Every readable entry of an array; a single value counts as a one-item array.
    func lossy<T: Decodable>(_ type: T.Type, _ key: Key) -> [T]? {
        if let list = try? decodeIfPresent([LossyEntry<T>].self, forKey: key) { return list.compactMap(\.value) }
        if let one = try? decodeIfPresent(T.self, forKey: key) { return [one] }
        return nil
    }
}

struct CatalogResponse: Decodable {
    var metas: [MetaItem]
    enum CodingKeys: String, CodingKey { case metas }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        metas = c.lossy(MetaItem.self, .metas) ?? []
    }
}
struct MetaResponse: Codable { var meta: MetaItem }

struct StreamItem: Codable, Hashable {
    var name: String?
    var title: String?
    var description: String?
    var url: String?
    var infoHash: String?
    /// Which file in the torrent this result is about (torrent add-ons such as Torrentio).
    var fileIdx: Int?
    var behaviorHints: BehaviorHints?
    /// Name of the add-on that returned this stream. Filled in by the client, never decoded.
    var addonName: String? = nil
    /// Comes from one of the user's own media servers (Jellyfin, Emby). Filled in by the client, never decoded.
    var isServer: Bool = false

    struct BehaviorHints: Codable, Hashable {
        var notWebReady: Bool?
        var filename: String?
        var bingeGroup: String?
        var proxyHeaders: ProxyHeaders?
    }

    struct ProxyHeaders: Codable, Hashable {
        var request: [String: String]?
    }

    /// Extra request headers the add-on asks for, merged over the request's own.
    func headers(merging base: [String: String]) -> [String: String] {
        base.merging(behaviorHints?.proxyHeaders?.request ?? [:]) { _, new in new }
    }

    enum CodingKeys: String, CodingKey {
        case name, title, description, url, infoHash, fileIdx, behaviorHints
    }

    /// A torrent result that needs a debrid service to become a link.
    var isTorrentOnly: Bool { playableURL == nil && !(infoHash ?? "").isEmpty }

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

/// A subtitle file offered by a subtitles add-on (OpenSubtitles and similar).
struct SubtitleItem: Codable, Hashable, Identifiable {
    var id: String?
    var url: String
    var lang: String?
    /// Add-on that returned it. Filled in by the client.
    var addonName: String? = nil

    enum CodingKeys: String, CodingKey { case id, url, lang }

    var label: String {
        let code = (lang ?? "").lowercased()
        let name = Locale.current.localizedString(forLanguageCode: String(code.prefix(2))) ?? code.uppercased()
        let shown = (code.count == 3 ? (Locale.current.localizedString(forLanguageCode: code) ?? name) : name)
        return [shown.isEmpty ? "Subtitles" : shown, addonName].compactMap { $0 }.joined(separator: " · ")
    }
}
struct SubtitleResponse: Codable { var subtitles: [SubtitleItem] }

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
    var duration: Double? = nil
}

/// Show id, season and episode read from a Cinemeta style episode id (tt123:1:4).
struct EpisodeRef: Hashable {
    var showId: String
    var season: Int
    var episode: Int

    init?(_ videoId: String?) {
        guard let videoId else { return nil }
        let parts = videoId.split(separator: ":")
        guard parts.count >= 3, let s = Int(parts[parts.count - 2]), let e = Int(parts[parts.count - 1]) else { return nil }
        showId = parts.dropLast(2).joined(separator: ":")
        season = s
        episode = e
    }
}

/// Languages offered for audio and subtitle preferences: two letter code, three letter codes, name.
enum Languages {
    struct Language: Hashable {
        var code: String
        var alt: [String]
        var name: String
        init(_ code: String, _ alt: [String], _ name: String) { self.code = code; self.alt = alt; self.name = name }
    }

    static let all: [Language] = [
        Language("en", ["eng"], "English"), Language("es", ["spa"], "Spanish"), Language("fr", ["fre", "fra"], "French"),
        Language("de", ["ger", "deu"], "German"), Language("it", ["ita"], "Italian"), Language("pt", ["por"], "Portuguese"),
        Language("nl", ["dut", "nld"], "Dutch"), Language("ru", ["rus"], "Russian"), Language("ja", ["jpn"], "Japanese"),
        Language("ko", ["kor"], "Korean"), Language("zh", ["chi", "zho"], "Chinese"), Language("ar", ["ara"], "Arabic"),
        Language("hi", ["hin"], "Hindi"), Language("pl", ["pol"], "Polish"), Language("tr", ["tur"], "Turkish"),
        Language("sv", ["swe"], "Swedish"), Language("da", ["dan"], "Danish"), Language("no", ["nor", "nob"], "Norwegian"),
        Language("fi", ["fin"], "Finnish"), Language("el", ["gre", "ell"], "Greek"), Language("he", ["heb"], "Hebrew"),
        Language("cs", ["cze", "ces"], "Czech"), Language("hu", ["hun"], "Hungarian"), Language("ro", ["rum", "ron"], "Romanian"),
        Language("th", ["tha"], "Thai"), Language("vi", ["vie"], "Vietnamese"), Language("id", ["ind"], "Indonesian"), Language("uk", ["ukr"], "Ukrainian")
    ]

    /// Every code that means [code], for matching track and subtitle languages.
    static func codes(for code: String) -> [String] {
        guard !code.isEmpty else { return [] }
        return [code] + (all.first { $0.code == code }?.alt ?? [])
    }

    static func matches(_ trackLang: String?, _ code: String) -> Bool {
        guard let t = trackLang?.lowercased(), !t.isEmpty, !code.isEmpty else { return false }
        let wanted = codes(for: code)
        return wanted.contains(t) || wanted.contains(String(t.prefix(2))) || t.hasPrefix(code + "-") || t.hasPrefix(code + "_")
    }
}
