# Project State

Current status as of 2026-10-10 (night): **#122 seam study — harness + production changes shipped; Tier 1 + Tier 2 (126 runs) complete and analyzed. The seam-quality conclusions have been substantially REVISED by the data (see below).**

## #122 seam study — what shipped

- `e941430` hard 5-min STT cap (#120).
- `7862603` `SeamPolicy { ALL | FORCED_ONLY | OFF }` (FORCED_ONLY = controller default), true `silenceAligned` tagging, `MIN_FRAGMENT_SECONDS` 7→10 s, word-snapped seam-local prompt.
- `b401fbc` `pipeline/SeamOverlap` + `stt-fragment.json` evidence (one accumulating per-fragment array: index, PCM bytes, cut type, transcript, words, ACTUAL prompt, echo tokens, seam-overlap tokens).
- `a222874` `harness` build variant (`./gradlew installHarness`) + `HarnessActivity` (adb-driven, injects a WAV through the production restore seam, own controller, waits for the shadow, writes `harness.json`); controller hooks `onShadowResult` + `fullContextAtStopEnabled`.
- `344ec69` per-fragment PCM size in the evidence.
- Prompt-window knob `promptMaxChars` wired through the controller (was missing → first sweep invalid).

## #122 seam study — findings (126 runs, gregor, 5 classes × formats × arms)

- **The dominant error source is Whisper repetition-loop hallucination / whole-fragment derailment, which occurs in BOTH the fragment join and the shadow full-context pass** — NOT seam mechanics. In the reliable runs the shadow looped ~1528 tokens vs the join's ~146. Every join↔shadow "error" metric MUST loop-mask first (the analyzer in `/tmp/opencode/seam-run/analyze.py` does).
- **No systematic boundary duplication**: `SeamOverlap` found ~0 clean suffix↔prefix repeats; ~290 echo tokens trimmed total. The owner's "repetitions" are Whisper loops, not seam dups.
- **Forced seams ≈ 1.4× worse per seam than silence seams** (5.3 vs 3.7 errors/seam, loop-masked). Supports workstream (a) — but see the null results below.
- **(b) CONFIRMED**: on all-silence 60-s samples, applying the seam mechanism at silence seams adds errors — mechanism off 5.4 err/100w, prompt-only 6.9, pre-roll-only 6.4, both 7.7. The new FORCED_ONLY default (no pre-roll/prompt at a silence-aligned seam) is the right call.
- **NULL: pre-roll SIZE does not matter** (0.5 s vs 2 s = identical 7.1).
- **NULL: fragment size does not matter for accuracy** (7 s vs 21 s ≈ 11.8 vs 11.6 err/100w, same classes/durations).
- **NULL: prompt window length does not matter** — the prompt is only the PREVIOUS fragment's transcript (~90 chars at 10 s, ~190 at 21 s, ~550 at 60 s), so `takeLast(800)` never bound and `96` only binds above ~10 s fragments. Sweeping 0/96/320/800 at 21 s and 60 s fragments showed no monotone effect.
- **Format: OGG ≥ WAV** in error rate at 30/150/300 s (the one consistent mechanical lever).
- **Reference reliability**: 10/126 shadows flagged unreliable (phone_radio, panel, long ogg) — the shadow collapses or loops; treat as diagnostic only.
- Corpus caveat: the "en_" classes are largely detected as German (metadata-only selection); labels are unreliable, conclusions are language-independent.

## Current Focus
**#122 follow-up (owner decision):** the levers that survived are (i) do NOT apply the seam mechanism at silence seams — shipped as FORCED_ONLY; (ii) the loop/derailment propagation is the real damage and is NOT addressed by overlap size, fragment size, or prompt length — the candidate fix is a **loop guard** (detect a repeated n-gram inside a fragment transcript and truncate it BEFORE it becomes the next fragment's prompt). Also #120 needs an on-device re-check.

## Pending
- [ ] **#122 owner decision** — accept the null results (no overlap/size/prompt lever) and prioritize a loop guard, or extend the corpus (independent/host-side reference, more English material) for a stronger verdict.
- [ ] **#120 on-device re-check** — the 300 s cap is shipped + unit-tested; confirm the shadow no longer expands on-device.
- [ ] **#121** (parent) — the study's seam evidence feeds it; close or keep open per the loop-guard outcome.
- [ ] **#116 Phase 2 owner decisions** untouched: gregor's auto-size floor is now 10 s; shadow diagnostic-only.
- [ ] **#118 Phase B** (delivery policy) still pending.
- [ ] **#112/#113** owner items.
- [ ] **F-Droid 1.3.6 pickup watch**; **#74 marketing/alpha**; **#109** visual check.

## Device Notes
- **Seam harness**: `./gradlew installHarness` installs the `harness` variant (same appId + key as release → shares the config; NEVER in release/F-Droid). Drive it with `adb shell am start -n com.georgernstgraf.polishedrecognition/.harness.HarnessActivity --es file <wav> --es label <id> --es mode raw --es format {ogg|wav} --es policy {all|forced|off} --ef fragmentSeconds 10 --ef searchSeconds 2 --ef preRollSeconds 1 --ei promptChars 96 --ez reference true`. Results land in `/sdcard/Android/data/<pkg>/files/harness/latest.json`; per-fragment evidence in `.../files/logs/stt-fragment.json`.
- Corpus (37 mic-format WAVs, 7 classes) at `/sdcard/Android/data/<pkg>/files/samples/`; host copy + manifest in `/tmp/opencode/seam-corpus/`.
- Study harness scripts (host, NOT in the repo): `/tmp/opencode/seam-run/{run_matrix.sh,run_prompt.sh,run_prompt2.sh,analyze.py}`.
- `f6de166c` = OnePlus 7T (current: **harness build installed**, reinstall release when done). `d890cc9e` = S5.
- gregor: no request queue (C=1 serial client); ~25× realtime; logs `sudo docker logs whisper`.
