---
type: Decisions
title: Decisions — Session Lifecycle
description: Recording state: pause, resume, cancel, auto-start, rotation, the post-send park, the journal and the recovery path.
tags: [knowledge, decisions, session-lifecycle]
generated: { by: opencode/knowledge-persistence, at: 2026-11-28T10:15:00Z }
verified: { by: opencode/knowledge-persistence, at: 2026-11-28T10:15:00Z }
sources: ["#219", "#218", "#217"]
status: stable
---

# Decisions — Session Lifecycle

Moved out of `DECISIONS.md` in #206; entries are verbatim. New decisions in this area are appended here.

## 2026-11-28: #219 — Cross-App-Leftovers werden automatisch zu Workbench-Diktaten (Keep-Checkbox)

Owner-Neuinterpretation von #217: ein App-Wechsel-Leftover ist KEIN Failure — die
incomplete-dictation-UI (Banner, Leftover-Page, Restore/Share/Dismiss) war für ihn
eine ungewollte Last. Neue Regel: eine Advanced-Checkbox `cross_app_keep`
(„Keep dictations discarded by an app switch in the Workbench", Standard AN) entscheidet,
ob ein fremd-app-Leftover überhaupt gesichert wird.

- **ON:** beide Sicherungs-Zweige bestätigen mit EINEM Toast mit App-Namen
  („Dictation from <app> saved to the Workbench"); das App-Label kommt über
  `SettingsStore.appDisplayName` (known-apps-Label → PackageManager-Label → Paketname,
  `ime_leftover_unknown_app` als Fallback) und ersetzt die #217-Dauerangabe.
- **OFF:** gar keine Sicherung (pre-#217-Discard) und beim JEDEM frischen Diktat-Zustand
  ein Toast, der die echte Checkbox-Benennung zitiert — der erste Wortlaut nannte eine
  nicht existierende Einstellung (review-fix `9bb3cb9`).
- **Zombie-Löschung ist Owner-gesegnet (Orchestrator-Entscheidung):** eine COMPLETED
  Journal-Session ohne gespeicherten Text wird GELÖSCHT (nicht nur ausgeblendet) —
  der Text war längst zugestellt, das Audio hat keinen wiedererlangbaren Wert; eine
  nur versteckte Zombie-Session würde Banner/Page-Navigation weiter belasten. Log-Zeile
  `prune: zombie session …` unter dem Tag `WorkbenchActivity`.
- **Der Hint bleibt zweiwegig** (COMPLETED vs. Failure-Arten): die When-Line über dem
  Hint nennt den konkreten Grund; der Hint ist Handlungs-Guidance.
- **Toast-Dauer 5,5 s bleibt** (#207-Lesbarkeitsentscheidung; „one brief toast" meint
  die Ein-Toast-Regel, nicht die Dauer).
- Dokumentiert, nicht gefixt: `releaseToRecovery()` lässt bei einem fortgesetzten Take
  den Preparer-Worker-Thread im Idle-Poll (50 ms) bis zum nächsten frischen Take leben —
  er kann nichts journaled (Transcriber gecancelt, Kanal zu) und heilt sich beim nächsten
  `startFragmentPreparation`.
- Wellen: `40ddfdc` (Keep-Checkbox + App-Namen-Toasts), `5c766a4` (Zombie-Pruning +
  reason-aware Hint), `7bcbcca` (owned Toast-Strip, #222), `a4d4890` (racy-Test-Fix),
  `9bb3cb9` (review-fix). Tests: `ImeLeftoverPolicyTest` +1 Matrixfall, `ImePauseEntryTest`
  +2; 876 `:app` grün, lint 0, Release auf BEIDEN Geräten (2026-11-28).

## 2026-11-28: #218 — Pause korrigiert nur die Notification, nie das FGS

Owner-Screenshot-Befund: pausierter Take (0:05), aber „Listening… · 2m"
und das Mikro-Icon in der Statusleiste. Die Ursache war ein reines
Anzeige-Loch (PAUSED fiel in beiden `StateChanged`-Handlern in
`else -> Unit`), die Verlockung „Notification weg in der Pause" wäre
falsch: **der #83-Pitfall bleibt die harte Randbedingung — das Mic-FGS
wird nur bei IDLE released**, weil Oplus den Prozess ohne es reaped, während
die pausierte Session nur im RAM lebt. Die Entscheidung: die Anzeige wird
umgeschaltet (Text `paused_notification` + `ic_notification_pause`), das
FGS bleibt. Nebeneffekt, der den Fix trägt: Pause/Resume ist jetzt ein
echter Textwechsel, also ein ehrlicher #118-Churn-Zeitstempel — der
„2m"-Quirk im Pausefall ist mitgelöst.

## 2026-11-27: #217 — der Cross-App-Einstieg sichert den Take, statt zu stallen (Release statt Kept)

Owner-Report (Markor): die Tastatur kam im Pause-Look hoch und blieb dort —
nicht reproduzierbar, länger als 1 s. Die Device-Logs (f6de166c, Episode
2026-11-26) bewiesen den Pfad: der #152-Kept-Zweig
(`kept=audio-without-transcripts`) hielt einen fremden PAUSED-Take fest und
unterdrückte den Auto-Start (eine Episode >1 min, dreimal hintereinander;
das Audio war der 11,976-s-Keyboard-Switch-Rest aus der eigenen Workbench).
Der Owner-Satz als Regel: **„Was wir in die Workbench sichern wollen, ist
immer der rohe Text — beziehungsweise das Audio, wenn noch ein
untranskribiertes Fragment in der Kette vorhanden ist."** Er revidiert
bewusst #113s „in-flight fragments are not counted" für diesen Pfad.

- **`ImeLeftoverPolicy`** (rein, neben `AutoStartPolicy`): Transkripte UND
  kein untranskribiertes Audio → `SAVE_TEXT_AND_CANCEL` (roher Text per
  `DictationStore.append` in die Werkstatt, dann der unveränderte #113-Cancel:
  Clipboard + Toast, Journal geht — nichts Untranskribiertes bleibt).
  Untranskribiertes Audio (Queued-Backlog, PENDING im Journal oder
  ungeschnittener Tail) → `RELEASE_TO_RECOVERY`. Kein Audio → `CANCEL_ONLY`
  (verlustfrei wie vor dem Guard).
- **`VoiceSessionController.releaseToRecovery()`**: beendet den Take OHNE
  Zerstörung — `ensureFragmentPreparer` (Restored-Fall, Manifest-Präfix),
  `prepareTailSync` (der Spool-Rest nach dem letzten Schnitt wird letztes
  Fragment), dann JEDES committed Fragment ohne Journal-Record synchron
  PENDING (der Queued-Backlog erreicht die Write-ahead-Schchrift des Workers
  nie; der `readRecord`-Guard verhindert das Downgrade eines DONE). Danach:
  `transcriber?.cancel()`, Session vergessen, Snapshot cleared, IDLE — die
  Journal-Session überlebt als Recovery-Vertrag. `null` (der Take bleibt,
  alter Kept-Zustand), wenn NICHTS journales wurde — das Release wird nie
  zum stillen Verlust.
- **IME-Seite**: Frisch-Start SOFORT in jedem Zweig (die Tastatur kommt nie
  mehr im Stillstand hoch); Toast (#207, 5,5 s, „Leftover take (0:12) saved —
  the Workbench will transcribe it") WÄHREND das neue Diktat schon recordet;
  `kickRecovery` → `RecoveryCoordinator.startManual(releasedSid)` — die
  Out-of-band-Transkription startet im Cross-Moment und weicht an
  Fragmentgrenzen dem Live-Diktat aus (#185-Verdrahtung); `onFinished` bleibt
  im Besitz des sichtbaren Screens, der IME setzt es nie.
- **Duplikat-frei konstruktiv**: der Text-Zweig löscht das Journal (nichts zu
  recovern), der Release-Zweig appendiert NICHT — die Recovery erzeugt EINEN
  vollen Rohtext-Eintrag, inklusive des provisorisch in die Ursprungs-App
  gestreamten Texts (das ist derselbe Transkript-Pool, kein eigener Vorrat).
  Die Commit-Grenze („sofern nicht in die Ursprungs-App committed") ist die
  Existenzbedingung des Pfads: ein committed Take existiert nie als
  PAUSED-Leiche.
- **Unverändert**: der Mistkübel (`flush` + #113-Clipboard-Salvage), das X
  (pausiert + wechselt, #155 — es verwarf nie; der Owner las es als Verwerfen
  — genau diese Fehllesbarkeit war der zweite Befund), `ImeStartDecision`,
  die #185-Recovery-Semantik. Glyphen-Tausch: der Switch-Button trägt wieder
  das #65-Tastatur-Icon; `ic_close` war nach dem Tausch tot und ist entfernt
  (#195-Regel).
- **Tests**: `ImeLeftoverPolicyTest` (3), `VoiceSessionControllerTest` +2
  (Release hält Journal; kein Downgrade eines AUTH-PENDING), `ImePauseEntryTest`
  (Release + frischer Start + Kick + Trace; kein-Audio-Cancel),
  `ImeSwitchGlyphTest` (2, statischer XML-Guard). Suite + `lintDebug` grün,
  Release auf der A03 installiert; der OnePlus bekommt es beim nächsten
  Anstecken. Known probe bias: `untranscribedAudioPending()` einem
  restored-ohne-Preparer-Take gegenüber über-approximiert (ganzer Spool) —
  die sichere Richtung.

## 2026-11-24: #201/#204 — die Leiste ohne Aufnahme ist ein ganz normaler Pausezustand bei 0:00

Owner-Report: nach dem Senden stand rechts neben der Checkbox „Sent — ↺ starts a
new take" (bzw. „↺ starts a new take" im frischen IDLE). „Das brauche ich
überhaupt nicht. Es soll ein ganz normaler Pausezustand bei 00:00 Minuten sein."

- **Entscheidung: keine Stage-Zeile ohne Aufnahme.** `applyUiState` zeigt die
  Stage-Zeile nur noch in `PROCESSING` (dort schreiben die Events sie). In `IDLE`
  ist sie GONE — der Timer (`0:00`) ist der einzige Text. Die Strings
  `ime_no_take`/`ime_post_send_parked` sind gelöscht (lint hätte sie als
  unbenutzt gemeldet).
- **Unverändert:** die Form der Leiste (Send disabled, ↺ live mit
  `ime_new_take_desc`, Gear/X live, Timer sichtbar auf `0:00`) und die Wirkung des
  Parks (#177: unterdrückt den Auto-Start nach einem Send). Der Unterschied
  zwischen „gerade gesendet" und „noch nie gesendet" ist damit ausschließlich
  unsichtbar — er lebt nur noch im Flag.
- **Warum kein Ersatztext.** Eine Erklärung, was ↺ tut, ist eine dritte Aussage
  über einen Zustand, den der Nutzer als Pause liest. Die Bedeutung trägt der
  Content-Description („New take") für Screenreader; auf dem Schirm bleibt die
  Pause eine Pause. Bewusst in Kauf genommen: ein frischer IDLE und ein frisch
  geparkter IDLE sind pixelgleich — unterscheidbar nur durch das Verhalten
  (kein Auto-Start nach dem Send, Feldwechsel beendet den Park).

## 2026-11-24: #198 — ein Throwable im Capture-/Worker-Thread parkt PAUSED, es killt den Prozess nicht

Aufgefallen über die Test-Suite: ein Fork starb mit `exit value 2` und **ohne**
Testfehler. Ursache war kein Heap-Problem (das behauptete der Kommentar in
`build.gradle.kts` seit #86/#131), sondern der App-eigene, JVM-globale
`Thread.setDefaultUncaughtExceptionHandler` in `PolishedRecognitionApp`, der mit
`Process.killProcess(...)` + `System.exit(2)` endet — in Robolectric lebt die
Application im Test-JVM, also tötet **ein** unbehandeltes Throwable auf einem
Hintergrund-Thread den ganzen Fork, lautlos.

Das Throwable war echt: `IllegalArgumentException: PCM range […] beyond buffer (0B)`,
geworfen vom `FragmentPreparer`-Worker, während `VoiceSessionController.flush()`
den Spool unter ihm truncatete.

- **Reihenfolge:** `flush()` stoppt und joint den Worker **vor** dem Truncaten.
- **Worker/AudioRecorder:** kein Throwable verlässt den Worker-Loop bzw. den
  Capture-Thread; ein Fehler meldet `onCaptureFailure`, und der Controller parkt
  die Aufnahme PAUSED mit ihrem Audio (#84-Park) statt Crash-Dialog + App-Kill.
- **Handler:** loggt das Throwable vor dem Prozessende (`Log.e`) — ein
  Exit-2-Fork ist ab jetzt diagnostizierbar statt stumm.
- **Tests:** `CaptureThreadGuard` stellt in den Live-Take-Klassen den Handler um
  und macht ein gefangenes Throwable zu einem lesbaren Testfehler.

## 2026-11-23: #192 (Nachtrag) — EINE Leiste ohne Aufnahme (geparkt bei 0:00), und ein Cancel zerstört nur, was er hergeben kann

Zwei Owner-Entscheidungen nach dem ersten #192-Commit. Beide sind klein und
ändern nur den „nichts gepuffert"-Zustand und den automatischen Cancel.

### „fresh idle should advance to a parked at 00:00"

Es gibt nur noch EINE Leiste ohne Aufnahme. `State.IDLE` rendert immer die
geparkte Form: Ebene (Send) disabled — es gibt nichts zu senden —, ↺ live mit
`ime_new_take_desc` („New take"), Timer sichtbar und auf `0:00` gepinnt.
~~Stage-Zeile entweder `ime_post_send_parked` oder `ime_no_take`~~ — **die
Stage-Zeile ist seit #201/#204 in IDLE weg** (siehe den neueren Eintrag oben).

- **Warum.** Zwei Looks für denselben Zustand („nichts gepuffert") waren einer
  zu viel, und der frische IDLE las sich als normaler, toter Leerlauf. Die Regel
  dahinter ist die des Owners: **ein Control ist nur dann live, wenn das
  Drücken etwas bewirkt.** Deshalb ist die Ebene in IDLE immer disabled
  (drücken würde nichts senden) und ↺ immer live (drücken startet eine
  Aufnahme). ~~Der Unterschied zwischen „gerade gesendet" und „noch nie etwas
  gesendet" ist nur der SATZ, nicht die Form — der Park-Flag steuert weiterhin
  die Auto-Start-Unterdrückung nach einem Send (#177) und die Stage-Zeile.~~
  **Seit #201/#204 gibt es auch keinen Satz mehr** (Owner: „ein ganz normaler
  Pausezustand bei 00:00"); der Park-Flag steuert nur noch die
  Auto-Start-Unterdrückung nach einem Send (#177).
- **Der ↺-Tap musste mitziehen.** Der Klick-Handler startete bisher nur
  `if (postSendParked)`; im frischen IDLE war ↺ damit live ohne Wirkung — genau
  der Zustand, den die Regel verbietet. Jetzt räumt der Tap den Park-Flag (falls
  gesetzt) und ruft immer `startIfPermitted()`.
- **Kein zweiter Zustand, kein Controller-Wissen.** Der Park bleibt reine
  IME-UI (`service/PostSendParkingPolicy`, Controller ist echt IDLE): PAUSED
  heißt weiter „Audio gepuffert, Resume hängt an" (#67/#84/#134), und ein Park
  dort würde eine bereits gelieferte Diktat-Aufnahme wiederauferstehen lassen.
- **Sichtbare Folge für #192 (b) auf dem Gerät:** eine echte Pause zeigt
  gepufferte Zeit und aktives Send, die No-Take-Leiste `0:00` und eine tote
  Ebene — der Unterschied ist ohne Layout-Änderung lesbar.

### Ein Cancel darf nur zerstören, was er hergibt

Der Fremd-App-Pfad (`AutoStartPolicy.FRESH_START`) cancellte jede PAUSED-Aufnahme
über den #113-Weg. Bei einer aus dem Snapshot wiederhergestellten Aufnahme ist
der `FragmentTranscriber` aber noch gar nicht gelaufen: es gibt Audio, aber
keine Transkripte — `cancel()` löscht dann die Journal-Session, ohne Clipboard
und ohne Toast. Genau die stille Vernichtung, gegen die #152 existiert.

- **Die Frage vor dem Cancel.** `FragmentTranscriber.peek()` ist ein
  NICHT-destruktiver Blick auf das, was `cancel()` zurückgeben würde (`null`,
  solange nichts transkribiert ist); `VoiceSessionController.salvageableText()`
  ist derselbe Blick auf Controller-Ebene (plus `isNotBlank`). Ein zweiter
  Aufruf liefert dasselbe, und der folgende `cancel()` liefert es weiterhin —
  ein konsumierender Blick würde den Guard in genau den Verlust verwandeln, den
  er verhindert (Tests auf beiden Ebenen).
- **Die Regel.** Fremde App + PAUSED + gepuffertes Audio + keine Transkripte ⇒
  **kein Cancel und kein frischer Start**: die Aufnahme bleibt PAUSED (in ihrer
  App, gepufferte Zeit sichtbar, Papierkorb = ausdrückliches Verwerfen), die
  Trace-Zeile lautet
  `pause cause=foreign-app kept=audio-without-transcripts pausedMs=<n>`. Gibt es
  Text zu retten, läuft der #113-Weg unverändert (Clipboard + Toast + frisch);
  hält die Pause gar kein Audio, ist der Cancel ebenfalls erlaubt — er zerstört
  dann nichts.
- **Bewusste Konsequenz:** eine fremde App kann eine pausierte Aufnahme NICHT
  mehr stillschweigend wegräumen. Der Preis ist ein bewusster Tap (Papierkorb)
  oder ein Send in der eigenen App — der einzige Weg, den #152 zulässt.

- **Tests:** `ImeNoTakeBarTest` (3: frischer IDLE = geparkt bei 0:00; ↺-Tap
  startet; ~~nach Send nur die Stage-Zeile anders~~ — seit #201/#204 gibt es
  auch den Satz nicht mehr, Park und frischer IDLE sind pixelgleich),
  `ImePauseEntryTest` umgeschrieben
  (Park vs. Pause mit `0:00`/gepufferter Zeit; fremde App mit Audio ohne
  Transkripte bleibt PAUSED + Trace-Zeile; Pause ohne Audio wird beendet),
  `FragmentTranscriberTest` +2 (`peek` nicht-destruktiv, `peek` null ohne
  Transkripte), `VoiceSessionControllerTest` +2 (`salvageableText` = was der
  Cancel hergibt und konsumiert nichts; null bei Audio ohne Transkripte).
  **Kein Version bump** — die App bleibt 1.5.1 / 10501.

### Owner-Antwort 2026-11-24 — der Post-Send-Look war nie gewollt (Variante „A")

Zur letzten offenen Frage des Issues (muss die Post-Send-Leiste von einer Pause
*unterscheidbar* sein?) kam die Antwort in zwei Schritten. Der Owner wollte
diesen Zustand überhaupt nicht: „ein ganz normaler Pausezustand bei 00:00"
(#201/#204, kein Satz mehr). Und auf die Nachfrage, ob damit nur die **Optik**
gemeint war (A) oder auch das **Verhalten** — also der Auto-Start nach einem
Send nicht mehr unterdrückt werden soll (B) — kam **A**.

- **Konsequenz:** `postSendParked` bleibt unverändert (reine IME-UI, unterdrückt
  nur den Auto-Start; Feldwechsel und Keyboard-Dismiss räumen ihn). Kein Ticket
  für B, keine Code-Änderung.
- **Konsequenz für das Issue:** der Acceptance-Punkt „visuell unterscheidbar" ist
  **ersetzt, nicht umgesetzt** — es gibt nichts zu unterscheiden, weil es die
  „zu tiefe Pause" nicht mehr gibt. #192 ist damit device-verifiziert
  (`f6de166c`, 2026-11-24) und geschlossen.

## 2026-11-23: #192 — Eine Pause gehört zu EINER App: dieselbe setzt immer fort, eine fremde rettet und ersetzt

Eine Pause war anonym: `pause()` setzte PAUSED, und der nächste Bind entschied
„PAUSED + gleiches Feld → weiter" — unabhängig davon, WER pausiert hat. Auf dem
OnePlus des Owners hat das eine Aufnahme zerstört: X (Tastaturwechsel) um 20:17
pausiert, 3,6 Minuten später automatisch fortgesetzt, und der nächste Send trug
das alte Audio mit (`INSERTION length=1125` um 20:23:44; das Audio stammte von
vor 20:17). Die entscheidende Frage ist nicht „ist pausiert?", sondern „gehört
diese Aufnahme in die App, die jetzt die Tastatur bindet?".

- **Die Regel ist die kürzestmögliche: dieselbe Ziel-App setzt IMMER fort.**
  Pause-Knopf, X (#155), Nav-Bar-Schließen, ein verstecktes Tastaturfenster und
  eine nach Prozess-Tod/Reboot aus dem #67-Snapshot wiederhergestellte Aufnahme
  verhalten sich identisch: Rückkehr in dieselbe App → `RESUME` (Audio wird
  angehängt). **Kein Zeitfenster, kein „braucht ↺", kein Sonderfall für
  Restore** — Owner, nach zwei Korrekturen an einem ersten Entwurf mit
  10-s-Fenster: „continue even after ten seconds", dann „always continue, no
  exceptions". Eine verstrichene Zeit darf nie über eine Aufnahme entscheiden.
- **Eine fremde App beendet die Aufnahme über den bestehenden #113-Weg.**
  Anderes Zielpaket → `FRESH_START`: `cancel()` (kopiert bereits transkribierte
  Fragmente in die Zwischenablage + Toast) und eine frische leere Aufnahme.
  Das ersetzt #134s FELD-Vergleich für PAUSED: ein anderes Feld DERSELBEN App
  ist weiterhin „mein eigenes Diktat" und wird fortgesetzt.
- **Der einzige verbleibende Zustand ist das Zielpaket.** Der Controller merkt
  sich beim Pausieren `callerPackage` (`pausedPackage()`), löscht es in
  `resume()`/`cancel()`/`stopAndTranscribe()` und schreibt es in den
  BESTEHENDEN Prozess-Tod-Snapshot (`cacheDir/session.meta`, additiv und
  nullable — ein pre-#192-Snapshot parst weiter und fällt auf das zuletzt
  bediente Feld zurück). Kein Workbench-Eintrag, keine Journal-Session, kein
  weiterer Zustand.
- **Bewusst KEINE `PauseReason`-Taxonomie.** Ein erster Entwurf hatte ein Enum
  (`USER_PAUSE`/`KEYBOARD_SWITCH`/`KEYBOARD_DISMISSED`) plus
  `SALVAGE_AND_FRESH`. Nach der finalen Regel entscheidet der Grund NICHTS
  mehr — „dieselbe App → weiter, fremde App → retten" gilt für jeden Pause-Pfad
  — und ein Typ ohne Entscheidung ist totes Gewicht. Entfernt (Owner: „do not
  keep a taxonomy that carries no decision"). Was bleibt: `AutoStartPolicy`
  bekommt nur noch `(state, sameTarget, permission)` → `RESUME` /
  `FRESH_START` / `NONE`; der Grund steht als reines Label in der bestehenden
  `pause cause=… pkg=…`-Zeile in `ime-lifecycle.log` (kein neuer Stream).
- **Ein verschwindendes Tastaturfenster pausiert eine laufende Aufnahme.**
  Bisher lief sie unsichtbar weiter (gemessen `20:13:36 onWindowHidden
  state=RECORDING`); jetzt pausiert `onWindowHidden`/`onFinishInputView` — aber
  nur ohne Rotationssignal (`configChangeInProgress`, frisches `RotationGate`),
  sonst würde jedes Drehen die Aufnahme anhalten (#83, Oplus-Rebind-Sturm).
  `ImeStartDecision` bleibt unverändert; der verzögerte PROVISIONAL_FREEZE-
  Resume (500 ms) ruft jetzt DIESELBE Entscheidung statt unbedingt `resume()`.
- **Restore ist kein FREEZE-Fall mehr.** `ImeStartDecision` verliert den
  `restored`-Parameter: „always continue, no exceptions" heißt, dass auch eine
  aus dem Snapshot wiederhergestellte Aufnahme beim ERSTEN Bind entschieden wird
  (gleiche App → weiter). Nur die Rotation friert weiter ein — dort ist die
  Session lebendig und der Bind eine Neuerzeugung, keine Rückkehr.
- **Der Post-Send-Park endet mit der Tastatur.** „if after send I close the IME
  with nav, and then tap to edit text, it should just start a new dictation" —
  `PostSendParkingPolicy.Event.DISMISSED` hebt den Park auf (vorher hielt er
  ihn); für die stehende Tastatur bleibt #177 unverändert.
- **Der Park sieht nicht mehr wie eine tiefe Pause aus.** ↺ trägt
  `ime_new_take_desc` („New take"), Send bleibt disabled, der Timer bleibt
  versteckt; eine echte Pause zeigt gepufferte Zeit und aktives Send. Keine
  Layout-Änderung, keine Farbe. (**Am 2026-11-24 durch #201/#204 überholt:** der
  Timer ist seit dem #192-Nachtrag sichtbar auf `0:00`, und eine Stage-Zeile gibt
  es nicht mehr.)
- **Bewusste Konsequenz (Owner-Entscheid, kein Versehen):** eine am Vortag
  pausierte Aufnahme kann an eine viel spätere Diktat-Session in derselben App
  anschließen. Das ist der Preis von „always continue, no exceptions".
- **Tests:** +18 (`:app` 642 → 660): `AutoStartPolicyTest` als Matrix,
  `ImePauseEntryTest` (11: Schließen → gleiche App; fremde App → Rettung +
  frisch; Restore → gleiche App; anderes Feld derselben App; X × Pause-Knopf;
  Rotationssturm; Hide-RECORDING; Park-Ende beim Schließen; Park vs. Pause),
  `VoiceSessionControllerTest` +5 (Ziel gemerkt/gelöscht/Snapshot-Roundtrip),
  `ImeStartDecisionTest` (Restore fällt nicht mehr unter FREEZE),
  `PostSendParkingPolicyTest` (DISMISSED hebt auf). **Kein Version bump** — die
  App bleibt 1.5.1 / 10501.

## 2026-11-23: #118 Phase C — ein Rebind überspringt identische Arbeit, statt sie zu wiederholen

Eine Drehung liefert auf Oplus einen Rebind-**Sturm** (mehrere
`onStartInputView → onConfigurationChanged → onFinishInputView`-Zyklen auf
derselben Instanz; PITFALLS). Die Session übersteht ihn seit #113
(`ImeStartDecision` FREEZE) — sichtbar unschön blieb die ARBEIT pro Zyklus:
`applyUiState` malte jeden Knopf neu, `startMicForeground` betrat erneut
`startForeground`, `updateNotification` schrieb die Notification neu, und
`setFlashing` startete die Atem-Animation des Mic-Knopfs neu — `startBreathing()`
cancelt den `ValueAnimator` und erzeugt ihn neu, die Phase sprang also auf
jedem Zyklus zurück. Das war der auffälligste Koller („far from clean").
Phase C ist reines Koaleszieren: identische Arbeit wird übersprungen. Am
TEXT-Einfügen ändert sich nichts (`commitWithSpacing`, `pendingResult`, die
#113-Salvage und die #186-Rescue-Pfade sind unberührt) und daran, WAS
gerendert wird, ebenfalls nicht — nur ob eine identische Wiederholung läuft.

- **Eine reine Policy, vier Fragen.** `service/RebindChurn` (Android-frei,
  Tabellentest `RebindChurnTest`): `render(previous, next)` über eine
  `UiSignature` (`state`, `postSendParked`, `llmForcedOff` — genau die Eingaben,
  die `applyUiState` liest), `notify(previousText, nextText)`,
  `assertForeground(alreadyAsserted)` und `startBreath(currentlyFlashing,
  nextFlashing)`. Jede Funktion antwortet für eine UNVERÄNDERTE Eingabe `false`;
  übersprungen werden kann also nur die identische Wiederholung, nie eine echte
  Änderung.
- **Der Render hängt an einer Signatur, eine neue View malt immer.** In
  `onCreateInputView` läuft `invalidateUiRender()` vor dem terminalen
  `applyUiState()`, deshalb malt jede neu erzeugte View genau einmal (auch nach
  einer Rotation). Der Preis: beim Skip laufen `updateRecTimer` und
  `updateLanguageEnabled` nicht mit — der Tick postet sich selbst weiter (er
  kehrt nur bei nicht-RECORDING zurück), und `refreshQuickSettings` (jeder Bind)
  ruft `updateLanguageEnabled` ohnehin auf.
- **Der FREEZE-Re-Assert ist jetzt per-Instanz.** `startMicForeground`
  überspringt `ServiceCompat.startForeground`, wenn DIESE Instanz den
  Mikrofon-FGS schon hält (`foregroundAsserted`, gesetzt NACH dem erfolgreichen
  Call, gelöscht im `catch` und in `stopMicForeground`). Die alte Begründung
  („die vorige Instanz kann gestorben sein") gilt genau für eine NEUE Instanz —
  deren Flag startet `false`. Weil ein Assert seine eigene Notification
  mitbringt, zählt er als `fgs` und nie zusätzlich als `notify`.
- **Die Notification folgt dem TEXT, nicht dem Ereignis.**
  `lastNotificationText` (gelöscht in `stopMicForeground`) verhindert den
  identischen Post; der Skip-Pfad des FGS repariert nur den Text — nach einer
  gescheiterten Session stünde sonst „processing" über einer erneuten Aufnahme.
- **Die Atem-Animation wird nur bei echter Zustandsänderung angefasst.**
  `startBreathing()` erhöht `breathStarts`; der Zähler ist der Beweis, dass ein
  Zyklus sie NICHT neu startet.
- **Gemessen im BESTEHENDEN Strom — kein neuer Kanal.** Die
  `onStartInputView`-Zeile in `ime-lifecycle.log` endet jetzt auf
  `churn=render:<r> fgs:<f> notify:<n> breath:<b>`; die vier `internal var`-
  Zähler werden direkt nach dem Log genullt, jede Zeile beschreibt also das
  Intervall, das gerade endete. Eine identische Wiederholung schreibt
  `churn=render:0 fgs:0 notify:0 breath:0`; die erste Zeile nach einer neuen
  View trägt `render:1` (der Pflicht-Anstrich).
- **Abgrenzung.** Eine IME kann die Drehung nicht verhindern (siehe „Rotation
  cannot be blocked by an IME" unten) — Phase C macht den Sturm billig, nicht
  unsichtbar. Phase B ist an #190 übergeben (Nachtrag im Phase-A-Eintrag).
- **Tests:** +14 (`RebindChurnTest` ×11 Tabellenfälle; `ImeRebindChurnTest` ×3 —
  die Trace-Zeile mit `churn=render:0 …` für die zweite Bindung, die
  `fgs`/`notify`-Koaleszenz samt echter Textänderung, und ein ECHTER
  RECORDING-Rebind, der die Atem-Animation nicht neu startet). Suite 1001 grün
  (12 skipped), `lintDebug` sauber, `assembleRelease` grün, kein Versions-Bump.

## 2026-11-22: #186 — ein Raw-Rescue ist KEINE Fertigstellung; ein leeres Rescue wird nie eingefügt

Der Gerätetest des #186-Fluges (Samsung SM-A032F, Flugmodus an, Raw-Modus an,
Release `147d404`) hat den Kern des Features widerlegt: die Session war komplett
offline, alle Fragmente scheiterten — und `runTranscription` lieferte trotzdem
`Result.success("")`. Der Erfolgs-Zweig von `stopAndTranscribe` markierte die
Journal-Session als fertig und löschte sie; die IME fügte einen leeren String ein
und sagte nichts. Weder Workbench noch Toast: das Diktat war weg, obwohl das
Feature „nichts geht verloren" heißt. Vier Regeln, die daraus folgen.

- **Completed + Delete nur ohne Rescue.** `journal.markCompleted(sid)` und
  `discardFragmentSession()` laufen im Erfolgs-Zweig nur, wenn
  `rescuePartial == null` ist. Ein Rescue behält seine Journal-Session (alle
  Fragmente bleiben PENDING mit ihrer aufgezeichneten Fehlerklasse), damit
  `recoverableSessions()` sie als unfertiges Diktat listet und der Grund sichtbar
  bleibt. Der Speicher-Cache (Transcriber/Preparer) fällt wie bisher — die
  Journalkopie ist die dauerhafte, nicht `cacheDir`. Damit ist ein Rescue
  ausdrücklich KEIN `completedAtMs`-Fall, obwohl Text geliefert wurde: geliefert
  ≠ fertig.
- **Der Grund reist mit dem Event.** `Event.PartialInfo` trägt additiv und
  defaulted `failure: SttFailure?` und `message: String?` (beide mit Default,
  jede bestehende Konstruktion kompiliert unverändert). Ein Beobachter kann
  dadurch sagen, WARUM gerettet wurde (OFFLINE vs. 410), statt nur „irgendwas
  fehlt". `deliver()` konsumiert das Feld weiterhin genau einmal.
- **Ein leeres Rescue wird NIE eingefügt — und ist nie stumm.** Die IME fügt
  den Text nur ein, wenn er nicht leer ist, und zeigt IMMER genau EINEN Toast:
  Hinweis der Fehlerklasse (`SttFailureText.resFor`) + technischer Grund +
  `ime_rescue_saved` („The recording is saved in the Workbench — Retry STT
  there."). Kein zusätzlicher generischer Fehler-Toast — das Diktat ist
  gespeichert, nur nicht vollständig transkribiert.
- **Der delegierte Mic-Pfad stimmt zu.** `PolishedRecognitionService` meldet ein
  Rescue mit leerem Text als `error` (Grund über `mapRecognitionError`) statt als
  leeren `results`-Erfolg — und ruft dabei ausdrücklich NICHT
  `controller.cancel()`: das würde über `discardFragmentSession()` genau das
  Journal löschen, das der Rescue gerettet hat.
- **Die Workbench-Retry-STT ist der Fertigstellungsweg.** Ein gerettetes Diktat
  kann nur dort vollständig werden (bzw. nach dem nächsten Netzanlauf
  automatisch), weil die IME-Session mit dem Rescue endet. Der saubere Erfolg
  bleibt unverändert (Completed + Delete) — beide Hälften sind durch
  Controller-Tests gepinnt, nicht nur die neue.

## 2026-11-22: #186 — das Gate liest VALIDATED, der Reconnect ist Vordergrund-only, und ein Offline-Diktat ist ein Zustand, kein Fehler

Ein Diktat, das ohne benutzbares Netz aufgenommen wurde, ist kein Fehlschlag: es
wird nicht gefragt, das Fragment wird als `OFFLINE` festgehalten, die Session
bleibt als unfertiges Diktat mit Grund im Ring — und sie transkribiert, sobald
das Netz zurück ist. Sechs Entscheidungen, alle mit dem Owner abgestimmt
(Datenschutz und Akku: nichts lädt im Hintergrund).

- **Das Gate liest `NET_CAPABILITY_VALIDATED`, nicht „connected".** Ein
  Captive Portal oder ein WLAN ohne Upstream ist verbunden und trotzdem nicht
  benutzbar; VALIDATED ist genau das Signal, mit dem auch das OS routet. Die
  Abfrage (`network/AndroidNetworkStatus`) ist ein reiner Lesezugriff über
  `activeNetwork → getNetworkCapabilities`, jeder `null`-Schritt ist `false` —
  im Zweifel wird gespeichert statt gesendet. Kein neuer Permission:
  `ACCESS_NETWORK_STATE` steht seit immer im Manifest.
- **Ein Ort, zwei Konsumenten.** `SttRequestRunner` und
  `TranscriptionPipeline` bekommen `isOnline: () -> Boolean = { true }` (Default
  hält jeden bestehenden Aufrufer unverändert); `PolishedRecognitionApp` injiziert
  EINEN Lambda, den Live-Pfad, `JournalReplay` und den Legacy-Chunk-Pfad
  gemeinsam teilen. Geprüft wird mit `NetworkGate.shouldAttempt` — eine
  nicht-suspendierende Momentaufnahme, keine Callback-Registrierung pro Fragment
  (siehe PITFALLS).
- **`OFFLINE` ist eine Klasse, kein Fehlertext.** `SttFailure.OFFLINE` schlägt
  jeden anderen Input (kein Request ging raus, also beschreibt kein Status und
  keine Exception die Lage besser); `attemptsFor(OFFLINE) = 1` ist per Vertrag
  unerreichbar und existiert nur, damit die Tabelle total bleibt. Der Runner gibt
  die Klasse VOR jedem Log/Record zurück — ein gated Fragment erzeugt weder
  `stt-upload` noch `stt-latency`, weil nichts rausging. Stirbt eine Verbindung
  MITTEN im Request, wird das Gate erneut gelesen: Netz weg → OFFLINE, sonst
  bleibt es das #183-TRANSIENT.
- **Der Session-Latch zählt OFFLINE mit.** `FragmentTranscriber.blockedFailure`
  kennt jetzt AUTH, BUDGET und OFFLINE: ein 3-Fragment-Diktat offline kostet
  NULL Requests, jedes Fragment landet PENDING mit Klasse `OFFLINE` und seinem
  komponierten Upload im Journal — nichts wird still verworfen.
- **Reconnect ist Vordergrund-only.** Kein Service, kein WorkManager, kein
  Hintergrund-Upload: `network/ReconnectMonitor` (`registerDefaultNetworkCallback`)
  wird von der Workbench in `onResume` gestartet und in `onPause` gestoppt.
  Gefeuert wird nur auf der KANTE (`reconnectFires(validated,
  previouslyValidated) = validated && !previouslyValidated`), weil
  `onCapabilitiesChanged` für dasselbe Netz mehrfach kommt — sonst startet jeder
  Wiederholungs-Callback einen Recovery-Lauf. Die Edge-Regel ist pur und liegt in
  `NetworkGate`, damit Test und Produktion dieselbe Regel benutzen. Dazu die
  beiden anderen Wege: der nächste Workbench-Eintritt und der manuelle
  Retry STT — auch ohne Callback heilt also jede Session.
- **KEINE Altersregel im Journal.** Das 256-MB-Cap hält alles, nichts läuft ab,
  also kann nichts still verschwinden — die Forderung des Issues („entweder
  behalten oder mit dokumentiertem Grund verwerfen") ist durch BEHALTEN erfüllt.
  Ein `offline_expired`-Grund existiert bewusst nicht: er hätte eine zweite
  Verwerfungsart eingeführt, für die es keinen Anlass gibt.
- **Kein neues UI.** Der Ring aus #188 rendert den Grund aus `RecoveryReason`;
  #186 liefert nur `Kind.OFFLINE` (auto-retryable, „Offline — saved, will
  transcribe when online") und die IME-Zeile `ime_stt_offline`. Der geparkte
  IME-Zustand nach einem Fehlschlag bleibt unverändert — kein neuer
  Controller-State.
- **Der LLM-Call ist genauso gated.** Offline liefert die Pipeline
  `SttFailure.OFFLINE` und speichert den ROHEN Text (`PipelineOutcome.rawText`) —
  die Hälfte, die die Aufnahme gekostet hat, geht nie verloren; beides,
  `finishTranscription` und das manuelle `polish`.
- **Verworfen:** ein Hintergrund-Upload (Datenschutz/Akku und ein zweiter,
  unsichtbarer Schreiber auf denselben Provider), eine persistierte
  Offline-Queue (das Journal IST die Queue), und eine Altersregel für unfertige
  Sessions.

## 2026-11-20: #182 — Das Journal hält den KOMPONIERTEN Upload, nicht das nackte Fragment

Ein Diktat ist unwiederbringliche Arbeit; der Recovery-Vertrag darf nicht am
Retention-Slider oder an `cacheDir` hängen. Das Journal ist deshalb ein eigener
Store unter `filesDir/journal/<sessionId>/`, kein Anhängsel des Evidence-Stores.

- **Komponierter Upload statt nacktem Fragment:** Der Pre-roll kostet ~1 s pro
  Fragment (~2 %), macht aber jeden Retry **byte-identisch** zum ersten Versuch
  — genau die Eigenschaft, auf der #184 (idempotente Uploads) aufsetzt — und
  befreit ein späteres Re-Run (#185) von Pre-rolls, die im Cache nicht mehr
  existieren.
- **Journal ≠ Evidence-Store:** `AudioEvidenceStore` ist ein forensisches
  Archiv, gesteuert vom Slider, kopiert nach `externalFilesDir` (adb-lesbar).
  Das Journal ist der Recovery-Vertrag, immer an, in `filesDir`. Zwei Zwecke,
  zwei Stores — kein gemeinsames Retention-Modell.
- **Der Fragment-Root bleibt in `cacheDir`:** Ein Umzug des Preparers nach
  `filesDir` wäre billiger (keine Doppelkopie), würde aber den Working Set mit
  dem Durability-Record mischen; `startFragmentPreparation` prunt das
  Verzeichnis auf **eine** Session und würde damit eine unfertige Diktatur
  löschen.
- **Kein Age-Cap, nur ein Size-Cap (256 MB):** Unfertige Arbeit nach Alter zu
  löschen ist genau das Fenster, in dem der Nutzer handeln müsste; die
  Reihenfolge ist oldest-first, die neueste (und eine benannte Live-)Session
  überlebt immer, jede Räumung wird geloggt.
- **Kein Scanner in #182:** `unfinishedSessions()` ist bewusst NICHT gebaut —
  die Kaltstart-Erkennung gehört zur Recovery-UI (#185), die als einzige weiß,
  was sie anzeigen will.
- **`SessionDirTime` extrahiert:** Evidence-Store und Journal ordnen ihre
  Session-Verzeichnisse nach derselben #132-Regel; die private Kopie im
  Evidence-Store wurde zur gemeinsamen Klasse, `AudioEvidenceStoreTest` blieb
  als Regressionsbeweis grün.

## 2026-11-20: #174/#177 — Post-Send ist ein UI-Parkzustand, kein Controller-State

Nach dem erfolgreichen Senden bleibt die Tastatur stehen (#174) — aber der
nächste Bind startete sofort eine frische Aufnahme, weil der Auto-Start (#165)
unverändert ansprang. #177 löst das in der IME, nicht im Controller.

- **Entscheidung:** die reine `service/PostSendParkingPolicy.decide(state,
  parked, event)` plus das IME-Flag `postSendParked`, gesetzt in
  `handleCompletedSuccess`. Die geparkte Leiste rendert ▶ (`ic_resume`,
  enabled), Send und Flush disabled, Gear/X enabled, Timer ausgeblendet.
- **Der Auto-Start wird ganz unterdrückt:** `requestAutoStart` steigt beim
  geparkten Flag vor dem gesamten #165-Pfad aus, inklusive des verzögerten
  First-Draw-Starts. ▶/Send löschen das Parken und starten eine frische
  Aufnahme.
- **Feldwechsel hebt das Parken auf** (`onStartInputView` löscht das Flag);
  dasselbe Feld (Rotation, Ausblenden/Einblenden) bleibt geparkt; `DISMISSED`
  ändert nichts.
- **Warum kein Controller-State:** der Controller ist nach dem Senden echt
  IDLE. Ein erzwungenes PAUSED würde die repo-weite Bedeutung von PAUSED
  brechen ("Audio bereit, Resume ausstehend", #67/#84/#134) und könnte eine
  bereits ausgelieferte Diktierung wiederbeleben.
- **Verworfen:** (a) Controller-Zustand PAUSED — verworfen; (b) IDLE lassen
  und nur den Auto-Start gaten — verworfen, weil die Leiste dann "senden/
  aufnehmen" statt "▶ startet neu" suggeriert.
- **Preis:** das Flag ist Instance-State und stirbt mit dem IME-Service — ein
  Service-Tod nach dem Senden verhält sich beim nächsten Bind wie vor #177.
  ▶ ist akustisch ein Neustart; die Content-Description bleibt
  `ime_resume_desc`.
- **Tests:** `PostSendParkingPolicyTest` (12), `ImeCompletedHandoverTest`
  (3→6). **NO version bump (owner policy).**

## 2026-11-19: #165 — the auto-start waits for the first IME frame, and a blocked mic aborts the take

- **The window gate is the first DRAW, not a platform callback.** AOSP API 36 `InputMethodService.showWindow()` calls `onWindowShown()` *before* `mWindow.show()` (lines 3196 vs 3201), and `isInputViewShown()` is `mDecorViewVisible`, set earlier still in `prepareWindow()` (3297). Both would have started the microphone FGS inside the exact gap that costs the while-in-use grant. So `AutoStartTiming` + a `ViewTreeObserver.OnDrawListener` on `ime_root` own the decision, and only a real frame releases a parked auto-start. This is the platform contract, not a ROM quirk: the emulator reproduced the reporter's `New WIU:DENIED` byte for byte.
- **Warm windows keep the old behaviour.** Field-to-field switches and returns happen with the window still drawn (`windowDrawn` stays true between shows), so they start immediately; only a cold bind can defer. The rotation FREEZE path (#83/#157) is untouched — its `startMicForeground()` re-assert belongs to a session that already holds the grant.
- **The 1500 ms timeout drops only a window that is not coming up (owner decision: 1500 ms).** Measured while proving the fix: the first frame arrived **1.1 s** after the bind on a loaded AVD, uncomfortably close to the limit. So on timeout the decision is `AutoStartTiming.onTimeout(windowDrawn, isInputViewShown())`: still showing → keep waiting, not even showing → drop (the client-hides-the-keyboard-on-IME-change case). A slow cold start therefore never loses the dictation, and a hidden keyboard never records.
- **Layer 2 aborts, it never retries (owner decision).** `MicAccessPolicy` checks 400 ms after a start for an app-op `MODE_IGNORED`/`MODE_ERRORED`, `AudioRecordingConfiguration.isClientSilenced()`, or an `AudioRecord` that never initialized; any of them cancels the session and toasts ("Microphone unavailable — tap the mic to start again") instead of uploading noise. Deliberately one-shot: a retry needs its own state machine and can loop, while the manual tap lands in a now-visible window anyway.
- **NO version bump (owner policy).** The fix rides the next release; the reporter confirms on the S24 Ultra before the issue closes.

## 2026-10-13: #134 — a paused leftover on a DIFFERENT field is salvaged and replaced by a fresh empty recording (same-field auto-resume kept)
- **Owner problem:** after #133 the keyboard hand-back is the normal flow, and the owner invokes Polished from another typing keyboard (system IME picker). Expected: a fresh dictation — empty buffer, straight into RECORDING. But a leftover PAUSED session auto-resumed even on a DIFFERENT field (deliberate per the #65/#83 "auto-resume PAUSED on a different field" clause), so an old paused dictation (hand-pause, gear/switch detour, parked failure) could resurrect hours later in a different app, appending new audio to the old buffer.
- **Decision (two owner answers):** PAUSED + **different field** → discard via the NORMAL CANCEL PATH (#113 salvage: already-transcribed fragments → clipboard + toast) then a fresh empty recording auto-starts. PAUSED + **same field** → unchanged auto-resume (#65's pause→detour→return flow survives). The discriminator is the existing `RotationGate` same-field identity — a same-field rebind can never be a genuine field change (#83), so "same field" reliably means "returning to my own dictation". The delegated RecognitionService route needs no change (`onStartListening` already cancels leftovers and starts fresh), and the process-death restore stays FREEZE-parked for a manual decision.
- **Shape:** `AutoStartPolicy` collapsed from two booleans (`shouldAutoStart`/`shouldAutoResume`) into one pure `entryAction(state, sameField, permission)` → `RESUME | FRESH_START | NONE`; the IME's `onStartInputView` tail maps it (FRESH_START cancels first iff PAUSED — salvage — then `startIfPermitted()`). `ImeStartDecision` is UNTOUCHED as a decision (PROCEED still covers different-field PAUSED) — only its doc line changed; the behaviour change lives entirely in the action mapping, which keeps the FREEZE/PROVISIONAL_FREEZE paths and all their tests intact.
- **Tests:** `AutoStartPolicyTest` rewritten for the matrix (9 cases: IDLE both fields, no-permission across all states, PAUSED same→RESUME / different→FRESH_START, live states→NONE); suite green, pre-push green (no flake).

## 2026-10-10: #118 Phase A shipped — insertion breadcrumb + tagged deliveries (`99b2e46`)
- **Choice**: The rotation partial-insertion investigation ships INSTRUMENTATION first (no behaviour change), because the owner's answer — "no send, text appeared on rotate" — has NO code path: `commitWithSpacing()` is the only editor-insertion site, and `Event.Completed` is emitted only from the live `deliver()` or the `pendingResult` re-emit in `attach()`. (a) `Event.Completed` gained `redelivered: Boolean` (came from the `pendingResult` hold after rotation, #83) and `partial: PartialInfo?` (`failedIndex`, `chunkCount` — a raw-mode rescue delivery whose joined text is incomplete by construction). (b) `pendingResult` now holds the whole `Event.Completed`, so `attach()` re-emits it with `redelivered = true`. (c) A new evidence stream `insertion.json` records every insertion: `surface` (`ime`|`service`), `reason` (`LIVE`|`REDELIVERED`|`RAW_RESCUE_PARTIAL` via the pure `service/InsertionTrace`), text `length`, origin-vs-current `FieldId` (captured at send in app-scoped `PolishedRecognitionApp.imeSessionField`) and `InputConnection` presence. The `RecognitionService` surface logs the same stream because the caller (Gboard etc.) inserts our returned result itself — the IME site cannot see that flow. (d) `rescuePartial` in the controller carries the rescue detail from `runTranscription` to `deliver`, reset per run so it cannot leak.
- **Reason**: the owner's four answers (flow unsure; perceived no-send; raw mode varies; partial → clipboard + toast, retry = paste) leave the MECHANISM unpinned — both ranked causes (raw-rescue partial, `pendingResult` re-delivery) require a send, so shipping the Phase B delivery policy first would risk fixing the wrong thing. Phase A is cheap and makes the next repro conclusive.
- **~~Planned next (Phase B)~~ — SUPERSEDED 2026-11-23 by #190, do not build.** Der geplante reine `InsertionDecision` am Einfügepunkt (LIVE success → COMMIT; REDELIVERED + same field → COMMIT, um #83 zu erhalten; REDELIVERED + changed field → clipboard + Toast; RAW_RESCUE_PARTIAL → clipboard + Toast) IST #190s Endklassifikation `MISMATCH` (geändertes Feld ⇒ Zwischenablage, kein Auto-Commit) plus deren Before-Window-Guard — ihn hier zu bauen würde dieselbe Policy zweimal schreiben (siehe den #190-Eintrag). Die Raw-Rescue-Hälfte von Phase B ist mit **#186** ausgeliefert (ein leeres Rescue fügt nichts ein und erklärt sich immer; ein nicht-leeres fügt ein *mit* einem Toast, der den Grund nennt). Der damals vermutete Defekt war ohnehin keine Delivery-Policy-Frage: die Berichte „partieller Text / viel fehlt" waren die #119-WAV-Pre-Roll-Regression (`580cc38`), und Phase As Breadcrumb zeigte für jede Einfügung `reason: LIVE`. **Phase C (Storm-UX) ist am 2026-11-23 ausgeliefert — siehe den `#118 Phase C`-Eintrag oben**; Phase D (On-Device-Matrix) bleibt Owner-Arbeit.
- **Tests**: +5 (`InsertionTraceTest` ×4; `VoiceSessionControllerTest` ×1 raw-mode rescue partial marker, plus the `redelivered` assertion in the existing stash test). Full suite green; `pre-push` hook passed.

## 2026-09-19: Pipeline failure parks as ordinary PAUSED, PCM preserved (#84, OPEN)
- **Choice**: `VoiceSessionController.stopAndTranscribe()` uses non-destructive `AudioRecorder.stopPreservingBuffer()` (new; existing `stop()` delegates to it + `flushBuffer()`); the buffer is discarded only after a successful upload. On pipeline failure the session parks as ordinary PAUSED — audio stays buffered (retry re-sends, resume appends), `accumulatedMs` preserved, disk snapshot re-written for #83 `restore()` coverage. `CancellationException` is rethrown (a user `cancel()` during PROCESSING must not resurrect as PAUSED). IME unchanged: existing failure Toast fires, no hide, PAUSED bar offers send/resume + editable language/Raw settings. Owner decisions: IME stays visible, Toast as-is, no special retry — "as if the send never happened"; inherits #83 auto-resume on field return.
- **Reason**: Owner request — network loss discarded the dictation; now the user fixes connectivity and taps send again.
- **Tradeoff**: Returning to the field after a failure auto-resumes the mic (standard #65/#83 PAUSED behavior) instead of waiting still-paused — retrying the exact bytes requires sending without leaving the field. 6 new tests (4 controller + 2 recorder); suite green + `assembleRelease` green. On-device feel-check pending.

## 2026-09-19: Flush button discards PCM keeping session mode + unit-test worker heap 1536m (#86, OPEN)
- **Choice**: New `ime_flush_button` (4th bottom-row button, `ic_delete` + `recording_icon_tint`, "Discard recording") — `AudioRecorder.flushBuffer()` resets `bufferStream` + speech-begin latch without touching the live `AudioRecord` thread; `VoiceSessionController.flush()` keeps the mode (RECORDING keeps capturing into a fresh buffer with restarted timer, PAUSED stays paused with zeroed timer), clears the disk snapshot (no #83-restore ghosts), keeps the UI callback attached (emits `StateChanged(sameState)`), no-op in IDLE/PROCESSING. Flush is enabled only in RECORDING/PAUSED. Owner decisions: discard + stay in mode (closing is cancel's job), bottom-row placement (appended after mic/send so cancel/pause/send keep order).
- **Reason**: Owner request — discard a bad take and re-record from scratch without leaving the IME. Mode-preserving (vs `cancel()` which nulls the callback + hides the IME). Complements #84 (buffer preserved on pipeline failure; flush is the manual escape hatch).
- **Tradeoff**: 4 equal-weight buttons are narrower; PROCESSING offers no flush (upload bytes already extracted — the pipeline owns them). RMS smoother resets on flush-while-recording so the pulse restarts from floor.
- **Infra (same commit `9b67336`)**: unit-test worker `maxHeapSize 512m → 1536m` in `app/build.gradle.kts` (`testOptions.unitTests.all`). The suite (244 tests) exceeded Gradle's 512m default — the executor died with exit value 2 exactly at the pipeline→service boundary while every package passed in isolation. Test-only setting, no effect on release artifacts/CI build outputs.

## 2026-09-18: Rotation-proof session on Oplus — same-field freeze, FGS retention, disk snapshot, lifecycle trace (#83 follow-up, CLOSED)
- **Choice**: Pure `service/ImeStartDecision` (FREEZE/CANCEL/PROVISIONAL_FREEZE/PROCEED) drives `onStartInputView`: RECORDING/PROCESSING cancel **only onto a different field** — a same-view rebind can never be a genuine field change, so no rotation mark is needed for the destructive decision. PAUSED on the same field with no signal yet freezes provisionally and finalizes after 500 ms (gate still stale + still PAUSED → auto-resume per #65; gate fresh → rotation confirmed, stay frozen; generation-guarded so superseded runnables no-op and user taps win). `onDestroy` releases mic FGS only when IDLE (dropping it mid-session let Oplus reap the process mid-rotation); the freeze path re-asserts FGS. `VoiceSessionController.snapshot()/restore()` persist raw PCM + duration to `cacheDir/session.{pcm,meta}` on pause/teardown and restore as PAUSED with continuing timer (the #67 reopen condition — process-death loss now observed on device). `service/ImeLifecycleTrace` appends pid-tagged bind/config/finish/destroy events to `ime-lifecycle.log` next to the rotating API logs (Oplus suppresses IME logcat). App-scoped `PolishedRecognitionApp.imeConfigChangeMs` + `imeLastField` feed the pure `service/RotationGate` (3 s freshness window, `fieldId==0` matches on package).
- **Reason**: On-device `ime-lifecycle.log` proved two mechanisms: (1) Oplus rebinds input (`restarting=false`, same field) ~150 ms BEFORE `onConfigurationChanged` delivers the mark — every mark-based check mis-fired, cancelling the live session and auto-starting a fresh `00:00` one; (2) full process death across rotation on the old build, caused by unconditional FGS release in `onDestroy` (owner experiment: PAUSED + rotate → timer `00:00` + pre-rotation audio gone, with no code path that discards a live PAUSED buffer).
- **Tradeoff**: Auto-resume after hide/show and switch-return lags ~0.5 s (provisional window); IDLE auto-start still fires immediately (a rotation-born spurious session loses no data — delaying it would regress every-focus auto-start). Snapshot I/O is synchronous on pause/teardown (session is stopped there, never mid-capture) and catches `Throwable` incl. OOM. Supersedes the `onConfigurationChanged`-flag design below (moved to HISTORY) and implements the deferred #67 proposal (moved to HISTORY).

## 2026-05-30: Auto-start recording in VoiceRecognitionActivity
- **Choice**: `VoiceRecognitionActivity` starts recording immediately in `onCreate()` (or on permission grant) instead of showing "Tap mic to speak" idle state
- **Reason**: One less tap for the user. When AnySoftKeyboard launches voice input, the user's intent is already "I want to dictate" — no need for a confirmation tap
- **Considered**: Keeping the explicit tap-to-start flow (safer but slower)
- **Tradeoff**: User must tap to STOP instead of START. Stop icon (square) replaces old mic/pause icon for clarity

## 2026-05-30: Pause/resume recording with three-button layout
- **Choice**: Replaced single mic button with three-button layout (Cancel / Pause-Resume / Stop) and moved blinking animation from mic to status text. Pause stops AudioRecord while keeping PCM buffer; resume appends to the same buffer. Stop extracts the full buffer for STT. `android:noHistory="true"` removed from manifest so activity survives navigation to Settings.
- **Reason**: Users need to change settings (e.g. target language) mid-session without losing recording. Pause → Settings → Save → Resume → Stop must use the new settings for transcription. `TranscriptionPipeline` reads settings at transcription time, not recording time, so changes apply automatically. `noHistory` was destroying the activity on navigation, losing the buffer — removed.
- **Considered**: `startActivityForResult` with auto-resume (complex lifecycle, overkill), saving PCM to temp file on pause (additional I/O, fragile)
- **Tradeoff**: Activity stays in back stack during Settings — if system kills it under memory pressure, buffer is lost. Counter: voice input sessions are short, and system kill in this window is unlikely.

## 2026-06-02: configChanges for rotation safety
- **Choice**: Add `android:configChanges="orientation|screenSize"` to `VoiceRecognitionActivity` in the manifest
- **Reason**: Default Activity rotation destroys and recreates the Activity, which calls `onDestroy()` → `audioRecorder.cancel()` → discards the entire PCM buffer. The user loses their recording and only gets the last fragment. `configChanges` prevents recreation; the AudioRecord thread continues uninterrupted.
- **Considered**: `android:screenOrientation="portrait"` (simpler but blocks landscape), ViewModel/AAC binding for recording state (overkill for a ~10s overlay)
- **Tradeoff**: Layout is reused as-is — no landscape-specific variant exists, but the centered layout works in both orientations.

## 2026-06-12: terminateSession for session cleanup
- **Choice**: Add `abortSession()` + `terminateSession()` to `opencode_client.ts`, add `try/finally` blocks around retry loops in `dispatchProficiency` and `dispatchTranslate`
- **Reason**: polished-recognition used `closeSession()` without `try/finally` — sessions leaked on errors and timeouts. zazentimer's `terminateSession()` calls abort then close with proper error handling.
- **Considered**: Wrapping existing `closeSession` calls (doesn't guarantee execution on exception)
- **Tradeoff**: Two additional HTTP calls per session cleanup. Negligible compared to the session lifespan.

## 2026-08-30: Auto-start mic recording when the keyboard appears (#54) [partially superseded 2026-09-07 by #65]
- Moved to HISTORY.md — the auto-start-on-IDLE policy remains in force; the "auto-resuming PAUSED sessions (rejected)" clause was overturned by #65 (see the 2026-09-07 decision).

## 2026-09-07: Preserve paused dictation across keyboard switches + auto-resume on re-entry (#65)
- **Choice**: `VoiceSessionController` is app-scoped (`PolishedRecognitionApp.voiceSessionController` lazy singleton). The IME attaches its event listener in `onCreate` (`attach`) and, in `onDestroy`, detaches instead of cancelling when the session is PAUSED (`detach` — rebindable listener, no state change); every other state cancels as before. `AutoStartPolicy.shouldAutoResume(state, perm)` (PAUSED + permission) makes `onStartInputView` auto-resume recording, with `startMicForeground()` before `resume()` (mirrors `startIfPermitted` ordering).
- **Reason**: Owner feedback: the new switch button (#65) discarded the dictation — `switchToPreviousInputMethod()` destroys the IME service and `onDestroy`'s `controller.cancel()` wiped the PCM buffer; the per-IME controller would have made the buffer unreachable on re-creation anyway. Owner workflow: dictate → switch icon (pause) → type on the real keyboard → return → recording continues appending to the old PCM → send. Auto-resume scope: owner chose "immer bei PAUSED" (also after manual pause + field/app switch) for minimal button presses, overriding #54's rejection.
- **Considered**: Per-IME controller with on-disk PCM session store (restore on create) — deferred to #67 (restore I/O on the keyboard-show path, more moving parts); provenance flag "paused-by-switch" to limit auto-resume to switch-caused pauses — rejected (owner wants resume on every PAUSED re-entry).
- **Tradeoff**: The preserved session is memory-only — process death while on the other keyboard loses the dictation (#67). A manually paused session now resumes on any keyboard re-show (owner-approved). 150 unit tests green; on-device smoke test pending at commit time.

## 2026-10-10: Rotation cannot be blocked by an IME — resilience, not prevention; partial output is a delivery-side decision (#113/#118)
- **Choice**: Do not pursue any rotation-blocking mechanism. There is none: no `InputMethodService`/`InputMethodManager` API controls the client Activity's orientation, and the global rotate-lock (`Settings.System.ACCELEROMETER_ROTATION`) needs `WRITE_SETTINGS` and is blocked on Oplus. Keep the app-scoped session + snapshot/restore + `RotationGate` FREEZE as the sanctioned resilience, and handle partial / rotation-re-delivered text on the delivery side (`commitWithSpacing`) rather than preventing the rotation.
- **Reason**: Owner asked whether the IME can request Android to block rotation while running. The Android contract is the opposite — an IME must survive config changes — and on-device logs (2026-10-10) show the FREEZE gate already preserves the dictation through the Oplus rebind storm; the remaining defect is a partial *insertion*, which blocking rotation would only mask (and which cannot be blocked anyway).
- **Tradeoff**: The rebind storm stays visible as UI churn (extra `applyUiState`/FGS/notification cycles); mitigating it is a UX task (Phase C of #118), not prevention. The residual bias of a wrongly-surviving live session (owner taps cancel) is unchanged from #113.

## 2026-10-14: #138 — transcripts are consume-once; salvage keys on a live session; clipboard untouched after successful insertion
- **Choice**: (a) The success branch of `stopAndTranscribe`'s transcribe job drops the fragment session (`discardFragmentSession()`, salvage return ignored) right after `clearPrepared()` and BEFORE `deliver()` — the transcriber's success cache is consumed the moment the result is delivered (the transcriber is lazily rebuilt per session). (b) `VoiceSessionController.cancel()` salvages (`Event.CancelledWithTranscripts` + `stt-text` log) only when `state != IDLE` on entry; an IDLE cancel is a pure teardown no-op. (c) Owner decision: after a SUCCESSFUL insertion the clipboard stays UNTOUCHED — never written (no salvage), never cleared (clearing would destroy the user's prior clipboard content). #113 salvage remains exclusive to the real cancel path (RECORDING/PAUSED/PROCESSING).
- **Reason**: #133 regression — the post-send `switchInputMethod` destroys the IME, its `onDestroy()` IDLE branch calls `cancel()`, and the never-cleared transcript cache (which exists for the retry-after-failure re-drain) was mistaken for undelivered work: error toast + clipboard copy of text already in the field. Pre-#133 unreachable (a successful send never switched IMEs).
- **Tradeoff**: The salvage check is a state snapshot, not a delivery flag — a future path that cancels between `deliver()` and `state = IDLE` would still salvage; the success-path consume makes that window empty in practice. Clearing before `deliver()` (not after) is deliberate: race-free against any delivery-time teardown.
- **Verification**: 481 tests green incl. the new regression `cancel after a successful delivery emits no salvage event` (restored fragment session, success pipeline, Completed received, teardown cancel silent); `assembleRelease` green; pre-push green; pushed `d0e7dbe`.

## 2026-11-21: #185 — the journal's session marker is the recovery state; re-run replays the gaps; the Workbench retries on entry

- **Choice**: (a) **The session state lives in the journal's `session.json`** (owner idea: write a marker into the bundle), not in a separate audit file: `completedAtMs` (written by the controller's success branch immediately before the existing delete), `dismissedAtMs`/`dismissReason` (tombstone). A session without either marker is a leftover. A marker-less session whose records are all `DONE` ALSO counts as completed — nothing is left to send, so the honest action is restore, not re-run. (b) **Dismissal = write the reason, then delete the audio and the records, keeping only `session.json`.** Tombstones are capped by count (50, newest win) inside `prune`; they do not consume the 256-MB audio cap. (c) **Re-run = per-fragment replay of the MISSING fragments only**, spliced with the stored text in index order: the journal's own composed upload (byte-identical), the recorded prompt, `EchoTrim` (now a pure `:core` object, delegated to by `FragmentTranscriber`), `LoopGuard` on a forced seam, `session_id`/`fragment_index` unchanged. Stops at the first failure; every `DONE` flip is durable. (d) **The Workbench is the entry point**: on entry it scans, shows a GONE-at-zero banner, Toasts BEFORE the first request, auto-retries only `TRANSIENT`/interrupted sessions (never AUTH/PROTOCOL/TERMINAL), remembers its attempts per PROCESS (`autoAttempted` — no persisted backoff; a restart is a new chance), and yields at every fragment boundary while the controller is RECORDING/PROCESSING. (e) **Sharing** rebuilds ONE WAV for WAV sessions (pre-rolls cut, `recovery/RecoveryAudio`) and attaches the pieces for OGG (no decoder on device), through a new `FileProvider` (`<pkg>.recovery`). (f) **The text is stored before the journal is deleted**; a failed polish falls back to the raw transcript.
- **Reason**: The owner's model ("the bundle is written during dictation, deleted on success, kept on failure") is already the #182 journal — the release default of 0 bundles gates only the *evidence* copy under `files/audio`, which is why the recovery UI must never read it. The marker the owner asked for fits the journal's `session.json`, which already is the write-ahead validity marker; a separate `dismissed.json` would be a second source of truth. Replaying byte-identical composed uploads is the only re-run that keeps every live-path property (prompt conditioning, acoustic echo trim, loop guard, #184 idempotency); reconstructing audio would fail for OGG and lose the prompts.
- **Tradeoff**: The delete on success is NOT reordered (#138 is load-bearing: the transcriber cache must be consumed before `deliver()`), so the completion marker is one tiny write whose visible effect is a delete that fails or a process death in between — deliberately accepted, it costs nothing and turns an invisible loss into a "text available" row. A completed dir that survives lingers until the user acts (there is no age rule; it is a handful of KB of audio). The re-run's `attempt` counter restarts at 1 — the dedupe identity is `session_id:fragment_index`, which stays. Journal audio of `DONE` fragments is KEPT (deleting it would strip the audio from a session the user may still want to share); the 256-MB cap bounds it.
- **Verification**: 874 tests green (`:core` 258 incl. `EchoTrimTest` 10, `JournalReplayTest` 13, `FragmentJournalTest` +13; `:app` +`RecoveryActionsTest`, `RecoveryReasonTest`, `RecoveryAudioTest`, `RecoveryCoordinatorTest`, its own screen test, `WorkbenchRecoveryBannerTest`, the recovery screen added to `ContrastMatrix`), `lintDebug` green (only the three pre-existing warnings), `assembleRelease` green, pre-push green. No version bump (consistent with #182–#184).
- **Not verified**: the force-stop acceptance criterion needs a device (claw has none) — owner check in HANDOFF.


**Also stored elsewhere** (ambiguous placement, cross-referenced per #206):
- [foundations.md](foundations.md) — „2026-11-24: #200 — der Retention-Default wird nicht mehr beim Lesen persistiert"
