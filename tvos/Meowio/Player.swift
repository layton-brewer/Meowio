import SwiftUI
import AVKit
import Combine

/// Sleep timer. It lives outside any one playback so it keeps counting across episodes and source switches.
@MainActor
final class SleepTimer: ObservableObject {
    static let shared = SleepTimer()

    enum Mode: Equatable {
        case off
        case until(minutes: Int, deadline: Date)
        case endOfEpisode
    }

    static let minuteOptions = [15, 30, 45, 60, 90]
    @Published private(set) var mode: Mode = .off

    func start(minutes: Int) {
        mode = minutes <= 0 ? .off : .until(minutes: minutes, deadline: Date().addingTimeInterval(Double(minutes) * 60))
    }
    func endOfEpisode() { mode = .endOfEpisode }
    func cancel() { mode = .off }

    var label: String {
        switch mode {
        case .off: return "Sleep timer"
        case .until(let minutes, _): return "Sleep in \(minutes) min"
        case .endOfEpisode: return "Sleep after this episode"
        }
    }

    /// True once a timed sleep has run out. Clears the timer.
    func consumeExpired() -> Bool {
        if case .until(_, let deadline) = mode, deadline <= Date() { mode = .off; return true }
        return false
    }

    /// Called when an episode ends. True when playback should stop here instead of starting the next one.
    func consumeEndOfEpisode() -> Bool {
        switch mode {
        case .endOfEpisode: mode = .off; return true
        case .until(_, let deadline) where deadline <= Date(): mode = .off; return true
        default: return false
        }
    }
}

struct PlayRequest: Identifiable {
    let id = UUID()
    var meta: MetaItem
    var videoId: String?
    var title: String
    var candidates: [StreamItem]
    var headers: [String: String] = [:]
    var isLive = false
    /// False for files played from the debrid cloud: they are not titles, so nothing is saved or scrobbled.
    var tracked = true
    /// Live TV VPN proxy for this Live TV request, or "" for none. Never set for anything but Live TV.
    var liveProxy = ""
    /// Live TV only: told why the channel failed, or nil once it plays.
    var onLiveResult: (@MainActor (LivePlaybackProblem?) -> Void)? = nil
    /// Live TV only: the address and headers to probe for a reason when every copy fails.
    var liveProbe: (String, [String: String])? = nil
    /// Where to start, in seconds, for a title the user left part way.
    var startAt: Double = 0
    /// Resolves the request for the episode after this one, when there is one.
    var nextEpisode: (@MainActor () async -> PlayRequest?)? = nil
}

/// Plays the best candidate and silently moves to the next one when it fails or does not start.
@MainActor
final class PlaybackController: ObservableObject {
    let player = AVPlayer()
    let mpv = MPVEngine()
    @Published var usingMPV = false
    private var triedMPV = Set<Int>()
    /// True from the moment a source shows its first picture until another source loads.
    private var sourcePlaying = false
    /// Seconds the playing source has been stuck at [stallPosition] while it should be playing.
    private var stallSeconds = 0
    private var stallPosition: Double = -1
    /// Playing mid-playback stops this long without moving before the next source takes over.
    private static let stallFailoverSeconds = 30
    @Published var failedAll = false {
        didSet { if failedAll, !oldValue { diagnoseLiveFailure() } }
    }
    @Published var liveProblem: LivePlaybackProblem?
    @Published var request: PlayRequest
    @Published var activeIndex = 0
    @Published var sleptMessage = false
    /// Subtitles from the user's subtitle add-ons for what is playing.
    @Published private(set) var addonSubtitles: [SubtitleItem] = []
    /// Shown near the end of an episode when another one follows ("Next Episode" button).
    @Published private(set) var upNextVisible = false
    /// Where Skip Intro jumps to, while an intro or recap chapter is playing.
    @Published private(set) var skipTarget: Double?
    /// Button text for the current skip: Skip Intro, Skip Recap or Skip Credits.
    @Published private(set) var skipLabel = "Skip Intro"
    /// Skip times from IntroDB and AniSkip for what is playing.
    private var skipSegments: [SkipSegment] = []
    @Published private(set) var speed: Double = 1
    /// Short status while a torrent result is turned into a link.
    @Published private(set) var status: String?

    private let settings: SettingsStore
    private var index = 0
    private var switches = 0
    private var cancellables = Set<AnyCancellable>()
    private var timeout: Task<Void, Never>?
    private var timeObserver: Any?
    private var ticker: Timer?
    private var resolved: [Int: URL] = [:]
    private var pendingSubtitle: SubtitleItem?
    /// The file mpv is playing, for Automatic subtitle sync.
    private var mpvSource: (url: URL, headers: [String: String])?
    /// The add-on subtitle the current timing correction was worked out for.
    private var syncedSubtitleURL: String?
    private var subtitleSyncTask: Task<Void, Never>?
    private var upNextDismissed = false
    private var advancing = false
    private var wasPaused = true
    private var started = false
    /// Set once the player screen closes, so a link that arrives late never starts playing in the background.
    private var stopped = false
    private(set) var lastSeconds: Double = 0
    private(set) var lastDuration: Double = 0
    var onProgress: ((PlayRequest, Double, Double, Bool) -> Void)?
    /// Position to start the source that is loading at: the resume point, or where the failed source stopped.
    private var pendingStart: Double = 0
    var onPlayed: ((PlayRequest, StreamItem) -> Void)?
    /// Playback started, paused or stopped, with percent watched. Trakt scrobbling listens.
    var onScrobble: ((PlayRequest, String, Double) -> Void)?
    var onClose: (() -> Void)?

