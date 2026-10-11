# Seam study — fragment stitching quality (2026-10, #122)

A reproducible, file-driven measurement of the live fragment-transcription
pipeline's seam behaviour (issue [#122](https://github.com/georgernstgraf/polished-recognition/issues/122),
parent [#121](https://github.com/georgernstgraf/polished-recognition/issues/121)).
The tooling lives in [`tools/seam-study/`](../../tools/seam-study/README.md).

## TL;DR

- The seam mechanisms are **largely NULL levers**: the acoustic pre-roll
  *size*, the *fragment size*, and the Whisper *prompt length* all show **no
  measurable accuracy effect**.
- The one real mechanical lever: **do not apply the seam mechanism at a
  silence-aligned cut** — that is now the production default (`SeamPolicy.FORCED_ONLY`).
- The dominant failure mode is **Whisper repetition-loop hallucination /
  whole-fragment derailment**, which appears in **both** the fragment join and
  the full-context reference, and is **not** a seam artifact.
- The owner-reported "repetitions" are these model loops, **not** boundary
  duplicates: a dedicated seam-overlap metric found ~0 clean dups.
- **OGG is consistently worse than WAV.** Clean, short inputs are exactly right.

## Background — two workstreams

The pipeline cuts a live recording into fragments (nominal 10 s since #122),
transcribes each through an OpenAI-compatible STT endpoint, and joins the
transcripts with a single space. At each seam the mechanism applies an
acoustic **pre-roll** (the last 1 s of the previous fragment prepended to the
upload) and a text **prompt** (the tail of the previous fragment's transcript).
The two questions were:

- **(a) Forced/hard cut** (no silence found in the search window): the cut
  splits a word across the boundary. Round 1 (no overlap) *dropped* it; round 2
  (pre-roll) appeared to *duplicate* it. Optimise the overlap.
- **(b) Silence-aligned cut**: a cut inside a pause needs **no seam at all** —
  the pre-roll/prompt may only *manufacture* contact.

## Method

**Harness.** The microphone cannot be scripted, so a `harness` build variant
(`src/harness/`, never in release/F-Droid) was added: it builds its **own**
`VoiceSessionController`, injects a 16 kHz mono WAV through the production
`restore()` snapshot seam, drives one real fragment-pipeline run, waits for the
shadow reference (`onShadowResult`), and writes `harness.json`. Knobs: fragment
size, silence-search window, pre-roll size, `SeamPolicy`, prompt chars,
`fullContextAtStopEnabled=false` (so the delivered text is always the fragment
join, never the full-context replacement).

**Corpus.** 7 classes × {10, 30, 60, 150, 300} s (+600 s for two classes),
converted to the exact mic format (16 kHz mono 16-bit PCM WAV). Classes:
`de_monologue_male`, `de_interview`, `en_monologue_male`, `en_monologue_female`,
`en_interview`, `panel_multispeaker`, `phone_radio`. The corpus was selected
from file metadata only (no listening) — see **Limitations**.

**Reference.** The pre-existing shadow pass (full-context STT of the same
assembled recording) serves as the comparison. It is *not* ground truth (below).

**Analysis.** `analyze.py` diffs the loop-masked join token stream against the
loop-masked shadow, attributes error tokens back to individual fragments (whose
start seam is tagged forced vs silence), and flags runs whose shadow word count
diverges from the join's.

**Runs.** 126 completed runs across the arms (all green); gregor only.

## Production changes shipped (all under #122)

- `SeamPolicy { ALL | FORCED_ONLY | OFF }`; `FORCED_ONLY` is the controller
  default — the pre-roll and prompt are applied **only** at forced cuts.
- `silenceAligned` is now the **true** cut signal from `SilenceCutter`
  (previously derived arithmetically), persisted per manifest entry; the
  pre-roll keys on the *previous* fragment's end cut.
- Auto-sizer floor `MIN_FRAGMENT_SECONDS` 7 → 10 s (the stop-tap tail is
  unaffected — always taken whole).
- `promptTail` word-snapped and seam-local (`DEFAULT_PROMPT_MAX_CHARS` = 96,
  was `takeLast(800)`).
- `SeamOverlap` metric + per-fragment `stt-fragment.json` evidence (index, PCM
  bytes, cut type, transcript, words, actual prompt, echo tokens, seam overlap).
- The `harness` variant, `onShadowResult`, `fullContextAtStopEnabled`,
  `promptMaxChars` hooks.
- Related prerequisite: the #120 hard 300 s STT upload cap.

## Results

### Errors vs duration / format (loop-masked, reliable runs)

`extra+missing` tokens per 100 shadow words:

| duration | ogg | wav |
|---|---|---|
| 10 s | — | 0.0 |
| 30 s | 21.7 | 6.7 |
| 60 s | 11.6 | 8.7 |
| 150 s | 23.3 | 12.1 |
| 300 s | 20.1 | 13.7 |
| 600 s | 11.1 | 4.7 |

Two stable effects: **(i)** OGG ≥ WAV at every duration; **(ii)** a mild upward
trend with duration that is noisy (the shadow's own reliability degrades with
size, so this is not a clean error-vs-seam-count curve).

### Per-seam cut type (loop-masked, reliable runs)

| seam type | seams | extra | missing | errors / seam |
|---|---|---|---|---|
| forced (hard) | 176 | 383 | 547 | **5.28** |
| silence-aligned | 1113 | 2603 | 1528 | **3.71** |

Forced seams are ~1.4× worse per seam — real, but small next to the loop effect.

### Lever arms — the null results

| arm | comparison | err/100w |
|---|---|---|
| mechanism at silence seams | off / preroll-only / prompt-only / both | 5.4 / 6.4 / 6.9 / **7.7** |
| pre-roll size | 0.5 s vs 1 s vs 2 s | 7.1 / — / 7.1 |
| fragment size | 7 s vs 21 s | 11.8 vs 11.6 |
| prompt length @21 s | 0 / 96 / 320 / 800 | 30.0 / 29.3 / 30.1 / 31.2 |
| prompt length @60 s | 0 / 96 / 320 / 800 | 13.2 / 16.2 / 6.9 / 4.3 |

Conclusions: **(b)** is confirmed (applying the mechanism at silence seams
adds errors → `FORCED_ONLY`); **(a)** is a null (overlap size is not the lever);
neither fragment size nor prompt length moves accuracy. The `W60` row is
non-monotonic but only 1 run per point (5 fragments) — the flat `W21` row is the
stronger evidence, and it is itself inflated by a shadow loop.

### The dominant error mode: repetition loops

- `SeamOverlap` found **~0** clean suffix↔prefix boundary dups; ~290 echo tokens
  were trimmed across the whole study. The "repetitions" are **model loops**.
- Loop tokens (a phrase of length ≤ 8 repeated ≥ 4× in a row) in the reliable
  runs: **join ≈ 146 vs shadow ≈ 1871**. The reference loops ~13× more than the
  join, and both loop on the same hard content (music, band-limited phone,
  disfluent comedy).
- Example: `en_interview` (an Australian comedy act with song segments) — the
  join looped `"me me me …"` ×111; elsewhere the shadow looped `"flop flop …"`
  ×231. These dominate every naive join↔shadow error total.
- **Consequence:** any join↔shadow metric MUST loop-mask both sides first, or it
  measures the reference's loops. 10/126 shadows were additionally flagged as
  unreliable (VAD collapse / expansion).

### Methodology correction: the prompt is one fragment

The prompt is `transcripts[index−1].text` — **only the immediately previous
fragment** — so its length is bounded by one fragment's transcript (~90 chars at
10 s, ~190 at 21 s, ~550 at 60 s). Therefore `takeLast(800)` **never truncated
anything** at production fragment sizes, and the round-1 premise "800 chars ≈
30–60 s of prior speech" is **false**. The 96-char seam-local default only binds
above ~10 s fragments; the sweep confirms prompt length is not an accuracy
lever. (Corrected in `CONVENTIONS.md`; original rationale archived in
`HISTORY.md`.)

## Interpretation

1. The fragment pipeline is correct on clean, short input (1 fragment → 0 errors).
2. The mechanical seam levers are exhausted: overlap size, fragment size and
   prompt length do not move accuracy, and only the *conditional application*
   of the mechanism (skip silence-aligned seams) helps. That is shipped.
3. The remaining damage is Whisper looping/derailment on pathological content,
   amplified because a derailed fragment's tail becomes the next fragment's
   prompt. **None of the tested levers address this.**
4. OGG fragments/assembly cost accuracy versus WAV — an unexplained, consistent
   effect worth a targeted follow-up.

## Limitations

- **No independent ground truth.** The reference is the same model; absolute
  error rates are inflated by legitimate rewordings, and the reference itself
  loops. Conclusions here are *relative*.
- **Corpus provenance.** Classes were selected from metadata; the `en_` classes
  are largely detected as German. The `en_interview` class in particular is a
  comedy act, not an interview.
- **gregor-specific.** Server-side `vad_filter=True` and its `prompt` handling
  differ from cloud providers; GROQ was not studied.
- Small n per cell in the mechanism/prompt arms (1–4 runs).

## Reproduction

See [`tools/seam-study/README.md`](../../tools/seam-study/README.md). In short:
`./gradlew installHarness`, push the corpus, run the arm scripts, then
`python3 tools/seam-study/analyze.py --arms`.

## Open questions / next steps

- **Loop guard** (candidate fix): detect a repeated n-gram inside a fragment's
  transcript and collapse/truncate it **before** it is stored — which also
  removes it from the next fragment's prompt. Optionally assisted by Whisper's
  own `compression_ratio` / `no_speech_prob` (would require adding `segments` to
  `SttResponse`). A collapse policy is cheap and deterministic; a
  retry-without-prompt policy can recover content at the cost of a call.
- **OGG vs WAV**: localise the accuracy gap (chaining pre-skip tick vs
  pre-roll upload composition).
- **Independent reference** for a definitive drop/duplicate verdict.
- **Language-verified corpus** if language-controlled conclusions are needed.
- On-device re-check of the #120 cap (shadow no longer expands).

## References

- Issues: #122 (this study), #121 (parent), #116 (auto-sizer/shadow), #117
  (seams/pre-roll), #119 (WAV assembly), #120 (5-min cap).
- Study commits: `e941430`, `7862603`, `b401fbc`, `a222874`, `344ec69`, `8306589`.
- Knowledge: `docs/ai/{STATE,HANDOFF,DECISIONS,PITFALLS,ARCHITECTURE,CONVENTIONS}.md`.
