#!/usr/bin/env bash
# #122 prompt-size sweep — isolates the Whisper prompt length (the pre-roll is
# held at 1 s, policy ALL so the prompt is applied at EVERY seam, fragment 10 s).
# Points: off / 32 / 96 (new default) / 320 / 800 (round-1 default) chars.
# 2 classes x 2 formats x 5 lengths x 60 s = 20 runs.
set -u

DEV=${DEV:-f6de166c}
PKG=com.georgernstgraf.polishedrecognition
D=/sdcard/Android/data/$PKG/files
BASE=$D/samples
OUT=${OUT:-/tmp/opencode/seam-run/results}
TIMEOUT=${TIMEOUT:-900}
mkdir -p "$OUT"

run_one() {
  local id=$1 file=$2 prompt=$3 format=$4
  [[ -f "$OUT/$id/harness.json" ]] && { echo "skip $id"; return; }
  local rdir="$OUT/$id"; mkdir -p "$rdir"
  adb -s "$DEV" shell rm -rf "$D/harness" </dev/null >/dev/null 2>&1
  adb -s "$DEV" shell am start -n "$PKG/.harness.HarnessActivity" \
     --es file "$file" --es label "$id" --es mode raw --es format "$format" --es policy all \
     --ef fragmentSeconds 10 --ef searchSeconds 2 --ef preRollSeconds 1 \
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

for c in en_monologue_male de_interview; do
  for f in ogg wav; do
    for p in 0 32 96 320 800; do
      run_one "Q${p}_${c}__60__${f}" "$BASE/$c/${c}_60s.wav" "$p" "$f"
    done
  done
done

# amplification sub-sweep: same prompt extremes on a LONGER sample, where
# derailments/loops actually occur, to test whether a long prompt propagates
# a bad tail further (7 s fragments -> ~43 seams on 300 s).
for p in 0 96 800; do
  run_one "L${p}_en_monologue_male__300__ogg" "$BASE/en_monologue_male/en_monologue_male_300s.wav" "$p" ogg
done
echo "PROMPT SWEEP DONE"
