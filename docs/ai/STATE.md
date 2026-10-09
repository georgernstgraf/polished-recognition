# Project State

Current status as of 2026-10-09 late (**#117 verified on-device; shadow comparison WORKS; owner decided the next round: 21-s fragments + 1-s pre-roll overlap — implementation pending, plan in #117**).

**#117 shipped & verified** (`400d13f` + `5ef704f` + `8b7f191`, pushed): silence-aligned fragment cuts + transcript prompt carry-over + shadow comparison. On-device verification (OnePlus vs gregor, 25-fragment ~3-min dictation):
- Core fixes work: `silenceAligned: true` + `pcmEnd` in `prepare.json`; `promptChars` in `stt-latency.json`; gregor forwards the prompt as faster-whisper `initial_prompt` (server code verified; `vad_filter=True` hardcoded server-side).
- Three verification bugs found & fixed: (1) shadow chunks deleted by `clearPrepared()` before the queued coroutine read them → dedicated `cacheDir/shadow/`; (2) silent crash swallow → error records + gate breadcrumb in `stt-shadow.json`; (3) **`NetworkOnMainThreadException`** — the shadow called `SttRequestRunner` without `Dispatchers.IO` (the runner runs on the CALLER's dispatcher; Robolectric can't catch this).
- **Quantified seam quality** (shadow: fragment-joined 406 vs full-context 415 words): ~4–5% seam loss remains despite the prompt — 9-word + 5-word phrase drops at seams, "Pest"→"Best" degradation, 6× "…" hallucinations. Prompt is a text bias; it cannot add acoustic context.

**Owner decisions 2026-10-09 (NOT yet implemented — plan detailed in the #117 comment "On-device verification result (2026-10-09) + owner decisions"):**
1. **Minimum fragment 21 s** (was 7 s) — ~3× fewer seams; stop-tail worst ≈ 23 s → gregor ≈ 1.2 s, avg ≈ 1.0 s (accepted vs < 1.3 s target); live cadence ~2.9 RPM.
2. **1-s acoustic pre-roll overlap** on fragment uploads (fragment files stay gapless; echoed text trimmed via known-tail token match; conservative = keep duplicates rather than lose words).
3. Phase 2: 40–60-s fragments for fast providers + **full-context-at-stop strategy** (shadow text authoritative where the profile says the provider is fast — GROQ ≈ 200× realtime → < 1 s for 10-min recordings; eliminates seams entirely for cloud).

Release state unchanged: **v1.3.6 (10306) released**; F-Droid pickup watch open. #112 restore-tap pending; #113 recurrence data pending.

## Current Focus
**Next session: implement the owner's #117 round 2** (21-s fragments + pre-roll overlap) — full implementation plan in the #117 comment. Then one on-device shadow round to compare seam quality against today's baseline (406/415 words). Then Phase 2.

## Completed (recent cycles)
- [x] **#117 verified on-device 2026-10-09** (`400d13f` + `5ef704f` + `8b7f191`): shadow comparison works end-to-end; seam quality quantified; residual loss → owner decisions recorded. Trunk clean at `8b7f191` (the 21-s constant edit was reverted before persistence — do NOT assume it is applied).
- [x] **#116 Phase 1 implemented 2026-10-08** (`bd9ba63` + `e9799df` + `04d17d4`): live fragment worker, stage lines, raw rescue. On-device checklist covered by the #117 verify rounds.
- [x] #64 CLOSED BY MEASUREMENT 2026-10-08; #115 CLOSED (owner verify PASSED); #114/#105 CLOSED; v1.3.5 + v1.3.6 released.
- [x] Earlier — see HISTORY.md / tracker.

## Pending
- [ ] **#117 round 2 (agent)** — implement 21-s minimum + 1-s pre-roll overlap per the #117 plan; tests green; `installRelease`; on-device shadow comparison vs baseline.
- [ ] **#116 Phase 2 (agent)** — per-provider profiles: `t(S) ≈ a + b·S` fit over `stt-latency.json` → auto `fragmentSeconds` (40–60 s for fast providers) + concurrency; **full-context-at-stop strategy** for fast providers (shadow mechanism exists, becomes authoritative).
- [ ] **#112 verify (owner)** — tap "Restore Default Prompts", confirm the crafted prompt appears; then close.
- [ ] **#113 freeze recurrence (owner)** — capture: stage line, X-tap vs system-back, `ime-lifecycle.log` + STT/LLM timestamps.
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
- Owner runs the **local LAN STT server** gregor (`http://10.8.0.16:11437/v1/`, `hwdsl2/whisper-server:cuda`, `large-v3`, SSH alias `gregor`, container name `whisper` — logs via `sudo docker logs whisper`; server code at `/opt/src/api_server.py` in the container; **VAD hardcoded on**, prompt forwarded as `initial_prompt`). Owner watches gregor's GPU spikes: 7–9-s bursts = live fragment cadence, one long spike at stop = the shadow pass.
- Log evidence via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` — `adb pull` is BLOCKED (scoped storage); use `adb shell cat` per file (mind stdin-eating in loops, `\r` stripping, and the exact package name). Streams: `stt-upload`, `stt-latency` (per-attempt, `durationMs` + `promptChars`), `prepare` (fragment cadence, `pcmEnd`, `silenceAligned`), `stt-shadow` (gate breadcrumb + fragmentText vs fullText + crash records), `stt-text`, `llm-prompt`/`llm-response`, `ime-lifecycle.log`.
- Owner dictates with **Raw mode on** (no LLM pass — llm-prompt stays stale; don't misread that as a pipeline failure).

## Next Session Suggestion
Implement the #117 round-2 plan (21-s fragments + pre-roll overlap) from the issue comment; unit tests first (FragmentPreparer pre-roll files + recover keep-set, FragmentTranscriber composition + trim), then on-device shadow comparison vs the 406/415 baseline. Owner watch signal: gregor GPU spikes every ~21–23 s during dictation.
