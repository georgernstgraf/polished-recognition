# Project State

Current status as of 2026-09-01 (#60 closed).

## Current Focus
**#60 (Ogg/Opus compression + stage feedback) CLOSED — fully verified on device.** Next workstream: #61 (remove VoiceRecognitionActivity + drop Material). #62 (HeliBoard RecognitionService re-add) opened, not started. MR !40029 watch + v1.2.1 release still open.

## Completed (this cycle)
- [x] #60 CLOSED: full implementation (WavReader/PcmConditioner/OpusOggTranscoder, controller transcode + WAV fallback, extension-derived multipart, compress_audio checkbox) + owner-feedback follow-up: `CompressingAudio` stage with "Compressing to .ogg …" IME bar label (84f3a32).
- [x] #60 on-device verification: Groq accepts the MediaMuxer Ogg Opus (text inserted), compressing label visible, airplane-mode → error toast without freeze, checkbox OFF → WAV path unchanged. Commits 545af3c, 84f3a32.
- [x] #61 created (remove activity + Material), #62 created (HeliBoard service re-add).

## Pending
- [ ] #61: remove VoiceRecognitionActivity + Material dependency (implementation phase ready to start).
- [ ] #62: HeliBoard mic via additive bound RecognitionService (not started).
- [ ] MR !40029 watch: F-Droid maintainer (linsui) response to the 1.2.0 bump.
- [ ] Cut v1.2.1 (versionCode bump) — consider bundling #60 + #61 into one release.

## Blockers
None.

## Next Session Suggestion
Start #61 implementation (plan already agreed with owner: relocate language logic, delete activity, drop Material/appcompat if clean), then decide release bundling.
