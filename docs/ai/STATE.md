# Project State

Current status as of 2026-10-09 (later): **#117 round 2 IMPLEMENTED & pushed (`5c49cc5`) — 21-s fragments + 1-s acoustic pre-roll + echo trim; on-device shadow comparison pending (owner device).**

**#117 round 2 shipped** (`5c49cc5`, pushed; full report in the issue comment 2026-10-09):
- `FRAGMENT_SECONDS` 7 → 21 (672 kB); `preRollBytes` ctor param (1 s / 32 000 B), controller test param `fragmentPreRollBytes` (0 = off in tests).
- `preroll_<i>.<ext>` side files (last 1 s of PCM before fragment start, same format, clamped), written BEFORE the manifest; pre-roll encode failure → `null`, never the WAV fallback; WAV-fallback rebuild re-creates pre-rolls; `recover()` keep-set includes them; hook + `committedFragments()` carry `(index, file, preRoll)` with an `isFile` check.
- `FragmentTranscriber`: upload composed ONCE as `preRoll + fragment` byte-concat (`upload_<i>.<ext>`), both attempts (incl. the HTTP-400 prompt retry — a bare-fragment retry would have dropped the pre-roll) use it, deleted in `finally`; missing pre-roll → bare fragment.
- Echo trim BEFORE storing: conservative token match vs previous transcript tail (head token equal, ≤1 mismatch for k ≥ 2, k ≤ 8; no match keeps everything), only when the upload carried a pre-roll; fully echoed fragment trims to empty; evidence → new `stt-trim.json` stream.
- Two bugs fixed during implementation that the original plan missed: (1) 400-retry bypassed the composed upload; (2) untrimmed echo would have re-conditioned the next fragment's prompt/tail.
- Tests: +12 (pre-roll PCM ranges incl. clamp, ogg+wav modes, hook payload, recover keep-set, fallback rebuild, composition byte-equality + cleanup, trim echo/no-match/single-token/full-echo/no-preroll cases, 400-retry-on-composed-upload). Full suite green (395) + `assembleRelease` green; pre-push hook green.

**Verification pending (owner device):** one dictation vs gregor → `stt-shadow.json` fragmentText vs fullText against the 2026-10-09 baseline (406 vs 415 words; 9-word + 5-word phrase drops; "Pest"→"Best"; 6× "…"). Expected: phrase drops shrink drastically; `prepare.json` shows ~21-s fragments (pcmEnd deltas ≈ 672 000 ± search window); `stt-trim.json` shows echo matches; stop→raw ≤ ~1.5 s. Watch signal: gregor GPU bursts every ~21–23 s during dictation.

Release state unchanged: **v1.3.6 (10306) released**; F-Droid pickup watch open. #112 restore-tap pending; #113 recurrence data pending.

## Current Focus
**Next session: on-device shadow comparison for #117 round 2** (needs the OnePlus attached — the agent host has none by default). If seam quality is acceptable → owner decides close; then #116 Phase 2 (auto-sized fragments 40–60 s + full-context-at-stop).

## Completed (recent cycles)
- [x] **#117 round 2 implemented 2026-10-09 (later)** (`5c49cc5`): 21-s fragments + 1-s pre-roll + echo trim; full report in the issue comment. Open: on-device verify.
- [x] **#117 round 1 verified on-device 2026-10-09** (`400d13f` + `5ef704f` + `8b7f191`): shadow comparison works; seam quality quantified (406/415 words); owner decisions recorded.
- [x] #116 Phase 1 implemented 2026-10-08; #64 CLOSED BY MEASUREMENT; #115 CLOSED; #114/#105 CLOSED; v1.3.5 + v1.3.6 released.

## Pending
- [ ] **#117 round-2 verify (owner + agent)** — `installRelease` → one dictation → shadow comparison vs the 406/415 baseline (see "Verification pending" above).
- [ ] **VoiceSessionControllerTest flake instrumentation** — the watch item recurred TWICE in the round-2 session (`fragment failure fails in polish mode…` in a full-suite run; `pipeline failure parks PAUSED…` in a class-level run — the documented "passes in class-level" no longer holds). Both re-runs green. Instrument the failure branch (see PITFALLS entry).
- [ ] **#116 Phase 2 (agent)** — per-provider profiles: `t(S) ≈ a + b·S` fit over `stt-latency.json` → auto `fragmentSeconds` (40–60 s for fast providers) + concurrency; **full-context-at-stop strategy** for fast providers. Note: `stt-latency` `durationMs`/`bytes` now INCLUDE the 1-s pre-roll (uploaded-size truth — the fit basis; do not "correct" it).
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
- f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — adb IME/secure-setting writes blocked, reads work.
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1) — adb writes WORK.
- **The OnePlus is also the Telegram bridge** — verify the foreground before `input tap`; scrcpy output is VFR — normalize before trimming.
- Owner runs the **local LAN STT server** gregor (`http://10.8.0.16:11437/v1/`, `hwdsl2/whisper-server:cuda`, `large-v3`, SSH alias `gregor`, container name `whisper` — logs via `sudo docker logs whisper`; server code at `/opt/src/api_server.py` in the container; **VAD hardcoded on**, prompt forwarded as `initial_prompt`). Owner watches gregor's GPU spikes: ~21–23-s bursts = live fragment cadence (round 2), one long spike at stop = the shadow pass.
- Log evidence via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` — `adb pull` is BLOCKED (scoped storage); use `adb shell cat` per file (mind stdin-eating in loops, `\r` stripping, and the exact package name). Streams: `stt-upload`, `stt-latency` (per-attempt, `durationMs` + `promptChars`), `prepare` (fragment cadence, `pcmEnd`, `silenceAligned`), `stt-trim` (round 2: `fragment`, `echoTokens`, `uploadWithPreRoll`), `stt-shadow` (gate breadcrumb + fragmentText vs fullText + crash records), `stt-text`, `llm-prompt`/`llm-response`, `ime-lifecycle.log`.
- Owner dictates with **Raw mode on** (no LLM pass — llm-prompt stays stale; don't misread that as a pipeline failure).

## Next Session Suggestion
On-device #117 round-2 verify: `installRelease`, one dictation vs gregor with Raw on, then `adb shell cat` the `stt-shadow.json` / `prepare.json` / `stt-trim.json` streams and compare against the 406/415 baseline. Then (owner verdict) close #117 or iterate; the flake instrumentation is the next code task regardless.
