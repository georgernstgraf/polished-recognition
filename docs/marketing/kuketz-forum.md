# Kuketz-Forum (Deutsch — Second Wave)

> **Timing-Empfehlung (oben lesen, dann posten):** Erst posten, wenn
> (1) F-Droid die Version 1.3.0 **mit Listing-Bildern** ausliefert (Paketseite
> prüfen — Stand 2026-09-20: 1.2.4 ohne Screenshots) und (2) die EN-Welle
> erste FAQ-Antworten geliefert hat, die hier wiederverwendbar sind.
> Mit eigenem Account posten; Ton: sachlich, Architektur-fokussiert.

---

**Titel:** Polished Recognition — Spracheingabe-Tastatur mit eigenen STT-/LLM-Endpunkten (FOSS, F-Droid)

Hallo zusammen,

ich habe eine Spracheingabe-Tastatur für Android gebaut, die ohne
Google-Spracherkennung auskommt: Polished Recognition
(https://f-droid.org/packages/com.georgernstgraf.polishedrecognition).

Funktionsweise: Man wechselt auf die Polished-Tastatur (reine Voice-Bar,
kein QWERTY), spricht, tippt auf Senden — der transkribierte und optional
per LLM nachbearbeitete Text landet in der jeweiligen App. Beide Stufen
akzeptieren beliebige OpenAI-kompatible Endpunkte: STT z. B. Whisper über
GROQ/OpenAI/selbstgehostet, Nachbearbeitung über ein beliebiges Chat-Modell
(auch lokale Ollama-/LM-Studio-Instanz — dann verlässt die Sprache das eigene
Netz gar nicht). System-Prompt frei editierbar, Raw-Modus ohne LLM-Aufruf,
Übersetzung optional, Pause/Resume über Tastaturwechsel hinweg.

Zur Architektur und den Berechtigungen, weil das hier sicher gefragt wird:
Die App hat keinen Account, keinen Server, kein Tracking. RECORD_AUDIO dient
ausschließlich der Mikrofonaufnahme, INTERNET ausschließlich den vom Nutzer
selbst konfigurierten STT-/LLM-Aufrufen — auf Headless-Emulatoren ohne
API-Key findet keinerlei App-Traffic statt. Der Quellcode ist offen, der
Build F-Droid-reproduzierbar.

Einschränkung: Funktioniert nur mit Tastaturen, die ihr Mikro an die
Spracheingabe delegieren (AOSP-Tastatur, HeliBoard, Fossify, OpenBoard).
Gboards Mikrofon ist fest auf Google verdrahtet — strukturell inkompatibel.

**F-Droid:** https://f-droid.org/packages/com.georgernstgraf.polishedrecognition
**Play-Alpha (Tester gesucht):** Gruppe beitreten
(https://groups.google.com/g/polished-recognition-alpha), dann mit demselben
Konto testen
(https://play.google.com/apps/testing/com.georgernstgraf.polishedrecognition).
**Feedback:** https://github.com/georgernstgraf/polished-recognition/issues
