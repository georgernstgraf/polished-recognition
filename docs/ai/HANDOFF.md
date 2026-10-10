# Hand Off

**2026-10-10 (night) — #122 seam study COMPLETE (Tier 1+2, 126 runs analyzed). Read STATE.md for the findings.** The headline: the seam mechanics are largely NULL levers (overlap size, fragment size, prompt length all show no effect); the dominant damage is Whisper **repetition-loop / whole-fragment derailment**, present in BOTH the fragment join and the shadow. One mechanical lever survived: do NOT apply the pre-roll/prompt at silence-aligned seams (shipped as `SeamPolicy.FORCED_ONLY`). Next session's decision: accept the nulls + build a **loop guard**, or extend the corpus for a stronger verdict.

The seam study was driven by a `harness` build variant (never shipped) that injects a WAV through the production restore seam — see STATE.md "Device Notes" for the exact adb invocation and the host scripts in `/tmp/opencode/seam-run/`.

## Where things stand (post-#122 code, all on `main`)

- `e941430` #120 5-min STT cap (300 s); `7862603` seam policy + 10 s floor + seam-local prompt; `b401fbc` SeamOverlap + `stt-fragment.json`; `a222874` harness variant; `344ec69` per-fragment PCM size; prompt-window knob wired.
- 126 study runs + Tier-1/2 analysis complete; runner + analyzer are host scratch (`/tmp/opencode/seam-run/`, not committed).
- The OnePlus `f6de166c` currently runs the **harness build** — reinstall release (`./gradlew installRelease`) when the study is done.

## Open tasks

1. [ ] **#122 owner decision (NEXT SESSION):** accept the null results (pre-roll size / fragment size / prompt length have no measurable accuracy effect; the damage is model loops) and prioritise a **loop guard** (truncate a repeated n-gram inside a fragment BEFORE it becomes the next fragment's prompt), or extend the corpus (host-side independent reference; more verified-English classes). Background + numbers in STATE.md and the #122 comments.
2. [ ] **#120 on-device re-check** — 300 s cap shipped + unit-tested; confirm the shadow no longer expands on-device (then close #120).
3. [ ] **#121 (parent)** — decide close-vs-keep once the loop-guard outcome is known; never close while #122 is open.
4. [ ] **#122 corpus caveat** — the "en_" classes are largely German; if a stronger language-controlled verdict is needed, re-select EN sources (needs real listening).
5. [ ] **#116 Phase 2 owner decisions** — the auto-size floor is now 10 s; shadow is diagnostic-only (unreliable on long/noisy audio).
6. [ ] **#118 Phase B** — delivery policy (InsertionDecision → clipboard + toast).
7. [ ] **#112 verify (owner)** — "Restore Default Prompts"; **#113 freeze recurrence (owner)**.
8. [ ] **F-Droid 1.3.6 pickup watch**; **#74 Phase 1 posting / Play-alpha recruitment**; **#109** notification-icon visual check; **#99/#101/#75**.

## Known on-device gotchas

- **Devices:** `f6de166c` = OnePlus 7T (currently the harness build); `d890cc9e` = S5 (LineageOS 18.1). Oplus blocks adb IME/secure-setting writes; reads work. USB MIDI mode (`18d1:4ee8`) hides the device from adb.
- **Logs:** `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/` — `adb pull` BLOCKED; use `adb shell cat` per file (strip `\r`, redirect `< /dev/null` in loops). Streams: `stt-upload`, `stt-latency`, `prepare`, `stt-trim`, `stt-fragment` (per-fragment array), `stt-shadow`, `stt-text`, `llm-*`, `insertion`, `ime-lifecycle.log`. Harness results: `files/harness/latest.json`.
- **gregor** (SSH alias `gregor`, container `whisper`, `hwdsl2/whisper-server:cuda`, `large-v3`): strictly serial; no request queue from the app (C=1). `vad_filter=True` hardcoded → shadow collapses on long/noisy uploads. **Whisper repetition-loops on repetitive/musical/noisy content in BOTH paths** — any join↔shadow metric must loop-mask first.
- **Agent host has no attached device by default** — device-only checks are delegated to the owner.

Last cleared: 2026-10-10 (**#122 seam study complete; Tier 1+2 analyzed; findings + null results in STATE.md**).
