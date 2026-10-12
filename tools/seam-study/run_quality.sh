#!/usr/bin/env bash
# #129 quality-sweep runner — staged, resumable, STRICTLY-SERIAL coordinate
# descent over the #126/#128 silence-quality knobs, plus the final 3x3
# factorial over axisK x qualityMin on the full corpus.
#
# It reuses run_matrix.sh's adb drive (one `am start` per arm, wait for
# files/harness/latest.json, cat-pull the evidence streams) and sweeps the
# #129 harness knobs recorded in every harness.json:
#   axisK / axisC / qualityMin (--ef), marginKMs (--ei), searchSpan (--es).
#
# Why staged, not a cartesian product: the STT server (gregor behind
# https://htl.graf.priv.at/v1/, large-v3) is C=1 — a full matrix would run
# for days. Coordinate descent holds everything but one knob at the current
# best value (stage 1 starts from the PROVISIONAL production defaults) and
# sweeps one knob per stage on a representative subset
# (quality_subset.txt); the final --full-factorial then crosses the two
# expected "bringers" (axisK x qualityMin) on the FULL corpus.
#
# Stages:
#   1  axisK       (fricative separation)      5 levels x subset
#   2  qualityMin  (stitch threshold)          5 levels x subset, axisK=stage-1 winner
#   3  axisC       (whisper/quiet-room anchor) 5 levels x subset, k/Q winners held
#   4  marginKMs   (cut edge margin)           3 levels x subset, k/Q/c winners held
#   5  searchSpan  (lateHalf vs fullWindow)    2 levels x subset, all winners held
#   final  --full-factorial: 3x3 axisK x qualityMin (winner +- one level)
#        on ALL corpus files from corpus/samples.json.
#
# Per-arm scoring is automated (analyze.py): primary = the #122 loop-masked
# err/100w against the shadow reference; tie-break = seam-duplication count
# (per-seam SeamOverlap on adjacent fragment transcripts). Winners land in
# $OUT/winners.txt after each stage; later stages read them back.
#
# Resume: an arm is DONE iff $OUT/<arm>/harness.json exists (run_matrix
# semantics) — re-invocations skip completed arms, so a crash or server
# outage never redoes work. $MAX_FAILS consecutive arms without a
# harness.json abort the run (server outage signature) — fix and re-invoke.
#
# Evidence cadence (rotation!): the app's prepare stream keeps ONE record
# per file, rotated 9 deep, and the harness path bursts ALL prepare records
# before the first upload — so every arm POLLS all 10 rotation slots during
# the run and merge_prepare.py dedupes them into <arm>/prepare_all.json
# (run_matrix's single post-run pull of prepare.json alone captures ONE
# record). stt-fragment.json is a full-array rewrite — one end pull gets
# every fragment.
#
# Usage:
#   bash tools/seam-study/run_quality.sh                 # stages 1..5
#   bash tools/seam-study/run_quality.sh --probe         # 1 end-to-end arm
#   bash tools/seam-study/run_quality.sh --stage 3       # one stage only
#   bash tools/seam-study/run_quality.sh --full-factorial
#   bash tools/seam-study/run_quality.sh --dry-run       # plan only, no adb
#   bash tools/seam-study/run_quality.sh --subset FILE   # subset override
#
# Knob defaults = the PROVISIONAL production values (#126/#128): axisK 0.65,
# axisC 100.0, qualityMin 10.0, marginKMs 150, searchSpan lateHalf, policy
# qgate, fragment 10 s. SEARCH defaults to FRAG so the harness cut geometry
# matches production's spans [S, 2S] (search = max(2 s, S) with S = 10 s;
# run_matrix's SEARCH=2 was the #122 mechanism-isolation geometry). Level
# lists are overridable: AXIS_K_LEVELS="0.4 0.5 0.6 0.7" etc. (comma/space
# separated). An explicitly exported knob value (e.g. AXIS_K=0.55) beats
# winners.txt, which beats the provisional default — single-stage re-runs
# pin the held knobs that way.
#
# Everything lands in $OUT (default /tmp/opencode/seam-quality/results —
# git-ignored territory by design, the committed snapshot is
# quality_results.csv, regenerated from $OUT after every arm). If a stage
# is re-run after a winner CHANGED, move $OUT aside first: the summary
# would otherwise mix two held-knob contexts (analyze.py warns when it
# sees more than one context per stage).
set -u
export LC_ALL=C

