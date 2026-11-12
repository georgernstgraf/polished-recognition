# Domain Knowledge

Business rules and domain relationships not obvious from code.

## Begriffe (canonical vocabulary, owner decision 2026-11-12)

The one place where the fragment/cut language is defined. Code uses the
English term; the German gloss is for conversation and docs. When a term
here conflicts with prose elsewhere, this list wins.

### The cut (audio side)

| Term | Definition | Do not say |
|---|---|---|
| **Slice** | The fixed PCM budget of one fragment — its *maximum* length (e.g. 21 s). Where the slice boundary falls is arbitrary and irrelevant. | "fragment size" (ambiguous), "chunk" |
| **Search window** | The last `p` % of the slice (e.g. 30 % → 6.3 s of a 21-s slice). The only region a cut may land in. | "cutting area", "search span", "late half", "full window" |
| **Cut** | The actual decision where a fragment ends and the next begins. Has a position and a quality. | "boundary", "split point" |
| **Fragment** | The PCM span from the previous cut to this cut. Length ∈ `[Slice·(1−p), Slice]`. | "piece", "part" |
| **Forced cut** | No silence candidate in the search window → cut at the quietest frame, `Q = 0`. | "fallback", "hard cut" |
| **Silence quality Q** | Dimensionless deficit area under the axis `X = k·M + c` (see the rules below). | "silence score" |
| **Candidate** | A contiguous below-axis region; the cut lands at its area centroid, clamped by the margin. | — |
| **Tail** | The last fragment: from the last cut to the stop-tap. Taken **whole** — never searched, never split. | "last fragment" (ambiguous) |
| **Stop-tap** | The user's press on the send key. It *ends* the tail; it is not a cut. | "send" |

**Cut is audio, seam is text.** One cut produces one seam.

### The seam (text side)

| Term | Definition |
|---|---|
| **Seam** | The place where two fragment transcripts meet. |
| **Stitch** | The ONE per-seam decision: pre-roll + prompt + echo trim. All three or none. |
| **Pre-roll** | The last 1 s of PCM before the cut, prepended to the **upload only** (fragment files stay gapless). |
| **Echo trim** | Removing the pre-roll's re-transcribed text from the transcript head. |
| **Quality threshold** (`qualityMin`) | `Q < threshold` → stitch. The only Boolean downstream of the cut. |

### Time and cost

| Term | Definition |
|---|---|
| **Fragment length** | The *actual* length of a fragment. Never conflate with the slice. |
| **Backlog** | Audio seconds committed but not yet transcribed, **including the in-flight request**. Lives in the logs; never displayed as a duration. |
| **Drain** | Working the backlog off at send time. |
| **RTF** | Request duration ÷ audio duration. `< 1` is faster than realtime. |
| **STOP → raw text** | Stop-tap to finished Whisper text. The only figure the user feels. |
| **`a + b·L`** | The per-request cost model: `a` = fixed network/server cost (0.55 s Groq, 0.76 s gregor), `b` = cost per audio second (1.95 %/s Groq, 2.57 %/s gregor/ogg). |
| **Encode rate** | Local encode time ÷ audio length, per format: OGG 7.9 %/s, WAV 0.1 %/s. Paid **before** the request, so it lengthens the in-flight window. |

**The rule that prevents the old confusion:** the stage line shows the audio
length of the fragment currently being transcribed — never the backlog.

## Entities
- **RecognitionService**: Android system service that processes voice input. Lifecycle: `onStartListening` → capture audio → `onStopListening` → process → `callback.results()`. The system calls these in response to keyboard mic interactions.
- **OpenAI API compatibility**: Not just OpenAI — GROQ, OpenRouter, ZAI, Ollama, LM Studio all implement the same contract. Two endpoints matter: `/v1/audio/transcriptions` (STT) and `/v1/chat/completions` (LLM).
- **STT Provider**: Handles speech-to-text. Must provide a `/v1/audio/transcriptions` endpoint accepting multipart audio uploads — WAV (`audio/wav`) always; Ogg/Opus (`audio/ogg`) when the user enables `compress_audio`.
- **LLM Provider**: Handles text post-processing. Must provide a `/v1/chat/completions` endpoint accepting standard chat message arrays.

