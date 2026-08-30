# Project State

Current status as of 2026-08-30 (late evening).

## Current Focus
**#52 (Gradle caching) complete — all workflows validated.** Next: tag `v1.2.0` (deferred by user to a later session).

## Completed (this cycle)
- [x] #52 Gradle caching/config cache, CLOSED: repo `gradle.properties` gains `caching`+`parallel`+`configuration-cache`; `setup-gradle@v4` in all 3 workflows; Robolectric jar cache in `build.yml`; single merged invocation `test assembleRelease bundleRelease`; `release.yml` gets `workflow_dispatch` `dry_run` (skips GH Release + Play upload). Wrapper jar regenerated to official checksum (commits `66eb197`, `3eb5927`, `0340ac9`). Validated: warm CI build step 28s (was ~187s); `release.yml` dry-run 1m27s with upload correctly skipped; `fdroid-apk.yml` rebuild of `v1.1.1` byte-identical (sha256 `53d06787…`).
- [x] #50 model dropdown UX, 3 rounds, CLOSED + verified.
- [x] #51 IME gear hint dialog, CLOSED + verified.
- [x] #49 default system prompt overhaul, CLOSED + installed.
- [x] #45 Settings + CrashDialog theme-free (commit `70e6981`, CLOSED; CrashDialog Copy test still outstanding).
- [x] #44 IME voice bar redesign (commit `4bdedbc`) — on-device verified 2026-08-23.
- [x] #46 IME keyboard selectability (commit `f4ebca4`) — on-device verified.

## Pending
- [ ] **Tag `v1.2.0`** (includes #49/#50/#51) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key `62f9d7b0…a76a85`) → comment on MR !40029 → force-push `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, clean at `ae5f1e5d`… verify current HEAD). Push the tag separately from branch commits (see PITFALLS).
- [ ] #45 leftover: CrashDialog Copy-button on-device test.
- [ ] Play upload step in `release.yml` untested with a real AAB since #52 changes — the `v1.2.0` tag run is the final live validation (dry-run already verified everything except the Play upload step itself).

## Blockers
None.

## Next Session Suggestion
Tag `v1.2.0` (push tag separately from branch commits), verify `release.yml` + `fdroid-apk.yml` live runs — this also completes the last #52 validation (real Play upload). Then fdroiddata metadata bump + MR !40029 comment + force-push. Quick win: CrashDialog Copy test while the release build is installed.
