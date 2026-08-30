# Project State

Current status as of 2026-08-30.

## Current Focus
**#50 (rounds 2–3) + #51 on-device verification.** #50 round 3 (commit `643cc38`): validation flag also fires on selections in other dropdowns (focusable=false quirk), blank-model error hints "a space shows all", cache-aware toast. #50 round 2 (`9847d9a`): per-URL model cache with 6-week TTL, migration, tap-race fix, symmetric validation. #51 (`b7dd88c`): `SettingsHintActivity` for IME gear + notification. All installed on OnePlus 7T. #49 closed (`b032d44`). User works in parallel on #52 (Gradle wrapper/CI). **Next: verify on device, close #50/#51, tag `v1.2.0`.**

## Completed (this cycle)
- [x] #50 round 3 (commit `643cc38`): dropdown-selection validation, hinting error text, cache-aware toast. NOTE: commit also carries #52's leftover wrapper files (attribution mixed, content correct).
- [x] #50 round 2 (commit `9847d9a`): per-URL model cache + 6-week TTL + migration in `SettingsStore` (+8 `SettingsStoreTest` cases); `performFiltering` exact-match→full list; symmetric validation; provider-switch/URL-focus dropdown reloads.
- [x] #50 round 1 (commit `b87e018`): tap→full list hook, `textNoSuggestions`+autofill-off, early focus-loss flag (two defects found on-device → round 2).
- [x] #51 IME gear hint dialog (commit `b7dd88c`): `SettingsHintActivity` trampoline; gear + notification repointed.
- [x] #49 default system prompt overhaul (commit `b032d44`, CLOSED): context-first prompt, trailing-only hallucination strip, positive output clause; installed on device.
- [x] #45 Settings + CrashDialog theme-free (commit `70e6981`, issue CLOSED — dropdowns/validate/save verified on-device 2026-08-30; CrashDialog Copy test still pending).
- [x] #44 IME voice bar redesign (commit `4bdedbc`) — on-device verified 2026-08-23.
- [x] #46 IME keyboard selectability (commit `f4ebca4`) — on-device verified.

## Pending
- [ ] On-device verify #50 (rounds 2–3)/#51 (see Current Focus) + CrashDialog Copy leftover; close issues.
- [ ] **Tag `v1.2.0`** (includes #49/#50/#51) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key `62f9d7b0…a76a85`) → comment on MR !40029 → force-push `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, clean at `ae5b1e5d`).
- [ ] #52 attribution note (see HANDOFF open task 3).

## Blockers
None. (Oplus ROM blocks adb `ime`/`settings secure`/`pm grant` — all UI-driven; not a blocker.)

## Next Session Suggestion
Verify #50/#51 on device (close issues when green), then tag `v1.2.0`, run `release.yml`, bump fdroiddata metadata + comment on MR !40029, force-push the `add-polished-recognition` branch.
