---
type: State
title: Project State — Audio Pipeline
description: Capture and spooling, slicing and seams, compression, audio evidence and the forensic bundle.
tags: [knowledge, state, audio-pipeline]
generated: { by: opencode/knowledge-persistence, at: 2026-11-25T12:00:00Z }
status: stable
---

# Project State — Audio Pipeline

Moved out of `STATE.md` in #206; sections are verbatim, newest first. The current status line, focus and pending items stay in `STATE.md`.

## #123 — Loop-Guard am erzwungenen Bruch (2026-11-20, shipped)

- **Was:** `LoopGuard.collapse()` (`:core`, auf `LoopMask.loopSpans`) ersetzt jeden Repetition-Loop durch seine erste Periode; aufgerufen in `FragmentTranscriber` **vor** dem Store und **nur** wenn `fragment.seamForced` — dort bekommt der Decoder Pre-roll + Prompt, also dort entsteht und wandert der Loop.
- **Warum so klein:** Loops im Alltag sind selten (~6 % Seam-Unterschreitung, zwei in Serie ~0,4 %); bewusst kein Setting, keinen eigenen Log-Stream, keine Policy-Matrix. `sttRaw` vs. `sttTrimmed` im `session.json` belegt jede Kürzung.
- **Tests:** `LoopGuardTest` (8) plus 2 Gate-Tests in `FragmentTranscriberTest` (Loop hinter erzwungenem Bruch → nächster Prompt und Join sauber; stille-aligned → unverändert). `:core` 171 → 181.

