import Foundation
import UIKit

private extension String {
    func has(_ pattern: String) -> Bool {
        range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
    }
}

/// Ranks streams for hands-off playback. This mirrors the Android "Smart" ranking, in order of importance:
/// 1. availability: links that play at once come before anything that still has to download;
/// 2. cam / telesync / screener copies go to the bottom;
/// 3. resolution fit, across every add-on: the screen's own resolution, then higher ones, then lower ones;
/// 4. sources the user pinned come next;
/// 5. series only: full-season packs first, so every episode of a season comes from one release;
/// 6. source quality (remux > bluray > web-dl > webrip > hdtv);
/// 7. the original order, which is the user's add-on order.
enum SmartRanker {
    static let defaultHeight = 1080

    /// Height of the best picture this screen can show.
    static var deviceHeight: Int {
        normalizeHeight(Int(UIScreen.main.nativeBounds.height))
    }

    struct Score {
        var availabilityTier: Int
        var isJunk: Bool
        var isSeasonPack: Bool
        var resolutionFit: Int
        var sourceQuality: Int
    }

    static func rank(_ streams: [StreamItem], isSeries: Bool, pinned: Set<String> = [], cached: Set<String> = [],
                     height: Int? = nil) -> [StreamItem] {
        if streams.count < 2 { return streams }
        let target = normalizeHeight(height ?? deviceHeight)
        let scored = streams.enumerated().map { (index: $0.offset, score: score($0.element, isSeries: isSeries, target: target, cached: cached)) }
        let ordered = scored.sorted { a, b in
            if a.score.availabilityTier != b.score.availabilityTier { return a.score.availabilityTier < b.score.availabilityTier }
            if a.score.isJunk != b.score.isJunk { return !a.score.isJunk }
            // A lower resolution never wins while a better one that plays exists in any add-on.
            if a.score.resolutionFit != b.score.resolutionFit { return a.score.resolutionFit > b.score.resolutionFit }
            let aPinned = pinned.contains(streams[a.index].addonName ?? "")
            let bPinned = pinned.contains(streams[b.index].addonName ?? "")
            if aPinned != bPinned { return aPinned }
            if a.score.isSeasonPack != b.score.isSeasonPack { return a.score.isSeasonPack }
            if a.score.sourceQuality != b.score.sourceQuality { return a.score.sourceQuality > b.score.sourceQuality }
            return a.index < b.index
        }
        return ordered.map { streams[$0.index] }
    }

    static func score(_ stream: StreamItem, isSeries: Bool, target: Int, cached: Set<String> = []) -> Score {
        let text = stream.combinedText
        return Score(
            availabilityTier: availabilityTier(stream, text: text, cached: cached),
            isJunk: isJunk(text),
            isSeasonPack: isSeries && isSeasonPack(text),
            resolutionFit: resolutionFit(parseHeight(text), target: target),
            sourceQuality: sourceQuality(text)
        )
    }

