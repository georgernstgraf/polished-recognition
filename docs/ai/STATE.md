# Project State

Current status as of 2026-08-31 (late, post v1.2.0 release).

## Current Focus
**v1.2.0 RELEASED.** #55, #56, #57 all CLOSED. Release pipeline fully validated including the previously-untested real Play upload. Remaining known work: #45 CrashDialog on-device test, #48/#53/#54 on-device verifications (batchable into one session).

## Completed (this cycle)
- [x] #57 CLOSED (rounds 1–5): two-line IME, hybrid voice-reactive pulse (breathing 0.9↔0.45 + gated RMS), RMS NaN root cause fixed (`computePcmRms`), inline stage display, gear divider, paused scale-up, quick settings during recording. Commits `0826a6d`,`7d95d84`,`f7d38e9`,`8d56d20`,`f7d38e9`,`43933fb`(->#56),`92698f3`,`558890b`.
- [x] #56 CLOSED: quick settings during recording shipped; language clause hardened (option C); language compliance evidence documented (model-dependent; gpt-oss-120b reliable).
- [x] #55 CLOSED: tag `v1.2.0` pushed separately; `release.yml` run 33369074786 SUCCESS — **real Play upload validated live**; GitHub release with signed APK; fdroiddata bumped to 1.2.0/10200 (commit `1c43ae05f`, force-pushed with verified lease `ae5b1e5dc`); MR !40029 note posted.
- [x] RMS diagnostics removed (kept as code comment with re-enable note, tag `PolishedRMS`).

## Pending
- [ ] #45 leftover: CrashDialog Copy-button on-device test (`adb shell am crash com.georgernstgraf.polishedrecognition`).
- [ ] On-device verification (v1.2.0 install session): #48/#53 dropdown trash vs label tap, dark-mode contrast, long-press inline edit; #54 auto-start on keyboard show; PAUSED-after-hide.

## Blockers
None.

## Next Session Suggestion
Batch the remaining on-device verifications (#48/#53/#54/#45) against the freshly installed v1.2.0 build; watch for F-Droid maintainer response on MR !40029.