## Rules
- The service is stateless: each voice input session starts fresh. No history is kept.
- **Fragment seams and silence quality (#126/#128, owner-agreed design 2026-10-11):** long dictations are split into fragments; a fragment boundary ("seam") that lands inside a word makes Whisper transcribe the halves on BOTH sides (the #126 "bist. bist." live failure — a voiceless /st/ coda passed the old absolute silence test). The seam policy is therefore: **the cut is best-effort, the stitch is the guarantee.** Silence quality Q is a continuous, dimensionless metric = the deficit AREA under the axis `X = k·M + c` (M = per-fragment mean frame level; the axis adapts to room/gain per fragment; fricatives sit ABOVE the axis for k ≳ 0.6, so they contribute exactly zero — hard exclusion, not dilution; since depth is axis-bounded, a Q threshold implies a minimum pause duration — depth×duration in ONE knob). The cut lands at the candidate's area CENTROID (energy-weighted center — it cannot sit at a loud run edge by construction), clamped to a K-ms margin. A seam **stitches** (acoustic pre-roll + Whisper prompt + echo trim) iff its `Q < qualityMin` — the ONE Boolean downstream of the cut; unmeasured seams (null Q) stitch, the conservative direction. German is rich in voiceless codas (bist/ist/kannst/musst/erst) — the failure class is ordinary speech, not exotic.
- **Measurement physics (design rationale):** the quality measurement runs on an 80-Hz-high-passed copy (25/50-Hz hum beats against the 10-ms frame grid and fabricates phase-aligned fake silences; the decision must see what the server sees — uploads are conditioned the same way). Gain invariance: scaling the whole window must not move Q or the centroid (k·M carries it; c is the only absolute element — the whisper/quiet-room anchor). Whispered speech compresses the speech/pause contrast to ~6–12 dB — the axis can thread the band; that regime is exactly the borderline-Q case where the Q-gate arms stitching, so the split heals in text.
- Raw mode (the "LLM" checkbox in the keyboard bar, unchecked) bypasses the LLM entirely — Whisper text is returned directly to the keyboard. The domain flag keeps its name `rawMode` and the preference key `raw_mode`; only the UI label is inverted (#154).
- Translation mode injects an additional instruction into the **system** prompt via `{{target_language_clause}}`.
- Source language detection comes from Whisper's response `language` field, mapped to human-readable via `config/LanguageMapper` (complete 100-entry Whisper `LANGUAGES` table: ISO codes like `"de"` and full names like `"German"`, case-insensitive). When the field is null/blank (or literally `"unknown"`), the `{{source_language_clause}}` sentence is dropped entirely from the system prompt.
- When the provider returns `language_probability` (faster-whisper gateways do; GROQ/OpenAI do not), the clause reads *"The Whisper service detected the recognized language as <Name> with a probability of X percent."* (#105); otherwise the old *"The STT service transcribed audio spoken in <Name>."* There is deliberately no confidence threshold — the LLM sees the number and judges for itself (owner decision).
- `avg_logprob` is **transcription** confidence (mean log-probability of the segment's tokens; `exp()` ≈ geometric mean of token probabilities; faster-whisper treats segments below −1.0 as failed) — it is NOT language confidence and must never be presented as a language probability. Related segment fields: `no_speech_prob` (silence likelihood), `compression_ratio`, `temperature`.
- If Whisper's language field is null/empty, a second API call is made with `response_format=json` to extract it.
- The `target_language` setting uses human-readable English names (e.g. "German", "French"), not ISO codes.
- Whisper hallucination handling (#49): the default system prompt strips only **trailing** hallucinations (e.g. "Thank you." after silence); an empty string is returned only if the entire transcription is a hallucination. Real dictation content before the hallucination is preserved.
- Audio upload format (#60): WAV (16 kHz mono 16-bit) is the default and the always-working baseline. `compress_audio` opt-in transcodes to Ogg/Opus 24 kbps purely to shrink uploads on slow connections; the transcoder is best-effort — any failure silently falls back to WAV. The conditioning (high-pass + peak normalization, amplify-only) is a comfort feature, not a requirement: the STT endpoint remains the authority on audio quality.
- **Output line-wrap (#81):** the final transcription (raw Whisper text or LLM-polished text) is word-wrapped to `wrap_width` characters (default 80; `0` disables). Wrapping preserves existing line breaks, packs words greedily, and never hard-splits a long word. **Hyphens are additional break opportunities (#103):** a word may be split *after* a `-`, `–` (U+2013) or `—` (U+2014), the dash staying at the end of the line, chosen at the last dash that fits (classic greedy). A fragment shorter than 4 characters on either side of a dash is merged with its neighbour, so no orphan fragment (`a-` / `-a`) appears; a non-breaking hyphen `‑` (U+2011) is never a break point, and URL-like tokens (`scheme://…`, `www.…`) are never split. Words without dashes are unaffected. It runs in the pipeline before `InsertionSpacingPolicy` padding, so a padded trailing space may exceed the width by one char. Valid widths are `0` (off) or `>= 20`.
- **Per-app line-wrap (#100):** the invoking app may override the global width. The app is learned as a dictation target when a dictation **starts** (IME send tap + auto-start; bound service `onStartListening`) and re-learned on a successful insertion (IME) or result delivery (bound service) — so the currently active app is on top while dictating, not only after insertion (#102 amendment). It is stored in the separate `known_apps` prefs file; the persistent map may be re-learned if OS backup is off. `wrapWidthFor(package)` = override when set, else global; `null` caller (no resolvable package) always uses the global width. Overrides apply via `TranscriptionPipeline.transcribe(file, callerPackage)`. The learner only rewrites an app's `lastSeen` when the previous write is older than 24 h. The Settings dropdown puts apps used in the current process first in true MRU order (#102), the rest by stored `lastSeen` descending — so within a process the last-used app is always on top and pre-selected.
- **Insertion spacing rules (#66, refined #73; feel-check verified 2026-09-07) — evolving, refine from longer use.** The transcription committed into the editor is padded so words never glue onto the surrounding text: a **leading space** when non-whitespace precedes the cursor (letters, punctuation like `.,?;)` — the original #66 complaint) or when the neighbor cannot be read (`null`); a genuine **field start** (empty-string neighbor, position 0) gets **no** leading blank (owner report #73: Markor et al. got an indent); a **trailing space** when no whitespace follows (including field end — desired so follow-up typing doesn't glue). Details: (a) any whitespace counts — space, tab, newline, and NBSP/U+00A0; (b) a `null` neighbor from `getTextBefore/AfterCursor` keeps the conservative padding (a missing separator is worse than a doubled one; some apps don't support the text read — owner decision #73); (c) whitespace already inside the transcription itself suppresses padding on that side (never doubled). Owner intent: these are UX rules, not spec frozen in stone — refine if longer use reveals edge cases. Sole implementation: `InsertionSpacingPolicy` (service/), tested in `InsertionSpacingPolicyTest`; changes to the rules must update both.
- **In-app help (#107):** the canonical help is `docs/help.html` — also the project site's **Help** tab — shown **offline** in `HelpActivity` (the file is bundled into the APK assets). It is opened from the **Help chip left of the Settings About heading** and from a **long-press on the IME settings gear** (a tap on the gear still opens Settings; the gear's old #88 press-hold hint was retired for this). On the keyboard a button's **tap** and **press-and-hold** differ: hold on cancel/flush/pause/send/switch shows the #88 hint overlay, hold on ⌫ clears the whole field, hold on the gear opens Help.
- **Play alpha testing requirement (#74):** a personal Play developer account (created after 2023-11-13) needs **≥12 testers opted in continuously for 14 days** before production access; the recruitment target is 20–30 for buffer. The tester's Google Group membership and the Play opt-in must use the **same Google account**, and the Play tester status must read **Member**, not *Pending*.
- **Settings-detour workflow (#72, owner practice 2026-10-09):** the owner dictates with HeliBoard as the companion key keyboard: gear tap → Polished pauses + switches to HeliBoard + opens Settings; Save/Back returns to the dictation target app with HeliBoard still active (its mic visible). The HeliBoard mic key re-invokes Polished via the auxiliary voice subtype (#46) and `ImeStartDecision` auto-resumes the paused app-scoped session (#83). An automatic switch-back to Polished on Settings exit is impossible (IME token restriction) and unwanted (owner: a picker would add a click) — never implement one.
