# Polished TestCaller

A throw-in test app that binds to Polished's `RecognitionService` **by explicit
component** and shows the `SpeechRecognizer` result on screen.

## Why this exists

Issue **#82**: the bound `PolishedRecognitionService` is a *secondary* listener
in `VoiceSessionController`; a keyboard-less caller used to get
`ERROR_CLIENT` (5) even though the pipeline had produced the correct text. The
delivery fix (`d327d43`, `deliver()`) routes `Completed` to secondary-only
consumers.

Verifying that fix through a real app is unreliable:

- **OnePlus 7T / OxygenOS** (Android 12): the ROM rejects the recognition
  preflight for the *caller* before any service code runs — see `PITFALLS.md`.
- **S5 / LineageOS** (Android 11): Duolingo binds **Google**, not our service,
  whatever the default voice-input setting says.

An explicit-component caller sidesteps both and exercises our service directly.

## Build

Reuses the root Gradle wrapper; no own wrapper needed.

```sh
ANDROID_HOME=/home/georg/Android/Sdk ./gradlew -p tools/testcaller assembleDebug
# -> tools/testcaller/app/build/outputs/apk/debug/app-debug.apk
```

## Install & run (example: S5, serial d890cc9e)

```sh
D=d890cc9e
adb -s $D install -r tools/testcaller/app/build/outputs/apk/debug/app-debug.apk
adb -s $D shell pm grant com.georgernstgraf.polishedrecognition.testcaller android.permission.RECORD_AUDIO
adb -s $D shell am start -n com.georgernstgraf.polishedrecognition.testcaller/.MainActivity
```

Tap **Start listening**, speak, then **Stop & transcribe** (auto-stops after 30 s
as a safety net). The result appears on screen and in logcat under the tag
`PolishedTestCaller`. A working service yields `onResults […];` a broken
delivery yields `onError 5`.

Watching the device side (optional):

```sh
adb -s $D logcat -v time | grep -E 'PolishedTestCaller|PolishedRecognitionService'
# app-side pipeline evidence:
adb -s $D shell ls -lt /sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs
```

## ⚠️ Android 11: the `BIND_RECOGNITION_SERVICE` caveat

The shipped service declares
`android:permission="android.permission.BIND_RECOGNITION_SERVICE"`. On
**Android 12+** the system mediates the bind (`RecognitionServiceManager`, which
holds the permission), so a normal app can bind. On **Android 11** the caller
binds **directly** and is denied:

```
Permission Denial: Accessing service … requires android.permission.BIND_RECOGNITION_SERVICE
```

So on an Android-11 device this caller only works against a build whose service
permission has been removed. Recipe (temporary, throw-away worktree — never
pushed):

```sh
cd <repo>
git worktree add /tmp/nobind HEAD
cp keystore.properties /tmp/nobind/keystore.properties
cp app/release.keystore    /tmp/nobind/app/release.keystore
# remove the android:permission="android.permission.BIND_RECOGNITION_SERVICE"
# attribute from the <service android:name=".service.PolishedRecognitionService"> block
cd /tmp/nobind
ANDROID_HOME=/home/georg/Android/Sdk ./gradlew assembleRelease
adb -s $D install -r /tmp/nobind/app/build/outputs/apk/release/app-release.apk
# … run the caller …
adb -s $D install -r app/build/outputs/apk/release/app-release.apk   # restore shipped build
git worktree remove /tmp/nobind --force
```

Because of this, the path is effectively **Android-12+ only as shipped**; the
owner decision (keep the permission + gate to API 31+ vs. drop it) is tracked in
`docs/ai/HANDOFF.md` / `STATE.md`.

## Not part of the app build

This project is deliberately **not** included by the root
`settings.gradle.kts`, so CI (`build.yml`, `fdroid-apk.yml`, `release.yml`) never
builds or bundles it.
