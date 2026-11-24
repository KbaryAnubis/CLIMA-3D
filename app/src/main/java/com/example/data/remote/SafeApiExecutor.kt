package com.example.data.remote

import com.example.data.model.WeatherError
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Internal outcome of a single remote HTTP call before repository cache-fallback resolution.
 */
sealed class RemoteCallOutcome<out T> {
    data class Success<T>(val body: T) : RemoteCallOutcome<T>()
    data class Failure(val error: WeatherError) : RemoteCallOutcome<Nothing>()
}

/**
 * Executes Retrofit suspend requests and translates HTTP, timeout, I/O, and Moshi JSON errors
 * into typed [WeatherError] instances while always preserving coroutine cancellation.
 */
object SafeApiExecutor {
    suspend inline fun <T> execute(
        provider: String,
        crossinline block: suspend () -> T,
    ): RemoteCallOutcome<T> {
        return try {
            RemoteCallOutcome.Success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (http: HttpException) {
            val serverBody = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
            RemoteCallOutcome.Failure(
                WeatherError.HttpError(
                    code = http.code(),
                    serverMessage = serverBody?.take(240)?.takeIf { it.isNotBlank() } ?: http.message(),
                    provider = provider,
                ),
            )
        } catch (timeout: SocketTimeoutException) {
            RemoteCallOutcome.Failure(
                WeatherError.TimeoutError(
                    provider = provider,
                    causeMessage = timeout.message,
                ),
            )
        } catch (interrupted: InterruptedIOException) {
            RemoteCallOutcome.Failure(
                WeatherError.TimeoutError(
                    provider = provider,
                    causeMessage = interrupted.message,
                ),
            )
        } catch (jsonData: JsonDataException) {
            RemoteCallOutcome.Failure(
                WeatherError.SerializationError(
                    provider = provider,
                    causeMessage = jsonData.message,
                ),
            )
        } catch (jsonEncoding: JsonEncodingException) {
            RemoteCallOutcome.Failure(
                WeatherError.SerializationError(
                    provider = provider,
                    causeMessage = jsonEncoding.message,
                ),
            )
        } catch (io: IOException) {
            RemoteCallOutcome.Failure(
                WeatherError.NetworkUnavailableError(
                    provider = provider,
                    causeMessage = io.message,
                ),
            )
        } catch (unexpected: Exception) {
            RemoteCallOutcome.Failure(
                WeatherError.UnknownError(
                    provider = provider,
                    causeMessage = unexpected.message,
                ),
            )
        }
    }
}
