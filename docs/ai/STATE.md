# Project State

Current status as of 2026-10-12: **#128 (Q-gated stitching) is IMPLEMENTED and verified — the seam stitch decision (pre-roll + prompt + echo trim) is `Q < qualityMin` under `SeamPolicy.Q_GATE` (the new production default), decided ONCE in `FragmentPreparer.seamStitchedAt` and consumed by the transcriber; suite 473 green, `assembleRelease` + `compileHarnessKotlin` green, pushed as `c4c14ab`. #129 (harness knobs + the research pass for the real k/c/K/qualityMin values) is the follow-up; #123 (loop guard) stays queued. The #126 metric, #127 audio-evidence retention and the #121/#122 close-out are history (see DECISIONS).**

## #128 — what shipped (`c4c14ab`)

- `SeamPolicy.Q_GATE` (controller default; ALL/FORCED_ONLY/OFF frozen A/B arms; harness `policy` extra accepts `qgate`). Boundary: STRICT `Q < qualityMin` (Q == min → bare); null Q (legacy manifests, fixed cuts) stitches; forced cuts (Q = 0) always stitch.
- `FragmentPreparer.Entry.seamQ` (manifest-round-tripped; Gson omits nulls → legacy manifests parse); `seamStitchedAt` = THE decision (pre-roll composition, 5-arg hook / `Committed.stitched`, `prepare.json`'s now-real `stitched`).
- `FragmentTranscriber` dropped its own `SeamPolicy` — prompt iff `CommittedFragment.stitched` (+ `!promptDisabled`); trim unchanged (keys on the pre-roll file). Pre-roll/prompt/trim can never disagree.
- `VoiceSessionController.fragmentQualityMin` (PROVISIONAL 10.0 = `FragmentPreparer.DEFAULT_QUALITY_MIN`; #126 fixtures bracket it: solid Q ≈ 40 bare, whisper Q ≈ 2.26 stitch; #129 measures the real value).
- Tests +8 → **473 green** (5 skipped integration as always); fixture numbers pre-verified against the #126 Python mirror (`/tmp/opencode/sq-mirror/predict_128.py`). Pre-push suite ran clean first try (2048m heap since #131 — no flake).

## #126 — what shipped (`7ff3800`)

- `audio/SilenceQuality` + `SearchSpan` (pure JVM): per-fragment M/P5-floor/max over the high-passed search span (`PcmConditioner.highPassOnly` — the one-pole extracted, normalization-free), axis `X = k·M + c`, deficit curve, candidates, Q, margin-clamped area-centroid cut; no candidate → forced quietest-frame cut (Q = 0). `candidateFound ⇔ Q > 0` is the interim `silenceAligned` mapping (#128 replaces it with `qualityMin`).
- `FragmentPreparer`: default search span = LATE half (owner decision); the decision always waits for the full window; `energyFallbackEnabled` inert; `prepare.json` carries the contract block `{M, min, max, X, Q, centroidMs, stitched:false}` instead of `silenceAligned`.
- `VoiceSessionController` plumbs `fragmentAxisK`/`fragmentAxisC`/`fragmentMarginKMs`/`fragmentSearchSpan` — PROVISIONAL defaults 0.65 / 100.0 / 150 ms / lateHalf; #129's research pass determines the real values (no Settings UI).
- Tests +14/−11 → **465 green** (5 skipped integration tests, as always); all fixture expectations pre-verified against an exact Python mirror of the metric (`/tmp/opencode/sq-mirror/`).
- Whisper-regime finding: a 6-dB band at whisper level is NOT the forced path with these provisionals (the axis threads/overshoots the band → one shallow candidate, Q ≈ 2.3 — the issue's "Q-gate arms stitching" case); the forced path needs a tight band with `M ≥ c/(1−k) ≈ 286`. Both regimes unit-tested; #129 measures the real boundary.

## #127 — what shipped

- `pipeline/AudioEvidenceStore` (pure JVM, injectable dir + clock): copy-only retention of `session.wav` + `fragments/` + `uploads/` into `<externalFilesDir>/audio/<sessionId>/`, ring buffer ≤ 3 sessions / ≤ 50 MB (newest session always survives), tmp+rename writes, never throws.
- Capture points: controller `retainAudioEvidence()` after `runTranscription` before `clearPrepared()` (success AND failure); `FragmentTranscriber` upload `finally` before `delete()`. `cancel()`/`flush()` = deliberate discard, no retention.
- Gate: `audio_evidence` toggle (`SettingsStore.audioEvidenceDefault` ← `BuildConfig.AUDIO_EVIDENCE_DEFAULT`: false release / true debug+harness); privacy hint in Settings; harness wires the store + gate too.
- Tests +18 → suite 462 green (now 465 after #126).

## #124/#125 — what shipped (`3647554`)

- `VoiceSessionController.resolvedSearchBytes()`: with a `fragmentSizeProvider` wired (production), `search = max(fragmentSearchBytes, resolvedFragmentBytes())` → fragment spans `[S, 2S]`; explicit injected values keep their exact semantics (`0` = legacy fixed cuts); the harness dropped its provider wiring so its explicit `searchSeconds` stays authoritative.
- `SilenceCutter.quietestFrameCut()` + `FragmentPreparer.energyFallbackEnabled` (default true): on a silence-free FULL window, cut at the minimum-energy 10-ms frame center in the late half `[1.5S, 2S]`, tagged FORCED (`FORCED_ONLY` still applies pre-roll + prompt). Harness knob `--ez energyFallback`.
- `BuildConfig.SHADOW_STT` / `FULL_CONTEXT_AT_STOP`: false in `defaultConfig` (release/F-Droid/CI), true in debug + harness; `PolishedRecognitionApp` wires them; the `stt-shadow` gate breadcrumb moved behind the gate (release writes NO `stt-shadow.json`); full-context delivery events renamed to `stt-fullcontext`; `SttEndpointPolicy` + test deleted.
- Tests +8 (444 green); `assembleRelease` + `compileHarnessKotlin` green.

## Energy-fallback extrapolation check (harness A/B, OnePlus → gregor)

Frozen config (frag=10, search=10=S, forced, wav, raw, shadow reference), 8 runs all green, loop-masked:

| arm | frags | forced seams | err/100w |
|---|---|---|---|
| search = 2 s (study config) | 68 | 3 | 6.2 |
| search = S = 10 s (shipped) | 52 | 0 | 4.1 |

The adaptive search eliminates forced seams on Tier-1 classes; the energy fallback NEVER fired there (both arms byte-identical — every 10-s window contains a qualifying pause). The fallback's regime is continuous/background-noise audio, where the shadow reference is itself unreliable — it stays validated at the unit-invariant level. Runs + analysis: `/tmp/opencode/seam-run/results/EF_*`, runner `run_energyfallback.sh`.

## Current Focus

#128 (Q-gated stitching) is implemented and verified (2026-10-12, `c4c14ab`). Next in the seam line: **#129** (harness knob rework — expose `axisK`/`axisC`/`qualityMin`/`marginK`/search-span as `am start` extras, drop the inert `energyFallback`, and run the research pass on retained audio + the corpus to replace the PROVISIONAL defaults: axis 0.65/100.0/150 ms/lateHalf and **qualityMin 10.0**). #123 (loop guard) stays queued. **With `fullContextAtStop` OFF in release, the fragment join is the ONLY delivery path.**

## Pending
- [ ] **#129 research pass**: measure real k/c/K/Q_min on retained audio + the corpus (harness on the Samsung A03 vs gregor); replace the provisional defaults (0.65 / 100.0 / 150 ms / **10.0**); rework the harness knobs (incl. making `qgate` the sensible default arm).
- [ ] **#127 on-device spot check (owner, optional)**: one dictation on a debug/harness build → `adb shell ls files/audio/<sessionId>/` shows `session.wav`, `fragments/`, `uploads/`; a second look at the Settings toggle (default off in release).
- [ ] **Owner dictation check (~25 s)** on the release build: `prepare.json` cadence (~10 s nominal, growth ≤ 2S) carries the `{M, min, max, X, Q, centroidMs, stitched}` block with a REAL `stitched` (Q < 10 → stitched seams now carry pre-roll + prompt), stop→raw ≤ ~2.4 s worst case, Settings fragment field blank.
- [ ] **#120 re-check (owner)**: > 5-min dictation on the release build → `stt-upload`/`stt-latency` every `durationMs ≤ 300_000`, no mid-text cut-off → then close #120.
- [ ] **#123 loop guard** — filed with the #122 evidence; queued behind the seam work.
- [ ] **Unidentified unit-test flake RECURRED AGAIN** (2026-10-12, during the #126 push — executor exit 2 at 318/465, 0 recorded failures; third recorded occurrence). Owner decision pending: raise the test-only `maxHeapSize` (1536m → 2048m)?
- [ ] **#118 Phase B** (delivery policy), **#112/#113** owner items, **#116 Phase 2** remaining owner decisions (auto-size floor now 10 s; shadow diagnostic-only, now debug/harness-only), **#110**, **#101**, **#99/#101/#75**, **#74 marketing**, **#109** visual check.
- [ ] **F-Droid 1.3.6 pickup watch** (tag `v1.3.6` = 10306 already out).

## Device Notes
- `f6de166c` = OnePlus 7T — **clean release 1.3.6 (10306)** (verified: no `DEBUGGABLE`, no harness activity). Corpus + leftover `harness/` artifacts remain under `files/`; `run-as` is dead on it (release, non-debuggable) — prefs were captured 2026-10-11: auto fragment size (no override), `compress_audio=false`, gregor `large-v3` @ `http://10.8.0.16:11437/v1/`, WAV profile `lastAppliedSeconds=10.0`, fit a=0.566/b=0.050, 30 samples.
- `R7SRB06XF5K` = SM-A032F — **clean release 1.3.6** (upgraded from 1.3.4/10304) + the full study corpus (38 WAVs/172 MB incl. `samples.json` + `REPORT.md`) pushed to `files/samples/` via `/data/local/tmp` (see PITFALLS: Samsung blocks adbd's `secure_mkdirs` under the app dir).
- gregor up (2026-10-11): API + SSH respond; serial C=1; the EF runs measured 300-s WAV sessions ≈ 58 s wall (join + shadow).
- Harness A/B tooling (host, NOT in the repo): `/tmp/opencode/seam-run/{run_energyfallback.sh,run_matrix.sh,analyze.py}`; results in `/tmp/opencode/seam-run/results/EF_*`.
- **One unidentified unit-test flake — THIRD recorded occurrence 2026-10-12** (during the #126 pre-push): executor exit 2 at 318/465 tests, 0 failures in any completed XML; single re-run green. Same signature as 2026-10-11 (312/462) and the #86 heap crash; see PITFALLS for the candidate fix (raise test-only `maxHeapSize`).
