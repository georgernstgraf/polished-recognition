package com.georgernstgraf.polishedrecognition.api

import com.georgernstgraf.polishedrecognition.api.dto.ChatRequest
import com.georgernstgraf.polishedrecognition.api.dto.ChatResponse
import com.georgernstgraf.polishedrecognition.api.dto.ModelsResponse
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAiChatApiService {

    @GET("models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<ModelsResponse>

    @GET("models")
    fun listModelsSync(
        @Header("Authorization") authorization: String
    ): Call<ModelsResponse>

    @POST("chat/completions")
    suspend fun chat(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): Response<ChatResponse>
}