    /// True when the add-on says this link is not cached yet. Playing it starts a download (or a
    /// "downloading" placeholder video) instead of the title, so failover never moves to one.
    static func isMarkedUncached(_ stream: StreamItem) -> Bool {
        stream.combinedText.has(#"\[[a-z]{2,3}\s*(download|⬇️?)\]|⏳|⬇️|\buncached\b|\bnot\s+cached\b|\bdownload\s+to\b"#)
            || PlaceholderVideo.isPlaceholderURL(stream.url)
    }

    /// 0 = plays at once, 1 = probably fine, 2 = has to download first.
    static func availabilityTier(_ stream: StreamItem, text: String, cached: Set<String> = []) -> Int {
        // A torrent the user's debrid service already has plays at once.
        if stream.isTorrentOnly, let h = stream.infoHash?.lowercased(), cached.contains(h) { return 0 }
        if text.has(#"\[[a-z]{2,3}\s*(download|⬇️?)\]|⏳|⬇️|\buncached\b|\bnot\s+cached\b|\bdownload\s+to\b"#) { return 2 }
        if PlaceholderVideo.isPlaceholderURL(stream.url) { return 2 }
        if stream.playableURL != nil { return 0 }
        if stream.infoHash != nil { return 2 }
        return 1
    }

    static func isJunk(_ text: String) -> Bool {
        if text.has(#"\b(cam|camrip|hdcam|hd-cam|telesync|hdts|hd-ts|telecine|hdtc|screener|dvdscr|scr)\b"#) { return true }
        // "TS" on its own, but not as the very last word of a line (that is usually a container name).
        return text.has(#"(?m)\bTS\b(?!\s*$)"#)
    }

    static func isSeasonPack(_ text: String) -> Bool {
        if text.has(#"📦|\b(complete|season\s?pack|full\s?season|s\d{1,2}\s?-\s?s?\d{1,2}|seasons?\s?\d{1,2}\s?(-|to)\s?\d{1,2})\b"#) { return true }
        // Torrentio prints the torrent name on one line and the episode file on the next. A line that
        // names a season without an episode is the pack's own name.
        for line in text.components(separatedBy: .newlines) {
            if line.has(#"\bS\d{1,2}(?![\dEex])\b|\bseason[ ._-]?\d{1,2}\b"#),
               !line.has(#"S\d{1,2}[ ._-]?E\d{1,3}|\b\d{1,2}x\d{2,3}\b|\bE\d{2,3}\b|\bepisode[ ._-]?\d{1,3}\b"#) {
                return true
            }
        }
        return false
    }

    static func parseHeight(_ text: String) -> Int? {
        if text.has(#"\b(2160p?|4k|uhd)\b"#) { return 2160 }
        if text.has(#"\b(1440p|2k|qhd)\b"#) { return 1440 }
        if text.has(#"\b(1080[pi]?|fhd|full\s?hd)\b"#) { return 1080 }
        if text.has(#"\b720p?\b"#) { return 720 }
        if text.has(#"\b576p?\b"#) { return 576 }
        if text.has(#"\b(480p?|sd)\b"#) { return 480 }
        if text.has(#"\b360p\b"#) { return 360 }
        return nil
    }

    /// Snaps a raw display height to the nearest standard video height at or below it.
    static func normalizeHeight(_ height: Int) -> Int {
        switch height {
        case 2160...: return 2160
        case 1440...: return 1440
        case 1080...: return 1080
        case 720...: return 720
        case 1...: return 480
        default: return defaultHeight
        }
    }

    /// Higher is better. The screen's own resolution wins, then anything above it, then below.
    static func resolutionFit(_ height: Int?, target: Int) -> Int {
        guard let height else { return 600 }
        if height == target { return 10_000 }
        if height > target { return 9_000 - (height - target) / 10 }
        return height
    }

    static func sourceQuality(_ text: String) -> Int {
        if text.has(#"\bremux\b"#) { return 5 }
        if text.has(#"\b(blu-?ray|bdrip|brrip|bd)\b"#) { return 4 }
        if text.has(#"\bweb[-. ]?dl\b|\bweb\b"#) { return 3 }
        if text.has(#"\bweb[-. ]?rip\b"#) { return 2 }
        if text.has(#"\b(hdtv|pdtv|dvdrip|hdrip)\b"#) { return 1 }
        return 2
    }
}

/// Spots the stand-in clips debrid add-ons serve instead of the title when a torrent is not cached yet
/// ("Torrent is being downloaded to debrid...", "Downloading", "Failed"). Such a link plays without an
/// error, so it is caught by its address, or by being far shorter than the runtime from metadata.
/// Same rules as PlaceholderVideoDetector on Android.
enum PlaceholderVideo {
    /// No stand-in clip is longer than this; a real title of known length never is this short.
    static let maxPlaceholderSeconds: Double = 300
    /// Without a runtime from metadata, only a clip this short counts as a stand-in.
    static let unknownRuntimeMaxSeconds: Double = 90
    /// Titles shorter than this (shorts, minisodes) are never judged by length.
    static let minRuntimeMinutesForLengthCheck = 10

    private static let pathPattern = #"/(?:videos?|static|assets|exceptions|media|public|placeholders?)/(?:[^/?#]+/)*([^/?#]*(?:download|caching|cached|queued|failed|failure|error|limit|blocked|infringement|unavailable|not[_-]?found|no[_-]?results?|expired|unauthori[sz]ed|forbidden|invalid|magnet|torrent)[^/?#]*)\.(?:mp4|mkv|webm|m4v|mov)(?:[?#]|$)"#
    /// A real file name names a year or an episode; a stand-in clip never does.
    private static let realTitlePattern = #"(?:^|[^0-9])(?:19|20)\d{2}(?:[^0-9]|$)|s\d{1,2}[ ._-]?e\d{1,3}|\d{3,4}p"#

    static func isPlaceholderURL(_ url: String?) -> Bool {
        guard let url, url.lowercased().hasPrefix("http"),
              let regex = try? NSRegularExpression(pattern: pathPattern, options: [.caseInsensitive]),
              let match = regex.firstMatch(in: url, range: NSRange(url.startIndex..., in: url)),
              let nameRange = Range(match.range(at: 1), in: url) else { return false }
        let name = String(url[nameRange])
        return name.count <= 48 && !name.has(realTitlePattern)
    }

    static func isTrailerText(_ text: String?) -> Bool {
        guard let text, !text.isEmpty else { return false }
        return text.has(#"\b(?:trailer|teaser)s?\b"#)
    }

    /// True when a video of [seconds] is too short to be a title whose runtime is [runtimeMinutes].
    static func isPlaceholderDuration(_ seconds: Double, runtimeMinutes: Int?) -> Bool {
        guard seconds.isFinite, seconds > 0 else { return false }
        guard let expected = runtimeMinutes, expected > 0 else { return seconds <= unknownRuntimeMaxSeconds }
        if expected < minRuntimeMinutesForLengthCheck { return false }
        // A fifth of the runtime, capped: a 22-minute episode must run at least 4.4 minutes.
        return seconds <= min(maxPlaceholderSeconds, Double(expected) * 60 / 5)
    }

    static func isPlaceholderPlayback(url: String?, seconds: Double, runtimeMinutes: Int?, streamText: String?) -> Bool {
        if isPlaceholderURL(url) { return true }
        if isTrailerText(streamText) { return false }
        return isPlaceholderDuration(seconds, runtimeMinutes: runtimeMinutes)
    }

    /// Minutes in a metadata runtime such as "2h 10min", "45 min" or "1:52"; nil when unknown.
    static func runtimeMinutes(_ raw: String?) -> Int? {
        guard let text = raw?.trimmingCharacters(in: .whitespaces).lowercased(), !text.isEmpty else { return nil }
        func number(_ pattern: String) -> Int? {
            guard let regex = try? NSRegularExpression(pattern: pattern),
                  let m = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
                  let r = Range(m.range(at: 1), in: text) else { return nil }
            return Int(text[r])
        }
        if let colon = try? NSRegularExpression(pattern: #"^(\d{1,2}):(\d{2})$"#),
           let m = colon.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
           let h = Range(m.range(at: 1), in: text), let mm = Range(m.range(at: 2), in: text),
           let hours = Int(text[h]), let minutes = Int(text[mm]) {
            return hours * 60 + minutes
        }
        let hours = number(#"(\d+)\s*h"#)
        let minutes = number(#"(\d+)\s*m"#)
        if hours != nil || minutes != nil { return (hours ?? 0) * 60 + (minutes ?? 0) }
        return number(#"(\d+)"#).flatMap { (1...1000).contains($0) ? $0 : nil }
    }
}
