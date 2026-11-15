import SwiftUI
import AVKit

// Trailers: the trailer's YouTube id comes from the title's own metadata (Cinemeta lists one for most titles) or from
// TMDB when a key is set. The video link is then looked up the way the YouTube iOS app asks for it, which gives an HLS
// stream Apple TV plays natively. YouTube can change this at any time, so the Trailer button only appears once a
// playable link has actually been found.

/// A trailer entry in a title's metadata. Add-ons write these in a few shapes, so decoding never fails.
struct TrailerRef: Codable, Hashable {
    var source: String?
    var ytId: String?
    var type: String?

    init(from decoder: Decoder) throws {
        if let single = try? decoder.singleValueContainer().decode(String.self) {
            source = single
            return
        }
        if let c = try? decoder.container(keyedBy: Keys.self) {
            source = try? c.decodeIfPresent(String.self, forKey: .source)
            ytId = try? c.decodeIfPresent(String.self, forKey: .ytId)
            type = try? c.decodeIfPresent(String.self, forKey: .type)
        }
    }

    enum Keys: String, CodingKey { case source, ytId, type }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Keys.self)
        try c.encodeIfPresent(source, forKey: .source)
        try c.encodeIfPresent(ytId, forKey: .ytId)
        try c.encodeIfPresent(type, forKey: .type)
    }

    var videoId: String? {
        for candidate in [ytId, source] {
            if let v = candidate, v.count == 11, v.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil { return v }
        }
        return nil
    }
}

enum Trailers {
    private static var cache: [String: URL?] = [:]

    /// A link the player can open for this title's trailer, or nil when there is none.
    static func playableURL(for meta: MetaItem) async -> URL? {
        if let hit = cache[meta.id] { return hit }
        var ids: [String] = []
        for ref in (meta.trailerStreams ?? []) + (meta.trailers ?? []) {
            if let v = ref.videoId, !ids.contains(v) { ids.append(v) }
        }
        if ids.isEmpty, let tmdb = await tmdbTrailerIds(for: meta) { ids = tmdb }
        for id in ids.prefix(3) {
            if let url = await YouTubeLink.resolve(videoId: id) {
                cache[meta.id] = url
                return url
            }
        }
        cache[meta.id] = .some(nil)
        return nil
    }

    private static func tmdbTrailerIds(for meta: MetaItem) async -> [String]? {
        guard TMDB.available, meta.id.hasPrefix("tt"),
              let found = await TMDB.get("/find/\(meta.id)", ["external_source": "imdb_id"]) else { return nil }
        let media = meta.type == "movie" ? "movie" : "tv"
        let results = found[media == "movie" ? "movie_results" : "tv_results"] as? [[String: Any]]
        guard let tmdbId = results?.first?["id"] as? Int,
              let videos = (await TMDB.get("/\(media)/\(tmdbId)/videos"))?["results"] as? [[String: Any]] else { return nil }
        let youtube = videos.filter { ($0["site"] as? String) == "YouTube" && ($0["key"] as? String) != nil }
        let ranked = youtube.sorted { a, b in
            func score(_ v: [String: Any]) -> Int {
                var s = 0
                if (v["type"] as? String) == "Trailer" { s += 4 } else if (v["type"] as? String) == "Teaser" { s += 1 }
                if (v["official"] as? Bool) == true { s += 2 }
                if (v["iso_639_1"] as? String) == "en" { s += 1 }
                return s
            }
            return score(a) > score(b)
        }
        return ranked.compactMap { $0["key"] as? String }
    }
}

/// Looks up a playable link for a YouTube video through the same request the YouTube iOS app makes.
enum YouTubeLink {
    private static let version = "20.10.4"

