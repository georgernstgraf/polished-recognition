# Installation Guide

## 1. Install from F-Droid (recommended)

[<img src="https://fdroid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/com.georgernstgraf.polishedrecognition)

F-Droid handles updates automatically.

## 2. Install from Play Store (Closed Testing)

The app is in closed testing on Google Play — everyone is welcome!

1. Join the testers group: https://groups.google.com/g/polished-recognition-alpha
   (any Google account; confirm membership shows **Member**, not Pending)
2. With the **same account**, open the
   [opt-in page](https://play.google.com/apps/testing/com.georgernstgraf.polishedrecognition)
   → **Become a tester** → install from the
   [Play Store](https://play.google.com/store/apps/details?id=com.georgernstgraf.polishedrecognition)

> Stay opted in: Google only starts the 14-day production-eligibility clock
> while at least 12 testers stay continuously opted in — don't leave the
> group right after installing.

> An APK is also built on GitHub for each release.

## 3. Configure Providers

Open the app (via the launcher, or if hidden on your device, launch settings via ADB):

```bash
adb shell am start -n "com.georgernstgraf.polishedrecognition/com.georgernstgraf.polishedrecognition.ui.SettingsActivity"
```

Then:
- **STT Provider** (e.g. Groq) → enter API token → Validate & Fetch Models → pick a Whisper model
- **LLM Provider** (optional) → same procedure
- **Target Language** (optional) → select language for translation
- **Save**

### Recommended Setup (GROQ — free)

1. Sign up at [console.groq.com](https://console.groq.com) and create an API key
2. In Settings, select **Groq Whisper** as STT provider and paste your key
3. Select **Groq** as LLM provider and paste your same key
4. Validate & Fetch Models on both
5. Recommended STT model: `whisper-large-v3-turbo` — runs at up to 300× real-time on Groq LPU hardware
6. Recommended LLM model: `gpt-oss-120b` — fast, capable, free tier covers daily keyboard usage

## 4. Enable the Voice Keyboard (IME)

Polished Recognition registers as an **auxiliary voice keyboard**. Enable it once:

1. **Settings → System → Languages & input → On-screen keyboard**
   (called "Keyboard & input method" on some ROMs)
2. Turn on **Polished Recognition**
3. Grant the **Microphone** permission when prompted

> On some OEM ROMs the on-screen-keyboard list is hidden. The app's Settings
> page has an **Enable Voice Keyboard (IME)** button that opens the system
> keyboard settings directly.

Then point your keyboard's voice input at it:

- **AOSP keyboard (LineageOS and derivatives) / HeliBoard (F-Droid only) /
  Fossify Keyboard / OpenBoard:** open the keyboard's settings →
  **Voice typing** (or "Voice input method") → select **Polished Recognition**
- Press the **voice / microphone key** on the keyboard while typing in any text
  field to start recording

> The app also answers the standard **`RECOGNIZE_SPEECH`** intent that some
> keyboards' mic buttons fire — no extra setup needed.

> **Gboard will never use this.** Gboard's microphone is hardcoded to Google's
> voice typing and cannot be redirected to a third-party voice input.
> Samsung Keyboard likewise only allows its own or Google's voice input.

## 5. Set as System Voice Input (SpeechRecognizer)

Apps without their own keyboard — Duolingo, Corvus, and other assistive tools —
call Android's `SpeechRecognizer` API, which dispatches to the system's default
voice-input service. Point that service at Polished Recognition once:

1. **Settings → System → Language & region → Speech → Voice input**
   (called "Languages & input → Voice input" on some ROMs)
2. Select **Polished Recognition**

If the "Voice input" menu is hidden on your ROM, set it via ADB:

```bash
adb shell settings put secure voice_recognition_service com.georgernstgraf.polishedrecognition/com.georgernstgraf.polishedrecognition.service.PolishedRecognitionService
```

Verify:

```bash
adb shell settings get secure voice_recognition_service
```

> On Oplus ROMs (OnePlus/OPPO/Realme) adb secure-settings writes are blocked
> (`SecurityException: uid 2000 does not have WRITE_SECURE_SETTINGS`) — use the
> Settings screen there. If "Voice input" is not visible at all, reboot the
> device: some ROMs enumerate recognition services at boot time.

> No extra in-app setup is needed — dictation in those apps uses the same
> STT/LLM providers you configured in §3.

## 6. Use It

1. Focus any text field
2. Switch to the **AOSP keyboard** (LineageOS), **HeliBoard** (F-Droid),
   **Fossify Keyboard**, or **OpenBoard** (or any keyboard that
   lets you choose a voice input provider)
3. Press the **voice / microphone key**
4. Tap **Send** when done speaking — the transcribed (and optionally translated)
   text is typed directly into the field
5. If no provider is configured yet, open the app from the launcher first

## Troubleshooting

**Polished Recognition not listed under Voice input?**
If **Settings → System → Language & region → Speech → Voice input** does not
offer Polished Recognition, set it via ADB (see §5). On Oplus ROMs the adb
write is blocked — use the Settings screen and reboot if the entry is missing.

**Polished Recognition not listed under on-screen keyboards?**
Make sure it is enabled in **Settings → On-screen keyboard**. The app's Settings
page has an **Enable Voice Keyboard (IME)** button that opens that screen
directly.

**Gboard does not pick it up?**
Gboard's microphone is hardcoded to Google's voice input and can **never** be
redirected to a third-party voice keyboard. Use the **AOSP keyboard**
(LineageOS), **HeliBoard**, **Fossify Keyboard**, or **OpenBoard** instead.

**Microphone permission missing?**
The first time you press the voice key the app asks for the Microphone
permission. You can also grant it under
**Settings → Apps → Polished Recognition → Permissions**.
