---
type: State
title: Project State — STT, LLM and Providers
description: Failure classification, uploads, provider behaviour.
tags: [knowledge, state, stt-providers]
generated: { by: opencode/knowledge-persistence, at: 2026-11-26T19:40:00Z }
sources: ["#206", "#153"]
status: stable
---

# Project State — STT, LLM and Providers

Moved out of `STATE.md` in #206; sections are verbatim, newest first. The current status line, focus and pending items stay in `STATE.md`.

## #153 — reasoning_effort on the LLM chat wire (2026-11-26, shipped)

- **What the provider says:** the opencode-go endpoint's `/v1/models` response carries per-model
  capability extensions — verbatim `capabilities.reasoning { "supported": true, "effort": ["low",
  "high", "max"] }` (probe: `/tmp/opencode/go-models.json`, 2026-11-26). The level vocabulary is
  PER MODEL: `none, minimal, low, medium, high, xhigh, max` all occur in one catalog. The standard
  OpenAI body (and the LiteLLM gateway's Whisper-only setup) carries none of it — the app treats
  the extension as optional everywhere. **Scope guard: the ticket touches ONLY the LLM polish path;
  the STT multipart upload is byte-identical to before.**
- **Request:** `ChatRequest.reasoningEffort` (`@SerializedName("reasoning_effort")`, nullable) —
  Gson omits the member when null, so a provider that never heard of the field receives a
  byte-identical request. `ModelEntry.capabilities` parses the extension additively.
- **Config:** `LlmProviderConfig.reasoningEffort: String?` rides the provider JSON round-trip
  (`readProvider`/`writeProvider` untouched — the null member is simply never serialized).
- **Cache:** `CachedModels.reasoningEfforts: Map<String, List<String>>?` — nullable on purpose;
  the Gson-bypasses-Kotlin-defaults pitfall (#101 comment) applies, readers normalize `orEmpty()`.
  TTL/prune shared with the id list.
- **Normalization:** `SettingsValidation.apply` writes the effort only when the configured model's
  cached list advertises it — a stale value decays to `null` (nothing sent) instead of going on
  the wire against the provider's vocabulary.
- **Header requirement (device finding 2026-11-26, superseding the docs' "no longer
  necessary" note):** OpenCode's **Go** endpoint answers HTTP 400 without `x-opencode-session` —
  the app sends a stable per-install UUID on Go-URLs only (`OpenCodeSessionInterceptor`, owner
  requirement: never on the Console endpoint or any other host); see `state/settings.md`.
- **Not verified:** whether a real chat completion with a picked effort is accepted end-to-end —
  the opencode-go chat endpoint needs an API key (401 in the probe); `reasoning_effort` itself is
  documented OpenAI standard and LiteLLM-normalized. The owner's device check covers the UI path.

## #75 — provider refusals: the body decides, a budget wall is asked ONCE (2026-11-22, shipped)

- **Was:** the only refusal the app has ever produced is a spend cap that cannot clear on retry. LiteLLM answers `429` with `{"type":"budget_exceeded","message":"ExceededBudget: … Spend=5.0006, Budget=5.0"}`; the #183 matrix reads "429 → TRANSIENT", the runner spends three attempts per fragment, #185 auto-retries the parked session on the next Workbench visit — and the user is told "HTTP 429". 9 of 100 measured request records were exactly that.
- **`ProviderError` (`:core`, pure, new):** parses the error envelope defensively (`{"error":{…}}` OR flat, `type`/`code` as string OR number, HTML/empty/unparseable → `null`), collapses the message to one line, runs it through `LogRedaction` (an error page may echo the authorization header) and caps it at 300 chars. `isBudget` is true only for `budget_exceeded`/`insufficient_quota`/`quota_exceeded`, matched case-insensitively against `type` AND `code`.
- **`SttFailure.BUDGET` + `attemptsFor`:** a 4xx whose body names a budget wall is BUDGET — exactly one attempt. The attempt table is now explicit: TRANSIENT 3 (`MAX_ATTEMPTS` stays the hard bound), BUDGET/AUTH/PROTOCOL/TERMINAL 1. **PROTOCOL dropping from 2 to 1 supersedes #183's "repeat exactly once"** (the class never occurred in the device logs; the manual Retry STT of #188 covers a one-off glitch). A bare `429`, a `rate_limit_exceeded` and an HTML/empty body stay TRANSIENT.
- **The sentence reaches the user:** the exception message is `HTTP 429 — ExceededBudget: … Spend=5.0006, Budget=5.0`; `SttFailureText` maps BUDGET to "Provider budget exhausted — top up or switch provider" and the IME prefixes it to the detail, unchanged (`failureText`).
- **The latch generalises:** `FragmentTranscriber.authBlocked` became `blockedFailure: SttFailure?` covering AUTH and BUDGET. A 3-fragment session whose first fragment hits BUDGET makes ONE request; the other two are recorded as failed without one, their composed uploads still reach the journal as PENDING with `failureClass = BUDGET`. `resetFailures()` clears it (a new Send may follow a top-up).
- **Diagnostics — existing containers only:** `stt-latency` gains `headers` (`retry-after` + `x-ratelimit-*`, lowercased, 64-char values) and `providerError` (type/code/message), on the success path too; both are omitted when absent, so a pre-#75 record keeps its shape. `session.json`'s fragment record gains the additive `rateLimit` block (`httpCode`, `type`, `message`, the limit headers) written where `sttError` is written. No new file, no new stream, no Settings change.
- **LLM path:** both chat call sites parse the same envelope and raise the same carrier (`SttRequestException`, class included), message `LLM post-processing failed: HTTP 429 — …`; the RAW text is still stored (`PipelineOutcome.rawText`), unchanged.
- **Recovery:** `RecoveryReason.of("BUDGET")` → `Kind.BUDGET` ("Provider budget exhausted"), `autoRetryable` = false. `JournalReplay` records the class for free (it already stores `failure.failure.name`), so a re-run that hits the wall stays in the manual list.
- **Deliberate cut:** the issue also sketched a "gate skip" log line for a fragment that never made a request. Not built — the fragment already carries class + text in the journal AND in the bundle.
- **Tests:** 902 → **932** (`:core` 270 → 297: `ProviderErrorTest` 9, `SttFailureClassifierTest` +5, `SttRequestRunnerTest` +8 net (two PROTOCOL-retry tests rewritten), `FragmentTranscriberTest` +4, `SessionBundleTest` +1; `:app` 577 → 580: `SttFailureTextTest` +1, `RecoveryReasonTest` +1, `TranscriptionPipelineTest` +1). No version bump.
- **Docs drift fixed en route:** CONVENTIONS still claimed "PROTOCOL exactly once", `docs/ai/ARCHITECTURE.md` still described the 2-attempt PROTOCOL, and HANDOFF claimed #75's device evidence was unreadable on this host — `adb devices` on `think` shows `R7SRB06XF5K` (Samsung SM-A032F) and `f6de166c` (OnePlus HD1903), and the logs read fine under `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs`.
- **Not verified:** the school gateway's own behaviour (whether it really strips the headers) — the body signal is the guarantee, not a measurement, and the header block is best-effort by design.

## #183 — STT failures are classified before anything acts (2026-11-20, shipped)

- **Was:** the runner knew "transient" (5xx/429/IOException) and "everything else". A dead token (five 401s in one session after an endpoint migration) and an unusable 200 were therefore the same category: neither stopped the hammering nor told the user anything they could act on.
- **`SttFailureClassifier` (`:core`, pure):** TRANSIENT (IOException/timeouts, 408, 429, 5xx), AUTH (401/403), PROTOCOL (a 2xx with no transcript, or an undecodable body — Gson/Retrofit validate nothing), TERMINAL (other 4xx, unexpected exceptions). `MalformedJsonException`/`EOFException` are IOExceptions and are matched BEFORE the transient branch.
- **Runner:** AUTH and TERMINAL fail immediately, TRANSIENT retries with `max(backoff, Retry-After)` (10 s per wait, 20 s per request — a longer advice fails instead of retrying early), PROTOCOL repeats the identical request exactly once. `Retry-After` accepts delta-seconds and an HTTP date (`RetryAfter`, injectable clock). The completion log carries `retryAfterMs`; `SttRequestException` carries `failure`.
- **Transcriber:** an AUTH failure sets a session latch — later fragments are recorded as failed WITHOUT a request, and their composed upload still reaches the journal. `resetFailures()`/`cancel()` clear it. PROTOCOL is journaled `NEEDS_MANUAL`; AUTH/TRANSIENT stay PENDING (retryable). "Resume after a token change" needed no new mechanism: the token is read per fragment from `SettingsStore` and `runTranscription` already calls `resetFailures()` — only the latch was missing.
- **Surfacing:** the controller wraps the failure in a `SttRequestException` carrying the class instead of a bare `Exception`; the IME maps AUTH/PROTOCOL through `SttFailureText` to a hint and APPENDS the technical detail. One Toast at stop, no new event, the bar parks PAUSED so Send retries.
- **Redaction:** `LogRedaction` (Bearer, `sk-`/`gsk_`-style keys) runs in `ResponseLoggerInterceptor` before the body reaches `stt-response.json`. The token charset excludes `"`/`'` — a `\S` class ate the JSON's closing quote and produced an invalid log body (found by test).
- **Deliberate deviation:** PROTOCOL repeats the request byte-identically instead of "a conservative shape" as the issue sketched — the only optional field is the prompt, and a prompt problem answers 4xx, not a broken 200. Recorded in DECISIONS.
- **Tests:** 807 → **840** (`SttFailureClassifierTest` 7, `RetryAfterTest` 4, `LogRedactionTest` 4, `SttRequestRunnerTest` +9, `FragmentJournalTest` +2, `FragmentTranscriberTest` +3, `SttFailureTextTest` 4). No version bump.
- **Not verified:** the school gateway itself (the reporter's network) — the caps are the guarantee, not a measurement.

## #184 — idempotent fragment uploads (2026-11-20, shipped)

- **Was:** every request was a bare multipart WAV with an optional prompt — the third attempt of fragment 7 was indistinguishable from fragment 8, so a lost acknowledgement plus a retry could append the same text twice at a server that does not dedupe.
- **Wire:** `session_id`, `fragment_index` (0-based, the journal/`session.json` key) and `attempt` go out as ordinary multipart fields through ONE `@PartMap` on `transcribeAudioSync`/`transcribeAudio`. An **empty** map omits all three, so the legacy chunk path and every server that ignores unknown fields keep the exact pre-#184 request.
- **Retries:** all attempts repeat the same identity and bump only `attempt`; the #117 prompt-rejection retry (HTTP 400) also re-sends the same identity, or a server would see two fragments.
- **Client guarantee:** `offer()` + the worker already key on the fragment index, and `transcripts.putIfAbsent` is the second line — a second success for one index is dropped with a `duplicate success … dropped` log line instead of doubling the joined text.
- **Pitfall found:** Retrofit rejects Kotlin's `Map<String, out RequestBody>` (`? extends` wildcard) — `@JvmSuppressWildcards` is mandatory, and the failure appears only at the first REAL call; the `:analyzer` `ReplayCommandTest` (real Retrofit) caught it, no mocked test could.
- **Deviation:** no new `session.json` fields — `SessionBundle.sessionId` IS the sent `session_id` and the fragment record is keyed by the same index, so duplicating them would add no verifiable information (DECISIONS entry).
- **Tests:** 802 → **806** (`SttRequestRunnerTest` +3: identity fields, retry identity, empty map; `FragmentTranscriberTest` +1: per-fragment identity). No version bump.

