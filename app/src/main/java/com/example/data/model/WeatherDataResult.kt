package com.example.data.model

/**
 * Identifies whether data was fetched live from the remote API or served from local Room cache.
 */
enum class DataSourceOrigin {
    LIVE_NETWORK,
    LOCAL_CACHE,
}

/**
 * Cache loading strategy for weather, air quality, and radar repositories.
 */
sealed class CachePolicy {
    /**
     * Always attempts a live network refresh when online; transparently falls back to Room cache
     * if offline or if the remote call fails.
     */
    data object NetworkFirst : CachePolicy()

    /**
     * Serves local Room cache when younger than [maxAgeMillis]; otherwise queries the network
     * with cache fallback on error.
     */
    data class CacheIfFresh(val maxAgeMillis: Long = DEFAULT_CACHE_TTL_MILLIS) : CachePolicy()

    /**
     * Reads strictly from local Room cache without attempting network I/O.
     */
    data object CacheOnly : CachePolicy()

    companion object {
        const val DEFAULT_CACHE_TTL_MILLIS: Long = 15L * 60L * 1000L
    }
}

/**
 * Structured error hierarchy for network, HTTP, timeout, serialization, and empty payload failures.
 */
sealed class WeatherError(
    open val provider: String,
    open val userMessagePt: String,
) {
    data class HttpError(
        val code: Int,
        val serverMessage: String?,
        override val provider: String,
    ) : WeatherError(
        provider = provider,
        userMessagePt = "Erro HTTP $code ao consultar $provider.",
    )

    data class TimeoutError(
        override val provider: String,
        val causeMessage: String? = null,
    ) : WeatherError(
        provider = provider,
        userMessagePt = "Tempo esgotado ao conectar com $provider.",
    )

    data class NetworkUnavailableError(
        override val provider: String,
        val causeMessage: String? = null,
    ) : WeatherError(
        provider = provider,
        userMessagePt = "Sem conexão com a internet para consultar $provider.",
    )

    data class SerializationError(
        override val provider: String,
        val causeMessage: String? = null,
    ) : WeatherError(
        provider = provider,
        userMessagePt = "Resposta inválida recebida de $provider.",
    )

    data class EmptyResponseError(
        override val provider: String,
    ) : WeatherError(
        provider = provider,
        userMessagePt = "Nenhum dado disponível retornado por $provider.",
    )

    data class UnknownError(
        override val provider: String,
        val causeMessage: String? = null,
    ) : WeatherError(
        provider = provider,
        userMessagePt = "Falha inesperada ao consultar $provider.",
    )
}

/**
 * Typed result returned by repositories, explicitly distinguishing live network data from cached data.
 */
sealed class WeatherResult<out T> {
    data class Success<T>(
        val data: T,
        val origin: DataSourceOrigin,
        val fetchedAtEpochMillis: Long,
        val staleReason: WeatherError? = null,
    ) : WeatherResult<T>() {
        val isFromCache: Boolean
            get() = origin == DataSourceOrigin.LOCAL_CACHE
    }

    data class Failure(
        val error: WeatherError,
    ) : WeatherResult<Nothing>()
}

/**
 * Lightweight wrapper for reactive cache observations via Flow.
 */
data class CachedRecord<T>(
    val data: T,
    val fetchedAtEpochMillis: Long,
)
