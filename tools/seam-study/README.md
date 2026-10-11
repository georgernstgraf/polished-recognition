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
| `analyze.py` | loop-masked analyzer + arm/duration/per-seam summaries; `--arms`, `--detail <runid>`, `--csv`. |
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
