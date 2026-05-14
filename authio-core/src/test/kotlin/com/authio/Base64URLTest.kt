package com.authio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Base64URLTest {

    @Test
    fun encodes_without_padding() {
        // "any carnal pleasure." -> standard base64 ends in '='; URL-safe
        // variant strips it. Sanity-check both the alphabet swap and the
        // pad-stripping in one go.
        assertEquals(
            "YW55IGNhcm5hbCBwbGVhc3VyZS4",
            Base64URL.encode("any carnal pleasure.".toByteArray()),
        )
    }

    @Test
    fun decodes_input_without_padding() {
        val bytes = Base64URL.decode("YW55IGNhcm5hbCBwbGVhc3VyZS4")
        assertEquals("any carnal pleasure.", String(bytes))
    }

    @Test
    fun decodes_input_with_padding() {
        // WebAuthn payloads typically arrive without pad, but a few SDKs
        // (and our own tests) round-trip with pad — both should work.
        val bytes = Base64URL.decode("YW55IGNhcm5hbCBwbGVhc3VyZS4=")
        assertEquals("any carnal pleasure.", String(bytes))
    }

    @Test
    fun round_trip_preserves_arbitrary_bytes() {
        val raw = (0..255).map { it.toByte() }.toByteArray()
        val encoded = Base64URL.encode(raw)
        // URL-safe alphabet: must NOT contain '+' or '/'.
        assertEquals(false, encoded.contains('+'))
        assertEquals(false, encoded.contains('/'))
        assertContentEquals(raw, Base64URL.decode(encoded))
    }
}
