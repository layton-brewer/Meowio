#!/usr/bin/env bash
# Builds the Live TV VPN static library for iPhone and iPad into <out>/libmeowiovpn.a (macOS host only).
# Usage: build-ios.sh <out-dir>
set -euo pipefail
out="$(mkdir -p "$1" && cd "$1" && pwd)"
here="$(cd "$(dirname "$0")" && pwd)"
sdk="$(xcrun --sdk iphoneos --show-sdk-path)"
clang="$(xcrun --sdk iphoneos --find clang)"
flags="-arch arm64 -isysroot $sdk -miphoneos-version-min=15.0"
(cd "$here" && GOTOOLCHAIN=go1.27.3 CGO_ENABLED=1 GOOS=ios GOARCH=arm64 CC="$clang $flags" \
  CGO_CFLAGS="$flags" CGO_LDFLAGS="$flags" \
  go build -trimpath -buildmode=c-archive -ldflags "-s -w -buildid=" -o "$out/libmeowiovpn.a" .)
ls -l "$out"