DEV=${DEV:-R7SRB06XF5K}
PKG=com.georgernstgraf.polishedrecognition
D=/sdcard/Android/data/$PKG/files
BASE=$D/samples
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-/tmp/opencode/seam-quality/results}
SUBSET=${SUBSET:-$HERE/quality_subset.txt}
CSV=${CSV:-$HERE/quality_results.csv}
MANIFEST=$HERE/corpus/samples.json
SERVER=${SERVER:-https://htl.graf.priv.at/v1}
TIMEOUT=${TIMEOUT:-900}
POLL=${POLL:-2}
MAX_FAILS=${MAX_FAILS:-3}
CHECK_SERVER=${CHECK_SERVER:-1}

# ---- knobs: provisional defaults, env overrides captured BEFORE use ----
DEF_K=0.65; DEF_C=100.0; DEF_Q=10.0; DEF_M=150; DEF_SPAN=lateHalf
AXIS_K_SET=0; [ -n "${AXIS_K:-}" ] && AXIS_K_SET=1
AXIS_C_SET=0; [ -n "${AXIS_C:-}" ] && AXIS_C_SET=1
QUALITY_MIN_SET=0; [ -n "${QUALITY_MIN:-}" ] && QUALITY_MIN_SET=1
MARGIN_K_MS_SET=0; [ -n "${MARGIN_K_MS:-}" ] && MARGIN_K_MS_SET=1
SEARCH_SPAN_SET=0; [ -n "${SEARCH_SPAN:-}" ] && SEARCH_SPAN_SET=1
AXIS_K=$(printf '%.2f' "${AXIS_K:-$DEF_K}")
AXIS_C=$(printf '%.1f' "${AXIS_C:-$DEF_C}")
QUALITY_MIN=$(printf '%.1f' "${QUALITY_MIN:-$DEF_Q}")
MARGIN_K_MS=${MARGIN_K_MS:-$DEF_M}
SEARCH_SPAN=${SEARCH_SPAN:-$DEF_SPAN}
POLICY=${POLICY:-qgate}
FRAG=${FRAG:-10}; SEARCH=${SEARCH:-$FRAG}; PREROLL=${PREROLL:-1}; PROMPT=${PROMPT:-96}
FORMAT=${FORMAT:-wav}

# ---- stage level lists (env-overridable, comma/space separated) --------
if [ -n "${AXIS_K_LEVELS:-}" ]; then IFS=', ' read -r -a AXIS_K_LEVELS <<< "$AXIS_K_LEVELS"
else AXIS_K_LEVELS=(0.45 0.55 0.65 0.75 0.85); fi
if [ -n "${QUALITY_MIN_LEVELS:-}" ]; then IFS=', ' read -r -a QUALITY_MIN_LEVELS <<< "$QUALITY_MIN_LEVELS"
else QUALITY_MIN_LEVELS=(2.0 5.0 10.0 20.0 40.0); fi   # #126 brackets: whisper Q~2.3 .. solid pause Q~40
if [ -n "${AXIS_C_LEVELS:-}" ]; then IFS=', ' read -r -a AXIS_C_LEVELS <<< "$AXIS_C_LEVELS"
else AXIS_C_LEVELS=(25.0 50.0 100.0 200.0 400.0); fi   # log-spaced around 100
if [ -n "${MARGIN_LEVELS:-}" ]; then IFS=', ' read -r -a MARGIN_LEVELS <<< "$MARGIN_LEVELS"
else MARGIN_LEVELS=(75 150 300); fi
if [ -n "${SPAN_LEVELS:-}" ]; then IFS=', ' read -r -a SPAN_LEVELS <<< "$SPAN_LEVELS"
else SPAN_LEVELS=(lateHalf fullWindow); fi

# ---------------------------------------------------------------- args ----
MODE_RUN=stages; STAGE_NUM=""; DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --stage) [ $# -ge 2 ] || { echo "--stage needs a number (1..5)" >&2; exit 2; }
             STAGE_NUM=$2; MODE_RUN=stage; shift ;;
    --full-factorial) MODE_RUN=factorial ;;
    --probe) MODE_RUN=probe ;;
    --subset) [ $# -ge 2 ] || { echo "--subset needs a file" >&2; exit 2; }
              SUBSET=$2; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
[ "$MODE_RUN" = stage ] && { [[ "$STAGE_NUM" =~ ^[1-5]$ ]] || { echo "--stage must be 1..5" >&2; exit 2; }; }
[ -f "$SUBSET" ] || { echo "subset file not found: $SUBSET" >&2; exit 2; }

# ------------------------------------------------------------- helpers ----
subset_samples() { grep -vE '^[[:space:]]*(#|$)' "$SUBSET" | sed 's/[[:space:]]*$//'; }
manifest_files() {
  python3 -c 'import json,sys
for e in json.load(open(sys.argv[1])): print(e["file"])' "$MANIFEST"
}
winner_of() { [ -f "$OUT/winners.txt" ] && awk -F= -v k="$1" '$1==k{print $2}' "$OUT/winners.txt" | tail -n 1; }
resolve() { # $1=set?  $2=env value  $3=knob name  $4=fmt  $5=default
  if [ "$1" = 1 ]; then printf "$4" "$2"; return; fi
  local w; w=$(winner_of "$3"); if [ -n "$w" ]; then printf "$4" "$w"; else printf "$4" "$5"; fi
}

pull_logs() { # run_matrix's evidence pull (local loop var! #122 pitfall)
  local rdir=$1 f
  for f in stt-fragment stt-shadow stt-text prepare stt-trim stt-upload stt-latency; do
    adb -s "$DEV" shell cat "$D/logs/$f.json" > "$rdir/$f.json" 2>/dev/null </dev/null
  done
}

poll_prepare() { # one adb call dumps all 10 rotation slots -> merge
  local rdir=$1
  local remote="for f in prepare prepare_1 prepare_2 prepare_3 prepare_4 prepare_5 prepare_6 prepare_7 prepare_8 prepare_9; do echo \"###SLOT \$f\"; cat $D/logs/\$f.json 2>/dev/null; echo; done"
  adb -s "$DEV" shell "$remote" </dev/null 2>/dev/null \
    | tr -d '\r' | python3 "$HERE/merge_prepare.py" "$rdir/prepare_all.json" >/dev/null 2>&1
}

arm_prefix() {
  case $1 in factorial) echo fx ;; probe) echo probe ;; *) echo "s$1" ;; esac
}

