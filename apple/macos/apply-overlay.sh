#!/usr/bin/env bash
# Rebrands a checkout of the desktop sources as Meowio.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd -P)"
root="$1"
gradle="$root/composeApp/build.gradle.kts"
icons="$root/composeApp/src/desktopMain/resources/icons"

sed -i.bak \
  -e 's/packageName = "Nuvio"/packageName = "Meowio"/' \
  -e 's/bundleID = "com.nuvio.media.desktop"/bundleID = "com.meowio.desktop"/' \
  -e 's/vendor = "Nuvio Media"/vendor = "Meowio"/' \
  -e 's/"Nuvio-macOS-/"Meowio-macOS-/' \
  -e 's/"Nuvio-\$desktopReleasePackageVersion/"Meowio-$desktopReleasePackageVersion/' \
  -e 's/menuGroup = "Nuvio"/menuGroup = "Meowio"/' \
  "$gradle"
rm -f "$gradle.bak"

# App icon: build an .icns from the Meowio cat icon.
set_dir="$(mktemp -d)/Meowio.iconset"
mkdir -p "$set_dir"
src="$here/../icon-1024.png"
for s in 16 32 128 256 512; do
  sips -z $s $s "$src" --out "$set_dir/icon_${s}x${s}.png" >/dev/null
  sips -z $((s*2)) $((s*2)) "$src" --out "$set_dir/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$set_dir" -o "$icons/nuvio-app-icon-transparent.icns"
cp "$src" "$icons/nuvio-app-icon-transparent.png"
