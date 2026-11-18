import Foundation

/// Automatic subtitle sync for Apple TV, the same optional feature as on the other apps.
///
/// When the viewer turns on an add-on subtitle, the video's own subtitle timing is read from the
/// Matroska Cues index (a few small range requests, no extra playback) and the add-on subtitle's
/// line starts are matched against it. A single delay, plus a frame-rate speed fix when needed, is
/// applied only when the match is confident. Subtitles are never turned on here.
enum SubtitleAutoSync {
    struct Result: Sendable {
        /// Seconds added to every subtitle time (mpv sub-delay).
        let delaySeconds: Double
        /// Subtitle time multiplier for frame-rate mismatches (mpv sub-speed).
        let speed: Double
    }

    static func sync(source: URL, headers: [String: String], subtitle: URL, language: String?) async -> Result? {
        guard let scheme = source.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        async let indexTask = MatroskaSubtitleIndex.load(url: source, headers: headers)
        async let targetTask = SubtitleCueStarts.load(url: subtitle)
        guard let tracks = await indexTask, let target = await targetTask, target.count >= 20 else { return nil }
        let usable = tracks.filter { $0.starts.count >= 20 }
        guard !usable.isEmpty else { return nil }
        // Same language first, full dialogue before forced-only tracks, text before picture subtitles.
        let ordered = usable.sorted { a, b in
            let aLang = Languages.matches(a.language, language ?? "") ? 0 : 1
            let bLang = Languages.matches(b.language, language ?? "") ? 0 : 1
            if aLang != bLang { return aLang < bLang }
            if a.forced != b.forced { return !a.forced }
            if a.picture != b.picture { return !a.picture }
            return a.starts.count > b.starts.count
        }
        for track in ordered.prefix(3) {
            if Task.isCancelled { return nil }
            if let result = SubtitleAligner.align(target: target, reference: track.starts) { return result }
        }
        return nil
    }
}

/// Finds the offset (and frame-rate speed) that lines up subtitle starts with reference starts.
enum SubtitleAligner {
    private static let binMs = 50.0
    private static let maxShiftMs = 60_000.0
    private static let matchToleranceMs = 150.0
    private static let speeds: [Double] = [1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0]

    private struct Candidate {
        let speed: Double
        let offsetMs: Double
        let matched: Int
        let peakVotes: Int
        let secondVotes: Int
    }

    static func align(target rawTarget: [Double], reference rawReference: [Double]) -> SubtitleAutoSync.Result? {
        let target = rawTarget.sorted()
        let reference = rawReference.sorted()
        guard target.count >= 20, reference.count >= 20 else { return nil }
        var best: Candidate?
        var normal: Candidate?
        for speed in speeds {
            guard let c = candidate(target: target, reference: reference, speed: speed) else { continue }
            if speed == 1.0 { normal = c }
            if best == nil || c.matched > best!.matched { best = c }
        }
        guard let chosen = best else { return nil }
        // A speed fix must clearly beat a plain delay, or it is noise.
        if chosen.speed != 1.0, let normal, Double(chosen.matched) < Double(normal.matched) * 1.25 { return confident(normal, target, reference) }
        return confident(chosen, target, reference)
    }

    private static func confident(_ c: Candidate, _ target: [Double], _ reference: [Double]) -> SubtitleAutoSync.Result? {
        // Lines whose start falls where the reference has any lines at all.
        guard let first = reference.first, let last = reference.last else { return nil }
        let covered = target.filter { t in
            let mapped = t * c.speed + c.offsetMs
            return mapped >= first - 5_000 && mapped <= last + 5_000
        }.count
        let needed = max(20, Int(Double(min(covered, reference.count)) * 0.45))
        guard c.matched >= needed else { return nil }
        guard Double(c.peakVotes) >= Double(max(c.secondVotes, 1)) * 2.0 else { return nil }
        var offset = c.offsetMs
        if c.speed == 1.0, abs(offset) < 100 { offset = 0 }
        return SubtitleAutoSync.Result(delaySeconds: offset / 1000, speed: c.speed)
    }

