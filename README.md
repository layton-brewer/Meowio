# Meowio

A fork of [Nuvio](https://github.com/NuvioMedia) for Android phones and Android TV that picks a working stream for you and just plays.

## Download

Get the latest APKs from the [Releases page](../../releases/latest).

| Device | File |
| --- | --- |
| Android phone or tablet | `Meowio-mobile.apk` |
| Android TV, Google TV, Fire TV | `Meowio-tv.apk` |

Android will ask you to allow installs from unknown sources.

## What is different from Nuvio

- **Smart auto-play.** Pressing Play picks a source for you instead of showing the source list. Sources that play instantly (cached debrid, direct links) come first.
- **Season packs for series.** Episodes prefer full-season releases so the whole season comes from the same release.
- **Right resolution for the device.** Movies pick the source that best matches the screen, and skip cam and screener copies.
- **Silent failover.** If a source fails, the player moves on to the next matching source in the background, up to six tries.
- **Preloading.** Sources start loading as soon as you open a title, so Play starts faster.
- **Auto-next on by default.** The next episode starts on its own, from the same release when possible.

You can switch back to the manual source list in Settings, under Playback, Stream selection.

## Signing in

Signing in with a normal Nuvio account works once the build knows the Nuvio account server address and key. Those two values are private settings, so they are stored as repository secrets and never in the code.

Until the secrets are added, builds have no account server. Choose **Continue Without Account** on the first screen. Add-ons, debrid services and playback all work without an account.

## Repository secrets

Add these under Settings → Secrets and variables → Actions. None of them are ever committed to the repository.

| Secret | Purpose |
| --- | --- |
| `NUVIO_SUPABASE_URL` | Account server address (for sign-in) |
| `NUVIO_SUPABASE_ANON_KEY` | Account server public key (for sign-in) |
| `SIGNING_KEYSTORE_BASE64` | Base64 of the signing keystore, so every release installs over the last |
| `SIGNING_KEY_ALIAS` | The key alias inside that keystore |
| `SIGNING_PASSWORD` | The keystore/key password |

Without the signing secrets the build still works, but each release gets a throwaway key and will not install on top of a previous version. See the signing note below.

## A stable signing key

Every Android app is signed, and Android only lets a new APK install over an older one when both carry the **same** signature. To create a keystore once and keep it forever:

```sh
keytool -genkeypair -v \
  -keystore meowio-release.jks \
  -alias meowio -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "YOUR_PASSWORD" -keypass "YOUR_PASSWORD" \
  -dname "CN=Meowio, O=Meowio, C=US"

base64 -i meowio-release.jks   # copy this into SIGNING_KEYSTORE_BASE64
```

Then set `SIGNING_KEY_ALIAS` to `meowio` and `SIGNING_PASSWORD` to the password you chose. Keep `meowio-release.jks` backed up somewhere safe; if it is lost, future updates can no longer install over an installed copy.

## Repository layout

- `tv/` is the Android TV app, based on [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV).
- `mobile/` is the phone app, based on [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile).

Each folder builds on its own with Gradle. See the README inside each folder.

## License

GPL-3.0, the same as Nuvio. Meowio is not affiliated with or endorsed by the Nuvio team.