    init(request: PlayRequest, settings: SettingsStore) {
        self.request = request
        self.settings = settings
        player.allowsExternalPlayback = true
    }

    private let serverReport = ServerPlayReporter()

    /// Tells Trakt, Simkl and MDBList (through onScrobble) and the Jellyfin or Emby server that served the file.
    private func emitProgress() {
        serverReport.report("progress", stream: request.candidates.indices.contains(index) ? request.candidates[index] : nil,
                            seconds: lastSeconds, paused: false)
    }

    private func emitScrobble(_ action: String, _ percent: Double) {
        guard request.tracked else { return }
        onScrobble?(request, action, percent)
        if request.candidates.indices.contains(index) {
            serverReport.report(action, stream: request.candidates[index], seconds: lastSeconds, paused: action == "pause")
        } else {
            serverReport.report(action, stream: nil, seconds: lastSeconds, paused: action == "pause")
        }
    }

    var percent: Double { lastDuration > 0 ? min(100, max(0, lastSeconds / lastDuration * 100)) : 0 }

    func start() {
        let interval = CMTime(seconds: 1, preferredTimescale: 1)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self, !self.usingMPV else { return }
                let seconds = time.seconds
                if seconds.isFinite { self.lastSeconds = seconds }
                if let d = self.player.currentItem?.duration.seconds, d.isFinite { self.lastDuration = d }
            }
        }
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        mpv.prepare()
        loadSubtitles()
        loadSkipSegments()
        // A link that failed a moment ago is skipped while a fresh one is there.
        let first = request.isLive ? 0 : (request.candidates.firstIndex { !DeadLinks.isDead($0) } ?? 0)
        load(at: first, keepPosition: false)
    }

    private func loadSkipSegments() {
        skipSegments = []
        guard !request.isLive, settings.skipIntro else { return }
        let meta = request.meta, videoId = request.videoId
        let id = request.id
        Task { [weak self] in
            let found = await SkipSegments.load(meta: meta, videoId: videoId)
            guard let self, self.request.id == id else { return }
            self.skipSegments = found
        }
    }

    func stop() {
        stopped = true
        finishRecording(completed: false)
        if started { emitScrobble("stop", percent) }
        started = false
        timeout?.cancel()
        ticker?.invalidate()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        mpv.stop()
        subtitleSyncTask?.cancel()
        cancellables.removeAll()
    }

    /// The user picked a specific source from the quality picker.
    func choose(index i: Int) {
        guard request.candidates.indices.contains(i) else { return }
        load(at: i)
    }

    func setSpeed(_ value: Double) {
        speed = value
        if usingMPV { mpv.setSpeed(value) } else {
            player.defaultRate = Float(value)
            if player.rate != 0 { player.rate = Float(value) }
        }
    }

    /// Plays an add-on subtitle. AVPlayer cannot load outside subtitle files, so the source reopens in mpv
    /// at the same spot.
    func useSubtitle(_ sub: SubtitleItem) {
        if usingMPV {
            mpv.addSubtitle(url: sub.url, title: sub.label, lang: sub.lang, select: true)
        } else {
            pendingSubtitle = sub
            triedMPV.insert(index)
            load(at: index)
        }
    }

    func skipIntro() {
        guard let t = skipTarget else { return }
        if usingMPV { mpv.seek(to: t) } else { player.seek(to: CMTime(seconds: t, preferredTimescale: 600)) }
        skipTarget = nil
    }

    /// "Next Episode" pressed, or the countdown ran out.
    func playNextNow() {
        upNextVisible = false
        advance(completedCurrent: true, userAsked: true)
    }

    func dismissUpNext() {
        upNextDismissed = true
        upNextVisible = false
    }

    private func tick() {
        syncFromMPV()
        if SleepTimer.shared.consumeExpired() {
            player.pause()
            if usingMPV { mpv.pause() }
            sleptMessage = true
        }
        guard !request.isLive else { return }
        checkMidPlaybackStall()
        // Pause and resume for scrobbling.
        let paused = usingMPV ? mpv.isPaused : player.timeControlStatus == .paused
        if started, paused != wasPaused { emitScrobble(paused ? "pause" : "start", percent) }
        wasPaused = paused
        if started, !paused { emitProgress() }
        // Next Episode card for the last stretch of an episode.
        let remaining = lastDuration - lastSeconds
        let showUpNext = settings.upNextCard && request.nextEpisode != nil && !upNextDismissed && lastDuration > 300
            && remaining > 0 && remaining <= min(45, lastDuration * 0.05 + 15)
        if showUpNext != upNextVisible { upNextVisible = showUpNext }
        // Skip button while an intro, recap or ending plays: from IntroDB/AniSkip times, else from mpv chapters.
        if settings.skipIntro {
            var target: Double?
            var label = "Skip Intro"
            if let seg = skipSegments.first(where: { lastSeconds >= $0.start && lastSeconds < $0.end - 2 }) {
                target = seg.end
                label = seg.label
            } else if usingMPV {
                let chapters = mpv.chapters()
                for (i, c) in chapters.enumerated() where i + 1 < chapters.count {
                    let next = chapters[i + 1].time
                    if lastSeconds >= c.time, lastSeconds < next - 2, Self.isIntro(c.title) { target = next }
                }
            }
            if target != skipTarget { skipTarget = target }
            if label != skipLabel { skipLabel = label }
        } else if skipTarget != nil {
            skipTarget = nil
        }
    }

    /// A source that stops mid-playback and does not recover (the host went away, a debrid link died)
    /// is replaced with the next source at the same position, instead of buffering forever.
    private func checkMidPlaybackStall() {
        guard sourcePlaying, !stopped, !failedAll, !advancing, lastDuration > 0 else { return }
        let position = usingMPV ? mpv.position : player.currentTime().seconds
        let wantsToPlay = usingMPV ? !mpv.isPaused : player.timeControlStatus != .paused
        guard position.isFinite, wantsToPlay, position < lastDuration - 5 else {
            stallSeconds = 0
            stallPosition = -1
            return
        }
        if stallPosition < 0 || abs(position - stallPosition) >= 0.75 {
            stallPosition = position
            stallSeconds = 0
            return
        }
        stallSeconds += 1
        guard stallSeconds >= Self.stallFailoverSeconds else { return }
        stallSeconds = 0
        sourcePlaying = false
        // Retrying the same link in mpv will not bring a dead host back: move to the next source.
        nextSource()
    }

    static func isIntro(_ title: String) -> Bool {
        let t = title.lowercased().trimmingCharacters(in: .whitespaces)
        return t == "op" || t.contains("intro") || t.contains("opening") || t.contains("recap") || t.contains("previously")
    }

    private func syncFromMPV() {
        guard usingMPV else { return }
        if mpv.position.isFinite, mpv.position > 0 { lastSeconds = mpv.position }
        if mpv.duration.isFinite, mpv.duration > 0 { lastDuration = mpv.duration }
    }

    private func finishRecording(completed: Bool) {
        syncFromMPV()
        guard !request.isLive, request.tracked, lastSeconds > 5 else { return }
        let done = completed || (lastDuration > 0 && lastSeconds / lastDuration > 0.92)
        onProgress?(request, lastSeconds, lastDuration, done)
    }

    private func loadSubtitles() {
        addonSubtitles = []
        guard !request.isLive, !settings.addons.isEmpty else { return }
        let type = request.meta.type
        let id = request.videoId ?? request.meta.id
        let addons = settings.addons
        Task { [weak self] in
            let list = await AddonClient.subtitles(type: type, id: id, addons: addons)
            guard let self, (self.request.videoId ?? self.request.meta.id) == id else { return }
            self.addonSubtitles = list
            if self.usingMPV { self.attachSubtitles() }
        }
    }

    /// Hands add-on subtitles to mpv and turns on the preferred language when no subtitle is showing yet.
    private func attachSubtitles() {
        let wanted = settings.subtitleLanguage.isEmpty ? (Locale.current.language.languageCode?.identifier ?? "en") : settings.subtitleLanguage
        let existing = Set(mpv.trackLanguages("sub").filter { $0.external }.compactMap { $0.lang })
        var added = 0
        for sub in addonSubtitles where added < 30 {
            if sub == pendingSubtitle { continue }
            // Only the preferred language plus English, to keep the track list short.
            guard Languages.matches(sub.lang, wanted) || Languages.matches(sub.lang, "en") else { continue }
            if existing.contains(sub.lang ?? "") && added > 0 { continue }
            mpv.addSubtitle(url: sub.url, title: sub.label, lang: sub.lang, select: false)
            added += 1
        }
        if let pending = pendingSubtitle {
            mpv.addSubtitle(url: pending.url, title: pending.label, lang: pending.lang, select: true)
            pendingSubtitle = nil
        } else if settings.subtitlesOn, mpv.selectedSubtitle == nil,
                  let pick = mpv.trackLanguages("sub").first(where: { Languages.matches($0.lang, wanted) }) {
            mpv.selectSubtitle(id: pick.id)
        }
    }

    /// Automatic subtitle sync (off by default): when the viewer's subtitle is an add-on file, line it
    /// up with the video's built-in subtitle timing. Only timing changes; subtitles are never turned on.
    private func subtitleChanged() {
        guard usingMPV, let source = mpvSource else { return }
        let selected = mpv.selectedExternalSubtitle()
        guard selected?.url != syncedSubtitleURL else { return }
        subtitleSyncTask?.cancel()
        if syncedSubtitleURL != nil { mpv.setSubtitleTiming(delay: 0, speed: 1) }
        syncedSubtitleURL = selected?.url
        guard settings.autoSubtitleSync, let selected, let subtitleURL = URL(string: selected.url) else { return }
        subtitleSyncTask = Task { [weak self] in
            let result = await SubtitleAutoSync.sync(source: source.url, headers: source.headers,
                                                     subtitle: subtitleURL, language: selected.lang)
            guard let self, !Task.isCancelled, let result, self.usingMPV,
                  self.mpvSource?.url == source.url, self.syncedSubtitleURL == selected.url,
                  self.mpv.selectedExternalSubtitle()?.url == selected.url else { return }
            self.mpv.setSubtitleTiming(delay: result.delaySeconds, speed: result.speed)
        }
    }

    private func load(at i: Int, keepPosition: Bool = true) {
        guard !stopped else { return }
        guard request.candidates.indices.contains(i) else {
            failedAll = true
            return
        }
        let stream = request.candidates[i]
        if keepPosition, !request.isLive {
            let current = usingMPV ? mpv.position : player.currentTime().seconds
            if current.isFinite, current > 5 { lastSeconds = current }
        }
        index = i
        activeIndex = i
        failedAll = false
        cancellables.removeAll()
        if let url = stream.playableURL ?? resolved[i] {
            open(url: url, stream: stream, at: i)
            return
        }
        guard stream.isTorrentOnly, settings.debridReady else { failover(); return }
        // Torrent result: ask the debrid service for a link, then play it.
        status = "Getting the link from \(settings.debridService.title)…"
        timeout?.cancel()
        let ref = EpisodeRef(request.videoId)
        let service = settings.debridService
        let key = settings.debridKey
        Task { [weak self] in
            let url = try? await Debrid.resolve(stream, season: ref?.season, episode: ref?.episode, service: service, key: key)
            guard let self, self.index == i, !self.stopped else { return }
            self.status = nil
            if let url {
                self.resolved[i] = url
                self.open(url: url, stream: stream, at: i)
            } else {
                self.nextSource()
            }
        }
    }

    private func open(url: URL, stream: StreamItem, at i: Int) {
        sourcePlaying = false
        stallSeconds = 0
        stallPosition = -1
        pendingStart = request.isLive ? 0 : (lastSeconds > 5 ? lastSeconds : request.startAt)
        let headers = stream.headers(merging: request.headers)
        let hint = (stream.behaviorHints?.filename ?? url.lastPathComponent).lowercased()
        // The Live TV VPN only ever applies to a Live TV request, and never to a debrid link.
        let proxy = request.isLive && LiveVpnGuard.mayRoute(url.absoluteString) ? request.liveProxy : ""
        let mpvOnly = ["mkv", "avi", "webm", "flv", "wmv", "ts", "m2ts", "mpg", "mpeg"].contains((hint as NSString).pathExtension)
            || stream.behaviorHints?.notWebReady == true || !proxy.isEmpty
        if mpvOnly || triedMPV.contains(i) {
            player.pause()
            player.replaceCurrentItem(with: nil)
            usingMPV = true
            mpv.onLoaded = { [weak self] in
                guard let self else { return }
                self.timeout?.cancel()
                self.attachSubtitles()
                if self.speed != 1 { self.mpv.setSpeed(self.speed) }
                self.mpv.play()
                self.didStart()
            }
            mpv.onFailed = { [weak self] in self?.failover() }
            mpv.onEnded = { [weak self] in self?.ended() }
            mpv.onSubtitleChanged = { [weak self] in self?.subtitleChanged() }
            mpvSource = (url, headers)
            syncedSubtitleURL = nil
            subtitleSyncTask?.cancel()
            mpv.setPreferences(audioLanguage: settings.audioLanguage, subtitleLanguage: settings.subtitleLanguage,
                               subtitlesOn: settings.subtitlesOn)
            mpv.setSubtitleStyle(settings.subtitleStyle)
            mpv.setSubtitleTiming(delay: 0, speed: 1)
            mpv.load(url: url.absoluteString, headers: headers, start: pendingStart, proxy: proxy)
            armTimeout()
            return
        }
        if usingMPV {
            mpv.stop()
            usingMPV = false
        }
        let asset: AVURLAsset
        if headers.isEmpty {
            asset = AVURLAsset(url: url)
        } else {
            asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        }
        let item = AVPlayerItem(asset: asset)
        item.textStyleRules = Self.textStyle(settings.subtitleStyle)
        item.publisher(for: \.status)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in
                guard let self else { return }
                if status == .readyToPlay {
                    self.timeout?.cancel()
                    if self.pendingStart > 0 {
                        self.player.seek(to: CMTime(seconds: self.pendingStart, preferredTimescale: 600))
                        self.pendingStart = 0
                    }
                    self.player.defaultRate = Float(self.speed)
                    self.player.play()
                    self.applyLanguages(to: item)
                    self.didStart()
                } else if status == .failed {
                    self.failover()
                }
            }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime, object: item)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.failover() }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime, object: item)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.ended() }
            .store(in: &cancellables)
        player.replaceCurrentItem(with: item)
        armTimeout()
    }

    private func diagnoseLiveFailure() {
        guard request.isLive, let probe = request.liveProbe else { return }
        Task { @MainActor [weak self] in
            let problem = await LivePlaybackProblem.diagnose(probe.0, headers: probe.1)
            guard let self, self.failedAll else { return }
            self.liveProblem = problem
            self.request.onLiveResult?(problem)
        }
    }

    private func didStart() {
        sourcePlaying = true
        stallSeconds = 0
        stallPosition = -1
        if request.isLive {
            liveProblem = nil
            request.onLiveResult?(nil)
        }
        onPlayed?(request, request.candidates[index])
        if !request.isLive, !started {
            started = true
            wasPaused = false
            emitScrobble("start", percent)
        }
    }

    /// The Subtitle style settings for subtitles the system player draws.
    static func textStyle(_ style: SubtitleStyle) -> [AVTextStyleRule] {
        func argb(_ v: UInt32) -> [Double] {
            [Double((v >> 24) & 0xFF) / 255, Double((v >> 16) & 0xFF) / 255, Double((v >> 8) & 0xFF) / 255, Double(v & 0xFF) / 255]
        }
        var attributes: [String: Any] = [
            kCMTextMarkupAttribute_RelativeFontSize as String: Double(style.size),
            kCMTextMarkupAttribute_ForegroundColorARGB as String: argb(style.textColor),
            kCMTextMarkupAttribute_CharacterBackgroundColorARGB as String: argb(style.backgroundColor),
            kCMTextMarkupAttribute_BoldStyle as String: style.bold
        ]
        if style.outline {
            attributes[kCMTextMarkupAttribute_CharacterEdgeStyle as String] = kCMTextMarkupCharacterEdgeStyle_Uniform as String
        }
        return AVTextStyleRule(textMarkupAttributes: attributes).map { [$0] } ?? []
    }

    /// Picks the preferred audio and subtitle languages in files AVPlayer opens.
    private func applyLanguages(to item: AVPlayerItem) {
        let audio = settings.audioLanguage
        let subs = settings.subtitleLanguage
        let subsOn = settings.subtitlesOn
        Task { @MainActor in
            if !audio.isEmpty, let group = try? await item.asset.loadMediaSelectionGroup(for: .audible),
               let option = group.options.first(where: { Languages.matches($0.extendedLanguageTag ?? $0.locale?.identifier, audio) }) {
                item.select(option, in: group)
            }
            if let group = try? await item.asset.loadMediaSelectionGroup(for: .legible) {
                if !subsOn {
                    if group.allowsEmptySelection { item.select(nil, in: group) }
                } else {
                    let wanted = subs.isEmpty ? (Locale.current.language.languageCode?.identifier ?? "en") : subs
                    if let option = group.options.first(where: { Languages.matches($0.extendedLanguageTag ?? $0.locale?.identifier, wanted) }) {
                        item.select(option, in: group)
                    }
                }
            }
        }
    }

    private func armTimeout() {
        timeout?.cancel()
        let seconds = UInt64(max(settings.startupTimeout, 5)) * 1_000_000_000
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: seconds)
            if Task.isCancelled { return }
            await MainActor.run { if self?.stopped == false { self?.failover() } }
        }
    }

    /// A failed AVPlayer source is retried once with mpv, which opens far more formats, before moving on.
    private func failover() {
        if !usingMPV, !triedMPV.contains(index), request.candidates.indices.contains(index),
           request.candidates[index].playableURL != nil || resolved[index] != nil {
            triedMPV.insert(index)
            load(at: index)
            return
        }
        nextSource()
    }

    private func nextSource() {
        guard settings.failoverEnabled, switches < settings.maxSwitches,
              request.candidates.indices.contains(index) else { failedAll = true; return }
        let failed = request.candidates[index]
        if !request.isLive { DeadLinks.mark(failed, playedURL: resolved[index]) }
        var next = index + 1
        while next < request.candidates.count, shouldSkip(request.candidates[next], after: failed) { next += 1 }
        guard next < request.candidates.count else { failedAll = true; return }
        switches += 1
        load(at: next)
    }

    /// Copies of the source that just failed, links that failed recently, and links that would start a
    /// download instead of the title are not worth a try.
    private func shouldSkip(_ candidate: StreamItem, after failed: StreamItem) -> Bool {
        if candidate.url == failed.url, candidate.infoHash == failed.infoHash { return true }
        if request.isLive { return false }
        return DeadLinks.isDead(candidate) || SmartRanker.isMarkedUncached(candidate)
    }

    private func ended() {
        if request.isLive { return }
        advance(completedCurrent: true)
    }

    private func advance(completedCurrent: Bool, userAsked: Bool = false) {
        guard !advancing else { return }
        finishRecording(completed: completedCurrent)
        if started { emitScrobble("stop", completedCurrent ? max(percent, 95) : percent) }
        started = false
        lastSeconds = 0
        if SleepTimer.shared.consumeEndOfEpisode() {
            sleptMessage = true
            return
        }
        guard settings.autoNext || userAsked, let provider = request.nextEpisode else { return }
        advancing = true
        upNextVisible = false
        status = "Finding the next episode…"
        Task {
            let next = await provider()
            status = nil
            advancing = false
            guard let next, !stopped else { return }
            replaceRequest(next)
        }
    }

    /// Moves on to another title or episode (the next episode, or what a watch party switched to).
    func replaceRequest(_ next: PlayRequest) {
        finishRecordingIfNeeded()
        request = next
        triedMPV.removeAll()
        resolved.removeAll()
        switches = 0
        lastSeconds = 0
        lastDuration = 0
        upNextDismissed = false
        skipTarget = nil
        loadSkipSegments()
        loadSubtitles()
        load(at: 0, keepPosition: false)
    }

    /// A switch made from outside (watch party) still saves how far the previous title got.
    private func finishRecordingIfNeeded() {
        guard started else { return }
        finishRecording(completed: false)
        emitScrobble("stop", percent)
        started = false
    }
}

