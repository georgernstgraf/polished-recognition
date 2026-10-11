# Acoustic seam-study corpus — build report

Host: think (graf.priv.at). Device: f6de166c (OnePlus 7T, Android 12), app com.georgernstgraf.polishedrecognition.
Cuts: 16 kHz / mono / signed-16-bit PCM / RIFF-WAVE, all verified by ffprobe (37 files, 7 classes).
Mount note: `~/Music/murl` was an empty (unmounted) dir at start; mounted `sshfs murl:/home/www/Music ~/Music/murl` (read-only).

## Manifest (class | source under ~/Music/murl | start | durations)
| class | source | start(s) | durations(s) |
|---|---|---|---|
| de_monologue_male | `_VORTRÄGE/Buermann/Buermann 20_11_Erlebnisschule.mp3` | 120 | 10,30,60,150,300 |
| de_interview | `@Audiobooks/Konrad Paul Liessmann/Denken und Leben I - CD 5/06 Philosophie und Öffentlichkeit - Mit Manfred Jochum im Gespräch.mp3` | 120 | 10,30,60,150,300 |
| en_monologue_male | `@Audiobooks/Fear and Loathing in Las Vegas CD 1 - 01.mp3` | 60 | 10,30,60,150,300,**600** |
| en_monologue_female | `@Esoterik/Gangaji/Gangaji - Who are You - complete/Gangaji - Who Are You - 04 of 19 - Searching and Awakening.mp3` | 60 | 10,30,60,150,300,**600** |
| en_interview | `Scared Weird Little Guys/Live at 42 Walnut Crescent/21 - Scared Weird Little Guys - Kiss.mp3` | 30 | 10,30,60,150,300 |
| panel_multispeaker | `_VORTRÄGE/Pöltner/Poeltner - Das Hoeren auf das Ungehoerte … 2018-01.12 - Frage und Antwort.ogg` | 120 | 10,30,60,150,300 |
| phone_radio | `@Comedy/Achmed - AOL Hotline.mp3` | 30 | 10,30,60,150,300 |

The two 600 s classes are **en_monologue_male** and **en_monologue_female** (longest clean sources).

## Per class: confidence & uncertainty
- **de_monologue_male** — high. German single-speaker lecture ("Erlebnisschule"). Gender inferred from name/format (male); metadata silent.
- **de_interview** — high. Title explicitly a two-person Gespräch (Liessmann ↔ Jochum); 20+ pauses/600 s consistent with dialogue.
- **en_monologue_male** — high. English audiobook, Hunter S. Thompson; single read voice. Narrator gender assumed male (not in tags).
- **en_monologue_female** — medium-high. Gangaji (US female teacher); rollout spectra flat, pause structure of continuous talk. Uncertainty: this "Who Are You" CD may include audience Q&A, so not guaranteed pure monologue.
- **en_interview** — **LOW**. No genuine English interview exists in the collection. Best 2+ speaker substitute: an Australian two-man live comedy act; may contain musical/song segments. Flagged as the weakest class.
- **panel_multispeaker** — medium. A German lecture **plus** audience "Frage und Antwort" (Q&A, several voices); not a formal panel but genuinely multi-speaker. No English panel present.
- **phone_radio** — high for band-limiting: 99% of spectral energy < 3.6 kHz (telephone band, vs >5 kHz for the mono/interview sources). Content/language (likely German phone prank) not audibly confirmed.

## Verification performed
- ffprobe on all 37 files: 16000 Hz, 1 ch, `pcm_s16le`; duration within ±0.2 s of request — PASS.
- Nesting: each class's shorter cuts are exact PCM prefixes of the longest cut (shorter cuts derived by truncating the longest) — PASS.
- sha256 of every WAV recorded in `samples.json`.

## Push
- Pushed to `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/samples/` (7 class dirs + `samples.json`).
- 161,601,628 bytes of WAV (37 files) + 12,059 B manifest; device `du` 162,290,629 B (includes pre-existing `_smoke/` and block overhead).
- All 7 device class dirs exist; 300 s files byte-size matched local for every class.

## Not found
- A real English interview/conversation (longest English 2-speaker files were <300 s or music-backed).
- A verified female-narrated English audiobook; speaker-count/gender cannot be heard, only inferred.
