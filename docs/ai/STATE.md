# Project State

Current status as of 2026-10-11 (evening): **#127 (per-session audio-evidence retention) is IMPLEMENTED and verified — ring-buffered `files/audio/<sessionId>/` evidence behind a Settings toggle (default OFF in release, ON in debug/harness), suite 462 green, `assembleRelease` + `compileHarnessKotlin` green. The issue stays OPEN for the parent epic's close-out (on-device spot check optional). The #121/#122 close-out, config freeze and A/B are history (see DECISIONS); #123 (loop guard) remains the next workstream.**

## #127 — what shipped

- `pipeline/AudioEvidenceStore` (pure JVM, injectable dir + clock): copy-only retention of `session.wav` + `fragments/` + `uploads/` into `<externalFilesDir>/audio/<sessionId>/`, ring buffer ≤ 3 sessions / ≤ 50 MB (newest session always survives), tmp+rename writes, never throws.
- Capture points: controller `retainAudioEvidence()` after `runTranscription` before `clearPrepared()` (success AND failure); `FragmentTranscriber` upload `finally` before `delete()`. `cancel()`/`flush()` = deliberate discard, no retention.
- Gate: `audio_evidence` toggle (`SettingsStore.audioEvidenceDefault` ← `BuildConfig.AUDIO_EVIDENCE_DEFAULT`: false release / true debug+harness); privacy hint in Settings; harness wires the store + gate too.
- Tests +18 → suite **462 green** (5 skipped integration tests, as always without `.env`).

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

#122 and #121 are closed (2026-10-11, final reports on the issues); #127 (audio evidence) is implemented and verified (2026-10-11 evening). Next: the owner's on-device dictation checks, then #123 as the next workstream. **With `fullContextAtStop` OFF in release, the fragment join is the ONLY delivery path — the join's dominant residual error is Whisper repetition-loop derailment → #123 (loop guard) is the highest-value follow-up.**

## Pending
- [ ] **#127 on-device spot check (owner, optional)**: one dictation on a debug/harness build → `adb shell ls files/audio/<sessionId>/` shows `session.wav`, `fragments/`, `uploads/`; a second look at the Settings toggle (default off in release).
- [ ] **Owner dictation check (~25 s)** on the release build: `prepare.json` cadence (~10 s nominal, growth ≤ 2S, fallback cuts in the late half), stop→raw ≤ ~2.4 s worst case (finding 3 of the plan), Settings fragment field blank.
- [ ] **#120 re-check (owner)**: > 5-min dictation on the release build → `stt-upload`/`stt-latency` every `durationMs ≤ 300_000`, no mid-text cut-off → then close #120.
- [ ] **#123 loop guard** — filed with the #122 evidence; next workstream.
- [ ] **Unidentified unit-test flake RECURRED** (2026-10-11, during #127 verification — captured signature in PITFALLS: executor exit 2 at 312/462 tests, 0 recorded failures; suite ~1.9× since #86's heap fix). Owner decision pending: raise the test-only `maxHeapSize` (1536m → 2048m)?
- [ ] **#118 Phase B** (delivery policy), **#112/#113** owner items, **#116 Phase 2** remaining owner decisions (auto-size floor now 10 s; shadow diagnostic-only, now debug/harness-only), **#110**, **#101**, **#99/#101/#75**, **#74 marketing**, **#109** visual check.
- [ ] **F-Droid 1.3.6 pickup watch** (tag `v1.3.6` = 10306 already out).

## Device Notes
- `f6de166c` = OnePlus 7T — **clean release 1.3.6 (10306)** (verified: no `DEBUGGABLE`, no harness activity). Corpus + leftover `harness/` artifacts remain under `files/`; `run-as` is dead on it (release, non-debuggable) — prefs were captured 2026-10-11: auto fragment size (no override), `compress_audio=false`, gregor `large-v3` @ `http://10.8.0.16:11437/v1/`, WAV profile `lastAppliedSeconds=10.0`, fit a=0.566/b=0.050, 30 samples.
- `R7SRB06XF5K` = SM-A032F — **clean release 1.3.6** (upgraded from 1.3.4/10304) + the full study corpus (38 WAVs/172 MB incl. `samples.json` + `REPORT.md`) pushed to `files/samples/` via `/data/local/tmp` (see PITFALLS: Samsung blocks adbd's `secure_mkdirs` under the app dir).
- gregor up (2026-10-11): API + SSH respond; serial C=1; the EF runs measured 300-s WAV sessions ≈ 58 s wall (join + shadow).
- Harness A/B tooling (host, NOT in the repo): `/tmp/opencode/seam-run/{run_energyfallback.sh,run_matrix.sh,analyze.py}`; results in `/tmp/opencode/seam-run/results/EF_*`.
- **One unidentified unit-test flake — RECURRED 2026-10-11** (during #127 verification): executor exit 2 at 312/462 tests, 0 failures in any completed XML, died just before `VoiceSessionControllerTest`; single re-run green. Signature matches the #86 executor heap crash; see PITFALLS for the candidate fix (raise test-only `maxHeapSize`).
