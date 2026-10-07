# Project State

Current status as of 2026-10-07 (**v1.3.5 RELEASED**: bump `8738516`, tag pushed separately, `release.yml` green with Play-alpha commit `14941024215117549040`, `fdroid-apk.yml` + `build.yml` green, AAB + APK on the GitHub release; F-Droid pickup watch open. **#105 + #114 CLOSED**, #112 restore-tap pending, #113 freeze needs recurrence data). `main` is at `686b653` (cleartext) on top of `beed16d` (#105: `SttResponse.languageProbability` + three-branch source-language clause + new `config/LanguageMapper` with the complete 100-entry Whisper `LANGUAGES` table, `TranscriptionPipelineTest` +2, `LanguageMapperTest` +7) and `64f3eec` (#112: `prompts.json` system default replaced with the owner's crafted version). `./gradlew test` green, `installRelease` on the OnePlus (HD1903, Android 12) green. **On-device verified:** local LAN STT server returns `"de"`/0.9995 → clause reads *"The Whisper service detected the recognized language as German with a probability of 100 percent."* (`llm-prompt.json` 23:42); LAN STT responses flow post-cleartext-fix. Latest public release **v1.3.3 (10303)**; F-Droid serves **1.3.3** with a complete listing. **v1.3.4 tag still pending** (owner go-ahead). **#74 Phase 1: Mastodon POSTED 2026-10-02**; owner profile verified via `rel="me"`. **#82 (RecognitionService) CLOSED 2026-10-03**, verified with Duolingo (A03 Core + OnePlus); scope Android 12+ only. **#107/#106 shipped + closed 2026-10-05** (in-app help, Pages overhaul). **#109 icon implemented, owner visual check pending.**

## Current Focus
Code-side: **#113 IME freeze** (single transient occurrence 2026-10-07 — no blink, timer stuck, pause/trash dead, close worked; prime suspect: PROCESSING hung on a slow local-STT upload; needs recurrence data from owner) and **#112 on-device confirm** (tap "Restore Default Prompts"). Release-side: push `v1.3.4` on the owner's go-ahead, then watch the three workflows. Growth-side: #74 Phase 1 wave (r/fossdroid, r/degoogle Showcase, r/selfhosted modmail, kuketz, Facebook #108) + Play-alpha recruitment.

## Completed (recent cycles)
- [x] #114 CLEARTEXT HTTP ALLOWED + CLOSED 2026-10-07 (`686b653`) — `res/xml/network_security_config.xml` (base-config cleartext permitted) wired via manifest; LAN STT (`http://10.8.0.16:11437/v1/`) validated + transcribing on-device the same evening.
- [x] #105 SOURCE-LANGUAGE CLAUSE + CLOSED 2026-10-07 (`beed16d` + mapper follow-up) — `language_probability` plumbed through DTO→pipeline; clause variant B (*"detected the recognized language as <Name> with a probability of X percent"*), old formulation without probability, dropped on unknown; `LanguageMapper` (100-entry Whisper table, codes + names); web research ruled out `avg_logprob` as a language-confidence substitute (transcription confidence only); on-device verified. Threshold/preferred-language ideas explicitly deferred (owner).
- [x] #112 DEFAULT PROMPT UPDATED 2026-10-07 (`64f3eec`) — owner's crafted prompt in `prompts.json` ("smarrphone" typo fixed); open: owner restore-tap confirm.
- [x] #113 IME FREEZE TICKETED 2026-10-07 (no code yet) — analysis comment on the issue; single occurrence, pipeline healthy after restart.
- [x] #109 custom notification mic icon implemented 2026-10-05 (`e623833`, owner visual check pending); #107 in-app help + #106 Pages overhaul shipped + closed; #104 Cortecs preset; #103 hyphen line-wrap on `main`; #102 closed; v1.3.4 (10304) prepared, tag pending; Mastodon launch posted + profile verified; #82 closed (verified with Duolingo).
- [x] Earlier — see HISTORY.md / tracker.

## Pending
- [ ] **F-Droid 1.3.5 pickup watch (automatic)** — ~3–5 days after the tag; reopen only if it stalls.
- [ ] **#112 verify (owner)** — tap "Restore Default Prompts" in Settings, confirm the crafted prompt appears.
- [ ] **#113 freeze recurrence (owner)** — if it happens again: stage-line content, X-tap vs system-back, plus `ime-lifecycle.log` + STT/LLM log timestamps.
- [ ] **#109 visual check (owner)** — notification mic icon variant B on the OnePlus, both paths.
- [ ] **Tag v1.3.4 (owner)** — `git tag v1.3.4 && git push origin v1.3.4` (push the tag separately).
- [ ] **#74 Phase 1 posting (owner)** — Mastodon ✅ (2026-10-02); remaining: r/fossdroid, r/degoogle (Showcase thread), r/selfhosted (modmail), kuketz, **Facebook (#108)**. Also paste the welcome message into the Google Group.
- [ ] **#74 Play-alpha recruitment (owner)** — 20–30 testers, ≥12 continuous 14 days.
- [ ] **#101 decision (owner)** — whether API tokens stay in Google Auto Backup.
- [ ] **#99 (owner)** — Google overview page shows outdated application images.
- [ ] **#75** — rate-limit header logging.
- [ ] **#64** — Ogg/Opus compression latency.
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

## Next Session Suggestion
Verify #112 (restore tap) and close it; watch for #113 recurrence data. Release-side: push `v1.3.4` on the owner's go-ahead. Growth-side: continue the #74 Phase 1 wave. Code-side: #75 / #64.
