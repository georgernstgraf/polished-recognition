# The `:analyzer` CLI — corpus-first analysis (#146)

The study's CODE lives on here, on the PC. `:analyzer` is a Kotlin-JVM CLI
that compiles the REAL production pipeline sources via `:core` (zero drift:
`FragmentPreparer`, `FragmentTranscriber`, `SttRequestRunner`,
`SessionBundle` are the same classes the device runs), plus the ported
analysis core of the #122/#129 seam study.

The old `analyze.py` compatibility is STRUCK (owner decision, 2026-10-15):
`:analyzer` defines its own canonical result format (below) once. The
Python study tooling was deleted in #147 (superseded); the #122/#129
fixtures serve as regression tests of the ported logic
(`FixtureRegressionTest` asserts every former `analyze.py --selftest`
value).

## Build & run

```bash
./gradlew :analyzer:installDist           # analyzer/build/install/analyzer/bin/analyzer
./gradlew :analyzer:test                  # the suite (incl. the fixture regression)
analyzer/build/install/analyzer/bin/analyzer <command> [options]
```

## Corpus convention (binding, owner decision 2; local-only rule 2026-10-15)

```
corpora/<name>/
  manifest.json      — LOCAL ONLY (never committed — see below)
  wavs/              — local material (16 kHz mono s16le WAVs, per-class dirs)
  bundles/           — local device bundles (uploads/ + session.json per session)
  results/<run-id>/  — local analyzer output (canonical result JSON)
```

**The whole `corpora/` tree is git-ignored (owner privacy decision, 2026-10-15):
corpora contain real-life, probably private, spoken words — nothing under
`corpora/` is ever committed, including manifests.** Share findings and
parameter conclusions via issues; provenance stays on the working host.

`manifest.json` fields: `name`, `createdAt`, `description`, `source`
(where the material came from + the build.sh recipe), `kinds` (e.g.
`["wav", "bundle"]`), `audio` (sampleRate/channels/encoding), and
`classes[]` — each with `name`, `kind`, `confidence` (`high` |
`medium-high` | `medium` | `low`), `note` (the uncertainty, verbatim from
the build report), `source` (provenance), `startSeconds`, and `files[]`
(`path` relative to the corpus dir, `seconds`, `sha256`).

Corpus BUILDING stays outside the CLI (owner decision 5): one `build.sh`
per corpus after the proven blueprint (murl/sshfs → ffmpeg cuts → ffprobe
verify → sha256 → manifest — see `tools/seam-study/corpus/build.sh`).
`tools/seam-study/generate_corpus_manifest.py` migrates a #122-style
`samples.json` + build report into the manifest convention.

**Korpus #1 = `seam-study-01`** — the #122 seam-study corpus (37 WAVs,
7 classes, nested cuts 10–600 s), imported 2026-10-15 from host `think`
(`rsync`), sha256-verified against `samples.json`. Its role: the
clean-baseline reference corpus (too clean to discriminate the gate —
now a stable reference).

## Commands

### `run` — corpus mode (the ex-HarnessActivity capability)

```bash
ANALYZER_STT_TOKEN=... analyzer run --corpus seam-study-01 \
    [--run-id ID] [--only SUBSTR] [--no-reference] \
    [--stt-url URL] [--model large-v3] \
    [--axis-k 0.65] [--axis-c 100.0] [--quality-min 10.0] [--margin-k-ms 150] \
    [--policy qgate] [--slice-seconds 21] \
    [--search-percent 30] [--pre-roll-seconds 1] [--prompt-chars 96]
```

For every corpus WAV (sha256-verified against the manifest): `WavPcm` →
in-memory `PcmSource` → the REAL `FragmentPreparer` (pinned knobs,
defaulting to the frozen production config) → the REAL `FragmentTranscriber`
(prompt carry, echo trim, stitch evidence) → `SttRequestRunner` → the
full-context reference pass → canonical `result.json` into
`results/<run-id>/<class>__<stem>/`.

Invariants:
- **Strictly serial** requests (C=1 — gregor serializes; the transcriber's
  worker is serial by construction, the reference pass runs after drain).