# ------------------------------------------------------------- planning ---
plan_stage() { # $1 = stage number -> "sample|k|c|q|m|span|stage|swept|level" lines
  local n=$1 knob levels
  local hk hc hq hm hspan
  hk=$(resolve "$AXIS_K_SET" "$AXIS_K" axisK '%.2f' "$DEF_K")
  hc=$(resolve "$AXIS_C_SET" "$AXIS_C" axisC '%.1f' "$DEF_C")
  hq=$(resolve "$QUALITY_MIN_SET" "$QUALITY_MIN" qualityMin '%.1f' "$DEF_Q")
  hm=$(resolve "$MARGIN_K_MS_SET" "$MARGIN_K_MS" marginKMs '%d' "$DEF_M")
  hspan=$(resolve "$SEARCH_SPAN_SET" "$SEARCH_SPAN" searchSpan '%s' "$DEF_SPAN")
  case $n in
    1) knob=axisK;      levels=("${AXIS_K_LEVELS[@]}") ;;
    2) knob=qualityMin; levels=("${QUALITY_MIN_LEVELS[@]}") ;;
    3) knob=axisC;      levels=("${AXIS_C_LEVELS[@]}") ;;
    4) knob=marginKMs;  levels=("${MARGIN_LEVELS[@]}") ;;
    5) knob=searchSpan; levels=("${SPAN_LEVELS[@]}") ;;
    *) return 1 ;;
  esac
  local lvl sample k c q m span
  while IFS= read -r sample; do
    for lvl in "${levels[@]}"; do
      k=$hk; c=$hc; q=$hq; m=$hm; span=$hspan
      case $knob in
        axisK)      k=$(printf '%.2f' "$lvl") ;;
        qualityMin) q=$(printf '%.1f' "$lvl") ;;
        axisC)      c=$(printf '%.1f' "$lvl") ;;
        marginKMs)  m=$lvl ;;
        searchSpan) span=$lvl ;;
      esac
      printf '%s|%s|%s|%s|%s|%s|%s|%s|%s\n' "$sample" "$k" "$c" "$q" "$m" "$span" "$n" "$knob" "$lvl"
    done
  done < <(subset_samples)
}

