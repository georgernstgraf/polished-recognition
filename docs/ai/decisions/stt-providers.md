---
type: Decisions
title: Decisions — STT, LLM and Providers
description: Endpoint configuration, model listing, prompts, failure classification, retries, uploads, language handling and latency.
tags: [knowledge, decisions, stt-providers]
generated: { by: opencode/knowledge-persistence, at: 2026-11-26T21:05:00Z }
status: stable
---

# Decisions — STT, LLM and Providers

Moved out of `DECISIONS.md` in #206; entries are verbatim. New decisions in this area are appended here.

## 2026-11-26: #214 — Tokens gehören zur URL: geteilter Tresor, aktiv schlägt Tresor, Unvalidiertes ist wertlos

Drei Regeln, Owner-entschieden (Nachtrag zum geschlossenen #214, wieder geöffnet):

- **Der Token wird pro Provider-URL gemerkt** (`canonicalUrl → token`, ein gemeinsamer Tresor für
  STT und LLM im Secrets-File — gleicher Endpoint = gleiche Credential). `canonicalProviderUrl`
  trimmt, lowercased Scheme+Host und entfernt Slashes am Ende: `…/v1` ≡ `…/v1/`; der Pfad bleibt
  wörtlich (self-hosted Endpoints können case-sensitive sein).
- **Aktiv schlägt Tresor.** Der pro Typ zuletzt angewandte Token gewinnt zur Request-Zeit — ein
  älterer Tresoreintrag überschreibt nie das faktisch Angewandte; der Tresor ist nur das
  Erinnerungsnetz für Providerwechsel. Jeder Apply legt seinen Token unter seiner URL ab; wer einen
  Token bewusst leert, wirft den Eintrag weg — außer der ANDERE Typ zeigt noch auf denselben Endpoint.
- **Unvalidiertes Fummelei ist wertlos.** Bei einem URL-Wechsel (Fokusverlust oder Presetwahl) wird
  das Token-Feld IMMER aus dem Tresor gefüllt — oder geleert, wenn der Tresor nichts hat. Die
  „Feld unberührt“-Prüfung war owner-seitig übervorsichtig und falsch; der Validierungszustand
  entscheidet, nicht die Berührung. Die geleerte Variante draftet `""`, damit der Apply nie den
  Token des VORHERIGEN Providers unter die neue URL schreibt (das ursprüngliche Leck).

Backup bleibt unangetastet: der Tresor liegt in `polished_recognition_secrets.xml`, das beide
Regeldateien exkludieren — kein Tresoreintrag erreicht die Google-Cloud-Sicherung oder den
Gerätetransfer.

## 2026-11-22: #75 — die Refusal-BODY entscheidet; ein Budget-Wall wird genau EINMAL gefragt

Die einzige Fehlerart, die das Gerät je erzeugt hat, ist eine Kostengrenze, die
kein Retry aufhebt: LiteLLM antwortet `429` mit
`{"type":"budget_exceeded","message":"ExceededBudget: … Spend=5.0006, Budget=5.0"}`,
die #183-Matrix liest daraus „transient", der Runner verbraucht drei Versuche
pro Fragment, #185 versucht es beim nächsten Workbench-Eintritt erneut — und der
Nutzer liest „HTTP 429". 9 von 100 gemessenen Request-Records waren genau das.

- **Der Body entscheidet, nicht der Status und nicht die Header.** `ProviderError`
  (`:core`, pure) liest den Fehler-Envelope defensiv (`{"error":{…}}` ODER flach,
  `type`/`code` als String ODER Zahl, HTML/leer → kein Provider-Fehler), macht
  aus der Provider-Sentence EINE Zeile, schickt sie durch `LogRedaction` und
  kappt sie bei 300 Zeichen. `isBudget` gilt nur für `budget_exceeded`,
  `insufficient_quota`, `quota_exceeded` (case-insensitive, gegen `type` UND
  `code`). **Header sind Diagnose, nie Entscheidung:** das School-Gateway
  (nginx + LiteLLM) darf `x-ratelimit-*`/`retry-after` strippen oder ersetzen —
  wir haben sie nie gesehen; der Body war immer da. Ein blanker `429`, ein
  `rate_limit_exceeded` oder ein unparsebarer Body bleiben `TRANSIENT`.
- **`SttFailure.BUDGET`** ist die fünfte Klasse. 4xx mit Budget-Body → BUDGET;
  401/403 bleiben AUTH (die spezifischere Tatsache, und Retry ist so oder so
  falsch); 5xx bleiben bei der Matrix (ein Gateway-Fehler ist ein
  Gateway-Fehler, auch wenn er den Provider-Body weiterreicht).
- **Versuche pro Klasse (`SttRequestRunner.attemptsFor`):** TRANSIENT 3
  (`MAX_ATTEMPTS` bleibt die harte Obergrenze), BUDGET 1, AUTH 1, TERMINAL 1,
  **PROTOCOL 1 — das ersetzt #183s „wiederholt byte-identisch genau einmal".**
  Begründung: PROTOCOL ist in den Gerätelogs nie aufgetreten, und der manuelle
  Retry STT (#188) deckt den Einzelfall ab. BUDGET 1 ist die eigentliche
  Korrektur: eine Kostengrenze kann sich zwischen zwei Versuchen nicht ändern.
- **Ein Budget-Request pro Session:** das #183-AUTH-Latch heißt jetzt
  `FragmentTranscriber.blockedFailure` und deckt AUTH + BUDGET. Jedes spätere
  Fragment wird ohne Request als Fehler verbucht (Klasse bleibt im Record, das
  komponierte Upload landet weiter im Journal, PENDING), `resetFailures()`
  löscht das Latch — der Moment, in dem der Nutzer aufgeladen oder den Token
  getauscht hat. Eine Diktatrunde von 40 Fragmenten kostet gegen eine leere Kasse
  also genau EINEN Request.
- **Der Provider-Satz erreicht den Nutzer:** die Exception trägt
  `"HTTP 429 — ExceededBudget: … Spend=5.0006, Budget=5.0"` (Status/Retry-After
  zuerst, Provider-Satz angehängt, nur wenn vorhanden), `SttFailureText` mappt
  BUDGET auf „Provider budget exhausted — top up or switch provider", die IME
  stellt den Hinweis VOR den technischen Detailtext. Kein neuer Event, kein
  neuer Screen.
- **Die LLM-Strecke ist dieselbe Strecke:** beide Chat-Aufrufe parsen denselben
  Envelope und werfen denselben Träger (`SttRequestException`, `failure =
  BUDGET`), Nachricht `"LLM post-processing failed: HTTP 429 — …"`. Der RAW-Text
  wird unverändert gespeichert (`PipelineOutcome.rawText`) — ein gescheiterter
  Polish kostet nie die Diktatur.
- **Diagnose in den BESTEHENDEN Behältern:** der `stt-latency`-Record bekommt
  `headers` (nur `retry-after` + `x-ratelimit-*`, lowercased, 64 Zeichen pro
  Wert) und `providerError` (type/code/message) — auch auf dem Erfolgspfad, denn
  „wie viel Budget war um 14:12 übrig" ist nur so beantwortbar. Beide Felder
  sind `null` und damit omittiert, wenn nichts da ist: pre-#75-Records behalten
  ihre Form. `session.json` bekommt pro Fragment `rateLimit` (httpCode, type,
  message, headers), geschrieben wo `sttError` schon geschrieben wird — kein
  neues File, kein neuer Stream, retention-gated wie alles dort.
  **Die Gate-Skip-Zeile aus dem Issue-Text** (ein ohne Request verbuchtes
  Fragment zusätzlich als Skip-Record loggen) ist NICHT gebaut: das Fragment
  steht mit Klasse und Text im Journal und im Bundle, ein zweiter Datensatz
  hätte keinen Leser.
- **Kein Usage-UI.** Die ursprüngliche Idee (Settings-Panel über
  `x-ratelimit-*`) ist gestrichen: die Header waren nie das Problem, und ein
  Panel wäre ein zweiter Ort für Daten, die die Diagnose schon hat.

## 2026-11-20: #183 — Fehlerklasse vor Aktion; der PROTOCOL-Retry wiederholt BYTE-IDENTISCH

Die #116-Retry-Politik kannte nur „transient" und „alles andere" — damit waren
ein toter Token und eine kaputte 2xx-Antwort dieselbe Kategorie. Die Klasse wird
jetzt VOR jeder Aktion bestimmt (`SttFailureClassifier`, pure) und von drei
Stellen benutzt: Runner (Versuche), Journal (Grund), UI (Aktion).

- **AUTH hält die SESSION an, nicht nur das Fragment:** 401/403 setzen im
  `FragmentTranscriber` ein Latch; jedes spätere Fragment wird ohne Request als
  Fehler verbucht. Der School-Gateway-Vorfall (fünf 401er in einer Session) ist
  der Beleg: weiterzuhämmern kostet nur Provider-Quota und Zeit. Das Latch wird
  von `resetFailures()` gelöscht — genau der Moment, in dem der Nutzer den Token
  geändert und erneut gesendet hat (der Token wird pro Fragment ohnehin live aus
  `SettingsStore` gelesen, also braucht „resume after a token change" KEINEN
  neuen Mechanismus, nur das Latch).
- **PROTOCOL wiederholt byte-identisch, ohne Formänderung:** Der einzige
  optionale Request-Teil ist der Whisper-Prompt, und ein Prompt-Problem antwortet
  mit 4xx, nicht mit einem kaputten 2xx. Ein Envelope-Fehler kommt von
  Truncation oder einem Proxy — der Retry ist die Wiederholung, nicht eine
  andere Form. Das hält `Result<SttResponse>` unverändert (kein neues
  `promptDropped`-Signal in den Transcriber) und ist durch #184 idempotent.
  Abweichung vom Issue-Text („conservative request shape") bewusst und hier
  festgehalten.
- **`Retry-After` wird respektiert, indem man AUFGIBT statt früher zu wiederholen:**
  Wer 60 s verlangt und nach 2 s erneut anklopft, verletzt genau die Empfehlung,
  die er befolgen will. Deshalb: 10 s pro Wartezeit, 20 s pro Request — darüber
  hinaus scheitert der Request (TRANSIENT, wiederholbar beim nächsten Send),
  statt den Worker zu blockieren. `Retry-After` kann Sekunden ODER ein
  HTTP-Datum sein (`RetryAfter`, mit injizierbarer Uhr getestet).
- **Die Klasse reist mit:** `SttRequestException.failure` (Default `TERMINAL` —
  ein unklassifizierter Fehler ist nicht wiederholungswürdig), im Journal als
  `failureClass` + `failureHttpCode`, und durch den Controller bis in die IME,
  die AUTH/PROTOCOL in einen Hinweis übersetzt (`SttFailureText`) und den
  technischen Detailtext ANHÄNGT statt ihn zu ersetzen (CONVENTIONS: der Nutzer
  muss sehen, was kaputt ist).
- **Kein Wire-Feld für die Klasse:** #184s `@PartMap` bleibt unverändert. Ein
  Server, der eine Fehlerklasse vom Client braucht, existiert nicht; das Journal
  ist der Ort, an dem die Klasse gebraucht wird (#185).
- **Redaction statt Verzicht auf den Body:** `LogRedaction` (Bearer, `sk-`/`gsk_`
  artige Keys) läuft im `ResponseLoggerInterceptor` VOR dem Log. Der Body bleibt
  forensisch (10-MB-Cap unverändert), nur Credentials werden ersetzt — die
  Token-Zeichenklasse schließt `"`/`'` aus, sonst frisst sie das schließende
  Quote und macht das JSON ungültig.

## 2026-11-20: #184 — Identität als EIN `@PartMap`, leerer Map = unverändertes Wire-Format

Ein Retry darf nie ein neues Fragment vortäuschen; die Fragment-Identität muss
auf die Leitung. Die Form ist bewusst so gewählt, dass **kein bestehender
Server** etwas merken kann.

- **Ein `@PartMap` statt drei `@Part`-Parametern:** `session_id`,
  `fragment_index`, `attempt` sind gewöhnliche multipart-Felder in EINER Map.
  Neue Felder (z. B. #183s Fehlerklasse, #186s Offline-Zustand) erweitern nur
  die Map, nicht die Retrofit-Signatur — und damit nicht die ~36 Mock-Stellen
  in den Tests.
- **Leerer Map = byte-identisch zu vorher:** der Legacy-Chunk-Pfad
  (`TranscriptionPipeline.runStt`) und der Analyzer übergeben keine Identität,
  senden also exakt das alte Request. Das ist der Grund, `sessionId == null`
  als alles-oder-nichts zu behandeln statt „attempt immer mitschicken".
- **`fragment_index` ist 0-basiert** und identisch mit dem Schlüssel in
  `session.json` und im Journal; die Log-Spalte `chunk` bleibt die 1-basierte
  Menschen-Nummerierung (#115). Zwei Zahlen, zwei Bedeutungen — bewusst, weil
  ein Umnummerieren des Wire-Feldes die Idempotenz-Key-Zusage bräche.
- **Der Client ist die eigentliche Garantie, nicht der Server:** `offer()`
  und der Worker sortieren pro Index, `transcripts.putIfAbsent` ist die zweite
  Linie gegen einen zweiten Erfolg. Ein verlorenes Ack + Retry kann serverseitig
  doppelt transkribieren — genau dafür existiert der Key
  `session_id:fragment_index`, den ein Gateway später adoptieren kann.
- **Keine neuen `session.json`-Felder:** die im Issue geforderten Identifikatoren
  sind dort bereits vorhanden — `SessionBundle.sessionId` ist exakt die
  gesendete `session_id` (derselbe Controller-Wert) und der Fragment-Record ist
  per `fragment_index` verschlüsselt. Sie zu duplizieren wäre Redundanz ohne
  Prüfwert; das Journal trägt die Identität ohnehin im Verzeichnis- und
  Dateinamen.

## 2026-10-07: Source-language clause carries Whisper language probability; ISO codes mapped via LanguageMapper (#105, CLOSED)
- **Choice**: `SttResponse`/`SttResult` gained `languageProbability: Float?` (Gson `@SerializedName("language_probability")`). The clause has three branches: probability present → `"The Whisper service detected the recognized language as <Name> with a probability of X percent."` (owner wording variant B, `Math.round(prob * 100)`); absent (e.g. GROQ) → the old `"The STT service transcribed audio spoken in <Name>."`; language null/blank/`"unknown"` → clause dropped (unchanged). No confidence threshold, no preferred-language setting — the owner decided the LLM should see the number and judge for itself. New `config/LanguageMapper` (complete 100-entry Whisper `LANGUAGES` table) maps ISO codes (`"de"`) and full names (`"German"`, case-insensitive) to display names with a capitalized-words fallback; both clause branches use it (replacing the private `capitalizeWords`).
- **Reason**: #105 (Spanish misdetected as Icelandic during the #82 verification). Web research established `avg_logprob` is **transcription** confidence (mean log-prob of the segment's tokens, `exp()` ≈ geometric mean; faster-whisper fails segments below −1.0) — **not** language confidence — so it was deliberately NOT used as a language-probability substitute. GROQ returns no `language_probability` (only `language` + segment fields); faster-whisper gateways do. Providers also disagree on the `language` format (GROQ: `"German"`, local faster-whisper: `"de"` — observed on-device as `"as De"` before the mapper).
- **Verification**: `TranscriptionPipelineTest` +2 (name+87 %, ISO-code→German+99 %), `LanguageMapperTest` +7; `./gradlew test` green; **on-device verified** (OnePlus, local LAN server returned `"de"`/0.9995 → clause reads `"as German with a probability of 100 percent"` in `llm-prompt.json`). Commits `beed16d` (+ mapper follow-up), pushed.
- **Out of scope**: the threshold/preferred-language ideas from the #105 body — explicitly deferred by owner decision.

## 2026-10-07: Default system prompt replaced with the owner's crafted version (#112, OPEN)
- **Choice**: `app/src/main/assets/prompts.json` `system` now ships the owner's on-device-crafted prompt (captured from `llm-prompt.json` 2026-10-07 19:44): "smartphone user" framing (typo "smarrphone" corrected), "(whisper)" engine note, "and sparingly" markdown, "Your output may equal the input.", question/exclamation punctuation rule, "one or blank" hallucination tail. `{{source_language_clause}}` / `{{target_language_clause}}` placeholders restored (the log held resolved text). Commit `64f3eec`, pushed.
- **Reason**: Owner request — the crafted prompt becomes the "Restore Default Prompts" baseline.
- **Open**: owner on-device tap of "Restore Default Prompts" to confirm.

## 2026-10-07: Cleartext HTTP allowed for self-hosted LAN endpoints (#114, CLOSED)
- **Choice**: `res/xml/network_security_config.xml` with `<base-config cleartextTrafficPermitted="true"/>`, wired via `android:networkSecurityConfig` in the manifest. Permissive base (not per-domain exceptions) — users point the app at arbitrary self-hosted OpenAI-compatible endpoints (HTTP LAN servers, HTTPS cloud).
- **Reason**: Provider validation against `http://10.8.0.16:11437/v1/` failed with `CLEARTEXT communication ... not permitted by network security policy` (Android 9+ default). Commit `686b653`, pushed.
- **Verification**: STT responses flowed through the LAN server immediately after install (22:44/23:42 logs).

## 2026-05-29: HTTP error detail in token validation
- **Choice**: Parse OpenAI-compatible error body JSON (`error.message`) and display it in `TextInputLayout.error` (e.g. "HTTP 401: Invalid API key")
- **Reason**: Users need actionable information when token validation fails. The generic "Invalid token or no access" gives no clue whether the token, provider URL, or network is wrong.
- **Considered**: Custom error dialog, dedicated error view in layout
- **Tradeoff**: Error messages from providers vary in format — the `error.message` extraction handles OpenAI/GROQ/OpenRouter. Non-standard providers may still show generic message.

## 2026-05-30: Provider settings redesign — visible URL fields + expanded presets
- **Choice**: Add editable API URL fields to both STT and LLM sections, auto-fill from preset map on provider selection, expand LLM presets from 5 to 15 providers
- **Reason**: Users couldn't see what endpoint they connected to. "Custom provider…" silently fell back to OpenAI's URL. OpenRouter and other providers need visible/editable URLs. The preset list was too small.
- **Considered**: URL field for LLM only (decided STT should match), per-provider modal for URL entry (over-engineered)
- **Tradeoff**: More scrollable fields in the layout. URL field is always editable even for known providers — could break known-good configs. Mitigated by auto-fill on provider select.

## 2026-05-30: Split LLM validation — Fetch Models vs Test Token
- **Choice**: Replace single Validate & Fetch with two buttons: [Fetch Models] and [Test Token]. Test Token sends a minimal chat request (max_tokens=1, "ping") with the selected model.
- **Reason**: OpenRouter's /v1/models endpoint is unauthenticated — returns models for any token, even invalid ones. A separate chat call catches 401/403 after models load. Keeps token validation reliable across providers.
- **Considered**: Single button doing both calls sequentially (confusing UX — "which failed?"), skipping token verification entirely (unsafe)
- **Tradeoff**: Two buttons instead of one in LLM section. Test Token requires model selection first. Costs 1 token per test.

## 2026-05-30: Searchable model dropdowns
- **Choice**: Change model AutoCompleteTextView from inputType=none to inputType=text with completionThreshold=1
- **Reason**: OpenRouter offers 600+ models — scrolling is impractical. Built-in ArrayAdapter filtering handles this with zero code changes beyond the XML attribute.
- **Considered**: Custom SearchView + ListView, third-party searchable spinner library
- **Tradeoff**: Slightly different UX from exposed dropdown behavior. Keyboard shows on tap before the dropdown. Built-in behavior is free and proven.
- **Choice**: Upgrade AGP from 8.2.2 to 8.7.3 and Gradle wrapper from 8.2 to 8.9
- **Reason**: Kotlin 2.1.20 generates metadata that R8 bundled with AGP 8.2.2 cannot parse (warning spam in release builds). Per Android docs, Kotlin 2.1 requires AGP ≥ 8.6 and R8 ≥ 8.6.17. AGP 8.7.3 is a stable patch release providing R8 8.7.x compatible with Kotlin 2.1 metadata. Gradle 8.9 is the minimum required by AGP 8.7.x.
- **Considered**: Downgrading Kotlin to 1.9.24 (simpler, zero side effects), upgrading to AGP 9.x (requires JDK 21, more API changes)
- **Tradeoff**: Requires Gradle wrapper upgrade and download of new distribution on first build. JDK 17 stays supported (AGP 9.x would require JDK 21).

## 2026-05-30: Sync Call for model listing (R8 workaround)
- **Choice**: Add non-suspend `listModelsSync` returning `Call<ModelsResponse>` to API services, use `Call.execute()` in `fetchSttModels`/`fetchLlmModels` instead of creating new Retrofit instances with suspend functions
- **Reason**: R8 full mode strips generic type info from the `Continuation<? super Response<ModelsResponse>>` parameter in Kotlin suspend functions. Retrofit's `HttpServiceMethod.parseAnnotations` casts this to `ParameterizedType`, causing `ClassCastException: java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType` in release builds. Using a non-suspend `Call<ModelsResponse>` avoids the `Continuation` type resolution path entirely
- **Considered**: Adding ProGuard keep rules (`-keep class kotlin.coroutines.Continuation`), OkHttp direct call
- **Tradeoff**: `Call.execute()` blocks the thread (fine — already on `Dispatchers.IO`). Slightly more verbose than suspend single-line call. Keeps the shared `OkHttpClient` with connection pooling via `PolishedRecognitionApp.getSttApi/getChatApi`

## 2026-05-30: Sync Call for transcribeAudio/chat (R8 workaround)
- **Choice**: Same sync `Call<T>` pattern extended to `transcribeAudioSync` and `chatSync` in the pipeline
- **Reason**: Same R8 `Continuation<? super Response<T>>` issue affects all Retrofit suspend functions, not just listModels. Release build transcription failed silently.
- **Considered**: ProGuard keep rules (risky, platform-dependent)
- **Tradeoff**: Pipeline code now blocks IO thread with `Call.execute()` — fine since `Dispatchers.IO` is designed for blocking I/O

## 2026-05-30: `BaseAdapter` + `Filterable` for model dropdowns (replaces custom `ArrayAdapter` filter)
- **Choice**: Replaced anonymous `ArrayAdapter` with custom `Filter` in `updateModelDropdown()` with a `ModelFilterAdapter` that extends `BaseAdapter` and implements `Filterable`, directly owning its display list
- **Reason**: `ArrayAdapter.clear()` and `addAll()` silently operate on the internal `mOriginalValues` list (not `mObjects`) when `mOriginalValues` is non-null. The custom `publishResults()` never actually updated the displayed data. Additionally, `setNotifyOnChange(false)` (added to prevent `ConcurrentModificationException`) was never reset to `true`, leaving the adapter in a permanently broken state that caused crashes on Backspace. `BaseAdapter` directly owns its display list — filter results are assigned as a new list copy, avoiding all `ArrayAdapter` internal state corruption.
- **Considered**: Fixing the `ArrayAdapter` approach by directly setting `mObjects` via reflection (fragile), switching back to standard `startsWith` filter (loses `contains` matching)
- **Tradeoff**: `BaseAdapter` requires manual `getView()`, `getCount()`, `getItem()` implementation (~15 extra lines vs. `ArrayAdapter`). Zero reflection, zero side effects.

## 2026-05-30: Error toasts for transcription failures
- **Choice**: Show `Toast.makeText(this, errorMessage, Toast.LENGTH_LONG)` in both `PolishedRecognitionService` and `VoiceRecognitionActivity` error paths (else-branch and catch-branch)
- **Reason**: Users get no feedback when transcription fails — the keyboard just shows an error code or nothing. Detailed error messages (HTTP status, exception text) help users diagnose issues (wrong token, network down, bad model)
- **Considered**: Dialog, snackbar (both require activity context which is unavailable in RecognitionService)
- **Tradeoff**: Toast might show behind keyboard UI. Counter: it's visible in the notification shade and toasts work from Service context

## 2026-06-04: Auto-select default models on fetch
- **Choice**: Add `default_model` to `ProviderPreset` and auto-select it during `SettingsActivity` model fetching if the dropdown is currently empty and the default is in the fetched list.
- **Reason**: Ensures the fastest models (e.g. `whisper-large-v3-turbo` for Groq) are automatically picked.
- **Considered**: Hard-coded UI defaults
- **Tradeoff**: JSON preset config requires one extra property mapping.

## 2026-06-12: Backport zazentimer translate.ts improvements
- **Choice**: Port all improvements from `zazentimer/prisma/translate.ts` to `polished-recognition/translation-engine/translate.ts`
- **Reason**: zazentimer's version had proper session cleanup (`terminateSession` + `try/finally`), DB consistency validation at startup, external JSON for model providers, configurable settled threshold, dynamic `--help`, and `MIN_VOTE_PROFICIENCY` from db.ts. polished-recognition's was a stale fork with hardcoded providers, leaked sessions, and a "ZazenTimer" app_name bug.
- **Considered**: Incrementally fixing each difference (slower, more error-prone)
- **Tradeoff**: Changes also affect `db.ts` and `opencode_client.ts`. `export.ts` and `voting_api.tsx` still need updating (#25 not fully done).

## 2026-06-12: Score-based settlement with configurable threshold
- **Choice**: `getSettledStrings(langId, threshold = SETTLED_SCORE_THRESHOLD)` uses `e.score` instead of `e.modelCount`, filters by `score >= threshold` (default 7)
- **Reason**: A single high-proficiency model (level 5) settles a string only with score >= 7 (requires at least two models). Score rewards both model agreement AND individual quality. `modelCount >= 3` was too coarse.
- **Considered**: Keeping modelCount-based (user wanted score-based per #25)
- **Tradeoff**: Threshold 7 means fewer strings auto-settle. More translation work per language but higher confidence in results.

## 2026-06-12: External JSON for model providers
- **Choice**: `llmmodels_master.json` now contains `{ name, providers[] }` objects. `translate.ts` imports this JSON instead of hardcoding `MODEL_PROVIDERS_RAW`.
- **Reason**: Single source of truth for model→provider mapping. Same JSON file is also used by `seed.ts` to populate the DB. Keeps model config synchronized between translation orchestrator and voting dashboard.
- **Considered**: Keeping hardcoded map (simpler but out of sync with DB)
- **Tradeoff**: JSON import requires Deno `with { type: "json" }` syntax. The JSON must exist at build time.

## 2026-06-16: Complete score-based settlement migration (#25)
- **Choice**: Unified all settlement logic to use proficiency-weighted scores instead of model counts. Added `TRANSLATION_SCORE_THRESHOLD=3` (for export eligibility) alongside existing `SETTLED_SCORE_THRESHOLD=7` (for full settlement). Introduced `byScoreThenCount` helper: sorts by cumulative score DESC first, then modelCount DESC as tiebreaker. Removed `level: { gte: 2 }` filter from `getEvaluation()`, `getTranslation()`, and `getStringSettlement()` DB queries — proficiency filtering now lives exclusively in the `translate.ts` orchestration layer (`MIN_VOTE_PROFICIENCY`).
- **Reason**: The DB layer should return all data; policy decisions about which models qualify belong in the orchestration layer. `byScoreThenCount` (score-first) replaces the old count-first sort because score rewards both agreement AND individual model quality — a single level-5 model agreeing with a level-2 model (score=7) should outrank three level-1 models agreeing (score=3). Zazentimer pioneered this approach; polished-recognition now matches.
- **Considered**: Keeping `level: { gte: 2 }` in DB queries (redundant with `translate.ts` filtering; couples DB layer to proficiency policy). Keeping count-first sort (too coarse — treats all models equally regardless of quality).
- **Tradeoff**: If votes exist from models without proficiency records (edge case), they are silently excluded by the `if (!level) continue` guard in scoring loops.

## 2026-06-16: Pipeline stage callback for status messages (#32)
- **Choice**: Changed `TranscriptionStage` from an enum to a sealed class: `object RequestingStt` (no data) and `data class RequestingLlm(val wordCount: Int)`. Added optional `onStageChange: ((TranscriptionStage) -> Unit)? = null` callback parameter to `TranscriptionPipeline.transcribe()`. The activity passes a callback that updates the bottom `elapsed_text` via `runOnUiThread`.
- **Reason**: The pipeline was a single suspend function with no stage visibility. The UI could only show "Processing…" for the entire duration. Users had no feedback during potentially long STT/LLM calls. The LLM message includes the Whisper transcription word count (e.g., "~ 123 words") so users can gauge expected processing time. Sealed class chosen over enum so `RequestingLlm` can carry the word count computed in the pipeline.
- **Considered**: Splitting `transcribe()` into two public methods called sequentially from the activity (changes the API more drastically; harder to maintain atomic error handling). Using a Flow/Channel (overkill for 2 stage transitions). Using `data object` for `RequestingStt` (not supported by the project's Kotlin version; plain `object` suffices).
- **Tradeoff**: The callback runs on the IO thread (inside `withContext`); the activity must marshal UI updates via `runOnUiThread`. The `null` default ensures existing callers (tests) are unaffected.

## 2026-06-26: Single editable system prompt, automatic user message (#37)
- **Choice**: Collapse three editable prompts (system, user template, translate) to two: a single **System Prompt** that absorbs all behavioral + task instructions, and a renamed **Target Language Prompt**. The user message becomes an automatic, non-editable `{{text}}` carrier (no longer surfaced in the UI). `{{source_language}}` and `{{translate_prompt}}` now resolve into the **system** message instead of the user message.
- **Reason**: The old user template conflated task instructions with data delivery, creating two competing instruction surfaces. Moving all instructions to the system role preserves the system-level precedence/injection-safety boundary (important because arbitrary spoken content flows through) while exposing only one editable instruction field. The transcribed text as a pure user message gives a clean sandbox.
- **Considered**: Option A (eliminate system role, one user message — weakest injection boundary); Option C (keep two fields, just tidy roles — didn't reduce field count).
- **Tradeoff**: The source-language sentence wording lives in code (`TranscriptionPipeline`), not in the editable prompt — users can move/delete the `{{source_language}}` token but not reword its sentence (consistent with how `{{translate_prompt}}` already worked).

## 2026-06-26: Drop source-language sentence when Whisper is unsure (#37)
- **Choice**: `{{source_language}}` resolves to the full sentence `"The STT service transcribed audio spoken in <Name>."` when Whisper returns a language, or to an **empty string** (whole sentence dropped) when `whisper.language` is null/blank or equals `"unknown"` (case-insensitive). `capitalizeWords` only runs when a language is known, so the stray `"unknown"` literal never reaches the prompt anymore.
- **Reason**: Whisper frequently fails to identify the language; the old code then produced *"…transcribed audio spoken in unknown."* — useless noise. Treating `{{source_language}}` as a clause (sentence-or-empty), placed on its own line like `{{translate_prompt}}`, makes removal 100% robust without parsing free-text sentence boundaries in an editable prompt.
- **Considered**: Bare-name substitution + line-based removal (requires the sentence on its own line; breaks if user merges lines); sentence-parse heuristic (fragile).
- **Tradeoff**: `PromptStore.get(KEY_USER)` bypasses SharedPreferences and always returns the asset default (`{{text}}`), so stale stored user-template values from upgraders are ignored — no migration needed.

## 2026-06-27: Rename prompt template variables for clarity (#39)
- **Choice**: Renamed the two system-prompt placeholders to convey their optional/clause behavior: `{{source_language}}` -> `{{optional_source_language_info}}` and `{{translate_prompt}}` -> `{{optional_target_language_wish}}`. Renamed the Target Language Prompt UI identifiers from `translate_prompt` to `target_language_prompt` (string name, two view ids, Kotlin field) to match the field's purpose.
- **Reason**: The old names hid that both resolve to optional, whole-clause-or-empty values dropped when not applicable. The `optional_` prefix makes the drop-on-empty contract visible at the call site and in the editable prompt. The UI ids still carried the legacy `translate_prompt` name even though the field had already been renamed "Target Language Prompt" (#37).
- **Considered**: Also renaming `KEY_TRANSLATE` / JSON key `"translate"` — rejected (changes the SharedPreferences key on existing installs, needs a migration for no functional gain). Copying the placeholder name verbatim into the UI ids (`optional_target_language_wish`) — rejected; the ids describe the editable field, not the system-prompt placeholder, so `target_language_prompt` is semantically correct.
- **Tradeoff**: Historical DECISIONS/HISTORY entries still mention the old placeholder names; left intact as a historical record rather than rewritten.

## 2026-06-27: Fail-fast prompt default loading (#39)
- **Choice**: Removed `PromptStore.loadDefaults`'s hardcoded string fallback. Asset loading (`prompts.json`) now fails fast on a missing/corrupt file (`error("prompts.json parsed to null")` for the null-return case; exceptions propagate) instead of silently substituting stale built-in strings.
- **Reason**: The fallback was a second source of truth that had already drifted from `prompts.json` (stale wording). It also gave incomplete protection: `gson.fromJson` returning `null` slipped past the `catch` and NPE'd later. A single source of truth removes the drift class of bug entirely. The unit test (`PromptStoreTest`) loads the real merged asset via Robolectric, so a malformed `prompts.json` fails `./gradlew test` (and the pre-push hook) before it can ship.
- **Considered**: Keep the fallback but sync it to `prompts.json` — rejected (reintroduces dual-source drift the next time the prompt is edited). Keep it for graceful degradation — rejected (a stale, untested default silently degrading is worse than a loud, debuggable failure).
- **Tradeoff**: A missing/corrupt `prompts.json` now crashes the lazy `PromptStore` construction (first transcription) instead of limping on. Mitigated by the test/pre-push guardrail and by the file being a committed, unfiltered asset.

## 2026-06-27: Rename prompt placeholders to symmetric *_clause (#39, 2nd round)
- **Choice**: Renamed the two system-prompt placeholders to a parallel `*_clause` pattern: `{{optional_source_language_info}}` -> `{{source_language_clause}}` and `{{optional_target_language_wish}}` -> `{{target_language_clause}}`. Renamed internals: `PromptStore.translatePromptTemplate` -> `targetLanguageClauseTemplate`, `TranscriptionPipeline` local `translatePrompt` -> `targetLanguageClause`. Renamed the Target Language Prompt UI identifiers `target_language_prompt` -> `target_language_clause` (string key + value "Target Language Clause", 2 view ids, Kotlin field `targetLanguageClauseField`).
- **Reason**: "clause" directly expresses that both resolve to an optional, whole-clause-or-empty value dropped when not applicable — more intuitive than the earlier `optional_*` prefix. Symmetric `*_clause` naming keeps the two placeholders parallel. The internals and UI ids still carried legacy "translate"/"prompt" wording that no longer matched the field's role.
- **Considered**: Keep the `optional_*` naming from the first #39 round — rejected; "clause" reads better and the user wanted the pair parallel. Rename `KEY_TRANSLATE` / JSON key `"translate"` — rejected again (SharedPreferences key change on existing installs).
- **Tradeoff**: Historical DECISIONS/HISTORY/STATE entries still mention the intermediate `optional_*` (and older `source_language`/`translate_prompt`) names; left intact as a record.

## 2026-06-27: Log exact LLM request as pretty JSON, skip raw mode (#40)
- **Choice**: `PromptLogger` now writes the verbatim `ChatRequest` sent to the LLM, pretty-printed (2-space indent, `GsonBuilder().setPrettyPrinting()`), to `prompt.json` (+ rotating history `prompt_1.json` … `prompt_9.json`, `maxCount` 5 -> 9). The log call moved from before the raw-mode early-return to after the `ChatRequest` is built, so raw mode (which never calls the LLM) produces no log. `PromptLogger.log(content: String)` is now a dumb rotating writer; `TranscriptionPipeline` owns serialization. Legacy `prompt*.md` files are swept on construction.
- **Reason**: The old behavior logged a markdown rendering of the resolved system/user strings even in raw mode (a prompt never sent) — misleading. Logging the actual `ChatRequest` JSON gives an exact, debuggable record of what went to the provider; pretty-printing makes it human-readable.
- **Considered**: Keep logging in raw mode — rejected (it documents a request that never happens). Log compact JSON byte-identical to the wire body — rejected (less readable; the data is identical, only whitespace differs). Reuse Retrofit's converter for serialization — default Gson matches it, so a standalone `GsonBuilder()` is equivalent and simpler.
- **Tradeoff**: The log is no longer byte-identical to the wire body (pretty vs compact); the test guards this by serializing the same captured `ChatRequest` with the same pretty Gson. Rotation `maxCount` 5 -> 9 retains more history.

## 2026-06-27: Log STT + LLM responses verbatim; generalize logger (#41)
- **Choice**: Added a third/fourth log stream via an OkHttp application interceptor (`ResponseLoggerInterceptor`) on the shared client. It captures the raw response body (`peekBody`, 10 MB cap), pretty-prints it (re-parsed via `Gson().fromJson(..., JsonElement)`, all fields preserved; raw fallback if not JSON), and writes to `stt-response.json` (path `audio/transcriptions`) or `llm-response.json` (path `chat/completions`); other paths (e.g. `models`) are ignored. Logs error responses too. Renamed `prompt.json` -> `llm-prompt.json`. Generalized `PromptLogger` -> `RotatingJsonLogger` with `log(baseName, content)`; init also sweeps the renamed `prompt.json`/`prompt_*.json`.
- **Reason**: The response DTOs are partial (`SttResponse` = text+language; `ChatResponse` = choices[].message), so re-serializing them drops `segments`, `usage` (tokens), `finish_reason`, etc. Capturing the raw body is the only way to log responses verbatim. `chat/completions` is used only by real transcription (Test Token / Fetch Models hit `models`), so path routing is pollution-free.
- **Considered**: Re-serialize the parsed DTOs (the "easy" route) — rejected (lossy, not verbatim). Log raw compact bytes — rejected (less readable); pretty re-serialization preserves every field, only whitespace differs. Add MockWebServer for an end-to-end interceptor test — rejected to keep deps minimal; the routing + pretty helpers are pure and unit-tested, integration covered by the on-device check.
- **Tradeoff**: Response logs are pretty, so not byte-exact (whitespace normalized) but field-complete. `peekBody` is capped at 10 MB; a pathological extra-long STT response could truncate. `chain.request()` and OkHttp client are now lazy (reordered so the client can reference the logger).

## 2026-08-30: Default system prompt overhaul — context-first, trailing-hallucination strip, positive output clause (#49)
- **Choice**: Rewrote the default system prompt in `app/src/main/assets/prompts.json`. New order: (1) role+context first ("You post-process voice dictation for a keyboard: … your output is inserted directly into the app's text field"), (2) `{{source_language_clause}}`, (3) concrete cleanup bullets (grammar/spelling/punctuation, fillers/stutters/repetitions, structure+markdown), (4) preserve meaning/wording, (5) ambiguity carve-out ("choose the most plausible intended reading"), (6) don't answer dictation questions, (7) Whisper hallucination clause, (8) "Return only the cleaned-up transcription.", (9) `{{target_language_clause}}`.
- **Hallucination clause**: scoped to **trailing** hallucinations ("Whisper sometimes appends a hallucinated phrase such as 'Thank you.' or 'Thanks for watching.' after trailing silence"). The LLM now strips only the trailing part; empty string **only if the entire transcription** is a hallucination (old behavior nuked everything). "Subtitles by Amara" example dropped — never appears on Whisper (user observation).
- **Output clause**: purely positive ("Return only the cleaned-up transcription.") — the counter-example list ("no introductions, explanations, labels, quotes, or extra commentary") was dropped; the context-first first line already justifies why commentary is unwanted.
- **Reason**: the task context was buried and instructions were abstract; LLMs don't really fix semantic issues in practice, so instructions are now concrete about what cleanup actually does. User-approved via 3 questions: ambiguity clause kept but reworded concretely; strip-trailing-only hallucination handling; positive-only output clause.
- **Considered**: dropping the ambiguity clause entirely (user chose keep+reword); making it an explicit no-semantics prohibition; keeping a quotes ban in the output clause (LLMs sometimes wrap output in quotes — accepted risk).
- **Tradeoff**: devices with a **stored** system prompt override keep the old default until Settings → restore default (system prompt). Placeholders `{{source_language_clause}}`/`{{target_language_clause}}` and the pipeline-injected clauses are unchanged; `PromptStoreTest` + `TranscriptionPipelineTest` assertions updated (`doesNotContain("Amara")` regression guard added).

## 2026-08-30: Model dropdown UX — tap-to-all, noSuggestions, strict+early validation (#50)
- **Choice**: (1) `ModelFilterAdapter` holds a single `Filter` + synchronous `showAll()`; both model fields get a click hook (`adapter.showAll(); showDropDown()`) and a Toast when no models are fetched. `threshold=1` stays (search-as-you-type). (2) `stt_model`/`llm_model` → `inputType="text|textNoSuggestions"` + `importantForAutofill="no"`. (3) Focus-loss validation: if a fetched list exists and trimmed text isn't in it → `error` immediately (non-blocking); free text allowed when no list exists; save keeps the strict in-list rule with trimmed text.
- **Reason**: On-device #45 verification found: full list only reachable via clear+space hack (no tap hook; threshold=1 re-filters by current text); typing "." auto-inserted the last active model (IME autocorrect on plain `inputType=text`); invalid field state only flagged at save, blocking it. User-approved: strict list + early flag.
- **Considered**: threshold=Int.MAX_VALUE (kills search); blocking focus on invalid (hostile, traps the empty-list case); permissive free-text save (user chose strict).
- **Tradeoff**: No SettingsActivity unit tests — on-device verification is the real check. The period-insert root cause (IME suggestions) is a hypothesis pending on-device confirmation; `textNoSuggestions` removes the injector class regardless.

## 2026-08-30: Per-provider model cache with 6-week TTL (#50 round 2)
- **Choice**: Model lists cached **per endpoint URL** in `SettingsStore` (`stt_model_lists`/`llm_model_lists` JSON maps: `{url: {timestamp, models[]}}`), with a **6-week TTL** (user-specified): entries older than 42 days are pruned and read as empty — behaving exactly like "no models fetched" (free text allowed, tap shows "No models yet — validate / fetch first"). Fetch/validate writes under the current URL. Legacy `stt_model_list`/`llm_model_list` keys migrate under the saved provider's baseUrl with a fresh timestamp on first read. Save validates against a fresh store read for the current URL (not the adapter).
- **Reason**: Round-1 on-device testing showed a globally-stored list survives provider switches — validation then rejected models valid for the new provider (user's "QN3827B" scenario: every save red-flagged, nothing persisted, restart showed an empty greyed hint). Per-URL keying also makes manual URL edits and "Custom provider…" correct for free. User explicitly chose "cache list per provider, but only 6 week long" over wiping-on-switch and over plain per-provider caching.
- **Considered**: Wiping the list on provider switch (loses useful caches); unbounded per-provider caching (stale lists linger forever); keying by provider displayName (all "Custom provider…" entries would collide).
- **Tradeoff**: Same URL typo variants create separate cache entries; provider-URL changes in preset updates cause a one-time refetch; TTL expiry is silent (the tap-toast is the only discovery affordance).

## 2026-08-30: Model dropdown — exact-match→full-list + symmetric blank validation (#50 round 2)
- **Choice**: (1) `performFiltering` returns the **full list when the constraint exactly equals a list entry** (case-insensitive) — because the view's internal focus-triggered filter is async and lands *after* the click handler's `showAll()`, previously shrinking the list to the one matching entry (user: "I only get the model list when I enter a blank entry"). (2) Validation is now symmetric between focus loss and save: blank → "Select or enter a model" (save always blocked — a model is required); non-blank not in the cached list → "Select a model from the list". Hint "Model" → "Select or fetch a model" (greyed hint was mistaken for content).
- **Reason**: Deterministic full-list display without reimplementing the dropdown machinery; consistency (round 1 flagged non-blank invalid at focus loss but ignored blank, while save blocked both — user called out the inconsistency).
- **Considered**: Custom AutoCompleteTextView subclass suppressing the focus filter (more invasive); threshold=MAX + fully manual dropdown management (TextWatcher/suppression edge cases); allowing blank saves (user chose block — transcription would fail at runtime).
- **Tradeoff**: Typing a *complete* exact model ID to search shows all models instead of the single match (harmless — intermediate keystrokes still filter).

## 2026-08-30: Strip reasoning/thinking blocks from LLM content (#47)
- **Choice**: New pure `stripReasoning(content)` in `api/dto/StripReasoning.kt` removes the reasoning-tag pairs (opening tag `think` in angle brackets + closing tag, and the `<reasoning>` pair) case-insensitively; applied inside `ChatResponse.getContent()` (the single choke point covering pipeline + Settings ping).
- **Reason**: Reasoning models (DeepSeek-R1, Qwen3-QwQ) inline CoT in `content`; it was committed verbatim into the user's text field. Always-on, no setting.
- **Considered**: Stripping at call sites (duplicated logic); handling DeepSeek's separate `reasoning_content` field (already ignored — out of scope).
- **Tradeoff**: Unclosed `</think>` strips to end of string (may drop legit trailing text if the provider misbehaves); orphaned closing tags are dropped silently. 11 unit tests in `StripReasoningTest`.

## 2026-08-30: "None (no translation)" renamed to "None (polish only)" / "Polish only" (#48 follow-up)
- **Choice**: `CustomLanguages.NONE_TARGET_LANGUAGE` = "None (polish only)" (Settings dropdown); `VoiceRecognitionActivity.NONE_TARGET_LANGUAGE` = "Polish only" (IME quick-settings spinner + full-screen activity — kept short for the narrow bar). `target_language_hint` string updated.
- **Reason**: User request — "polish only" conveys that no-target-language mode still cleans up the transcription in its original language, unlike the old "no translation" wording.
- **Tradeoff**: None of the labels is ever persisted (`targetLanguage` stores `null` when None is selected in both the Settings save path and the IME spinner path), so the rename is purely cosmetic with zero config migration. Two separate constants exist because the IME bar needs a short label.


**Also stored elsewhere** (ambiguous placement, cross-referenced per #206):
- [audio-pipeline.md](audio-pipeline.md) — „2026-10-08: Parallel chunk uploads rejected by measurement; successor = live fragment transcription (#64 CLOSED, #116)"
- [ime-field.md](ime-field.md) — „2026-06-01: Dynamic language dropdown with persistent custom languages"
- [ime-field.md](ime-field.md) — „2026-08-30: Editable + deletable custom target languages (#48, #53)"
- [ime-field.md](ime-field.md) — „2026-08-31 (round 5): Clause wording hardened + gear divider; user-message purity locked (#56, #57)"
- [audio-pipeline.md](audio-pipeline.md) — „2026-10-08: #115 — stt-text.json evidence log alongside the interceptor's stt-response.json"
