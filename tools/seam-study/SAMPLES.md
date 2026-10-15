# Samples layout (git-ignored audio material)

`samples/` at the repo root carries the seam-research ground-truth audio. It is
**git-ignored** (`.gitignore`: `/samples/corpus/` entirely;
`/samples/retained/*` except a tracked `.gitkeep`) — the ~171 MB of material
never enters the repository; re-pull it from the device when needed.

## `samples/corpus/` — the 38-WAV seam-study corpus

- 37 nested WAV cuts (16 kHz / mono / s16le) across 7 acoustic classes
  (`de_monologue_male`, `de_interview`, `en_monologue_male`,
  `en_monologue_female`, `en_interview`, `panel_multispeaker`, `phone_radio`;
  10/30/60/150/300 s each, plus 600 s for the two English monologues) plus the
  top-level `_alpacino.wav` probe = **38 WAVs, ~171 MB** (~172 MB on device
  with `samples.json` + `REPORT.md`).
- The tracked manifest material lives in `tools/seam-study/corpus/`:
  `samples.json` (per-file sha256 + source provenance), `REPORT.md`,
  `build.sh`. The local copy is byte-verified against those sha256s — if a
  re-pull ever disagrees, the pull is broken, not the manifest.
- The **same corpus lives on the Samsung A03** (`R7SRB06XF5K`) at
  `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/samples/`
  (17 top-level entries, per-class subdirs) and stays as the on-device
  re-pull source — do NOT delete or modify anything there.
- Re-pull from the device (adb pull is BLOCKED on the app dir; `exec-out` tar
  is binary-safe, `adb shell` would mangle the stream):

  ```sh
  adb -s R7SRB06XF5K exec-out \
    'cd /sdcard/Android/data/com.georgernstgraf.polishedrecognition/files && tar cf - samples' \
    < /dev/null | tar xf - -C samples/corpus --strip-components=1
  ```

## `samples/retained/` — per-session retention pulls (#127)

Dictation evidence from the #127 audio-evidence retention
(`files/audio/<sessionId>/` → `session.wav`, `fragments/`, `uploads/`) lands
here via `tools/seam-study/pull_retained.sh`:

```sh
tools/seam-study/pull_retained.sh                       # all sessions, Samsung default
tools/seam-study/pull_retained.sh R7SRB06XF5K <sessionId>   # one specific session
```

Empty today — the installed release build on the Samsung predates #127, so
`files/audio/` does not exist yet (the script reports that state gracefully).

## Known gap — no bist-class samples

The corpus currently LACKS samples of the **bist failure class** (#126):
German word-final /st/ + pause at a fragment boundary ("bist", "ist", "lest"
…). Before the final factorial sweep of #129, targeted owner dictations of
that class must land in `samples/retained/` via the #127 retention (any
build with the retention slider turned up — it is a user setting since
#142) and join the measurement set.
