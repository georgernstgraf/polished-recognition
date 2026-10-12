# Seam-study tooling (`#122`)

Reproducible, file-driven measurement of the fragment-stitching algorithm.
A WAV is injected through the production restore seam of a special `harness`
build, one real fragment-pipeline transcription runs per adb `am start`, and
the fragment join is compared against a full-context **shadow** transcript.
Results back the write-up in [`docs/research/2026-10-seam-study.md`](../../docs/research/2026-10-seam-study.md).

## Layout

| path | what |
|---|---|
| `run_matrix.sh` | Tier 1 core + Tier 2 arms (before/after, size, mechanism, pre-roll). `MODE=` selects the arm. |
| `run_prompt.sh` | prompt-length sweep at the default 10 s fragments (kept for the record; see the "window never binds" finding). |
| `run_prompt2.sh` | prompt-length sweep at 21 s / 60 s fragments, where the window actually binds. |
| `run_quality.sh` | **#129**: staged, resumable, strictly-serial coordinate descent over `axisK`/`axisC`/`qualityMin`/`marginKMs`/`searchSpan` + the final `--full-factorial`; `--dry-run`/`--stage N`/`--probe`/`--subset FILE` modes. |
| `analyze.py` | loop-masked analyzer + arm/duration/per-seam summaries; `--arms`, `--detail <runid>`, `--csv`, plus **#129**: `--quality-csv`, `--stage-winners`, `--selftest`. |
| `merge_prepare.py` | **#129**: merges polled snapshots of the rotating `prepare` stream (10-deep, one record per file) into one deduplicated `prepare_all.json` per arm. |
| `quality_subset.txt` | **#129**: the 11-sample representative subset for the descent stages (rationale inside). |
| `quality_results.csv` | **#129**: one row per sweep arm (regenerated from the results dir after every arm — the committed snapshot of the research data). |
| `testdata/quality-fixture/` | **#129**: synthetic arm (hand-written `harness.json` + `stt-fragment.json` + `prepare_all.json`) with known expected values; `analyze.py --selftest` asserts them. |
| `corpus/` | `build.sh` (ffmpeg conversion), `samples.json` (manifest: class, source, offset, durations, sha256), `REPORT.md` (class confidence). |
| `results.csv` | the 126-run per-run table (raw data snapshot). |

## Prerequisites

- The `harness` build variant on the device: `./gradlew installHarness`
  (**never** built for release/F-Droid; same applicationId + key as release,
  so it installs in place and shares the provider config).
- The corpus on the device at
  `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/samples/`.
- A reachable OpenAI-compatible STT provider (the study used the local `gregor`,
  `large-v3`, which forwards the Whisper `prompt`). Python 3 on the host.

## Run

```bash
# corpus (host): build the WAVs and push them + the manifest
bash corpus/build.sh                     # see the script for the sshfs mount + ffmpeg recipe
# one arm (Tier 1 core by default):
MODE=tier1     bash run_matrix.sh
MODE=tier2size bash run_matrix.sh        # 7 s / 21 s fragment sweep
MODE=tier2policy bash run_matrix.sh      # ALL / FORCED_ONLY / OFF / pre-roll-only / prompt-only
MODE=tier2preroll bash run_matrix.sh     # pre-roll 0.5 s / 2 s
bash run_prompt2.sh                      # prompt 0/96/320/800 at 21 s & 60 s fragments
# analysis
python3 analyze.py --arms                # arm summary (loop-masked)
python3 analyze.py --detail <runid>      # per-fragment seam table
```

Each run is idempotent (a finished `results/<id>/harness.json` is skipped), so
re-running a mode resumes instead of redoing work. Results land in `results/<id>/`
(`harness.json` + `stt-fragment.json` + `stt-shadow.json` + the other streams).

## Quality sweep (#129)

`run_quality.sh` measures the real values for the #126/#128 knobs
(`axisK`, `axisC`, `qualityMin`, `marginKMs`, `searchSpan` — provisional
today: 0.65 / 100.0 / 10.0 / 150 / lateHalf). It is a **staged coordinate
descent**, not a cartesian product — gregor is C=1, a full matrix would run
for days:

