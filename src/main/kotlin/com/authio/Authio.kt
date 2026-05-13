package com.authio

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Authio Android / Kotlin SDK.
 *
 * Multi-org-first: a single end-user identity can belong to many
 * organizations. [Session.userId] always identifies the person; [orgId]
 * is the active organization (may be null when the user has authenticated
 * but not yet selected an org).
 */
class Authio(
    val apiKey: String,
    val baseUrl: String = "https://api.authio.com",
    private val http: OkHttpClient = OkHttpClient(),
) {
    init {
        require(apiKey.isNotBlank()) { "Authio: apiKey is required" }
    }

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    fun getUser(id: String): User =
        request("/v1/users/$id", User::class.java)!!

    fun listMemberships(userId: String): List<Membership> =
        requestList("/v1/users/$userId/memberships", Membership::class.java)

    fun switchOrganization(organizationId: String): Session {
        val body = mapOf("organization_id" to organizationId)
        val json = moshi.adapter(Map::class.java).toJson(body)
        val rb = json.toRequestBody("application/json".toMediaTypeOrNull())
        return requestWithBody("/v1/sessions/switch-org", "POST", rb, Session::class.java)!!
    }

    private fun <T : Any> request(path: String, cls: Class<T>): T? {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("User-Agent", "authio-kotlin/0.1.0")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AuthioError(resp.code, "request_failed", resp.message)
            }
            val body = resp.body?.string() ?: return null
            moshi.adapter(cls).fromJson(body)
        }
    }

    private fun <T : Any> requestWithBody(
        path: String,
        method: String,
        body: okhttp3.RequestBody,
        cls: Class<T>
    ): T? {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .method(method, body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("User-Agent", "authio-kotlin/0.1.0")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AuthioError(resp.code, "request_failed", resp.message)
            }
            val raw = resp.body?.string() ?: return null
            moshi.adapter(cls).fromJson(raw)
        }
    }

    private fun <T : Any> requestList(path: String, cls: Class<T>): List<T> {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("User-Agent", "authio-kotlin/0.1.0")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AuthioError(resp.code, "request_failed", resp.message)
            }
            val raw = resp.body?.string() ?: return emptyList()
            val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, cls)
            moshi.adapter<List<T>>(type).fromJson(raw) ?: emptyList()
        }
    }
}

class AuthioError(val status: Int, val code: String, message: String) : RuntimeException(message)

@JsonClass(generateAdapter = true)
data class User(
    val id: String,
    val project_id: String,
    val email: String,
    val email_verified: Boolean,
    val name: String? = null,
    val avatar_url: String? = null,
    val default_organization_id: String? = null,
)

@JsonClass(generateAdapter = true)
data class Organization(
    val id: String,
    val project_id: String,
    val name: String,
    val slug: String,
)

@JsonClass(generateAdapter = true)
data class Membership(
    val id: String,
    val project_id: String,
    val user_id: String,
    val organization_id: String,
    val role: String,
    val status: String,
)

@JsonClass(generateAdapter = true)
data class Session(
    val session_id: String,
    val user_id: String,
    val org_id: String? = null,
    val role: String? = null,
)
