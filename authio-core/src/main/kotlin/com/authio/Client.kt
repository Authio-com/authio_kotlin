package com.authio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM Authio client — covers every flow that doesn't need an
 * Activity / system UI:
 *
 *  * Magic-link send + callback consumption
 *  * Session verification + refresh + revoke
 *  * Org switching + listing memberships
 *  * Building OAuth authorize URLs (the Custom Tabs launch lives in
 *    `:authio-android`, but URL construction belongs here so we can
 *    unit-test it without the Android SDK)
 *
 * For passkey ceremonies, instantiate [com.authio.android.AuthioAndroid]
 * from the Android module — it wraps this Client and adds the
 * Credential Manager bridge.
 */
class Client internal constructor(
    val publishableKey: String,
    val apiUrl: String,
    private val transport: HttpTransport,
) {

    /**
     * Public constructor. Builds an [OkHttpClient] with sensible
     * defaults: 10 s connect, 30 s read, follow redirects off (we want
     * the magic-link callback to land on us, not the IdP). Pass your
     * own via [okHttp] if you need cookies / interceptors / TLS pinning.
     */
    constructor(
        publishableKey: String,
        apiUrl: String = DEFAULT_API_URL,
        okHttp: OkHttpClient? = null,
    ) : this(
        publishableKey = require(publishableKey),
        apiUrl = sanitizeUrl(apiUrl),
        transport = HttpTransport(
            baseUrl = sanitizeUrl(apiUrl),
            publishableKey = publishableKey,
            ok = okHttp ?: defaultOk(),
        ),
    )

    // -------------------------------------------------------------------
    //  Magic link
    // -------------------------------------------------------------------

    /**
     * Send a magic link to [destination] (email or phone-with-prefix).
     * The link, when clicked / tapped, hits `/v1/auth/magic-link/callback`
     * which then redirects to [redirectUri].
     *
     * On Android, `redirectUri` is your custom-scheme deep link
     * (e.g. `myapp://auth-callback`). On Web you pass the URL of the
     * page that calls [consumeMagicLinkCallback].
     */
    suspend fun sendMagicLink(
        destination: String,
        redirectUri: String,
        organizationId: String? = null,
    ) {
        val body = buildJsonObject {
            put("destination", destination)
            put("redirect_uri", redirectUri)
            if (organizationId != null) put("organization_id", organizationId)
        }
        transport.postNoResponse("/v1/auth/magic-link/send", body.toString())
    }

    /**
     * Consume a magic-link callback URL and exchange it for a session.
     * Pass the *full* deep-link your activity received; the SDK extracts
     * `?token=…` and POSTs it to auth-core with `Accept: application/json`,
     * which causes auth-core to return the session envelope as JSON
     * (its default behaviour for browsers is a 302 redirect).
     */
    suspend fun consumeMagicLinkCallback(url: String): AuthioSession {
        val token = extractQuery(url, "token")
            ?: throw AuthioError.InvalidCallbackUrl("magic-link callback URL has no `token` parameter")
        return transport.postRaw(
            path = "/v1/auth/magic-link/callback",
            rawJsonBody = "{}",
            respSerializer = AuthioSession.serializer(),
            query = mapOf("token" to token),
        )
    }

    // -------------------------------------------------------------------
    //  OAuth
    // -------------------------------------------------------------------

    /**
     * Build the OAuth authorize URL the user's browser tab should land
     * on. Kept synchronous because it's pure string assembly — perfect
     * for unit tests without a network. The Android module then opens
     * this URL in a Custom Tab.
     */
    fun buildOAuthAuthorizeUrl(
        provider: OAuthProvider,
        redirectUri: String,
        state: String,
        organizationId: String? = null,
    ): String {
        val builder = "$apiUrl/v1/auth/oauth/${provider.id}/authorize"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .addQueryParameter("publishable_key", publishableKey)
        if (organizationId != null) builder.addQueryParameter("organization_id", organizationId)
        return builder.build().toString()
    }

    /**
     * Parse the `?...` portion of an OAuth callback deep-link. We use
     * `java.net.URI` rather than OkHttp's `HttpUrl` because the latter
     * only accepts `http`/`https` schemes — and Android deep-links are
     * almost always custom schemes like `myapp://auth-callback`.
     */
    fun parseOAuthCallback(url: String): OAuthCallback {
        val params = parseQueryString(url)
            ?: throw AuthioError.InvalidCallbackUrl("not a valid URL: $url")

        val state = params["state"]
        val accessToken = params["access_token"]
        val sessionId = params["session_id"]
        val error = params["error"]
        val errorDescription = params["error_description"]

        if (error != null) {
            throw AuthioError.Api(
                message = errorDescription ?: error,
                code = "oauth.$error",
                status = 0,
            )
        }
        if (accessToken == null || sessionId == null) {
            throw AuthioError.InvalidCallbackUrl(
                "OAuth callback missing required `session_id` / `access_token` parameters",
            )
        }
        return OAuthCallback(
            sessionId = sessionId,
            accessToken = accessToken,
            refreshToken = params["refresh_token"],
            expiresAt = params["expires_at"] ?: "",
            state = state,
        )
    }

    // -------------------------------------------------------------------
    //  Session ops
    // -------------------------------------------------------------------

    /**
     * Best-effort verification: hit `/v1/me` with the session's bearer
     * token; success = still valid. A future revision should additionally
     * verify the access-token JWT against the JWKS locally to avoid the
     * round-trip (documented in the README).
     */
    suspend fun verify(session: AuthioSession): Boolean = try {
        transport.get("/v1/me", User.serializer(), bearer = session.accessToken)
        true
    } catch (e: AuthioError.Api) {
        if (e.status == 401 || e.status == 403) false else throw e
    }

    suspend fun me(session: AuthioSession): User =
        transport.get("/v1/me", User.serializer(), bearer = session.accessToken)

    suspend fun listMyOrganizations(session: AuthioSession): List<MembershipWithOrg> =
        transport.get(
            "/v1/me/organizations",
            ListSerializer(MembershipWithOrg.serializer()),
            bearer = session.accessToken,
        )

    /**
     * Switch the active organization on a session. Returns a *new*
     * envelope — store it and discard the previous one.
     */
    suspend fun switchOrganization(session: AuthioSession, to: String): AuthioSession {
        val body = buildJsonObject {
            put("session_id", session.sessionId)
            put("organization_id", to)
        }
        return transport.postRaw(
            path = "/v1/sessions/switch-org",
            rawJsonBody = body.toString(),
            respSerializer = AuthioSession.serializer(),
            bearer = session.accessToken,
        )
    }

    /**
     * Pick an org during the post-auth selection step (when a user has
     * no `default_organization_id` and authenticated discoverably).
     */
    suspend fun selectOrganization(session: AuthioSession, organizationId: String): AuthioSession {
        val body = buildJsonObject {
            put("session_id", session.sessionId)
            put("organization_id", organizationId)
        }
        return transport.postRaw(
            path = "/v1/sessions/select-org",
            rawJsonBody = body.toString(),
            respSerializer = AuthioSession.serializer(),
            bearer = session.accessToken,
        )
    }

    suspend fun revokeSession(session: AuthioSession) {
        val body = buildJsonObject { put("session_id", session.sessionId) }
        transport.postNoResponse(
            path = "/v1/sessions/revoke",
            rawJsonBody = body.toString(),
            bearer = session.accessToken,
        )
    }

    // -------------------------------------------------------------------
    //  Passkey wire helpers — used by the Android module
    // -------------------------------------------------------------------

    /**
     * Fetch the WebAuthn registration options for [email] and return them
     * verbatim as a JSON string. The Android module passes this string
     * directly to `CreatePublicKeyCredentialRequest(requestJson)`.
     *
     * Returns the [PasskeyChallenge] holding the raw JSON + the
     * `request_id` that the matching `/verify` call must echo back.
     */
    suspend fun fetchPasskeyRegistrationOptions(email: String): PasskeyChallenge {
        val body = buildJsonObject { put("email", email) }
        return transport.postRaw(
            path = "/v1/auth/passkey/register/options",
            rawJsonBody = body.toString(),
            respSerializer = PasskeyOptionsEnvelope.serializer(),
        ).asChallenge()
    }

    /**
     * Same shape as [fetchPasskeyRegistrationOptions], but for login.
     * Pass `email = null` to trigger a *discoverable* (a.k.a. usernameless)
     * passkey ceremony — the device shows the user the list of credentials
     * registered for the RP.
     */
    suspend fun fetchPasskeyLoginOptions(email: String?): PasskeyChallenge {
        val body = buildJsonObject {
            if (email != null) put("email", email)
        }
        return transport.postRaw(
            path = "/v1/auth/passkey/login/options",
            rawJsonBody = body.toString(),
            respSerializer = PasskeyOptionsEnvelope.serializer(),
        ).asChallenge()
    }

    /**
     * Hand the raw `PublicKeyCredential` JSON returned by Credential
     * Manager back to auth-core for verification. On success the server
     * mints a session envelope.
     */
    suspend fun verifyPasskeyRegistration(
        credentialJson: String,
        challenge: PasskeyChallenge,
        email: String,
    ): AuthioSession {
        // Compose the body as a literal string so we can splice the raw
        // `credentialJson` (already a valid JSON object emitted by
        // CredentialManager) without round-tripping through JsonObject
        // and risking re-ordering / re-encoding of the WebAuthn fields.
        val rebuilt =
            """{"email":${quote(email)},"request_id":${quote(challenge.requestId)},"credential":$credentialJson}"""
        return transport.postRaw(
            path = "/v1/auth/passkey/register/verify",
            rawJsonBody = rebuilt,
            respSerializer = AuthioSession.serializer(),
        )
    }

    suspend fun verifyPasskeyLogin(
        credentialJson: String,
        challenge: PasskeyChallenge,
    ): AuthioSession {
        val rebuilt = """{"request_id":${quote(challenge.requestId)},"credential":$credentialJson}"""
        return transport.postRaw(
            path = "/v1/auth/passkey/login/verify",
            rawJsonBody = rebuilt,
            respSerializer = AuthioSession.serializer(),
        )
    }

    private fun quote(s: String): String =
        AuthioJson.encodeToString(String.serializer(), s)

    companion object {
        const val DEFAULT_API_URL: String = "https://api.authio.com"

        private fun require(publishableKey: String): String {
            if (publishableKey.isBlank()) {
                throw AuthioError.Config("publishableKey must not be blank")
            }
            // Permit pk_test_, pk_live_, or anything callers want — the
            // server is the source of truth on what's accepted.
            return publishableKey
        }

        internal fun sanitizeUrl(url: String): String {
            if (url.isBlank()) throw AuthioError.Config("apiUrl must not be blank")
            val trimmed = url.trim().trimEnd('/')
            // Validate parseability eagerly so we fail at construction
            // time, not deep inside the first request.
            runCatching { trimmed.toHttpUrl() }.getOrElse {
                throw AuthioError.Config("apiUrl is not a valid URL: $url")
            }
            return trimmed
        }

        private fun defaultOk(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        internal fun extractQuery(url: String, name: String): String? =
            parseQueryString(url)?.get(name)

        /**
         * Pull the query parameters out of any URL — `https://`, `myapp://`,
         * `intent://`, whatever. Returns `null` if the input isn't a URI
         * at all. Decoded values are URL-decoded.
         */
        internal fun parseQueryString(url: String): Map<String, String>? {
            val raw = runCatching { java.net.URI(url) }.getOrNull()?.rawQuery
                ?: return null
            if (raw.isEmpty()) return emptyMap()
            return buildMap {
                for (pair in raw.split('&')) {
                    if (pair.isEmpty()) continue
                    val idx = pair.indexOf('=')
                    val name = if (idx < 0) pair else pair.substring(0, idx)
                    val value = if (idx < 0) "" else pair.substring(idx + 1)
                    put(
                        java.net.URLDecoder.decode(name, Charsets.UTF_8),
                        java.net.URLDecoder.decode(value, Charsets.UTF_8),
                    )
                }
            }
        }
    }
}

/** Result of [Client.parseOAuthCallback]. */
data class OAuthCallback(
    val sessionId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: String,
    val state: String?,
) {
    fun toSession(): AuthioSession = AuthioSession(
        sessionId = sessionId,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
    )
}

/**
 * Holder for a server-issued WebAuthn challenge. [optionsJson] is what
 * Credential Manager wants verbatim; [requestId] correlates the
 * /options call with the matching /verify call so auth-core can rebind
 * to the same in-flight request.
 */
data class PasskeyChallenge(
    val optionsJson: String,
    val requestId: String,
)

@Serializable
internal data class PasskeyOptionsEnvelope(
    @SerialName("request_id") val requestId: String,
    val options: JsonObject,
) {
    fun asChallenge(): PasskeyChallenge =
        PasskeyChallenge(optionsJson = options.toString(), requestId = requestId)
}
