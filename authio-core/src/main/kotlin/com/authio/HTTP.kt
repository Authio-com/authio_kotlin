@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.authio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** SDK release version, surfaced in the User-Agent and useful for telemetry. */
const val AUTHIO_SDK_VERSION: String = "0.1.0-alpha.0"

/**
 * Thin async wrapper around OkHttp. Public so the Android module can
 * reuse it for its own platform-specific needs without re-implementing
 * the JSON / error-decoding boilerplate.
 *
 * Design notes:
 *  * One `OkHttpClient` per [HttpTransport] — clients are heavy, so the
 *    parent [com.authio.Client] holds exactly one and feeds it in.
 *  * Each call returns the parsed body or throws an [AuthioError]
 *    subclass. We never swallow / convert exceptions silently.
 */
internal class HttpTransport(
    private val baseUrl: String,
    private val publishableKey: String,
    private val ok: OkHttpClient,
    private val json: Json = AuthioJson,
) {

    private val mediaJson = "application/json; charset=utf-8".toMediaType()
    private val trimmedBase = baseUrl.trimEnd('/')

    suspend fun <T> get(
        path: String,
        respSerializer: KSerializer<T>,
        bearer: String? = null,
        query: Map<String, String> = emptyMap(),
    ): T = exec(
        method = "GET",
        path = path,
        body = null,
        bearer = bearer,
        query = query,
        respSerializer = respSerializer,
    )

    suspend fun <Req, Resp> postJson(
        path: String,
        body: Req,
        bodySerializer: KSerializer<Req>,
        respSerializer: KSerializer<Resp>,
        bearer: String? = null,
    ): Resp {
        val payload = json.encodeToString(bodySerializer, body).toRequestBody(mediaJson)
        return exec(
            method = "POST",
            path = path,
            body = payload,
            bearer = bearer,
            respSerializer = respSerializer,
        )
    }

    suspend fun postNoResponse(
        path: String,
        rawJsonBody: String,
        bearer: String? = null,
    ) {
        val payload = rawJsonBody.toRequestBody(mediaJson)
        execRaw(method = "POST", path = path, body = payload, bearer = bearer).close()
    }

    suspend fun <Resp> postRaw(
        path: String,
        rawJsonBody: String,
        respSerializer: KSerializer<Resp>,
        bearer: String? = null,
        query: Map<String, String> = emptyMap(),
    ): Resp {
        val payload = rawJsonBody.toRequestBody(mediaJson)
        return execWithBytes(
            method = "POST",
            path = path,
            body = payload,
            bearer = bearer,
            query = query,
        ).let { decodeOrThrow(it, respSerializer) }
    }

    suspend fun <T> delete(path: String, respSerializer: KSerializer<T>, bearer: String? = null): T =
        exec(
            method = "DELETE",
            path = path,
            body = null,
            bearer = bearer,
            respSerializer = respSerializer,
        )

    private suspend fun <T> exec(
        method: String,
        path: String,
        body: RequestBody?,
        bearer: String?,
        query: Map<String, String> = emptyMap(),
        respSerializer: KSerializer<T>,
    ): T {
        val raw = execWithBytes(method, path, body, bearer, query)
        return decodeOrThrow(raw, respSerializer)
    }

    private fun <T> decodeOrThrow(rawBody: String, ser: KSerializer<T>): T {
        if (rawBody.isEmpty()) {
            throw AuthioError.Decode("empty response body where ${ser.descriptor.serialName} was expected")
        }
        return try {
            json.decodeFromString(ser, rawBody)
        } catch (t: Throwable) {
            throw AuthioError.Decode("failed to decode ${ser.descriptor.serialName}: ${t.message}", t)
        }
    }

    private suspend fun execWithBytes(
        method: String,
        path: String,
        body: RequestBody?,
        bearer: String?,
        query: Map<String, String> = emptyMap(),
    ): String {
        val url = "$trimmedBase$path".toHttpUrl().newBuilder().apply {
            for ((k, v) in query) addQueryParameter(k, v)
        }.build()

        val req = Request.Builder()
            .url(url)
            .method(method, body)
            .addHeader("Authorization", "Bearer ${bearer ?: publishableKey}")
            .addHeader("X-Authio-Publishable-Key", publishableKey)
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "authio-kotlin/$AUTHIO_SDK_VERSION")
            .build()

        return execRaw(req)
    }

    private suspend fun execRaw(
        method: String,
        path: String,
        body: RequestBody?,
        bearer: String?,
    ): Response {
        val req = Request.Builder()
            .url("$trimmedBase$path")
            .method(method, body)
            .addHeader("Authorization", "Bearer ${bearer ?: publishableKey}")
            .addHeader("X-Authio-Publishable-Key", publishableKey)
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "authio-kotlin/$AUTHIO_SDK_VERSION")
            .build()
        return execRawAndCheck(req)
    }

    private suspend fun execRaw(req: Request): String {
        val resp = execRawAndCheck(req)
        val text = resp.body?.string().orEmpty()
        resp.close()
        return text
    }

    private suspend fun execRawAndCheck(req: Request): Response = withContext(Dispatchers.IO) {
        val resp = ok.newCall(req).await()
        if (!resp.isSuccessful) {
            val raw = resp.body?.string().orEmpty()
            resp.close()
            val parsed = runCatching {
                json.decodeFromString(WireError.serializer(), raw)
            }.getOrNull()
            throw AuthioError.Api(
                message = parsed?.message ?: "HTTP ${resp.code}: $raw",
                code = parsed?.code ?: "http_${resp.code}",
                status = resp.code,
                requestId = parsed?.requestId,
            )
        }
        resp
    }
}

/** Suspend-friendly OkHttp `Call.execute` that propagates cancellation. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(AuthioError.Network(e.message ?: "network failure", e))
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
}
