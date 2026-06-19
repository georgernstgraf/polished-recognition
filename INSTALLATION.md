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
3. Select **Groq** as LLM provider and paste the same key
4. Validate & Fetch Models on both
5. Recommended STT model: `whisper-large-v3-turbo` — runs at up to 300× real-time on Groq LPU hardware
6. Recommended LLM model: `gpt-oss-120b` — fast, capable, free tier covers daily keyboard usage

### 3. Set as Default Voice Input

#### Standard Android (Pixel, etc.)
**Settings → System → Languages & input → Voice input → Polished Recognition**

#### Samsung (Android 11)
The "Voice input" menu is hidden. Use ADB:

```bash
adb shell settings put secure voice_recognition_service com.georgernstgraf.polishedrecognition/com.georgernstgraf.polishedrecognition.service.PolishedRecognitionService
```

Verify:

```bash
adb shell settings get secure voice_recognition_service
```

#### OnePlus / OxygenOS (Android 12)
1. **Settings → Apps → Default apps → Digital assistant app → Voice input**
2. Select **Polished Recognition**

If "Voice input" is not visible, reboot the device. OxygenOS enumerates recognition services at boot time.

You can also enable the service from the Settings screen — tap **Set as Voice Input** at the bottom of the app's Settings page.

### 4. Install AnySoftKeyboard

Gboard uses its own built-in Google speech engine and cannot be intercepted. Install [AnySoftKeyboard](https://play.google.com/store/apps/details?id=com.menny.android.anysoftkeyboard) from the Play Store.

### 5. Use It

1. Switch keyboard to **AnySoftKeyboard** (globe key → AnySoftKeyboard)
2. Press the **microphone button**
3. The voice overlay appears — recording starts immediately
4. Tap the **Send** button to transcribe and insert text
5. Switch back to Gboard via the globe key when typing manually

### Troubleshooting

**App not in app drawer?** On OnePlus/OxygenOS, launch via ADB (see step 2) or open AnySoftKeyboard and press the mic button — the overlay will open the app.

**"Voice input" not visible in settings?** On OnePlus/OxygenOS, reboot the device.

**Gboard doesn't use the service?** Gboard has its own speech engine. Install AnySoftKeyboard instead.

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
- **STT-Anbieter** (z. B. Groq) → API-Token eingeben → Validate & Fetch Models → Whisper-Modell auswählen
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

### 3. Als Standard-Spracheingabe festlegen

#### Standard-Android (Pixel, etc.)
**Einstellungen → System → Sprachen & Eingabe → Spracheingabe → Polished Recognition**

#### Samsung (Android 11)
Das "Spracheingabe"-Menü ist versteckt. Verwende ADB:

```bash
adb shell settings put secure voice_recognition_service com.georgernstgraf.polishedrecognition/com.georgernstgraf.polishedrecognition.service.PolishedRecognitionService
```

Prüfen:

```bash
adb shell settings get secure voice_recognition_service
```

#### OnePlus / OxygenOS (Android 12)
1. **Einstellungen → Apps → Standard-Apps → Digitaler Assistent → Spracheingabe**
2. **Polished Recognition** auswählen

Falls "Spracheingabe" nicht sichtbar ist, das Gerät neu starten. OxygenOS listet Erkennungsdienste beim Booten neu auf.

Du kannst den Dienst auch direkt in der App aktivieren — tippe unten auf der Einstellungsseite auf **Set as Voice Input**.

### 4. AnySoftKeyboard installieren

Gboard nutzt seine eigene Google-Sprachengine und kann nicht abgefangen werden. Installiere [AnySoftKeyboard](https://play.google.com/store/apps/details?id=com.menny.android.anysoftkeyboard) aus dem Play Store.

### 5. Verwendung

1. Tastatur zu **AnySoftKeyboard** wechseln (Globus-Taste → AnySoftKeyboard)
2. Auf das **Mikrofon-Symbol** tippen
3. Das Sprach-Overlay erscheint — die Aufnahme beginnt sofort
4. Auf **Send** tippen, um den Text zu transkribieren und einzufugen
5. Fur manuelles Tippen via Globus-Taste zurück zu Gboard wechseln

### Fehlerbehebung

**App nicht im App-Drawer?** Auf OnePlus/OxygenOS via ADB starten (siehe Schritt 2) oder AnySoftKeyboard öffnen und das Mikrofon antippen — das Overlay öffnet die App.

**"Spracheingabe" nicht in den Einstellungen sichtbar?** Auf OnePlus/OxygenOS das Gerät neu starten.

**Gboard nutzt den Dienst nicht?** Gboard hat eine eigene Sprachengine. Installiere stattdessen AnySoftKeyboard.
