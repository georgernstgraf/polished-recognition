# Installation Guide

## 1. Install from Play Store (Closed Testing)

The app is in closed testing on Google Play — everyone is welcome!
The Play Store link works as soon as you're on the testers list:

1. Drop me a short [email](mailto:georg.ernst.graf@gmail.com) and I'll add you
   right away
2. Then install from the [Play Store](https://play.google.com/store/apps/details?id=com.georgernstgraf.polishedrecognition)

> An APK is also built on GitHub for each release.

## 2. Configure Providers

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

## 3. Enable the Voice Keyboard (IME)

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

## 4. Use It

1. Focus any text field
2. Switch to the **AOSP keyboard** (LineageOS), **HeliBoard** (F-Droid),
   **Fossify Keyboard**, or **OpenBoard** (or any keyboard that
   lets you choose a voice input provider)
3. Press the **voice / microphone key**
4. Tap **Send** when done speaking — the transcribed (and optionally translated)
   text is typed directly into the field
5. If no provider is configured yet, open the app from the launcher first

## Troubleshooting

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
