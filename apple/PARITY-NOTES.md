# Apple parity: work in progress

## macOS
- NuvioDesktop is pinned to fca444b96ea03251fb21dc46ac5de035d3358806 in `.github/workflows/apple.yml`.
- `apple/macos/overlay/` holds the Desktop sources with Meowio features three-way merged in
  (base: NuvioMobile 4af1db91, which is what `mobile/` was synced to; merge-file against the pinned Desktop).
  Includes Smart ranking, pinned sources, failover hooks, Live TV, backup/restore, insights, AI assistant,
  sleep timer, glass UI, themes, strings. New desktopMain actuals: Live TV platform, insights, glass, backup file dialogs,
  pinned sources + per-show playback speed storage.
- NOT YET COMPILED. Local `:composeApp:compileKotlinDesktop` on Linux (JDK 17 via apt, init script apple/macos/tools/p.gradle
  adding dl.google.com + repo.maven.apache.org) kept hitting Maven Central 429s. Run the macOS CI job and fix compile errors.
- Conflicts resolved by taking the Desktop side: PlayerDestination, HomeHeroSection, MetaDetailsScreen header, updater
  (updater still points at upstream Nuvio: needs to use layton-brewer/Meowio and pick Meowio-macos-*.dmg).
- CloudStream, TV downloads are Android-only; document in README.

## iOS
Builds from `mobile/`, which already has iosMain actuals for every Meowio expect. Needs only a CI check.

## tvOS
`tvos-wip/` is a half-finished rewrite (models, Smart ranker, settings store with themes/backup, add-on client,
library/stream stores, player with failover/sleep timer/quality menu, full Live TV with M3U/Xtream/XMLTV/favorites/pruning).
Still missing: Home/Search/Detail/Settings/Insights/AI views and the App entry point; nothing here has been compiled.
The working app in `tvos/Meowio/` is untouched.
