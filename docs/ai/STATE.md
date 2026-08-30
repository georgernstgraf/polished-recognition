# Project State

Current status as of 2026-08-30.

## Current Focus
**Release prep.** #49 (default system prompt overhaul) implemented + closed (commit `b032d44`), installed on OnePlus 7T. New prompt: context-first order, concrete cleanup bullets, trailing-only Whisper hallucination strip (empty string only if entire transcription is one), positive-only output clause, "Subtitles by Amara" dropped. Full rationale in DECISIONS 2026-08-30. **Next: tag `v1.2.0`** (now includes #49).

## Completed (this cycle)
- [x] #49 default system prompt overhaul (commit `b032d44`, CLOSED): `app/src/main/assets/prompts.json` rewritten; `PromptStoreTest` + `TranscriptionPipelineTest` assertions updated; `./gradlew test` green; installed on device via `installRelease`. Placeholders/pipeline clause injection unchanged.
- [x] #45 Settings + CrashDialog theme-free (commit `70e6981`, issue CLOSED — on-device verify pending).
- [x] #44 IME voice bar redesign (commit `4bdedbc`) — on-device verified 2026-08-23.
- [x] #46 IME keyboard selectability (commit `f4ebca4`) — on-device verified.

## Pending
- [ ] **Tag `v1.2.0`** (now includes #49) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key `62f9d7b0…a76a85`) → comment on MR !40029 → force-push `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, clean at `ae5b1e5d`).
- [ ] On-device verify #45 (Settings + CrashDialog; release build already installed).

## Blockers
None. (Oplus ROM blocks adb `ime`/`settings secure`/`pm grant` — all UI-driven; not a blocker.)

## Next Session Suggestion
Tag `v1.2.0` (includes #49), run `release.yml`, bump fdroiddata metadata + comment on MR !40029, force-push the `add-polished-recognition` branch. Then on-device verify #45.