struct PlayerViewControllerRepresentable: UIViewControllerRepresentable {
    let player: AVPlayer
    @ObservedObject var controller: PlaybackController
    @ObservedObject var sleep: SleepTimer
    @ObservedObject var party: PartyRepository

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        var menus: [UIMenu] = []
        let candidates = controller.request.candidates
        if candidates.count > 1 {
            let actions = candidates.prefix(25).enumerated().map { (i, s) in
                UIAction(title: s.label, subtitle: s.addonName ?? "",
                         state: i == controller.activeIndex ? .on : .off) { [weak controller] _ in
                    Task { @MainActor in controller?.choose(index: i) }
                }
            }
            menus.append(UIMenu(title: "Quality", image: UIImage(systemName: "sparkles.tv"), children: actions))
        }
        if !controller.request.isLive {
            if !controller.addonSubtitles.isEmpty {
                let subs = controller.addonSubtitles.prefix(40).map { sub in
                    UIAction(title: sub.label) { [weak controller] _ in
                        Task { @MainActor in controller?.useSubtitle(sub) }
                    }
                }
                menus.append(UIMenu(title: "Add-on subtitles", image: UIImage(systemName: "captions.bubble"), children: subs))
            }
            let speeds = [0.75, 1.0, 1.25, 1.5, 2.0].map { r in
                UIAction(title: r == 1 ? "Normal" : String(format: "%gx", r), state: controller.speed == r ? .on : .off) { [weak controller] _ in
                    Task { @MainActor in controller?.setSpeed(r) }
                }
            }
            menus.append(UIMenu(title: "Speed", image: UIImage(systemName: "speedometer"), children: speeds))
            var items: [UIAction] = [
                UIAction(title: "Off", state: sleep.mode == .off ? .on : .off) { _ in
                    Task { @MainActor in SleepTimer.shared.cancel() }
                }
            ]
            for m in SleepTimer.minuteOptions {
                items.append(UIAction(title: "\(m) minutes") { _ in
                    Task { @MainActor in SleepTimer.shared.start(minutes: m) }
                })
            }
            items.append(UIAction(title: "End of episode", state: sleep.mode == .endOfEpisode ? .on : .off) { _ in
                Task { @MainActor in SleepTimer.shared.endOfEpisode() }
            })
            menus.append(UIMenu(title: sleep.label, image: UIImage(systemName: "moon.zzz"), children: items))
            if let partyMenu = PartyMenu.make(party: party, controller: controller) { menus.append(partyMenu) }
        }
        vc.transportBarCustomMenuItems = menus
        // Netflix style buttons in the corner: Skip Intro and Next Episode.
        var actions: [UIAction] = []
        if controller.skipTarget != nil {
            actions.append(UIAction(title: controller.skipLabel, image: UIImage(systemName: "forward.fill")) { [weak controller] _ in
                Task { @MainActor in controller?.skipIntro() }
            })
        }
        if controller.upNextVisible {
            actions.append(UIAction(title: "Next Episode", image: UIImage(systemName: "forward.end.fill")) { [weak controller] _ in
                Task { @MainActor in controller?.playNextNow() }
            })
        }
        vc.contextualActions = actions
    }
}

