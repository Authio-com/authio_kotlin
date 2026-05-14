package com.authio

import kotlinx.serialization.json.Json

/**
 * Single configured Json instance reused everywhere. We intentionally:
 *
 *  * `ignoreUnknownKeys = true` — server adds new fields without bumping
 *    the SDK; older clients should not crash.
 *  * `encodeDefaults = false` — avoids sending `null`/empty defaults the
 *    server will reject as "unexpected field".
 *  * `coerceInputDefaults = true` — tolerate `null` for fields that have
 *    a default (e.g. server returning `"memberships": null`).
 */
internal val AuthioJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    coerceInputValues = true
    explicitNulls = false
    classDiscriminator = "_kind"
}

/**
 * Cross-module accessor for the configured [Json] instance. Kept tiny
 * and documented as part of the SDK contract so the Android module can
 * reuse the exact same configuration when (de)serialising sessions to
 * EncryptedSharedPreferences. Not intended for app code.
 */
object AuthioJsonAccess {
    val json: Json get() = AuthioJson
}
