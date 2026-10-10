# Hand Off

**2026-10-10 (Phase A, #118): rotation partial-insertion Phase A SHIPPED (`99b2e46`, pushed). Owner answers recorded on the issue: flow unsure, perceived "no send", raw mode varies, partial → clipboard + toast (retry = paste). Code trace: no code path supports "no send" — the ONLY insertion site is `commitWithSpacing()` and `Event.Completed` comes only from `deliver()`/`attach()`. Phase A = instrumentation: `Event.Completed` now carries `redelivered` + `partial` (`failedIndex`/`chunkCount`); both insertion surfaces (IME + RecognitionService) log a new `insertion.json` stream (`surface`, `reason`, `length`, origin-vs-current `FieldId`, `hasConnection`). Next: owner on-device repro with the breadcrumb, then Phase B (`InsertionDecision` → clipboard + toast; clear `pendingResult` on a real field change).**

**2026-10-10 (night): rotation storm investigation — the #113 amendment (`21e9e80`) is VERIFIED on-device (post-01:40:59 install: `sameField=false + gateFresh + RECORDING → FREEZE`, previously `CANCEL`). The storm is Oplus re-creating the IME input view; the 3000 ms `RotationGate` absorbs it and the dictation survives (UI churns per cycle). An IME cannot block rotation (no API) — the fix is delivery-side. New issue #118 files the partial-transcript insertion seen on rotation (raw-rescue / `pendingResult`); plan + 4 owner questions are on the issue. Storm/pid/build table posted to #113.**

**2026-10-10 (later): #116 Phase 2 CORE SHIPPED (`f152cd9` + `9453658`) — Theil–Sen per-provider profiles → auto fragment sizing (stepwise ±50 %, bootstrap 12 samples), full-context-at-stop with the gregor-safe predicted-latency gate, Settings override field. The #117 test-flake watch item ROOT-CAUSED and FIXED (real ordering bug: the failure branch published PAUSED before `snapshot()` — on-device that window loses the dictation; + harness event race). C=1 kept — concurrency conflicts with prompt carry-over; owner decision flagged on #116. Next: owner on-device verify.**

**2026-10-10 (early): #117 CLOSED — round 2 (21-s fragments + 1-s acoustic pre-roll + echo trim) verified on-device: 0 dropped words (baseline −9), 0 "…" hallucinations (baseline 6×), fragment text ⊇ full text. Trunk at `dc04eb9`.**

**2026-10-10 (movie session): 16-min recording logged — fragment pipeline clean (47/47, all attempt 1), but the shadow full-context pass COLLAPSED on gregor's hardcoded VAD (fragment concat 2310 chars vs full pass 1021; first ~9 min lost). Baked into Phase 2's gate. #113 FREEZE recurrence captured (2026-10-09 22:18:36 CEST, evidence on the issue).**

## Where things stand

- Round 2 shipped in `5c49cc5` (21-s fragments + 1-s acoustic pre-roll + echo trim; implementation details in CONVENTIONS/ARCHITECTURE/DECISIONS, full report in the #117 comment). Issue CLOSED with owner verdicts 2026-10-10.
- Verification ran under TV audio (owner deliberately treated junk audio as a scenario): seam quality PASSED. Silence-aligned cuts gracefully fell back to hard cuts under background noise (3 of 4 fragments) — expected, see PITFALLS.
- Duplicated "Panikmache" phrase at the fragment 1→2 seam: owner could not confirm → accepted harmless by construction (real repetition kept verbatim — LLM polish dedupes in polish mode, raw mode is verbatim by design; trim correctly never fired, `echoTokens=0` throughout).
- **Latency caveat**: stop→raw ≈ 2.2 s measured = the tail chunk's round-trip (the ONLY chunk paid at send; tail bounded ~23 s = fragment + search window; near-worst-case length this session; gregor variance 0.7–2.3 s for identical 21-s uploads). Accepted; Phase 2's auto-sizer would land gregor at ~12–14-s fragments (tail ≈ 1.3 s).

## Open tasks

1. [ ] **#116 Phase 2 on-device verify (owner)** — core shipped (`f152cd9` + `9453658`): `SttLatencyProfile` (Theil–Sen fit of `t(S) ≈ a + b·S`), auto `fragmentSeconds` via `VoiceSessionController.fragmentSizeProvider` (stepwise ±50 % anchored on the persisted `lastAppliedSeconds`; bootstrap ≥ 12 samples ≈ ~4 min dictation — profiles start EMPTY, no retro-fit of `stt-latency.json`), full-context-at-stop (predicted `a + b·S_total ≤ 1.3 s` → awaited full pass replaces the fragment join, any failure falls back to the join; `stt-shadow` `fullContextAtStop` records), Settings override field (blank = auto, 7–60 s). Acceptance: `stt-upload` cadence at the auto size, stop→raw ≤ 1.3 s gregor / ≤ 1 s GROQ. Watch: the derived gregor size may differ from the predicted 12–14 s — the fit decides.
2. [ ] **C=1 vs concurrency (owner decision, on #116)** — concurrency auto-detection NOT implemented: prompt carry-over + echo trim need fragment i−1's transcript at upload time (serial-worker guarantee); C > 1 breaks seam conditioning unless prompts are disabled.
3. [ ] **#112 verify (owner)** — tap "Restore Default Prompts", confirm the crafted prompt appears; then close.
4. [ ] **#113 freeze recurrence (owner)** — stage line, X-tap vs system-back. Recurrence captured 2026-10-09 22:18:36 CEST (`restarting=true state=RECORDING outcome=FREEZE`; input view finished while RECORDING 22:17:34 → PAUSED 22:17:50 → restart → FREEZE → IDLE 22:18:43); evidence posted to the issue — remaining owner work: stage-line behavior + X-tap vs system-back.
5. [ ] **F-Droid 1.3.6 pickup watch (automatic)**.
6. [ ] **#74 Phase 1 posting (owner)** — r/fossdroid (own words — sub bans AI promo), r/degoogle (weekly Showcase thread only), r/selfhosted (modmail first), kuketz (DE, second wave), Facebook DE (#108); Google Group welcome message from `docs/marketing/alpha-welcome.md`.
7. [ ] **#74 Play-alpha recruitment (owner)** — 20–30 testers, ≥12 × 14 days.
8. [ ] **#109 visual check (owner)** — notification mic icon variant B, both paths.
9. [ ] **#99 (owner)** — Google overview page outdated images; **#101 (owner)** — token storage vs Auto Backup; **#75** — rate-limit header logging; insertion-spacing watch — passive.
10. [ ] **#118 Phase B — delivery policy (ready to implement)** — Phase A shipped (`99b2e46`): `Event.Completed` carries `redelivered`/`partial`; the `insertion.json` stream records every insertion on both surfaces. Phase B: pure `InsertionDecision` at `commitWithSpacing` (LIVE success → COMMIT; REDELIVERED + same field → COMMIT per #83; REDELIVERED + changed field → clipboard + toast; RAW_RESCUE_PARTIAL → clipboard + toast, never auto-insert) + clear `pendingResult` on a genuine field change. Then Phase C (storm UX) and Phase D (owner on-device matrix). The mechanism is still unpinned — the breadcrumb's next repro settles which cause is real. Related: #113.

## Known on-device gotchas

- **Devices: f6de166c = OnePlus 7T** (HD1903, Oplus, 1080×2400); d890cc9e = S5 (SM-G900F, LineageOS 18.1). `input tap` needs REAL pixels.
- **Oplus blocks adb IME/secure-setting writes** — Settings UI only; reads (`dumpsys`) work.
- **USB mode gotcha (2026-10-09):** the phone in MIDI mode (`lsusb: 18d1:4ee8`) is invisible to adb — switch the USB mode to file transfer / keep USB debugging on, then accept the RSA prompt.
- **The OnePlus doubles as the Telegram bridge** — verify the foreground before `input tap`; scrcpy output is VFR — normalize before trimming.
- **Log evidence**: `adb shell cat` per file from `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` — `adb pull` is BLOCKED; `adb shell` in a `while read` loop eats stdin (`< /dev/null`); strip `\r`; exact package name `com.georgernstgraf.polishedrecognition`. Streams: `stt-upload`, `stt-latency` (`durationMs`, `promptChars`), `prepare` (`pcmEnd`, `silenceAligned`), `stt-trim` (`fragment`, `echoTokens`, `uploadWithPreRoll`), `stt-shadow` (gate breadcrumb, fragmentText vs fullText, crash records), `stt-response` (per-request verbose responses — newest = shadow pass, rotation covers the fragments), `stt-text`, `llm-*`, `insertion` (#118: `surface`, `reason` LIVE/REDELIVERED/RAW_RESCUE_PARTIAL, `length`, origin/current `FieldId`, `hasConnection`), `ime-lifecycle.log`.
- **gregor** (SSH alias `gregor`, container `whisper`, `hwdsl2/whisper-server:cuda`, `large-v3`): server code `/opt/src/api_server.py`; `vad_filter=True` hardcoded; `prompt` → `initial_prompt`; GPU spikes: ~21–23-s bursts = live fragment cadence (round 2), one long spike at stop = the shadow pass. Measured: ~2.2–2.3 s per 21-s upload (0.7 s outlier). **Owner dictates with Raw mode on** — a stale `llm-prompt.json` is NOT a pipeline failure.
- The IME crashes on `?attr/` theme attrs — platform attrs / `@null` / explicit colors only in IME layouts.
- **README/fastlane screenshots must stay byte-identical** (`docs/img/*.png` ↔ `fastlane/.../phoneScreenshots/{1,2,3}-*.png`); verify with `md5sum`.
- Pulse-contrast (#87): foreground rows breathe 0.3↔1.0 on a 1333 ms sine while RECORDING.
- **Agent host has no attached device by default** — device-only checks are delegated to the owner.

Last cleared: 2026-10-10 (**#118 Phase A — insertion breadcrumb + tagged `Event.Completed` shipped; owner answers recorded; Phase B next**).
