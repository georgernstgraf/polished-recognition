# Project State

Current status as of 2026-08-30.

## Current Focus
**#50 + #51 on-device verification.** Both implemented (commits `b87e018`/`b7dd88c`) and installed on OnePlus 7T. #50: model dropdown tap→full list, `textNoSuggestions`, strict list + early focus-loss flag. #51: IME gear + notification open `SettingsHintActivity` instead of Settings (kills circular dependency with the active keyboard). #49 (system prompt overhaul) closed earlier today (commit `b032d44`). **Next: verify on device, close #50/#51, tag `v1.2.0`.**

## Completed (this cycle)
- [x] #50 model dropdown UX (commit `b87e018`): `ModelFilterAdapter.showAll()` + tap hook; `text|textNoSuggestions` + `importantForAutofill="no"`; focus-loss error flag; save trims text.
- [x] #51 IME gear hint dialog (commit `b7dd88c`): `SettingsHintActivity` trampoline; gear + notification repointed.
- [x] #49 default system prompt overhaul (commit `b032d44`, CLOSED): context-first prompt, trailing-only hallucination strip, positive output clause; installed on device.
- [x] #45 Settings + CrashDialog theme-free (commit `70e6981`, issue CLOSED — dropdowns/validate/save verified on-device 2026-08-30; CrashDialog Copy test still pending).
- [x] #44 IME voice bar redesign (commit `4bdedbc`) — on-device verified 2026-08-23.
- [x] #46 IME keyboard selectability (commit `f4ebca4`) — on-device verified.

## Pending
- [ ] On-device verify #50/#51 (see Current Focus) + CrashDialog Copy leftover; close issues.
- [ ] **Tag `v1.2.0`** (includes #49/#50/#51) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key `62f9d7b0…a76a85`) → comment on MR !40029 → force-push `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, clean at `ae5b1e5d`).

## Blockers
None. (Oplus ROM blocks adb `ime`/`settings secure`/`pm grant` — all UI-driven; not a blocker.)

## Next Session Suggestion
Verify #50/#51 on device (close issues when green), then tag `v1.2.0`, run `release.yml`, bump fdroiddata metadata + comment on MR !40029, force-push the `add-polished-recognition` branch.