    private static func candidate(target: [Double], reference: [Double], speed: Double) -> Candidate? {
        let bins = Int(maxShiftMs / binMs) * 2 + 1
        let center = Int(maxShiftMs / binMs)
        var votes = [Int](repeating: 0, count: bins)
        var low = 0
        for t in target {
            let mapped = t * speed
            while low < reference.count, reference[low] < mapped - maxShiftMs { low += 1 }
            var j = low
            while j < reference.count, reference[j] <= mapped + maxShiftMs {
                let bin = Int(((reference[j] - mapped) / binMs).rounded()) + center
                if bin >= 0, bin < bins { votes[bin] += 1 }
                j += 1
            }
        }
        // Votes within 100 ms of each shift.
        var window = [Int](repeating: 0, count: bins)
        for i in 0..<bins {
            var sum = 0
            for k in max(0, i - 2)...min(bins - 1, i + 2) { sum += votes[k] }
            window[i] = sum
        }
        guard let peak = window.indices.max(by: { window[$0] < window[$1] }), window[peak] > 0 else { return nil }
        var second = 0
        for i in 0..<bins where abs(i - peak) > 20 { second = max(second, window[i]) }
        let coarse = Double(peak - center) * binMs

        // Refine with the median residual of lines that match the coarse offset.
        var residuals: [Double] = []
        for t in target {
            let mapped = t * speed + coarse
            if let r = nearest(reference, mapped), abs(r - mapped) <= 250 { residuals.append(r - mapped) }
        }
        guard !residuals.isEmpty else { return nil }
        residuals.sort()
        let offset = coarse + residuals[residuals.count / 2]
        var matched = 0
        for t in target {
            let mapped = t * speed + offset
            if let r = nearest(reference, mapped), abs(r - mapped) <= matchToleranceMs { matched += 1 }
        }
        return Candidate(speed: speed, offsetMs: offset, matched: matched, peakVotes: window[peak], secondVotes: second)
    }

    private static func nearest(_ sorted: [Double], _ value: Double) -> Double? {
        guard !sorted.isEmpty else { return nil }
        var lo = 0, hi = sorted.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if sorted[mid] < value { lo = mid + 1 } else { hi = mid }
        }
        if lo == 0 { return sorted[0] }
        if lo == sorted.count { return sorted[sorted.count - 1] }
        return abs(sorted[lo] - value) < abs(sorted[lo - 1] - value) ? sorted[lo] : sorted[lo - 1]
    }
}

/// Line start times (ms) of an add-on subtitle: SRT, WebVTT or ASS/SSA.
enum SubtitleCueStarts {
    private static let maxBytes = 4 * 1024 * 1024

    static func load(url: URL) async -> [Double]? {
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? true,
              data.count <= maxBytes else { return nil }
        let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) ?? ""
        return parse(text)
    }

    static func parse(_ text: String) -> [Double] {
        var starts: [Double] = []
        for rawLine in text.split(whereSeparator: { $0 == "\n" || $0 == "\r" }) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("Dialogue:") {
                // Dialogue: Layer, Start, End, ...
                let fields = line.dropFirst("Dialogue:".count).split(separator: ",", maxSplits: 3)
                if fields.count >= 2, let ms = timestamp(String(fields[1]).trimmingCharacters(in: .whitespaces)) { starts.append(ms) }
            } else if let arrow = line.range(of: "-->") {
                let left = line[..<arrow.lowerBound].trimmingCharacters(in: .whitespaces)
                if let ms = timestamp(left) { starts.append(ms) }
            }
        }
        return starts
    }

    /// "01:02:03,456", "01:02:03.456", "02:03.456" or ASS "1:02:03.45" to milliseconds.
    private static func timestamp(_ s: String) -> Double? {
        let parts = s.replacingOccurrences(of: ",", with: ".").split(separator: ":")
        guard parts.count == 2 || parts.count == 3 else { return nil }
        var total = 0.0
        for (i, part) in parts.enumerated() {
            guard let v = Double(part) else { return nil }
            if i < parts.count - 1 { total = (total + v) * 60 } else { total += v }
        }
        return total * 1000
    }
}

/// Reads subtitle timing from a Matroska/WebM file's Cues index with HTTP range requests.
enum MatroskaSubtitleIndex {
    struct Track: Sendable {
        let number: UInt64
        let language: String?
        let forced: Bool
        let picture: Bool
        var starts: [Double]
    }

    private static let probeBytes = 512 * 1024
    private static let maxElementBytes = 8 * 1024 * 1024

