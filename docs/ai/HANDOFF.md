# Hand Off

**2026-10-09 late: #117 verified on-device; owner decided round 2 — IMPLEMENTATION PENDING, do the plan in the #117 comment.** Trunk is clean at `8b7f191` (a half-applied 21-s constant edit was REVERTED before persistence — do not assume it exists). The #117 comment **"On-device verification result (2026-10-09) + owner decisions"** (https://github.com/georgernstgraf/polished-recognition/issues/117#issuecomment-6076144180) contains the full implementation plan for the next session:

1. **Fragment minimum 7 s → 21 s** (`FRAGMENT_SECONDS`/`DEFAULT_FRAGMENT_BYTES`; constructor parameter already exists — Phase 2 will drive 40–60 s for fast providers).
2. **1-s acoustic pre-roll overlap**: `FragmentPreparer` writes `preroll_%06d.<ext>` per committed fragment (PCM `[max(0, start−preRollBytes), start)`, same format as the fragments — OGG chaining makes the concatenated upload valid); `FragmentTranscriber` composes the upload as `preRoll + fragment` byte-concat into `upload_%06d.<ext>`, uploads that, deletes it; the echoed pre-roll text is TRIMMED by token-matching against the previous fragment's known transcript tail (conservative: no match → keep duplicates, never drop words); trim evidence into a NEW `stt-trim.json` stream (never `prepare.json` — rotation depth).
3. Gotchas already worked out in the plan: `recover()`'s orphan cleanup must keep kept fragments' pre-roll files; the WAV-fallback rebuild must re-create them; the hook signature becomes `(index, file, preRoll)`; tests must derive the fragment index from the runner's `chunk` arg (upload files are named `upload_*`); controller gains a `fragmentPreRollBytes` test param (default off in tests); pre-roll transcode failure must NOT trigger the WAV fallback.
4. Verify: unit tests green → `installRelease` → one on-device dictation → compare `stt-shadow.json` fragmentText vs fullText against the 2026-10-09 baseline (**406 vs 415 words; 9-word + 5-word phrase drops; "Pest"→"Best"; 6× "…"**) — the drops should shrink drastically.

**Why** (owner reasoning): 7 s gives Whisper practically no context; 21 s cuts seam problems ~3× while gregor's STT stays ≈ 1 s (measured model t(30 s) = 1.4 s; worst tail ≈ 1.2 s — owner accepted vs the 1.3 s target); the pre-roll gives real acoustic context at the seam (stronger than the text prompt) and protects quiet onsets from gregor's hardcoded VAD; full-context-at-stop for fast providers goes to Phase 2 with the per-provider profiles.

**Verification history (all evidence on-device 2026-10-09):** silence-aligned cuts + prompt carry-over WORK (`silenceAligned`, `pcmEnd`, `promptChars` in the logs; gregor forwards `initial_prompt` — verified in the server's `/opt/src/api_server.py`). The shadow comparison works after three fixes (`5ef704f` dir-race + crash logging + gate breadcrumb; `8b7f191` NetworkOnMainThreadException — the runner runs on the caller's dispatcher, ALWAYS wrap in `Dispatchers.IO`; Robolectric cannot catch device-only threading violations).

**Watch item:** `VoiceSessionControllerTest` has a load-sensitive flake (`pipeline failure parks PAUSED…`, `retry after failure…`, `fragment failure…` — "session.meta exists" false / wrong Completed order in heavily loaded runs; passes in class-level/isolated/repeat runs; NOT caused by the shadow test — the failing tests run before it in execution order). If it recurs, instrument the failure branch.

## Open tasks

1. [ ] **#117 round 2 (agent, next session)** — implement 21-s fragments + pre-roll overlap per the #117 comment; then on-device shadow comparison vs the 406/415 baseline.
2. [ ] **#116 Phase 2 (agent)** — per-provider profiles (`t(S) ≈ a + b·S` over `stt-latency.json` → auto `fragmentSeconds` 40–60 s for fast providers + concurrency) + **full-context-at-stop strategy** for fast providers (GROQ ≈ 200× realtime → < 1 s for 10-min recordings — seams disappear entirely for cloud; gregor stays on fragments).
3. [ ] **#112 verify (owner)** — tap "Restore Default Prompts", confirm the crafted prompt appears; then close.
4. [ ] **#113 freeze recurrence (owner)** — stage line, X-tap vs system-back, `ime-lifecycle.log` + STT/LLM timestamps.
5. [ ] **F-Droid 1.3.6 pickup watch (automatic)**.
6. [ ] **#74 Phase 1 posting (owner)** — r/fossdroid (own words — sub bans AI promo), r/degoogle (weekly Showcase thread only), r/selfhosted (modmail first), kuketz (DE, second wave), Facebook DE (#108); Google Group welcome message from `docs/marketing/alpha-welcome.md`.
7. [ ] **#74 Play-alpha recruitment (owner)** — 20–30 testers, ≥12 × 14 days.
8. [ ] **#109 visual check (owner)** — notification mic icon variant B, both paths.
9. [ ] **#99 (owner)** — Google overview page outdated images; **#101 (owner)** — token storage vs Auto Backup; **#75** — rate-limit header logging; insertion-spacing watch — passive.

## Known on-device gotchas

- **Devices: f6de166c = OnePlus 7T** (HD1903, Oplus, 1080×2400); d890cc9e = S5 (SM-G900F, LineageOS 18.1). `input tap` needs REAL pixels.
- **Oplus blocks adb IME/secure-setting writes** — Settings UI only; reads (`dumpsys`) work.
- **The OnePlus doubles as the Telegram bridge** — verify the foreground before `input tap`; scrcpy output is VFR — normalize before trimming.
- **Log evidence**: `adb shell cat` per file from `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` — `adb pull` is BLOCKED; `adb shell` in a `while read` loop eats stdin (`< /dev/null`); strip `\r`; exact package name `com.georgernstgraf.polishedrecognition`. Streams: `stt-upload`, `stt-latency` (`durationMs`, `promptChars`), `prepare` (`pcmEnd`, `silenceAligned`), `stt-shadow` (gate breadcrumb, fragmentText vs fullText, crash records with stack), `stt-text`, `llm-*`, `ime-lifecycle.log`.
- **gregor** (SSH alias `gregor`, container `whisper`, `hwdsl2/whisper-server:cuda`, `large-v3`): server code `/opt/src/api_server.py`; `vad_filter=True` hardcoded; `prompt` → `initial_prompt`; GPU spikes: 7–9-s bursts = live fragment cadence, one long spike at stop = the shadow pass (21–23-s bursts expected after round 2). **Owner dictates with Raw mode on** — a stale `llm-prompt.json` is NOT a pipeline failure.
- The IME crashes on `?attr/` theme attrs — platform attrs / `@null` / explicit colors only in IME layouts.
- **README/fastlane screenshots must stay byte-identical** (`docs/img/*.png` ↔ `fastlane/.../phoneScreenshots/{1,2,3}-*.png`); verify with `md5sum`.
- Pulse-contrast (#87): foreground rows breathe 0.3↔1.0 on a 1333 ms sine while RECORDING.
- **Agent host has no attached device by default** — device-only checks are delegated to the owner (the OnePlus was attached throughout this session).

Last cleared: 2026-10-09 late (**#117 verified + decisions recorded; round-2 implementation plan in the issue comment; trunk clean at `8b7f191`**).
