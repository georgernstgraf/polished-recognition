# Reddit — r/degoogle (privacy angle)

> Post with your account. Self-promotion is restricted: post in the recurring
> weekly **"Degoogle Showcase"** thread (or ask the mods first) — NOT as a
> standalone post, and never post the same product twice.
> Emphasize: no Google voice typing, no accounts. Rewrite in your own words
> (AI-written promo posts are removed in Reddit FOSS subs).
> Alpha onboarding is canonical in alpha-welcome.md — keep the two-step block in sync. Post body stays version-free.

---

**Title:** Degoogled voice input for Android — dictate without Google's voice typing

**Body:**

Every stock keyboard either phones home to Google for voice typing or has no
voice input at all once you degoogle. I built an alternative: Polished
Recognition (FOSS, on F-Droid):
https://f-droid.org/packages/com.georgernstgraf.polishedrecognition

Voice-only keyboard, no QWERTY: switch to it, speak, tap send. Transcription
runs on the STT provider you configure (Whisper via GROQ, OpenAI,
self-hosted…), and an LLM you choose polishes the text. No Google services
involved at any point, no account, no registration, no analytics — and with
a local Ollama setup your voice never leaves the device.

Pairs with degoogled-friendly keyboards that delegate the mic: AOSP keyboard
(LineageOS etc.), HeliBoard (F-Droid), Fossify, OpenBoard. (Gboard is
structurally incompatible — its mic only talks to Google.)

F-Droid: https://f-droid.org/packages/com.georgernstgraf.polishedrecognition
Play alpha (testers wanted, 12+ needed):
https://groups.google.com/g/polished-recognition-alpha →
https://play.google.com/apps/testing/com.georgernstgraf.polishedrecognition
Feedback: https://github.com/georgernstgraf/polished-recognition/issues
