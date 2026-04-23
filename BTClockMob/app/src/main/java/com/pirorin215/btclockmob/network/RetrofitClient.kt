package com.pirorin215.btclockmob.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pirorin215.btclockmob.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

object RetrofitClient {

    private const val BASE_URL = "https://www.googleapis.com/"

    fun create(tokenProvider: suspend () -> String): GoogleTasksApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // Only log in debug builds
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(loggingInterceptor)
            .build()

        val contentType = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true }

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        return retrofit.create(GoogleTasksApiService::class.java)
    }
}
