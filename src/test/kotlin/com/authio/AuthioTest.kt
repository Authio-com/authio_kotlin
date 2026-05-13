package com.authio

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class AuthioTest {
    @Test fun `apiKey is required`() {
        assertFailsWith<IllegalArgumentException> { Authio("") }
    }

    @Test fun `default base url is api authio com`() {
        val a = Authio("sk_test_x")
        assertEquals("https://api.authio.com", a.baseUrl)
    }
}
