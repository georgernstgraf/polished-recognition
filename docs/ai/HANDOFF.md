# Hand Off

**No active code issues. v1.2.0 released (#55/#56/#57 all CLOSED). Remaining: on-device verification batch (#48/#53/#54/#45) and MR !40029 maintainer watch.**

## Open tasks

1. [ ] **On-device verification batch** (v1.2.0 already installed on f6de166c as of 09:33):
   - #48/#53: dropdown trash-icon tap deletes without selecting; label tap still selects; icon contrast in dark mode; long-press inline edit (new name appended+selected; short tap opens dropdown).
   - #54: auto-start fires on keyboard show when IDLE + mic granted; PAUSED session stays paused after hide.
   - #45: CrashDialog Copy-button test (`adb shell am crash com.georgernstgraf.polishedrecognition`).
2. [ ] **MR !40029 watch**: F-Droid maintainer (linsui) response to the 1.2.0 bump; fdroiddata worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition` at `1c43ae05f` (branch `add-polished-recognition`, force-pushed with verified lease).

## Known on-device gotchas (Oplus/OnePlus)

- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (llm-prompt/llm-response rotating JSON) — no root needed.
- Pulse diagnostics: re-enable the commented `Log.d` block in `PolishedVoiceInputIME.onRmsChanged` (tag `PolishedRMS`).

Last cleared: 2026-08-31 (late — v1.2.0 released, #55/#56/#57 closed, knowledge current).
