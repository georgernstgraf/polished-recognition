# Project State

Current status as of 2026-10-10 (early): **#117 CLOSED — round 2 (21-s fragments + 1-s acoustic pre-roll + echo trim, `5c49cc5`) verified on-device: 0 dropped words (round-1 baseline −9), 0 "…" hallucinations (baseline 6×), fragment text ⊇ full text, noisy-condition run accepted as a scenario. Next: #116 Phase 2 (owns fragment sizing — fixes the 2.2-s gregor tail) + flake instrumentation.**

**Closure verdicts (2026-10-10):** duplicated "Panikmache" phrase at the fragment 1→2 seam — owner cannot confirm; accepted harmless by construction (real repetition kept verbatim → LLM polish dedupes in polish mode; raw mode is verbatim by design; trim correctly never fired, `echoTokens=0`). Stop→raw ≈ 2.2 s measured = the tail chunk's round-trip (only chunk paid at send; tail bounded ~23 s, near-worst case this session; gregor variance 0.7–2.3 s for identical lengths) — accepted; Phase 2 auto-sizing would shrink gregor to ~12–14-s fragments (tail ≈ 1.3 s).

**Round-2 on-device verification** (report in the #117 comment 2026-10-09/10):
- 4 fragments / ~85 s under TV audio: 3 hard cuts at exactly 672 000 B (background RMS defeats silence search → graceful fallback, expected) + 1 silence-aligned cut (21.76 s).
- Pre-roll on the wire confirmed (uploads 96.8–100 kB vs 91.2 kB bare; `promptChars` 278/378/380).
- `stt-trim.json`: `echoTokens=0` everywhere — no false trims. The one fragment-extra phrase ("Es geht nicht um Panikmache", 5 words, wording differs from the tail) sits at the fragment 1→2 seam; too long/modified for a 1-s pre-roll echo — likely real speech the full-context Whisper collapsed. Owner confirmation pending.
- **Latency caveat**: stop→raw ≈ 2.2 s (tail 21.7 s audio, gregor elapsed 2166 ms) vs the 1.3-s target; session variance (fragment 0: 0.7 s vs fragments 1–3: 2.2–2.3 s for identical lengths). Phase 2's auto-sizer would land gregor at ~12–14-s fragments with this measured slope.

Release state unchanged: **v1.3.6 (10306) released**; F-Droid pickup watch open. #112 restore-tap pending; #113 recurrence data pending.

## Current Focus
**#116 Phase 2** (per-provider auto-sizing — fixes the 2.2-s gregor tail with the freshly measured samples; full-context-at-stop for fast providers) and the **VoiceSessionControllerTest flake instrumentation**. #117 closed.

## Completed (recent cycles)
- [x] **#117 CLOSED 2026-10-10** — round 2 implemented (`5c49cc5`) + verified on-device (noisy-condition A/B: 0 dropped words, 0 hallucinations, fragment ⊇ full); verdicts resolved (duplicated phrase harmless by construction; 2.2-s tail latency accepted, Phase 2 owns sizing). Full history: `400d13f` + `5ef704f` + `8b7f191` + `5c49cc5`.
- [x] **#117 round 1 verified 2026-10-09** (`400d13f` + `5ef704f` + `8b7f191`): shadow comparison works; seam loss quantified (406/415); owner decisions recorded.
- [x] #116 Phase 1 implemented 2026-10-08; #64 CLOSED BY MEASUREMENT; #115 CLOSED; #114/#105 CLOSED; v1.3.5 + v1.3.6 released.

## Pending
- [ ] **#116 Phase 2 (agent)** — per-provider profiles: `t(S) ≈ a + b·S` fit over `stt-latency.json` → auto `fragmentSeconds` + concurrency; **full-context-at-stop strategy** for fast providers. `stt-latency` `durationMs`/`bytes` INCLUDE the 1-s pre-roll (uploaded-size truth — correct fit basis; do not "correct" it). Real 21-s gregor samples now exist; the fit would shrink gregor to ~12–14-s fragments (tail ≈ 1.3 s).
- [ ] **VoiceSessionControllerTest flake instrumentation (agent)** — the watch item recurred twice in the round-2 session incl. a CLASS-LEVEL run (`pipeline failure parks PAUSED…`, compress-OFF path); both re-runs green. Instrument the failure branch (see PITFALLS).
- [ ] **#112 verify (owner)** — tap "Restore Default Prompts", confirm the crafted prompt appears; then close.
- [ ] **#113 freeze recurrence (owner)** — stage line, X-tap vs system-back, `ime-lifecycle.log` + STT/LLM timestamps.
- [ ] **F-Droid 1.3.6 pickup watch (automatic)**.
- [ ] **#74 Phase 1 posting (owner)** — r/fossdroid, r/degoogle (Showcase), r/selfhosted (modmail), kuketz, Facebook (#108); welcome message into the Google Group.
- [ ] **#74 Play-alpha recruitment (owner)** — 20–30 testers, ≥12 × 14 days.
- [ ] **#109 visual check (owner)** — notification mic icon variant B.
- [ ] **#99, #101 (owner decisions)**; **#75** rate-limit header logging; insertion-spacing watch — passive.

## Blockers
None.

## Device Notes
- f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — adb IME/secure-setting writes blocked, reads work. USB mode gotcha: `18d1:4ee8` (MIDI mode) is invisible to adb — switch USB mode to file transfer / keep USB debugging on.
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1) — adb writes WORK.
- **The OnePlus is also the Telegram bridge** — verify the foreground before `input tap`; scrcpy output is VFR — normalize before trimming.
- Owner runs the **local LAN STT server** gregor (`http://10.8.0.16:11437/v1/`, `hwdsl2/whisper-server:cuda`, `large-v3`, SSH alias `gregor`, container name `whisper` — logs via `sudo docker logs whisper`; server code at `/opt/src/api_server.py` in the container; **VAD hardcoded on**, prompt forwarded as `initial_prompt`). GPU spikes: ~21–23-s bursts = live fragment cadence (round 2), one long spike at stop = the shadow pass. Measured 2026-10-09: ~2.2–2.3 s per 21-s upload (fragment 0 outlier 0.7 s).
- Log evidence via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` — `adb pull` is BLOCKED (scoped storage); use `adb shell cat` per file (mind stdin-eating in loops, `\r` stripping, and the exact package name). Streams: `stt-upload`, `stt-latency` (`durationMs` + `promptChars`), `prepare` (`pcmEnd`, `silenceAligned`), `stt-trim` (`fragment`, `echoTokens`, `uploadWithPreRoll`), `stt-shadow` (gate breadcrumb + fragmentText vs fullText + crash records), `stt-response` (per-request verbose responses — newest = shadow pass, rotation covers the fragments), `stt-text`, `llm-prompt`/`llm-response`, `ime-lifecycle.log`.
- Owner dictates with **Raw mode on** (no LLM pass — llm-prompt stays stale; don't misread that as a pipeline failure).

## Next Session Suggestion
Start #116 Phase 2 (per-provider auto-sizing — fixes the 2.2-s gregor tail with the freshly measured t(S) samples) or the small flake-instrumentation task first; both are fully specified in HANDOFF.md.
