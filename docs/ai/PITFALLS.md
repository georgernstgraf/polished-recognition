# Pitfalls

Things that do not work, subtle bugs, and non-obvious constraints.
Read this file carefully before making changes in affected areas.

- RecognitionService cannot be annotated with @AndroidEntryPoint (Hilt). Manual DI via Application class is required.
- `GET /v1/models` is not universally supported. GROQ returns it, some local models (Ollama/LM Studio) may not. Always handle 404 by falling back to free-text model input.
- The `AudioRecord` thread must run at `THREAD_PRIORITY_URGENT_AUDIO` to avoid buffer underruns on slower devices.
- `callback.results()` must be called from the same thread that received `onStopListening()` — use `runOnUiThread` or Main dispatcher.
- WAV header requires little-endian byte ordering. Standard `DataOutputStream.writeInt()` is big-endian and will produce corrupt WAV files.
- Retrofit instances for dynamic base URLs must be cached. Creating a new Retrofit instance per call leaks OkHttp connection pools.
- `SpeechRecognizer.RESULTS_RECOGNITION` expects `ArrayList<String>` — plain `List<String>` causes a ClassCastException in Bundle.
- `settings.gradle.kts` must use `dependencyResolutionManagement` (not `dependencyResolution`) for Gradle 8.x.
- `runCatching { }` is not a coroutine scope — suspend functions cannot be called inside it. Use `withContext(Dispatchers.IO)` instead.
- `RecognitionService.Callback.readyForSpeech()` requires a `Bundle` parameter (pass `Bundle.EMPTY`) in API 30+.
- The STT `response_format=verbose_json` returns both `text` and `language` fields in one call — prefer it over separate calls for language detection.
