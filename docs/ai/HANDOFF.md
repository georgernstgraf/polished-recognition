# Hand Off

**Active: auxiliary voice IME (MR !40029, @bhavyashah04122005).** Implemented and builds green (`assembleRelease` + `test`). Bound `RecognitionService` removed; `VoiceRecognitionActivity` (intent path, full UI) kept; new `PolishedVoiceInputIME` + `VoiceSessionController` (shared record→transcribe). `INSTALLATION.md` rewritten. Docs updated (DECISIONS/STATE/PITFALLS). **Two open items before closing the loop:**

1. **On-device verification (user):** enable the IME in system keyboard settings → select Polished Recognition as voice input in HeliBoard/Fossify/OpenBoard → confirm `commitText` into a text field; confirm pause/resume + quick lang/raw still work in the full-screen activity.
2. **F-Droid MR !40029 metadata bump (after new release):** the fdroiddata worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition` is **unstuck** (`git rebase --abort` → `add-polished-recognition` @ `ae5b1e5d`, clean). To ship the IME via F-Droid: cut a new app release (e.g. 1.2.0, tag `v1.2.0` → `release.yml` builds signed APK+AAB) → bump `metadata/com.georgernstgraf.polishedrecognition.yml` (versionName/versionCode/commit; keep `Binaries`/`AllowedAPKSigningKeys` if signing key unchanged) → collapse the stale version-pinning commits (`git reset --soft <base>` + single commit) → comment on MR !40029 → force-push the branch.

**GitLab token hygiene (user action pending):** the old `glpat-…` was exposed in-session; remote URL + local `credential.helper` were stripped and `credential.helper=store` set globally. **User must:** revoke the old token on GitLab web, create a new one (scopes `api` + `write_repository`), and run `git fetch` in `~/repos/schurlix/fdroiddata` entering `oauth2`:<new> at the prompt (do **not** paste the token into this chat).

Last cleared: 2026-08-22. Knowledge files current.
