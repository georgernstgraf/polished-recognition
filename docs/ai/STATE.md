# Project State

Current status as of 2026-08-23.

## Current Focus
**#45 Settings + CrashDialog theme-free** — implemented, committed (`70e6981`), pushed, issue CLOSED. `assembleRelease` + `test` green. On-device verify still pending (open Settings, provider dropdown, validate/fetch, model dropdown, token eye toggle, save; force a crash → CrashDialog Copy). **Next: tag `v1.2.0`** (the v1.2.0 release was blocked behind #44/#46/#45 — all now done).

## Completed (this cycle)
- [x] #45 Settings + CrashDialog theme-free (commit `70e6981`): new `Theme.PolishedRecognition.Plain` → `@android:style/Theme.DeviceDefault.DayNight` (+ `.Transparent` for crash). `SettingsActivity` + `CrashDialogActivity` → plain `android.app.Activity`; `androidx.appcompat.app.AlertDialog` → `android.app.AlertDialog`. `activity_settings.xml`: 10× `TextInputLayout`+`TextInputEditText`/`AutoCompleteTextView` → plain `EditText`/`AutoCompleteTextView`; `MaterialCheckBox`→`CheckBox`; 6× `Widget.Material3.Button.*` → plain `Button` (secondary = `Widget.DeviceDefault.Button.Borderless`); `?attr/textAppearance*` → explicit `textSize`/`textStyle` + `?android:attr/textColor*` (DayNight-safe). Token reveal kept via manual eye `ImageButton` (`ic_eye`/`ic_eye_off` VectorDrawables) + `togglePassword()`. Errors via `setError(msg)` (popup + red icon); dropped Material `helperText`. Provider/target dropdowns use `setOnClickListener{showDropDown()}` + `threshold=Int.MAX_VALUE` (Material's exposed-dropdown hook is gone — PITFALL #37). `dialog_crash.xml`: Material buttons → plain. `item_language_dropdown.xml` + `item_manage_language.xml`: `?attr/textAppearanceBody1` → `textSize=16sp`+`?android:attr/textColorPrimary`; `?attr/selectableItemBackground` → `?android:attr/selectableItemBackground`. The `material` dependency stays (VoiceRecognition + MicrophonePermission still use Material). `ModelFilterAdapter` + `LanguageDropdownAdapter` (`BaseAdapter`+`Filterable`) preserved unchanged.
- [x] #44 IME voice bar redesign (commit `4bdedbc`): 3 icon `ImageButton`s (Cancel/Pause-Resume/Mic-Send), pause/resume, gear-implicit-pause, `onFinishInputView` lifecycle fix, custom spinner item layouts with explicit colors, quick-settings disabled+dimmed during RECORDING/PROCESSING, content descriptions. **On-device verified 2026-08-23** (all 6 tests passed).
- [x] #46 IME keyboard selectability (commit `f4ebca4`): non-auxiliary keyboard subtype added to `voice_method.xml`. **Verified on-device:** nav-bar switcher renders for `Gboard + Polished`.
- [x] AOSP-source investigation of "Manage Keyboards" greyed-out rule (documented in PITFALLS).
- [x] Knowledge persistence run.

## Pending
- [ ] **Tag `v1.2.0`** → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key unchanged `62f9d7b0…a76a85`) → comment on MR !40029 → force-push the `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, currently clean at `ae5b1e5d`). This finalizes the F-Droid inclusion after #44/#46/#45.
- [ ] On-device verify #45 (see Current Focus).

## Blockers
None. (Oplus ROM blocks adb `ime`/`settings secure`/`pm grant` — all UI-driven; not a blocker.)

## Next Session Suggestion
Tag `v1.2.0` (HEAD is `70e6981`), run `release.yml`, bump fdroiddata metadata + comment on MR !40029, force-push the `add-polished-recognition` branch. Then on-device verify #45 (Settings + CrashDialog) — reopen #45 if anything regressed.
