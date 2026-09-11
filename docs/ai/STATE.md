# Project State

Current status as of 2026-09-11 (v1.2.2 RELEASED to Play alpha + GitHub; F-Droid auto-update pending — #76 open. Open: #76 watch, #74 launch marketing, #75 rate-limit logging, #67, #64).

## Current Focus
**#76 — v1.2.2 publication**: tag `v1.2.2` (bump 42ec028, versionCode 10202) pushed; `release.yml` uploaded the AAB to **Play alpha** (edit committed `07044436216605520550`, status=completed, whatsnew attached) and `fdroid-apk.yml` attached the reproducible `polished-recognition.apk` to the GitHub release. Remaining: F-Droid side — fdroidbot auto-update (`AutoUpdateMode: Version`, no manual MR) must produce the 1.2.2 metadata + build on fdroiddata. #76 stays open until then.

## Completed (this cycle)
- [x] #76 (standalone release issue) created — split from #74 (marketing): release mechanics vs. listing assets.
- [x] #74 body corrected: listing package → **v1.2.3** (F-Droid reads fastlane metadata from the built tag; 1.2.2 ships without listing assets); release mechanics → #76; no fdroiddata MR for updates (AutoUpdateMode).
- [x] v1.2.2 release: `whatsnew-en-GB` refreshed (#66/#73/#72/#69 wording), bump 10202/"1.2.2", tag pushed, both tag-workflows green, GitHub release carries `app-release.aab` + `polished-recognition.apk`.
- [x] Tests 237/237 green — after fixing local `.env`: Groq **retired `llama-3.3-70b-versatile`**, replaced with `openai/gpt-oss-120b` (gitignored; CI unaffected).
- [x] #69: closed (pulse 0.15/dwell, verified in code; S5 feel-check not explicitly repeated).
- [x] #63: closed as by design (SettingsActivity is launcher activity → recents behavior).
- [x] #72: gear→keyboard-switch→Settings flow, owner smoke test PASS, closed.
- [x] #73: field-start leading blank removed, owner feel-check PASS, closed.

## Pending
- [ ] #76: F-Droid publication watch (fdroidbot MR "Add 1.2.2" → green → build on f-droid.org) — then close.
- [ ] #74: F-Droid launch marketing Phase 0/1 — next step **v1.2.3 listing-assets release** (fastlane `images/` icon + phoneScreenshots, full_description rewrite, README/INSTALLATION badges, then tag v1.2.3).
- [ ] #75: rate-limit header logging (remaining-requests/-tokens, reset headers, retry-after, 429 counts; local persistence + simple usage view in Settings).
- [ ] #67: disk snapshot of paused dictation (standalone deferred `enhancement`).
- [ ] #64: Ogg/Opus compression latency — measure per-stage transcode timings on the S5 first.
- [ ] Insertion-spacing watch: owner refinements from longer use — DOMAIN.md is the rule reference; changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Blockers
None.

## Device Notes
- d890cc9e = **S5** (SM-G900F, LineageOS 18.1, 1080×1920) — adb `ime`/`settings put secure` WORK (userdebug). f6de166c = **OnePlus 7T** (HD1903, Oplus, 1080×2400) — Settings UI only; adb IME writes blocked (verified 2026-09-07), reads work.
- HeliBoard IS installed on the S5 (only disappears from `ime list -s` when disabled); its mic uses the system `voice_recognition_service`, NOT the auxiliary IME. HeliBoard is currently the default keyboard on the OnePlus.
- Oplus suppresses app-level IME logcat — use on-screen observation or the rotating /sdcard JSON logs.
- Screenshot sessions on a configured device LIVE-record on field focus — cancel explicitly (#128 pattern).

## Next Session Suggestion
#76 F-Droid watch (then close), then #74 v1.2.3 listing-assets release, then #75 (rate-limit logging) or #64 (Ogg latency timings) / #67.
