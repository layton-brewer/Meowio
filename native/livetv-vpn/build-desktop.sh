#!/usr/bin/env bash
# Builds the Live TV VPN helper for Mac or Windows into <desktop-sources>/composeApp/src/desktopMain/resources/livetv-vpn/<os>-<arch>/.
# It is plain Go (no cgo), so it cross-compiles from any host.
# Usage: build-desktop.sh <macos|windows> <arm64|x86_64> <desktop-sources-dir>
set -euo pipefail
os="$1"; arch="$2"; root="$3"
here="$(cd "$(dirname "$0")" && pwd)"
case "$os" in macos) goos=darwin; ext="" ;; windows) goos=windows; ext=".exe" ;; *) echo "unknown os $os" >&2; exit 2 ;; esac
case "$arch" in arm64) goarch=arm64 ;; x86_64) goarch=amd64 ;; *) echo "unknown arch $arch" >&2; exit 2 ;; esac
out="$root/composeApp/src/desktopMain/resources/livetv-vpn/$os-$arch"
mkdir -p "$out"
(cd "$here" && GOTOOLCHAIN=go1.27.3 CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
  go build -trimpath -ldflags "-s -w -buildid=" -o "$out/meowio-vpn$ext" .)
ls -l "$out"
