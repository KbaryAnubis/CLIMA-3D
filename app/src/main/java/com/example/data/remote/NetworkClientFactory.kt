package com.example.data.remote

import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Official public base URLs for Clima 3D's keyless meteorological providers.
 */
object ClimaApiEndpoints {
    const val OPEN_METEO_FORECAST_BASE_URL: String = "https://api.open-meteo.com/"
    const val OPEN_METEO_AIR_QUALITY_BASE_URL: String = "https://air-quality-api.open-meteo.com/"
    const val OPEN_METEO_GEOCODING_BASE_URL: String = "https://geocoding-api.open-meteo.com/"
    const val RAINVIEWER_BASE_URL: String = "https://api.rainviewer.com/"

    const val USER_AGENT: String = "Clima3D-Android/1.0 (${BuildConfig.APPLICATION_ID})"

    const val CONNECT_TIMEOUT_SECONDS: Long = 12L
    const val READ_TIMEOUT_SECONDS: Long = 15L
    const val WRITE_TIMEOUT_SECONDS: Long = 15L
    const val CALL_TIMEOUT_SECONDS: Long = 25L
}

/**
 * Factory for shared Moshi, OkHttpClient, and Retrofit API service instances.
 * Supports custom [OkHttpClient] and base URLs for deterministic unit/Robolectric tests.
 */
object NetworkClientFactory {

    fun createMoshi(): Moshi =
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

    fun createOkHttpClient(
        additionalInterceptors: List<Interceptor> = emptyList(),
        enableLogging: Boolean = BuildConfig.DEBUG,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(ClimaApiEndpoints.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(ClimaApiEndpoints.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(ClimaApiEndpoints.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(ClimaApiEndpoints.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                    .newBuilder()
                    .header("User-Agent", ClimaApiEndpoints.USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }

        additionalInterceptors.forEach { builder.addInterceptor(it) }

        if (enableLogging) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(logging)
        }

        return builder.build()
    }

    fun createRetrofit(
        baseUrl: String,
        okHttpClient: OkHttpClient,
        moshi: Moshi,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    fun createForecastApi(
        okHttpClient: OkHttpClient,
        moshi: Moshi,
        baseUrl: String = ClimaApiEndpoints.OPEN_METEO_FORECAST_BASE_URL,
    ): OpenMeteoForecastApi =
        createRetrofit(baseUrl, okHttpClient, moshi).create(OpenMeteoForecastApi::class.java)

    fun createAirQualityApi(
        okHttpClient: OkHttpClient,
        moshi: Moshi,
        baseUrl: String = ClimaApiEndpoints.OPEN_METEO_AIR_QUALITY_BASE_URL,
    ): OpenMeteoAirQualityApi =
        createRetrofit(baseUrl, okHttpClient, moshi).create(OpenMeteoAirQualityApi::class.java)

    fun createGeocodingApi(
        okHttpClient: OkHttpClient,
        moshi: Moshi,
        baseUrl: String = ClimaApiEndpoints.OPEN_METEO_GEOCODING_BASE_URL,
    ): OpenMeteoGeocodingApi =
        createRetrofit(baseUrl, okHttpClient, moshi).create(OpenMeteoGeocodingApi::class.java)

    fun createRainViewerApi(
        okHttpClient: OkHttpClient,
        moshi: Moshi,
        baseUrl: String = ClimaApiEndpoints.RAINVIEWER_BASE_URL,
    ): RainViewerApi =
        createRetrofit(baseUrl, okHttpClient, moshi).create(RainViewerApi::class.java)
}
