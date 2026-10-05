# Facebook — persönlicher Post

> **Mit dem eigenen (privaten) Account posten.** GIF anhängen:
> **docs/marketing/mastodon-demo.gif** — derselbe Social-Cut wie auf Mastodon
> (540×700, der leere schwarze Streifen zwischen Notiztext und Tastatur ist
> entfernt); Facebook spielt ein hochgeladenes GIF automatisch als Video ab.
> Ton: **persönlich, erste Person, kein Marketing-Sprech** — „ich habe das für
> mich gebaut und gebe es weiter“, nicht „Produkt-Launch“.
> **Inhaltlicher Fokus:** eigene tägliche Nutzung (seit 25 Jahren im Netz, seit
> den frühen Tagen auf Facebook), Datenschutz/Datensicherheit (die Stimme geht
> nur dorthin, wohin man sie selbst zeigt), **keine kommerzielle Absicht**
> (keine Werbung, kein Premium, kein Datenverkauf), **Open Source aus Freude
> am Geben**, und die Suche nach Play-Alpha-Testern.
> Alpha-Onboarding ist kanonisch in `alpha-welcome.md` — den Zweistufen-Block
> synchron halten. Der Post-Text bleibt versionsfrei (keine Versionsnummer).
> **GIF-Alt-Text** (Facebook-Feld „Beschreibung“): „Bildschirmaufnahme: eine
> dunkle Notiz-App mit der Polished-Recognition-Tastatur auf Englisch
> eingestellt. Die Person diktiert auf Deutsch, stockt ein paar Sekunden,
> tippt auf Senden, und ein sauberer englischer Satz erscheint in der Notiz.“
> **Tipp:** Facebook drosselt die Reichweite von Posts mit externen Links. Wenn
> das stört, den Text ohne die Link-Blöcke posten und die Links in den ersten
> Kommentar legen (Variante unten).

---

## Post-Text (Deutsch)

Ich habe mir eine Tastatur gebaut. Nicht, weil ich eine Firma gründen wollte —
sondern weil mich etwas genervt hat.

Ich bin seit 25 Jahren im Netz unterwegs und seit den frühen Tagen hier auf
Facebook. Ich tippe viel: Nachrichten, Mails, Notizen. Spracheingabe war für
mich nie wirklich brauchbar — und wenn sie funktioniert, landet das gesprochene
Wort auf den Servern von Google. Das wollte ich nicht.

Also habe ich **Polished Recognition** gebaut: eine reine Spracheingabe-Tastatur
für Android (kein QWERTY, nur die Sprachleiste). Man wechselt auf die Tastatur,
spricht, tippt auf Senden — der Text landet in jeder App. Die Transkription
läuft über einen STT-Dienst, den man selbst wählt, und ein Sprachmodell putzt
das Gesagte glatt. Mit einer lokalen Ollama-Instanz verlässt die Stimme das
eigene Gerät nie.

Im GIF oben: Ich diktiere auf Deutsch, mit Versprechern und Füllwörtern, tippe
auf Senden — und ein sauberer englischer Satz steht in der Notiz.

Was mir dabei wichtig ist:

🔒 **Datensicherheit:** kein Konto, kein Server von mir, kein Tracking. Meine
Aufnahmen gehen ausschließlich dorthin, wo ich sie hinweise.
🙌 **Kein Geschäft:** Ich verdiene damit nichts. Keine Werbung, kein Premium,
kein Verkauf von Daten. Es ist gratis.
🔓 **Open Source:** Der Code ist offen, die App ist auf F-Droid. Ich habe das
für mich gebaut und gebe es weiter — einfach, weil es vielleicht anderen hilft.

Und weil alles noch ganz frisch ist, suche ich weiter **Play-Alpha-Tester**.
Zwei Schritte, mit demselben Google-Konto:

1. Gruppe beitreten: https://groups.google.com/g/polished-recognition-alpha
2. Opt-in annehmen:
   https://play.google.com/apps/testing/com.georgernstgraf.polishedrecognition

Wer 14 Tage durchgehend dabei bleibt, hilft, die App für alle freizugeben.

**F-Droid:**
https://f-droid.org/packages/com.georgernstgraf.polishedrecognition
**Fehler & Ideen:**
https://github.com/georgernstgraf/polished-recognition/issues

---

## Variante für bessere Reichweite — Links im ersten Kommentar

Wenn Facebook den Post mit Links ausbremst: denselben Text **ohne** die beiden
Link-Blöcke („F-Droid“ / „Fehler & Ideen“) posten und direkt danach als ersten
Kommentar anhängen:

> F-Droid: https://f-droid.org/packages/com.georgernstgraf.polishedrecognition
> Fehler & Ideen: https://github.com/georgernstgraf/polished-recognition/issues
>
> Play-Alpha-Tester gesucht (zwei Schritte, dasselbe Google-Konto):
> 1. https://groups.google.com/g/polished-recognition-alpha
> 2.
> https://play.google.com/apps/testing/com.georgernstgraf.polishedrecognition
