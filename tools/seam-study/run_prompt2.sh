#!/usr/bin/env bash
# #122 prompt-window sweep, part 2 — at fragment sizes where the window BINDS.
#
# The prompt is only the PREVIOUS fragment's transcript (~90 chars per 10 s of
# speech), so at the default 10 s fragments no window >= 96 ever truncates
# anything (the 10 s sweep is uninformative / the "800 was too long" premise is
# void). The window only binds when the previous fragment's transcript exceeds
# it: ~190 chars at 21 s, ~550 at 60 s. This sweeps there, on a 300 s sample
# (ogg, the format with more drift), fragment 21 s / 60 s, prompt 0/96/320/800.
set -u

DEV=${DEV:-f6de166c}
PKG=com.georgernstgraf.polishedrecognition
D=/sdcard/Android/data/$PKG/files
BASE=$D/samples
OUT=${OUT:-/tmp/opencode/seam-run/results}
TIMEOUT=${TIMEOUT:-900}
mkdir -p "$OUT"

run_one() {  # id file prompt frag format
  local id=$1 file=$2 prompt=$3 frag=$4 format=$5
  [[ -f "$OUT/$id/harness.json" ]] && { echo "skip $id"; return; }
  local rdir="$OUT/$id"; mkdir -p "$rdir"
  adb -s "$DEV" shell rm -rf "$D/harness" </dev/null >/dev/null 2>&1
  adb -s "$DEV" shell am start -n "$PKG/.harness.HarnessActivity" \
     --es file "$file" --es label "$id" --es mode raw --es format "$format" --es policy all \
     --ef fragmentSeconds "$frag" --ef searchSeconds 2 --ef preRollSeconds 1 \
     --ei promptChars "$prompt" --ez reference true </dev/null >/dev/null 2>&1
  local waited=0
  while [ "$waited" -lt "$TIMEOUT" ]; do
    if adb -s "$DEV" shell "test -f $D/harness/latest.json" </dev/null 2>/dev/null; then break; fi
    sleep 2; waited=$((waited+2))
  done
  adb -s "$DEV" shell cat "$D/harness/latest.json" > "$rdir/harness.json" 2>/dev/null </dev/null
  local f
  for f in stt-fragment stt-shadow stt-text prepare stt-trim stt-upload stt-latency; do
    adb -s "$DEV" shell cat "$D/logs/$f.json" > "$rdir/$f.json" 2>/dev/null </dev/null
  done
  local v="FAILED"; grep -q '"success": true' "$rdir/harness.json" 2>/dev/null && v="ok"
  echo "[$(date +%H:%M:%S)] $id $v (${waited}s)"
}

for frag in 21 60; do
  for p in 0 96 320 800; do
    run_one "W${frag}_P${p}_en_monologue_male__300__ogg" \
            "$BASE/en_monologue_male/en_monologue_male_300s.wav" "$p" "$frag" ogg
  done
done
echo "PROMPT-2 SWEEP DONE"
