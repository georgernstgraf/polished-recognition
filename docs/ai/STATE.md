# Project State

Current status as of 2026-06-02.

## Current Focus
All open issues closed. Ready for new features.

## Completed (this cycle)
- [x] #5 — Token validation fix: TextWatcher clears error on text change, HTTP error body details displayed, dead tautology filter removed
- [x] #16 — VoiceRecognitionActivity rotation fix: added `android:configChanges="orientation|screenSize"` to prevent Activity restart and recording data loss
- [x] #17 — CI: use local debug keystore for consistent APK signatures
- [x] 4 GitHub Secrets set: DEBUG_KEYSTORE, DEBUG_STORE_PASSWORD, DEBUG_KEY_ALIAS, DEBUG_KEY_PASSWORD
- [x] CI workflow decodes keystore into `~/.android/debug.keystore` before `assembleRelease`

## Pending
- [ ] None

## Blockers
None

## Next Session Suggestion
On-device smoke test of voice recording + token validation. Consider new feature or improvement.