    private static let idEBML: UInt64 = 0x1A45DFA3
    private static let idSegment: UInt64 = 0x18538067
    private static let idSeekHead: UInt64 = 0x114D9B74
    private static let idSeek: UInt64 = 0x4DBB
    private static let idSeekID: UInt64 = 0x53AB
    private static let idSeekPosition: UInt64 = 0x53AC
    private static let idInfo: UInt64 = 0x1549A966
    private static let idTimestampScale: UInt64 = 0x2AD7B1
    private static let idTracks: UInt64 = 0x1654AE6B
    private static let idTrackEntry: UInt64 = 0xAE
    private static let idTrackNumber: UInt64 = 0xD7
    private static let idTrackType: UInt64 = 0x83
    private static let idCodecID: UInt64 = 0x86
    private static let idLanguage: UInt64 = 0x22B59C
    private static let idLanguageBCP47: UInt64 = 0x22B59D
    private static let idFlagForced: UInt64 = 0x55AA
    private static let idCues: UInt64 = 0x1C53BB6B
    private static let idCuePoint: UInt64 = 0xBB
    private static let idCueTime: UInt64 = 0xB3
    private static let idCueTrackPositions: UInt64 = 0xB7
    private static let idCueTrack: UInt64 = 0xF7
    private static let idCluster: UInt64 = 0x1F43B675

    private struct Element {
        let id: UInt64
        /// Offset of the element's data, relative to the buffer it was read from.
        let dataStart: Int
        let size: Int?
        var end: Int? { size.map { dataStart + $0 } }
    }

    static func load(url: URL, headers: [String: String]) async -> [Track]? {
        guard let probe = await fetch(url, headers, offset: 0, length: probeBytes) else { return nil }
        guard let ebml = readElement(probe, 0), ebml.id == idEBML, let ebmlEnd = ebml.end,
              let segment = readElement(probe, ebmlEnd), segment.id == idSegment else { return nil }
        let segmentStart = segment.dataStart

        // Top-level elements found in the probe, and the SeekHead's map of the rest.
        var found: [UInt64: Data] = [:]
        var positions: [UInt64: Int] = [:]
        var pos = segmentStart
        while pos < probe.count, let el = readElement(probe, pos) {
            if el.id == idCluster { break }
            guard let end = el.end else { break }
            if [idSeekHead, idInfo, idTracks, idCues].contains(el.id) {
                positions[el.id] = pos
                if end <= probe.count { found[el.id] = probe.subdata(in: el.dataStart..<end) }
            }
            if el.id == idSeekHead, let body = found[idSeekHead] {
                for seek in children(body) where seek.id == idSeek {
                    guard let seekEnd = seek.end else { continue }
                    let seekBody = body.subdata(in: seek.dataStart..<seekEnd)
                    var seekID: UInt64?
                    var seekPos: UInt64?
                    for c in children(seekBody) {
                        if c.id == idSeekID { seekID = uint(seekBody, c) }
                        if c.id == idSeekPosition { seekPos = uint(seekBody, c) }
                    }
                    if let seekID, let seekPos, positions[seekID] == nil { positions[seekID] = segmentStart + Int(seekPos) }
                }
            }
            pos = end
        }

        if found[idInfo] == nil, let at = positions[idInfo] { found[idInfo] = await fetchElement(url, headers, at: at) }
        if found[idTracks] == nil, let at = positions[idTracks] { found[idTracks] = await fetchElement(url, headers, at: at) }
        guard let tracksBody = found[idTracks] else { return nil }
        if found[idCues] == nil, let at = positions[idCues] { found[idCues] = await fetchElement(url, headers, at: at) }
        guard let cuesBody = found[idCues] else { return nil }

        var scaleNs = 1_000_000.0
        if let info = found[idInfo] {
            for c in children(info) where c.id == idTimestampScale {
                if let v = uint(info, c), v > 0 { scaleNs = Double(v) }
            }
        }

        var tracks: [UInt64: Track] = [:]
        for entry in children(tracksBody) where entry.id == idTrackEntry {
            guard let end = entry.end else { continue }
            let body = tracksBody.subdata(in: entry.dataStart..<end)
            var number: UInt64?
            var type: UInt64?
            var codec = ""
            var language: String?
            var forced = false
            for c in children(body) {
                switch c.id {
                case idTrackNumber: number = uint(body, c)
                case idTrackType: type = uint(body, c)
                case idCodecID: codec = string(body, c) ?? ""
                case idLanguage: if language == nil { language = string(body, c) }
                case idLanguageBCP47: language = string(body, c)
                case idFlagForced: forced = (uint(body, c) ?? 0) != 0
                default: break
                }
            }
            guard let number, type == 0x11 else { continue }
            let picture = codec.hasPrefix("S_HDMV") || codec.hasPrefix("S_VOBSUB") || codec.hasPrefix("S_DVBSUB")
            tracks[number] = Track(number: number, language: language, forced: forced, picture: picture, starts: [])
        }
        guard !tracks.isEmpty else { return nil }

        for point in children(cuesBody) where point.id == idCuePoint {
            guard let end = point.end else { continue }
            let body = cuesBody.subdata(in: point.dataStart..<end)
            var time: UInt64?
            var trackNumbers: [UInt64] = []
            for c in children(body) {
                if c.id == idCueTime { time = uint(body, c) }
                if c.id == idCueTrackPositions, let cEnd = c.end {
                    let positionsBody = body.subdata(in: c.dataStart..<cEnd)
                    for p in children(positionsBody) where p.id == idCueTrack {
                        if let n = uint(positionsBody, p) { trackNumbers.append(n) }
                    }
                }
            }
            guard let time else { continue }
            let ms = Double(time) * scaleNs / 1_000_000
            for n in trackNumbers where tracks[n] != nil { tracks[n]?.starts.append(ms) }
        }
        let result = tracks.values.filter { !$0.starts.isEmpty }
        return result.isEmpty ? nil : Array(result)
    }

