#!/usr/bin/env python3
"""
Query Google Play Console for app status.

Usage:
  python3 scripts/query-play-console.py

Environment variables:
  PLAY_SERVICE_ACCOUNT_JSON_PATH
      Path to the Google Play service account JSON key file.
      Default: ~/svn/georg/private/iron-country-322716-cbf4e476a3b0.json

Queries:
  - App existence for com.georgernstgraf.polishedrecognition
  - Available tracks (production, beta, alpha, internal)
  - Latest release info per track
"""

import json, os, sys
from pathlib import Path

from google.oauth2 import service_account
from googleapiclient.discovery import build

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]
PACKAGE = "com.georgernstgraf.polishedrecognition"


def get_credentials():
    path = os.environ.get(
        "PLAY_SERVICE_ACCOUNT_JSON_PATH",
        str(Path.home() / "svn" / "georg" / "private"
            / "iron-country-322716-cbf4e476a3b0.json"),
    )
    if not os.path.exists(path):
        print(f"Error: service account file not found: {path}", file=sys.stderr)
        print("Set PLAY_SERVICE_ACCOUNT_JSON_PATH to the correct path.", file=sys.stderr)
        sys.exit(1)
    return service_account.Credentials.from_service_account_file(path, scopes=SCOPES)


def query_play(service):
    print(f"Package: {PACKAGE}\n")

    # Insert an edit to query tracks
    try:
        edit = service.edits().insert(packageName=PACKAGE, body={}).execute()
        edit_id = edit["id"]
    except Exception as e:
        print(f"Error: cannot create edit — app may not exist: {e}")
        if "not found" in str(e).lower() or "404" in str(e):
            print("The app does not exist yet in Play Console, or the service account lacks access.")
        sys.exit(1)

    try:
        tracks_resp = service.edits().tracks().list(
            packageName=PACKAGE, editId=edit_id
        ).execute()
    except Exception as e:
        print(f"Error querying tracks: {e}")
        service.edits().delete(packageName=PACKAGE, editId=edit_id).execute()
        sys.exit(1)

    print("Tracks:")
    for t in tracks_resp.get("tracks", []):
        track = t["track"]
        releases = t.get("releases", [])
        print(f"  {track}: {len(releases)} release(s)")
        for r in releases:
            name = r.get("name", "?")
            status = r.get("status", "?")
            vcs = r.get("versionCodes", [])
            inapp = r.get("inAppUpdatePriority", "?")
            print(f"    {name}: status={status} versionCodes={vcs} inAppUpdatePriority={inapp}")
            user_facing = r.get("userFraction", None)
            if user_facing is not None:
                print(f"      staged rollout: {float(user_facing) * 100:.1f}%")
            release_notes = r.get("releaseNotes", [])
            for rn in release_notes:
                lang = rn.get("language", "?")
                text = rn.get("text", "")
                print(f"      releaseNotes({lang}): {text[:120]}")

    service.edits().delete(packageName=PACKAGE, editId=edit_id).execute()
    print(f"\n{len(tracks_resp.get('tracks', []))} track(s) found")


def main():
    creds = get_credentials()
    service = build("androidpublisher", "v3", credentials=creds)
    query_play(service)


if __name__ == "__main__":
    main()
