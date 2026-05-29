# Hand Off

## Active
- #6 Fix R8/ProGuard ClassCastException — implemented, needs release build verification
- #1, #2, #3, #4 — pending closure

## Completed
- #6 R8 fix: added `listModelsSync` to API services, SettingsActivity uses `Call.execute()` and cached Retrofit instances (commit 7f3758a, pushed)
- #5 Token validation error state — closed

## Pending
- #6: build release APK (`./gradlew assembleRelease`), verify token validation no longer crashes, then close
- #1, #2, #3, #4: close via issue-finish workflow
- On-device release smoke test

## Next Session
1. Build and verify release APK for #6
2. Close #6 if verified
3. Close #3, #2, #1, #4 via issue-finish

Last updated: 2026-05-30
