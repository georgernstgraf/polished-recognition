# Hand Off

**Active: #57 (pulse root cause fixed — needs on-device verify + RMS calibration) and #55 (release v1.2.0 tracking). #56 partially fixed (quick settings during recording) — verify + language follow-up pending. #43 excluded by user decision.**

## Open tasks

1. [ ] **#57 on-device verify + calibrate** (user dictates once): breathing (0.6↔0.9, 2 s) during silence, brightening with speech; then `adb logcat -d -s PolishedRMS` → finite rms values (NaN root cause fixed in `computePcmRms`); tune `RMS_CEILING` (2500) in `RmsAlphaMapper` if speech saturates or ambient exceeds the 200 gate; **remove the temporary `Log.d("PolishedRMS")` diagnostics after calibration**.
2. [ ] **#56 verify**: RAW + language selectable during recording AND pause (shipped). Language: clause wording hardened (option C, `43933fb`); user message stays pure `{{text}}` (locked); gpt-oss-120b never miscomplies (model-dependent). Verify new wording in next `llm-prompt.json`; model A/B in Settings if still flaky.
3. [ ] **#55 — Tag `v1.2.0`** (now includes #57 rounds 1–4 + #56 fix) → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (keep `Binaries`/`AllowedAPKSigningKeys`, key `62f9d7b0…a76a85`) → comment on MR !40029 → force-push `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`; verify HEAD). Push the tag separately from branch commits (PITFALLS). Watch the real Play upload run.
4. [ ] **#45 leftover**: CrashDialog Copy-button on-device test (`adb shell am crash com.georgernstgraf.polishedrecognition`).
5. [ ] **On-device verify** (same session): #48/#53 dropdown trash vs label tap, dark-mode contrast, long-press inline edit; #54 auto-start on keyboard show; #48/#53/#54/#45 batch.

## Known on-device gotchas (Oplus/OnePlus)

- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished` + `dumpsys package … | grep RECORD_AUDIO`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → HeliBoard's mic won't route to Polished. Use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts. (Also true for Settings/Crash layouts under `Theme.DeviceDefault` — see PITFALLS.)
- "Manage Keyboards" greyed-out behavior matches AOSP — no Oplus OEM override (7-row evidence table in PITFALLS).
- Diagnostic logs readable via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (llm-prompt/llm-response rotating JSON) — no root needed; invaluable for prompt/response evidence.

Last cleared: 2026-08-31 (round 4 — RMS NaN root cause, single-animator pulse, #56 quick settings; installed 08:22 on f6de166c).
