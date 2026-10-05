# Notification microphone icon (#109)

Design reference for the small icon of the ongoing **voice-input notification**
(IME channel `voice_recognition_ime`, id 1002; service channel
`voice_recognition_service`, id 1003).

## Where the icon is used

- Active drawable: `app/src/main/res/drawable/ic_notification_mic.xml` (**variant B**)
- Wired in:
  - `PolishedVoiceInputIME.kt` → `buildNotification()` (`setSmallIcon`)
  - `PolishedRecognitionService.kt` → `buildNotification()` (`setSmallIcon`)

## Template

The previous icon was the platform drawable
`android.R.drawable.ic_btn_speak_now`, which AOSP ships as a **bitmap**, not a
vector:

```
frameworks/base/core/res/res/drawable-{m,h,xh,xxh}dpi/ic_btn_speak_now.png
```

Its silhouette (a studio / condenser microphone: capsule + yoke arms + stem +
elliptical foot) was measured from the 96 px (xxhdpi) bitmap and redrawn as a
24×24 vector. The original capsule is only ~5.5 units wide and the glyph leaves
a lot of empty canvas — hence the widened variants below.

## Variants

All three share the same construction; only the capsule width / stroke weights
differ (capsule width in 24-unit space):

| File | Capsule width | vs. original | Character |
|------|---------------|--------------|-----------|
| `variant-b.xml` | 6.6 | ≈ +20 % | closest to the original, just bolder — **shipped** |
| `variant-c.xml` | 7.6 | ≈ +38 % | moderate widening |
| `variant-d.xml` | 8.3 | ≈ +51 % | boldest |

`comparison.png`: AOSP original (grey) next to B, C, D.

## Notes

- Android renders notification small icons as a **monochrome (alpha)
  silhouette** — the system tints it, so the `#FFFFFF` fill/stroke here is only
  a placeholder; the colour is not ours to choose.
- Android `VectorDrawable` has **no `<ellipse>`/`<rect>` element** — the
  elliptical foot is a `<path>` (`M… a rx,ry 0 1 0 … Z`).
- SVG sources used to render the previews are reproducible from these vector
  files; there is no runtime dependency on this folder (it is documentation).

## Switching variant

Copy the chosen `variant-*.xml` over
`app/src/main/res/drawable/ic_notification_mic.xml` and rebuild
(`./gradlew assembleRelease`).
