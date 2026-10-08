# Project State

Current status as of 2026-10-08 night (**#115 streaming fragment encoding on `main`** — `0f43035` chunker port, `5df67b4` stt-text evidence, `401e1b4` memoization/timeouts, `5363a38` **fragment streaming**: fast Opus drain loop (root cause of 1.25×-real-time encode fixed), `FragmentPreparer` encodes 7-s OGG fragments live during dictation (compress ON only) into `cacheDir/fragments/<sessionId>/` + manifest, upload chunk = byte concatenation (OGG chaining, RFC 3533), fixed 600-s boundaries, retry/process-death fragment reuse, session-level WAV fallback; 330 tests green, `assembleRelease` green, **`installRelease` on f6de166c 20:41**). **Owner on-device test 20:46 PASSED**: 8 fragments (7×7 s + 2.4 s tail) encoded at **≈608 ms/fragment = ~11.5× real time** (old loop: ~0.8×) — the drain-loop fix measured at ~9× speedup; worker keeps up trivially; stop-time work = 190 ms tail + cat; single 226-kB chunk → LAN Whisper → 644 chars complete, no cutoff ("großartig", owner). Chunking goal of #115 verified end-to-end; #115 CLOSED 2026-10-08 (sub-issue aitranscribe#81 closed: crafted prompt adopted in aitranscribe `c55f441`, two-generation prompts.toml migration, 196 tests green).

Release state unchanged from 2026-10-07: **v1.3.5 (10305) released** — Play alpha confirmed (`release.yml` green, `Successfully committed 14941024215117549040`), `fdroid-apk.yml` + `build.yml` green, AAB + APK on the GitHub release; F-Droid pickup watch ~3–5 days after the tag. **#105 + #114 CLOSED**; #112 restore-tap pending; #113 freeze needs recurrence data. Latest public release **v1.3.5**; F-Droid serves 1.3.3 until fdroidbot runs.

## Current Focus
Code-side: **#115 owner on-device verify** (long dictation → ≥2 chunk uploads, no mid-text cutoff), then **aitranscribe#81** (prompt sync in the sister repo) and closing #115 after both land. Also #113 recurrence data and #112 restore-tap confirm. Growth-side: #74 Phase 1 wave + Play-alpha recruitment.

## Completed (recent cycles)
- [x] #115 PART A: CHUNKING PORTED 2026-10-08 (`0f43035`) — `WavChunker`/`WavWriter` new in `audio/`; `AudioRecorder` delegates encoding to `WavWriter`; controller emits numbered chunk files `recording_N.{ogg,wav}` (per-chunk transcoding + fallback), pipeline joins texts; single-file legacy path untouched for within-limits recordings; chunk limits injectable as constructor params for tests; `WavChunkerTest` (8 cases) + pipeline join/failure tests + controller multi-chunk handoff test. Known deviation from core.py, documented: the 60 s floor is additionally capped at the size-derived budget so a small byte limit can never be violated.
- [x] #115 PART B ticketed as **aitranscribe#81** (2026-10-08): replace `[post_process.system]` in aitranscribe's `main.py` with the current prompts.json system prompt + prompts.toml migration stamp (pitfall known since aitranscribe#73).
- [x] #114/#105/#112 shipped + closed 2026-10-07 (`686b653`/`beed16d`/`64f3eec`); v1.3.5 (10305) released same day.
- [x] Earlier — see HISTORY.md / tracker.

## Pending
- [ ] **#115 on-device verify (owner)** — then close (sub-issue aitranscribe#81 must be closed first).
- [ ] **aitranscribe#81 (prompt sync)** — child of #115, sister repo; note its prompts.toml migration pitfall.
- [ ] **F-Droid 1.3.5 pickup watch (automatic)** — ~3–5 days after the tag; reopen only if it stalls.
- [ ] **#112 verify (owner)** — tap "Restore Default Prompts" in Settings, confirm the crafted prompt appears.
- [ ] **#113 freeze recurrence (owner)** — if it happens again: stage-line content, X-tap vs system-back, plus `ime-lifecycle.log` + STT/LLM log timestamps.
- [ ] **#109 visual check (owner)** — notification mic icon variant B on the OnePlus, both paths.
- [ ] **#74 Phase 1 posting (owner)** — Mastodon ✅ (2026-10-02); remaining: r/fossdroid, r/degoogle (Showcase thread), r/selfhosted (modmail), kuketz, **Facebook (#108)**. Also paste the welcome message into the Google Group.
- [ ] **#74 Play-alpha recruitment (owner)** — 20–30 testers, ≥12 continuous 14 days.
- [ ] **#101 decision (owner)** — whether API tokens stay in Google Auto Backup.
- [ ] **#99 (owner)** — Google overview page shows outdated application images.
- [ ] **#75** — rate-limit header logging.
- [ ] **#64 (repurposed 2026-10-08)** — parallelize chunk uploads for slow STT servers (compression latency resolved by #115 streaming).
- [ ] Insertion-spacing watch — passive.

## Blockers
None.

## Device Notes
- f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — adb IME/secure-setting writes blocked, reads work.
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb IME/settings writes WORK.
- **The OnePlus is also the Telegram bridge to the agent session** — incoming messages overlay scrcpy recordings. Verify the foreground before `input tap`; record long, trim; keep the raw until the GIF is signed off.
- scrcpy output is **VFR** — normalize (`ffmpeg -vf fps=30 -c:v libx264`) before trimming/contact sheets.
- OnePlus Notes (`com.oneplus.note`) is dark; new note via the FAB; the note list shows private titles — never record it.
- Owner now runs a **local LAN STT server** (`http://10.8.0.16:11437/v1/`, faster-whisper-style: returns ISO `language` codes + `language_probability`) alongside GROQ LLM (`qwen/qwen3.8-27b`).
- Chunked upload on-device: watch `logs/` JSON for multiple `recording_N.*` paths per session (#115).

## Next Session Suggestion
Owner verifies #115 on-device (chunked uploads + no cutoff) and closes; then work **aitranscribe#81** (prompt sync + prompts.toml migration) in the sister repo and close it (which unblocks #115's complete closure). Code-side after that: #112 confirm, #113 recurrence, #75 / #64.
