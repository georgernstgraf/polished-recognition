# Hand Off

**2026-10-11 (evening) — #127 (per-session audio-evidence retention) is IMPLEMENTED and verified: ring-buffered `files/audio/<sessionId>/` (`session.wav`, `fragments/`, `uploads/`) behind the `audio_evidence` Settings toggle (default OFF in release, ON in debug/harness via `BuildConfig.AUDIO_EVIDENCE_DEFAULT`), copy-only, suite 462 green, `assembleRelease` + `compileHarnessKotlin` green. #127 stays open for the parent epic's close-out (optional owner on-device spot check). The unit-test flake RECURRED once during verification — captured signature in PITFALLS/STATE (executor exit 2 at 312/462, 0 recorded failures; candidate fix: raise the test-only maxHeapSize — owner decision). Otherwise unchanged from the afternoon: #122/#121 closed, config freeze shipped, both phones on clean release 1.3.6.**

## Where things stand

- Frozen production config shipped: fragment auto (10-s floor), WAV, `FORCED_ONLY`, bounds 10–60 s, search = max(2 s, S) → spans [S, 2S], quietest-frame forced-cut fallback in [1.5S, 2S] (`energyFallbackEnabled`), pre-roll/prompt 1 s / ≤96 chars word-snapped (forced seams only), `fullContextAtStop` OFF in release (REVERSAL of #116 Phase 2 — see DECISIONS 2026-10-11), shadow OFF in release (`BuildConfig` gates; `SttEndpointPolicy` deleted; full-context events → `stt-fullcontext`).
- **#127 audio evidence (new):** `pipeline/AudioEvidenceStore` (pure JVM ring buffer, ≤ 3 sessions / ≤ 50 MB, newest survives) + retention in `VoiceSessionController` (session.wav + fragments, both success and failure, before `clearPrepared()`) and `FragmentTranscriber` (composed uploads, before the `finally` delete). Cancel/flush = deliberate discard, no retention. Harness wires it too.
- Energy-fallback A/B (harness, gregor): search=2 vs search=S → err/100w 6.2 → 4.1, 68→52 frags, 3→0 forced seams; the fallback never fired on Tier-1 (both arms identical) — dormant safety net, validated at unit level.
- Suite **462 green** (+18 for #127); `assembleRelease` + `compileHarnessKotlin` green; the unidentified pre-push flake recurred once (see PITFALLS/STATE).

## Open tasks

1. [ ] **#123 loop guard (NEXT WORKSTREAM)** — detect a repeated n-gram in a fragment transcript and truncate it BEFORE it is stored/prompted. With `fullContextAtStop` off, the fragment join is the ONLY delivery path — its dominant residual error (Whisper repetition-loop derailment) is the ceiling of output quality. Evidence + spec in the #123 body (filed from the #122 study).
2. [ ] **#127 on-device spot check (owner, optional)**: one dictation on a debug/harness build → `adb shell ls /sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/audio/<sessionId>/` shows `session.wav`, `fragments/`, `uploads/`; toggle visible in Settings, off by default in release.
3. [ ] **Unit-test flake follow-up (owner decision)**: raise the test-only `maxHeapSize` (1536m → 2048m)? Signature captured in PITFALLS (exit 2 at 312/462, 0 recorded failures — the #86 executor-crash shape).
4. [ ] **Owner dictation check (~25 s)** on the release build (OnePlus or Samsung): `prepare.json` cadence (~10 s nominal, growth ≤ 2S, fallback cuts in the late half), stop→raw latency (worst ~1.6–2.4 s accepted, typical ~0.8–1.0 s — revisit if > 2 s), Settings fragment field blank.
5. [ ] **#120 re-check (owner)** — corrected method: > 5-min dictation on the RELEASE build (the shadow is gone from release), then confirm from `stt-upload`/`stt-latency` that every `durationMs ≤ 300_000` and the delivered text has no mid-text cut-off → close #120.
6. [ ] **#118 Phase B** — delivery policy (`InsertionDecision` → clipboard + toast).
7. [ ] **#116 Phase 2** owner decisions residue — auto-size floor now 10 s; shadow now debug/harness-only.
8. [ ] **#112** verify "Restore Default Prompts" (owner); **#113 freeze recurrence (owner)**.
9. [ ] **F-Droid 1.3.6 pickup watch**; **#74 Phase 1 posting / Play-alpha recruitment**; **#109** notification-icon visual check; **#99/#101/#75**.

## Known on-device gotchas

- **Devices:** `f6de166c` = OnePlus 7T (clean release 1.3.6); `R7SRB06XF5K` = Samsung A03 (clean release 1.3.6 + full corpus). Pin `ANDROID_SERIAL`/`-s` — both attached. Oplus blocks adb IME/secure-setting writes (reads OK); USB MIDI mode hides the device.
- **Samsung/Android 13 blocks adbd's recursive mkdir under `/sdcard/Android/data/<pkg>/`** — push via `/data/local/tmp` + `adb shell cp -a` (see PITFALLS). `adb push dir dest/` creates a `dir/` SUBDIR — push `dir/.` for contents.
- **Logs:** `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/` — `adb pull` BLOCKED; use `adb shell cat` per file (strip `\r`, redirect `< /dev/null` in loops). Streams: `stt-upload`, `stt-latency`, `prepare`, `stt-trim`, `stt-fragment`, `stt-shadow` (harness/debug only), `stt-fullcontext` (harness/debug only), `stt-text`, `llm-*`, `insertion`, `ime-lifecycle.log`. Harness results: `files/harness/latest.json`.
- **`run-as` is dead on the OnePlus** (release, non-debuggable) — device prefs were captured 2026-10-11 and are recorded in STATE.md.
- **gregor** (SSH alias `gregor`, container `whisper`, `hwdsl2/whisper-server:cuda`, `large-v3`): strictly serial (C=1); `vad_filter=True` hardcoded; repetition-loops on repetitive/musical/noisy content in BOTH join and shadow — loop-mask before any join↔shadow diff (analyzer: `/tmp/opencode/seam-run/analyze.py`).
- **Agent host has no attached device by default** — device-only checks are delegated to the owner.

Last cleared: 2026-10-11 (evening) (**#127 audio-evidence retention implemented + verified, docs persisted; flake recurrence captured; #123 remains the next workstream**).
