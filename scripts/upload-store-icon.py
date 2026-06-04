#!/usr/bin/env python3
"""
Upload distribution/play-store-icon.png as the store listing icon in Play Console.

Usage:
  python3 scripts/upload-store-icon.py

Environment variables:
  PLAY_SERVICE_ACCOUNT_JSON_PATH
      Path to the Google Play service account JSON key file.
      Default: ~/svn/georg/private/iron-country-322716-cbf4e476a3b0.json
"""

import os, sys
from pathlib import Path

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]
PACKAGE = "com.georgernstgraf.polishedrecognition"
ICON_PATH = "distribution/play-store-icon.png"


def get_credentials():
    path = os.environ.get(
        "PLAY_SERVICE_ACCOUNT_JSON_PATH",
        str(Path.home() / "svn" / "georg" / "private"
            / "iron-country-322716-cbf4e476a3b0.json"),
    )
    return service_account.Credentials.from_service_account_file(path, scopes=SCOPES)


def main():
    creds = get_credentials()
    service = build("androidpublisher", "v3", credentials=creds)

    # Create edit
    edit = service.edits().insert(packageName=PACKAGE, body={}).execute()
    edit_id = edit["id"]
    print(f"Edit ID: {edit_id}")

    # Upload icon
    media = MediaFileUpload(ICON_PATH, mimetype="image/png", resumable=True)
    result = service.edits().images().upload(
        packageName=PACKAGE, editId=edit_id,
        language="en-GB", imageType="icon",
        media_body=media
    ).execute()
    print(f"Uploaded icon: {result.get('images', [{}])[0].get('id', '?')}")

    # Validate and commit
    service.edits().validate(packageName=PACKAGE, editId=edit_id).execute()
    commit = service.edits().commit(packageName=PACKAGE, editId=edit_id).execute()
    print(f"Committed edit: {commit['id']}")
    print("Done. Play Store listing icon updated.")


if __name__ == "__main__":
    main()
