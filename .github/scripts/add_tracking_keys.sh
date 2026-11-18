#!/usr/bin/env bash
# Appends the tracking sign-in keys that exist as repository secrets to a local.properties file.
# Usage: add_tracking_keys.sh <properties file>
# Reads TRAKT_CLIENT_ID, TRAKT_CLIENT_SECRET, SIMKL_CLIENT_ID, MDBLIST_API_KEY, TMDB_API_KEY, PREMIUMIZE_CLIENT_ID and INTRODB_API_URL from the
# environment. Missing ones are skipped (the apps then show "Not set up yet"). Nothing is printed.
set -eu
f="$1"
add() { if [ -n "${2:-}" ]; then printf '%s=%s\n' "$1" "$2" >> "$f"; fi; }
touch "$f"
add TRAKT_CLIENT_ID "${TRAKT_CLIENT_ID:-}"
add TRAKT_CLIENT_SECRET "${TRAKT_CLIENT_SECRET:-}"
add SIMKL_CLIENT_ID "${SIMKL_CLIENT_ID:-}"
add MDBLIST_CLIENT_ID "${MDBLIST_API_KEY:-}"
add TMDB_API_KEY "${TMDB_API_KEY:-}"
add PREMIUMIZE_CLIENT_ID "${PREMIUMIZE_CLIENT_ID:-}"
# Skip intro/recap/credits timings. IntroDB reads are anonymous, so every build gets the public API
# unless a non-empty INTRODB_API_URL is already in the file or set as a secret.
if ! grep -Eq '^[[:space:]]*INTRODB_API_URL[[:space:]]*=[[:space:]]*[^[:space:]]' "$f"; then
  add INTRODB_API_URL "${INTRODB_API_URL:-https://api.introdb.app/}"
fi
