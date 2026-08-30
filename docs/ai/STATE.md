# Project State

Current status as of 2026-08-30 (evening).

## Current Focus
**Release prep.** All issues from this cycle are CLOSED + verified on device (OnePlus 7T): #49 (system prompt overhaul, `b032d44`), #50 (model dropdown UX, rounds `b87e018`/`9847d9a`/`643cc38`), #51 (IME gear hint dialog, `b7dd88c`). User works in parallel on #52 (Gradle wrapper/CI). **Next: tag `v1.2.0` (deferred by user to a later session).**

## Completed (this cycle)
- [x] #50 model dropdown UX, 3 rounds, CLOSED + verified: tap→full list (race fixed via exact-match→full list), per-URL model cache with 6-week TTL + migration, `textNoSuggestions`+autofill-off, symmetric blank/not-in-list validation (focus loss + non-focusable dropdown selections), hinting error text, cache-aware toast.
- [x] #51 IME gear hint dialog, CLOSED + verified: `SettingsHintActivity` trampoline; gear + mic-notification repointed; circular dependency broken.
- [x] #49 default system prompt overhaul, CLOSED + installed: context-first prompt, trailing-only hallucination strip, positive output clause.
- [x] #45 Settings + CrashDialog theme-free (commit `70e6981`, CLOSED; dropdowns/validate/save verified 2026-08-30 — CrashDialog Copy test still outstanding).
- [x] #44 IME voice bar redesign (commit `4bdedbc`) — on-device verified 2026-08-23.
- [x] #46 IME keyboard selectability (commit `f4ebca4`) — on-device verified.

## Pending
- [ ] **Tag `v1.2.0`** (includes #49/#50/#51) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key `62f9d7b0…a76a85`) → comment on MR !40029 → force-push `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, clean at `ae5b1e5d`).
- [ ] #45 leftover: CrashDialog Copy-button on-device test.
- [ ] #52 attribution note (see HANDOFF open task 3).

## Blockers
None. (Oplus ROM blocks adb `ime`/`settings secure`/`pm grant` — all UI-driven; not a blocker.)

## Next Session Suggestion
Tag `v1.2.0` (push the tag separately from branch commits — release.yml trigger pitfall), verify `release.yml` + `fdroid-apk.yml` runs, then fdroiddata metadata bump + MR !40029 comment + force-push. Quick win: CrashDialog Copy test while the release build is installed.