    static func resolve(videoId: String) async -> URL? {
        guard let url = URL(string: "https://www.youtube.com/youtubei/v1/player?prettyPrint=false") else { return nil }
        let body: [String: Any] = [
            "videoId": videoId,
            "contentCheckOk": true,
            "racyCheckOk": true,
            "context": ["client": [
                "clientName": "IOS", "clientVersion": version, "deviceMake": "Apple", "deviceModel": "iPhone16,2",
                "osName": "iPhone", "osVersion": "18.3.2.22D82", "hl": "en", "gl": "US"
            ]]
        ]
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.timeoutInterval = 15
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("com.google.ios.youtube/\(version) (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)", forHTTPHeaderField: "User-Agent")
        r.setValue("5", forHTTPHeaderField: "X-YouTube-Client-Name")
        r.setValue(version, forHTTPHeaderField: "X-YouTube-Client-Version")
        r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        guard let (data, response) = try? await URLSession.shared.data(for: r),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (json["playabilityStatus"] as? [String: Any])?["status"] as? String == "OK",
              let streaming = json["streamingData"] as? [String: Any] else { return nil }
        if let hls = streaming["hlsManifestUrl"] as? String, let link = URL(string: hls) { return link }
        // Otherwise the best picture-and-sound file YouTube offers in one piece.
        let formats = (streaming["formats"] as? [[String: Any]] ?? [])
            .filter { ($0["url"] as? String) != nil && ($0["mimeType"] as? String)?.hasPrefix("video/mp4") == true }
            .sorted { ($0["height"] as? Int ?? 0) > ($1["height"] as? Int ?? 0) }
        return (formats.first?["url"] as? String).flatMap(URL.init(string:))
    }
}

/// Full-screen trailer playback with the system controls. Menu closes it.
struct TrailerPlayerView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        let player = AVPlayer(url: url)
        vc.player = player
        player.play()
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ vc: AVPlayerViewController, coordinator: ()) {
        vc.player?.pause()
        vc.player = nil
    }
}

/// The Home header's trailer: starts a couple of seconds after the title appears, plays muted over its art, loops,
/// and fades in only once video is really playing. Nothing shows when no playable trailer is found.
struct HeroTrailerLayer: View {
    let item: MetaItem
    @StateObject private var model = HeroTrailerModel()

    var body: some View {
        HeroTrailerSurface(player: model.player)
            .opacity(model.ready ? 1 : 0)
            .animation(.easeInOut(duration: 0.8), value: model.ready)
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .task { await model.start(for: item) }
            .onDisappear { model.stop() }
    }
}

@MainActor
final class HeroTrailerModel: ObservableObject {
    let player = AVPlayer()
    @Published var ready = false
    private var observers: [NSObjectProtocol] = []
    private var statusObserver: NSKeyValueObservation?

    func start(for item: MetaItem) async {
        player.isMuted = true
        try? await Task.sleep(nanoseconds: 2_500_000_000)
        if Task.isCancelled { return }
        guard let url = await Trailers.playableURL(for: item), !Task.isCancelled else { return }
        let playerItem = AVPlayerItem(url: url)
        statusObserver = playerItem.observe(\.status) { [weak self] it, _ in
            Task { @MainActor in
                if it.status == .readyToPlay { self?.ready = true }
                else if it.status == .failed { self?.ready = false }
            }
        }
        observers.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: playerItem,
                                                                 queue: .main) { [weak self] _ in
            Task { @MainActor in
                self?.player.seek(to: .zero)
                self?.player.play()
            }
        })
        player.replaceCurrentItem(with: playerItem)
        player.play()
    }

    func stop() {
        ready = false
        player.pause()
        player.replaceCurrentItem(with: nil)
        statusObserver = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
    }
}

struct HeroTrailerSurface: UIViewRepresentable {
    let player: AVPlayer

    final class PlayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> PlayerView {
        let v = PlayerView()
        v.playerLayer.player = player
        v.playerLayer.videoGravity = .resizeAspectFill
        return v
    }

    func updateUIView(_ uiView: PlayerView, context: Context) {}
}
