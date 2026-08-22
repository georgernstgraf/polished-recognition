# Installation Guide

## English

### 1. Install from Play Store (Closed Testing)

The app is currently in closed testing. The Play Store link only works after
you have been added to the testers list:

1. Send a [request email](mailto:georg.ernst.graf@gmail.com) to be added
2. Once added, install from the [Play Store](https://play.google.com/store/apps/details?id=com.georgernstgraf.polishedrecognition)

> An APK is also built on GitHub for each release.

### 2. Configure Providers

Open the app (via the launcher, or if hidden on your device, launch settings via ADB):

```bash
adb shell am start -n "com.georgernstgraf.polishedrecognition/com.georgernstgraf.polishedrecognition.ui.SettingsActivity"
```

Then:
- **STT Provider** (e.g. Groq) → enter API token → Validate & Fetch Models → pick a Whisper model
- **LLM Provider** (optional) → same procedure
- **Target Language** (optional) → select language for translation
- **Save**

#### Recommended Setup (GROQ — free)

1. Sign up at [console.groq.com](https://console.groq.com) and create an API key
2. In Settings, select **Groq Whisper** as STT provider and paste your key
3. Select **Groq** as LLM provider and paste your same key
4. Validate & Fetch Models on both
5. Recommended STT model: `whisper-large-v3-turbo` — runs at up to 300× real-time on Groq LPU hardware
6. Recommended LLM model: `gpt-oss-120b` — fast, capable, free tier covers daily keyboard usage

### 3. Enable the Voice Keyboard (IME)

Polished Recognition registers as an **auxiliary voice keyboard**. Enable it once:

1. **Settings → System → Languages & input → On-screen keyboard**
   (called "Keyboard & input method" on some ROMs)
2. Turn on **Polished Recognition**
3. Grant the **Microphone** permission when prompted

> On some OEM ROMs the on-screen-keyboard list is hidden. The app's Settings
> page has an **Enable Voice Keyboard (IME)** button that opens the system
> keyboard settings directly.

Then point your keyboard's voice input at it:

- **HeliBoard / Fossify Keyboard / OpenBoard:** open the keyboard's settings →
  **Voice typing** (or "Voice input method") → select **Polished Recognition**
- Press the **voice / microphone key** on the keyboard while typing in any text
  field to start recording

> The app also answers the standard **`RECOGNIZE_SPEECH`** intent
> (e.g. AnySoftKeyboard's mic button) — no extra setup needed.

### 4. Use It

1. Focus any text field
2. Switch to **HeliBoard / Fossify Keyboard / OpenBoard** (or any keyboard that
   lets you choose a voice input provider)
3. Press the **voice / microphone key**
4. Tap **Send** when done speaking — the transcribed (and optionally translated)
   text is typed directly into the field
5. If no provider is configured yet, open the app from the launcher first

### Troubleshooting

**Polished Recognition not listed under on-screen keyboards?**
Make sure it is enabled in **Settings → On-screen keyboard**. The app's Settings
page has an **Enable Voice Keyboard (IME)** button that opens that screen
directly.

**Gboard does not pick it up?**
Gboard uses its own Google speech engine and cannot be redirected to a
third-party voice input. Use **HeliBoard**, **Fossify Keyboard**, or
**OpenBoard** instead.

**Microphone permission missing?**
The first time you press the voice key the app asks for the Microphone
permission. You can also grant it under
**Settings → Apps → Polished Recognition → Permissions**.

---

## Deutsch

### 1. Aus dem Play Store installieren (Closed Testing)

Die App befindet sich im geschlossenen Test. Der Play-Store-Link funktioniert
erst, nachdem du zur Testerliste hinzugefügt wurdest:

1. Sende eine [Anfrage-E-Mail](mailto:georg.ernst.graf@gmail.com)
2. Nach Freischaltung: installiere über den [Play Store](https://play.google.com/store/apps/details?id=com.georgernstgraf.polishedrecognition)

> Eine APK wird für jedes Release auch auf GitHub erstellt.

### 2. Anbieter konfigurieren

Öffne die App (über den Launcher, oder falls nicht sichtbar, über ADB):

```bash
adb shell am start -n "com.georgernstgraf.polishedrecognition/com.georgernstgraf.polishedrecognition.ui.SettingsActivity"
```

Dann:
- **STT-Anbieter** (z. B. Groq) → API-Token eingeben → Validate & Fetch Models → Whisper-Modell auswählen
- **LLM-Anbieter** (optional) → gleiches Vorgehen
- **Zielsprache** (optional) → Sprache für Übersetzung auswählen
- **Save**

#### Empfohlene Einrichtung (GROQ — kostenlos)

1. Registriere dich auf [console.groq.com](https://console.groq.com) und erstelle einen API-Key
2. In den Einstellungen: **Groq Whisper** als STT-Anbieter wählen und Key einfügen
3. **Groq** als LLM-Anbieter wählen und denselben Key einfügen
4. Bei beiden: Validate & Fetch Models
5. Empfohlenes STT-Modell: `whisper-large-v3-turbo` — bis zu 300× Echtzeit auf Groq LPU-Hardware
6. Empfohlenes LLM-Modell: `gpt-oss-120b` — schnell, leistungsfähig, kostenloses Kontingent reicht für tägliche Tastaturnutzung

### 3. Die Voice-Tastatur (IME) aktivieren

Polished Recognition registriert sich als **auxiliary Voice-Tastatur**. Einmalig aktivieren:

1. **Einstellungen → System → Sprachen & Eingabe → Bildschirmtastatur**
   (bei manchen ROMs „Tastatur & Eingabemethode")
2. **Polished Recognition** einschalten
3. **Mikrofon**-Berechtigung erteilen, wenn abgefragt

> Bei manchen OEM-ROMs ist die Liste der Bildschirmtastaturen versteckt. Die
> Einstellungsseite der App hat einen Button **Enable Voice Keyboard (IME)**,
> der die Systemtastatur-Einstellungen direkt öffnet.

Danach die Spracheingabe der Tastatur darauf zeigen:

- **HeliBoard / Fossify Keyboard / OpenBoard:** in den Einstellungen der
  Tastatur → **Voice typing** (bzw. „Voice input method") → **Polished Recognition** auswählen
- Die **Mikrofon-/Voice-Taste** der Tastatur in einem beliebigen Textfeld drücken, um die Aufnahme zu starten

> Die App beantwortet zusätzlich den Standard-Intent **`RECOGNIZE_SPEECH`**
> (z. B. die Mikrofontaste von AnySoftKeyboard) — keine weitere Einrichtung nötig.

### 4. Verwendung

1. Ein beliebiges Textfeld fokussieren
2. Zu **HeliBoard / Fossify Keyboard / OpenBoard** wechseln (oder jede Tastatur, die die Voice-Eingabe wählbar macht)
3. Die **Voice-/Mikrofon-Taste** drücken
4. **Send** tippen, wenn man fertig gesprochen hat — der transkribierte (und ggf. übersetzte) Text wird direkt ins Feld eingefügt
5. Falls noch kein Anbieter konfiguriert ist, zuerst die App aus dem Launcher öffnen

### Fehlerbehebung

**Polished Recognition nicht in den Bildschirmtastaturen gelistet?**
Sicherstellen, dass sie unter **Einstellungen → Bildschirmtastatur** aktiviert ist. Die Einstellungsseite der App hat einen Button **Enable Voice Keyboard (IME)**, der diesen Bildschirm direkt öffnet.

**Gboard nutzt sie nicht?**
Gboard verwendet die eigene Google-Sprachengine und lässt sich nicht auf eine Drittanbieter-Voice-Eingabe umleiten. Stattdessen **HeliBoard**, **Fossify Keyboard** oder **OpenBoard** verwenden.

**Mikrofon-Berechtigung fehlt?**
Beim ersten Druck auf die Voice-Taste fragt die App die Mikrofon-Berechtigung ab. Sie lässt sich auch unter **Einstellungen → Apps → Polished Recognition → Berechtigungen** erteilen.
