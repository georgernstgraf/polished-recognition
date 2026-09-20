# Reddit — r/fossdroid (main post)

> Post with your account. Title suggestion below. Attach the demo GIF
> (reddit supports GIF uploads) + link F-Droid in the body.
> Tone: honest author seeking feedback — no marketing speak.

---

**Title:** I built a FOSS voice keyboard that uses your own STT/LLM endpoints — looking for feedback

**Body:**

Hi r/fossdroid — I'm the author of Polished Recognition, now on F-Droid:
https://f-droid.org/packages/com.georgernstgraf.polishedrecognition

It's a voice keyboard (not another keyboard app — it has no QWERTY, it's
purely the voice bar). Switch to it, speak, tap send, and polished text gets
typed into whatever app you're in. The pipeline is yours: pick any
OpenAI-compatible STT (Whisper on GROQ, OpenAI, self-hosted…) and optionally
any chat LLM to polish/fix/translate the transcription. GROQ's free tier runs
the whole thing at zero cost. Fully editable system prompt, Raw mode that
skips the LLM, pause/resume that survives keyboard switches.

No account, no server, no analytics — your audio only goes where you point
it, and with local Ollama it goes nowhere at all.

Works with AOSP keyboard, HeliBoard, Fossify, OpenBoard. Gboard can't use it
(its mic is hardcoded to Google — nothing any third-party app can do).

What I'd love feedback on: transcription quality on your provider, the
pause/resume flow, and anything confusing in Settings. Issues:
https://github.com/georgernstgraf/polished-recognition/issues

Also looking for Play alpha testers (12+ needed for production access):
join https://groups.google.com/g/polished-recognition-alpha then accept
https://play.google.com/apps/testing/com.georgernstgraf.polishedrecognition
with the same account.
