package com.georgernstgraf.polishedrecognition

import android.app.Application
import com.georgernstgraf.polishedrecognition.api.OpenAiChatApiService
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.config.ProviderPresetLoader
import com.georgernstgraf.polishedrecognition.config.SettingsStore
import com.georgernstgraf.polishedrecognition.pipeline.PromptLogger
import com.georgernstgraf.polishedrecognition.pipeline.PromptStore
import com.georgernstgraf.polishedrecognition.pipeline.TranscriptionPipeline
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.io.File

class PolishedRecognitionApp : Application() {

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
