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
/// 3. sources the user pinned come next;
/// 4. series only: full-season packs first, so every episode of a season comes from one release;
/// 5. resolution fit: the screen's own resolution, then higher ones, then lower ones;
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

    static func rank(_ streams: [StreamItem], isSeries: Bool, pinned: Set<String> = [], height: Int? = nil) -> [StreamItem] {
        if streams.count < 2 { return streams }
        let target = normalizeHeight(height ?? deviceHeight)
        let scored = streams.enumerated().map { (index: $0.offset, score: score($0.element, isSeries: isSeries, target: target)) }
        let ordered = scored.sorted { a, b in
            if a.score.availabilityTier != b.score.availabilityTier { return a.score.availabilityTier < b.score.availabilityTier }
            if a.score.isJunk != b.score.isJunk { return !a.score.isJunk }
            let aPinned = pinned.contains(streams[a.index].addonName ?? "")
            let bPinned = pinned.contains(streams[b.index].addonName ?? "")
            if aPinned != bPinned { return aPinned }
            if a.score.isSeasonPack != b.score.isSeasonPack { return a.score.isSeasonPack }
            if a.score.resolutionFit != b.score.resolutionFit { return a.score.resolutionFit > b.score.resolutionFit }
            if a.score.sourceQuality != b.score.sourceQuality { return a.score.sourceQuality > b.score.sourceQuality }
            return a.index < b.index
        }
        return ordered.map { streams[$0.index] }
    }

    static func score(_ stream: StreamItem, isSeries: Bool, target: Int) -> Score {
        let text = stream.combinedText
        return Score(
            availabilityTier: availabilityTier(stream, text: text),
            isJunk: isJunk(text),
            isSeasonPack: isSeries && isSeasonPack(text),
            resolutionFit: resolutionFit(parseHeight(text), target: target),
            sourceQuality: sourceQuality(text)
        )
    }

    /// 0 = plays at once, 1 = probably fine, 2 = has to download first.
    static func availabilityTier(_ stream: StreamItem, text: String) -> Int {
        if text.has(#"\[[a-z]{2,3}\s*(download|⬇️?)\]|⏳|⬇️|\buncached\b|\bnot\s+cached\b|\bdownload\s+to\b"#) { return 2 }
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
