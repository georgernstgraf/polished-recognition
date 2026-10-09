# Project State

Current status as of 2026-10-10 (later): **#116 Phase 2 CORE SHIPPED (`f152cd9` + `9453658`) — Theil–Sen per-provider latency profiles, auto fragment sizing (stepwise ±50 %), full-context-at-stop with the gregor-safe gate, Settings override field. #117 CLOSED; the #117 test-flake watch item ROOT-CAUSED and FIXED (real ordering bug: failure branch published PAUSED before the snapshot). Next: owner on-device verify of Phase 2 + C=1-vs-concurrency decision.**

**Closure verdicts (2026-10-10):** duplicated "Panikmache" phrase at the fragment 1→2 seam — owner cannot confirm; accepted harmless by construction (real repetition kept verbatim → LLM polish dedupes in polish mode; raw mode is verbatim by design; trim correctly never fired, `echoTokens=0`). Stop→raw ≈ 2.2 s measured = the tail chunk's round-trip (only chunk paid at send; tail bounded ~23 s, near-worst case this session; gregor variance 0.7–2.3 s for identical lengths) — accepted; Phase 2 auto-sizing owns the fix.

**Round-2 on-device verification** (report in the #117 comment 2026-10-09/10):
- 4 fragments / ~85 s under TV audio: 3 hard cuts at exactly 672 000 B (background RMS defeats silence search → graceful fallback, expected) + 1 silence-aligned cut (21.76 s).
- Pre-roll on the wire confirmed (uploads 96.8–100 kB vs 91.2 kB bare; `promptChars` 278/378/380).
- `stt-trim.json`: `echoTokens=0` everywhere — no false trims. The one fragment-extra phrase ("Es geht nicht um Panikmache", 5 words, wording differs from the tail) sits at the fragment 1→2 seam; too long/modified for a 1-s pre-roll echo — likely real speech the full-context Whisper collapsed. Owner confirmation pending.
- **Latency caveat**: stop→raw ≈ 2.2 s (tail 21.7 s audio, gregor elapsed 2166 ms) vs the 1.3-s target; session variance (fragment 0: 0.7 s vs fragments 1–3: 2.2–2.3 s for identical lengths). Phase 2's auto-sizer would land gregor at ~12–14-s fragments with this measured slope.

Release state unchanged: **v1.3.6 (10306) released**; F-Droid pickup watch open. #112 restore-tap pending; #113 recurrence captured (see Pending).

## 2026-10-10 movie-session data point (16-min recording, 47 fragments, raw mode)

Long-form stress test of the round-2 pipeline (owner played a ~15-min movie into the mic, 23:55:46–00:12:07 CEST, 47 fragments ≈ 971 s):

- **Pipeline clean**: 47/47 fragments uploaded, all `attempt: 1` / HTTP 200, `silenceAligned: true` throughout, echo trim quiet (`echoTokens` 0 except 7 on frag 30, 1 on frag 27), tail round-trip 3.3 s (19 KB / ~3.3 s tail). No LLM traffic (raw mode — `llm-*` stale from 22:24, expected).
- **Shadow full-context pass COLLAPSED on gregor**: 588.4-s chunk → 12.8 s after VAD (garbled, `no_speech_prob` 0.64); 382.9-s chunk → 47.6 s after VAD, only the last ~61 s transcribed. Fragment concat **2310 chars** vs full pass **1021 chars** — the first ~9 min vanished. **Implication for #116 Phase 2: full-context-at-stop is unusable on gregor-style VAD servers for long recordings; fragment sizing is correctness-critical there.** (Details in PITFALLS.)
- Language misdetected `nn` (Norwegian Nynorsk, 0.61) on the assembled live text — audio is English; dialogue chunks still transcribed fine. One music hallucination at the credits tail entered the fragment text (not the full pass).
- IME: **no freeze during the movie session**; a FREEZE recurrence was captured at 22:18:36 CEST in the earlier dictation session (evidence posted to #113).

## Current Focus
**#116 Phase 2 verification** — the core is shipped (profile fit + auto sizing + full-context-at-stop + override UI, see DECISIONS 2026-10-10); what remains is the owner on-device verify and the C=1-vs-concurrency decision (prompt carry-over conflict, flagged on the issue). The test-flake watch item is CLOSED (root-caused, fixed, relocated to HISTORY).

## Completed (recent cycles)
- [x] **#117 CLOSED 2026-10-10** — round 2 implemented (`5c49cc5`) + verified on-device (noisy-condition A/B: 0 dropped words, 0 hallucinations, fragment ⊇ full); verdicts resolved (duplicated phrase harmless by construction; 2.2-s tail latency accepted, Phase 2 owns sizing). Full history: `400d13f` + `5ef704f` + `8b7f191` + `5c49cc5`.
- [x] **#117 round 1 verified 2026-10-09** (`400d13f` + `5ef704f` + `8b7f191`): shadow comparison works; seam loss quantified (406/415); owner decisions recorded.
- [x] #116 Phase 1 implemented 2026-10-08; #64 CLOSED BY MEASUREMENT; #115 CLOSED; #114/#105 CLOSED; v1.3.5 + v1.3.6 released.

## Pending
- [ ] **#116 Phase 2 on-device verify (owner)** — core shipped (`f152cd9` + `9453658`); auto-sizing engages after ≥ 12 samples (~4 min dictation at 21 s; profiles start EMPTY, no retro-fit of old logs). Acceptance: `stt-upload` cadence at the auto-derived size, stop→raw ≤ 1.3 s (gregor) / ≤ 1 s (GROQ), Settings field round-trip (blank = auto, 7–60 s), `stt-shadow` `fullContextAtStop` records on fast-provider sessions. Watch: the derived gregor size may differ from the predicted 12–14 s — the fit decides.
- [ ] **C=1 vs concurrency (owner decision, on #116)** — concurrency auto-detection was NOT implemented: prompt carry-over + echo trim need fragment i−1's transcript at upload time (serial-worker guarantee); raising C breaks seam conditioning unless prompts are disabled. Flagged on the issue.
- [ ] **#112 verify (owner)** — tap "Restore Default Prompts", confirm the crafted prompt appears; then close.
- [ ] **#113 freeze recurrence (owner)** — stage line, X-tap vs system-back. Recurrence captured 2026-10-09 22:18:36 CEST (evidence on the issue; `restarting=true state=RECORDING outcome=FREEZE` path).
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
Owner on-device verify of Phase 2 (see Pending) — then #112/#113 owner items, or pick up whatever the verification surfaces. The completed items (#117, flake) are closed; do not re-open them.
