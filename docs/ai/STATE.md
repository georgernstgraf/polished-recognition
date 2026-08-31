# Project State

Current status as of 2026-08-31 (README revamp session).

## Current Focus
**All issue-driven work complete — #58 (README revamp) CLOSED.** Remaining: MR !40029 maintainer watch; the verification-round fixes (ac75231) are on master but **unreleased** — next tag should be v1.2.1 (versionCode bump required).

## Completed (this cycle)
- [x] #58 CLOSED: README completely rewritten — IME-first positioning (owner decision), v1.2.0-accurate features (Raw mode vs "Polish only" kept distinct, custom target languages, reasoning-strip, model cache), trimmed developer section at bottom, fresh screenshots captured from the v1.2.0 device into new `docs/img/` (ime-recording, settings). Follow-up (owner): overlay screenshot + section dropped — overlay UI is pre-revamp, app presents as IME-only; `docs/img/overlay-recording.png` deleted. Old `distribution/*.png` identified as stale pre-#48/#49 UI and left untouched (store assets, separate concern).

## Pending
- [ ] MR !40029 watch: F-Droid maintainer (linsui) response to the 1.2.0 bump; fdroiddata worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition` at `1c43ae05f`.
- [ ] Cut v1.2.1 (versionCode bump) to ship the ac75231 verification-round fixes.

## Blockers
None.

## Next Session Suggestion
Cut v1.2.1 (bump versionCode → tag → release.yml → fdroiddata bump + MR comment), or wait for linsui's MR !40029 response first and bundle both.