struct PlayerScreen: View {
    @StateObject private var controller: PlaybackController
    @ObservedObject private var sleep = SleepTimer.shared
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var streams: StreamStore
    @EnvironmentObject var trakt: TraktStore
    @EnvironmentObject var simkl: SimklStore
    @EnvironmentObject var mdblist: MDBListStore
    @EnvironmentObject var party: PartyRepository
    @EnvironmentObject var servers: MediaServerStore
    @Environment(\.dismiss) private var dismiss
    private let settings: SettingsStore
    @State private var bridge: PartyPlayerBridge?
    /// A message with buttons is showing over the picture.
    private var blocked: Bool { controller.failedAll || controller.sleptMessage }

    init(request: PlayRequest, settings: SettingsStore) {
        self.settings = settings
        _controller = StateObject(wrappedValue: PlaybackController(request: request, settings: settings))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if controller.usingMPV {
                MPVPlayerLayer(controller: controller, sleep: sleep, party: party, interactive: !blocked)
            } else if !blocked {
                // The system player keeps the remote's focus, so it steps aside while a message needs a button press.
                PlayerViewControllerRepresentable(player: controller.player, controller: controller, sleep: sleep, party: party)
                    .ignoresSafeArea()
            }
            if party.party != nil {
                VStack {
                    HStack { Spacer(); PartyBadge(party: party) }
                    Spacer()
                }
            }
            if let status = controller.status {
                VStack {
                    HStack(spacing: 16) { ProgressView(); Text(status) }
                        .padding(.horizontal, 28).padding(.vertical, 16).glass()
                    Spacer()
                }
                .padding(.top, 60)
                .allowsHitTesting(false)
            }
            if controller.failedAll {
                VStack(spacing: 20) {
                    Text("None of the available sources would play.")
                    if let problem = controller.liveProblem { Text(problem.rawValue).font(.callout).foregroundStyle(.orange) }
                    Button("Close") { dismiss() }
                }
                .padding(40)
                .glass()
            }
            if controller.sleptMessage {
                VStack(spacing: 20) {
                    Text("Sleep timer ended playback.")
                    Button("Close") { dismiss() }
                    Button("Keep watching") {
                        controller.sleptMessage = false
                        controller.player.play()
                        if controller.usingMPV { controller.mpv.play() }
                    }
                }
                .padding(40)
                .glass()
            }
        }
        .onAppear {
            controller.onProgress = { request, seconds, duration, completed in
                library.recordPlay(meta: request.meta, videoId: request.videoId, seconds: seconds,
                                   duration: duration > 0 ? duration : nil, completed: completed)
            }
            controller.onPlayed = { request, stream in
                if request.meta.type == "series" { streams.rememberRelease(parentId: request.meta.id, stream: stream) }
            }
            controller.onScrobble = { request, action, percent in
                trakt.scrobble(action, meta: request.meta, videoId: request.videoId, progress: percent)
                simkl.scrobble(action, meta: request.meta, videoId: request.videoId, progress: percent)
                mdblist.scrobble(action, meta: request.meta, videoId: request.videoId, progress: percent)
            }
            controller.start()
            let b = PartyPlayerBridge(controller: controller, party: party, settings: settings, streams: streams,
                                      library: library, servers: servers)
            b.start()
            bridge = b
        }
        .onDisappear {
            bridge?.stop()
            bridge = nil
            controller.stop()
        }
    }
}

