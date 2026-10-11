#!/usr/bin/env bash
# #122 seam-study runner. Drives HarnessActivity over adb, one run at a time,
# and pulls each run's artifacts into results/<id>/.
# Usage: [MODE=tier1] [OUT=...] [FRAG=10] [SEARCH=2] [PREROLL=1] bash run_matrix.sh
set -u

DEV=${DEV:-f6de166c}
PKG=com.georgernstgraf.polishedrecognition
D=/sdcard/Android/data/$PKG/files
BASE=$D/samples
OUT=${OUT:-/tmp/opencode/seam-run/results}
FRAG=${FRAG:-10}; SEARCH=${SEARCH:-2}; PREROLL=${PREROLL:-1}; PROMPT=${PROMPT:-96}
TIMEOUT=${TIMEOUT:-900}
MODE=${MODE:-tier1}
mkdir -p "$OUT"

pull_logs() {
  local rdir=$1
  local f
  for f in stt-fragment stt-shadow stt-text prepare stt-trim stt-upload stt-latency; do
    adb -s "$DEV" shell cat "$D/logs/$f.json" > "$rdir/$f.json" 2>/dev/null </dev/null
  done
}

run_one() {
  local id=$1 file=$2 mode=$3 format=$4 policy=$5 frag=$6 search=$7 preroll=$8 prompt=$9 ref=${10}
  [[ -f "$OUT/$id/harness.json" ]] && { echo "skip $id (already done)"; return; }
  local rdir="$OUT/$id"; mkdir -p "$rdir"
  adb -s "$DEV" shell rm -rf "$D/harness" </dev/null >/dev/null 2>&1
  adb -s "$DEV" shell am start -n "$PKG/.harness.HarnessActivity" \
     --es file "$file" --es label "$id" --es mode "$mode" --es format "$format" --es policy "$policy" \
     --ef fragmentSeconds "$frag" --ef searchSeconds "$search" --ef preRollSeconds "$preroll" \
     --ei promptChars "$prompt" --ez reference "$ref" </dev/null >/dev/null 2>&1
  local waited=0
  while [ "$waited" -lt "$TIMEOUT" ]; do
    if adb -s "$DEV" shell "test -f $D/harness/latest.json" </dev/null 2>/dev/null; then break; fi
    sleep 2; waited=$((waited+2))
  done
  adb -s "$DEV" shell cat "$D/harness/latest.json" > "$rdir/harness.json" 2>/dev/null </dev/null
  pull_logs "$rdir"
  local verdict="FAILED"
  grep -q '"success": true' "$rdir/harness.json" 2>/dev/null && verdict="ok"
  echo "[$(date +%H:%M:%S)] $id $verdict (${waited}s)"
}

CLASSES=(de_monologue_male de_interview en_monologue_male en_monologue_female en_interview panel_multispeaker phone_radio)
DURS=(30 60 150 300)
FORMATS=(ogg wav)

if [[ "$MODE" == "tier1" ]]; then
  for c in "${CLASSES[@]}"; do
    for d in "${DURS[@]}"; do
      for f in "${FORMATS[@]}"; do
        run_one "${c}__${d}__${f}" "$BASE/$c/${c}_${d}s.wav" raw "$f" forced "$FRAG" "$SEARCH" "$PREROLL" "$PROMPT" true
      done
    done
  done
  for c in en_monologue_male en_monologue_female; do
    for f in "${FORMATS[@]}"; do
      run_one "${c}__600__${f}" "$BASE/$c/${c}_600s.wav" raw "$f" forced "$FRAG" "$SEARCH" "$PREROLL" "$PROMPT" true
    done
  done
  run_one "de_interview__10__wav" "$BASE/de_interview/de_interview_10s.wav" raw wav forced 10 2 1 96 true
  run_one "en_monologue_male__10__wav" "$BASE/en_monologue_male/en_monologue_male_10s.wav" raw wav forced 10 2 1 96 true
fi

if [[ "$MODE" == "tier2before" ]]; then
  # "before" arm: pre-#122 behaviour (pre-roll+prompt at EVERY seam)
  for c in en_monologue_male de_interview; do
    for d in "${DURS[@]}"; do
      for f in "${FORMATS[@]}"; do
        run_one "B_${c}__${d}__${f}" "$BASE/$c/${c}_${d}s.wav" raw "$f" all "$FRAG" "$SEARCH" "$PREROLL" "$PROMPT" true
      done
    done
  done
fi

if [[ "$MODE" == "tier2size" ]]; then
  # fragment-size sweep 7 s / 21 s (both formats), forced-only
  for c in en_monologue_male de_interview; do
    for d in 60 300; do
      for f in "${FORMATS[@]}"; do
        for s in 7 21; do
          run_one "S${s}_${c}__${d}__${f}" "$BASE/$c/${c}_${d}s.wav" raw "$f" forced "$s" "$SEARCH" "$PREROLL" "$PROMPT" true
        done
      done
    done
  done
fi

if [[ "$MODE" == "tier2policy" ]]; then
  # mechanism isolation on a continuous (monologue) + pause-rich (interview)
  # class. Cuts held constant (search 2 s); only the seam mechanism varies.
  for c in en_monologue_male de_interview; do
    for f in "${FORMATS[@]}"; do
      run_one "P_all_${c}__60__${f}"     "$BASE/$c/${c}_60s.wav" raw "$f" all    "$FRAG" 2 "$PREROLL" "$PROMPT" true
      run_one "P_forced_${c}__60__${f}"  "$BASE/$c/${c}_60s.wav" raw "$f" forced "$FRAG" 2 "$PREROLL" "$PROMPT" true
      run_one "P_off_${c}__60__${f}"     "$BASE/$c/${c}_60s.wav" raw "$f" off    "$FRAG" 2 "$PREROLL" "$PROMPT" true
      run_one "P_preroll_${c}__60__${f}" "$BASE/$c/${c}_60s.wav" raw "$f" all    "$FRAG" 2 "$PREROLL" 0         true
      run_one "P_prompt_${c}__60__${f}"  "$BASE/$c/${c}_60s.wav" raw "$f" all    "$FRAG" 2 0          "$PROMPT" true
    done
  done
fi

if [[ "$MODE" == "tier2preroll" ]]; then
  # pre-roll size sweep on a forced-cut-heavy class (long prompt off: promptChars=0)
  for f in "${FORMATS[@]}"; do
    for p in 0.5 2; do
      run_one "R${p}_en_monologue_male__60__${f}" "$BASE/en_monologue_male/en_monologue_male_60s.wav" raw "$f" forced "$FRAG" "$SEARCH" "$p" 0 true
    done
  done
fi

echo "MATRIX $MODE DONE"
