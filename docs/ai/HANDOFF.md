# Hand Off

**2026-10-10 (early): #117 CLOSED — round 2 (21-s fragments + 1-s acoustic pre-roll + echo trim) verified on-device: 0 dropped words (baseline −9), 0 "…" hallucinations (baseline 6×), fragment text ⊇ full text. Trunk at `dc04eb9`. Next: #116 Phase 2 (owns fragment sizing) + flake instrumentation.**

**2026-10-10 (later): 16-min movie session logged — fragment pipeline clean (47/47, all attempt 1), but the shadow full-context pass COLLAPSED on gregor's hardcoded VAD (fragment concat 2310 chars vs full pass 1021; first ~9 min lost). Phase 2: treat gregor as fragments-only for long recordings. #113 FREEZE recurrence captured (2026-10-09 22:18:36 CEST, evidence on the issue).**

## Where things stand

- Round 2 shipped in `5c49cc5` (21-s fragments + 1-s acoustic pre-roll + echo trim; implementation details in CONVENTIONS/ARCHITECTURE/DECISIONS, full report in the #117 comment). Issue CLOSED with owner verdicts 2026-10-10.
- Verification ran under TV audio (owner deliberately treated junk audio as a scenario): seam quality PASSED. Silence-aligned cuts gracefully fell back to hard cuts under background noise (3 of 4 fragments) — expected, see PITFALLS.
- Duplicated "Panikmache" phrase at the fragment 1→2 seam: owner could not confirm → accepted harmless by construction (real repetition kept verbatim — LLM polish dedupes in polish mode, raw mode is verbatim by design; trim correctly never fired, `echoTokens=0` throughout).
- **Latency caveat**: stop→raw ≈ 2.2 s measured = the tail chunk's round-trip (the ONLY chunk paid at send; tail bounded ~23 s = fragment + search window; near-worst-case length this session; gregor variance 0.7–2.3 s for identical 21-s uploads). Accepted; Phase 2's auto-sizer would land gregor at ~12–14-s fragments (tail ≈ 1.3 s).

## Open tasks

1. [ ] **#116 Phase 2 (agent)** — per-provider profiles: `t(S) ≈ a + b·S` fit over `stt-latency.json` → auto `fragmentSeconds` + concurrency; **full-context-at-stop strategy** for fast providers. `stt-latency` `durationMs`/`bytes` INCLUDE the 1-s pre-roll (uploaded-size truth — correct fit basis; do not "correct"). Real 21-s gregor samples exist; the fit would shrink gregor to ~12–14-s fragments (tail ≈ 1.3 s). **2026-10-10 movie-session caveat: the full-context shadow pass collapsed on gregor VAD (588-s chunk → 12.8 s after VAD; fragment concat 2310 vs 1021 chars) — gregor must stay fragments-only for long recordings; see STATE.md data block + PITFALLS.**
2. [ ] **VoiceSessionControllerTest flake instrumentation (agent)** — the watch item recurred twice in the round-2 session (`fragment failure fails in polish mode…` full-suite; `pipeline failure parks PAUSED…` **class-level**, compress-OFF path — "session.meta exists" false / missing `Completed`). Both re-runs green. Instrument the failure branch (log the snapshot()/event-path failure cause) before the next debug round.
3. [ ] **#112 verify (owner)** — tap "Restore Default Prompts", confirm the crafted prompt appears; then close.
4. [ ] **#113 freeze recurrence (owner)** — stage line, X-tap vs system-back, `ime-lifecycle.log` + STT/LLM timestamps. **Recurrence captured 2026-10-09 22:18:36 CEST** (`restarting=true state=RECORDING outcome=FREEZE`; input view finished while RECORDING 22:17:34 → PAUSED 22:17:50 → restart → FREEZE → IDLE 22:18:43); evidence posted to the issue — remaining owner work: stage-line behavior + X-tap vs system-back.
5. [ ] **F-Droid 1.3.6 pickup watch (automatic)**.
6. [ ] **#74 Phase 1 posting (owner)** — r/fossdroid (own words — sub bans AI promo), r/degoogle (weekly Showcase thread only), r/selfhosted (modmail first), kuketz (DE, second wave), Facebook DE (#108); Google Group welcome message from `docs/marketing/alpha-welcome.md`.
7. [ ] **#74 Play-alpha recruitment (owner)** — 20–30 testers, ≥12 × 14 days.
8. [ ] **#109 visual check (owner)** — notification mic icon variant B, both paths.
9. [ ] **#99 (owner)** — Google overview page outdated images; **#101 (owner)** — token storage vs Auto Backup; **#75** — rate-limit header logging; insertion-spacing watch — passive.

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

Last cleared: 2026-10-10 later (**movie-session STT data persisted — gregor VAD full-context collapse + #113 FREEZE recurrence**).