/// Controls for mpv playback, laid out like the system player: click to pause, left and right to skip,
/// swipe up or down for quality, audio, subtitles, speed and the sleep timer.
struct MPVPlayerLayer: View {
    @ObservedObject var controller: PlaybackController
    @ObservedObject var sleep: SleepTimer
    @ObservedObject var party: PartyRepository
    /// False while a message with its own buttons covers the player, so the remote reaches those buttons.
    var interactive = true
    @State private var showMenu = false
    @State private var showBar = true
    @State private var tracks: [MPVEngine.Track] = []
    @State private var hideTask: Task<Void, Never>?

    var body: some View {
        ZStack(alignment: .bottom) {
            MPVSurface(engine: controller.mpv).ignoresSafeArea()
            if showBar || showMenu {
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                VStack(alignment: .leading, spacing: 18) {
                    if showMenu { menuPanel }
                    progressBar
                }
                .padding(.horizontal, 80)
                .padding(.bottom, 60)
                .transition(.opacity)
            }
            if !showMenu, let corner = cornerAction {
                // Netflix style corner button. Clicking the remote presses it while it is showing.
                HStack {
                    Spacer()
                    Label(corner, systemImage: controller.skipTarget != nil ? "forward.fill" : "forward.end.fill")
                        .font(.headline)
                        .foregroundStyle(.black)
                        .padding(.horizontal, 30).padding(.vertical, 16)
                        .background(.white, in: RoundedRectangle(cornerRadius: 12))
                        .shadow(radius: 12)
                }
                .padding(.trailing, 80)
                .padding(.bottom, showBar ? 200 : 80)
                .transition(.opacity)
                .allowsHitTesting(false)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: showBar)
        .animation(.easeInOut(duration: 0.2), value: showMenu)
        .focusable(!showMenu && interactive)
        .onPlayPauseCommand { controller.mpv.togglePause(); poke() }
        .onTapGesture {
            if controller.skipTarget != nil { controller.skipIntro() }
            else if controller.upNextVisible { controller.playNextNow() }
            else { controller.mpv.togglePause() }
            poke()
        }
        .onMoveCommand { direction in
            guard !showMenu else { return }
            switch direction {
            case .left: controller.mpv.seek(by: -10); poke()
            case .right: controller.mpv.seek(by: 10); poke()
            case .up, .down: openMenu()
            @unknown default: break
            }
        }
        .onExitCommand(perform: showMenu && interactive ? { showMenu = false; poke() } : nil)
        .onAppear { poke() }
    }

    private var cornerAction: String? {
        if controller.skipTarget != nil { return controller.skipLabel }
        if controller.upNextVisible { return "Next Episode" }
        return nil
    }

    private var progressBar: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let pos = controller.mpv.position
            let dur = controller.mpv.duration
            VStack(alignment: .leading, spacing: 12) {
                Text(controller.request.title).font(.title3.bold()).lineLimit(1)
                if !controller.request.isLive, dur > 0 {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(.white.opacity(0.25))
                            Capsule().fill(.white).frame(width: geo.size.width * min(1, max(0, pos / dur)))
                        }
                    }
                    .frame(height: 8)
                    HStack {
                        Text(Self.clock(pos))
                        if controller.mpv.isPaused { Image(systemName: "pause.fill") }
                        if controller.speed != 1 { Text(String(format: "%gx", controller.speed)) }
                        Spacer()
                        Text("-" + Self.clock(max(0, dur - pos)))
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                } else if controller.request.isLive {
                    Label("Live", systemImage: "dot.radiowaves.left.and.right").font(.caption).foregroundStyle(.red)
                }
            }
        }
    }

    private var menuPanel: some View {
        HStack(spacing: 30) {
            if controller.request.candidates.count > 1 {
                Menu {
                    ForEach(Array(controller.request.candidates.prefix(25).enumerated()), id: \.offset) { i, s in
                        Button { controller.choose(index: i) } label: {
                            if i == controller.activeIndex { Label("\(s.label) \(s.addonName ?? "")", systemImage: "checkmark") }
                            else { Text("\(s.label) \(s.addonName ?? "")") }
                        }
                    }
                } label: { Label("Quality", systemImage: "sparkles.tv") }
            }
            let audio = tracks.filter { $0.type == "audio" }
            if audio.count > 1 {
                Menu {
                    ForEach(audio) { t in
                        Button { controller.mpv.select(t); refreshTracks() } label: {
                            if t.selected { Label(t.title, systemImage: "checkmark") } else { Text(t.title) }
                        }
                    }
                } label: { Label("Audio", systemImage: "speaker.wave.2") }
            }
            let subs = tracks.filter { $0.type == "sub" }
            if !subs.isEmpty || !controller.addonSubtitles.isEmpty {
                Menu {
                    Button("Off") { controller.mpv.subtitlesOff(); refreshTracks() }
                    ForEach(subs) { t in
                        Button { controller.mpv.select(t); refreshTracks() } label: {
                            if t.selected { Label(t.title, systemImage: "checkmark") } else { Text(t.title) }
                        }
                    }
                    if !controller.addonSubtitles.isEmpty {
                        Menu("More from add-ons") {
                            ForEach(Array(controller.addonSubtitles.prefix(40).enumerated()), id: \.offset) { _, sub in
                                Button(sub.label) { controller.useSubtitle(sub); refreshTracks() }
                            }
                        }
                    }
                } label: { Label("Subtitles", systemImage: "captions.bubble") }
            }
            if !controller.request.isLive {
                Menu {
                    ForEach([0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { r in
                        Button { controller.setSpeed(r) } label: {
                            let title = r == 1 ? "Normal" : String(format: "%gx", r)
                            if controller.speed == r { Label(title, systemImage: "checkmark") } else { Text(title) }
                        }
                    }
                } label: { Label("Speed", systemImage: "speedometer") }
                Menu {
                    Button("Off") { SleepTimer.shared.cancel() }
                    ForEach(SleepTimer.minuteOptions, id: \.self) { m in
                        Button("\(m) minutes") { SleepTimer.shared.start(minutes: m) }
                    }
                    Button("End of episode") { SleepTimer.shared.endOfEpisode() }
                } label: { Label(sleep.label, systemImage: "moon.zzz") }
                PartyMenuButton(party: party, controller: controller)
                Button { controller.mpv.seek(by: -30) } label: { Label("30s", systemImage: "gobackward.30") }
                Button { controller.mpv.seek(by: 30) } label: { Label("30s", systemImage: "goforward.30") }
            }
        }
        .padding(24)
        .glass()
    }

    private func openMenu() {
        refreshTracks()
        showMenu = true
        showBar = true
        hideTask?.cancel()
    }

    private func refreshTracks() { tracks = controller.mpv.tracks() }

    /// Shows the progress bar for a few seconds after any remote press.
    private func poke() {
        showBar = true
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            if Task.isCancelled { return }
            if !showMenu && !controller.mpv.isPaused { showBar = false }
        }
    }

    static func clock(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let s = Int(seconds)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                         : String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// Links that just failed to play, remembered for half an hour so that failover and the next try of
/// the same title do not pick them again. Memory only.
@MainActor
enum DeadLinks {
    private static let ttl: TimeInterval = 30 * 60
    private static var until: [String: Date] = [:]

    static func mark(_ stream: StreamItem, playedURL: URL? = nil) {
        let expiry = Date().addingTimeInterval(ttl)
        for key in keys(stream) { until[key] = expiry }
        if let playedURL { until["url:" + playedURL.absoluteString] = expiry }
        if until.count > 500 { until = until.filter { $0.value > Date() } }
    }

    static func isDead(_ stream: StreamItem) -> Bool {
        let now = Date()
        return keys(stream).contains { (until[$0] ?? .distantPast) > now }
    }

    private static func keys(_ stream: StreamItem) -> [String] {
        var out: [String] = []
        if let url = stream.url, !url.isEmpty { out.append("url:" + url) }
        if let hash = stream.infoHash?.lowercased(), !hash.isEmpty {
            out.append("torrent:\(hash):\(stream.fileIdx.map(String.init) ?? "-")")
        }
        return out
    }
}
