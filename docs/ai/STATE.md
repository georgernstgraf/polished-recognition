# Project State

Current status as of 2026-08-31 (#60 Ogg/Opus compression session).

## Current Focus
**#60 (optional Ogg/Opus compression before STT upload) — implemented, committed (545af3c), pushed. On-device verification still pending.** MR !40029 maintainer watch continues; v1.2.1 release still pending.

## Completed (this cycle)
- [x] #60 (in progress): full implementation — `WavReader`/`PcmConditioner`/`AudioTranscoder`/`OpusOggTranscoder` in `audio/`, `VoiceSessionController` transcode + WAV fallback, pipeline extension-derived multipart media type, `compress_audio` checkbox (end of STT section), 15 new tests. `./gradlew test` green (141 tests), `assembleRelease` green, zero new dependencies.
- [x] FFmpegKit retirement researched: binaries gone from Maven since 2025-04; decision recorded to use platform MediaCodec/MediaMuxer instead (DECISIONS.md).

## Pending
- [ ] #60 on-device verification (`installRelease`): real MediaCodec encode; Groq accepts the MediaMuxer-produced Ogg Opus; WAV-vs-OGG size comparison; toggle-off → WAV path; airplane-mode error path.
- [ ] MR !40029 watch: F-Droid maintainer (linsui) response to the 1.2.0 bump; fdroiddata worktree `~/repos/schurlix/fdroiddata-mr-polished-recognition` at `1c43ae05f`.
- [ ] Cut v1.2.1 (versionCode bump) to ship the ac75231 verification-round fixes — consider bundling #60.

## Blockers
None.

## Next Session Suggestion
On-device verification of #60 (checklist in the issue comment + HANDOFF.md), then close #60; then decide whether to fold v1.2.1 into an #60-inclusive release.
