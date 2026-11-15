"""Writes the AltStore / SideStore source for the iPhone and iPad build.

usage: altstore_source.py TAG IPA_PATH NOTES_FILE OUT_JSON

The source is attached to every release as altstore.json, so one stable link always describes the newest build:
https://github.com/layton-brewer/Meowio/releases/latest/download/altstore.json
Add it once in AltStore or SideStore (Sources, +) and updates then arrive as normal app updates, refreshed in the
background. tvOS is not in the source: AltStore and SideStore do not install on Apple TV.
"""
import datetime
import hashlib
import json
import os
import sys

tag, ipa, notes_path, out = sys.argv[1:5]
repo = os.environ.get("GITHUB_REPOSITORY", "layton-brewer/Meowio")
version = tag.lstrip("v")
size = os.path.getsize(ipa)
digest = hashlib.sha256(open(ipa, "rb").read()).hexdigest()
notes = ""
if os.path.exists(notes_path):
    notes = open(notes_path, encoding="utf-8").read().strip()
notes = notes or f"Meowio {version}"
date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
base = f"https://github.com/{repo}"
icon = f"https://raw.githubusercontent.com/{repo}/main/apple/icon-1024.png"
download = f"{base}/releases/download/{tag}/Meowio-ios.ipa"
subtitle = "Smart auto-play for movies and shows"
description = (
    "Meowio plays the best source for you: pick a title and it starts, and switches to another source on its own if one fails. "
    "Free Live TV, a frosted-glass interface, watch party, collections and trailers."
)

app = {
    "name": "Meowio",
    "bundleIdentifier": "com.meowio.app",
    "developerName": "Layton Brewer",
    "subtitle": subtitle,
    "localizedDescription": description,
    "iconURL": icon,
    "tintColor": "6C5CE7",
    "category": "entertainment",
    "screenshotURLs": [],
    "versions": [
        {
            "version": version,
            "buildVersion": version,
            "date": date,
            "localizedDescription": notes,
            "downloadURL": download,
            "size": size,
            "sha256": digest,
        }
    ],
    # The older single-version fields, for apps that read those.
    "version": version,
    "versionDate": date,
    "versionDescription": notes,
    "downloadURL": download,
    "size": size,
    "appPermissions": {"entitlements": [], "privacy": {}},
}
source = {
    "name": "Meowio",
    "identifier": "com.meowio.source",
    "subtitle": subtitle,
    "description": "Updates for Meowio on iPhone and iPad.",
    "iconURL": icon,
    "website": base,
    "tintColor": "6C5CE7",
    "sourceURL": f"{base}/releases/latest/download/altstore.json",
    "apps": [app],
    "news": [],
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(source, f, indent=2)
print("AltStore source for", tag, "size", size)
