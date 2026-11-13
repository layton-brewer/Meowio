import SwiftUI
import AVKit
import Combine

/// Plays the best candidate and silently moves to the next one on failure or stall.
@MainActor
final class PlaybackController: ObservableObject {
    let player = AVPlayer()
    @Published var failedAll = false
    private var candidates: [StreamItem]
    private var index = 0
    private var cancellables = Set<AnyCancellable>()
    private var timeout: Task<Void, Never>?
    private let maxSwitches = 8

    init(candidates: [StreamItem]) {
        self.candidates = candidates
        player.allowsExternalPlayback = true
    }

    func start() { load(at: 0) }

    func stop() {
        timeout?.cancel()
        player.pause()
        player.replaceCurrentItem(with: nil)
        cancellables.removeAll()
    }

    private func load(at i: Int) {
        guard i < candidates.count, i < maxSwitches, let url = candidates[i].playableURL else {
            failedAll = true
            return
        }
        index = i
        cancellables.removeAll()
        let item = AVPlayerItem(url: url)
        item.publisher(for: \.status)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in
                guard let self else { return }
                if status == .readyToPlay {
                    self.timeout?.cancel()
                    self.player.play()
                } else if status == .failed {
                    self.next()
                }
            }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime, object: item)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.next() }
            .store(in: &cancellables)
        player.replaceCurrentItem(with: item)
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            if Task.isCancelled { return }
            await MainActor.run { self?.next() }
        }
    }

    private func next() { load(at: index + 1) }
}

struct PlayerViewControllerRepresentable: UIViewControllerRepresentable {
    let player: AVPlayer
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        return vc
    }
    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {}
}

struct PlayerScreen: View {
    let request: PlayRequest
    @StateObject private var controller: PlaybackController
    @Environment(\.dismiss) private var dismiss

    init(request: PlayRequest) {
        self.request = request
        _controller = StateObject(wrappedValue: PlaybackController(candidates: request.candidates))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            PlayerViewControllerRepresentable(player: controller.player).ignoresSafeArea()
            if controller.failedAll {
                VStack(spacing: 20) {
                    Text("None of the available sources would play.")
                    Button("Close") { dismiss() }
                }
            }
        }
        .onAppear { controller.start() }
        .onDisappear { controller.stop() }
    }
}