factorial_levels() { # $1=fmt  $2=winner  $3..=level list -> 3 distinct levels
  local fmt=$1 w=$2; shift 2
  local list=() x
  for x in "$@"; do list+=("$(printf "$fmt" "$x")"); done
  w=$(printf "$fmt" "$w")
  local n=${#list[@]} i=-1 j start
  [ "$n" -lt 3 ] && { printf '%s\n' "${list[@]}"; return 0; }
  for j in "${!list[@]}"; do [ "${list[j]}" = "$w" ] && i=$j; done
  if [ "$i" -lt 0 ]; then start=$(( (n - 3) / 2 ))       # unknown winner: middle three
  elif [ "$i" -eq 0 ]; then start=0                       # winner at the low edge
  elif [ "$i" -ge $((n - 1)) ]; then start=$((n - 3))     # winner at the high edge
  else start=$((i - 1)); fi                               # winner +- one level
  printf '%s\n%s\n%s\n' "${list[start]}" "${list[start + 1]}" "${list[start + 2]}"
}

plan_factorial() {
  local hk hc hq hm hspan ks qs sample k q
  hk=$(resolve "$AXIS_K_SET" "$AXIS_K" axisK '%.2f' "$DEF_K")
  hc=$(resolve "$AXIS_C_SET" "$AXIS_C" axisC '%.1f' "$DEF_C")
  hq=$(resolve "$QUALITY_MIN_SET" "$QUALITY_MIN" qualityMin '%.1f' "$DEF_Q")
  hm=$(resolve "$MARGIN_K_MS_SET" "$MARGIN_K_MS" marginKMs '%d' "$DEF_M")
  hspan=$(resolve "$SEARCH_SPAN_SET" "$SEARCH_SPAN" searchSpan '%s' "$DEF_SPAN")
  ks=$(factorial_levels '%.2f' "$hk" "${AXIS_K_LEVELS[@]}")
  qs=$(factorial_levels '%.1f' "$hq" "${QUALITY_MIN_LEVELS[@]}")
  while IFS= read -r k; do
    while IFS= read -r q; do
      while IFS= read -r sample; do
        printf '%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
          "$sample" "$k" "$hc" "$q" "$hm" "$hspan" factorial "axisK+qualityMin" "k${k}_Q${q}"
      done < <(manifest_files)
    done <<< "$qs"
  done <<< "$ks"
}

# ------------------------------------------------------------- dry run ----
if [ "$DRY_RUN" = 1 ]; then
  echo "== run_quality.sh dry run — plan only, NO device/server contact =="
  echo "device: $DEV   out: $OUT   csv: $CSV   subset: $SUBSET"
  echo "knobs (provisional defaults): axisK=$DEF_K axisC=$DEF_C qualityMin=$DEF_Q marginKMs=$DEF_M searchSpan=$DEF_SPAN policy=$POLICY"
  echo "geometry: fragment=${FRAG}s search=${SEARCH}s (spans [S,2S]) preRoll=${PREROLL}s prompt=$PROMPT format=$FORMAT timeout=${TIMEOUT}s poll=${POLL}s"
  echo
  mapfile -t SUB < <(subset_samples)
  echo "subset samples (${#SUB[@]}):"
  printf '  %s\n' "${SUB[@]}"
  echo
  case $MODE_RUN in
    probe)
      echo "probe: 1 arm — ${SUB[0]} at the provisional defaults (end-to-end server check)"
      ;;
    stage|stages)
      for n in 1 2 3 4 5; do
        [ "$MODE_RUN" = stage ] && [ "$n" != "$STAGE_NUM" ] && continue
        local_hk=$(resolve "$AXIS_K_SET" "$AXIS_K" axisK '%.2f' "$DEF_K")
        local_hc=$(resolve "$AXIS_C_SET" "$AXIS_C" axisC '%.1f' "$DEF_C")
        local_hq=$(resolve "$QUALITY_MIN_SET" "$QUALITY_MIN" qualityMin '%.1f' "$DEF_Q")
        local_hm=$(resolve "$MARGIN_K_MS_SET" "$MARGIN_K_MS" marginKMs '%d' "$DEF_M")
        local_hspan=$(resolve "$SEARCH_SPAN_SET" "$SEARCH_SPAN" searchSpan '%s' "$DEF_SPAN")
        case $n in
          1) echo "stage 1 — sweep axisK, levels: ${AXIS_K_LEVELS[*]} (others at provisional defaults)";;
          2) echo "stage 2 — sweep qualityMin, levels: ${QUALITY_MIN_LEVELS[*]}, held axisK=$local_hk";;
          3) echo "stage 3 — sweep axisC, levels: ${AXIS_C_LEVELS[*]}, held k=$local_hk Q=$local_hq";;
          4) echo "stage 4 — sweep marginKMs, levels: ${MARGIN_LEVELS[*]}, held k=$local_hk Q=$local_hq c=$local_hc";;
          5) echo "stage 5 — sweep searchSpan, levels: ${SPAN_LEVELS[*]}, held k=$local_hk Q=$local_hq c=$local_hc m=$local_hm";;
        esac
        echo "  arms: $(plan_stage "$n" | wc -l)"
      done
      ;;
    factorial)
      ks=$(factorial_levels '%.2f' "$(resolve "$AXIS_K_SET" "$AXIS_K" axisK '%.2f' "$DEF_K")" "${AXIS_K_LEVELS[@]}")
      qs=$(factorial_levels '%.1f' "$(resolve "$QUALITY_MIN_SET" "$QUALITY_MIN" qualityMin '%.1f' "$DEF_Q")" "${QUALITY_MIN_LEVELS[@]}")
      echo "full factorial — axisK x qualityMin on the FULL corpus:"
      echo "  axisK levels:     $(echo "$ks" | tr '\n' ' ')"
      echo "  qualityMin levels: $(echo "$qs" | tr '\n' ' ')"
      echo "  corpus files: $(manifest_files | wc -l) (from $MANIFEST)"
      echo "  arms: $(plan_factorial | wc -l)"
      ;;
  esac
  echo
  echo "held-knob resolution: env override > \$OUT/winners.txt > provisional default"
  exit 0
