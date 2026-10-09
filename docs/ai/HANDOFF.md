# Hand Off

**2026-10-09 (later): #117 round 2 IMPLEMENTED & pushed (`5c49cc5`) — 21-s fragments + 1-s acoustic pre-roll + echo trim. NEXT: on-device shadow comparison (owner device), then owner verdict on closing #117.** Full implementation report: the issue comment 2026-10-09 (after `5c49cc5`).

## What round 2 changed (so nobody re-derives it)

- `FragmentPreparer`: `FRAGMENT_SECONDS = 21.0` (`DEFAULT_FRAGMENT_BYTES = 672_000`), `preRollBytes` ctor param (1 s = 32 000 B). Fragment ≥ 1 gets `preroll_<i>.<ext>` (last 1 s of PCM before its start, same format, clamped at stream start) written BEFORE the manifest. Pre-roll encode failure → `null` and NEVER the WAV fallback; the WAV-fallback rebuild re-creates pre-rolls; `recover()`'s orphan keep-set includes kept fragments' pre-rolls (they are NOT manifest entries). Hook + `committedFragments()` = `(index, file, preRoll)`, pre-roll derived via `name.replaceFirst("frag_","preroll_")` + `isFile` check.
- `FragmentTranscriber`: upload composed ONCE as `preRoll + fragment` byte-concat into `upload_<i>.<ext>` in the session dir; **BOTH** attempts use it — the HTTP-400 prompt retry originally re-uploaded the bare fragment (would have silently dropped the pre-roll; the echo is acoustic). Deleted in `finally`. Echo trim runs BEFORE storing the transcript (the trimmed text feeds the next fragment's prompt AND known tail): token match `[\p{L}\p{N}]+` vs previous tail, k = 8…1, head token must equal tail token, ≤1 mismatch for k ≥ 2, no match keeps everything; runs ONLY when the upload carried a pre-roll. Evidence → `stt-trim.json` (fragment, echoTokens, uploadWithPreRoll).
- Controller: `fragmentPreRollBytes` test param (0 = off in tests, mirroring `fragmentSearchBytes`); logger wired into the transcriber.
- Tests: +12 — pre-roll PCM ranges incl. clamp, ogg+wav modes, hook payload, recover keep-set, fallback rebuild, composition byte-equality + cleanup, trim echo/no-match/single-token/full-echo/no-preroll, 400-retry-on-composed-upload. Full suite (395) + `assembleRelease` green.

## Open tasks

1. [ ] **#117 round-2 verify (owner + agent, next session)** — OnePlus attached → `./gradlew installRelease` → one dictation vs gregor (Raw mode on) → compare `stt-shadow.json` fragmentText vs fullText against the 2026-10-09 baseline (**406 vs 415 words; 9-word + 5-word phrase drops; "Pest"→"Best"; 6× "…"**) — phrase drops should shrink drastically. Check `prepare.json` (~21-s fragments, pcmEnd deltas ≈ 672 000 ± search window), `stt-trim.json` (echo matches), stop→raw ≤ ~1.5 s. Watch signal: gregor GPU bursts every ~21–23 s.
2. [ ] **VoiceSessionControllerTest flake — instrumentation now due (agent)** — the watch item recurred twice in the round-2 session (`fragment failure fails in polish mode…` full-suite; `pipeline failure parks PAUSED…` **class-level**, the documented "passes in class-level" no longer holds; second one is the compress-OFF legacy path, untouched by round 2). Both re-runs green. Instrument the failure branch before the next deep debug round (see PITFALLS).
3. [ ] **#116 Phase 2 (agent)** — per-provider profiles (`t(S) ≈ a + b·S` over `stt-latency.json` → auto `fragmentSeconds` 40–60 s for fast providers + concurrency) + **full-context-at-stop strategy** (shadow text authoritative where the profile says the provider is fast; GROQ ≈ 200× realtime). NOTE: `stt-latency` `durationMs`/`bytes` now INCLUDE the 1-s pre-roll — that is uploaded-size truth, the correct fit basis; do not "correct" it.
4. [ ] **#112 verify (owner)** — tap "Restore Default Prompts", confirm the crafted prompt appears; then close.
5. [ ] **#113 freeze recurrence (owner)** — stage line, X-tap vs system-back, `ime-lifecycle.log` + STT/LLM timestamps.
6. [ ] **F-Droid 1.3.6 pickup watch (automatic)**.
7. [ ] **#74 Phase 1 posting (owner)** — r/fossdroid (own words — sub bans AI promo), r/degoogle (weekly Showcase thread only), r/selfhosted (modmail first), kuketz (DE, second wave), Facebook DE (#108); Google Group welcome message from `docs/marketing/alpha-welcome.md`.
8. [ ] **#74 Play-alpha recruitment (owner)** — 20–30 testers, ≥12 × 14 days.
9. [ ] **#109 visual check (owner)** — notification mic icon variant B, both paths.
10. [ ] **#99 (owner)** — Google overview page outdated images; **#101 (owner)** — token storage vs Auto Backup; **#75** — rate-limit header logging; insertion-spacing watch — passive.

## Known on-device gotchas

- **Devices: f6de166c = OnePlus 7T** (HD1903, Oplus, 1080×2400); d890cc9e = S5 (SM-G900F, LineageOS 18.1). `input tap` needs REAL pixels.
- **Oplus blocks adb IME/secure-setting writes** — Settings UI only; reads (`dumpsys`) work.
- **The OnePlus doubles as the Telegram bridge** — verify the foreground before `input tap`; scrcpy output is VFR — normalize before trimming.
- **Log evidence**: `adb shell cat` per file from `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` — `adb pull` is BLOCKED; `adb shell` in a `while read` loop eats stdin (`< /dev/null`); strip `\r`; exact package name `com.georgernstgraf.polishedrecognition`. Streams: `stt-upload`, `stt-latency` (`durationMs`, `promptChars`), `prepare` (`pcmEnd`, `silenceAligned`), `stt-trim` (round 2: `fragment`, `echoTokens`, `uploadWithPreRoll`), `stt-shadow` (gate breadcrumb, fragmentText vs fullText, crash records), `stt-text`, `llm-*`, `ime-lifecycle.log`.
- **gregor** (SSH alias `gregor`, container `whisper`, `hwdsl2/whisper-server:cuda`, `large-v3`): server code `/opt/src/api_server.py`; `vad_filter=True` hardcoded; `prompt` → `initial_prompt`; GPU spikes: ~21–23-s bursts = live fragment cadence (round 2), one long spike at stop = the shadow pass. **Owner dictates with Raw mode on** — a stale `llm-prompt.json` is NOT a pipeline failure.
- The IME crashes on `?attr/` theme attrs — platform attrs / `@null` / explicit colors only in IME layouts.
- **README/fastlane screenshots must stay byte-identical** (`docs/img/*.png` ↔ `fastlane/.../phoneScreenshots/{1,2,3}-*.png`); verify with `md5sum`.
- Pulse-contrast (#87): foreground rows breathe 0.3↔1.0 on a 1333 ms sine while RECORDING.
- **Agent host has no attached device by default** — device-only checks are delegated to the owner (the OnePlus was attached throughout the round-1 verify session).

Last cleared: 2026-10-09 (later) (**#117 round 2 implemented + pushed `5c49cc5`; on-device shadow comparison next**).
