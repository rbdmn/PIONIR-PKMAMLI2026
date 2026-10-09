package com.smartplug.app.data.remote

import com.smartplug.app.domain.model.ApiFailure
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiFailure) : ApiResult<Nothing>
}

/** IDs for failures raised locally rather than returned by any server. */
object LocalErrorCodes {
    const val TIMEOUT = "network_timeout"
    const val NO_CONNECTIVITY = "no_connectivity"
    const val UNEXPECTED = "unexpected_error"
    const val EMPTY_BODY = "empty_response_body"
}

/**
 * Runs a Retrofit [call], mapping it into [ApiResult] and parsing the JSON error envelope on
 * failure. Understands both the target contract in design.md
 * (`{"error":{"code":...,"message":...}}`) and the flat shape already shipped by firmware today
 * (`{"error":"<code>"}`, see firmware/LOCAL-API.md) so the app keeps working against either.
 */
suspend fun <T> safeApiCall(call: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = call()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                ApiResult.Success(body)
            } else {
                // 202/204-with-no-body endpoints use Response<Unit>; treat as success.
                @Suppress("UNCHECKED_CAST")
                ApiResult.Success(Unit as T)
            }
        } else {
            ApiResult.Failure(parseErrorBody(response.code(), response.errorBody()))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SocketTimeoutException) {
        ApiResult.Failure(ApiFailure(0, LocalErrorCodes.TIMEOUT, e.message))
    } catch (e: IOException) {
        ApiResult.Failure(ApiFailure(0, LocalErrorCodes.NO_CONNECTIVITY, e.message))
    } catch (e: Exception) {
        ApiResult.Failure(ApiFailure(0, LocalErrorCodes.UNEXPECTED, e.message))
    }
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

// A plain, non-Android Moshi instance dedicated to error-body parsing. Deliberately not the
// app-wide DI Moshi/org.json: android.jar's org.json classes are stubbed to throw in plain JVM
// unit tests (no Robolectric), so error parsing must stay off any Android-framework JSON API.
private val errorBodyMapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
private val errorBodyAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(errorBodyMapType)

private fun parseErrorBody(httpCode: Int, errorBody: ResponseBody?): ApiFailure {
    val raw = errorBody?.string()
    if (raw.isNullOrBlank()) {
        return ApiFailure(httpCode, LocalErrorCodes.EMPTY_BODY)
    }
    return try {
        val json = errorBodyAdapter.fromJson(raw)
        when (val errorNode = json?.get("error")) {
            is Map<*, *> -> ApiFailure(
                httpCode = httpCode,
                errorCode = errorNode["code"] as? String ?: LocalErrorCodes.UNEXPECTED,
                message = errorNode["message"] as? String,
            )
            is String -> ApiFailure(httpCode = httpCode, errorCode = errorNode)
            else -> ApiFailure(httpCode, LocalErrorCodes.UNEXPECTED)
        }
    } catch (e: Exception) {
        ApiFailure(httpCode, LocalErrorCodes.UNEXPECTED, raw)
    }
}
