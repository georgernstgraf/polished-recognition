# Hand Off

**2026-09-24: #95 CLOSED** (IME bottom-row icons unified, feel-check "wunderbar"); **#94 IN PROGRESS** (listing-asset refresh — `featureGraphic.png` added, screenshots + `demo.gif` still to capture). Default branch `main`. **2026-09-21: v1.3.1 (10301) RELEASED** (`c1d8b3e`, tag separate; Play edit `12594441127685593678`, all workflows green). Open: #94, #91 (watch), #82 (Peter retest), #74 (watch/polish/drafts), #75, #64.

## Open tasks

1. [ ] **#94 — finish listing-asset refresh** (sub of #74): capture **3 screenshots** of the post-#90/#92/#95 UI on the OnePlus 7T → `docs/img/` **and** `fastlane/metadata/android/en-US/images/phoneScreenshots/` (same names: `ime-recording`, `settings`, `settings-providers`); re-record `docs/img/demo.gif` (scrcpy, no `screenrecord` on Oplus); `featureGraphic.png` (1024×500) already staged. Then commit + push, bump **1.3.2 (10302)**, tag `v1.3.2` **separately**. Decide: update `whatsnew-en-GB` (short listing-refresh + #95 icon note).
2. [x] **#95 CLOSED 2026-09-24** — IME bottom-row icons (`0882890` + `15fea5b`); see DECISIONS 2026-09-24 + PITFALLS vector-clipping entry.
3. [ ] **#91 watch — v1.3.1 (10301) RELEASED 2026-09-21**: F-Droid pickup (bot, hours–days; may serve 1.3.0 first). Play alpha committed, GitHub AAB+APK present.
4. [ ] **#82 feel-check (Peter + owner)** — Peter has build-287 `app-release.apk` with test brief; INSTALLATION.md §5 restored. Peter retests on a #82 build; owner runs the same trio on the device. Then `finish` #82.
5. [ ] **#74 watch** — v1.3.0/v1.3.1 F-Droid listing pickup WITH images; Play 1.3.1 review; social-preview upload left to owner.
6. [ ] **#75 — log rate-limit headers** (`x-ratelimit-remaining-requests`/`-tokens`, reset headers, `retry-after` + 429 counts per day/model; local persistence; simple usage view in Settings).
7. [ ] **#64 — explore parallelize/hide Ogg/Opus compression latency** (measure per-stage on the S5 first).
8. [ ] **Insertion-spacing watch** — passive; refine only on new reports. Rule reference: DOMAIN.md; changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest` together.

## Known on-device gotchas

- **Devices: f6de166c = OnePlus 7T** (HD1903, Oplus, **1080×2400**); d890cc9e = S5 (SM-G900F, LineageOS 18.1, 1080×1920). `input tap` needs REAL pixels (`adb shell wm size`); screencap PNGs may display scaled.
- **Oplus blocks adb IME/secure-setting writes** (`ime list/enable/set`, `settings put secure`, `pm grant`) — Settings UI only; reads (`dumpsys`) work. S5 (userdebug) allows them.
- **HeliBoard's mic uses the auxiliary voice IME** (not the bound service) on current versions → nav-bar switcher or the Polished switch button.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/` (stt-response/llm-prompt/llm-response rotating JSON + `ime-lifecycle.log`). Screenshot sessions with a configured provider LIVE-record on field focus — cancel explicitly (#128).
- IME state: pause bars = recording, ↺ = paused; ␡ flush = discard buffer keeping mode, ⌫ (left of flush) = delete last word / long-press clears field (#90). **README screenshots live in `docs/img/`; `distribution/*.png` remain stale.**
- Pulse-contrast check (#87): while RECORDING the foreground rows breathe 0.3↔1.0 on a 1333 ms sine (`PulseAlphaPolicy.blinkAlpha`); outside RECORDING fully opaque (`PulseAlphaPolicy.target`).
- **Agent host has no attached device by default** — on-device verification is delegated to the owner; leave device-only checks as explicit items.

Last cleared: 2026-09-24 (after #95 close; next: finish #94 listing assets + release v1.3.2).