- **Never upload >300 s**: fragment uploads are one fragment + 1 s pre-roll;
  reference slices go through `WavChunker` (300-s cap, #120 rule).
- **Credentials never committed**: `--stt-token` or `ANALYZER_STT_TOKEN`
  (endpoint: `--stt-url` or `ANALYZER_STT_URL`).
- **Resume-safe**: a sample whose `result.json` reports `success` is
  skipped — interrupted runs continue where they stopped.

The analyzer writes WAV fragments (`wavMode` — no platform Opus encoder on
the JVM); the transcription path runs regardless of `compress_audio` in
production too, so this exercises the same code paths.

### Canonical `result.json` (the format, defined once)

```jsonc
{
  "formatVersion": 1,
  "runId": "clean-baseline-20261015-1440",
  "corpus": "seam-study-01",
  "class": "de_interview",
  "file": "wavs/de_interview/de_interview_60s.wav",
  "sha256": "2fded0…", "sessionId": "de_interview_60s-20261015-…",
  "durationSeconds": 60.0,
  "startedAtMs": 1760520000000, "endedAtMs": 1760520024000,
  "knobs": { "axisK": 0.65, "axisC": 100.0, "qualityMin": 10.0,
             "marginKMs": 150, "policy": "qgate",
             "sliceSeconds": 21.0, "searchPercent": 30.0,
             "preRollSeconds": 1.0, "promptChars": 96 },
  "stt": { "baseUrl": "…", "model": "large-v3" },
  "success": true, "error": null,
  "join": 152, "reference": 149,          // raw token counts (pre-mask)
  "joinLoops": 0, "referenceLoops": 0,    // loop-masked token counts
  "ratio": 1.02, "reliable": true,        // reliability window [0.80, 1.25]
  "extra": 3, "missing": 2,
  "err100w": 3.36,                        // (extra+missing)/reference·100
  "fragments": [ { "index": 0, "words": 39, "seamForced": null,
                   "echo": 0, "extra": 0, "missing": 0 } ],
  "seams":   [ { "seam": 1, "q": 2.0, "stitched": true,
                 "overlap": 2, "split": false, "unclean": true } ],
  "q": { "median": 21.0, "p25": 2.0, "p75": 40.0,
         "stitchedMedian": 2.0, "bareMedian": 40.0,
         "uncleanMedian": 2.0, "cleanMedian": 40.0,
         "spearman": -1.0, "trimFalsePositives": 1,
         "seamOverlapTotal": 2, "seamsUnclean": 1, "wordSplits": 1 },
  "joinText": "…", "referenceText": "…", "referenceSlices": ["…"]
}
```

Same dir: `session.json` (the REAL forensic bundle, the app's own shape —
per-fragment sttRaw/sttTrimmed/echoTokens/prompt + the START-seam quality
block), `session/` (fragments + uploads retained), `logs/` (the live-debug
evidence streams).

### `scan` — bundle mode (the #129 natural-find detector)

```bash
analyzer scan corpora/seam-study-01/bundles [--q-threshold 10.0] [--detail ID]
```

Parses every `session.json` (the real bundle shape, round-trip-guarded by
`SessionBundleRoundTripTest`), prints a tabular inspection, then the
**Q-scan**: every seam with `stitched` / `seamForced` / Q, flagging seams
below the configurable threshold (`LOWQ` — seams below the gate EXPECT a
stitch) plus `STITCHED-BUT-CLEAN` inconsistencies. This is the automated
natural-find detector for #129: real dictations whose seams fall below the
provisional gate tell us where the real `qualityMin` boundary sits.

An unparseable `session.json` is **skipped with a stderr warning** — a corpus
is real material and one bad bundle must not blind the scan (live case:
2026-11-12, the 140-byte adb-error bundles above).

### `report` — provider-performance aggregation (#144c)

```bash
analyzer report corpora/seam-study-01/bundles
```

Per provider (baseUrl+model): sessions, fragments, audio seconds,
pre-roll seconds, stitched/forced seam counts, STT failures — and since
#148 the per-request STT timing: timed requests, mean request latency
(`req(s)`), mean real-time factor (`RTF` = request duration / audio
duration; `< 1` is faster than realtime, the serial pipeline's streaming
budget), mean upload size (`up(kB)`), plus the slowest request and worst
RTF across all sessions. Fragments without timing (legacy bundles) simply
contribute no timed request.

### `compare` — cross-corpus comparison

```bash
analyzer compare corpora/*/results/<run-id> [more dirs...] [--by-knob axisK]
```

Default: per corpus+class table (the #129 double-check — "does the
candidate hold in ALL corpora?"). `--by-knob K`: the response-curve port of
`analyze.py quality_modes` — per knob LEVEL the mean err/100w over reliable
runs, seam-duplication totals, unclean-seam counts, and the coordinate-
descent winner (minimal mean err/100w, tie-break lower duplication, then
closer to the provisional default).

### `pull` — adb encapsulation (owner decision 4)

```bash
analyzer pull --corpus seam-study-01 [--serial f6de166c]
```

Pulls `files/audio/<sessionId>/` (uploads/ + session.json) off a device
into `corpora/<name>/bundles/`. Encapsulates the hard-won adb gotchas:
`adb pull` is BLOCKED on these devices → `adb exec-out cat` per file
(binary-safe; `adb shell`'s pty would mangle WAV bytes); `adb shell ls`
listings are `\r`-stripped; every adb call redirects stdin from
`/dev/null`; the serial is pinned (`-s`, two devices usually attached:
`f6de166c` = OnePlus 7T, `R7SRB06XF5K` = A03).

**Only bundles are pulled**: a session dir without `session.json` (pre-#142:
`session.wav` + `fragments/`) is skipped with a note — it has no forensic
record, and pulling it used to write adb's "No such file" text into a 140-byte
`session.json` that then aborted every scan of the corpus (2026-11-12, 101 of
481 opo dirs).

### Timing analysis from bundles (the #124 / #116 evidence recipe)

`report` aggregates per-request latency, but the figures that explain the
USER EXPERIENCE — how long a fragment is, how long the tail is, how much of
the stop is real waiting — need the per-session timeline. `session.json` has
everything; this snippet reproduces the #124 measurements (317 clean
sessions: body 15.7/17.7/19.6 s p10/50/90, tail 3.4/7.9/18.1 s,
STOP→raw 0.94/1.65/3.13 s, backlog 4.0/10.7/22.0 audio seconds,
`t = 0.72 s + 2.77 %/s`):

```bash
python3 - <<'EOF' corpora/<name>/bundles
import json, glob, statistics as st, sys
from itertools import combinations

# PITFALL: session.json's endedAt is the SESSION END (after delivery), NOT the
# stop tap. Without pauses the tap is startedAt + last.endMs; the delta to the
# tail's sttSentAt is the local encode time, so 0..10 s is the sane band
# (outside = pause, process restore, clock skew).
body, tail, stop_raw, backlog, lat = [], [], [], [], []
for p in sorted(glob.glob(sys.argv[1] + '/*/session.json')):
    try:
        d = json.load(open(p))
    except Exception:
        continue
    frs = d.get('fragments') or []
    if isinstance(frs, dict):
        frs = [frs[k] for k in sorted(frs, key=int)]
    frs = [f for f in frs if f.get('startMs') is not None and f.get('endMs') is not None]
    if not frs or not d.get('startedAt'):
        continue
    dur = lambda f: (f['endMs'] - f['startMs']) / 1000.0
    last = frs[-1]
    tap = d['startedAt'] + last['endMs']
    if not last.get('sttSentAt') or not last.get('sttReceivedAt'):
        continue
    if not (0 <= (last['sttSentAt'] - tap) / 1000.0 <= 10):
        continue
    body += [dur(f) for f in frs[:-1]]
    tail.append(dur(last))
    stop_raw.append((last['sttReceivedAt'] - tap) / 1000.0)
    backlog.append(sum(dur(f) for f in frs
                       if not f.get('sttReceivedAt') or f['sttReceivedAt'] > tap))
    for f in frs:
        if f.get('sttSentAt') and f.get('sttReceivedAt') and dur(f) > 0:
            lat.append((dur(f), (f['sttReceivedAt'] - f['sttSentAt']) / 1000.0))

def stat(name, v):
    if not v:
        return
    v = sorted(v)
    q = lambda x: v[min(len(v) - 1, int(x * len(v)))]
    print(f'{name:42s} n={len(v):4d}  p10={q(.1):6.2f} p50={st.median(v):6.2f} p90={q(.9):6.2f}')

stat('fragment length, body (s)', body)
stat('fragment length, tail (s)', tail)
stat('STOP -> raw text (s)', stop_raw)
stat('backlog at the tap, audio seconds', backlog)
slopes = [(t2 - t1) / (L2 - L1) for (L1, t1), (L2, t2) in combinations(lat, 2) if abs(L2 - L1) > 1e-9]
b = st.median(slopes)
a = st.median([t - b * L for L, t in lat])
print(f't = {a:.2f} s + {100 * b:.2f} %/s')
EOF
```

Split by provider before trusting a cost model: `a` and `b` are per
provider **and per format** (OGG vs WAV), which is exactly how the app keys
its profile. The local encode rate is NOT in there — take it from
`logs/prepare*.json` (`encodeMs` vs `bytes`): OGG ≈ 7.9 % of the audio
length, WAV ≈ 0.1 %.

### `reconstruct` — bundles → session WAVs (#149)

```bash
analyzer reconstruct --corpus <name> [--session ID-SUBSTR] [--dry-run]
```

Rebuilds a **continuous session WAV** from every post-#142 bundle
(`uploads/` + `session.json` — the bundles have no `session.wav`), so the
daily driver's real dictations become corpus material for the #129
fragment-size sweep and future sizing studies:

- Tiling: fragments in index order; every composed `upload_*` carries the
  pre-roll in its head — cut `preRollMs` so the piece aligns with the
  PCM-derived `startMs`/`endMs` boundaries. A bare `frag_*` upload
  (degraded composition) is copied whole — it does NOT carry the head even
  though `preRollMs` is recorded.
- Format detection is **per fragment** from the `uploadFile` extension —
  never from `provider.compressAudio` (a session-start snapshot that is
  legitimately wrong in the two designed mixed-bundle cases: the OGG→WAV
  transcoder-fallback rebuild, and process-death recovery across a
  settings flip). WAV pieces cut pure-Kotlin (:core WavReader/WavWriter);
  OGG pieces decode via host **ffmpeg** (documented host dependency).
- **Strict gate (owner decision):** a bundle whose audio is incomplete —
  `sttError`, missing upload file, gap/overlap, or a piece deviating more
  than ±3 ms from its boundary span — is **discarded whole**. One exception
  is codec padding, not missing audio (#129, measured 2026-11-12): a decoded
  OGG piece is systematically longer than its span — **+12.8 ms** for bare
  fragments (last Opus frame padded) and **+30.7 ms** for composed
  `upload_*` pieces (same padding + the recorded `preRollMs` being truncated
  to 980 ms), WAV 0 ms. An overshoot up to **40 ms** is trimmed back to the
  span and noted; a SHORT piece is always fatal. No splicing,
  no silence-fill; corpus material is never silently glued.
- Output: `wavs/<sessionId>.wav` (16 kHz mono s16le) + a manifest entry
  (class `reconstructed`, sha256, seconds — idempotent per path), so
  `analyzer run` consumes it like any corpus WAV. `--dry-run` validates
  and reports without writing.
- Privacy: reconstructions land under `corpora/` — LOCAL-ONLY, never
  committed.

Typical pipeline: `pull` → `reconstruct` → `run` (the fragment-size sweep).

## The loop-mask port (the analysis heart)

- **`:core` `pipeline/LoopMask`** — `loop_spans`/`compress_loops`, verbatim
  from `analyze.py`: phrase runs of length 1–8 repeated ≥ 4× compressed to
  one `<loop>` sentinel BEFORE any diff. Unit-tested in `:core`
  (`LoopMaskTest`); doubles as the #123 production loop-guard core.
- **`:analyzer` `analysis/`** — the rest of the proven logic: the seam
  attribution (`attribute`, LCS-diff opcodes mapped onto fragment
  boundaries — replace vs delete+insert decompositions yield identical
  extra/missing sums), seam cleanliness (`SeamOverlap` = the REAL Kotlin
  metric via `:core`, plus the `word_split_flag` heuristic), the
  statistics (`pct` ceil-index percentiles, `median`, average-rank
  `spearman`), and the reliability filter (ratio ∈ [0.80, 1.25]).

The #129 fixture (`tools/seam-study/testdata/quality-fixture/`, mirrored
into `src/test/resources/quality-fixture/`) is the regression: every value
the Python `--selftest` asserts is asserted against the Kotlin port.

## Caveats (carried over verbatim — read before trusting a number)

- **The reference is not ground truth.** It is the same Whisper on the same
  audio as one big block; it loops/hallucinates on repetitive, musical or
  noisy content and its VAD collapses on long uploads (hence the ≤300-s
  slices). The analyzer loop-masks BOTH texts and flags runs whose
  reference word count diverges from the join's.
- Absolute error rates are inflated by legitimate rewordings; the TRENDS
  are the signal.
- Korpus #1 was selected from metadata only (no listening); the `en_`
  classes are largely detected as German — labels are unreliable,
  conclusions are not. `en_interview` is the weakest class (a comedy act,
  not a real interview).
- gregor is strictly serial (C=1) and its `vad_filter=True` is hardcoded —
  the reason for the slice cap and the serial request discipline.
