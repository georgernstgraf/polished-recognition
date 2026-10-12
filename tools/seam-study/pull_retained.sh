#!/usr/bin/env bash
# Pull retained audio-evidence sessions (#127: files/audio/<sessionId>/) off a
# connected device into samples/retained/ (git-ignored, see SAMPLES.md).
#
# Usage: tools/seam-study/pull_retained.sh [serial] [session-id ...]
#   serial     adb device serial (default: R7SRB06XF5K, the Samsung A03)
#   session-id one or more specific sessions; omit to pull ALL retained ones
#
# adb pull is BLOCKED on the app's external dir on these devices — the pull is
# tar-over-exec-out. exec-out is binary-safe; adb shell would allocate a pty
# and CRLF-mangle the tar stream. Every adb call redirects < /dev/null so it
# cannot eat a caller loop's stdin.
set -euo pipefail

SERIAL="${1:-R7SRB06XF5K}"
PKG="com.georgernstgraf.polishedrecognition"
BASE="/sdcard/Android/data/$PKG/files"
DEST="$(cd "$(dirname "$0")/../.." && pwd)/samples/retained"

if [ $# -gt 0 ]; then shift; fi

list_sessions() {
  # The remote ls exits non-zero when files/audio/ is absent (expected on
  # builds predating #127) — tolerate it, the empty result is the signal.
  adb -s "$SERIAL" shell "ls $BASE/audio/ 2>/dev/null" < /dev/null \
    | tr -d '\r' | sed '/^$/d' || true
}

pull_one() {
  local sid="$1"
  case "$sid" in ''|*/*|*' '*) echo "invalid session id: '$sid'" >&2; return 1;; esac
  echo "pulling $sid ..."
  adb -s "$SERIAL" exec-out "cd $BASE/audio && tar cf - '$sid'" < /dev/null \
    | tar xf - -C "$DEST"
  [ -d "$DEST/$sid" ] || { echo "  pull failed: $DEST/$sid was not created" >&2; return 1; }
  echo "  -> $DEST/$sid"
}

mkdir -p "$DEST"

if [ $# -gt 0 ]; then
  for sid in "$@"; do pull_one "$sid"; done
else
  sessions="$(list_sessions)"
  if [ -z "$sessions" ]; then
    echo "no retained sessions on device $SERIAL ($BASE/audio absent or empty)"
    echo "(expected state on release builds predating #127 — audio evidence is OFF there)"
    exit 0
  fi
  echo "retained sessions on $SERIAL:"
  echo "$sessions" | sed 's/^/  /'
  for sid in $sessions; do pull_one "$sid"; done
fi
