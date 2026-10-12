# Project State

Current status as of 2026-10-12: **#129's staged research run is EXECUTED on the Samsung A03 (220 arms, serial, ~16 s/arm, server `https://htl.graf.priv.at/v1/` → gregor large-v3): winners `axisK = 0.75`, `axisC = 100.0`, `marginKMs = 150`, `searchSpan = lateHalf` (three provisional defaults confirmed — stability knobs), `qualityMin = 40.0` on clean-corpus audio — BUT the response curves are FLAT (err/100w 10.7–12.1 across every level; seam Q median ≈ 66): the corpus cannot discriminate the stitching gate, so qualityMin is NOT decided yet; the `--full-factorial` is gated on retained bist-class dictations (SAMPLES.md gap). Harness build + retention verified in real operation (probe 16 s green; `files/audio/` ring buffer worked; `pull_retained.sh` byte-perfect). Remaining for the epic: owner bist-class dictations → pull → factorial → parameter proposal → #126/#127/#128/#129 close-out. #123 (loop guard) stays queued.**

## #129 research run — results (2026-10-12)

- **Winners (`samples/results/winners.txt`):** `axisK = 0.75` (adoptable — moves the axis above the fricative band, strictly safer at zero cost on clean audio; provisional was 0.65), `qualityMin = 40.0` (NOT adoptable as a decision — on clean audio any threshold ≥ the Q floor scores identically; the knob only has meaning where seams are sick), `axisC = 100.0` / `marginKMs = 150` / `searchSpan = lateHalf` (provisional defaults confirmed; flat curves — stability knobs as predicted from the #122 null pattern).
- **Evidence chain:** probe arm green 16 s end-to-end; 222 CSV rows (`tools/seam-study/quality_results.csv`, derived, git-ignored); per-seam Q evidence complete on ≤60-s subset samples; 0 seam overlap / 0 word splits / 0 trim-fp across all arms; `phone_radio_30s` unreliable at every level (hard class, not a knob effect).
- **Runner fixes during bring-up:** health check accepts 401/403 as reachable (`b04cbf3` — the auth token lives on the phone); `samples/results/` + derived CSV git-ignored (`2216942`, `e1c1c36`).
- **Owner dependency (the epic's critical path):** bist-class dictations on the A03 (harness build has retention ON — sessions auto-land in `files/audio/`, ring buffer keeps 3, pull with `pull_retained.sh`): German sentences with word-final /st/ + pause near a ~10–20-s boundary ("…informiert **bist**" ⏸ "Jedenfalls…", plus ist/kannst/musst/erst). THEN: `--full-factorial` (3×3 axisK×qualityMin on the full corpus) → parameter proposal.

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
- [ ] **#129 FINAL (gated on owner dictations)**: owner dictates bist-class sentences on the A03 (see HANDOFF) → `pull_retained.sh` → `--full-factorial` (3×3 axisK×qualityMin, full corpus + retained samples) → parameter proposal (adopt `axisK=0.75`; the REAL `qualityMin` from sick-seam data; `axisC=100.0`/`marginKMs=150`/`searchSpan=lateHalf` confirmed) → epic #130 close-out (#126/#127/#128/#129).
- [ ] **#127 on-device spot check (owner, optional)**: one dictation on a debug/harness build → `adb shell ls files/audio/<sessionId>/` shows `session.wav`, `fragments/`, `uploads/`; a second look at the Settings toggle (default off in release). (Partially PROVEN already: the #129 research run's harness sessions retained correctly on the A03.)
- [ ] **Owner dictation check (~25 s)** on the release build: `prepare.json` cadence (~10 s nominal, growth ≤ 2S) carries the `{M, min, max, X, Q, centroidMs, stitched}` block with a REAL `stitched`, stop→raw ≤ ~2.4 s worst case, Settings fragment field blank. NOTE: release builds still carry the PROVISIONAL defaults (0.65/100/10.0) until #129 concludes.
- [ ] **#120 re-check (owner)**: > 5-min dictation on the release build → `stt-upload`/`stt-latency` every `durationMs ≤ 300_000`, no mid-text cut-off → then close #120.
- [ ] **#123 loop guard** — filed with the #122 evidence; queued behind the seam work.
- [ ] **#118 Phase B** (delivery policy), **#112/#113** owner items, **#116 Phase 2** remaining owner decisions (auto-size floor now 10 s; shadow diagnostic-only, now debug/harness-only), **#110**, **#101**, **#99/#101/#75**, **#74 marketing**, **#109** visual check.
- [ ] **F-Droid 1.3.6 pickup watch** (tag `v1.3.6` = 10306 already out).

## Device Notes
- `f6de166c` = OnePlus 7T — **clean release 1.3.6 (10306)** (verified: no `DEBUGGABLE`, no harness activity). Corpus + leftover `harness/` artifacts remain under `files/`; `run-as` is dead on it (release, non-debuggable) — prefs were captured 2026-10-11: auto fragment size (no override), `compress_audio=false`, gregor `large-v3` @ `http://10.8.0.16:11437/v1/`, WAV profile `lastAppliedSeconds=10.0`, fit a=0.566/b=0.050, 30 samples.
- `R7SRB06XF5K` = SM-A032F — **harness build installed 2026-10-12** (`adb install -r app-harness.apk`, versionName 1.3.6/10306 + ALL epic code: #126 metric, #127 retention ON, #128 Q_GATE, #129 knobs); full study corpus (38 WAVs) at `files/samples/`; STT = `https://htl.graf.priv.at/v1/` (→ gregor RTX 2070, large-v3, ~20× realtime, C=1, `vad_filter=True`; auth token on the phone — the host gets 401, which the runner's health check correctly treats as reachable). `files/audio/` retention ring buffer verified in real operation (3 sessions / 22 MB, oldest pruned).
- gregor up (2026-10-12): reachable via `htl.graf.priv.at` (nginx 80→443, OpenAI-compatible `/v1/`); the #129 research arms measured ~16–20 s per arm (probe 16 s end-to-end incl. join + shadow reference).
- Research results LIVE IN THE REPO (git-ignored, /tmp is unreliable): `samples/results/` (per-arm dirs + `winners.txt`), corpus `samples/corpus/` (38 WAVs, sha256-verified), retained pulls `samples/retained/` (3 research-run sessions, md5-verified). Derived CSV: `tools/seam-study/quality_results.csv` (git-ignored, regenerated per arm).