    private static func fetchElement(_ url: URL, _ headers: [String: String], at offset: Int) async -> Data? {
        guard let head = await fetch(url, headers, offset: offset, length: 16),
              let el = readElement(head, 0), let size = el.size, size <= maxElementBytes else { return nil }
        guard let data = await fetch(url, headers, offset: offset + el.dataStart, length: size), data.count == size else { return nil }
        return data
    }

    /// Bytes [offset, offset + length) of the file, or nil when the server cannot serve a range.
    private static func fetch(_ url: URL, _ headers: [String: String], offset: Int, length: Int) async -> Data? {
        var request = URLRequest(url: url)
        request.timeoutInterval = 10
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        request.setValue("bytes=\(offset)-\(offset + length - 1)", forHTTPHeaderField: "Range")
        guard let (bytes, response) = try? await URLSession.shared.bytes(for: request),
              let http = response as? HTTPURLResponse else { return nil }
        // A full-file answer is only usable for the very start of the file.
        guard http.statusCode == 206 || (http.statusCode == 200 && offset == 0) else { return nil }
        var data = Data()
        data.reserveCapacity(length)
        do {
            for try await byte in bytes {
                data.append(byte)
                if data.count >= length { break }
            }
        } catch {
            return data.isEmpty ? nil : data
        }
        return data
    }

    private static func children(_ data: Data) -> [Element] {
        var out: [Element] = []
        var pos = 0
        while pos < data.count, let el = readElement(data, pos), let end = el.end, end <= data.count {
            out.append(el)
            pos = end
        }
        return out
    }

    private static func readElement(_ data: Data, _ offset: Int) -> Element? {
        guard let (id, idLength) = vint(data, offset, keepMarker: true),
              let (size, sizeLength) = vint(data, offset + idLength, keepMarker: false) else { return nil }
        let unknown = size == (UInt64(1) << UInt64(7 * sizeLength)) - 1
        let dataStart = offset + idLength + sizeLength
        return Element(id: id, dataStart: dataStart, size: unknown || size > UInt64(Int32.max) ? nil : Int(size))
    }

    private static func vint(_ data: Data, _ offset: Int, keepMarker: Bool) -> (UInt64, Int)? {
        guard offset >= 0, offset < data.count else { return nil }
        let base = data.startIndex
        let first = data[base + offset]
        guard first != 0 else { return nil }
        let length = first.leadingZeroBitCount + 1
        guard length <= 8, offset + length <= data.count else { return nil }
        var value = UInt64(keepMarker ? first : first & (0xFF >> UInt8(length)))
        if length > 1 {
            for i in 1..<length { value = (value << 8) | UInt64(data[base + offset + i]) }
        }
        return (value, length)
    }

    private static func uint(_ data: Data, _ el: Element) -> UInt64? {
        guard let size = el.size, size >= 1, size <= 8, el.dataStart + size <= data.count else { return nil }
        let base = data.startIndex
        var value: UInt64 = 0
        for i in 0..<size { value = (value << 8) | UInt64(data[base + el.dataStart + i]) }
        return value
    }

    private static func string(_ data: Data, _ el: Element) -> String? {
        guard let size = el.size, el.dataStart + size <= data.count else { return nil }
        let base = data.startIndex
        let bytes = data[(base + el.dataStart)..<(base + el.dataStart + size)].prefix { $0 != 0 }
        return String(data: Data(bytes), encoding: .utf8)
    }
}
