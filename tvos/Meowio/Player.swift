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
    /// Resolves the request for the episode after this one, when there is one.
    var nextEpisode: (@MainActor () async -> PlayRequest?)? = nil
}

/// Plays the best candidate and silently moves to the next one when it fails or does not start.
@MainActor
final class PlaybackController: ObservableObject {
    let player = AVPlayer()
    @Published var failedAll = false
    @Published var request: PlayRequest
    @Published var activeIndex = 0
    @Published var sleptMessage = false

    private let settings: SettingsStore
    private var index = 0
    private var switches = 0
    private var cancellables = Set<AnyCancellable>()
    private var timeout: Task<Void, Never>?
    private var timeObserver: Any?
    private var sleepTicker: Timer?
    private(set) var lastSeconds: Double = 0
    private(set) var lastDuration: Double = 0
    var onProgress: ((PlayRequest, Double, Bool) -> Void)?
    var onPlayed: ((PlayRequest, StreamItem) -> Void)?
    var onClose: (() -> Void)?

    init(request: PlayRequest, settings: SettingsStore) {
        self.request = request
        self.settings = settings
        player.allowsExternalPlayback = true
    }

    func start() {
        let interval = CMTime(seconds: 5, preferredTimescale: 1)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self else { return }
            let seconds = time.seconds
            if seconds.isFinite { self.lastSeconds = seconds }
            if let d = self.player.currentItem?.duration.seconds, d.isFinite { self.lastDuration = d }
        }
        sleepTicker = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if SleepTimer.shared.consumeExpired() {
                    self.player.pause()
                    self.sleptMessage = true
                }
            }
        }
        load(at: 0)
    }

    func stop() {
        finishRecording(completed: false)
        timeout?.cancel()
        sleepTicker?.invalidate()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        cancellables.removeAll()
    }

    /// The user picked a specific source from the quality picker.
    func choose(index i: Int) {
        guard request.candidates.indices.contains(i) else { return }
        load(at: i)
    }

    private func finishRecording(completed: Bool) {
        guard !request.isLive, lastSeconds > 5 else { return }
        let done = completed || (lastDuration > 0 && lastSeconds / lastDuration > 0.92)
        onProgress?(request, lastSeconds, done)
    }

    private func load(at i: Int) {
        guard request.candidates.indices.contains(i), let url = request.candidates[i].playableURL else {
            failedAll = true
            return
        }
        index = i
        activeIndex = i
        failedAll = false
        cancellables.removeAll()
        let asset: AVURLAsset
        if request.headers.isEmpty {
            asset = AVURLAsset(url: url)
        } else {
            asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": request.headers])
        }
        let item = AVPlayerItem(asset: asset)
        item.publisher(for: \.status)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in
                guard let self else { return }
                if status == .readyToPlay {
                    self.timeout?.cancel()
                    self.player.play()
                    self.onPlayed?(self.request, self.request.candidates[self.index])
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
        timeout?.cancel()
        let seconds = UInt64(max(settings.startupTimeout, 5)) * 1_000_000_000
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: seconds)
            if Task.isCancelled { return }
            await MainActor.run { self?.failover() }
        }
    }

    private func failover() {
        guard settings.failoverEnabled, switches < settings.maxSwitches else { failedAll = true; return }
        let failedURL = request.candidates[index].url
        var next = index + 1
        while next < request.candidates.count, request.candidates[next].url == failedURL { next += 1 }
        guard next < request.candidates.count else { failedAll = true; return }
        switches += 1
        load(at: next)
    }

    private func ended() {
        finishRecording(completed: true)
        lastSeconds = 0
        if request.isLive { return }
        if SleepTimer.shared.consumeEndOfEpisode() {
            sleptMessage = true
            return
        }
        guard settings.autoNext, let provider = request.nextEpisode else { return }
        Task {
            if let next = await provider() {
                request = next
                switches = 0
                lastSeconds = 0
                lastDuration = 0
                load(at: 0)
            }
        }
    }
}

struct PlayerViewControllerRepresentable: UIViewControllerRepresentable {
    let player: AVPlayer
    @ObservedObject var controller: PlaybackController
    @ObservedObject var sleep: SleepTimer

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
        }
        vc.transportBarCustomMenuItems = menus
    }
}

struct PlayerScreen: View {
    @StateObject private var controller: PlaybackController
    @ObservedObject private var sleep = SleepTimer.shared
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var streams: StreamStore
    @Environment(\.dismiss) private var dismiss

    init(request: PlayRequest, settings: SettingsStore) {
        _controller = StateObject(wrappedValue: PlaybackController(request: request, settings: settings))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            PlayerViewControllerRepresentable(player: controller.player, controller: controller, sleep: sleep)
                .ignoresSafeArea()
            if controller.failedAll {
                VStack(spacing: 20) {
                    Text("None of the available sources would play.")
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
                    }
                }
                .padding(40)
                .glass()
            }
        }
        .onAppear {
            controller.onProgress = { request, seconds, completed in
                library.recordPlay(meta: request.meta, videoId: request.videoId, seconds: seconds, completed: completed)
            }
            controller.onPlayed = { request, stream in
                if request.meta.type == "series" { streams.rememberRelease(parentId: request.meta.id, stream: stream) }
            }
            controller.start()
        }
        .onDisappear { controller.stop() }
    }
}