```bash
bash run_quality.sh --dry-run            # the plan, no device/server contact
bash run_quality.sh --probe              # ONE end-to-end arm first (server check)
bash run_quality.sh                      # stages 1..5 on quality_subset.txt
bash run_quality.sh --stage 3            # a single stage (held knobs from
                                         #   $OUT/winners.txt or env overrides)
bash run_quality.sh --full-factorial     # 3x3 axisK x qualityMin, FULL corpus
python3 analyze.py <results-dir> --quality-csv quality_results.csv
python3 analyze.py <results-dir> --stage-winners <results-dir>/winners.txt
python3 analyze.py --selftest            # unit-check vs testdata/quality-fixture
```

- **Stages**: 1 `axisK` (5 levels) → 2 `qualityMin` (5, with stage-1's
  winner) → 3 `axisC` (5) → 4 `marginKMs` (3) → 5 `searchSpan` (2) — always
  on the 11-sample subset; then the 3×3 factorial over
  `axisK × qualityMin` (winner ± one level) on all 37 corpus files.
  Scoring is automated: primary = the loop-masked err/100w, tie-break =
  seam-duplication count. Winners land in `$OUT/winners.txt` after each
  stage; `axisC`/`marginKMs`/`searchSpan` are expected to be stability knobs
  — the `--stage-winners` response curves make flat (no-effect) curves
  visible, replicating the #122 null-result pattern.
- **Resume**: an arm is done iff its `harness.json` exists (run_matrix
  semantics); re-invocations skip completed arms, so crashes/server outages
  never redo work. `$MAX_FAILS` consecutive arms without a `harness.json`
  abort the run.
- **Evidence cadence (rotation!)**: the app's `prepare` stream keeps ONE
  record per file, rotated 9 deep, and the harness path bursts ALL prepare
  records before the first upload — `run_matrix.sh`'s single post-run pull
  of `prepare.json` captures exactly ONE record. `run_quality.sh` therefore
  polls all 10 rotation slots during every arm and merges them
  (`merge_prepare.py`) into `<arm>/prepare_all.json`. Arms with ≤ 11
  fragments capture every seam's record (the tail's record is never needed);
  longer arms report the loss as `prepare_captured < prepare_expected`.
- **Per-seam analysis** (`--detail`, `--quality-csv`): per-seam `Q` and
  `stitched` from the prepare contract block, seam cleanliness via the
  Python `SeamOverlap` mirror (cross-checked against the on-device
  `seamOverlapTokens` — `tokmirror_mismatch` must stay 0) plus a word-split
  heuristic, Q distributions split clean/unclean and stitched/bare (THE
  `qualityMin`/`axisK` decision input), Spearman Q-vs-defect, and a
  trim-false-positive flag. Heuristic limits: overlap cannot distinguish a
  straddled word from a surviving echo or a legitimate repetition; the
  word-split flag fires on ordinary punctuation-less continuous speech and
  misses German capitalized nouns; the trim flag's "missing" attribution is
  itself reference-dependent — treat all three as review flags, not verdicts.
- Everything is gregor-specific and strictly serial; the geometry mirrors
  production (`fragment 10 s, search = S → spans [S, 2S]`, qgate policy).

## Arm-ID legend

| prefix | arm |
|---|---|
| `<class>__<dur>__<fmt>` | Tier 1 core: fragment 10 s, search 2 s, policy `FORCED_ONLY`. |
| `B_…` | "before": policy `ALL` (pre-roll+prompt at every seam). |
| `S7_…` / `S21_…` | fragment size 7 s / 21 s. |
| `P_all_` `P_forced_` `P_off_` `P_preroll_` `P_prompt_` | mechanism isolation at 60 s. |
| `R0.5_` / `R2_` | pre-roll size 0.5 s / 2 s. |
| `W<frag>_P<prompt>_…` | prompt window at 21 s / 60 s fragments. |

## Caveats (read before trusting a number)

- **The shadow is not ground truth.** It is the same Whisper on the same audio
  as one big block; it loops/hallucinates on repetitive, musical or noisy
  content and its VAD collapses on long uploads. The analyzer therefore
  **loop-masks both texts** and flags runs whose shadow word count diverges
  from the join's. Without that, the metric mostly measures the reference.
- Absolute error rates are inflated by legitimate rewordings (casing,
  punctuation, number formatting) between the two paths; the **trends** are the
  signal.
- The corpus was selected from metadata only (no listening); the `en_` classes
  are largely detected as German — labels are unreliable, conclusions are not.
- Everything here is gregor-specific; GROQ (no server-side VAD, different
  `prompt` handling) may behave differently.
