# Project State

Current status as of 2026-10-08 late (**STT latency benchmark + #64 closed by measurement + #116 ticketed**): host-side bench against gregor (`10.8.0.16:11437`, `hwdsl2/whisper-server:cuda`, `large-v3` on an RTX 2070 SUPER, ~3.9 GB VRAM resident) and GROQ (`whisper-large-v3-turbo`), real German radio speech as app-format OGG/Opus 24 kbps mono, 3 runs per point. **gregor x ≈ 22–29× realtime** (`t(S) ≈ a + b·S`, floor a ≈ 0.65–0.9 s, 600 s = 26.9 s — the ">2 min/600 s" premise of #64 is NOT reproducible), **strictly serialized** (wall time constant over C=1..4 for 4×90 s; 2×600 s parallel = 53.6 s vs 26.9 s sequential; GPU avg 75–81 % independent of C; GPU dips to 0 % are per-request segment-batch bubbles). **GROQ x ≈ 200×** (0.9 s per 180-s OGG, floor ~0.25 s), parallelizes trivially; WAV/PCM is upload-bound over WAN (180 s WAV = 5.8 MB → 2.7 s). WAV is also slower than OGG on gregor (8.2 s vs 6.2 s per 180 s). **#64 CLOSED** with the full evidence comment (no parallel-upload code); successor **#116 "Live fragment transcription"** created: worker consumes #115's 7-s fragments live during dictation (C=1 vs serialized providers), stop-tap tail ≤ one fragment → stop→raw ≈ 1.1–1.3 s (gregor) / <1 s (GROQ); per-provider self-measured profiles (robust `t(S) ≈ a + b·S` fit from `durationMs` completion records → auto `fragmentSeconds` + concurrency, bootstrap 7 s/C=1 until ≥12 samples); retry ×3 transient-only with 1 s/2 s backoff; LLM polish stays a single full-context pass after stop (owner decision). Open design rock flagged in #116: IME must rewrite its own committed span (raw-first display). **aitranscribe#82** carries the findings for the sister project. Bench scratch stays in `/tmp/opencode` (owner decision — no repo harness).

Release state unchanged from 2026-10-07/08: **v1.3.6 (10306) released** (bump `84a2e54`, release.yml green, F-Droid pickup watch open); **#115 streaming fragment encoding on `main`** (`0f43035`/`5df67b4`/`401e1b4`/`5363a38`), owner on-device test 20:46 PASSED (8 fragments at ~11.5× real time encode, single 226-kB chunk complete, no cutoff — "großartig"); #115 CLOSED 2026-10-08 (aitranscribe#81 closed: crafted prompt adopted in aitranscribe `c55f441`). **#105 + #114 CLOSED**; #112 restore-tap pending; #113 freeze needs recurrence data.

## Current Focus
Code-side: **#116** (live fragment transcription — Phase 1 worker + completion records + retry; Phase 2 self-measured provider profiles), starting with the IME text-replacement design decision (the big rock). Also #113 recurrence data, #112 restore-tap confirm. Growth-side: #74 Phase 1 wave + Play-alpha recruitment.

## Completed (recent cycles)
- [x] #64 CLOSED BY MEASUREMENT 2026-10-08 — parallel chunk uploads rejected: gregor serializes (parallel actively harmful at large chunks), GROQ already ~200×. Evidence comment on #64; no code. On-device `stt-upload.json` has no durations (evidence gap → #116 completion records); all recent long dictations used the OGG path.
- [x] #115 PART A: CHUNKING PORTED 2026-10-08 (`0f43035`) + fragment streaming (`5363a38`), owner on-device verify 20:46 PASSED; #115 CLOSED (aitranscribe#81 closed: prompt sync `c55f441`).
- [x] #114/#105/#112 shipped 2026-10-07 (`686b653`/`beed16d`/`64f3eec`); v1.3.5 (10305) released same day; v1.3.6 (10306) released 2026-10-08.
- [x] Earlier — see HISTORY.md / tracker.

## Pending
- [ ] **#116 (new 2026-10-08)** — live fragment transcription: Phase 1 worker + `durationMs` completion records + retry ×3 transient-only + flush/cancel; Phase 2 self-measured provider profiles (auto fragmentSeconds/concurrency). First sub-decision: IME committed-span rewrite for raw-first display.
- [ ] **F-Droid 1.3.6 pickup watch (automatic)** — ~3–5 days after the tag; reopen only if it stalls.
- [ ] **#112 verify (owner)** — tap "Restore Default Prompts" in Settings, confirm the crafted prompt appears.
- [ ] **#113 freeze recurrence (owner)** — if it happens again: stage-line content, X-tap vs system-back, plus `ime-lifecycle.log` + STT/LLM log timestamps. (#116 will shrink PROCESSING to ~1–2 s, defusing the prime suspect.)
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
- Chunked upload on-device: watch `logs/` JSON for multiple `recording_N.*` paths per session (#115).

## Next Session Suggestion
Start #116 with the IME committed-span rewrite decision (owner), then Phase 1: fragment worker (C=1 vs gregor), `durationMs` completion records, retry ×3 transient-only, flush/cancel; on-device acceptance: stop→raw ≤ 1.3 s (gregor) / ≤ 1 s (GROQ).
