package com.authio

import java.util.Base64

/**
 * Base64-URL (RFC 4648 §5) encoding without padding. We bake our own
 * helpers rather than relying on Java 8 `Base64.getUrlEncoder()` directly
 * so callers don't have to remember `withoutPadding()` and so we can
 * tolerate WebAuthn payloads that arrive with or without padding.
 */
object Base64URL {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /**
     * Decode a base64url string. Tolerates input with or without `=`
     * padding so the same helper handles WebAuthn payloads from either
     * the browser (no pad) and Android's Credential Manager (no pad).
     */
    fun decode(input: String): ByteArray {
        val padded = padIfNeeded(input)
        return decoder.decode(padded)
    }

    private fun padIfNeeded(input: String): String {
        val rem = input.length % 4
        return if (rem == 0) input else input + "=".repeat(4 - rem)
    }
}
