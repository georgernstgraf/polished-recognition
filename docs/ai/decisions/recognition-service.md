---
type: Decisions
title: Decisions — RecognitionService
description: The bound Android speech service: its scope, caller checks and the delivery contract towards secondary sessions.
tags: [knowledge, decisions, recognition-service]
generated: { by: opencode/knowledge-persistence, at: 2026-11-25T12:00:00Z }
status: stable
---

# Decisions — RecognitionService

Moved out of `DECISIONS.md` in #206; entries are verbatim. New decisions in this area are appended here.

## 2026-10-03: RecognitionService scoped to Android 12+; hidden on Android 11 (#82)

- **Decision (owner)**: the bound `RecognitionService` entry point is supported on **Android 12 (API 31)+ only**. `minSdk` stays 30: on Android 11 the **IME** (`PolishedVoiceInputIME`) is the dictation path and works there.
- **Reason**: on Android 11 the caller binds the service **directly** and `android:permission="android.permission.BIND_RECOGNITION_SERVICE"` blocks every normal app (there is no `RecognitionServiceManager` to mediate — see PITFALLS). An Android-11 branch (drop the declaration, or an in-code caller check) was rejected as not worth it for an old platform.
- **How**: the service is gated by `android:enabled="@bool/recognition_service_enabled"` — `false` in `res/values/bools.xml`, `true` in `res/values-v31/bools.xml` — so Android 11 does not offer it as a broken entry in the Voice-input picker. The `BIND_RECOGNITION_SERVICE` declaration stays.
- **Tradeoff**: Android-11 users dictate via the keyboard/IME only, not via `SpeechRecognizer`. **Verified 2026-10-03 on the LineageOS S5 (Android 11):** after installing the gated build, `cmd package query-services -a android.speech.RecognitionService` lists **only Google's** service (our entry is gone), while `PolishedVoiceInputIME` stays registered.
- **Status**: #82 stays open until a real caller (Duolingo/Corvus) is verified on a healthy Android-12+ device ("path A").

## 2026-10-03: RecognitionService results delivered to secondary-only sessions (#82)
- **Choice**: `VoiceSessionController.stopAndTranscribe` routes the finished pipeline result through a new `deliver(result)` helper: `Completed` is emitted whenever the primary (IME) callback **or** any secondary listener is attached; only when nobody listens is it parked in `pendingResult` for the next primary `attach()` (rotation path, #83). Applied to **both** the success and failure branches.
- **Reason**: The bound `PolishedRecognitionService` is a **secondary** listener, never the primary slot. On a keyboard-less call (Duolingo/Corvus — no IME callback attached) the old `if (callback != null) else pendingResult` guard swallowed the result, and the controller then emitted `StateChanged(IDLE)`, which the service mapped to `ERROR_CLIENT` (5). The caller got an error although STT/LLM had produced the exact expected sentence — confirmed on-device: the app's `stt-response`/`llm-response` logs showed `"Viven en una casa enorme."` while Duolingo received error 5.
- **Also**: The path is currently **not exercisable on the owner's OnePlus 7T** — OxygenOS rejects the recognition preflight for the caller (`RECORD_AUDIO`) before any service code runs, reproduced with two recognizers, after a Duolingo reinstall, **and after rolling the device back to the pre-session build** (still immediate error 9, no pipeline logs) → confirmed ROM-side, not the build. The fix is unit-verified (`VoiceSessionControllerSecondaryTest` +1) and shipped to `main` (`d327d43`). **On-device verification (2026-10-03, LineageOS S5):** Duolingo there routes to Google (Android 11 binds the caller directly; see PITFALLS), so a minimal explicit-component test caller (`tools/testcaller/`) was used — with the service’s `BIND_RECOGNITION_SERVICE` permission temporarily removed, our service recorded and delivered the text to the caller **6× (`onResults`), 0 errors**, confirming the delivery fix **at the protocol level only** (a real-caller / Duolingo verification is still outstanding; #82 stays open). Also uncovered: as shipped the path is **Android-12+ only** (on Android 11 the service permission blocks normal-app binds). Follow-up decision pending (drop the declaration vs. gate to API 31+).

## 2026-09-20: #82 bound RecognitionService implemented on shared singleton (unverified on-device)
- **Choice**: New `service/PolishedRecognitionService` drives the shared `VoiceSessionController` singleton as a *secondary* listener (`add/removeSecondaryListener`, `startShared()` — purely additive, existing single-callback API untouched). IME keeps its primary callback forever. Busy-discard on service start (mirrors the IME field-change cancel, owner decision); pipeline failure delivers the error to the client app then `cancel()`s to IDLE (no PAUSED residue, owner decision). Neutral notification (own channel/id 1003, no action buttons — no colorful Material remnants, owner decision). Manifest block restored verbatim from the pre-#43 declaration (`BIND_RECOGNITION_SERVICE`, mic FGS).
- **Reason**: Peter confirmed both gating answers — Duolingo/Corvus honor the system default (no own engine), and final-only results with upload latency beat his 5–7 s on-device Whisper while winning on privacy. The single-callback slot cannot be shared (review finding: sharing would deafen the IME + orphan `pendingResult`s); the secondary-listener amendment preserves the Singleton pattern without that damage. Owner rejected a second controller instance and any change to tested behavior.
- **Tradeoff**: `cancel()` nulls the IME primary slot too (harmless in IDLE — no async events; next IME tap re-attaches via `start{}`); if the Polished keyboard binds mid-service-dictation it may cancel the session (client gets an error). No pause UI / partial results in v1. 253 tests green (8 new), `assembleRelease` green. Commit `3e38bf7`. Open: owner on-device feel-check (Duolingo/Corvus + IME regression trio).

## 2026-05-29: RecognitionService over IME
- **Choice**: Implement as `RecognitionService`, not a custom `InputMethodService` keyboard
- **Reason**: Works with every keyboard (Gboard, SwiftKey, Samsung, OpenBoard). Users just change one system setting (Voice Input provider). Building a full keyboard requires thousands of lines for QWERTY, autocorrect, suggestions, themes, etc. — all unrelated to voice input.
- **Considered**: Forking OpenBoard, building custom IME from scratch
- **Tradeoff**: No custom keyboard UI — depends on existing keyboard's mic button behavior

## 2026-05-30: Try-and-toast for voice recognition service setting
- **Choice**: The "Set as Voice Input" button in Settings only attempts `Settings.Secure.putString("voice_recognition_service", ...)` and shows a Toast with the result — no dialog, no system settings opener, no cancel button
- **Reason**: `WRITE_SECURE_SETTINGS` is a `signature|privileged` permission — regular apps cannot obtain it via manifest. The putString call fails with SecurityException on normal devices. On success (rooted/custom ROM), a toast confirms; on failure, the toast shows the error or ADB fallback command
- **Considered**: Showing a help dialog with manual setup steps (overly complex, buried the simple try), opening system settings (no voice input option on Samsung Android 11)
- **Tradeoff**: Users who never connect via ADB always see the error toast. Counter: the info dialog on the recording screen and README contain the full setup guide


**Also stored elsewhere** (ambiguous placement, cross-referenced per #206):
- [ime-field.md](ime-field.md) — „2026-08-22: Auxiliary voice IME + remove bound RecognitionService (MR !40029, @bhavyashah04122005)"
- [build-release.md](build-release.md) — „2026-09-20: #82 docs repair — ADB voice-input block restored to INSTALLATION.md"
