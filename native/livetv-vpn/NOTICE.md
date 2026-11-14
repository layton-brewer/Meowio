# Live TV VPN engine

This folder is the WireGuard engine from [NuvioPlusIPTV](https://github.com/homelessbrian/NuvioPlusIPTV) (`vpn-android/native`). ghbarker wrote it and contributed it to that project, which is licensed GPL-3.0, the same license as Meowio. The code is used as is. The only change is the JNI function names, which are renamed to Meowio's `com.meowio.livetv.LiveTvVpnNative` class.

- WireGuard runs inside the app on a gVisor netstack. There is no system VPN and no Android TUN interface.
- The engine only exposes a token-protected HTTP proxy on 127.0.0.1. Only the Live TV relay ever uses it.
- Before each request, the engine asks the app whether that address may use the tunnel. Meowio refuses debrid hosts, so they are never sent through it.

`internal/netstack` comes from wireguard-go (MIT, see its LICENSE). Other dependencies: wireguard-go (MIT), gVisor (Apache-2.0) and golang.org/x (BSD).
