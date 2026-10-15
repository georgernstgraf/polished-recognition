package com.georgernstgraf.polishedrecognition

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import com.georgernstgraf.polishedrecognition.api.OpenAiChatApiService
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.config.ProviderPresetLoader
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.config.SttFragmentSizer
import com.georgernstgraf.polishedrecognition.pipeline.PromptStore
import com.georgernstgraf.polishedrecognition.pipeline.AudioEvidenceStore
import com.georgernstgraf.polishedrecognition.pipeline.CoreLog
import com.georgernstgraf.polishedrecognition.pipeline.CoreOs
import com.georgernstgraf.polishedrecognition.pipeline.ResponseLoggerInterceptor
import com.georgernstgraf.polishedrecognition.pipeline.RotatingJsonLogger
import com.georgernstgraf.polishedrecognition.pipeline.SttRequestRunner
import com.georgernstgraf.polishedrecognition.pipeline.TranscriptionPipeline
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.georgernstgraf.polishedrecognition.ui.CrashDialogActivity
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PolishedRecognitionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // #146: :core no longer imports android.util.Log directly — route its
        // output through the same android.util.Log (identical tags/priorities)
        // so behavior is unchanged. Must happen before any pipeline code logs.
        CoreLog.install(object : CoreLog.Backend {
            override fun d(tag: String, message: String) {
                Log.d(tag, message)
            }

            override fun i(tag: String, message: String) {
                Log.i(tag, message)
            }

            override fun w(tag: String, message: String) {
                Log.w(tag, message)
            }

            override fun e(tag: String, message: String, throwable: Throwable?) {
                if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
            }
        })
        // #146: :core worker threads get their os-level priority through the
        // CoreOs hook — same call FragmentPreparer made inline before the move.
        CoreOs.install(object : CoreOs.Backend {
            override fun setThreadPriority(priority: Int) {
                android.os.Process.setThreadPriority(priority)
            }
        })
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val type = throwable.javaClass.name
            val message = throwable.message ?: ""
            val stackTrace = StringWriter().let { sw ->
                throwable.printStackTrace(PrintWriter(sw))
                sw.toString()
            }

            Intent(this, CrashDialogActivity::class.java).apply {
                putExtra(CrashDialogActivity.EXTRA_EXCEPTION_TYPE, type)
                putExtra(CrashDialogActivity.EXTRA_EXCEPTION_MESSAGE, message)
                putExtra(CrashDialogActivity.EXTRA_STACK_TRACE, stackTrace)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }.also { startActivity(it) }

            Process.killProcess(Process.myPid())
            System.exit(2)
        }
        // #142: time-based evidence eviction at startup — a retention-horizon
        // change takes effect even when no dictation follows. Best-effort and
        // off the main thread (deleteRecursively on old bundles may take a
        // moment); failures are silent by store contract.
        Thread {
            try {
                audioEvidenceStore.evict()
            } catch (_: Throwable) {
            }
        }.apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * #132: the per-stream log history — since #142 a SINGLE unified depth
     * for every build type (the streams are live debugging only; the
     * forensic record lives in `session.json` per session).
     */
    val jsonLogger by lazy {
        RotatingJsonLogger(
            File(getExternalFilesDir(null) ?: filesDir, "logs"),
            maxCount = BuildConfig.LOG_ROTATION_DEPTH
        )
    }

    val okHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(ResponseLoggerInterceptor(jsonLogger))
            // Read/write 600 s = OpenAI-SDK parity with aitranscribe (#115):
            // a CPU-only LAN Whisper server can need several minutes per
            // 10-min chunk; the old 120 s read timeout aborted the call and
            // parked the session as PAUSED while the server was still working.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(600, TimeUnit.SECONDS)
            .build()
    }

    val settingsStore by lazy {
        SettingsStore(this, evidenceRetentionDefaultSeconds = BuildConfig.AUDIO_EVIDENCE_RETENTION_DEFAULT_SECONDS)
    }
    val promptStore by lazy { PromptStore(this) }
    val providerPresetLoader by lazy { ProviderPresetLoader(this) }

    /**
     * #127/#142: per-session audio-evidence retention
     * (`files/audio/<sessionId>/`, next to `logs/` — the adb-readable root
     * on release devices). The forensic bundle is `uploads/` + `session.json`
     * (#142); the retention horizon is a USER setting (the 0→50-years log
     * slider, default `BuildConfig.AUDIO_EVIDENCE_RETENTION_DEFAULT_SECONDS`:
     * 0 in release — privacy —, 30 days in debug). Age-based eviction
     * + the invisible storage safety valve live in the store.
     */
    val audioEvidenceStore by lazy {
        AudioEvidenceStore(
            File(getExternalFilesDir(null) ?: filesDir, "audio"),
            // #142: the retention horizon is a USER setting (SettingsStore),
            // read live so a slider change takes effect immediately; the
            // storage safety valve is a constant, invisible to the user.
            retentionSeconds = { settingsStore.evidenceRetentionSeconds }
        )
    }

    val transcriptionPipeline by lazy {
        TranscriptionPipeline(
            getSttApi = { baseUrl -> getSttApi(baseUrl) },
            getChatApi = { baseUrl -> getChatApi(baseUrl) },
            promptStore = promptStore,
            settingsStore = settingsStore,
            logger = jsonLogger,
            sttRunner = SttRequestRunner(
                getSttApi = { baseUrl -> getSttApi(baseUrl) },
                logger = jsonLogger,
                // Per-provider latency profile feed (#116 Phase 2): every
                // successful evidenced request is a (S, t) sample. The
                // runner invokes this only on success with duration parsed.
                onCompletion = { completion ->
                    val durationMs = completion.durationMs
                    if (durationMs != null) {
                        runCatching {
                            settingsStore.recordSttSample(
                                baseUrl = completion.config.baseUrl,
                                model = completion.config.model,
                                mediaType = completion.mediaType,
                                durationMs = durationMs,
                                elapsedMs = completion.elapsedMs
                            )
                        }.onFailure {
                            android.util.Log.w("PolishedRecognitionApp", "stt sample recording failed", it)
                        }
                    }
                }
            )
        )
    }

    val voiceSessionController by lazy {
        val fragmentSizer = SttFragmentSizer(settingsStore)
        VoiceSessionController(this, transcriptionPipeline, settingsStore, logger = jsonLogger,
            fragmentSizeProvider = { fragmentSizer.fragmentSeconds() },
            // #127: audio-evidence retention (Settings toggle; the default
            // comes from BuildConfig.AUDIO_EVIDENCE_DEFAULT — OFF in
            // release, ON in debug).
            audioEvidenceStore = audioEvidenceStore,
            audioEvidenceEnabled = { settingsStore.audioEvidence })
    }

    /**
     * Last IME rotation signal, monotonic uptime ms written by
     * `PolishedVoiceInputIME.onConfigurationChanged` and judged by
     * `RotationGate`. App-scoped so it survives IME service recreation on
     * rotation (#83 follow-up) — the old instance-flag never reached the new
     * instance on ROMs that destroy the service (Oplus), wiping the session
     * on every rotation.
     */
    @Volatile
    var imeConfigChangeMs: Long = 0L

    /**
     * Last field served by the voice IME (`packageName` + `fieldId`), judged
     * by `RotationGate` together with [imeConfigChangeMs]. A client-app
     * recreation on rotation keeps both, so the same field is recognized
     * even when Oplus rebinds input with `restarting=false`.
     */
    @Volatile
    var imeLastField: com.georgernstgraf.polishedrecognition.service.RotationGate.FieldId? = null

    /**
     * The field the current dictation was SENT from (#118), captured by
     * `PolishedVoiceInputIME` at send time. The insertion breadcrumb compares
     * it against the field at delivery: a pendingResult re-delivery across a
     * rotation that lands in a *changed* field is the prime suspect for
     * "text appeared on rotate". App-scoped so it survives IME recreation.
     */
    @Volatile
    var imeSessionField: com.georgernstgraf.polishedrecognition.service.RotationGate.FieldId? = null

    private val sttApiCache = ConcurrentHashMap<String, OpenAiSttApiService>()
    private val chatApiCache = ConcurrentHashMap<String, OpenAiChatApiService>()

    fun getSttApi(baseUrl: String): OpenAiSttApiService =
        sttApiCache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OpenAiSttApiService::class.java)
        }

    fun getChatApi(baseUrl: String): OpenAiChatApiService =
        chatApiCache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OpenAiChatApiService::class.java)
        }
}
