#!/usr/bin/env bash
# Pull the app's rotating JSON logs (llm-prompt / stt-response / llm-response)
# from a connected device into ./tmp/logs/ for local inspection.
set -euo pipefail

SRC="/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/tmp"

mkdir -p "$DEST"
echo "Pulling $SRC -> $DEST/"
adb pull "$SRC" "$DEST/"

if [ -d "$DEST/logs" ]; then
    echo "Done:"
    ls -la "$DEST/logs"
fi