fi

# ------------------------------------------------------------- execution --
run_arm() { # $1 sample $2 k $3 c $4 q $5 m $6 span $7 stage $8 swept $9 level
  local sample=$1 k=$2 c=$3 q=$4 m=$5 span=$6 stage=$7 swept=$8 level=$9
  local stem arm rdir
  stem=$(basename "$sample" .wav)
  arm="$(arm_prefix "$stage")_${stem}__k${k}_c${c}_Q${q}_m${m}_${span}"
  rdir="$OUT/$arm"
  if [[ -f "$rdir/harness.json" ]]; then echo "skip $arm (done)"; return 0; fi
  mkdir -p "$rdir"
  cat > "$rdir/arm.json" <<JSON
{"arm": "$arm", "stage": "$stage", "swept": "$swept", "level": "$level", "sample": "$sample", "class": "${sample%%/*}",
 "axisK": $k, "axisC": $c, "qualityMin": $q, "marginKMs": $m, "searchSpan": "$span",
 "policy": "$POLICY", "fragmentSeconds": $FRAG, "searchSeconds": $SEARCH, "preRollSeconds": $PREROLL,
 "promptChars": $PROMPT, "format": "$FORMAT"}
JSON
  adb -s "$DEV" shell rm -rf "$D/harness" </dev/null >/dev/null 2>&1
  adb -s "$DEV" shell "rm -f $D/logs/prepare*.json" </dev/null >/dev/null 2>&1
  adb -s "$DEV" shell am start -n "$PKG/.harness.HarnessActivity" \
    --es file "$BASE/$sample" --es label "$arm" --es mode raw --es format "$FORMAT" --es policy "$POLICY" \
    --ef fragmentSeconds "$FRAG" --ef searchSeconds "$SEARCH" --ef preRollSeconds "$PREROLL" \
    --ef axisK "$k" --ef axisC "$c" --ef qualityMin "$q" \
    --ei promptChars "$PROMPT" --ei marginKMs "$m" --ez reference true \
    </dev/null >/dev/null 2>&1
  local waited=0 verdict=FAILED
  while [ "$waited" -lt "$TIMEOUT" ]; do
    if adb -s "$DEV" shell "test -f $D/harness/latest.json" </dev/null 2>/dev/null; then verdict=ok; break; fi
    poll_prepare "$rdir"
    sleep "$POLL"; waited=$((waited + POLL))
  done
  poll_prepare "$rdir"
  adb -s "$DEV" shell cat "$D/harness/latest.json" > "$rdir/harness.json" 2>/dev/null </dev/null
  if [ ! -s "$rdir/harness.json" ]; then rm -f "$rdir/harness.json"; verdict=FAILED; fi
  pull_logs "$rdir"
  if [ "$verdict" = ok ]; then
    grep -q '"success": true' "$rdir/harness.json" 2>/dev/null || verdict="ok(success=false)"
    : > "$rdir/DONE"
  fi
  echo "[$(date +%H:%M:%S)] $arm $verdict (${waited}s)"
  python3 "$HERE/analyze.py" "$OUT" --quality-csv "$CSV" >/dev/null 2>&1 || true
  [ "$verdict" = FAILED ] && return 1
  return 0
}

