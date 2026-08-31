# Project State

Current status as of 2026-08-31 (round 4, post root-cause hunt).

## Current Focus
#57 pulse root-caused and fixed (AudioRecorder RMS NaN — latent since day one); #56 quick-settings-during-recording shipped. Awaiting on-device verification of pulse + RMS calibration. Language non-compliance documented (no prompt changes per user decision). Open: #55 (release v1.2.0), #43 (tracker), #56 (verify + language follow-up), #57 (verify).

## Completed (this cycle)
- [x] #57 round 4: `computePcmRms` sign-correct + Long accumulation (NaN root cause; 7 regression tests); single-owner pulse (breath animator writes `max(breath, voice)` directly; chase removed; NaN guard) (`8d56d20`).
- [x] #56: quick settings (spinner + Raw) enabled during RECORDING (inside `8d56d20`).
- [x] Language evidence gathered from device logs: clause present in 10/10 prompts; model compliance 7/10; failures clustered German-source. No prompt changes (user decision).

## Pending
- [ ] **#57 on-device verify**: dictate once → confirm breathing during silence + brightening with speech; `adb logcat -d -s PolishedRMS` now shows finite rms (calibrate `RMS_CEILING` if speech saturates / ambient exceeds the 200 gate); remove the temporary `Log.d("PolishedRMS")` after calibration.
- [ ] **#56 on-device verify**: RAW + language toggleable mid-recording and mid-pause; changes take effect for the transcription.
- [ ] #56 language follow-up (user decides later): quantify flaky compliance (repeat dictations per language); optional model A/B in Settings; deferred mitigation = directive in user message.
- [ ] **#55 — release `v1.2.0`**: tag (push tag separately) → `release.yml` → fdroiddata bump → MR !40029 comment + force-push branch.
- [ ] #45 leftover: CrashDialog Copy-button on-device test.

## Blockers
None.

## Next Session Suggestion
User dictates once: verify pulse + pull PolishedRMS logcat for calibration; then #55 release tagging (v1.2.0 now includes all #57 rounds + #56 fix).
