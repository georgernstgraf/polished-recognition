# Project State

Current status as of 2026-06-01.

## Current Focus
Issue #14 completed — dynamic language dropdown with persistent custom languages, delete via Manage dialog, info dialog shortened.

## Completed (this cycle)
- [x] #14 — Dynamic language selection dropdown with "No Translation" + "English" built-in, user‑typed custom languages persisted in SharedPreferences, `LanguageDropdownAdapter` (BaseAdapter+Filterable), delete via "Manage saved languages" dialog, `threshold=1` for filtering
- [x] #15 — Info dialog shortened to English‑language note + clickable README link (done as part of #14)
- [x] Custom languages property in `SettingsStore` (JSON array)
- [x] `ic_delete.xml` trash can vector drawable
- [x] `item_language_dropdown.xml` (plain TextView) and `item_manage_language.xml` (text + delete button) layouts
- [x] Convention: always install via `installRelease` (debug APK uses `.debug` suffix → wrong app ID for voice recognition)

## Pending
- [ ] None

## Blockers
None

## Next Session Suggestion
Issue #14 closed. Next: issue #15 (resume button green in paused state) or new features.
