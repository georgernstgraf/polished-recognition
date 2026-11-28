---
type: State
title: Project State — Settings
description: The settings overview, its four category screens and the draft/apply save model.
tags: [knowledge, state, settings]
generated: { by: opencode/knowledge-persistence, at: 2026-11-28T10:15:00Z }
sources: ["#219", "#212", "#153", "#214"]
verified: { by: opencode/knowledge-persistence, at: 2026-11-28T10:15:00Z }
status: stable
---

# Project State — Settings

The settings surface as its own theme since #212 (the overview + the four category
screens). Sections are verbatim, newest first.

## #219 — `cross_app_keep` (2026-11-28, implemented)

- **Die Checkbox:** Boolean „Keep dictations discarded by an app switch in the
  Workbench", Key `cross_app_keep`, Standard AN, in `AdvancedActivity` (die Advanced-/
  Debugging-Sektion), Draft/Apply wie `imeLivePreview`.
- **Der App-Label-Resolver:** `SettingsStore.appDisplayName(pkg, fallback)` —
  known-apps-Label (#100) → PackageManager-Label → Paketname; ein `null`-Paket
  (unlesbarer Pre-#192-Snapshot) liefert den Fallback `ime_leftover_unknown_app`.
  Pure Read (#200): der In-Memory-Cache antwortet, nichts wird persistiert.
- Details zum Verhalten: [decisions/session-lifecycle.md](../decisions/session-lifecycle.md)
  + [state/session-lifecycle.md](session-lifecycle.md) (#219).

## #214 addendum — token vault per provider URL (2026-11-26, implemented — OPEN until the owner's device check)

- **Owner's practice problem:** he switches provider URLs constantly (presets, experiments); the
  #101 model keeps ONE token per provider TYPE (`stt_token`/`llm_token`), so every URL change
  applied the PREVIOUS provider's token under the new URL — retyping each time.
- **Vault (`59ee546`):** one shared JSON map `canonicalUrl → token` inside
  `polished_recognition_secrets.xml` (STT + LLM types together — same endpoint = same credential).
  The #101 backup exclusions already cover that FILE, so no vault entry can reach the Google cloud
  backup or device transfer; `BackupRulesTest` needed no change.
- **Canonical key:** `SettingsStore.canonicalProviderUrl` — trim, scheme+host lowercased, trailing
  slashes stripped; the PATH stays verbatim (self-hosted endpoints can be case-sensitive).
  `…/v1` ≡ `…/v1/`.
- **Resolution order:** the per-type active token (last applied) still wins at request time — an
  older vault entry never overrides what was actually applied; the vault is the switch memory.
  Every apply files its token under its URL (cap 20, oldest dropped). Deliberately clearing a
  token drops the vault entry unless the OTHER type still points at the same endpoint. Pre-#214
  installs seed the vault from the token in effect on the first read.
- **UI rule (owner's explicit correction — the "untouched field" guard was übervorsichtig):** an
  UNVALIDATED token fiddled into the field is worthless. A URL change (focus loss or preset
  selection) ALWAYS refills the token field from the vault for the new endpoint — or empties it;
  the emptied case drafts `""` so the apply cannot fall back to the previous provider's token (the
  original leak). No validated-flag bookkeeping in the draft — the vault-write rides the normal
  apply path.
- **Tests:** +11 (`SettingsStoreSecretsTest` +6: canonicalization, per-URL round trip, vault in the
  secrets file only, legacy seeding, shared-endpoint clear; `ProvidersActivityTest` +2: refill on
  focus loss incl. trailing-slash return, preset refill). 843 `:app` tests forced green, lint 0,
  release on BOTH devices. Owner check: preset away and back — the token returns without retyping.

## #214 — editing a field clears its own stale error (2026-11-26, shipped)

- **Device finding (owner, while testing #153):** a correctly entered token still wore the red
  error ball; only leaving and re-entering Settings removed it.
- **Root cause:** commit `75fd935` (May 2026, „fix: clear token error on text change (#5)") had
  TextWatchers clear the token fields' errors on every text change. The #212 move consolidated
  all six watchers into `draftWatcher` — whose KDoc still claimed „clears the field error … (the
  #45 contract)" — but the body only wrote the draft. Android never auto-clears `setError`, so a
  stale failure message stuck while the user corrected the field; only the success paths
  (`error = null` after a successful fetch/test) or a screen rebuild removed it.
- **Fix (`0bef6f0`):** `draftWatcher(field, write)` clears `field.error = null` on every
  non-silenced text change BEFORE writing the draft — all six fields (2× token, 2× URL, 2×
  model dropdown) obey the documented contract again. Silenced programmatic loads (`loadFields`,
  fetch auto-fill) touch neither draft nor error. The model-dropdown errors are re-set by
  `validateModelField` on focus loss when still invalid.
- **Tests:** `ProvidersActivityTest` +3 — editing the token clears its stale error, OTHER fields'
  errors survive a sibling's edit, the silenced load clears nothing. Suite + lint green; release
  installed on BOTH devices; #214 closed the same day.

## #153 — reasoning-effort spinner, only when the provider speaks (2026-11-26, implemented — OPEN until the owner's device check)

- **Shape:** the LLM section of `ProvidersActivity` gains ONE conditional row behind the model dropdown
  (`llm_reasoning_effort`, `AutoCompleteTextView`, `inputType=none` + tap-to-open like the provider
  dropdowns, GONE by default). It exists **only** when the selected model's fetched capability list
  advertises effort levels (`capabilities.reasoning.effort` — observed verbatim on the opencode-go
  endpoint); a provider without the extension leaves the surface exactly as it was. No synthetic
  "default" entry, no app-side vocabulary: the spinner starts UNSELECTED and nothing is sent until
  the user picks — the provider's own default stays intact. Owner's explicit wish ("Default (nichts
  senden)" was rejected).
- **Wire:** `ModelEntry.capabilities.reasoning { supported, effort }` (nullable, additive, Gson
  tolerates the standard `/models` body); `ChatRequest.reasoningEffort`
  (`@SerializedName("reasoning_effort")`) — `null` serializes to NO member. All three LLM build
  sites pass the config's value through (`finishTranscription`, `polish`, the Providers token test).
  `LlmProviderConfig.reasoningEffort: String? = null` persists in the provider JSON.
- **Cache:** `CachedModels` gains `reasoningEfforts: Map<String, List<String>>?` (nullable — a
  pre-#153 cache JSON has no member and Gson bypasses Kotlin defaults, readers normalize via
  `orEmpty()`); `SettingsStore.setLlmModels(baseUrl, models, reasoningEfforts = emptyMap())` +
  `getLlmReasoningEfforts(baseUrl)` with the SAME TTL/prune behavior as `getLlmModels`.
- **Draft/apply:** `SettingsDraft.llmReasoningEffort` (in `isEmpty` + `clear()`); a pending level
  ALONE makes the draft non-empty (the provider entry is rewritten). `SettingsValidation.apply`
  normalizes defensively: the effort survives only when the configured model's cached list still
  advertises it — a stale value becomes `null` (nothing sent). No new check name.
- **Invariants (UI-enforced, `updateReasoningRow()`):** no levels → row GONE + pending choice
  dropped; model edit re-evaluates (`draftWatcher` on `llm_model`); URL/provider change reloads
  (`reloadModelDropdown` for the LLM dropdown); fetch success always re-evaluates (the model text
  may be unchanged while the capability cache is not); a stored choice survives a re-render only
  while the current model lists it.
- **Tests:** `ChatRequestJsonTest` (:core, new — effort member sent/omitted, capability parse +
  capability-less body), `SettingsStoreTest` +5 (roundtrip, default null, per-URL/model map, empty
  map on capability-less cache, TTL expiry), `TranscriptionPipelineTest` +3 (request carries /
  omits the effort, polish carries it), `ProvidersActivityTest` +4 (row hidden, exact levels +
  unselected, draft-only write, model switch clears), `SettingsValidationTest` +3 (isEmpty, persist,
  stale normalize). Full suite + `lintDebug` green (forced). Release installed on BOTH devices.
- **Preset wave (`0b03719`):** two LLM presets in `provider_presets.json` — **"OpenCode"**
  (`https://opencode.ai/inference/openai/v1/`, the Console pay-as-you-go endpoint) and
  **"OpenCode Go"** (`https://opencode.ai/inference/go/openai/v1/`), both `default_model
  glm-5.3-flash` (the owner's own agent model, 0-day retention). Researched 2026-11-26: the level
  vocabulary is per model across the whole catalog; OpenCode's own config syntax `opencode-go/
  glm-5.3-flash#high` confirms the `#variant` IS the reasoning effort. `ProviderPresetLoaderTest`
  pins both base URLs.
- **Session-header wave (`d39a853`) — OWNER DEVICE FINDING, docs drift corrected:** the Go
  endpoint answered **HTTP 400** "Request is missing x-opencode-session and cannot be routed
  efficiently" on the first Fetch Models with a real key — the web docs' "no longer necessary"
  note covers ANOTHER client's native header, not this one. Fix: `OpenCodeSessionInterceptor`
  (app `api/`) adds the stable per-install `x-opencode-session` UUID
  (`SettingsStore.opencodeSessionId`, created once, persisted) **exclusively to Go-URLs**
  (owner requirement: host `opencode.ai` AND path prefix `/inference/go/`) — the Console
  endpoint, other opencode.ai paths, every foreign host and the whole STT path stay
  byte-identical. Only `getChatApi` uses the derived `chatOkHttpClient`.
  `OpenCodeSessionInterceptorTest` pins: Go URL carries the header, Console URL on the same
  host does NOT, foreign host untouched, stale header overwritten. Suite + lint green;
  release installed on BOTH devices.
- **Owner check:** open a Provider setup whose LLM endpoint is opencode-go → Fetch Models → pick a
  reasoning model (e.g. `glm-5.3`): the spinner must appear with exactly `low / high / max`, stay
  empty until touched, and the polish must still work; the STT section must be unchanged. Preset
  path: pick the "OpenCode Go" preset → URL + default model land in the fields → Fetch Models —
  **which must now succeed past the HTTP 400.**


## #212 — Settings becomes an overview with four category screens (2026-11-26, CLOSED — owner verdict passed on the device)

- **Shape:** `SettingsActivity` is the OVERVIEW — target-language spinner (+ long-press
  add/edit + `manage_custom_languages` dialog, unchanged and WRITE-THROUGH, the IME
  spinner shares the field) on top, then four category buttons in the owner-agreed order
  (1 Providers & Tokens, 2 Manage Prompts, 3 Output Control, 4 Advanced / Debugging),
  `set_recognition_service` + About at the bottom, **no Save buttons anywhere**.
- **New screens** (plain activities, `Theme.PolishedRecognition.Plain`, `exported="false"`):
  `ui/ProvidersActivity.kt` (STT + LLM provider config incl. model selection, presets,
  validate/fetch/test, eye toggles — live behaviors verbatim), `ui/PromptsActivity.kt`
  (system prompt + target-language clause, resets stay immediate and clear their draft
  entry), `ui/OutputControlActivity.kt` (wrap width + presets + per-app section),
  `ui/AdvancedActivity.kt` (slice/search auto+lock, FIFO, OGG compression, live-preview
  kill switch, evidence-retention slider). Layouts `activity_{providers,prompts,
  output_control,advanced}.xml`; the old sections moved verbatim with the same view ids.
- **Save model:** `ui/SettingsDraft.kt` (process-wide singleton, pending edits only,
  unset = stored value) + `ui/SettingsValidation.kt` (the old `saveAndClose()` checks
  extracted verbatim — same 9 check names, same order — plus `apply()`). Sub-screen Back
  writes ONLY the draft (no toast, no prefs). Back on the overview = leaving Settings:
  validate → success applies the draft, toast "Settings saved", finish; failure toasts
  the first failed check (`settings-save-aborted` log line per failed check), finishes
  anyway, and the failing entries keep their stored value while everything else applies.
- **Back mechanics:** both platform paths route into `SettingsActivity.handleBackExit()`
  (API ≥ 33 `OnBackInvokedDispatcher` callback + classic `onBackPressed()` override,
  `backExitHandled` guard makes double delivery a no-op; the manifest does NOT set
  `enableOnBackInvokedCallback`, so the classic path is the live one — inline
  `@Suppress("GestureBackNavigation")` with the reason documented).
- **Draft edge cases (deliberate, tested):** empty draft → "Settings saved" WITHOUT
  validation (backing out of untouched Settings must not abort on an unconfigured
  provider); `apply()` writes a provider entry only when the draft holds a pending
  provider change (an empty draft never materialises an empty config over `null`);
  unchecking slice/search auto keeps the resolved value in the draft so back-exit
  validates the displayed value, not an empty manual field; per-app width raw edits
  survive sub-screen hops (`pendingAppWrap` redisplayed).
- **Polish wave after the first device verdicts (4 commits, all owner-approved):**
  `3c24afc` — H1 screen title "Polished Recognition Settings" + the language-section
  heading became **LLM Control** (the reused `processing_section` string said
  "Output Control" since before #212; the string was retired, the Output Control
  button/screen keeps its name — different content); `5f63b29` — 13 × 1dp
  `@style/SettingsDivider` (16dp breathing room) between configuration items across all
  five screens; `81992d2` — the About row swapped (title left, Help chip right, weighted
  `Space` = space-between); `96cc343` — the contrast EXCHANGE the owner asked for:
  dividers lead in `@color/text_secondary` (light 7.8:1 / night 9.4:1), while
  `colorControlNormal` → `@color/control_quiet` (~20 % opacity light `#33000000` /
  night `#33FFFFFF`) steps ALL unselected control lines (EditText + spinner underlines)
  back app-wide. The focused line keeps `colorControlActivated` (accent).
- **Tests:** 805 `:app` (was 771) — new `SettingsValidationTest` (check-name contract ×
  9, draft-isEmpty, apply-skips-invalid, token/secret separation), per-screen Robolectric
  tests (`ProvidersActivityTest` 5, `PromptsActivityTest` 4, `OutputControlActivityTest`
  6, `AdvancedActivityTest` 5), `SettingsActivityTest` rewritten for the overview (7);
  `ModelFilterAdapter` extracted (`ui/ModelFilterAdapter.kt`); the four screens joined
  `ui/contrast/ContrastMatrix.kt` (all four passed light/night). `SettingsStore` keys,
  IME and Workbench untouched; no migration.
- **Owner verdicts (2026-11-26):** overview + heading + dividers „sehr gut eingebaut";
  About swap + quiet underlines „passst". Known and accepted: `colorControlNormal` is
  app-wide — Workbench EditText underlines and empty checkbox outlines are quieter too.
  The final state (`96cc343`) is installed on the OnePlus (`f6de166c`); the Samsung
  A032F dropped off `adb` before the last push and gets it at the next `installRelease`.
  #156 note: the settings screenshots are stale across the whole surface now.
