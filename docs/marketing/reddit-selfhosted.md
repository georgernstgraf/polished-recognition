# Reddit — r/selfhosted (BYOK angle)

> Post with your account. Emphasize: local models, no cloud required.

---

**Title:** Voice typing on Android with fully self-hosted STT+LLM (Ollama) — my FOSS keyboard

**Body:**

I wanted voice typing on my phone without sending audio to big tech, so I
built Polished Recognition (FOSS, on F-Droid):
https://f-droid.org/packages/com.georgernstgraf.polishedrecognition

It's a voice-only keyboard: switch to it, dictate, tap send, text lands in
any app. Both pipeline stages accept any OpenAI-compatible endpoint, so you
can point STT at your self-hosted Whisper and the polish step at your
Ollama/LM Studio instance — nothing leaves your network. (Hosted options
like GROQ/OpenRouter work too, including free tiers.)

Details: editable system prompt with language variables, Raw mode (pure
transcription, no LLM call), optional translation, pause/resume that survives
switching back to your typing keyboard, searchable model pickers.

Caveat: needs a keyboard that delegates its mic button (AOSP, HeliBoard,
Fossify, OpenBoard) — Gboard is hardcoded to Google.

Play alpha testers also wanted:
https://groups.google.com/g/polished-recognition-alpha →
https://play.google.com/apps/testing/com.georgernstgraf.polishedrecognition
(same account for both). Issues:
https://github.com/georgernstgraf/polished-recognition/issues
