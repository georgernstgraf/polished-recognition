# Project State

Current status as of 2026-10-09 (**#117 fragment seam quality IMPLEMENTED — silence-aligned cuts + prompt carry-over + shadow comparison; on-device verify pending**): commit `400d13f`, 376 tests green, `assembleRelease` green, pushed. **Problem (owner observation):** words consistently dropped at the fixed 7-s fragment seams vs gregor — hard cuts split words mid-word and every fragment was transcribed cold. **Fixes:** (1) new `audio/SilenceCutter` + reworked `FragmentPreparer`: cut points search forward (default 2 s) for a ≥40 ms silence run and cut at its center (hard-cut fallback on continuous speech; encoder waits for the window rather than splitting a word; streams stay gapless; manifest entries carry PCM `end` offsets, legacy manifests wiped + re-encoded once; chunk assembly byte-capped via `min(25 MB, 600 s)`). (2) `FragmentTranscriber` conditions each fragment on the previous transcript tail (~200 tokens) via the multipart `prompt` field — no dedup needed; HTTP 400 on a prompted request retries once without it + disables prompts for the session. (3) **`stt-shadow.json`**: on LAN/local STT endpoints a fire-and-forget full-context STT of the assembled recording logs `fragmentText` vs `fullText` after result delivery (evidence off — Phase 2 measurement streams unpolluted). Phase 2 per-provider auto-sizer compatibility guaranteed (nominal size is a parameter, cap is size-based, completion records carry `promptChars`).

Release state unchanged from 2026-10-08: **v1.3.6 (10306) released**; F-Droid pickup watch open. **#115 CLOSED** (owner on-device test PASSED); **#105 + #114 CLOSED**; #112 restore-tap pending; #113 freeze needs recurrence data (PROCESSING now shrinks to tail+LLM, defusing its prime suspect).

## Current Focus
**#117 on-device verification (owner, OnePlus f6de166c):** `installRelease`, long dictation vs gregor → read `logs/stt-shadow.json` (joined fragment transcripts should match the full-context text — no seam drops); `prepare.json` shows `silenceAligned` cuts + `pcmEnd` offsets; stage line `Ns → STT` ≤1.3 s after stop-tap unchanged. This verify also covers #116 Phase 1's checklist. Then #116 Phase 2 (per-provider profiles: robust `t(S) ≈ a + b·S` fit over `stt-latency.json` → auto `fragmentSeconds = clamp((T − 0.3 − 1.5a)/b, 7, 60)` + concurrency auto-detect — the #117 groundwork keeps it unblocked).

## Completed (recent cycles)
- [x] **#117 seam fix implemented 2026-10-09** (`400d13f`): SilenceCutter + silence-aligned FragmentPreparer cuts + prompt carry-over (400-fallback) + byte-capped chunk assembly + LAN-gated shadow comparison in `stt-shadow.json`. 376 tests green (+19 net: SilenceCutterTest ×7, FragmentPreparerTest ×7, FragmentTranscriberTest ×3, SttRequestRunnerTest ×3, minus test-shape updates). **On-device verify pending (owner).**
- [x] **#116 Phase 1 implemented 2026-10-08** (`bd9ba63` + `e9799df` + `04d17d4`): STT retry ×3 + `stt-latency.json` completion records (`AudioDuration`); live fragment worker (C=1, ordered, failure-isolated, cached transcripts, `resetFailures`); controller stop flow = tail → drain → LLM; stage lines `Ns → STT` / `N words → LLM`; raw rescue. **On-device verify pending (owner).**
- [x] #64 CLOSED BY MEASUREMENT 2026-10-08 — parallel chunk uploads rejected: gregor serializes, GROQ already ~200×. Evidence comment on #64; no code.
- [x] #115 fragment streaming (`5363a38`), owner on-device verify 20:46 PASSED; #115 CLOSED.
- [x] #114/#105/#112 shipped 2026-10-07 (`686b653`/`beed16d`/`64f3eec`); v1.3.5 (10305) released same day; v1.3.6 (10306) released 2026-10-08.
- [x] Earlier — see HISTORY.md / tracker.

## Pending
- [ ] **#117 + #116 Phase 1 on-device verify (owner)** — one combined round: `stt-shadow.json` diff (seam quality), fragment-cadence `stt-upload.json`, `stt-latency.json` `durationMs` records, `Ns → STT` ≤1.3 s after stop-tap; then Phase 2 profiles.
- [ ] **F-Droid 1.3.6 pickup watch (automatic)** — ~3–5 days after the tag; reopen only if it stalls.
- [ ] **#112 verify (owner)** — tap "Restore Default Prompts" in Settings, confirm the crafted prompt appears.
- [ ] **#113 freeze recurrence (owner)** — if it happens again: stage-line content, X-tap vs system-back, plus `ime-lifecycle.log` + STT/LLM log timestamps. (#116 shrinks PROCESSING to tail+LLM, defusing the prime suspect.)
- [ ] **#109 visual check (owner)** — notification mic icon variant B on the OnePlus, both paths.
- [ ] **#74 Phase 1 posting (owner)** — Mastodon ✅ (2026-10-02); remaining: r/fossdroid, r/degoogle (Showcase thread), r/selfhosted (modmail), kuketz, **Facebook (#108)**. Also paste the welcome message into the Google Group.
- [ ] **#74 Play-alpha recruitment (owner)** — 20–30 testers, ≥12 continuous 14 days.
- [ ] **#101 decision (owner)** — whether API tokens stay in Google Auto Backup.
- [ ] **#99 (owner)** — Google overview page shows outdated application images.
- [ ] **#75** — rate-limit header logging.
- [ ] Insertion-spacing watch — passive.

## Blockers
None.

## Device Notes
- f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — adb IME/secure-setting writes blocked, reads work.
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb IME/settings writes WORK.
- **The OnePlus is also the Telegram bridge to the agent session** — incoming messages overlay scrcpy recordings. Verify the foreground before `input tap`; record long, trim; keep the raw until the GIF is signed off.
- scrcpy output is **VFR** — normalize (`ffmpeg -vf fps=30 -c:v libx264`) before trimming/contact sheets.
- OnePlus Notes (`com.oneplus.note`) is dark; new note via the FAB; the note list shows private titles — never record it.
- Owner runs a **local LAN STT server** (`http://10.8.0.16:11437/v1/` on gregor, `hwdsl2/whisper-server:cuda`, model `large-v3`, API token via `sudo docker inspect` on gregor) alongside GROQ LLM (`qwen/qwen3.8-27b`); see PITFALLS for its latency/serialization characteristics.
- Chunked upload on-device: watch `logs/` JSON for multiple `recording_N.*` paths per session (#115). **#116 adds `stt-latency.json`** (per-attempt records with `durationMs` + `promptChars` since #117); **#117 adds `stt-shadow.json`** (fragmentText vs fullText — only active for LAN/local STT endpoints). Fragment cadence = one `stt-upload.json` entry per ~7 s of dictation.

## Next Session Suggestion
Owner on-device verify of #117 + #116 Phase 1 on the OnePlus (acceptance: `stt-shadow.json` fragment vs full-context texts agree — no seam drops; `prepare.json` silence-aligned boundaries; `Ns → STT` ≤1.3 s). If green → Phase 2 (per-provider profiles: robust `t(S) ≈ a + b·S` fit over `stt-latency.json` records → auto `fragmentSeconds` + concurrency auto-detect, bootstrap 7 s/C=1 until ≥12 samples, stepwise adaptation ±50 %/step with revert, manual override "auto").
