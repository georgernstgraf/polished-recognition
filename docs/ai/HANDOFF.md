# Hand Off

**2026-10-10 (early): #117 round 2 VERIFIED ON-DEVICE (2026-10-09 ~23:47, noisy-condition run) — 0 dropped words (baseline −9), 0 "…" hallucinations (baseline 6×), fragment text ⊇ full text. Trunk at `8efa9b2`. Owner verdict on closure pending (two questions).**

## Where things stand

- Round 2 shipped in `5c49cc5` (21-s fragments + 1-s acoustic pre-roll + echo trim; implementation details in CONVENTIONS/ARCHITECTURE/DECISIONS, full report in the #117 comment).
- Verification ran under TV audio (owner deliberately treated junk audio as a scenario): seam quality PASSED. Silence-aligned cuts gracefully fell back to hard cuts under background noise (3 of 4 fragments) — expected, see PITFALLS.
- The one open artifact question: the fragment text has "Es geht hier nicht um Panikmache. **Es geht nicht um Panikmache**, sondern um Medizin." at the fragment 1→2 seam (full-context pass has it once). 5 words + wording change = NOT a pre-roll echo (≤3 words, verbatim); most likely real speech the full-context Whisper collapsed. `echoTokens=0` on all fragments — trim correctly did not fire.
- **Latency caveat**: stop→raw ≈ 2.2 s (tail 21.7 s, gregor elapsed 2166 ms) vs the 1.3-s target; session variance on gregor (2.2–2.3 s vs 0.7 s for identical 21-s uploads). Phase 2's auto-sizer would land gregor at ~12–14-s fragments with this measured slope.

## Open tasks

1. [ ] **#117 closure (owner)** — (a) confirm the "Panikmache" sentence was spoken twice (or accept it as harmless), (b) feel-check the 2.2-s stop→raw; then close the issue. No code work pending on this issue.
2. [ ] **VoiceSessionControllerTest flake instrumentation (agent)** — the watch item recurred twice in the round-2 session (`fragment failure fails in polish mode…` full-suite; `pipeline failure parks PAUSED…` **class-level**, compress-OFF path — "session.meta exists" false / missing `Completed`). Both re-runs green. Instrument the failure branch (log the snapshot()/event-path failure cause) before the next debug round.
3. [ ] **#116 Phase 2 (agent)** — per-provider profiles: `t(S) ≈ a + b·S` fit over `stt-latency.json` → auto `fragmentSeconds` + concurrency; **full-context-at-stop strategy** for fast providers. `stt-latency` `durationMs`/`bytes` INCLUDE the 1-s pre-roll (uploaded-size truth — correct fit basis; do not "correct"). Real 21-s gregor samples now exist; the fit would shrink gregor to ~12–14-s fragments (tail ≈ 1.3 s).
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
- **USB mode gotcha (2026-10-09):** the phone in MIDI mode (`lsusb: 18d1:4ee8`) is invisible to adb — switch the USB mode to file transfer / keep USB debugging on, then accept the RSA prompt.
- **The OnePlus doubles as the Telegram bridge** — verify the foreground before `input tap`; scrcpy output is VFR — normalize before trimming.
- **Log evidence**: `adb shell cat` per file from `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` — `adb pull` is BLOCKED; `adb shell` in a `while read` loop eats stdin (`< /dev/null`); strip `\r`; exact package name `com.georgernstgraf.polishedrecognition`. Streams: `stt-upload`, `stt-latency` (`durationMs`, `promptChars`), `prepare` (`pcmEnd`, `silenceAligned`), `stt-trim` (`fragment`, `echoTokens`, `uploadWithPreRoll`), `stt-shadow` (gate breadcrumb, fragmentText vs fullText, crash records), `stt-response` (per-request verbose responses — newest = shadow pass, rotation covers the fragments), `stt-text`, `llm-*`, `ime-lifecycle.log`.
- **gregor** (SSH alias `gregor`, container `whisper`, `hwdsl2/whisper-server:cuda`, `large-v3`): server code `/opt/src/api_server.py`; `vad_filter=True` hardcoded; `prompt` → `initial_prompt`; GPU spikes: ~21–23-s bursts = live fragment cadence (round 2), one long spike at stop = the shadow pass. Measured: ~2.2–2.3 s per 21-s upload (0.7 s outlier). **Owner dictates with Raw mode on** — a stale `llm-prompt.json` is NOT a pipeline failure.
- The IME crashes on `?attr/` theme attrs — platform attrs / `@null` / explicit colors only in IME layouts.
- **README/fastlane screenshots must stay byte-identical** (`docs/img/*.png` ↔ `fastlane/.../phoneScreenshots/{1,2,3}-*.png`); verify with `md5sum`.
- Pulse-contrast (#87): foreground rows breathe 0.3↔1.0 on a 1333 ms sine while RECORDING.
- **Agent host has no attached device by default** — device-only checks are delegated to the owner.

Last cleared: 2026-10-10 early (**#117 round 2 verified on-device; owner verdict pending; trunk at `8efa9b2`**).
