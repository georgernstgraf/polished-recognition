---
type: Decisions
title: Decisions — IME and the Target Field
description: The keyboard surface: its bar, icons and pulse, keyboard switching, the composing region, insertion, spacing and line wrap.
tags: [knowledge, decisions, ime-field]
generated: { by: opencode/knowledge-persistence, at: 2026-11-25T12:00:00Z }
status: stable
---

# Decisions — IME and the Target Field

Moved out of `DECISIONS.md` in #206; entries are verbatim. New decisions in this area are appended here.

## 2026-11-25: #205 — die Live-Vorschau ist implementiert (eine Abweichung von der Entscheidungstabelle)

Die Owner-Entscheidungen D1–D6 stehen unverändert; umgesetzt sind sie in
`service/LivePreview.kt` (app-weiter Zustand + die reinen Policy-Objekte),
`service/EditorPort.kt` (die Editor-Naht) und der IME. Eine Stelle der
D3-Tabelle war **nicht eindeutig** und wurde zugunsten der Invariante 5
(„nie ein Duplikat") aufgelöst:

- **Entscheidung: beim Bind bedeutet `B + P` „committetes Präfix K = P", nicht
  „Composition hat überlebt".** `setComposingText` **ersetzt** die
  Composing-Region; sind `B + P` im Feld und P bereits committet, würde ein
  erneutes Senden von P das Feld verdoppeln. Die Plattform committet bei jedem
  Feldende (`onFinishInput` → `finishComposingText`), also ist K = P der
  Normalfall eines Rebinds (D3 sagt das selbst). Die Tabellenzeile
  „`B + P` ⇒ resend" gilt für den **Tick auf der lebenden Verbindung**, wo
  `B + K + sent` das Ersetzen der Region erlaubt; beim Bind wird sie nie
  ausgeführt. Folge: Ein Diktat, das eine Rotation überlebt, endet raw
  vollständig (`SUFFIX_APPENDED` bzw. `COMMITTED_BY_APP`) mit Q in der
  Zwischenablage — genau der Owner-Satz „roh oder poliert, egal; vollständig
  ist die Anforderung".
- **Entscheidung: B beim ersten Append um die Selektion kürzen.** Der erste
  Compose-Schreibvorgang ersetzt die Selektion; ohne die Kürzung enthielte B
  den markierten Text und der Restore-Guard (`Fenster == B`) könnte nach dem
  Löschen der Composition nie passen.
- **Entscheidung: ein Tick mit unlesbarem Fenster bricht nur die ANZEIGE ab**
  (`streamAborted`); die Lieferung entscheidet der Terminal-Schritt. Ein
  unlesbares Feld am Terminal ist `MISMATCH` (nichts einfügen, Q ins
  Clipboard), ein nie geschriebenes Feld `UNREADABLE` (heutiger Pfad).

## 2026-11-25: #205 — die Live-Vorschau ist entschieden (ersetzt #190)

#190 wurde als *superseded* geschlossen; alle Owner-Entscheidungen stehen in
#205 als D1–D6. Was sich gegenüber dem #190-Entwurf geändert hat:

- **Anzeigetakt statt „Kadenz“**: ein Sprung pro fertigem Fragment. Keine
  zusätzlichen STT-/LLM-Requests, keine zweite Anzeige-Kadenz, keine verkürzte
  Produktions-Slice, kein Streaming-STT. Gemessen 20 s (gregor/ogg), 137 s
  (GROQ); Bootstrap 21 s, Untergrenze `8 s/(1−p)` ≈ 11 s. Ein Diktat kürzer als
  eine Slice zeigt nichts — akzeptiert.
- **Rotation = NavBar-Schließen**: ein Mechanismus. Die Plattform committet beim
  Field-Ende (`onFinishInput` → `finishComposingText`), K ist damit der
  Normalfall. Reconciliation beim Bind gegen die Fensterprobe (`B+P` / nur `B` /
  `B+K`); **verifiziertes** Wiederaufsetzen ist erlaubt — die frühere
  Invariante 4 („nie wieder komponieren“) gilt nur noch unverifiziert.
- **Streaming-Zustand app-weit** (neben dem Controller): nach einem
  IME-Instanz-Tod kommt `Completed(redelivered = true)` an eine Instanz ohne
  Gedächtnis; ein instanzgebundener Zustand machte den blinden Insert in ein
  K-Feld zum verbotenen Duplikat (Invariante 5).
- **Terminal-Kaskade E → B → A** für `0 < K < P`: Anzeige fortsetzen, sonst
  Roh-Suffix anhängen (Cursor-Verifikation, Marker gestrippt), sonst nichts +
  Clipboard. Nie löschen; **Invariante 2** ist auf den Streaming-Pfad
  eingegrenzt (Löschen nur als ausdrückliche Nutzeraktion).
- **Invariante 1** gilt „auf der Verbindung, die gestreamt hat“; **I6** keine
  Zusatzkosten; **I7** Marker und Zwischentext sind nie Liefertext.
- **Neue Naht `EditorPort`**: es gibt keinen Fake-`InputConnection`, und
  `insertResultText` liegt über dem Commit — ohne diese Naht ist die
  Composing-Seite nicht testbar.
- Unterlage: `docs/design/190-live-vorschau-begriffe.pdf` (9 Seiten, `a9ef070`);
  wird um die Kadenz-Sektion und diese Entscheidungen ergänzt.

## 2026-11-22/23: #190 — die Zwischenanzeige läuft über die Composing-Region, der Endzustand bleibt #116 Option C

Das Diktat soll im Zielfeld wachsen, während transkribiert wird — und nach Send
soll das Feld aussehen wie heute. Beides zusammen ist nur eine Anzeige-Frage:
`setComposingText` (bisher an keiner Stelle im Repo) ist der einzige Kanal, den
die IME besitzt und der kein Input des Clients ist. Der Richtungswechsel gegen
#116 Option C ist gewollt — aber nur für die ANZEIGE; der Endzustand bleibt
Option C. Sechs Punkte (Q1–Q6) hat der Owner am 2026-11-23 einzeln entschieden.

- **Composing statt Commit — nie ein Rewrite, und immer EINE Region.**
  `setComposingText` ERSETZT die Composing-Region; es gibt kein „append to
  composition". Also trägt eine einzige Region den ganzen Zwischentext, und jeder
  Fragment-Append sendet ihn komplett neu (ein paar KB IPC, irrelevant). Das
  Event trägt deshalb den gejointen, echo-getrimmten Snapshot (`snapshot()`),
  keinen Delta — sonst stünden Seam-Duplikate auf dem Schirm, die der Endtext
  nicht hat. Am Send wird die Region VERWORFEN (`setComposingText("", 1)`),
  danach läuft der heutige Pfad unverändert: `commitWithSpacing(polished)` →
  `InsertionSpacingPolicy` → `commitText`. `deleteSurroundingText` auf
  committetem Text bleibt verboten — das war der eigentliche #116-Felsen
  (Selection-State, Auto-Edits, fremde Compose-Regions).
- **Der Terminal-Pfad ist eingefroren.** Jedes Ergebnis, das heute nichts
  einfügt, räumt die Composing-Region und fügt nichts ein: X/Cancel
  (#113-Salvage bleibt Clipboard), **Flush (#86)** (dessen Audio bewusst
  verworfen wird — die Zwischenanzeige muss mit ihm verschwinden), STT-Fehler,
  geparktes PAUSED, AUTH/BUDGET/OFFLINE-Latch. #118 Phase B bleibt unberührt:
  Streaming kann nicht ändern, was landet.
- **Auswahl am Sessionstart (#190/Q1):** Snapshot via
  `getSelectedText(GET_TEXT_WITH_STYLES)` + Range, VOR dem ersten Append.
  Non-null ⇒ bei jedem nicht-liefernden Ausgang zurückschreiben
  (`setComposingText(snapshot)` → `finishComposingText()` → `setSelection`) —
  ein committeter Edit plus Re-Selektion (TextWatcher, Undo, Markor dirty;
  Styles nur so weit, wie der Editor spanned commits annimmt). Restore nur, wenn
  das Before-Window noch passt, sonst Snapshot in die Zwischenablage +
  `ime_selection_restore_failed`. **`null` ⇒ trotzdem streamen**, ohne Snapshot,
  ohne Restore, ohne Toast (`selectionUnreadable`): die API kann „keine Auswahl"
  nicht von „sage ich nicht" unterscheiden, und der Owner will die Anzeige lieber
  als die Daten. „Nichts wird committet und später ersetzt" gilt damit nur noch
  für den SENDE-Pfad; ein Cancel ist in solchen Apps bewusst verlustbehaftet.
- **Guard = Before-Window (#190/Q3).** `getTextBeforeCursor(n)` über den ganzen
  Zwischentext ist nicht implementierbar (Kosten, Capps, `null`) — verglichen
  wird ein ~64-Zeichen-Fenster gegen das beim ersten Append gemerkte
  Before-Window plus Schwanz des Zwischentexts. Der ERSTE Fehlschlag stoppt nur
  das Appenden (`streamAborted`), er entscheidet NICHT die Lieferung; Autorität
  ist der Terminal-Check nach dem Verwerfen (Fenster muss wieder exakt das
  Before-Window sein — der Vergleich gegen das Before-Window statt gegen
  „unseren Text" verhindert den False Positive, wenn das Diktat mit denselben
  Wörtern endet, die davor stehen). Ist das Feld von Anfang an nicht lesbar, wird
  gar nicht gestreamt (heutiger Pfad, blinder Commit) — nie in ein Feld
  schreiben, das man nicht lesen kann.
- **Drei Terminal-Ausgänge (#190/Q4), nicht zwei.** (a) Fenster = Before-Window ⇒
  polierten Text über den heutigen Pfad einfügen (`CLEARED`). (b) Das Feld endet
  mit unserem Zwischentext ⇒ der Client hat ihn als committeten Text behalten —
  Rotation/Rebind, Fokusverlust (das Committen einer Composition ist der
  Plattform-Vertrag, kein App-Fehler) oder ein Editor, der `setComposingText` wie
  `commitText` behandelt. Dann NICHTS einfügen, den polierten Text in die
  Zwischenablage und Toast `ime_raw_kept` („Raw text kept — polished version
  copied"): der Text ist da, nur roh — die Owner-Regel „roh oder poliert, egal"
  ist erfüllt, kein Duplikat (`COMMITTED_BY_APP`). (c) Alles andere ⇒ nichts
  einfügen, Zwischenablage + `ime_polished_copied` (`MISMATCH`). Die
  #118-Taxonomie (`LIVE`/`REDELIVERED`/`RAW_RESCUE_PARTIAL`) bleibt unangetastet;
  der Terminal-Record bekommt additiv `streamed`/`streamOutcome`.
- **`<pending>` für ein endgültig gescheitertes Fragment (#190/Q5).** Ein
  Fragment-Fehlschlag ist NICHT session-fatal (nur AUTH/BUDGET/OFFLINE latchen),
  spätere Fragmente transkribieren weiter — das Loch entsteht also schon während
  der Aufnahme, nicht erst beim Drain. Anzeige: ein `<pending>` pro
  zusammenhängender Lücke, **nur im Anzeige-Join** (nie in `Drained.text`, nie im
  gelieferten Text); bei `COMMITTED_BY_APP` bleibt es als ehrliche Spur im Feld.
  Stirbt der IME-Prozess, bleibt der Zwischentext im Client stehen — akzeptiert,
  kein Bug (besser als ein leeres Feld; das Audio liegt im #152-Journal).
- **Rotation setzt keine Composition wieder auf — und der Client darf den Text
  behalten.** Der Span stirbt mit dem Input-View; ein Wiederaufbau wäre eine
  zweite Wahrheit über Text, den niemand committet hat. Der Client kann das Feld
  inklusive unseres Zwischentexts sichern und wiederherstellen (Plattform, kein
  Fehler) — dann landet er als committeter Rohtext im Feld, der Terminal-Check
  erkennt (b) und legt die polierte Fassung in die Zwischenablage. Zugestanden:
  ein Rotationsdiktat endet damit roh im Feld statt poliert wie heute; repariert
  wird das NICHT durch Löschen des wiederhergestellten Texts.
- **Kill-Switch (#190/Q6):** Settings-Toggle „Live preview in the text field"
  (`SettingsStore.imeLivePreview`, Key `ime_live_preview`, Default AN), gelesen
  am Sessionstart. AUS ⇒ null Composing-Schreibzugriffe, byte-identisch zu heute.
  Das ist der einzige Ausweg für einen Client, der den Zwischentext sofort
  committet (sonst landet der polierte Text nur in der Zwischenablage) — bewusst
  KEINE Package-Blacklist, keine Heuristik, kein Kompatibilitätsmodus.
- **Der Owner hat die Nebenwirkungen akzeptiert:** in einem plain `EditText`
  (Markor, Settings-Felder) ist der Zwischentext voraussichtlich unterstrichen,
  `TextWatcher` feuern pro Fragment (Drafts, Markor dirty), App-Autocomplete und
  Spell-Check sehen Composing-Wörter, und ein App-Send mitten im Diktat kann den
  rohen Zwischentext verschicken. „Exakt wie heute" gilt für **Feldinhalt und
  Cursor nach Send**, nicht für die Event-Historie der App.
- **Verworfen:** eine reine Bar-Vorschau ohne Feld-Schreibzugriff (das
  risikofreie Gegenmodell — der Wunsch ist, die App füllen zu sehen); jede
  satzweise oder pro-Fragment-Politur (#116-Entscheid: EIN Full-Context-Pass);
  Partial Results auf der `RecognitionService`-Fläche (Gboard-Mikro, API 31+ —
  eigene Fläche, eigenes Issue; `partialResults` ist heute unbenutzt); jeder
  Per-App-Workaround (Package-Blacklist über `EditorInfo.packageName` wäre
  technisch möglich und ist abgelehnt).
- **Beschlossen, nicht gebaut:** die Umsetzung steht hinter #188, #191, #156 und
  den offenen Owner-Gerätechecks. Ein Issue, kein Epic — der Scope, der es zum
  „big rock" machte, ist explizit weggefallen.

## 2026-11-19: #175 — the exit decides the keyboard; `switchBackAfterSend` is gone; the Workbench/IME boundary clarified

- **The contract (owner decision):** **Send** ⇒ Polished stays the default IME
  (#174 delivers the mechanism). **X** ⇒ the previous typing keyboard comes
  back, exactly as before (#155): pause a running take, resolve the last-used
  keyboard with `SwitchTargetPolicy`, `switchInputMethod`; no target or an
  ineffective switch ⇒ `openKeyboardSettings()`. **Gear** ⇒ typing keyboard +
  Settings, unchanged and deliberate (2026-09-07 #72): Settings' EditTexts
  cannot be filled by a voice IME, and no app can switch itself back
  afterwards with public APIs.
- **Why the setting died instead of being kept as an override:** with Send
  never switching, `switchBackAfterSend` had exactly one remaining effect —
  resurrecting the behaviour #174 removes. A preference that can only undo a
  decided contract is a trap for the next reader.
- **No prefs migration.** The key simply stops being read; a stale
  `switch_back_after_send` boolean in SharedPreferences is inert. (Removing it
  would need a write on every startup for a value nothing consumes.)
- **Removed:** `SettingsStore.switchBackAfterSend` + its key, the Settings
  checkbox and its hint (`activity_settings.xml`), both strings, the load/save
  pair in `SettingsActivity`, and the two `SettingsStoreTest` cases.
  `README.md` and `docs/help.html` now state the contract; the help gains a
  "Send and the keyboard" section and names the gear exception explicitly.
- **#166 boundary clarified on the owner's wording** — "die Workbench ist
  einfach ein Textmanager, die IME bleibt eine Tastatureingabe, und die
  scheinbare Verletzung ist keine": nothing forces the IME into the
  Workbench. Entering the Workbench while another keyboard is active starts
  no dictation — by design. The user dictates with that keyboard's mic key,
  which calls our delegated `RecognitionService` (enabled from API 31,
  `values-v31/bools.xml`) into the focused field; `callerPackage` still forces
  raw inside our own app (#168), and since #176 nothing polishes a Workbench
  dictation automatically — it stays raw until a polish button is tapped
  (#178). No
  `privateImeOptions` protocol, no Activity-hosted recording bar — both were
  evaluated and rejected as unnecessary machinery.
- **Also confirmed (no code change):** the IME inserts exactly ONCE, with the
  finished (already polished) text — #116 option C, recorded 2026-10-07. There
  is no fragment-by-fragment insertion into the field and therefore no
  "replace the raw text with the polished text" step to build; the fragment
  progress is visible only in the stage line.
- **NO version bump (owner policy).**

## 2026-11-19: #174 — Send behaves like a normal keyboard: the voice keyboard stays, nothing switches, nothing hides

Owner report: "eine normale Tastatur wird ausgeblendet, wenn ich im
Navigationsmenü die Pfeiltaste nach unten drücke … unsere Tastatur sollte
genauso arbeiten." **This entry supersedes #133's automatic post-send switch
and the `requestHideSelf(0)` that closed that path.**

- **What happens on a successful send:** the finished (already polished, #116
  option C) text is committed ONCE via `insertResultText` → `commitWithSpacing`,
  the app-usage learning runs, and that is all. No `switchInputMethod`, no
  `requestHideSelf(0)`, no policy object.
- **Why the policy is gone rather than inverted:** the old
  `PostSendSwitchPolicy.decide(autoSwitch, target)` existed only to choose
  between SWITCH and HIDE_ONLY. With both arms removed there is no decision
  left; a policy object that always returns the same answer is noise.
- **Hiding is the platform's contract.** The navigation-bar down arrow / Back
  dismisses the input view; because the *default* IME was never touched, the
  next focus opens Polished again. That is exactly the typing-keyboard
  behaviour the owner asked for, and it makes the "always Polished next after
  Send" rule (#175) true by construction. Only `switchInputMethod` changes the
  default IME; `requestHideSelf` never did (PITFALLS, #155).
- **Leaving the voice keyboard is the X's job** (#155, contract in #175):
  pause a running take, resolve the last-used typing keyboard, switch. The
  gear keeps its documented switch (DECISIONS 2026-09-07 #72): Settings'
  EditTexts cannot be filled by a voice IME, and an automatic return is
  impossible with public APIs.
- **Testability change:** the class is `open` and the success path is
  `internal fun handleCompletedSuccess(text, event)` with the single insertion
  point factored out as `internal open fun insertResultText(text)`.
  `ImeCompletedHandoverTest` (Robolectric) subclasses the service, records
  `switchInputMethod`/`requestHideSelf`/`insertResultText`, and pins: one
  insert, zero switches, zero hides — for the live and the redelivered
  (`pendingResult`) path alike. The seam exists for the recording subclass
  only; there is no production caller besides `onEvent`.
- **Unchanged on purpose:** the failure path (toast + PAUSED parking, the
  keyboard stays), rotation/`onFinishInputView` pause, the `onDestroy` state
  matrix, and #138's consume-once guard — that guard protects every *other*
  teardown (rotation, process death, a later X), not just the removed switch.
- **NO version bump (owner policy).**

## 2026-11-13/14: #161–#164 — the app owns its text palette, and only our own palette is gated

- **The app theme declares its own `textColorPrimary/Secondary/Tertiary` (owner decision).** The audit measured the shipped palette at 16 light-mode failures (secondary `#70777C` on `#F0F0F3` = 4.00:1 in 13 rows, accent-as-text 3.99:1, disabled 1.88:1). Inheriting the platform's translucent greys means every OEM night skin decides our legibility — and #160 proved an OEM skin can even point those attributes at selectors. So the theme owns the palette through `res/color/text_{primary,secondary,tertiary}.xml` selectors over `values`/`values-night` colour pairs (light `#212121`/`#4A4A4A`/`#666666`, night `#FFFFFF`/`#C0C0C0`/`#A0A0A0`), opaque by construction. The alternative — patching individual screens — was rejected: it fixes the 16 rows and leaves the mechanism.
- **The `_enabled` suffix is a hard naming rule, not taste.** `res/color/text_primary.xml` and `<color name="text_primary">` are the SAME resource id, so the enabled colour is `text_primary_enabled` and the disabled one `text_disabled`. Renaming either back collides at build time.
- **Disabled text must reach 3.0:1, hence own disabled entries (owner decision).** The platform's 26 % black measured 1.88:1 on the "Forget" button. WCAG exempts disabled controls, but an unreadable button is a defect regardless — so each selector carries an explicit `state_enabled="false"` entry (`#808080` light / `#8A8A8A` night = 3.47 / 5.0) and the gate enforces 3.0 there.
- **OEM-theme runs are measured but NOT gated (owner decision).** The audit runs every screen under `Theme.Material`/`Theme.Material.Light` (the selector-based OEM shape) because that is where #160's class of bug lives, but a foreign skin's palette is not ours: those runs assert the mechanic (the walk matched, no invisible text) and print the ratios for triage, while the zero-violation gate applies to the app theme only. Gating a foreign palette would produce red builds we cannot fix.
- **Thresholds: 4.5 normal / 3.0 large / 3.0 disabled, and "large" means bounds AND measured glyph height ≥ 18 sp.** Nothing in this app qualifies as large, so the effective floor is 4.5 for all enabled text — the large rule exists so the gate cannot be softened later by relabelling a row.
- **`TextContrast` stays declared in `lint.xml` although it does not exist in lint 32.1.1 (AGP 9.1.1).** Verified by jar inspection and empirically. Removing the declaration would hide the intent; keeping it silently does nothing. Decision: keep it, document it as inert in the file and in CONVENTIONS/PITFALLS, and treat `XmlColorPolicyTest` as the effective gate. The lint baseline (91 entries: 2 pre-existing errors, 88 warnings) is accepted as-is — it must never gain a contrast issue.
- **The render test is opt-in, not part of the required suite.** It costs > 10 s and depends on Robolectric `NATIVE` graphics internals; promotion needs 10 identical consecutive runs, two green CI weeks and < 10 s inside the suite, and any non-reproducible verdict reverts it (criteria in CONVENTIONS).
- **NO version bump for #161–#164 (owner policy).** The palette is user-visible but rides the next release with its own bump; shipping a build for it alone would burn a version number for a change no store listing mentions.

## 2026-11-13: #157 — the IME pads itself away from the navigation bar, unconditionally

- **The problem only exists from API 35, and it is a platform change, not an app setting.** Same build, same 3-button navigation, two AVDs: API 31 keeps the IME content above the nav bar (the platform applies the inset to the IME window's content), API 35 lays the IME window out edge-to-edge (`[0,2058][1080,2400]` against a nav bar at `[0,2274][1080,2400]`). So the "targetSdk 36 ⇒ edge-to-edge" explanation in the report was right about the trigger and wrong about the mechanism (it is not the Activity enforcement; the IME window itself stopped insetting its content).
- **No SDK gate.** The alternative — `if (SDK_INT >= 35)` — would encode a guess. Measured instead: on API 31 the root view never receives an inset dispatch (the platform consumes it at the decor; the trace line never appears), so the same listener is inert there. A gate would only protect against double padding that provably does not happen, and would hide the mechanism from the next reader. If a future OEM reports the opposite (31 with a dispatch), the trace line will show it immediately.
- **Padding is recomputed from the XML base, never appended.** Insets are dispatched repeatedly (show, rebind after rotation, split-screen); appending to `root.paddingBottom` grows the keyboard on every event. The listener therefore carries the base paddings captured at inflation.
- **`max`, not sum, per side.** In landscape the 3-button navigation bar sits inside the cutout region; summing both would over-pad the bottom row sideways.
- **The insets stay unconsumed** (the listener returns them): the IME window itself is still placed by the platform from them.

## 2026-11-12: #155 — the IME loses its top-row keyboard button; the X hands the field back to the typing keyboard
- **Trigger (owner request):** the keyboard-switch button at the far left of the top row was redundant next to the bottom row, and the language spinner (`layout_weight="1"`) was cramped. The button is **removed without replacement**: the spinner grows into the freed 36 dp + 8 dp margin automatically.
- **The X inherits the switch:** `ime_cancel_button` → `ime_switch_button` (icon and geometry unchanged), handler = the removed button's body verbatim — pause a running take, `resolveKeyKeyboardTarget(selfId)`, `switchInputMethod(target)`; no target or an ineffective switch → `openKeyboardSettings()`.
- **The X no longer cancels:** `controller.cancel()` and `requestHideSelf(0)` are gone from it. Discarding is the trash button's job (`flush()` — clears audio + timer, stays in the keyboard). Consequence, accepted: throwing a take away and leaving costs two taps (trash, then X), and the #113 clipboard salvage on an *explicit* cancel is no longer reachable from the bar (it still fires on app/field change and on the `onDestroy` IDLE cancel).
- **The X always switches — NOT gated by `switchBackAfterSend`:** that setting governs only the automatic post-send switch (`PostSendSwitchPolicy`). Gating the X as well would leave no manual way out of the voice keyboard once the second switch control is gone.
- **#134 salvage untouched:** `onStartInputView`/`AutoStartPolicy` (PAUSED + different field/app → `cancel()` + #113 salvage + fresh recording) and the `onDestroy` IDLE cancel remain the only discard-on-context-change path. The pause the X performs is exactly what makes the same-field return resume the dictation.
- **Renames:** `ime_cancel`/`ime_cancel_desc`/`ime_cancel_hint` deleted; `ime_switch_keyboard_desc` ("Switch keyboard") and `ime_switch_hint` ("Keyboard — back to your text keyboard") now belong to the X, and `ImeHintPolicy` maps `ime_switch_button` → `ime_switch_hint`. `docs/help.html`'s interactive demo follows (top row without the switch key, X labelled "Keyboard").
- **Rejected alternative — gate the X by `switchBackAfterSend`:** consistent-looking, but with the top-row button gone it would strand users in the voice keyboard whenever the setting is off; the setting is about *automatic* behaviour, the X is the explicit gesture.
- **Out of scope (deliberate):** no version bump, no `docs/marketing/*` edits, no pipeline/session behaviour change.

## 2026-11-12: #154 — UI speaks LLM (inverted IME toggle), Settings raw-mode checkbox removed, one language entry "LLM, keep language"
- **Trigger (owner request):** the label "RAW" described the *implementation*, not the *choice*. Users do not think "skip the LLM", they think "do I want the polish?". So the bar checkbox now reads **LLM** and means what it says: checked = the LLM polishes (and translates when a target language is set), unchecked = plain transcription.
- **UI-only inversion (binding):** `SettingsStore.rawMode`, the preference key `raw_mode`, `PipelineOutcome.rawMode`, `SessionBundle` (`provider.rawMode`/`llm.rawMode`) and the analyzer DTOs are UNCHANGED. The inversion lives at exactly two call sites in the IME — `llmCheckbox.isChecked = !settings.rawMode` and `settings.rawMode = !isChecked` — plus `updateLanguageEnabled()` (spinner enabled only while the LLM is on). Consequence: no preference migration, no forensic-format break, and existing bundles keep their meaning.
- **Settings checkbox removed:** `raw_mode` (CheckBox + string + `rawModeCheckbox` field/load/save in `SettingsActivity`) is gone — the IME toggle is the single place, and it already persists immediately, so the state survives restarts. The Settings screen keeps the target-language dropdown.
- **Language entry unified:** both language constants are `"LLM, keep language"`, one source (`CustomLanguages.NONE_TARGET_LANGUAGE`, re-exported by `LanguageOptions`). The old split ("Polish only" for the narrow IME bar, "None (polish only)" for Settings) existed only because the IME bar is narrow; the new label is equally long, so two variants would only invite drift.
- **Rejected alternative — rename + migrate the stored setting** (e.g. `llm_mode`, default true, one-shot migration of `raw_mode`): it touches three persisted/forensic surfaces for zero user-visible gain, can silently flip the behaviour of existing installs if the migration misses, and would desynchronise the analyzer's existing bundles. Renaming the *UI* names only (`ime_llm`, `quick_llm`) keeps the vocabulary honest where users see it.
- **Out of scope (deliberate):** no version bump (rides the next release), no marketing-post edits (`docs/marketing/*` are version-free), no pipeline behaviour change.

## 2026-10-13: #133 shipped — auto-switch back to the typing keyboard after a successful send (default ON, silent-hide fallback)
- **Two owner decisions deviate from the issue body:** (a) the new `switchBackAfterSend` setting defaults to **ON** (the body proposed opt-in/OFF to preserve existing behaviour — the owner explicitly wants the new behaviour as the default; existing users get it on upgrade since a missing prefs key reads `true`); (b) the "no typing keyboard available" fallback is a **silent hide**, NOT an auto-open of the system keyboard settings — with the feature default ON, auto-opening settings after every dictation would be intrusive for users who have only Polished enabled. The MANUAL keyboard-switch button keeps its settings-screen fallback unchanged.
- **Shape:** pure `service/PostSendSwitchPolicy.decide(autoSwitch, target)` → `SWITCH` / `HIDE_ONLY` (enum, matching the `ImeStartDecision` object+enum style; no sealed hierarchy needed since the caller already holds the resolved target string). The target comes from the EXISTING `resolveKeyKeyboardTarget()` / `SwitchTargetPolicy` — no duplicated history parsing.
- **Firing point:** in the IME's `Event.Completed` SUCCESS branch only, between `commitWithSpacing(text)` and `requestHideSelf(0)`. Ordering fact verified in `VoiceSessionController`: `Completed` is emitted BEFORE the `IDLE` transition on success (and the recording is already stopped at send), so the switch only ever fires on a finished, successfully inserted session — the issue's "switch only after IDLE with inserted text" edge case is satisfied without extra plumbing. Cancel and pipeline-failure paths stay on the voice keyboard for retry; a failed `switchInputMethod` degrades to the plain hide (no loops, no settings detour).
- **Delegated RecognitionService route needs NO guard:** `PolishedRecognitionService` never calls `switchInputMethod` at all (the calling keyboard owns insertion and re-shows itself), so the auto-switch is a no-op there by construction.
- **Tests:** +3 (`PostSendSwitchPolicyTest`: on+target → SWITCH, off → HIDE_ONLY, null target → HIDE_ONLY) +2 (`SettingsStoreTest`: default-true, round-trip) → suite **479 green**; `assembleRelease` green.

## 2026-10-05: The voice-input notification uses a custom widened mic vector (#109)
- **Choice**: The ongoing voice-input notification uses a custom 24dp vector `app/src/main/res/drawable/ic_notification_mic.xml` (variant **B**) as its small icon instead of the platform bitmap `android.R.drawable.ic_btn_speak_now` — in **both** notification paths: `PolishedVoiceInputIME` (channel `voice_recognition_ime`, id 1002) and `PolishedRecognitionService` (channel `voice_recognition_service`, id 1003). Variant B reconstructs the AOSP silhouette (studio/condenser mic: capsule + yoke arms + stem + elliptical foot) with the capsule widened ~20 %; bolder alternatives **C** (~+38 %) and **D** (~+51 %) are kept as drop-in vectors in `docs/design/notification-icon/`.
- **Reason**: The platform icon is a **bitmap** (`drawable-*dpi/ic_btn_speak_now.png`), not a vector, whose capsule is only ~5.5 units wide in a 24-unit box and reads too narrow in the status bar. The notification is ours, so its icon is fully under our design control. (The green mic/dot shown next to it on Android 12+ is the SystemUI privacy indicator and is **not** customisable.)
- **Trade-off**: Android renders notification small icons as a **monochrome (alpha) silhouette** — the system tints it, so only shape/size is ours, not colour. Android `VectorDrawable` has **no `<ellipse>`/`<rect>`** — the elliptical foot is a `<path>` (`a rx,ry 0 1 0 …`). Variant B is the owner's pick; C/D ship only as design references (not wired into the APK).
- **Out of scope**: the notification channel name/description and the notification texts; requesting `POST_NOTIFICATIONS` (Android 13+ still hides the notification; only the system privacy indicator remains).
- **Verification**: `./gradlew test` + `./gradlew assembleRelease` (+ `lintVitalRelease`) green; the on-device visual check (owner, Android 12 OnePlus) is **pending**.

## 2026-10-03: Line-wrap breaks at hyphens, URL-safe and orphan-free (#103)
- **Choice**: `LineWrapPolicy` splits a word after `-`, `–` (U+2013) or `—` (U+2014) as an additional break opportunity, keeping the dash at the end of the line; the break point is chosen classically greedily (last dash that fits). A fragment shorter than `MIN_DASH_FRAGMENT = 4` on **either** side of a dash is merged with its neighbour, so no orphan fragment (`a-` / `-a`) ends up alone on a line (short fragments merge forward, a short tail backward). URL-like whitespace tokens (`^[A-Za-z][A-Za-z0-9+.-]*://` or `^www\.`, case-insensitive) are never split. U+2011 (non-breaking hyphen) is not a break point. Words without dashes and `width <= 0` are byte-for-byte unchanged; no new setting.
- **Reason**: A long compound (e.g. `Datenschutz-Grundverordnung`) previously occupied a single over-long line because a word was never hard-split. Breaking at existing hyphens fills the configured width without touching ordinary words. The symmetric minimum prevents ugly one- or two-char orphans, and the URL guard preserves the long-standing "long tokens survive intact" property (copy-paste/clickability).
- **Considered**: Minimal variant (split only words longer than the width, move shorter ones whole to the next line) — rejected by the owner in favour of classic greedy, which fills lines better; left-only minimum — rejected in favour of the symmetric rule; splitting URLs — rejected (breaks links); separate on/off setting — rejected as unnecessary since the change is purely additive (only breaks text that already overflowed).
- **Also**: en/em dash were explicitly included in the break set (owner choice); U+2011 stays excluded.
- **Tests**: `LineWrapPolicyTest` +9 (hyphen, hyphen after a preceding word, en/em dash, URL, U+2011, leading-fragment merge, trailing-fragment merge, dash-free regression, `width == 0`). `./gradlew test` green.

## 2026-09-28: Per-app line-wrap width with learned apps (#100)
- **Choice**: The global `wrapWidth` stays the default; an optional per-app override (`KnownApp(label, lastSeenMs, wrapWidth: Int?)`, `null` = global, `0` = off, `>= 20` = width) is applied. Validation unified to `0 or >= SettingsStore.MIN_WRAP_WIDTH (20)` for both. Resolution happens in `TranscriptionPipeline` via `settingsStore.wrapWidthFor(callerPackage)`; the caller package is captured from `EditorInfo.packageName` (IME) and `RecognitionService.Callback.getCallingUid()` → `PackageManager.getPackagesForUid` (bound service, framework captures the client uid on the binder thread — works on API 29+). Both entry points learn the dictation target only on successful insertion/delivery; own package and blank are ignored.
- **Storage**: A **separate** SharedPreferences file `known_apps` (candidate Option A), so the frequent learner writes and the OS backup stay isolated from `polished_recognition_settings`. Included in Google Auto Backup by default (`allowBackup=true`, no rules) — learned apps/overrides survive a device change. `SettingsStore` keeps an in-memory cache (lazily loaded) so the throttle check is O(1) and no JSON/IO runs on the insertion hot path.
- **Async writes**: All `known_apps` persistence runs on an injectable single-thread daemon executor; failures only `Log.w`. The learner task is **only enqueued when the last dictation in that app is older than 24 h** (or the app is new) — no thread on the common path. Tests inject a direct executor.
- **Settings UI**: Output Control gains a "Line wrap per app" section — dropdown of learned apps (recently-used first) + value field (empty = global) + forget button; empty-state hint. No installed-app scan, no `<queries>`, no new permission.
- **Considered**: app-type categories (rejected — not reliably detectable on Android); `QUERY_ALL_PACKAGES`/installed-app picker (rejected — Play friction, user only wants learned apps); writing on every dictation (rejected — read-modify-write churn); `Binder.getCallingUid()` at dispatch (rejected — `onStartListening` runs on the main thread after the binder identity is lost; `Callback.getCallingUid()` carries it correctly).
- **Tradeoff**: The learned list is re-learned on a fresh install if the user disables OS backup; persisted `lastSeen` ordering is coarse (up to 24 h stale between writes; refined for the running process by #102's in-memory MRU). API tokens remaining in Auto Backup is tracked separately (#101).

## 2026-09-24: Redundant IME timer/spinner divider removed (#97)
- **Choice**: `ime_rec_timer_divider` deleted from `ime_voice_input.xml` and `PolishedVoiceInputIME` (property, `findViewById`, three visibility writes in `updateRecTimer`). `ime_quick_settings_divider` remains the single separator before the language spinner. Commit `0a91a8e`.
- **Reason**: #88 (`9939418`) moved the spinner behind the timer, leaving `ime_rec_timer_divider` (added in #71 to separate the timer from the then-right Raw checkbox) directly beside `ime_quick_settings_divider` — two bars + 16dp gap between timer and spinner during RECORDING/PAUSED.
- **Tradeoff**: None; pure deletion. Verified `./gradlew test` green.

## 2026-09-24: IME bottom-row icons unified — 24dp / 1.6 stroke, line backspace cross, full-height cancel (#95)
- **Choice**: All five bottom-row ImageButtons render 24dp-intrinsic vectors (`scaleX/Y=1.5` → 36dp): `ic_close`/`ic_pause`/`ic_resume`/`ic_send` bumped 18dp→24dp; `ic_pause` bars stretched to y3–21; `ic_close` `strokeWidth` 2→1.6 and its X stretched to full height (y3–21, like trash/pause/send); `ic_backspace` inner X redrawn as two line strokes (was a stroked closed polygon → hollow/"outlined" look), outline+X both 1.6; `ic_delete_outline` lid redrawn as a single 1.6 rim line + handle (was a 2-unit band whose lower edge merged with the can top → visibly thick).
- **Reason**: Owner visual review — cancel/pause/send looked optically smaller than backspace/trash (intrinsic 18dp vs 24dp); backspace X looked outlined; trash rim a touch too thick; pause not full height.
- **Tradeoff**: `ic_backspace` wrapped in `<group android:scaleX/Y="0.9333">` with `strokeWidth 1.714` (net screen stroke 1.6) to shrink it ~5% **and** move the right edge off x=24, where the 1.6 stroke was half-clipped by the viewport (only 0.8 visible). All four small drawables are IME-only (`VoiceRecognitionActivity` removed in #61); `ic_delete_outline` stays shared with the Settings language rows. Commits `0882890` + `15fea5b`, owner feel-check "wunderbar".

## 2026-09-20: #84 + #88 closed (feel-checks passed); spacing watch verified; social preview built (#74)
- **Choice**: Owner passed both feel-checks — #84 (airplane-mode retry) and #88 (long-press hints) closed as completed, both shipped in v1.3.0. Insertion-spacing rules (#66/#73) verified working nicely in daily use — watch stays passive (refine only on new reports). Social preview: `docs/img/social-preview.png` (1280×640, PIL/Noto, icon + tagline + pipeline strip + phone crop) ready for owner upload in GitHub settings; repo text suggestions delivered (description/homepage/topics).
- **Reason**: Closes the v1.3.0 verification loop; everything implemented this cycle is now either closed or in release watch.

## 2026-09-19: Long-press overlay hint Toast, two broken attempts removed (#88, OPEN)
- **Choice**: `service/ImeHintPolicy` maps the 4 lower-row buttons to fixed detailed hints (new `ime_cancel_hint`/`ime_flush_hint`/`ime_mic_send_hint`/`ime_pause_resume_hint`; top row has none). The IME shows the hint as a `Toast` at the top of the screen on long-press (consumed, no tap) and cancels it on finger lift — visible only while pressed, never under the thumb, never touching IME layout. Touch listener returns false so single taps are byte-for-byte the old path.
- **Reason**: Two failed attempts per owner: (1) `android:tooltipText` pops up under the thumb — unreadable; (2) press-hold text in the stage line shifts the bar mid-tap (release lands off-button, single taps die) and forces itself into the IME layout.
- **Tradeoff**: A held press past the Toast duration (~3.5 s) outlives the popup until release re-shows nothing — accepted, holding help that long is not a real flow. `ImeHintPolicyTest` locks mapping + wording. Follow-up `6614ec1`: mic-icon remnants removed (send icon + `ime_send_desc` in all 4 states, `ime_send_button` rename, dead `ime_record`/`ime_stop`/`ime_mic_desc` + `ic_mic.xml` deleted, sweep verified clean), send-only `ime_send_hint`, single reused Toast (consecutive-press fix). Deliberately kept: `AudioRecorder`/mic FGS/`RECORD_AUDIO` (capture path) + notification mic icon (accurate while recording). On-device feel-check pending.

## 2026-09-19: Volume-independent sine blink during recording, RMS chain deleted (#87, CLOSED)
- **Choice**: RECORDING pulse is a pure sine blink — linear 0..2π phase `ValueAnimator` (1333 ms exact = 2000/1.5, RESTART, `LinearInterpolator`) driving the pure `PulseAlphaPolicy.blinkAlpha(phase, floor, ceil)` → 0.3↔1.0 (floor 0.3, ceil 1.0 per owner; ceil extended to full 1 on explicit confirmation). The sine eases in/out at the extrema with no keyframes and no dwell. `PulseAlphaPolicy.target(state, breathAlpha)` is single-value; non-RECORDING states stay pinned to full opacity (#77 rule unchanged). Deleted: `RmsAlphaMapper` + test, `Event.RmsChanged`/`SpeechBegin`, `AudioRecorderListener`, per-buffer `computePcmRms` + its 6 regression tests; `AudioRecorder.start()/resume()` take no listener and the capture thread only appends PCM.
- **Reason**: Owner request — remove the volume-dependent blinking, don't go as low as the 0.15 floor, ~1.5× faster. The RMS chain's only consumer was the pulse (`SpeechBegin` was already a no-op in the IME), so full removal leaves no dead audio-thread work (no per-buffer sqrt, no EMA).
- **Tradeoff**: Recording loudness no longer influences the UI at all (silence and speech look identical); the deleted sign-correction/noise-gate knowledge moves to HISTORY.md for any future audio-coupled UI. 5 new sine-mapping tests; suite 244→235, green + `assembleRelease` green. On-device feel-check PASSED by owner 2026-09-19 — issue closed.

## 2026-09-17: REC time counter in IME bar, divider-framed right of language dropdown (#71)
- **Choice**: New `ime_rec_timer` TextView (monospace, 13sp) + `ime_rec_timer_divider` in `ime_voice_input.xml`, placed between the language spinner and the Raw checkbox (Raw stays at the right edge); pure `service/RecTimeFormatter` (`recording()` → "REC m:ss", `paused()` → frozen "m:ss"); 1 s main-handler tick in `PolishedVoiceInputIME.updateRecTimer()` fed by `VoiceSessionController.recordedDurationMs()` (active mic time only, pauses excluded). PAUSED shows option (a) from the ticket (frozen, no REC prefix); notification unchanged (IME bar only).
- **Reason**: Owner request — deterministic recording indicator alongside the pulse; adaptive noise floor stays dropped per the earlier #71 decision.
- **Tradeoff**: Tick granularity is 1 s (no sub-second display); format string lives in code, not in `strings.xml` (single testable source).

## 2026-09-16: Configurable output line-wrap, 0 to disable, default 80 (#81)
- **Choice**: `SettingsStore.wrapWidth` (Int, default 80, `0` = disabled) + pure `pipeline/LineWrapPolicy` greedy word-wrap applied to the final text in `TranscriptionPipeline` (both raw and LLM paths, before `InsertionSpacingPolicy` padding in the IME). Settings UI: numeric `EditText` (`inputType="number"`) + quick-set buttons 80/120/200/0-off + validation (0 or ≥10).
- **Reason**: Owner request — on-demand wrapping at 80/90/200 chars, fully disableable via 0, default 80.
- **Tradeoff**: Wrapping runs before spacing padding, so the trailing space from `InsertionSpacingPolicy` can push a line 1 char over width — accepted (padding correctness beats exact width). Long single words are never hard-split (URLs survive).

## 2026-09-13: IME pulse applies only while RECORDING; all other states full opacity (#77)
- **Choice**: Extract `service/PulseAlphaPolicy.target(state, breathAlpha, voiceAlpha)` — returns `maxOf(breathAlpha, voiceAlpha)` only in `RECORDING`, else `1f`. `PolishedVoiceInputIME.applyAlpha()` delegates to it.
- **Reason**: Pausing during the breathing cycle's deep/dwell phase cancelled the animator but left the last animated `breathAlpha` (floor 0.15) applied; since `RmsChanged` is only honored in `RECORDING`, nothing ever lifted it and the IME froze near-invisible in `PAUSED`. The pulse is a *live-recording* indicator, so it has no meaning outside `RECORDING` — full contrast everywhere else (also fixes stale dimming inherited by `IDLE`/`PROCESSING`).
- **Considered**: PAUSED-only override (rejected — leaves `PROCESSING`/`IDLE` able to inherit stale values); resetting `breathAlpha`/`voiceAlpha` on pause (works but implicit and untestable).
- **Tradeoff**: One new policy object + test file; `IDLE` rows now render at `1.0` (previously sat at the leftover `BREATH_CEIL = 0.9`).

## 2026-05-30: `ValueAnimator` blink with ease-in-out
- **Choice**: Replace Coroutine-based blink with `ValueAnimator.ofFloat(0.3f, 1.0f)` using `AccelerateDecelerateInterpolator`, `INFINITE` repeat, `REVERSE` mode
- **Reason**: CSS-like "ease-in-out" smooth pulse instead of abrupt alpha steps. `ValueAnimator` is Android-native and doesn't need a coroutine scope
- **Considered**: `ObjectAnimator` (less control over repeat/reverse), keeping coroutine approach with smoother steps (more code)
- **Tradeoff**: Removed `Job`, `delay`, `isActive` imports. Animator lifecycle tied to manual start/cancel

## 2026-05-30: Blink animation on status text instead of mic button
- **Choice**: `ValueAnimator` on `statusText.alpha` instead of `micButton.alpha`
- **Reason**: Three-button layout removed the large mic button. Blinking "Recording…" text at 28sp provides the visual feedback that recording is active, matching iOS voice memos UX pattern
- **Considered**: Adding a separate indicator dot (extra layout element)
- **Tradeoff**: Status text loses blink during pause — user sees static "Paused" text. Clear enough for the paused state distinction.

## 2026-06-01: Dynamic language dropdown with persistent custom languages
- **Choice**: Replace fixed language list from `LanguageMapper.supportedLanguages` with a dynamic `LanguageDropdownAdapter` combining "None (no translation)" + "English" (always present) + `settings.customLanguages` (user-entered, persisted as JSON array in SharedPreferences). Delete via "Manage saved languages" dialog with trash icon per row
- **Reason**: Users want to type any language name and have it persist across sessions. A static list of 29 languages forces users to type the same language every time. AutoCompleteTextView dropdown items cannot contain focusable children (ImageButton steals touch → row not selectable), so deletion is handled in a separate dialog, not inline in the dropdown
- **Considered**: Swipe-to-delete in dropdown (conflicts with ListView scrolling), long-press popup via reflection (fragile), trash icon in dropdown (not selectable), long-press via OnTouchListener (unreliable with ListView touch dispatch)
- **Tradeoff**: Two-step delete (Manage dialog → tap trash). Delete happens immediately without confirmation. A Toast confirms removal.

## 2026-08-22: Auxiliary voice IME + remove bound RecognitionService (MR !40029, @bhavyashah04122005)
- **Choice**: Added an auxiliary `InputMethodService` (`PolishedVoiceInputIME`, `isAuxiliary="true"`, `imeSubtypeMode="voice"`, `res/xml/voice_method.xml`) so the app appears natively in HeliBoard/Fossify/OpenBoard's voice-typing picker — and **removed** the bound `PolishedRecognitionService` (and its `Settings.Secure.putString("voice_recognition_service", …)` "Set as Voice Input" button), which silently `SecurityException`d on OEM ROMs. `VoiceRecognitionActivity` (the `RECOGNIZE_SPEECH` intent path, full UI with pause/resume + quick language/raw) is **kept** as the rich surface and the AnySoftKeyboard fallback.
- **Reason**: `@bhavyashah04122005`'s on-device report on MR !40029 (tested Samsung One UI 8.5/Android 16) showed the `RecognitionService` path is broken-by-design on OEM ROMs (hidden `VOICE_INPUT_SETTINGS`, `Settings.Secure` blocked without root/ADB) and that modern open keyboards query auxiliary voice IMEs, not bound `RecognitionService`s. Validated against `woheller69/whisperIME(+)` (canonical pattern). F-Droid maintainer `linsui` endorsed: "I thought this should be implemented."
- **De-duplication**: extracted `VoiceSessionController` (owns `AudioRecorder` + drives `TranscriptionPipeline`; emits `StateChanged`/`StageChanged`/`Completed` on the Main thread) so the activity and the IME share one record→transcribe flow instead of two/three copies.
- **Keep vs replace**: kept the activity (richer UI) and only added the IME — diverging from the comment's "replaced" wording. Removed the bound service entirely (user-approved): the only loss is the stock-Pixel system-voice-picker entry, marginal since the app requires a cooperating keyboard regardless.
- **Tradeoff**: IME permission-from-service needs a `MicrophonePermissionActivity` trampoline (`InputMethodService` can't request runtime perms directly). Recording inside an IME uses a `FOREGROUND_SERVICE_TYPE_MICROPHONE` notification via `ServiceCompat.startForeground`. `INSTALLATION.md` rewritten: dropped the ADB `voice_recognition_service` block and the AnySoftKeyboard-as-required step; AnySoftKeyboard demoted to optional (intent path still works).

## 2026-08-22: Compact IME bar over delegate-to-activity + on-device findings
- **Choice**: The `PolishedVoiceInputIME` shows its **own compact inline bar** (mic/status/quick-settings/gear) rather than delegating to the full-screen `VoiceRecognitionActivity`. After on-device testing (Oplus/OnePlus phone, v1.2.0), the user confirmed the compact bar is acceptable **if** it gains pause/resume + quick language/raw + a settings gear — so the bar is enriched in place (issue #44), not replaced by the activity.
- **Controller deadlock fix**: `VoiceSessionController.stopAndTranscribe` now resets `state = IDLE` and emits `StateChanged(IDLE)` after `Completed`. Without this, the IME's mic button saw `PROCESSING` on the 2nd dictation → deadlock (the `when(state)` only handles IDLE/RECORDING). Harmless to the activity (it finishes on Completed).
- **CrashDialog Copy-to-Clipboard**: `CrashDialogActivity` now uses a custom `dialog_crash.xml` with two buttons — **Copy to Clipboard** (left, copies type+message+stacktrace via `ClipboardManager`, toast "Copied", dialog stays open) and **Close App** (right, `finishAffinity`+`exitProcess`). Replaced the single-positive-button `AlertDialog`. Motivated by the IME crash-loop where the on-screen stacktrace was visible but not copyable.
- **On-device findings (Oplus)**: (1) the IME crash was `?attr/selectableItemBackgroundBorderless` (Material theme attr) in the service context → fixed `@null` (PITFALL). (2) HeliBoard's mic uses `voice_recognition_service`, not the auxiliary IME — so removing the bound service (#43) broke HeliBoard's mic→Polished (a possible follow-up is to re-add the service additively; the user later decided to defer all UI work to fresh agents). (3) `adb ime`/`settings secure`/`pm grant` all blocked from shell on this ROM — UI-only. (4) The compact-bar language `Spinner` dropdown is unreadable (dark-on-dark) in the service context — needs custom explicit-color item layouts (issue #44).
- **Deferred to fresh agents**: the compact-bar redesign (issue #44) and the Settings theme-free rewrite (issue #45) are NOT implemented in this session — the user chose to hand them to new agents with a clean context window. This commit only persists the findings + the already-done fixes (crash fix, controller deadlock fix, CrashDialog Copy button, v1.2.0 bar baseline).

## 2026-08-30: IME gear + notification open a hint dialog, not Settings (#51) [superseded for the gear 2026-09-07 by #72]
- Moved to HISTORY.md — the notification `contentIntent` still launches `SettingsHintActivity` (unchanged); the gear now opens real Settings via keyboard switch (see the 2026-09-07 #72 decision).

## 2026-08-30: Editable + deletable custom target languages (#48, #53)
- **Choice**: (1) Rename: pencil icon per row in the manage-languages dialog → rename dialog (`CustomLanguages.validateRename`: blank → error; existing name incl. built-ins, case-insensitive → error; case-only rename of same entry allowed) — rename replaces in place and re-points `targetLanguage` if it was selected. (2) Delete: clickable **non-focusable** (`focusable="false"` + `focusableInTouchMode="false"`) trash `ImageButton` directly in the target-language dropdown rows for custom entries (`item_language_dropdown.xml` now a LinearLayout row); built-ins (None/English) get no icon. Shared `deleteCustomLanguage()` persists immediately, resets selection when the deleted entry was active, rebuilds the adapter.
- **Reason**: Old Material UI (#14-era) had edit-selected + append-on-save + per-entry trash icons; the #45 theme-free rewrite lost edit + in-dropdown delete. Append-on-save already survived in `saveAndClose()`.
- **Considered**: Long-press-to-delete in the dropdown — not implementable (no `setOnItemLongClickListener` on `AutoCompleteTextView`); visual-only icon + dialog-only delete — rejected (clickable non-focusable child is framework-sound: `AbsListView` only blocks rows with `hasFocusable()` descendants).
- **Tradeoff**: Supersedes the 2026-06-01 "two-step delete via Manage dialog" decision (that decision was driven by the old focusable-child pitfall interpretation). On-device verification of icon-tap vs row-tap still pending.

## 2026-08-30: Long-press inline edit + always-open manage dialog (#48 round 2)
- **Choice**: (1) Long-press on the target-language field enters inline edit mode (field becomes focusable, `text|textNoSuggestions`, cursor + IME shown); commit via IME Done or focus loss. Commit semantics (`CustomLanguages.commitEdit`): blank → revert to previous value; matches existing entry (built-ins incl., case-insensitive) → select it; new name → **append to `customLanguages` + select + Toast**, no Save needed. Short tap keeps opening the dropdown (tap-to-open selector untouched). (2) The manage-languages dialog now always opens; when the list is empty it shows a hint message instead of the early-return toast that left the user with no input surface.
- **Reason**: User report (#48 reopened): "Manage saved languages" yielded only a toast on first use — there was no input surface at all. User-specified interaction model: short tap = open dropdown, **holding the selected value = make it editable** (old Material behavior, edits saved directly to the list).
- **Considered**: Add-input inside the manage dialog (rejected — user specified long-press-on-field as the add/edit gesture); immediate-append on plain field edit without long-press (rejected — would conflict with tap-to-open selector semantics); inline rename of the selected entry (rejected — rename stays in the manage dialog pencil, inline edit is add/select per #53's "appended to the list").
- **Tradeoff**: While in edit mode, tapping the field still opens the dropdown (matches old Material); focus-loss commit can fire when the user taps Save — harmless (commit then save, both persist). Case-only edit of the selected custom entry resolves to the canonical existing entry (case rename stays a dialog operation). On-device verification pending (device disconnected this round).

## 2026-08-31: Two-line IME with flash pulse + inline stage display (#57)
- **Choice**: IME bar reduced from three rows to two. Row 1: gear (far left) + language spinner | divider | Raw checkbox + a `ime_stage_text` TextView (`GONE` by default). Row 2: cancel | pause/resume | mic/send (unchanged). The standalone status line (and its `ime_status_*` strings) is removed.
- **Flash feedback**: superseded the same day — see "Voice-reactive RMS-driven pulse (#57 round 2)". Initially a fixed-cycle `ValueAnimator` on root alpha (1.0 ↔ 0.7, 500 ms).
- **Paused signal**: pause/resume button scales up to 1.3× (150ms `ViewPropertyAnimator`) in PAUSED, back to 1.0 otherwise — no text indicator.
- **Processing feedback — zero toasts**: on PROCESSING the quick settings (spinner/divider/Raw) become `GONE` and `ime_stage_text` becomes visible in their place, updated by the existing `Event.StageChanged` callback ("Transcribing (STT)…" / "Polishing (LLM)…"). Because Raw mode makes the pipeline single-stage (`TranscriptionPipeline.kt` early-returns before the LLM call), the inline text automatically shows only one stage in Raw mode — fixed two toasts would have misstated the stage count. Success = transcript committed + IME hides; failure = existing error toast; foreground notification remains the fallback when the IME is hidden.
- **Reason**: user change request — remove the "Recording…"/"Paused" text line, move the gear to the left of row 1, and signal recording by flashing the whole bar. The inline-stage idea was the user's own suggestion (re-use row 1's space during processing).
- **Considered**: toast feedback (rejected — user chose inline stage text over one/two toasts); background-color animation between two explicit colors (rejected — root alpha is simpler and theme-safe); stage text as a separate always-visible row (rejected — that's the three-line layout being removed).
- **Tradeoff**: PAUSED is only signaled by the enlarged resume icon (no text); PROCESSING only by disabled buttons + stage text. The animator holds the root view — cancelled in `onDestroy()` (input view recreation creates a fresh one). View alpha dims children too, which matches "the entire IME flashes".

## 2026-08-31 (round 2): Voice-reactive RMS-driven pulse (#57) [partially superseded by round 3]
- **Choice**: The fixed-cycle flash is replaced by a microphone-loudness-driven pulse. `Event.RmsChanged` (raw 16-bit RMS per ~40–80 ms buffer, emitted continuously while RECORDING, previously ignored) feeds `RmsAlphaMapper` (pure object, unit-tested): EMA smoothing (weight 0.3), log-scaled normalization against `RMS_CEILING = 2500`, mapped to root alpha `0.5 + 0.5·level` (floor 0.5 = deep gray on silence, 1.0 = full contrast on loud speech). The IME chases the target with `View.animate()`: slow dive / quick rise, deadband skip; `View.animate()` reuses the per-view animator and starts from the current value, so frequent RMS events chase smoothly without flicker.
- **Reason**: User feedback: the fixed pulse was "too frantic" and felt like superficial tinkering; wanted solid visual feedback. Voice-reactive gives cause-and-effect.
- **Outcome / superseded in part**: on device the bar sat at ~0.8–0.95 the whole recording — a live mic never reads RMS 0 (ambient noise ~50–200), so the log map from zero parked everything near the top; the 0.5 floor was mathematically unreachable. Fixed in round 3 (noise gate + breathing baseline); see PITFALLS.

## 2026-08-31 (round 3): Hybrid pulse — breathing baseline + gated RMS boost (#57)
- **Choice**: While RECORDING the displayed alpha is `max(breathAlpha, voiceAlpha)`. (1) **Breathing baseline**: repeating `ValueAnimator` on a `breathAlpha` field, `BREATH_CEIL 0.9 ↔ BREATH_FLOOR 0.6`, 1000 ms per direction (2 s cycle), ease-in-out — the bar gently breathes during silence instead of sitting static. (2) **Voice layer**: `RmsAlphaMapper` now gates ambient noise (`NOISE_FLOOR = 200f`, aligned with `AudioRecorder`'s speech-begin threshold) before log-normalizing to `RMS_CEILING = 2500`, so silence → exactly 0.5 and speech (400–2000+) → 0.84–1.0. `applyTargetAlpha()` chases `max(breath, voice)` via `View.animate()` (rise 150 ms / dive 600 ms, deadband 0.015 — smaller since breathing needs continuous updates). Both animators reset/cancelled on non-RECORDING states and in `onDestroy`. Throttled `Log.d("PolishedRMS")` diagnostics (every 10th event) for logcat calibration of `RMS_CEILING`; to be removed after calibration.
- **Reason**: On-device round-2 failure (bar stuck near full contrast — see round-2 outcome) plus user request for "flashing *additional* to the loudness feedback". User picked the breathing band 0.6 ↔ 0.9 from three options.
- **Considered**: Deeper breathing 0.5 ↔ 0.95 (rejected — speech indistinguishable at breathing peaks); subtler 0.7 ↔ 0.9 (rejected — user wanted visible idle motion); adaptive noise floor (deferred — static 200 gate first, logcat data decides).
- **Tradeoff**: Breathing dominates silence entirely (voice floor 0.5 < breath floor 0.6); speech is distinguishable from all but the breathing peak. Two animators coordinate via the `max()` composition, not the view — the breathing animator never touches the view directly. Tuning knobs: `RMS_CEILING` (mic sensitivity), `NOISE_FLOOR` (room noise), breath band + periods.

## 2026-08-31 (round 4): RMS NaN root cause + single-animator pulse + quick settings during recording (#57, #56)
- **Choice**: (1) Fixed `AudioRecorder.computeRms` — 16-bit LE samples were assembled unsigned (0..65535); squares of values > 46340 overflowed `Int` → negative sum → `sqrt(negative) = NaN` for any normal speech. Now sign-corrected (`sample ≥ 32768 → sample − 65536`) + `Long` accumulation, extracted as testable `computePcmRms` with 7 regression tests. (2) IME pulse is now **single-owner**: the breathing `ValueAnimator` writes `root.alpha = maxOf(breathAlpha, voiceAlpha)` directly every frame; RMS events only update the `voiceAlpha` field; `!rms.isFinite()` guard; the deadband/`View.animate()` chase was removed (its restart math crawled on descents even with finite values). (3) #56: quick settings (spinner + Raw) are now **enabled during RECORDING** (were alpha-0.4-disabled); PROCESSING stays locked.
- **Reason**: Round-3 on-device evidence — `logcat PolishedRMS` showed `rms=NaN` on nearly every event while `breathAlpha` oscillated; `maxOf(breath, NaN) = NaN` froze the view alpha. The NaN generator predated #57 (latent since AudioRecorder was written — RMS was never consumed before). The user's "hunt for a bigger issue" instinct was correct. The chase-architecture removal was second-order hardening. Enabling quick settings during recording implements #56's main request (toggle RAW mid-recording); the pipeline reads settings at transcription time so changes take effect.
- **Language evidence (NO prompt changes — user decision)**: all 10 logged `llm-prompt.json` files contained the target-language clause (selection persistence is fine; no spinner clobbering). Compliance was intermittent (7/10 correct: Japanese/English/Persian/Urdu/one Polish; 3 failures all German-source dictations → German out). A directive *spoken inside the dictation* always worked. Deferred: directive-in-user-message mitigation (known strong lever), model A/B via Settings.
- **Tradeoff**: Commit hygiene note — the #56 one-liner landed inside the #57 commit `8d56d20` (same file as the pulse fix; splitting would have required force-push). Breathing + voice still compose via `max()`, so at breathing peaks speech is momentarily indistinguishable. RMS calibration via logcat still pending a live dictation.

## 2026-08-31 (round 5): Clause wording hardened + gear divider; user-message purity locked (#56, #57)
- **Choice**: (1) Default Target Language Clause reworded — "Please produce the output in {{target_language}}." → "IMPORTANT: Write your output in {{target_language}}, regardless of the language of the transcription." (resolves into the system prompt's last line via `{{target_language_clause}}`; position unchanged). (2) IME row 1: vertical divider between gear and language spinner (mirrors the spinner|Raw divider). (3) RMS diagnostics kept temporarily (owner decision).
- **Reason**: Log evidence: clause present in 10/10 prompts but compliance flaky (7/10; failures clustered German-source → German out); owner observed gpt-oss-120b never makes these mistakes → model-capability-dependent; owner chose wording hardening (option C) over user-message injection (A) or prompt-restructuring (B).
- **Locked design principle**: the LLM user message stays **exactly the freshly transcribed text** (`{{text}}`) — clean split of duties, owner-affirmed. User-message injection is permanently rejected as a language-compliance lever.
- **Tradeoff**: C is the weakest of the three levers (wording only, same position) — if flakiness persists, remaining levers are model A/B via Settings (strongest evidence: gpt-oss-120b) and, last, position changes within the system prompt. A stored clause/system-prompt override shadows the new default (restore-defaults path documented on #56).

## 2026-08-31: Verification-round fixes — hint task affinity, rows-only pulse, explicit label select, theme-aware IME icons (#59, #48/#53)
- **Choice**: (1) `SettingsHintActivity` gets `android:taskAffinity=""` — after OK the user returns to the previous app instead of the stale Settings task. (2) The IME pulse animates only the two foreground rows (`ime_row_top`/`ime_row_buttons` alpha), not the root view — root background static. (3) The Settings target-language dropdown commits selection via an explicit label click listener (`settings.targetLanguage` + `setText(item, false)` + `dismissDropDown()`), not the popup ListView's internal item click. (4) IME cancel/pause/send icons get `android:tint="@color/recording_icon_tint"` at the usage site (drawables untouched — `VoiceRecognitionActivity` reuses them on colored buttons).
- **Reason**: (1) NEW_TASK attached the hint to the app task whose root was a still-alive SettingsActivity → finishing the dialog dumped the user INTO Settings mid-dictation (the circular trap the hint warns about). (2) Root-alpha pulse looked like a pulsing background in light mode but invisible in dark (white-on-dark vs black-on-dark) — owner chose "pulse icons only". (3) On-device: label taps on custom rows (visible trash) were swallowed by the popup ListView; built-ins worked — owner-verified twice. (4) Bottom icons were hardcoded white → invisible on light-mode overlay.
- **Considered**: (1) owner's original proposal: gear short-tap no-op + long-press tooltip (rejected — kills in-IME discoverability, the rationale for keeping the gear in #51); removing the gear. (2) pulsing a foreground-only overlay drawable.
- **Tradeoff**: (1) one extra task for the hint (invisible — `excludeFromRecents`). (2) pulse no longer dims the bar background. (3) selection bypasses `AutoCompleteTextView`'s internal replaceText path — duplicated commit logic lives in the adapter. (4) none.

## 2026-09-07: Keyboard-switch button redesign — history-resolved target + settings fallback (#65)
- **Choice**: The switch button means *"give me a keyboard with keys"*. Tap: pause if RECORDING → resolve the target ourselves via `SwitchTargetPolicy`: parse `input_methods_subtype_history` (most-recent-first), pick the first entry that is enabled, has keys (non-auxiliary subtypes, or an IME with zero subtypes), and isn't Polished → `switchInputMethod(target)`. No such keyboard → open `Settings.ACTION_INPUT_METHOD_SETTINGS` (the enable-keyboards screen). Safety net: fresh `default_input_method` read (direct provider query, bypassing the in-process Settings cache) before/after the switch — unchanged → open the settings screen. The picker fallback (`showInputMethodPicker`) is gone.
- **Reason**: On-device smoke test proved `switchToPreviousInputMethod()` unusable as the decision point: it resolves "last" from the subtype history whose head reverts to Polished after **every** settings touch (IME enable/disable, even via adb) → self-targeted silent no-op switch → returns `true` → any `on false` fallback is dead code (IMMS logs "Illegal subtype state: old subtype = null" because our IME never sets a current subtype). And `showInputMethodPicker()` was useless in the fallback state anyway (lists enabled IMEs = Polished itself). Owner spec: "entweder zurückspringen auf das letzte Keyboard mit Tasten; wenn das ich selber bin, soll die Tastatur-Systemeinstellungen aufgehen — der User will eine Tastentastatur, und wenn keine in der History ist, muss er in den Aktivieren-Schirm".
- **Considered**: Keep system resolution + post-check only (insufficient — the self-target trap fires in the common case, right after any settings change); read-only `enabledInputMethodList` check without history (misses "last used" semantics when several keyboards are enabled); hidden-key reads are accepted (history + `default_input_method` as string literals — reads of Settings.Secure are unrestricted; only the post-check needs a fresh provider query).
- **Tradeoff**: Relies on two hidden Settings.Secure keys (`input_methods_subtype_history`, `default_input_method`) — stable platform surface since API ~11, but undocumented. Enabled-but-never-used keyboards are not in the history → button opens the settings screen (owner-approved). 160 unit tests green; verified on S5 (settings fallback + direct switch) and installed on both test devices (S5, OnePlus 7T).

## 2026-09-07: Pulse dives to alpha 0.15 with 15% floor dwell (#69)
- **Choice**: Both pulse floors 0.45 → **0.15**: `RmsAlphaMapper.ALPHA_FLOOR` (voice layer) and `BREATH_FLOOR` (breathing layer) — lowered together because the displayed alpha is `max(breath, voice)`; a lone breath floor below the voice floor would just clamp flat at 0.45. `startBreathing()` reworked from `ofFloat(CEIL, FLOOR)` + REVERSE (1 s halves) to a **keyframe cycle** (2000 ms, infinite): fall 42.5% → **dwell 15% of the cycle at the floor** (300 ms) → rise 42.5%, `AccelerateDecelerateInterpolator` retained. Test speech thresholds re-based on the 0.15 floor (400→≥0.7, 800→≥0.8, 2000→≥0.95).
- **Reason**: Owner: "das Pulsieren geht nicht tief genug in den low-contrast Bereich hinein". Two causes: (1) floors at 0.45 too shallow; (2) perceptual — the old animator *touched* the floor only momentarily (ease-in-out, zero dwell time), so even the existing depth was barely visible. Owner confirmed the 0.15 depth is safe because speech lifts `voiceAlpha` to 0.8+ instantly via EMA — the deep range exists only during silence, by design.
- **Considered**: Keeping `ALPHA_FLOOR` at 0.45 (rejected — would mute the new depth); adaptive noise floor for the known gate-clamp problem (deferred again, see Tradeoff); alpha 0.30/0.35 (owner explicitly picked 0.15).
- **Tradeoff**: Static `NOISE_FLOOR = 200` gate stays: in a louder room (ambient RMS 250–600) `voiceAlpha` parks at 0.58–0.81 and the 0.15 depth is unreachable — the round-3 deferred "adaptive noise floor" is the prepared lever if device testing shows this. At 0.15 the rows are ~85% transparent (near-invisible) at the floor dwell — owner-accepted. Implemented via `PropertyValuesHolder.ofKeyframe` + `getAnimatedValue("breathAlpha")` (ValueAnimator has no direct keyframe overload). Verified: tests green, assembleRelease green, installed on OnePlus (S5 not connected — on-device feel check pending).

## 2026-09-07: Prominent Gboard-hardcoded-mic warning + EN-only docs (#70)
- **Choice**: README gets a **prominent callout directly under the intro** ("Gboard will never use this.") plus a sharpened "Works everywhere" bullet (delegation mechanism = system voice-input service + explicit Gboard exception) and a Troubleshooting bullet. INSTALLATION.md de-Germanized (whole German section removed — **all docs EN-only**, owner decision); keyboard recommendations per owner: **AOSP keyboard (LineageOS)** added first ("cool"), **HeliBoard marked "(F-Droid only)"**, **AnySoftKeyboard dropped** ("very old") — the `RECOGNIZE_SPEECH` intent support itself stays (app capability), only the stale example was neutralized. Samsung Keyboard hardcoding also noted.
- **Reason**: Owner: "im README möchte ich prominent sehen, dass das Mikro von Gboard NIE ein externes voice-IME nutzt". Verified via FUTO Voice Input README ("Incompatible keyboards: Gboard — hardcoded to use Google's voice input, does not support third-party options"; Samsung Keyboard likewise; delegating: HeliBoard, FlorisBoard, AnySoftKeyboard, Unexpected Keyboard, AOSP/LineageOS). README previously claimed delegation with no exception; INSTALLATION.md had it only buried in Troubleshooting.
- **Considered**: German variant of the new note (rejected — owner: "alles EN-only", extends to removing the INSTALLATION.md German section); FlorisBoard/Unexpected Keyboard in the README list (kept out — owner only endorsed AOSP/HeliBoard/Fossify/OpenBoard).
- **Tradeoff**: README gains one callout block (visual weight above the hero screenshots); INSTALLATION.md loses its German audience — deliberate owner call. Issue #70 closed same-session (commit `4c70c45`).

## 2026-09-07: Whitespace padding around committed text (#66)
- **Choice**: `InsertionSpacingPolicy` (pure object, unit-tested) pads the transcription before `commitText`: a **leading space** when the cursor sits at field start (`before` null/empty) or after non-whitespace (letters, punctuation `.,?;)`), a **trailing space** when no whitespace follows (`after` null/empty = field end). The IME reads the one-char neighbors via `getTextBeforeCursor(1,0)`/`getTextAfterCursor(1,0)` in the new `commitWithSpacing()` and applies the policy before committing.
- **Reason**: Issue #66: text inserted right after sentence punctuation glued onto the sentence ("bar.foo" instead of "bar. foo"); owner requested symmetric trailing padding ("am Ende … wenn danach kein Leerzeichen vorhanden ist"). Owner-confirmed rules: any whitespace counts (newline/tab/NBSP suppress padding); `null` neighbors (some apps don't support the text read) count as "no whitespace" → space still added — a missing separator is worse than a doubled one; whitespace already inside the transcription itself suppresses padding (no doubling).
- **Considered**: New issue for the extended scope (rejected — open #66 already covered the leading-space-before-punctuation core, extension documented in its implementation comment); restricting to real U+0020 neighbors (rejected — a newline before the insertion point would then get an extra space).
- **Tradeoff**: Padding operates on 1-char windows only (a multi-space run is fine — first char decides); NBSP needed an explicit union with `Character.isSpaceChar` because `isWhitespace()` misses it (see PITFALLS). Feel-check on device: **passed 2026-09-07** — rules are now the owner-verified baseline in `DOMAIN.md` ("evolving — refine from longer use"); issue #66 closed.

## 2026-09-07: Gear opens Settings via keyboard switch (#72) — supersedes the #51 gear-hint
- **Choice**: The IME gear no longer shows the "open from launcher" hint. Tap: pause if RECORDING → resolve the target key keyboard via the same `SwitchTargetPolicy` logic as the switch button (extracted into shared private `resolveKeyKeyboardTarget(selfId)` in `PolishedVoiceInputIME`) → **null** → open `Settings.ACTION_INPUT_METHOD_SETTINGS` (Polished settings NOT opened — nothing to type on) → otherwise `startActivity(SettingsActivity, FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP)` **before** `switchInputMethod(target)` (launch-first: the switch destroys the IME service, #65 pitfall; `CLEAR_TOP` prevents Back from landing on a stale launcher-Settings instance, #59 pitfall).
- **Reason**: #51's hint existed only because the voice IME made Settings' EditTexts untypable; #65's verified switch mechanism removes that blocker — switch to the text keyboard first, then Settings is fully editable. Owner decisions: **return path = nothing automatic** (no picker, no WRITE_SECURE_SETTINGS — user returns manually via nav-bar switcher/globe key; a fully automatic return is impossible without WRITE_SECURE_SETTINGS because only the *current* IME can `switchInputMethod` and the Polished IME service is destroyed by the outbound switch); settings closed other than back/save (home swipe, recents) → do nothing.
- **Considered**: IME picker on back/save (rejected — extra tap, owner wants nothing automatic); auto-return via adb-granted `WRITE_SECURE_SETTINGS` + `Settings.Secure.putString` on `default_input_method` (rejected — one-time per-device setup, Oplus blocked adb settings-writes); opening Polished settings without switching when no key keyboard exists (rejected — untypable fields).
- **Tradeoff**: Returning to Polished after settings is manual (2 steps via keyboard switcher); a paused dictation survives the whole detour app-scoped and auto-resumes on Polished's re-entry (#65 mechanics). `SettingsHintActivity` + hint strings stay for the mic-notification `contentIntent`. Owner smoke test on the OnePlus 2026-09-07: **PASS** (switch+settings+edit+back+auto-resume+notification-hint); "no key keyboard" fallback not exercisable there (identical to the verified switch-button path), S5 not retested — owner-accepted. Issue #72 closed.

## 2026-09-07: No leading blank at genuine field start (#73) — refines the #66 spacing rule
- **Choice**: `InsertionSpacingPolicy` splits the leading-space condition that #66 shared between field start and unreadable neighbors: `before` == **empty string** (genuine field start, position 0) → **no** leading blank; `before` == **null** (app does not support `getTextBeforeCursor`) → conservative leading blank stays. Trailing rule unchanged (field-end trailing space stays, `after` null/empty → space).
- **Reason**: Owner report after #72: dictating at position 0 in Markor inserted one leading blank too many — an empty field should not get an indent. `getTextBeforeCursor(1,0)` returns `""` at position 0, and the old `before.isNullOrEmpty()` branch treated it like "no whitespace present". Owner explicitly chose to keep the blank for the null case (#66 rationale: a missing separator mid-text is worse than a doubled space).
- **Considered**: Dropping the leading blank for null too (rejected — apps without text read would glue dictation onto the previous word); test-first-in-Markor-before-deciding-null (rejected — owner decided upfront).
- **Tradeoff**: An app that returns `""` for unreadable neighbors (instead of null) would now lose the leading blank mid-text — unknown such app; the 1-char read is the standard InputConnection contract. Policy + 19 tests updated together; DOMAIN.md rule text updated. Owner feel-check (Markor on OnePlus): **passed 2026-09-07** — field start clean, mid-text separators intact, trailing preserved. Issue #73 closed.

## 2026-09-20: IME UI batch — 1.5x lower row, no REC prefix, delete-word button, outline trash, no pause-enlarge (#90, OPEN)
- **Choice**: Lower-row buttons 48dp→72dp + `scaleX/Y=1.5` on all five (layout bounds unchanged); new `ime_delete_word_button` (⌫ `ic_backspace`) directly left of the trash — tap deletes trailing-ws + last token via pure `service/DeleteWordPolicy` (same whitespace predicate as `InsertionSpacingPolicy`), long-press clears the whole field (select-all + empty commit in batch edit, 9999-char surrounding-text fallback when no extracted text); always enabled in every state; no press-hint (long-press taken, locked by test). Flush uses new stroke-based `ic_delete_outline` (solid `ic_delete` untouched — shared with Settings dialogs). `setPausedEnlarged()` deleted entirely. `RecTimeFormatter.recording()` returns plain `m:ss`.
- **Reason**: Owner UI requests; pulse already signals recording (REC redundant); trash solid too dominant; pause-enlarge broke button equality.
- **Tradeoff**: 1.5x via view scale (not larger vectors) — ripple scales too, accepted. 261 tests green (8 new), `assembleRelease` green. Open: owner on-device visual feel-check.

## 2026-09-20: #90 follow-up — outline backspace + Settings trash, solid ic_delete deleted
- **Choice**: `ic_backspace` redrawn as stroke-based outline (same 1.6dp round stroke pattern as `ic_delete_outline`); both Settings trash usages (`item_manage_language.xml`, `item_language_dropdown.xml`) switched to `ic_delete_outline`; now-unreferenced solid `ic_delete.xml` deleted.
- **Reason**: Owner follow-up — backspace solid was as dominant as the trash had been; Settings trash cans match the IME flush icon now.
- **Tradeoff**: None — colors/tints unchanged, only fill→stroke. 261 green + `assembleRelease` green. Still open: owner visual feel-check before `finish` #90.

## 2026-10-09: Settings-detour IME restore evaluated and rejected — manual return stands (#72)
- **Choice**: No code change. An automatic "switch back to Polished on Settings exit" was evaluated and rejected: (a) it is impossible with public APIs — only the currently bound IME may switch (IMMS token check); after the gear-tap `switchInputMethod(target)` Polished is unbound, so neither the IME instance nor `SettingsActivity` holds a token (`InputMethodManager.setInputMethod` / `switchToLastInputMethod` all require the CURRENT IME's token); (b) the semi-automatic `showInputMethodPicker()` on Settings exit was explicitly rejected by the owner — it would add an extra click, exactly what he does not want.
- **Reason**: Owner report of the practical flow: after Save/Back from Settings he lands in the dictation target app with HeliBoard (the key keyboard Polished switched to) still active — and HeliBoard's mic key re-invokes Polished via the auxiliary voice subtype (#46), where `ImeStartDecision` auto-resumes the paused app-scoped session (#83). The manual return is therefore a single, familiar tap, not friction worth an API workaround.
- **Tradeoff**: The #72 decision ("return to Polished is manual — nothing automatic") is reaffirmed. Framing (owner): this is an **Android-infrastructure limitation**, not a product preference — our decisions (manual return, no picker) are deliberate choices WITHIN what Android enables, not concessions. Revisit only if the platform ever exposes a public switch-back API.


**Also stored elsewhere** (ambiguous placement, cross-referenced per #206):
- [session-lifecycle.md](session-lifecycle.md) — „2026-11-20: #174/#177 — Post-Send ist ein UI-Parkzustand, kein Controller-State"
- [session-lifecycle.md](session-lifecycle.md) — „2026-11-19: #165 — the auto-start waits for the first IME frame, and a blocked mic aborts the take"
- [session-lifecycle.md](session-lifecycle.md) — „2026-10-10: #118 Phase A shipped — insertion breadcrumb + tagged deliveries (`99b2e46`)"
- [audio-pipeline.md](audio-pipeline.md) — „2026-10-08: #116 Phase 1 shipped — no IME field rewrite (Option C), dedicated latency stream, failure-cache retry semantics"
- [workbench.md](workbench.md) — „2026-09-30: Dictation target recorded at dictation start (#102 amendment)"
- [audio-pipeline.md](audio-pipeline.md) — „2026-09-01: Compression stage feedback in the IME bar (#60)"
