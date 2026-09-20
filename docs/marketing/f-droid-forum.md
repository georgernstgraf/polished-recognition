# F-Droid forum — New apps

> Post at: https://community.fdroid.org/ (New apps category), with your account.
> Attach: docs/img/demo.gif + 1-2 screenshots from docs/img/.
> Timing: post once F-Droid serves 1.3.0 WITH listing images (check the package page first).

---

**Polished Recognition — a voice keyboard powered by your own AI** (https://f-droid.org/packages/com.georgernstgraf.polishedrecognition)

Hi all — I built a voice keyboard for Android that transcribes with the speech
provider *you* choose and refines the text with the language model *you*
choose. Switch to the Polished keyboard, speak, tap send, and polished text
lands in whatever app you're typing in.

- **Bring your own provider:** 18 presets (GROQ, OpenAI, OpenRouter, Google AI,
  DeepSeek, xAI, Mistral, local Ollama/LM Studio, …) or any custom
  OpenAI-compatible endpoint. GROQ's free tier covers a full STT-plus-polish
  pipeline at zero cost.
- **Raw mode** skips the LLM entirely; **Polish only** refines without
  translating; target-language translation built in.
- Pause/resume mid-dictation, switch keyboards mid-dictation without losing
  audio, fully editable system prompt, searchable model pickers.
- **Privacy:** no account, no server, no analytics. Audio goes only to the
  provider you configured — or nowhere, with local Ollama.

One honest limitation: it works with keyboards that delegate their mic
(AOSP keyboard, HeliBoard, Fossify, OpenBoard). Gboard's mic is hardcoded to
Google and can never use it.

**Get it on F-Droid:** https://f-droid.org/packages/com.georgernstgraf.polishedrecognition

Also in Play closed testing — join the testers group
(https://groups.google.com/g/polished-recognition-alpha), then accept testing
with the same account
(https://play.google.com/apps/testing/com.georgernstgraf.polishedrecognition).

Feedback welcome as GitHub issues:
https://github.com/georgernstgraf/polished-recognition/issues
