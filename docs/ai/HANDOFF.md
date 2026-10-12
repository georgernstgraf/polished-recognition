# Hand Off

**2026-10-12 — #126 (silence-quality metric) is IMPLEMENTED and verified: `audio/SilenceQuality` (adaptive axis `X = k·M + c`, deficit-area Q, margin-clamped area-centroid cut, measured on the 80-Hz-high-passed LATE-half span) replaces the deleted `SilenceCutter`; `prepare.json` carries the `{M, min, max, X, Q, centroidMs, stitched:false}` contract block; suite 465 green, `assembleRelease` + `compileHarnessKotlin` green, pushed as `7ff3800` (+ docs commit). #128 (stitching gate `qualityMin`) and #129 (harness knobs + the research pass for the real k/c/K values) are the follow-ups. The unit-test flake hit a THIRD recorded occurrence during the push (exit 2 at 318/465, 0 failures, re-run green — maxHeapSize decision still pending with the owner). Otherwise unchanged from 2026-10-11: #127 shipped (issue open for the epic close-out), config freeze live, both phones on clean release 1.3.6.**

## Where things stand

- **#126 shipped (`7ff3800`):** production seam path = `SilenceQuality` over the late-half search span (owner decision), cut at the best candidate's area centroid with a 150-ms edge margin, no candidate → forced quietest-frame cut; `silenceAligned := Q > 0` (interim — #128's `qualityMin` replaces it); PROVISIONAL axis defaults 0.65 / 100.0 / 150 ms / lateHalf (harness-only knobs, #129 decides the real values); `energyFallbackEnabled` inert; `SilenceCutter` deleted. Whisper-regime finding recorded in DECISIONS: a 6-dB whisper band is the threading regime (shallow candidate, Q ≈ 2.3), NOT the forced path — the forced path needs a tight band with `M ≥ c/(1−k) ≈ 286`.
- Frozen production config shipped: fragment auto (10-s floor), WAV, `FORCED_ONLY`, bounds 10–60 s, search = max(2 s, S) → spans [S, 2S], pre-roll/prompt 1 s / ≤96 chars word-snapped (forced seams only), `fullContextAtStop` OFF in release, shadow OFF in release (`BuildConfig` gates).
- **#127 audio evidence:** `pipeline/AudioEvidenceStore` ring buffer (≤ 3 sessions / ≤ 50 MB) + retention in `VoiceSessionController` and `FragmentTranscriber`; cancel/flush = deliberate discard.
- Suite **465 green** (+14/−11 for #126); `assembleRelease` + `compileHarnessKotlin` green (harness needed zero changes).

## Open tasks

1. [ ] **#128 stitching gate (NEXT)** — pre-roll + prompt + echo trim when `Q < qualityMin`; fills the `stitched` placeholder in `prepare.json`; `SeamPolicy` becomes a harness A/B arm only.
2. [ ] **#129 harness knobs + research pass** — expose `axisK`/`axisC`/`qualityMin`/`marginK`/search-span as harness extras, drop the inert `energyFallback`, and measure the real k/c/K/Q_min on retained audio + the corpus (Samsung A03 vs gregor).
3. [ ] **#123 loop guard** — detect a repeated n-gram in a fragment transcript and truncate it BEFORE it is stored/prompted (evidence + spec in the #123 body).
4. [ ] **#127 on-device spot check (owner, optional)**: one dictation on a debug/harness build → `adb shell ls /sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/audio/<sessionId>/` shows `session.wav`, `fragments/`, `uploads/`; toggle visible in Settings, off by default in release.
5. [ ] **Unit-test flake follow-up (owner decision)**: raise the test-only `maxHeapSize` (1536m → 2048m)? THIRD recorded occurrence 2026-10-12 (exit 2 at 318/465, 0 recorded failures — the #86 executor-crash shape).
6. [ ] **Owner dictation check (~25 s)** on the release build: `prepare.json` cadence (~10 s nominal, growth ≤ 2S) with the new contract block, stop→raw latency (worst ~1.6–2.4 s accepted, typical ~0.8–1.0 s), Settings fragment field blank.
7. [ ] **#120 re-check (owner)** — > 5-min dictation on the RELEASE build, then confirm from `stt-upload`/`stt-latency` that every `durationMs ≤ 300_000` → close #120.
8. [ ] **#118 Phase B** — delivery policy (`InsertionDecision` → clipboard + toast).
9. [ ] **#116 Phase 2** owner decisions residue — auto-size floor now 10 s; shadow now debug/harness-only.
10. [ ] **#112** verify "Restore Default Prompts" (owner); **#113 freeze recurrence (owner)**.
11. [ ] **F-Droid 1.3.6 pickup watch**; **#74 Phase 1 posting / Play-alpha recruitment**; **#109** notification-icon visual check; **#99/#101/#75**.

## Known on-device gotchas

- **Devices:** `f6de166c` = OnePlus 7T (clean release 1.3.6); `R7SRB06XF5K` = Samsung A03 (clean release 1.3.6 + full corpus). Pin `ANDROID_SERIAL`/`-s` — both attached. Oplus blocks adb IME/secure-setting writes (reads OK); USB MIDI mode hides the device.
- **Samsung/Android 13 blocks adbd's recursive mkdir under `/sdcard/Android/data/<pkg>/`** — push via `/data/local/tmp` + `adb shell cp -a` (see PITFALLS). `adb push dir dest/` creates a `dir/` SUBDIR — push `dir/.` for contents.
- **Logs:** `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/` — `adb pull` BLOCKED; use `adb shell cat` per file (strip `\r`, redirect `< /dev/null` in loops). Streams: `stt-upload`, `stt-latency`, `prepare` (now with the #126 contract block), `stt-trim`, `stt-fragment`, `stt-shadow` (harness/debug only), `stt-fullcontext` (harness/debug only), `stt-text`, `llm-*`, `insertion`, `ime-lifecycle.log`. Harness results: `files/harness/latest.json`.
- **`run-as` is dead on the OnePlus** (release, non-debuggable) — device prefs were captured 2026-10-11 and are recorded in STATE.md.
- **gregor** (SSH alias `gregor`, container `whisper`, `hwdsl2/whisper-server:cuda`, `large-v3`): strictly serial (C=1); `vad_filter=True` hardcoded; repetition-loops on repetitive/musical/noisy content in BOTH join and shadow — loop-mask before any join↔shadow diff (analyzer: `/tmp/opencode/seam-run/analyze.py`).
- **Agent host has no attached device by default** — device-only checks are delegated to the owner.

Last cleared: 2026-10-12 (**#126 silence-quality metric implemented + verified + pushed, docs persisted; flake third occurrence captured; #128/#129 next, #123 queued**).
