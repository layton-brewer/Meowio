import Foundation
import Security
import ComposeApp

// The Live TV VPN engine is a Go static library (native/livetv-vpn) linked into the app. Its C
// functions are declared here by name; nothing else in the app can reach the tunnel.
@_silgen_name("meowioVpnCheck") private func vpnCheck(_ config: UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>?
@_silgen_name("meowioVpnStart") private func vpnStart(_ config: UnsafePointer<CChar>, _ protectHttps: Int32, _ token: UnsafePointer<CChar>) -> Int64
@_silgen_name("meowioVpnPort") private func vpnPort(_ handle: Int64) -> Int32
@_silgen_name("meowioVpnStop") private func vpnStop(_ handle: Int64)

final class LiveTvVpnEngineImpl: NSObject, LiveTvVpnEngine {
    private var handle: Int64 = 0

    func check(config: String) -> String? {
        return config.withCString { text in
            guard let reason = vpnCheck(text) else { return nil }
            defer { free(reason) }
            return String(cString: reason)
        }
    }

    func start(config: String, protectHttps: Bool) -> String? {
        stop()
        var bytes = [UInt8](repeating: 0, count: 24)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { return nil }
        let token = Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        let id = config.withCString { text in token.withCString { vpnStart(text, protectHttps ? 1 : 0, $0) } }
        guard id != 0 else { return nil }
        let port = vpnPort(id)
        guard port > 0 else {
            vpnStop(id)
            return nil
        }
        handle = id
        return "\(port):\(token)"
    }

    func stop() {
        if handle != 0 {
            vpnStop(handle)
            handle = 0
        }
    }
}
