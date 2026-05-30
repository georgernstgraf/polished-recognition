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
- Kotlin 1.9.x compiler cannot read Kotlin 2.1.x stdlib metadata. When test dependencies pull newer Kotlin stdlib transitively, upgrade the project Kotlin plugin to match.
- `PromptStore` must initialize `gson` before `defaults`. Kotlin initializes properties in declaration order. A `loadDefaults()` call that references `gson` after its declaration hits an uninitialized property → NPE → swallowed by catch → fallback defaults used silently.
- `SttProviderConfig` and `LlmProviderConfig` data classes have 5 constructor params with `id` as the only default. In Kotlin 2.1.x, positional args with default params at the front require named args for the rest.
- The `ChatResponse` constructor takes `List<ChatChoice>`, not `List<ChatMessage>`. Each `ChatChoice` wraps a `ChatMessage`.
- `captureNullable<ChatRequest>()` requires an explicit type parameter in the slot approach. Prefer `slot<ChatRequest>()` for capture semantics.
- `TextInputLayout.error` persists indefinitely until explicitly set to `null`. If no `TextWatcher` clears it on text change, the red error state remains even after the user corrects the input. Always pair `textField.addTextChangedListener { layout.error = null }` when setting `layout.error` from validation logic.
- R8 full mode strips generic type information from the `Continuation<? super Response<T>>` parameter in Kotlin suspend functions within Retrofit interfaces. `HttpServiceMethod.parseAnnotations` casts to `ParameterizedType` and crashes with `ClassCastException: java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType`. Use non-suspend `Call<T>` return types in code paths that run on `Dispatchers.IO` to avoid the `Continuation` type resolution entirely.
- AGP 8.2.2 bundles R8 that cannot parse Kotlin 2.1.x metadata. The R8 warning "An error occurred when parsing kotlin metadata" appears on every release build. Minimum AGP version for Kotlin 2.1 is 8.6, minimum R8 is 8.6.17. Upgrade AGP to 8.7+.
- Kotlin 2.1.x makes `javaClass.classLoader` return type `ClassLoader?` (nullable). Calling `.getResource()` without `?.` produces a compilation warning. Use `javaClass.classLoader?.getResource(…)`.
- `ResponseBody.create(MediaType?, String)` is deprecated in OkHttp 4.12.0, but the replacement extension `String.toResponseBody(MediaType?)` does not exist in this version. Suppress the warning with `@Suppress("DEPRECATION")` rather than using the unavailable extension.
