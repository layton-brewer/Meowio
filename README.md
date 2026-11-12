# Nuvio Extended

A fork of [Nuvio](https://github.com/NuvioMedia) for Android phones and Android TV that picks a working stream for you and just plays.

## Download

Get the latest APKs from the [Releases page](../../releases/latest).

| Device | File |
| --- | --- |
| Android phone or tablet | `NuvioExtended-mobile.apk` |
| Android TV, Google TV, Fire TV | `NuvioExtended-tv.apk` |

These are debug-signed builds. Android will ask you to allow installs from unknown sources.

## What is different from Nuvio

- **Smart auto-play.** Pressing Play picks a source for you instead of showing the source list. Sources that play instantly (cached debrid, direct links) come first.
- **Season packs for series.** Episodes prefer full-season releases so the whole season comes from the same release.
- **Right resolution for the device.** Movies pick the source that best matches the screen, and skip cam and screener copies.
- **Silent failover.** If a source fails, the player moves on to the next matching source in the background, up to six tries.
- **Preloading.** Sources start loading as soon as you open a title, so Play starts faster.
- **Auto-next on by default.** The next episode starts on its own, from the same release when possible.

You can switch back to the manual source list in Settings, under Playback, Stream selection.

## Signing in

Nuvio accounts need a Supabase server address and key that are not part of the public source, so these builds have no account server. Choose **Continue Without Account** on the first screen. Add-ons, debrid services and playback all work without an account.

## Repository layout

- `tv/` is the Android TV app, based on [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV).
- `mobile/` is the phone app, based on [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile).

Each folder builds on its own with Gradle. See the README inside each folder.

## License

GPL-3.0, the same as Nuvio. Nuvio Extended is not affiliated with or endorsed by the Nuvio team.
