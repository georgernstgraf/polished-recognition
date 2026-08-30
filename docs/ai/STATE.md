# Project State

Current status as of 2026-08-30 (late evening, post-orchestration run).

## Current Focus
User-issue batch orchestrated and CLOSED: #47, #48+#53, #54 (commits `a48d279`, `2c5a315`, `db82be8`); #48 reopened and re-fixed with long-press inline edit (`c678339`). All CI green. Open: #55 (release v1.2.0 tracking) and #43 (auxiliary IME decision already shipped — see DECISIONS 2026-08-22; issue kept open as follow-up tracker).

## Completed (this cycle)
- [x] #47 reasoning-strip: `stripReasoning()` + `getContent()` choke point, 11 tests (`a48d279`).
- [x] #48+#53 editable/deletable custom target languages: rename dialog + in-dropdown non-focusable trash icon, `CustomLanguages` helper, 11 tests (`2c5a315`).
- [x] #54 mic auto-start: `AutoStartPolicy` + `onStartInputView` hook, 5 tests (`db82be8`).
- [x] #48 round 2: long-press inline edit on target-language field + always-open manage dialog, 5 commitEdit tests (`c678339`).
- [x] #55 created as release-tasks tracker (excluded from code orchestration).
- [x] #52 Gradle caching/config cache, CLOSED + validated (warm CI 28s; dry-run release validated).
- [x] #50 model dropdown UX, #51 gear hint dialog, #49 system prompt overhaul — CLOSED.

## Pending
- [ ] **#55 — release `v1.2.0`**: tag (push tag separately from branch commits) → `release.yml` (real Play upload = last untested #52 path — watch the run) → fdroiddata metadata bump → MR !40029 comment + force-push branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, verify HEAD).
- [ ] #45 leftover: CrashDialog Copy-button on-device test (`adb shell am crash com.georgernstgraf.polishedrecognition`).
- [ ] On-device verification of #48/#53 (dropdown icon-tap vs row-tap; dark-mode contrast; **long-press inline edit**: short tap opens dropdown, hold edits, new name appended+selected) and #54 (auto-start on real IME; Oplus adb restriction → Settings UI only).
- [ ] Install current build on device — `installRelease` failed once (device disconnected); run when reconnected.

## Blockers
None.

## Next Session Suggestion
Work #55: tag `v1.2.0`, watch `release.yml` live (Play upload validation), then fdroiddata bump + MR update. While the release build is installed on-device, verify #48/#53/#54 on-device (see Pending) and do the CrashDialog Copy test.
