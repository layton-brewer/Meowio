import SwiftUI
import UIKit
import QuartzCore
import Libmpv

/// libmpv playback for formats AVPlayer cannot open (MKV and friends). Mirrors the iOS app's mpv setup.
final class MPVEngine: NSObject {
    /// Layer mpv draws into.
    let layer = TVMetalLayer()
    private var mpv: OpaquePointer?
    private let queue = DispatchQueue(label: "meowio.mpv.events")
    private var lastPosition: Double = 0
    private var lastDuration: Double = 0
    private var loaded = false

    var onLoaded: (() -> Void)?
    var onFailed: (() -> Void)?
    var onEnded: (() -> Void)?

    var position: Double { lastPosition }
    var duration: Double { lastDuration }

    override init() {
        super.init()
        layer.pixelFormat = .bgra8Unorm
        layer.framebufferOnly = true
    }

    private func start() {
        guard mpv == nil else { return }
        mpv = mpv_create()
        guard let mpv else { return }
        var wid = Int64(Int(bitPattern: Unmanaged.passUnretained(layer).toOpaque()))
        mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &wid)
        let options: [(String, String)] = [
            ("vo", "gpu-next"), ("gpu-api", "vulkan"), ("gpu-context", "moltenvk"),
            ("hwdec", "videotoolbox"), ("hwdec-software-fallback", "300"),
            ("ao", "audiounit"), ("audio-channels", "auto"), ("audio-fallback-to-null", "yes"),
            ("vulkan-swap-mode", "fifo"), ("vulkan-queue-count", "1"),
            ("vulkan-async-compute", "no"), ("vulkan-async-transfer", "no"), ("vulkan-disable-interop", "yes"),
            ("subs-match-os-language", "yes"), ("subs-fallback", "yes"),
            ("keep-open", "yes"), ("target-colorspace-hint", "yes"), ("tone-mapping", "auto"),
            ("cache", "yes"), ("demuxer-max-bytes", "150MiB")
        ]
        for (k, v) in options { mpv_set_option_string(mpv, k, v) }
        mpv_initialize(mpv)
        mpv_observe_property(mpv, 0, "time-pos", MPV_FORMAT_DOUBLE)
        mpv_observe_property(mpv, 0, "duration", MPV_FORMAT_DOUBLE)
        mpv_observe_property(mpv, 0, "eof-reached", MPV_FORMAT_FLAG)
        mpv_set_wakeup_callback(mpv, { ctx in
            guard let ctx else { return }
            let engine = Unmanaged<MPVEngine>.fromOpaque(ctx).takeUnretainedValue()
            engine.readEvents()
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    func load(url: String, headers: [String: String]) {
        start()
        guard let mpv else { onFailed?(); return }
        loaded = false
        lastPosition = 0
        lastDuration = 0
        if headers.isEmpty {
            mpv_set_property_string(mpv, "http-header-fields", "")
        } else {
            let joined = headers.map { "\($0.key): \($0.value)" }.joined(separator: ",")
            mpv_set_property_string(mpv, "http-header-fields", joined)
        }
        command(["loadfile", url, "replace"])
    }

    func play() { setFlag("pause", false) }
    func pause() { setFlag("pause", true) }
    var isPaused: Bool {
        guard let mpv else { return true }
        var flag: Int32 = 0
        mpv_get_property(mpv, "pause", MPV_FORMAT_FLAG, &flag)
        return flag != 0
    }
    func togglePause() { setFlag("pause", !isPaused) }
    func seek(by seconds: Double) { command(["seek", String(seconds), "relative"]) }

    func stop() {
        guard let m = mpv else { return }
        mpv = nil
        mpv_set_wakeup_callback(m, nil, nil)
        mpv_terminate_destroy(m)
    }

    private func setFlag(_ name: String, _ value: Bool) {
        guard let mpv else { return }
        var v: Int32 = value ? 1 : 0
        mpv_set_property(mpv, name, MPV_FORMAT_FLAG, &v)
    }

    private func command(_ args: [String]) {
        guard let mpv else { return }
        var cstrings: [UnsafeMutablePointer<CChar>?] = args.map { strdup($0) }
        cstrings.append(nil)
        mpv_command(mpv, &cstrings)
        for p in cstrings { free(p) }
    }

    private func readEvents() {
        queue.async { [weak self] in
            guard let self else { return }
            while let mpv = self.mpv {
                guard let event = mpv_wait_event(mpv, 0) else { break }
                let id = event.pointee.event_id
                if id == MPV_EVENT_NONE { break }
                switch id {
                case MPV_EVENT_PROPERTY_CHANGE:
                    guard let data = event.pointee.data else { break }
                    let prop = data.assumingMemoryBound(to: mpv_event_property.self).pointee
                    let name = String(cString: prop.name)
                    if name == "time-pos", prop.format == MPV_FORMAT_DOUBLE, let d = prop.data {
                        self.lastPosition = d.assumingMemoryBound(to: Double.self).pointee
                    } else if name == "duration", prop.format == MPV_FORMAT_DOUBLE, let d = prop.data {
                        self.lastDuration = d.assumingMemoryBound(to: Double.self).pointee
                    } else if name == "eof-reached", prop.format == MPV_FORMAT_FLAG, let d = prop.data,
                              d.assumingMemoryBound(to: Int32.self).pointee != 0 {
                        DispatchQueue.main.async { self.onEnded?() }
                    }
                case MPV_EVENT_FILE_LOADED:
                    self.loaded = true
                    DispatchQueue.main.async { self.onLoaded?() }
                case MPV_EVENT_END_FILE:
                    if let data = event.pointee.data {
                        let end = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                        if end.reason == MPV_END_FILE_REASON_ERROR {
                            DispatchQueue.main.async { self.onFailed?() }
                        }
                    }
                case MPV_EVENT_SHUTDOWN:
                    return
                default:
                    break
                }
            }
        }
    }
}

/// A Metal layer that ignores degenerate drawable sizes while mpv's swapchain is being set up.
final class TVMetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 { super.drawableSize = newValue }
        }
    }
}

/// Hosts the engine's layer.
struct MPVSurface: UIViewRepresentable {
    let engine: MPVEngine

    final class Host: UIView {
        var engine: MPVEngine?
        override func layoutSubviews() {
            super.layoutSubviews()
            guard let layer = engine?.layer else { return }
            let scale = window?.screen.nativeScale ?? UIScreen.main.nativeScale
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            layer.contentsScale = scale
            layer.frame = bounds
            layer.drawableSize = CGSize(width: bounds.width * scale, height: bounds.height * scale)
            CATransaction.commit()
        }
    }

    func makeUIView(context: Context) -> Host {
        let v = Host()
        v.backgroundColor = .black
        v.engine = engine
        v.layer.addSublayer(engine.layer)
        return v
    }

    func updateUIView(_ uiView: Host, context: Context) {}
}
