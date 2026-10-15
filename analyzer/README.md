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
    [--search-span lateHalf] [--policy qgate] [--fragment-seconds 21] \
    [--search-seconds 2] [--pre-roll-seconds 1] [--prompt-chars 96]
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
             "marginKMs": 150, "searchSpan": "lateHalf", "policy": "qgate",
             "fragmentSeconds": 21.0, "searchSeconds": 2.0,
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

### `report` — provider-performance aggregation (#144c)

```bash
analyzer report corpora/seam-study-01/bundles
```

Per provider (baseUrl+model): sessions, fragments, audio seconds,
pre-roll seconds, stitched/forced seam counts, STT failures.

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
