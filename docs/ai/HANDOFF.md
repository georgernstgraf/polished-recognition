# Hand Off

**2026-09-24: v1.3.2 (10302) RELEASED** — listing-asset refresh (#94) + IME icon refinement (#95). All workflows green, Play alpha edit `07956435961582591479`, GitHub AAB+APK present. **#94 + #95 CLOSED.** Default branch `main`. Open: #91 (v1.3.1 watch), #82 (Peter retest), #74 (watch), #75, #64.

## Open tasks

1. [ ] **v1.3.2 watch** — F-Droid fdroidbot pickup of 1.3.2 **with** the new listing assets (screenshots + featureGraphic); Play alpha 1.3.2 review. Check upstream `metadata/com.georgernstgraf.polishedrecognition.yml` for `CurrentVersion`/`CurrentVersionCode`.
2. [ ] **#91 watch** — superseded by v1.3.2 (bot likely serves 1.3.2 directly); close when the pickup is seen.
3. [ ] **#82 feel-check (Peter + owner)** — Peter retests on a #82 build; owner runs the same trio on the device. Then `finish` #82.
4. [ ] **#74 watch** — parent of #94; social-preview upload left to owner (no API). Keep open until pickup verified.
5. [ ] **#75 — log rate-limit headers** (`x-ratelimit-remaining-requests`/`-tokens`, reset headers, `retry-after` + 429 counts per day/model; local persistence; simple usage view in Settings).
6. [ ] **#64 — Ogg/Opus compression latency** (measure per-stage on the S5 first).
7. [ ] **Insertion-spacing watch** — passive; refine only on new reports. Rule reference DOMAIN.md; changes must update `InsertionSpacingPolicy` + `InsertionSpacingPolicyTest`.

## Known on-device gotchas

- **Devices: f6de166c = OnePlus 7T** (HD1903, Oplus, 1080×2400); d890cc9e = S5 (SM-G900F, LineageOS 18.1, 1080×1920). `input tap` needs REAL pixels (`adb shell wm size`).
- **Oplus blocks adb IME/secure-setting writes** — Settings UI only; reads (`dumpsys`) work. S5 (userdebug) allows them.
- **The OnePlus doubles as the Telegram bridge to the agent session** — chat notifications overlay a scrcpy recording. Verify the foreground app before `input tap`; record long, trim, keep the raw mp4 until the GIF is approved.
- The IME crashes on `?attr/` theme attrs — only platform attrs / `@null` / explicit colors in IME layouts.
- Diagnostic logs via adb: `/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/logs/`. Screenshot sessions with a configured provider LIVE-record on field focus — cancel explicitly (#128).
- IME state: pause bars = recording, ↺ = paused; ␡ flush = discard buffer keeping mode, ⌫ (left of flush) = delete last word / long-press clears field (#90/#95). **README screenshots live in `docs/img/`; `distribution/*.png` remain stale.**
- **README/fastlane screenshots must stay byte-identical** (`docs/img/*.png` ↔ `fastlane/.../phoneScreenshots/{1,2,3}-*.png`); verify with `md5sum` after any re-capture.
- Pulse-contrast (#87): foreground rows breathe 0.3↔1.0 on a 1333 ms sine during RECORDING; fully opaque otherwise.
- **Agent host has no attached device by default** — on-device verification is delegated to the owner; leave device-only checks as explicit items.

Last cleared: 2026-09-24 (v1.3.2 released; next: F-Droid pickup watch + finish #82).
