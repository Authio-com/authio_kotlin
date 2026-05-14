package com.authio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for errors returned by every Authio service. The body
 * mirrors the OpenAPI `Error` schema:
 *
 * ```json
 * { "code": "passkey.no_credential", "message": "...", "request_id": "req_..." }
 * ```
 */
@Serializable
internal data class WireError(
    val code: String,
    val message: String,
    @SerialName("request_id") val requestId: String? = null,
)

/**
 * Hierarchical error type the SDK throws. Callers pattern-match on the
 * subclass to drive UI; the [code]/[status]/[requestId] surface is
 * preserved for logging and retries.
 *
 * `RuntimeException` (not checked) keeps the public API ergonomic from
 * coroutines.
 */
sealed class AuthioError(
    message: String,
    val code: String,
    val status: Int = 0,
    val requestId: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** Server returned an error envelope. */
    class Api(
        message: String,
        code: String,
        status: Int,
        requestId: String? = null,
    ) : AuthioError(message, code, status, requestId)

    /** Network / I/O failure before we could parse a response. */
    class Network(message: String, cause: Throwable? = null) :
        AuthioError(message, "network_error", cause = cause)

    /** Response body could not be decoded as the expected type. */
    class Decode(message: String, cause: Throwable? = null) :
        AuthioError(message, "decode_error", cause = cause)

    /** Caller-supplied configuration is invalid (publishable key, URL, …). */
    class Config(message: String) :
        AuthioError(message, "config_error")

    /** No passkeys are registered for this RP on this device. */
    class NoCredentialAvailable(message: String = "No passkey available for this account.") :
        AuthioError(message, "passkey.no_credential")

    /** User dismissed the OS passkey / OAuth prompt. */
    class UserCancelled(message: String = "User cancelled authentication.") :
        AuthioError(message, "user_cancelled")

    /** Credential Manager / FIDO2 ceremony failed for any other reason. */
    class PasskeyCeremonyFailed(message: String, cause: Throwable? = null) :
        AuthioError(message, "passkey.ceremony_failed", cause = cause)

    /** Magic-link or OAuth callback URL was malformed. */
    class InvalidCallbackUrl(message: String) :
        AuthioError(message, "invalid_callback_url")
}
