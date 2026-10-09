package com.georgernstgraf.polishedrecognition

import android.app.Application
import android.content.Intent
import android.os.Process
import com.georgernstgraf.polishedrecognition.api.OpenAiChatApiService
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.config.ProviderPresetLoader
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.pipeline.PromptStore
import com.georgernstgraf.polishedrecognition.pipeline.ResponseLoggerInterceptor
import com.georgernstgraf.polishedrecognition.pipeline.RotatingJsonLogger
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
    }

    val jsonLogger by lazy {
        RotatingJsonLogger(File(getExternalFilesDir(null) ?: filesDir, "logs"))
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

    val settingsStore by lazy { SettingsStore(this) }
    val promptStore by lazy { PromptStore(this) }
    val providerPresetLoader by lazy { ProviderPresetLoader(this) }

    val transcriptionPipeline by lazy {
        TranscriptionPipeline(
            getSttApi = { baseUrl -> getSttApi(baseUrl) },
            getChatApi = { baseUrl -> getChatApi(baseUrl) },
            promptStore = promptStore,
            settingsStore = settingsStore,
            logger = jsonLogger
        )
    }

    val voiceSessionController by lazy {
        VoiceSessionController(this, transcriptionPipeline, settingsStore, logger = jsonLogger,
            shadowSttEnabled = {
                settingsStore.sttProvider?.baseUrl?.let { isLocalSttEndpoint(it) } == true
            })
    }

    /**
     * The #117 shadow comparison runs only against LOCAL STT endpoints:
     * the extra full-context pass costs GPU time on the user's own box
     * (gregor measures 22–29× realtime), but real money against cloud
     * providers. Pure string heuristic — no DNS on any caller thread.
     */
    private fun isLocalSttEndpoint(baseUrl: String): Boolean {
        val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".local")) return true
        if (host.startsWith("[")) return true // bracketed IPv6 loop/link-local literals
        return Regex("^127\\.|^10\\.|^192\\.168\\.|^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
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
