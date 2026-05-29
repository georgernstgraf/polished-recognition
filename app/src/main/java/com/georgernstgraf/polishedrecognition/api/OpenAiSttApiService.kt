package com.georgernstgraf.polishedrecognition.api

import com.georgernstgraf.polishedrecognition.api.dto.ModelsResponse
import com.georgernstgraf.polishedrecognition.api.dto.SttResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface OpenAiSttApiService {

    @GET("models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<ModelsResponse>

    @Multipart
    @POST("audio/transcriptions")
    suspend fun transcribeAudio(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("response_format") responseFormat: RequestBody
    ): Response<SttResponse>
}
