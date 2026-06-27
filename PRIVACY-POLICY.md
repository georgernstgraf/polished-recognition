# Privacy Policy

**Last updated:** June 27, 2026

## Overview

Polished Recognition is an Android voice input service that captures microphone
audio and sends it to user-configured API endpoints for transcription and
optional translation. This privacy policy explains what data is collected, how
it is used, and what control you have over it.

## Data Collection

### Microphone Audio

- Audio is captured **only** when you tap the microphone button on your keyboard
  and hold to record.
- Recording is entirely user-initiated and user-controlled. No audio is captured
  in the background or without explicit action.
- The app does **not** store audio recordings permanently on your device or
  upload them to any server controlled by the app developer.

### API Tokens and Configuration

- API tokens, provider URLs, and model selections are stored locally on your
  device in Android SharedPreferences.
- These credentials are **never** transmitted to any server controlled by the
  app developer.

### Permissions

| Permission | Purpose |
|-----------|---------|
| `RECORD_AUDIO` | Required to capture microphone input for speech recognition |
| `INTERNET` | Required to send audio to your configured STT endpoint and text to your configured LLM endpoint |
| `FOREGROUND_SERVICE` | Required to maintain audio capture while the keyboard is in use |
| `FOREGROUND_SERVICE_MICROPHONE` | Required by Android 14+ for microphone foreground services |
| `POST_NOTIFICATIONS` | Required to show a persistent notification during audio capture (Android 13+) |

## How Your Data Is Used

### Audio Data

When you record audio:

1. Audio is captured as raw PCM data on your device
2. Converted to WAV format in memory
3. Sent **directly** to the STT (Speech-to-Text) API endpoint you have
   configured in Settings (e.g., Groq, OpenAI, or a self-hosted service)
4. The transcribed text may be sent to the LLM (Language Model) endpoint you
   have configured for optional post-processing

**The app developer does not operate, control, or receive data from any API
endpoint.** All data flows directly between your device and the provider you
choose.

### Configuration Data

All provider settings, prompt templates, and preferences are stored locally on
your device in Android SharedPreferences. No configuration data is transmitted
to the app developer.

### Diagnostic Logs

To help you troubleshoot your provider configuration, the app writes rotating
JSON log files to its app-private storage
(`Android/data/com.georgernstgraf.polishedrecognition/files/logs/`). These
contain:

- the prompt sent to your LLM, including the transcribed text
  (`llm-prompt.json`; not written in raw mode);
- the raw response from your STT provider, including the transcript
  (`stt-response.json`);
- the raw response from your LLM provider (`llm-response.json`).

Up to nine historical copies of each are kept. These logs are stored **only**
on your device and are **never** transmitted to the app developer. They remain
until you clear the app's data, uninstall the app, or delete the files.

## Third-Party Data Sharing

Polished Recognition **does not**:

- Share data with third parties
- Use analytics SDKs
- Display advertisements
- Track user behavior
- Transmit crash reports or diagnostics to the app developer (local diagnostic logs stay on your device — see Diagnostic Logs above)
- Sell user data

The only external data transmission is the audio and text data you explicitly
choose to send to the API provider you configure in Settings.

## Data Retention

- Audio recordings exist temporarily in device memory during transcription and
  are discarded immediately after processing.
- Configuration data remains on your device until you clear app data or
  uninstall the application.
- Diagnostic log files (the LLM prompt including transcript, and the raw STT
  and LLM responses) are stored on your device as described under Diagnostic
  Logs, until you clear app data or uninstall.
- No data is retained on servers controlled by the app developer.

## Children's Privacy

This app is not directed at children under 13. It does not knowingly collect
personal information from children.

## Changes to This Policy

This privacy policy may be updated to reflect changes in app functionality or
legal requirements. The date at the top of this page will indicate when the
policy was last revised.

## Contact

If you have questions about this privacy policy, please open an issue on the
[GitHub repository](https://github.com/georgernstgraf/polished-recognition)
or email [georg.ernst.graf@gmail.com](mailto:georg.ernst.graf@gmail.com).

---

*Polished Recognition — https://georgernstgraf.github.io/polished-recognition*
