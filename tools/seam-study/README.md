# Seam-study material (#122/#129 — historical)

This directory now holds the seam study's **corpus provenance and historical
results** only. The study's *code* has moved to the PC: research runs go
through the **`:analyzer`** CLI (see
[`analyzer/README.md`](../../analyzer/README.md)) — it compiles the REAL
production pipeline via `:core` and replaces the device-driven harness build,
which was **removed in #147**. The write-up lives in
[`docs/research/2026-10-seam-study.md`](../../docs/research/2026-10-seam-study.md).

## What remains here

| path | what |
|---|---|
| `corpus/` | `build.sh` (ffmpeg conversion — the build blueprint for future corpora), `samples.json` (manifest: class, source, offset, durations, sha256), `REPORT.md` (class confidence). Imported as Korpus #1 (`seam-study-01`) in #146. |
| `testdata/quality-fixture/` | **#129**: synthetic arm (`harness.json` + `stt-fragment.json` + `prepare_all.json`) with known expected values — kept as provenance; the analyzer's regression test reads the identical copy in `analyzer/src/test/resources/quality-fixture/`. |
| `quality_subset.txt` | **#129**: the 11-sample representative subset for the descent stages (rationale inside). |
| `results.csv` | the 126-run per-run table (raw data snapshot of the #122 study). |
| `generate_corpus_manifest.py` | one-shot migration tool (used in #146 to turn `samples.json` + build report into the analyzer manifest convention). |
| `pull_retained.sh` | pulls retained evidence sessions (`files/audio/<sessionId>/`) off a device into `samples/retained/` — still the workflow behind `SAMPLES.md` (the analyzer's `pull` command writes into `corpora/<name>/bundles/` instead). |
| `SAMPLES.md` | the git-ignored `samples/` layout (corpus on the A03 + retained pulls). |

## How research runs now

No device harness build, no `installHarness` — the corpus lives on the PC
(`corpora/seam-study-01/`):

```bash
# corpus mode: one real pipeline transcription per WAV + reference pass
ANALYZER_STT_TOKEN=... analyzer run --corpus seam-study-01 [knob options]

# bundle mode: Q-scan over device bundles (the #129 natural-find detector)
analyzer scan corpora/seam-study-01/bundles

# device bundles: adb pull (gotchas encapsulated), then aggregate/compare
analyzer pull --corpus seam-study-01 [--serial f6de166c]
analyzer report corpora/seam-study-01/bundles
analyzer compare corpora/*/results/<run-id> [--by-knob axisK]
```

The old Python analysis (loop-mask, seam attribution, quality statistics) was
ported verbatim into `:core` (`pipeline/LoopMask`) and `:analyzer/analysis/`;
`FixtureRegressionTest` asserts every value the deleted `analyze.py
--selftest` asserted. The device-driven runners (`run_matrix.sh`,
`run_prompt*.sh`, `run_quality.sh`, `merge_prepare.py`) are deleted — their
functionality is the `run` command's knob matrix.

## Historical results

- `samples/results/` (git-ignored, host-local) holds the #122/#129 runs —
  including `winners.txt` (the staged coordinate-descent winners:
  `axisK=0.75` adoptable; `axisC`/`marginKMs`/`searchSpan` confirmed;
  `qualityMin` UNDECIDED — flat curves, the corpus is too clean).
- `results.csv` is the committed snapshot of the 126-run #122 table.
- The #129 findings and caveats are in the research write-up.

## Caveats (read before trusting a number — carried over)

- **The reference is not ground truth.** One big Whisper pass loops/
  hallucinates on repetitive, musical or noisy content and its VAD collapses
  on long uploads — the analyzer **loop-masks both texts** and slices
  references to ≤300 s. Without that, the metric mostly measures the
  reference.
- Absolute error rates are inflated by legitimate rewordings (casing,
  punctuation, number formatting); the **trends** are the signal.
- The corpus was selected from metadata only (no listening); the `en_`
  classes are largely detected as German — labels are unreliable,
  conclusions are not.
- Everything is gregor-specific; GROQ (no server-side VAD, different
  `prompt` handling) may behave differently.
