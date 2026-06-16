package com.georgernstgraf.polishedrecognition

import android.app.Application
import android.content.Intent
import android.os.Process
import com.georgernstgraf.polishedrecognition.api.OpenAiChatApiService
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.config.ProviderPresetLoader
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.pipeline.PromptLogger
import com.georgernstgraf.polishedrecognition.pipeline.PromptStore
import com.georgernstgraf.polishedrecognition.pipeline.TranscriptionPipeline
import com.georgernstgraf.polishedrecognition.ui.CrashDialogActivity
import com.google.android.material.color.DynamicColors
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
        DynamicColors.applyToActivitiesIfAvailable(this)
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

    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val settingsStore by lazy { SettingsStore(this) }
    val promptStore by lazy { PromptStore(this) }
    val providerPresetLoader by lazy { ProviderPresetLoader(this) }

    val promptLogger by lazy {
        PromptLogger(File(getExternalFilesDir(null) ?: filesDir, "logs"))
    }

    val transcriptionPipeline by lazy {
        TranscriptionPipeline(
            getSttApi = { baseUrl -> getSttApi(baseUrl) },
            getChatApi = { baseUrl -> getChatApi(baseUrl) },
            promptStore = promptStore,
            settingsStore = settingsStore,
            promptLogger = promptLogger
        )
    }

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
