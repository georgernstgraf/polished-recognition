# Project State

Current status as of 2026-07-01.

## Current Focus
**F-Droid MR !40029** — tested by community tester (pando85, SDK 36 emulator). Network-traffic question from maintainer (linsui) clarified and thread resolved. Awaiting final review/merge.

## Completed (this cycle)
- [x] Release 1.1.1: reproducible F-Droid build (`Binaries:` + `AllowedAPKSigningKeys:`, RSA-2048 release key, `dependenciesInfo.includeInApk=false`). Pinned v1.1.1; `UpdateCheckMode: Tags ^v` to skip `build-*` tags. All 9 CI jobs green incl `check apk`.
- [x] F-Droid MR !40029 community test (pando85, 2026-07-01): app installs/launches, recording UI works, no crashes, no analytics/tracking. Network question from linsui ("I thought the network connects are from the emulator itself") — clarified: app uses user-configured cloud APIs (not on-device ML), INTERNET permission is for STT/LLM endpoints, no traffic observed because no API key was configured. Thread resolved.
- [x] Knowledge addons: MR URL + glab CLI auth recorded in CONVENTIONS.md; "on-device ML" misunderstanding pitfall added to PITFALLS.md.

## Pending
- [ ] F-Droid MR !40029 — awaiting maintainer merge.
- [ ] Issue #20: Play Console preconditions before production track.

## Blockers
None.

## Next Session Suggestion
Monitor MR !40029 for merge. On new version release, update the MR per CONVENTIONS.md F-Droid workflow.
