# Project State

Current status as of 2026-10-03 (**#103 shipped to `main`; Mastodon launched; v1.3.4 prepared**). `main` carries the #103 hyphen line-wrap (`80e6687`, unit-tested, not yet released) alongside the release bump `fee52fe` — `versionCode 10304`, `versionName 1.3.4`, and a `whatsnew-en-GB` entry for the per-app line-wrap (#100/#102). The `v1.3.4` tag is **not yet pushed** (owner go-ahead pending); pushing it triggers `release.yml` (Play alpha), `fdroid-apk.yml`, `build.yml`. This release ships **#100 + #102** (per-app line-wrap width + last-used-app-first dropdown, dictation target recorded at dictation start). **#102 CLOSED** after the owner's feel-check (daily use). Latest public release **v1.3.3 (10303)**; F-Droid serves **1.3.3** with a complete listing. **#74 Phase 1: the Mastodon thread was POSTED 2026-10-02** (toot 1 = demo GIF + pitch, toot 2 = thank-you to `@fdroidorg@floss.social`/linsui, doubling as the boost request, + Play-alpha CTA); the owner's profile `@schurlix` is **verified** against the project Pages via `rel="me"`, and the GitLab MR !40029 thanks is done. Google Group settings verified (join = Anyone on the web, post = Managers). **#82 (RecognitionService): the delivery bug is fixed** (`d327d43` — secondary-only sessions now receive `Completed`; `VoiceSessionControllerSecondaryTest` +1, `./gradlew test` green); on-device verification is currently blocked by an OxygenOS caller-permission issue (see PITFALLS). Default branch `main`. Open: #99, #101, #82, #75, #64, #74.

## Current Focus
**Marketing wave (#74 Phase 1)** — **Mastodon POSTED 2026-10-02**; next: r/fossdroid (own words — the sub bans AI promo), r/degoogle (weekly "Degoogle Showcase" thread), r/selfhosted (modmail first), kuketz (DE, 2nd wave), 1–2 days apart. **Release v1.3.4 (10304)** — tag pending the owner's go-ahead; then F-Droid auto-pickup (~3–5 days). Separately, **#74 Play-alpha tester recruitment** (20–30 testers, ≥12 continuous for 14 days).

## Completed (recent cycles)
- [x] #82 DELIVERY FIX shipped to `main` 2026-10-03 (`d327d43`) — `stopAndTranscribe` now delivers `Completed` to a **secondary-only** consumer via a `deliver()` helper (primary callback OR secondary listeners; `pendingResult` only when nobody listens, #83). Root cause of the long-standing "recognition works, caller gets error 5": the bound `RecognitionService` was never the primary slot, so on a keyboard-less call the result was swallowed and the caller got `ERROR_CLIENT`. Confirmed on-device via the app's `stt/llm-response` logs (correct text produced) vs. Duolingo error 5. `VoiceSessionControllerSecondaryTest` +1; `./gradlew test` green. **On-device re-verification is blocked** by an OxygenOS caller-permission issue on the OnePlus (see PITFALLS); #82 stays open for a clean-ROM check.
- [x] #103 SHIPPED to `main` 2026-10-03 (`80e6687`) — `LineWrapPolicy` breaks words after `-`/`–`/`—` (classic greedy), URL-like tokens stay unbreakable, a fragment < 4 chars on either side of a dash is merged (no orphans), U+2011 is never a break point; words without dashes unchanged. `LineWrapPolicyTest` +9; `./gradlew test` green. Not yet in a release (ships with the next bump).
- [x] #74 Mastodon launch POSTED 2026-10-02 — two-toot thread live (toot 1: demo GIF + pitch; toot 2: `@fdroidorg@floss.social` thank-you, especially linsui, doubling as the one-off boost request, + the Play-alpha CTA). Owner profile `@schurlix` **verified** against the project Pages via `rel="me"` (`262e11c`; `verified_at 2026-10-02T05:06:17Z`). The F-Droid handle was corrected to `@fdroidorg@floss.social` (the `@fdroidorg@mastodon.social` one is an empty placeholder, `aa36ff0`); `docs/marketing/mastodon.md` records the posted toot 2 (`5988ac0`). GitLab MR !40029 thanks to linsui done (owner, not on Mastodon). Imported 17 follows; ~25 hashtags followed.
- [x] #74 Mastodon draft refined 2026-10-01 (`26e6b87`, `65b89b1`, `7e7ccc0`) — the demo GIF is now described in the toot body (not just alt text; precise alt text added); it is **German speech → English translation** (the bar's language is the *target*, not the source); `qwen3.8-27b` + the owner's daily use + **GROQ's free tier** (`whisper-large-v3`) are named; the post is a two-toot thread (toot 1 = 488 chars with the GIF, toot 2 = Play-alpha two-step + issues). A trimmed **540×700** social GIF (`docs/marketing/mastodon-demo.gif`, 580 px empty band removed) replaces the tall original on Mastodon. `./gradlew test` green; pushed.
- [x] #102 CLOSED 2026-10-01 — owner feel-check done (app in daily use). Final report on the issue; ships in v1.3.4 (10304). (Implementation: `9c82c27` MRU + `88dbc45` dictation-start amendment.)
- [x] v1.3.4 (10304) PREPARED on `main` 2026-10-01 (`fee52fe`) — `versionCode 10304`, `versionName 1.3.4`, `whatsnew-en-GB` describes the per-app line-wrap (#100/#102); `./gradlew test` + `assembleRelease` green. Tag pushed separately on the owner's go-ahead.
- [x] #74 Phase 1 marketing drafts 2026-10-01 (`65526b5`) — version-free refresh of the 6 posts, timing headers generalized off the stale 1.3.0/1.2.4 references, and new `docs/marketing/alpha-welcome.md` (Google Group welcome message + canonical two-step tester instructions). F-Droid listing verified complete.
- [x] #102 AMENDMENT on `main` 2026-09-30 (`88dbc45`) — `recordKnownApp` at dictation start (IME + service) so the currently dictated app is top/pre-selected; in-memory MRU `mruOrder`; no extra disk I/O; `./gradlew test` 281 green.
- [x] #100 SHIPPED to `main` 2026-09-28 (`b7fa292`) — per-app line-wrap width with learned apps, separate `known_apps` prefs, 24 h-throttled async writes, Settings "Line wrap per app" section.
- [x] v1.3.3 (10303) RELEASED 2026-09-24 — bump `377e4a2`, whatsnew-en-GB, tag `v1.3.3`; release.yml (Play alpha) + fdroid-apk.yml + build.yml green.
- [x] #97 / #96 / #94 / #95 / #93 / #92 / #91 and earlier — see HISTORY.md / tracker.

## Pending
- [ ] **Tag v1.3.4 (owner)** — `git tag v1.3.4 && git push origin v1.3.4` (push the tag separately).
- [ ] **#74 Phase 1 posting (owner)** — Mastodon ✅ (2026-10-02); remaining: r/fossdroid, r/degoogle (Showcase thread), r/selfhosted (modmail), kuketz. Also paste the welcome message into the Google Group.
- [ ] **#74 Play-alpha recruitment (owner)** — 20–30 testers, ≥12 continuous 14 days.
- [ ] **#101 decision (owner)** — whether API tokens stay in Google Auto Backup.
- [ ] **#99 (owner)** — Google overview page shows outdated application images.
- [ ] **#82 verification (owner, on a clean ROM)** — the delivery fix is on `main` (`d327d43`), but the OnePlus/OxygenOS blocks the recognition preflight for the caller (`RECORD_AUDIO`) before our code runs. Verify Duolingo/Corvus on the LineageOS S5 (or another clean ROM), then `finish` #82. IME regression trio unchanged (daily use).
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

## Next Session Suggestion
Growth-side: continue the #74 Phase 1 wave — r/fossdroid (own words, no AI), r/degoogle (weekly Showcase thread), r/selfhosted (modmail first), then kuketz; drive Play-alpha recruitment. Release-side: push `v1.3.4` on the owner's go-ahead, then watch the three workflows. Code-side: `finish` #82 / #75 / #64.
