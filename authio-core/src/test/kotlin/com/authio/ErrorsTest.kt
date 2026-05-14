package com.authio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ErrorsTest {

    @Test
    fun api_error_carries_full_metadata() {
        val e = AuthioError.Api(
            message = "Bad pk",
            code = "auth.invalid_publishable_key",
            status = 401,
            requestId = "req_abc",
        )
        assertEquals("Bad pk", e.message)
        assertEquals("auth.invalid_publishable_key", e.code)
        assertEquals(401, e.status)
        assertEquals("req_abc", e.requestId)
    }

    @Test
    fun subclass_checks_drive_pattern_matching() {
        val errs: List<AuthioError> = listOf(
            AuthioError.UserCancelled(),
            AuthioError.NoCredentialAvailable(),
            AuthioError.InvalidCallbackUrl("missing token"),
            AuthioError.Network("dns failure"),
        )
        // Spot-check that each survives an `is` test in user code,
        // which is the documented integration pattern.
        assertIs<AuthioError.UserCancelled>(errs[0])
        assertIs<AuthioError.NoCredentialAvailable>(errs[1])
        assertIs<AuthioError.InvalidCallbackUrl>(errs[2])
        assertIs<AuthioError.Network>(errs[3])

        // And that all of them share the sealed parent — so a single
        // `catch (e: AuthioError)` block in app code catches everything.
        for (e in errs) assertIs<AuthioError>(e)
    }

    @Test
    fun network_error_preserves_cause_chain() {
        val ioe = java.io.IOException("connection reset")
        val e = AuthioError.Network("can't reach api", ioe)
        assertEquals(ioe, e.cause)
        assertEquals("network_error", e.code)
    }
}
