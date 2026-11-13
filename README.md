<div align="center">

<img src="docs/assets/meowio-logo.webp" alt="Meowio cat logo" width="220" />

# Meowio

**Pick something to watch. Let Meowio pick the stream.**

A free, open-source fork of [Nuvio](https://github.com/NuvioMedia) for Android phones, tablets, and TV, with Smart auto-play, built-in free Live TV, and a frosted-glass interface.

[Download](https://github.com/layton-brewer/Meowio/releases/latest) · [What's different](#whats-different-from-nuvio) · [Get started](#get-started) · [Screenshots](#screenshots) · [Report an issue](https://github.com/layton-brewer/Meowio/issues)

</div>

## Download

Choose the APK for your device. Both apps require **Android 7.0 or newer**.

| Device | Download |
| --- | --- |
| Android phone or tablet | [Meowio-mobile.apk](https://github.com/layton-brewer/Meowio/releases/latest/download/Meowio-mobile.apk) |
| Android TV, Google TV, or Android-based Fire TV | [Meowio-tv.apk](https://github.com/layton-brewer/Meowio/releases/latest/download/Meowio-tv.apk) |

See [all releases](https://github.com/layton-brewer/Meowio/releases) for earlier versions. The APKs in this repository are Meowio; the upstream Nuvio store listings are separate apps.

## What's different from Nuvio?

Meowio builds on Nuvio's media browsing and playback foundation. These are the additions and changed defaults in this fork, relative to the Nuvio versions it started from.

### Less time choosing streams

| Feature | What it does |
| --- | --- |
| **Smart auto-play** | Press Play to automatically choose a source instead of opening the source list. Direct links, cached debrid streams, and media-server sources are prioritized over sources that still need to download. |
| **Pinned sources** | Give preferred add-ons or plugins priority in Smart ranking and bring their streams to the top of the source list. Availability and cam/screener ranking still take precedence in Smart mode. |
| **Season-pack preference** | For series, favor full-season releases and try to keep subsequent episodes on the same release when available. |
| **Resolution matching** | Favor streams that match the device's display resolution, then higher resolutions, then lower ones. This is a ranking preference, not a bandwidth cap. |
| **Lower priority for poor copies** | Push cam, telesync, and screener releases down the ranking within the same availability tier. They are not completely blocked. |
| **Automatic fallback** | If playback fails and backup sources are available, try another source without sending you back to the list, with a limit of six source switches per title. |
| **Source preloading** | Start looking up sources when you open a title and preload next-episode sources to reduce waiting. |
| **Auto-next enabled by default** | Continue to the next episode automatically, preferring the same release when possible. |

Prefer to choose for yourself? Change **Stream selection** to **Manual** in the playback settings.

### Live TV, ready to open

- **Free channels included:** Live TV starts with the [Free-TV project's channel list](https://github.com/Free-TV/IPTV), so you can browse without adding a playlist.
- **Country-based starting group:** When a matching group exists, the built-in list opens on the country configured on your device.
- **Your own sources:** Replace the built-in list with an M3U playlist URL or Xtream login. Removing your source restores the free channels.
- **Channel browsing:** Search, filter by group, save favorites, and return to recently watched channels.
- **Now and next:** See programme information when guide data is available.
- **Separate from movie and series tracking:** Live channels stay out of watch history and scrobbling. Live TV source settings stay on the device and are not synced.
- **Optional tab:** Hide Live TV in layout settings if you do not use it.

Channel availability depends on the source and your region. Built-in Live TV does not provide an on-demand movie or series library.

### A different look and everyday controls

- **Frosted-glass interface:** Translucent panels, subtle borders, and highlights across menus, dialogs, title details, episode cards, and player controls. Background blur is used where supported.
- **Meowio branding:** Cat icons, themed app artwork, TV banners, and a matching splash screen.
- **Sleep timer:** Pause playback after a chosen time or at the end of the episode.
- **Playback speed remembered per show:** Available on both apps; Meowio adds this behavior to mobile.
- **Meowio updates:** The in-app updater checks this repository's releases and selects the APK for your device.
- **Optimized distributed builds:** Code optimization and startup profiles are enabled in the shipped APKs.

### Playback defaults

Fresh installs use these defaults. Existing saved preferences are preserved.

| Setting | Phone / tablet | TV |
| --- | --- | --- |
| Stream selection | Smart | Smart |
| Auto-play next episode | On | On |
| Prefer / reuse the same release for series | On | On |
| Preload next-episode sources | On | On |
| Tunneled playback | On | On |
| Audio downmix | Off | Off |
| Force AC-3 transcoding for optical / SPDIF | Not available | On |

Playback and audio settings remain adjustable for your device and sound system.

## Get started

1. **Download the matching APK** from the links above.
2. **Install it on your device.** If prompted, allow the browser or file manager you are using to install unknown apps. On TV, transfer the TV APK to the device and open it with a file manager.
3. **Choose what to watch:**
   - **Live TV:** Open the Live TV tab to browse the included free channels, or configure your own source there.
   - **Movies and series:** Configure your add-ons or media sources first. Smart auto-play chooses among the streams those sources return; it does not supply an on-demand catalogue of playable streams by itself.
4. **Open a title and press Play.** Leave Stream selection on Smart for automatic selection, or choose Manual to see the source list.
5. **Adjust playback to suit you.** Set your audio and subtitle preferences, choose whether episodes continue automatically, and use the player's sleep timer when needed.

## Screenshots

Phone and TV screenshots are coming soon.

<!--
SCREENSHOT LAYOUT — enable this block once the actual image files are committed.
Suggested files under docs/screenshots/:
  mobile-home.png, mobile-player.png
  tv-home.png, tv-live-tv.png

Replace the coming-soon line with the following HTML, then remove this comment.
Use real Meowio captures and keep credentials, account details, and private URLs out of view.

### Phone
<p>
  <img src="docs/screenshots/mobile-home.png" alt="Meowio home screen on Android" width="240" />
  <img src="docs/screenshots/mobile-player.png" alt="Meowio mobile player and playback controls" width="240" />
</p>

### TV
<p>
  <img src="docs/screenshots/tv-home.png" alt="Meowio TV home screen with the glass sidebar" width="480" />
  <img src="docs/screenshots/tv-live-tv.png" alt="Meowio Live TV channel browser" width="480" />
</p>
-->

## FAQ

**Can I still select a stream manually?**  
Yes. Set Stream selection to Manual in the playback settings.

**Does pinning a source guarantee that Smart mode uses it?**  
No. Smart mode considers availability and whether a release is a cam/screener before pinned-source preference. It then considers season packs, resolution, source quality, and the original add-on order.

**Does Meowio include movies and series?**  
You bring your own add-ons or media sources for on-demand playback. The included free-channel playlist is for Live TV.

**Why is a Live TV channel or programme guide missing?**  
Channels and guide data come from the configured provider. Availability can change, and not every channel has guide data. You can refresh the list or configure a different source.

**How do I update?**  
Use the in-app updater or download the latest APK for the same device type from [Releases](https://github.com/layton-brewer/Meowio/releases/latest).

**Are there iOS or desktop downloads?**  
This repository currently publishes Android mobile and TV APKs. The mobile source tree includes upstream multiplatform code, but that does not mean a Meowio iOS or desktop release is available.

## Bugs and feedback

[Open an issue](https://github.com/layton-brewer/Meowio/issues) with:

- Your device model, Android version, and Meowio version.
- Whether you use the mobile or TV app.
- What you expected, what happened, and the steps to reproduce it.
- A screenshot or relevant log excerpt, if useful. Remove credentials and private stream URLs before posting.

## Build from source

The apps have separate Gradle projects. Use JDK 17 and an Android SDK; the [release workflow](.github/workflows/release.yml) shows the build configuration used for published APKs.

```bash
git clone https://github.com/layton-brewer/Meowio.git
cd Meowio
```

**Phone / tablet** — from the repository root:

```bash
cd mobile
./gradlew :androidApp:assembleFullDebug -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore
```

**TV** — from the repository root:

```bash
cd tv
./gradlew :app:assembleFullDebug
```

| Folder | App | Upstream project |
| --- | --- | --- |
| [mobile/](mobile/) | Android phone / tablet | [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) |
| [tv/](tv/) | Android TV | [NuvioTV](https://github.com/NuvioMedia/NuvioTV) |

Some source files, configuration keys, and platform documentation retain upstream Nuvio names.

## Credits and license

Meowio is based on the work of the [Nuvio team](https://github.com/NuvioMedia). The default Live TV playlist comes from [Free-TV/IPTV](https://github.com/Free-TV/IPTV).

Licensed under [GNU GPL v3.0](LICENSE), the same as Nuvio. Meowio is an independent fork and is not affiliated with or endorsed by the Nuvio team.
