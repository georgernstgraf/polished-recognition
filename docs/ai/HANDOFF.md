# Hand Off

## Active
- #1, #2, #3, #4 — pending closure

## Completed
- #6 R8 fix: added `listModelsSync` to API services, SettingsActivity uses `Call.execute()` and cached Retrofit instances (commit 7f3758a, pushed)
- #6 R8 fix: upgraded AGP 8.2.2→8.7.3, Gradle 8.2→8.9 — resolves R8 Kotlin 2.1 metadata warnings
- #6 test warnings: fixed nullable ClassLoader calls, suppressed deprecated ResponseBody.create
- #5 Token validation error state — closed

## Pending
- #1, #2, #3, #4: close via issue-finish workflow
- On-device release smoke test

## Next Session
1. Close #3, #2, #1, #4 via issue-finish
2. On-device release smoke test

Last updated: 2026-05-30
