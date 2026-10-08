# Project State

Current status as of 2026-10-08 late (**#116 Phase 1 IMPLEMENTED — live fragment transcription, on-device verify pending**): `bd9ba63` (STT plumbing: `SttRequestRunner` with retry ×3 transient-only — IOException/timeouts, HTTP 5xx, 429; 1 s/2 s backoff; per-attempt completion records in a dedicated **`stt-latency.json`** stream with `durationMs` (parsed via `AudioDuration` from WAV header / Ogg granule), `httpCode`, `elapsedMs`, `mediaType`) + `e9799df` (live worker: `FragmentPreparer.onFragmentCommitted` hook → new `FragmentTranscriber` (serial C=1, ordered accumulation, per-fragment failure isolation, cached transcripts, `resetFailures()` re-queues only failed fragments) → controller stop flow = tail encode → drain → **single full-context LLM pass** → `Completed`; pipeline post-STT half extracted as `finishTranscription(Result<SttResult>)`; compress-off + stage-memoization paths unchanged; RecognitionService single-`onResults` unchanged). IME/Service stage lines now read `56s → STT` (pending audio seconds) and `120 words → LLM` ("Polishing (LLM)…" removed). **Owner design decisions 2026-10-08:** NO committed-span rewrite (Option C — nothing committed to the field until polished text; stage line carries progress); Raw-toggle rescue after fragment failure (raw retry re-attempts failed fragments, delivers joined raw text); RecognitionService path stays batch/sequential. GROQ rate limits verified: 20 RPM free tier ≥ 8.6 RPM live 7-s cadence — no change needed. 357 tests green, `assembleRelease` green; Phase-1 progress comment + knowledge persisted on #116.

Release state unchanged from 2026-10-07/08: **v1.3.6 (10306) released**; F-Droid pickup watch open. **#115 CLOSED** (owner on-device test PASSED); **#105 + #114 CLOSED**; #112 restore-tap pending; #113 freeze needs recurrence data (PROCESSING now shrinks to tail+LLM, defusing its prime suspect).

## Current Focus
**#116 on-device verification (owner, OnePlus f6de166c):** long dictation against gregor → fragment-cadence `stt-upload.json` records + `stt-latency.json` with `durationMs`; stage line `Ns → STT` ≤1.3 s after stop-tap; then Phase 2 (per-provider profiles) as the second half of #116. Also #113 recurrence data, #112 restore-tap confirm. Growth-side: #74 Phase 1 wave + Play-alpha recruitment.

## Completed (recent cycles)
- [x] **#116 Phase 1 implemented 2026-10-08** (`bd9ba63` + `e9799df`): STT retry ×3 + `stt-latency.json` completion records (`AudioDuration`); live fragment worker (C=1, ordered, failure-isolated, cached transcripts, `resetFailures`); controller stop flow = tail → drain → LLM; stage lines `Ns → STT` / `N words → LLM`; raw rescue. 357 tests green, `assembleRelease` green. **On-device verify pending (owner).**
- [x] #64 CLOSED BY MEASUREMENT 2026-10-08 — parallel chunk uploads rejected: gregor serializes, GROQ already ~200×. Evidence comment on #64; no code.
- [x] #115 fragment streaming (`5363a38`), owner on-device verify 20:46 PASSED; #115 CLOSED.
- [x] #114/#105/#112 shipped 2026-10-07 (`686b653`/`beed16d`/`64f3eec`); v1.3.5 (10305) released same day; v1.3.6 (10306) released 2026-10-08.
- [x] Earlier — see HISTORY.md / tracker.

## Pending
- [ ] **#116 on-device verify (owner)** — OnePlus long dictation vs gregor: fragment-cadence `stt-upload.json`, `stt-latency.json` `durationMs` records, `Ns → STT` ≤1.3 s after stop-tap, no boundary-quality regression; then Phase 2 profiles.
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
- Chunked upload on-device: watch `logs/` JSON for multiple `recording_N.*` paths per session (#115). **#116 adds `stt-latency.json`** — per-attempt records with `durationMs`; fragment cadence = one `stt-upload.json` entry per ~7 s of dictation.

## Next Session Suggestion
Owner on-device verify of #116 Phase 1 on the OnePlus (acceptance: `Ns → STT` stage line ≤1.3 s after stop-tap vs gregor; `stt-latency.json` fragment records with real durations). If green → Phase 2 (per-provider profiles: robust `t(S) ≈ a + b·S` fit over `stt-latency.json` records → auto `fragmentSeconds = clamp((T − 0.3 − 1.5a)/b, 7, 60)` + concurrency auto-detect, bootstrap 7 s/C=1 until ≥12 samples, stepwise adaptation ±50 %/step with revert, manual override "auto").