run_plan() { # $1 = plan text; strictly serial, consecutive-failure abort
  local plan=$1 fails=0 line rc
  [ -z "$plan" ] && { echo "empty plan"; return 0; }
  echo "=== $(echo "$plan" | wc -l) arms ==="
  while IFS='|' read -r sample k c q m span stage swept level; do
    [ -z "$sample" ] && continue
    if run_arm "$sample" "$k" "$c" "$q" "$m" "$span" "$stage" "$swept" "$level"; then
      fails=0
    else
      fails=$((fails + 1))
      echo "  failed-arm counter: $fails/$MAX_FAILS (no harness.json — server/device trouble?)"
      if [ "$fails" -ge "$MAX_FAILS" ]; then
        echo "ABORT after $MAX_FAILS consecutive failed arms — resume by re-invoking (completed arms are skipped)."
        return 1
      fi
    fi
  done < <(printf '%s\n' "$plan")
  return 0
}

mkdir -p "$OUT"
adb -s "$DEV" get-state </dev/null >/dev/null 2>&1 || { echo "device $DEV not reachable (adb get-state)"; exit 1; }
if [ "$CHECK_SERVER" = 1 ]; then
  # 401/403 prove reachability + a live OpenAI-compatible endpoint: the auth
  # token lives on the PHONE (provider config), not on the host — an
  # auth-gated answer is a healthy answer. Only connection-level failures
  # (timeout, DNS, 5xx) mean unreachable.
  code=$(curl -sS --max-time 20 -o /dev/null -w '%{http_code}' "$SERVER/models" 2>/dev/null) \
    || { echo "STT server unreachable from the host: $SERVER — the phone must reach it too (CHECK_SERVER=0 to skip)"; exit 1; }
  case "$code" in
    2*|401|403) : ;;
    *) echo "STT server answered HTTP $code at $SERVER/models — expected 2xx or an auth gate (401/403)"; exit 1 ;;
  esac
fi

case $MODE_RUN in
  probe)
    sample=$(subset_samples | head -n 1)
    run_arm "$sample" "$AXIS_K" "$AXIS_C" "$QUALITY_MIN" "$MARGIN_K_MS" "$SEARCH_SPAN" probe probe default \
      || { echo "probe FAILED — do not start the sweep"; exit 1; }
    echo "PROBE DONE — inspect $OUT and quality_results.csv, then run the stages."
    ;;
  stage)
    run_plan "$(plan_stage "$STAGE_NUM")" || exit 1
    python3 "$HERE/analyze.py" "$OUT" --stage-winners "$OUT/winners.txt" || true
    echo "stage $STAGE_NUM DONE — winners so far:"; sed 's/^/  /' "$OUT/winners.txt" 2>/dev/null
    ;;
  factorial)
    run_plan "$(plan_factorial)" || exit 1
    python3 "$HERE/analyze.py" "$OUT" --quality-csv "$CSV" >/dev/null 2>&1 || true
    echo "FULL FACTORIAL DONE — csv: $CSV"
    ;;
  stages)
    for n in 1 2 3 4 5; do
      run_plan "$(plan_stage "$n")" || exit 1
      python3 "$HERE/analyze.py" "$OUT" --stage-winners "$OUT/winners.txt" || true
      echo "stage $n DONE — winners so far:"; sed 's/^/  /' "$OUT/winners.txt" 2>/dev/null
    done
    echo "QUALITY DESCENT DONE — winners:"; sed 's/^/  /' "$OUT/winners.txt" 2>/dev/null
    echo "next: --full-factorial (AFTER the retained bist-class samples land, see SAMPLES.md)"
    ;;
esac
