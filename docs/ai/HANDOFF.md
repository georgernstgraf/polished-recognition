# Hand Off

**Active: none — #45 implemented + closed (commit `70e6981`), #44/#46 already closed. Next: tag `v1.2.0` → fdroiddata metadata bump → MR !40029.**

## What's done

### #45 — Settings + CrashDialog theme-free (commit `70e6981`, CLOSED — on-device verify PENDING)
- New `Theme.PolishedRecognition.Plain` → `@android:style/Theme.DeviceDefault.DayNight` (+ `windowNoTitle`/`windowActionBar=false`; `.Transparent` variant for crash) in `app/src/main/res/values/themes.xml`. DayNight preserved.
- `SettingsActivity` + `CrashDialogActivity` (`app/src/main/java/.../ui/`): `AppCompatActivity` → `android.app.Activity`; `androidx.appcompat.app.AlertDialog` → `android.app.AlertDialog`; all Material imports dropped.
- `activity_settings.xml` (`app/src/main/res/layout/`): 10× `TextInputLayout`+`TextInputEditText`/`AutoCompleteTextView` → plain `EditText`/`AutoCompleteTextView`; `MaterialCheckBox` → `CheckBox`; 6× `Widget.Material3.Button.*` → plain `Button` (secondary = `@android:style/Widget.DeviceDefault.Button.Borderless`); `?attr/textAppearance*` → explicit `textSize`/`textStyle` + `?android:attr/textColor*` (DayNight-safe).
- **Token reveal (kept)**: each token `EditText` in a horizontal `LinearLayout` with a 48dp `ImageButton` (`stt_token_toggle`/`llm_token_toggle`); `togglePassword(field, toggle)` in `SettingsActivity.kt` flips `PasswordTransformationMethod` + swaps `ic_eye`↔`ic_eye_off` (new `app/src/main/res/drawable/ic_eye.xml` + `ic_eye_off.xml`).
- **Errors**: `setError(msg)` on the `EditText`/`AutoCompleteTextView` (popup + red icon). Dropped Material `helperText` (success already Toasts). TextWatchers clear `error = null` on edit.
- **Provider dropdowns (`inputType=none`)**: `setupDropdowns()` wires `setOnClickListener { showDropDown() }` + `threshold = Int.MAX_VALUE` for `sttProvider`/`llmProvider`/`target_language` (Material's exposed-dropdown hook is gone — PITFALL #37). XML has `focusable="false"` + `cursorVisible="false"` + `clickable="true"`.
- `dialog_crash.xml`: `Widget.Material3.Button(.OutlinedButton)` → plain `Button` (Copy = `Borderless`, Close = default).
- `item_language_dropdown.xml` + `item_manage_language.xml`: `?attr/textAppearanceBody1` → `textSize=16sp` + `?android:attr/textColorPrimary`; `?attr/selectableItemBackground` → `?android:attr/selectableItemBackground` (bare `?attr/` doesn't resolve under `Theme.DeviceDefault` — same class of bug as PITFALL #83).
- `strings.xml`: added `show_password` (token-toggle content description).
- `ModelFilterAdapter` + `LanguageDropdownAdapter` (`BaseAdapter` + `Filterable`) preserved unchanged — PITFALLS #34/35.
- The `material` dependency STAYS in `app/build.gradle.kts` — `VoiceRecognitionActivity` + `MicrophonePermissionActivity` still use Material (`MaterialButton` per the 2026-05-30 decision); out of scope.

### #44 — IME voice bar redesign (commit `4bdedbc`, CLOSED — verified on-device 2026-08-23)
- Row 3 of `ime_voice_input.xml`: 3 icon `ImageButton`s (Cancel `ic_close` / Pause-Resume `ic_pause`↔`ic_resume` / Mic-Send `ic_mic`↔`ic_send`), equal weight, 48dp, `?android:attr/selectableItemBackgroundBorderless` backgrounds (platform-safe — never `?attr/`).
- `PolishedVoiceInputIME` button semantics: Cancel→`cancel()`+`requestHideSelf`; Pause-Resume→RECORDING `pause()` / PAUSED `resume()`; Mic-Send role-swap IDLE→`startIfPermitted()` / RECORDING+PAUSED→`stopAndTranscribe()`.
- **Settings gear implicit-pause**: gear enabled during RECORDING; press → `controller.pause()` THEN open `SettingsActivity`. On return state=PAUSED → pause_resume shows `ic_resume` → user explicitly resumes. Gear disabled during PROCESSING.
- **Lifecycle fix (critical)**: `onFinishInputView` now only `controller.pause()`s if RECORDING (releases mic, keeps buffer); PAUSED/PROCESSING untouched.
- Spinner contrast: `ime_spinner_item.xml` (black on transparent) + `ime_spinner_dropdown_item.xml` (black on white) with explicit hardcoded colors.
- Quick-settings (spinner+raw) disabled+dimmed (`alpha=0.4`) during RECORDING/PROCESSING; enabled in IDLE/PAUSED.
- **On-device verify 2026-08-23: ALL 6 tests passed.**

### #46 — IME keyboard selectability (commit `f4ebca4`, CLOSED — verified on-device)
- `app/src/main/res/xml/voice_method.xml`: added a non-auxiliary keyboard subtype (`imeSubtypeMode="keyboard"`, `isAuxiliary` omitted → default false, `subtypeId="0x70c01a1f"`, `label="@string/app_name"`) alongside the existing auxiliary voice subtype.
- Nav-bar keyboard switcher now renders for `Gboard + Polished` (confirmed on-device).
- `isAsciiCapable` deliberately NOT set (keeps AOSP's user-protection — see PITFALLS).

## Open tasks
1. [ ] **Tag `v1.2.0`** → `release.yml` → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (version + commit; keep `Binaries`/`AllowedAPKSigningKeys`, signing key unchanged `62f9d7b0…a76a85`) → comment on MR !40029 → force-push the `add-polished-recognition` branch (worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition`, currently clean at `ae5b1e5d`).
2. [ ] **On-device verify #45**: `./gradlew installRelease` (release key unchanged → `adb install -r` preserves Groq config) → open Settings: provider dropdown opens on tap, validate/fetch, model dropdown searchable, token eye toggle works, save; force a crash → CrashDialog Copy button. Reopen #45 if anything regressed.

## Known on-device gotchas (Oplus/OnePlus)
- `adb shell ime …` / `settings put secure …` / `pm grant …` → all blocked (SecurityException). Enable IME + grant mic + set default keyboard via Settings UI only. Verify via `dumpsys input_method | grep polished` + `dumpsys package … | grep RECORD_AUDIO`.
- HeliBoard's mic uses the system `voice_recognition_service`, NOT the auxiliary IME → HeliBoard's mic won't route to Polished. Use the nav-bar switcher or Fossify Keyboard.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts. (Now also true for Settings/Crash layouts under `Theme.DeviceDefault` — see PITFALLS.)
- "Manage Keyboards" greyed-out behavior matches AOSP — no Oplus OEM override (7-row evidence table in PITFALLS).

Last cleared: 2026-08-23. Knowledge files current (STATE/HANDOFF/ARCHITECTURE/CONVENTIONS/DECISIONS/PITFALLS updated for #45; HISTORY unchanged — nothing superseded).
