---
type: Pitfalls
title: Pitfalls — Workbench
description: The Workbench store and its pages: instances, focus rules, and the dictation ring.
tags: [knowledge, pitfalls, workbench]
generated: { by: opencode/knowledge-persistence, at: 2026-11-25T12:00:00Z }
status: stable
---

# Pitfalls — Workbench

Moved out of `PITFALLS.md` in #206; entries are verbatim. New pitfalls in this area are appended here.

- **Eine Seite im Ring kann KEINEN Text haben — und darf deshalb auch keinen Focus bekommen (2026-11-22, #188).** Das Leftover-Blatt zeigt Raw leer; `DictationStore.read()` verwirft jeden Record mit leerem `raw`, und die IME committet nur in das FOKUSSIERTE Feld. Lässt man Raw fokussierbar, kann die IME hineinschreiben, `commitEdits()` findet keinen `currentId` — die Eingabe ist weg, ohne Fehler. Deshalb setzt `render()` für eine Leftover-Seite `isEnabled`/`isFocusable`/`isFocusableInTouchMode` false (in EINEM `rendering`-Block, sonst feuert der Focus-Verlust `onRawFieldLeft` und liefert den Seiteninhalt an die Zwischenablage), und `onRecordingStarted` entscheidet dort `NEW` statt `RAW_EDIT`. Zweite Falle: `isEnabled=false` nimmt dem Feld den Focus — der Wechsel zurück auf ein gespeichertes Diktat muss die Flags ZURÜCKSETZEN und `requestFocus()` nachziehen, sonst landet das nächste Diktat nirgends.

- **`DictationStore` hat drei Instanzen, und `@Synchronized` ist pro Instanz (2026-11-22, #188).** Die Workbench, der `RecoveryCoordinator` und jeder Test bauen ihr eigenes `DictationStore`-Objekt; das Lock schützt nur gegen Threads DERSELBEN Instanz. Ein Recovery-Lauf kann also parallel zu einem `append` der Workbench schreiben — deshalb wird nach jedem Eingriff NEU GELESEN (`store.loadAll()`), nie ein zwischengespeichertes `pages`/`dictations` weiterverwendet, und der FIFO-Cap gilt pro Schreibvorgang: eine wiederhergestellte Transkription kann ein altes Diktat verdrängen (der Store-Vertrag, kein Bug).


**Also stored elsewhere** (ambiguous placement, cross-referenced per #206):
- [session-lifecycle.md](session-lifecycle.md) — „**`onFinished` im `RecoveryCoordinator` ist EIN Slot — zwei Besitzer heißt ein stummer Screen (2026-11-22, #188).** #185 hatte die Workbench UND den Recovery-Screen als Setter; wer zuletzt `onResume` hatte, bekam das Outcome, der andere zeigte stillschweigend nichts. Seit #188 gibt es nur noch die Workbench. Wer je einen zweiten Setter braucht, braucht zuerst einen Event-Bus, keinen zweiten Slot; `onPause` MUSS den Slot auf `null` setzen, sonst schreibt ein fertiger Lauf in eine zerstörte Activity."
