package com.georgernstgraf.polishedrecognition.api

import com.georgernstgraf.polishedrecognition.api.dto.ModelsResponse
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap

interface OpenAiSttApiService {

    @GET("models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<ModelsResponse>

    @GET("models")
    fun listModelsSync(
        @Header("Authorization") authorization: String
    ): Call<ModelsResponse>

    @Multipart
    @POST("audio/transcriptions")
    suspend fun transcribeAudio(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("response_format") responseFormat: RequestBody,
        /** Whisper conditioning prompt (#117); null parts are omitted. */
        @Part("prompt") prompt: RequestBody?,
        /**
         * #184: ordinary alpha-numeric multipart fields — `session_id`,
         * `fragment_index`, `attempt`. An EMPTY map keeps the request
         * byte-identical to the pre-#184 wire format (a server that ignores
         * unknown fields, i.e. every OpenAI-compatible one, is unaffected).
         * The future server-side idempotency key is
         * `session_id:fragment_index`.
         */
        @PartMap extraFields: Map<String, @JvmSuppressWildcards RequestBody> = emptyMap()
    ): Response<SttResponse>

    @Multipart
    @POST("audio/transcriptions")
    fun transcribeAudioSync(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("response_format") responseFormat: RequestBody,
        /** Whisper conditioning prompt (#117); null parts are omitted. */
        @Part("prompt") prompt: RequestBody?,
        /** #184: see [transcribeAudio]; empty = unchanged wire format. */
        @PartMap extraFields: Map<String, @JvmSuppressWildcards RequestBody> = emptyMap()
    ): Call<SttResponse>
}