## #124 — slice geometry (2026-11-12, `68aaa04`)
- **Measured basis:** 380 retained sessions / 917 fragments / 787 timed requests pulled from the opo (`analyzer pull` into `corpora/opo-2026-11/`, local-only). Body fragments 15.0–19.6 s (median 17.7) at a nominal 10 s — the `search = max(2 s, S)` + late-half rule was producing 1.5–2× the nominal size. 61 % of dictations never committed a live fragment.
- **Cost model (Theil–Sen, per provider):** `a` = 0.76 s (gregor/ogg), 0.55 s (groq/wav); `b` = 2.57 %/s, 1.95 %/s; local encode 7.9 %/s (OGG) vs 0.1 %/s (WAV); stop→raw median 1.65 s (p90 3.13), RTF median 0.07. The model reproduces the measured gregor status quo at slice 20 s (predicted 2.00 s, observed 1.98 s).
- **Geometry:** slice = fragment MAXIMUM; search window = last `p` % of it; `cut ∈ [sliceEnd − window, sliceEnd]`; `SearchSpan` deleted. Migration: legacy `fragment_seconds_override` × 2, and the old (S, late half) geometry is exactly slice 2S with p = 25 %.
- **Sizer:** fixed budget 2.0 s, `chooseSlice` = largest slice fitting it, floor `8 s/(1−p)` wins over the budget, encode rate from the profile, **pure read** (no anchor, no write-back). Picks 20 s (gregor/ogg) / 67 s (gregor/wav) / 137 s (GROQ).
- **Adaptive window %:** forced-cut rate > 2 % → +5, < 0.5 % → −5, bounds 10–60, bootstrap 30, window re-anchored per step (`seamsTotalAtStep`/`seamsForcedAtStep`).
- **New profile fields:** `encodeMsTotal`/`encodeAudioMsTotal` (+ `encodeRate()`), `searchPercent`, `seamsTotal`/`seamsForced` (+ the step anchors). `lastAppliedSeconds` and `setSttProfileAppliedSeconds` are GONE; **no version bump** — the existing samples survive.
- **New preparer hooks:** `onSeamCut(forced)` and `onFragmentEncoded(audioMs, encodeMs)`; the controller wires both per session from `settings.sttProvider` + the compress flag.
- **Settings:** new "Advanced / seldom needed" section — `slice_auto` + `slice_seconds` (5–180 s), `search_auto` + `search_percent` (10–60 %), `compress_audio` relabelled **"Slow connection: upload OGG/Opus"**, and the line-wrap block (global + per-app) moved there. Auto = checkbox on → field locked, grey, showing the RESOLVED value; unchecking hands that value over as the start. Grey values re-resolve when the Slow-connection box flips (format is part of the profile key).
- **Stage line (#116):** `RequestingSttFragment(audioSeconds)` replaces `RequestingSttProgress(pendingSeconds)`; emitted at request start; string `STT · %1$ds audio`. The backlog figure is gone from the UI (it stays in `pendingAudioSeconds()` for evidence).
- **Analyzer:** `--slice-seconds`/`--search-percent`; the run default moves to 14.7–21 s fragments, so **the #129 baseline must be re-run** (published runs stay valid as model v1).


## #149 — what shipped (2026-10-15, `40aee05`)

- **`:analyzer` `reconstruct` command** (`bundle/ReconstructCommand.kt`): `analyzer reconstruct --corpus NAME [--session ID-SUBSTR] [--dry-run]` — for every post-#142 bundle in `<corpus>/bundles/`: iterate fragments in index order, cut `preRollMs × 32` bytes off each composed `upload_*`'s head, tile the pieces into `wavs/<sessionId>.wav` (16 kHz mono s16le) + a manifest entry (class `reconstructed`, sha256, seconds — idempotent per path) so `analyzer run` consumes it like any corpus WAV. `--dry-run` validates and reports without writing.
- **Strict gate (owner decision):** a bundle with ANY incomplete audio — `sttError`, missing upload file, missing/inverted boundaries, gap/overlap between consecutive boundaries, or a piece deviating > ±3 ms from its boundary span — is DISCARDED WHOLE (reported, never partially reconstructed). The ±3 ms tolerance is precision, not sloppiness: `startMs`/`endMs` are PCM-bytes/32 truncated (±1 ms per edge) and `preRollMs` is `AudioDuration.estimateMs` ms-truncated (±1 ms).
- **Pre-roll cut keys on the upload NAME:** `upload_*` = composed (pre-roll in the head) → cut; `frag_*` = degraded bare fallback → copied whole with a note, even though `preRollMs` is recorded (composeUpload's degradation path keeps the fragment's own name — cutting there would eat real fragment audio).
- **Mixed-bundle reality supported:** format detection PER FRAGMENT from the `uploadFile` extension — never from `provider.compressAudio` (a session-start snapshot, legitimately wrong in the two designed mixed cases; see PITFALLS). WAV pieces pure-Kotlin (:core WavReader/WavWriter); OGG pieces via host **ffmpeg** (`-f s16le -ar 16000 -ac 1`, documented host dependency; present on think + CI runners). compressAudio mismatches are advisory notes, not verdicts.
- **Tests:** 12 new (`ReconstructCommandTest`) — synthetic bundles built with the REAL `:core` SessionBundle + composeUpload semantics: byte-exact WAV round-trip, OGG/mixed (OGG prefix + WAV tail) round-trip via real ffmpeg (assumption-guarded), degraded-bare no-cut, discard matrix (sttError / missing upload / gap / lying pre-roll), legacy skip, manifest idempotency, dry-run, session filter, compressAudio cross-check. End-to-end CLI smoke on a synthetic corpus verified the written WAV (10.000 s exact) + manifest.
- **Typical pipeline:** `pull` → `reconstruct` → `run` (the #129 fragment-size sweep). analyzer/README.md documents the command.

## #148 — what shipped (2026-10-15, `04903e5`)

- **`:core` `SessionBundle.FragmentRecord`:** + `sttSentAt` / `sttReceivedAt` (epoch ms wall-clock of the SUCCESSFUL attempt — the bracket includes `SttRequestRunner.run`'s internal retry loop: the request duration as the app saw it) + `uploadBytes` (the composed upload's size — keeps bundles self-contained when `uploads/` ages out).
- **`FragmentTranscriber.process`:** each `sttRunner.run` attempt is bracketed; the 400-retry (prompt fallback) RESETS both timestamps so the failed attempt's latency never contaminates the recorded pair. Failure branch records `uploadBytes` + `sttError` but NO timing (minimal shape per the issue). `uploadBytes` is captured at composition time — the composed upload is deleted in the finally-block BEFORE the outcome fold (a `length()` read there would record 0).
- **Analyzer:** `FragmentDto` mirrors all three fields (round-trip-guarded by `SessionBundleRoundTripTest`); new helpers `BundleIo.requestMs` / `realTimeFactor` (request vs audio duration, `RTF < 1` = faster than realtime); `report` gains `reqs` / `req(s)` / `RTF` / `up(kB)` columns + a "timing (#148)" summary line (slowest request, worst RTF); legacy bundles parse and contribute no timed request. New `ReportCommandTest` (timed + legacy session). analyzer/README.md documents the columns.
- **Serves:** the owner's server-timing use case on the 6-month-retained opo bundles (network latency vs transcription speed per request), alongside #129's seam analysis from the same bundles.
## #129 first real-dictation scan (2026-10-15, on think)

- The A03 (`R7SRB06XF5K`, attached on think) carries **3 retained dictations from 2026-10-14** in the PRE-#142 format (`session.wav` + `fragments/`, no `session.json`) — created before the v1.4.1 in-place install and preserved by it. Corpus `corpora/a03-evidence-2026-10/` (bundle + wav kinds), run-id `a03-reanalysis-20261015`, production knobs, gregor reference in ≤300-s slices.
- Result: **zero real seam duplication** (`overlap=0` across 47 seams); 6/34 splitFlag heuristics on the 12.8-min session (documented false-positive-prone, no overlap backing); that session's gregor REFERENCE looped 40× (refLoops=40 vs joinLoops=0) — the loop-mask held the diff (`missing=275` instead of an explosion; #122 discipline proven on live data). Session 3 (5.2 min) has a REAL concentrated diff: fragment 4 missing 46 tokens, fragment 12 missing 25 (echo=0, not seam-related — Whisper derailment class, flagged for manual review; a #123 data point).
- **No qualityMin conclusion** — no real sick seams in this sample. The decisive source is the OnePlus (post-#142 session.json with per-seam Q, days of daily dictations, 30-d retention): once attached on think → `analyzer pull` → `scan`.
- **PullCommand hardened en route (`2f70422`):** (a) waitFor-before-read pipe deadlock — every output > ~64 KB (i.e. every session.wav) burned the 60-s timeout; output now drains on a background thread before `waitFor`; (b) directory entries were catted as files because `adb exec-out` does not propagate remote exit codes — `ls -1p` dir detection + one-level tree walk now handles both bundle formats (post-#142 `uploads/`+`session.json` AND pre-#142 `session.wav`+`fragments/`). 4 regression tests in `PullProcessTest`; 527 tests green.

## Epic #141 — #142 shipped (`e150a74`, 2026-10-14)

- **The bundle:** `files/audio/<sessionId>/` = `uploads/` + `session.json` ONLY. `SessionBundle` (pure JVM, Gson, fragments as a sorted JSON ARRAY via a serialization DTO): per-fragment boundaries + START-seam decision/quality block (mirrors the prepare contract), uploadFile/preRollMs/sttRaw/sttTrimmed/echoTokens/prompt/sttError; session-level provider info, the LLM block (via the pipeline's `var onOutcome` hook — no signature change, no mock churn), and the delivery outcome (`inserted` | `error`).
- **Retention:** `SettingsStore.evidenceRetentionSeconds` (0 = off; legacy `audio_evidence` migrates ON → 30 d), `EvidenceRetention` log-stops 1 h → 50 years + labels, SeekBar + live label in Settings; `AudioEvidenceStore` age-eviction (#132 prefix, mtime fallback) + invisible 512-MB storage valve (oldest-first, newest survives) + app-start `evict()`; defaults: release 0, debug/harness 30 d. `LOG_ROTATION_DEPTH` unified at 32.
- **Bonus fix:** bare fragment uploads (no pre-roll) are retained NOW — the pre-#142 code skipped the evidence copy whenever the upload WAS the fragment file (WAV mode lost its audio evidence entirely).
- Tests 485 green (SessionBundleTest, the eviction matrix, the migrations, slider mapping; the ring-buffer tests replaced). Next: #144 (the PC analyzer — #129's offline analysis path).
## #135/#136 — what shipped (`c5f2046` + `2c362db`, 2026-10-13)

- **The crash:** A03 (release), ~24-min dictation → `OutOfMemoryError` (46,438,416-B allocation) in `AudioRecorder.copyPcmRange` → `BAOS.toByteArray` on the live fragment-worker thread → process death, dictation lost. Root cause: the ranged read copied the WHOLE session buffer per fragment (+#135: `stopPreservingBuffer` stacked TWO full copies at stop, `snapshotPcm` a third).
- **Stage 1 (`c5f2046`):** in-RAM block chain + `WavWriter.writeHeader` (stop WAV assembled with one copy). **Stage 2 (`2c362db`, owner directive):** recorder file-backed (`cacheDir/recording.pcm` spool, one RAF + lock) — `copyPcmRange` = seek+read, `PcmSource` contract unchanged (`FragmentPreparer` untouched), `stopSessionWavFile(): File` streams the WAV, snapshot = disk copy, restore = RENAME onto the spool. Controller threads `File` through runTranscription/prepareAudioFiles/retainAudioEvidence; legacy WavChunker branch materializes bytes only for empty recordings; stop WAV deleted after retention/cancel.
- **Result:** session RAM O(1) — dictation length no longer heap-bounded; evidence fidelity unchanged. Suite + pre-push green; `assembleRelease` + `compileHarnessKotlin` green; deployed to both devices 2026-10-13 23:48.

## #132 — what shipped (`a431a03`, 2026-10-12)

- **Retention capacity is a BUILD-TYPE property:** `AUDIO_EVIDENCE_MAX_SESSIONS` (int) / `AUDIO_EVIDENCE_MAX_BYTES` (long) buildConfigFields — release 3 / 50 MB (the #127 defaults, byte-identical behaviour), debug+harness **99 / 1 GB**; `PolishedRecognitionApp` passes them into `AudioEvidenceStore` (whose own defaults stay as fallback). Rationale: problematic stitches are rare; the owner dictates in daily life and analyses days later, so the corpus must bridge multi-day gaps. The 1-GB byte cap still binds before the session cap on long dictations (by design — storage notary).
- **Timestamped session IDs:** top-level `internal fun newSessionId(now: Date = Date())` in `VoiceSessionController.kt` → `yyyyMMdd-HHmm-<8hex>` LOCAL time + 8-hex uuid suffix (unique within the minute, one path/JSON-safe token); both creation sites use it. Retained evidence dirs are recognisable by dictation date/time at a glance — and harness-run sessions from different runs are distinguishable without log correlation.
- ~~`sessionId` in EVERY `stt-shadow` row~~ — SUPERSEDED (#143): the shadow streams are REMOVED; session correlation now lives in `session.json` (#142).
- **`LOG_ROTATION_DEPTH`:** release 9, debug/harness 99 rows per stream (#132) — [ACTIVE until #142] the build-type distinction loses its role with the forensic bundle; the plan is a unified small depth.
- **`pull_retained.sh --last N`:** device-side `ls -t` selection of the N newest sessions (full listing still shown); safe routine pulls once 99 sessions can accumulate.
- Tests: +1 (`newSessionId` format/prefix), shadow test extended (`sessionId` assertion) — suite green incl. pre-push; `compileHarnessKotlin` + `assembleRelease` green. **Deployed:** harness APK in place on both devices (A03 `R7SRB06XF5K` 15:52, OnePlus `f6de166c` 15:53), provider configs preserved (same applicationId + signing key).

## #129 research run — results (2026-10-12)

- **Winners (`samples/results/winners.txt`):** `axisK = 0.75` (adoptable — moves the axis above the fricative band, strictly safer at zero cost on clean audio; provisional was 0.65), `qualityMin = 40.0` (NOT adoptable as a decision — on clean audio any threshold ≥ the Q floor scores identically; the knob only has meaning where seams are sick), `axisC = 100.0` / `marginKMs = 150` / `searchSpan = lateHalf` (provisional defaults confirmed; flat curves — stability knobs as predicted from the #122 null pattern).
- **Evidence chain:** probe arm green 16 s end-to-end; 222 CSV rows (`tools/seam-study/quality_results.csv`, derived, git-ignored); per-seam Q evidence complete on ≤60-s subset samples; 0 seam overlap / 0 word splits / 0 trim-fp across all arms; `phone_radio_30s` unreliable at every level (hard class, not a knob effect).
- **Runner fixes during bring-up:** health check accepts 401/403 as reachable (`b04cbf3` — the auth token lives on the phone); `samples/results/` + derived CSV git-ignored (`2216942`, `e1c1c36`).
- **Owner dependency (the epic's critical path):** bist-class dictations on EITHER device (both run the harness build with retention ON — sessions auto-land in `files/audio/`, the ring buffer keeps 99/1 GB since #132, so multi-day gaps no longer lose evidence; pull with `pull_retained.sh [--last N]`): German sentences with word-final /st/ + pause near a ~10–20-s boundary ("…informiert **bist**" ⏸ "Jedenfalls…", plus ist/kannst/musst/erst). THEN: `--full-factorial` (3×3 axisK×qualityMin on the full corpus) → parameter proposal.

## #128 — what shipped (`c4c14ab`)

- `SeamPolicy.Q_GATE` (controller default; ALL/FORCED_ONLY/OFF frozen A/B arms; harness `policy` extra accepts `qgate`). Boundary: STRICT `Q < qualityMin` (Q == min → bare); null Q (legacy manifests, fixed cuts) stitches; forced cuts (Q = 0) always stitch.
- `FragmentPreparer.Entry.seamQ` (manifest-round-tripped; Gson omits nulls → legacy manifests parse); `seamStitchedAt` = THE decision (pre-roll composition, 5-arg hook / `Committed.stitched`, `prepare.json`'s now-real `stitched`).
- `FragmentTranscriber` dropped its own `SeamPolicy` — prompt iff `CommittedFragment.stitched` (+ `!promptDisabled`); trim unchanged (keys on the pre-roll file). Pre-roll/prompt/trim can never disagree.
- `VoiceSessionController.fragmentQualityMin` (PROVISIONAL 10.0 = `FragmentPreparer.DEFAULT_QUALITY_MIN`; #126 fixtures bracket it: solid Q ≈ 40 bare, whisper Q ≈ 2.26 stitch; #129 measures the real value).
- Tests +8 → **473 green** (5 skipped integration as always); fixture numbers pre-verified against the #126 Python mirror (`/tmp/opencode/sq-mirror/predict_128.py`). Pre-push suite ran clean first try (2048m heap since #131 — no flake).

## #126 — what shipped (`7ff3800`)

- `audio/SilenceQuality` + `SearchSpan` (pure JVM): per-fragment M/P5-floor/max over the high-passed search span (`PcmConditioner.highPassOnly` — the one-pole extracted, normalization-free), axis `X = k·M + c`, deficit curve, candidates, Q, margin-clamped area-centroid cut; no candidate → forced quietest-frame cut (Q = 0). `candidateFound ⇔ Q > 0` is the interim `silenceAligned` mapping (#128 replaces it with `qualityMin`).
- [SUPERSEDED by #124: the search span is the slice's last `p` %, not a fixed late half.] `FragmentPreparer`: default search span = LATE half (owner decision); the decision always waits for the full window; `energyFallbackEnabled` inert; `prepare.json` carries the contract block `{M, min, max, X, Q, centroidMs, stitched:false}` instead of `silenceAligned`.
- `VoiceSessionController` plumbs `fragmentAxisK`/`fragmentAxisC`/`fragmentMarginKMs` — PROVISIONAL defaults 0.65 / 100.0 / 150 ms; #129's research pass determines the real values (no Settings UI). [SUPERSEDED: `fragmentSearchSpan` no longer exists — #124 replaced it with the search-window percentage.]
- Tests +14/−11 → **465 green** (5 skipped integration tests, as always); all fixture expectations pre-verified against an exact Python mirror of the metric (`/tmp/opencode/sq-mirror/`).
- Whisper-regime finding: a 6-dB band at whisper level is NOT the forced path with these provisionals (the axis threads/overshoots the band → one shallow candidate, Q ≈ 2.3 — the issue's "Q-gate arms stitching" case); the forced path needs a tight band with `M ≥ c/(1−k) ≈ 286`. Both regimes unit-tested; #129 measures the real boundary.

## #127 — what shipped

- `pipeline/AudioEvidenceStore` (pure JVM, injectable dir + clock): copy-only retention of `session.wav` + `fragments/` + `uploads/` into `<externalFilesDir>/audio/<sessionId>/`, ring buffer ≤ 3 sessions / ≤ 50 MB (newest session always survives), tmp+rename writes, never throws.
- Capture points: controller `retainAudioEvidence()` after `runTranscription` before `clearPrepared()` (success AND failure); `FragmentTranscriber` upload `finally` before `delete()`. `cancel()`/`flush()` = deliberate discard, no retention.
- Gate: `audio_evidence` toggle (`SettingsStore.audioEvidenceDefault` ← `BuildConfig.AUDIO_EVIDENCE_DEFAULT`: false release / true debug+harness); privacy hint in Settings; harness wires the store + gate too.
- Tests +18 → suite 462 green (now 465 after #126).

## #124/#125 — what shipped (`3647554`)

- [SUPERSEDED by #124 — `resolvedSearchBytes()` no longer exists; see `resolvedSliceBytes()`/`resolvedSearchWindowBytes()`.] ~~`VoiceSessionController.resolvedSearchBytes()`: with a `fragmentSizeProvider` wired (production), `search = max(fragmentSearchBytes, resolvedFragmentBytes())` → fragment spans `[S, 2S]`; explicit injected values keep their exact semantics (`0` = legacy fixed cuts); the harness dropped its provider wiring so its explicit `searchSeconds` stays authoritative.
- [SUPERSEDED by #124: there is no `SilenceCutter` and no `[1.5S, 2S]` span any more.] `SilenceCutter.quietestFrameCut()` + `FragmentPreparer.energyFallbackEnabled` (default true): on a silence-free FULL window, cut at the minimum-energy 10-ms frame center in the late half `[1.5S, 2S]`, tagged FORCED (`FORCED_ONLY` still applies pre-roll + prompt). Harness knob `--ez energyFallback`.
- ~~`BuildConfig.SHADOW_STT` / `FULL_CONTEXT_AT_STOP`~~ — REMOVED in #143 (the fields, the wiring, the streams; `SttEndpointPolicy` was already deleted in #125).
- Tests +8 (444 green); `assembleRelease` + `compileHarnessKotlin` green.

## Energy-fallback extrapolation check (harness A/B, OnePlus → gregor)

Frozen config (frag=10, search=10=S, forced, wav, raw, shadow reference), 8 runs all green, loop-masked:

| arm | frags | forced seams | err/100w |
|---|---|---|---|
| search = 2 s (study config) | 68 | 3 | 6.2 |
| search = S = 10 s (shipped) | 52 | 0 | 4.1 |

The adaptive search eliminates forced seams on Tier-1 classes; the energy fallback NEVER fired there (both arms byte-identical — every 10-s window contains a qualifying pause). The fallback's regime is continuous/background-noise audio, where the shadow reference is itself unreliable — it stays validated at the unit-invariant level. Runs + analysis: `/tmp/opencode/seam-run/results/EF_*`, runner `run_energyfallback.sh`.

