# Hand Off

**Active: #57 (two-line IME — implemented, installed on device, awaiting user on-device verification) and #55 (release v1.2.0 tracking). #47/#48/#53/#54 all implemented + CLOSED. #43 excluded by user decision.**

## Open tasks

1. [ ] **#57 on-device verify** (UI-only on Oplus — adb IME ops blocked): flash pulse (whole bar, full contrast ↔ slight gray) while recording; pause/resume button enlarged ~1.3× when paused; during processing row 1 shows "Transcribing (STT)…"/"Polishing (LLM)…" in place of spinner/Raw (Raw mode → only STT stage); gear at far left of row 1 still opens the hint dialog.
2. [ ] **#55 — Tag `v1.2.0`** (now includes #57) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key unchanged `62f9d7b0…a76a85`) → comment on MR !40029 → force-push the `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`; verify current HEAD). Push the tag separately from branch commits (PITFALLS). The real Play upload step of `release.yml` is the only #52-related path not yet exercised live — watch that run.
3. [ ] **#45 leftover**: CrashDialog Copy-button on-device test (force a crash: `adb shell am crash com.georgernstgraf.polishedrecognition`).
4. [ ] **On-device verify** (same install session as the v1.2.0/#57 check):
   - #48/#53: dropdown trash-icon tap deletes without selecting; label tap still selects; icon contrast in dark mode; rename dialog (dedupe + re-point of active selection); **long-press the language field → inline edit → new name appended+selected without Save; short tap still opens dropdown**.
   - #54: auto-start fires on keyboard show when IDLE + mic granted; PAUSED session stays paused after hide; no permission-activity spam when RECORD_AUDIO missing.

## Known on-device gotchas (Oplus/OnePlus)

- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished` + `dumpsys package … | grep RECORD_AUDIO`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → HeliBoard's mic won't route to Polished. Use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts. (Also true for Settings/Crash layouts under `Theme.DeviceDefault` — see PITFALLS.)
- "Manage Keyboards" greyed-out behavior matches AOSP — no Oplus OEM override (7-row evidence table in PITFALLS).

Last cleared: 2026-08-31 (#57 implemented + installed on f6de166c; knowledge files current).
