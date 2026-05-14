package com.authio

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the wire surface: spin up a real local HTTP
 * server (MockWebServer), point the Client at it, drive each method,
 * and assert both what we *sent* and how we decoded what came back.
 */
class ClientHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var client: Client

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = Client(
            publishableKey = "pk_test_local",
            apiUrl = server.url("").toString().trimEnd('/'),
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun every_request_carries_publishable_key_headers() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"u_1","email":"a@b.c"}""").setResponseCode(200))

        val session = AuthioSession(
            sessionId = "sess",
            accessToken = "tok_session",
            expiresAt = "2026-01-01T00:00:00Z",
        )
        client.me(session)

        val recorded = server.takeRequest()
        assertEquals("/v1/me", recorded.path)
        assertEquals("Bearer tok_session", recorded.getHeader("Authorization"))
        assertEquals("pk_test_local", recorded.getHeader("X-Authio-Publishable-Key"))
        assertTrue(recorded.getHeader("User-Agent").orEmpty().startsWith("authio-kotlin/"))
    }

    @Test
    fun magic_link_send_posts_expected_body() = runTest {
        server.enqueue(MockResponse().setResponseCode(202))

        client.sendMagicLink(
            destination = "alice@example.com",
            redirectUri = "myapp://auth-callback",
            organizationId = "org_acme",
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/auth/magic-link/send", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"destination\":\"alice@example.com\""), "body=$body")
        assertTrue(body.contains("\"redirect_uri\":\"myapp://auth-callback\""), "body=$body")
        assertTrue(body.contains("\"organization_id\":\"org_acme\""), "body=$body")
    }

    @Test
    fun magic_link_callback_returns_session_envelope() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "session_id":"sess_ml",
                  "access_token":"at_ml",
                  "refresh_token":"rt_ml",
                  "expires_at":"2026-05-13T20:00:00Z"
                }
                """.trimIndent(),
            ),
        )

        val s = client.consumeMagicLinkCallback("myapp://auth-callback?token=mt_xyz")

        val recorded = server.takeRequest()
        assertEquals("/v1/auth/magic-link/callback?token=mt_xyz", recorded.path)
        assertEquals("sess_ml", s.sessionId)
        assertEquals("at_ml", s.accessToken)
        assertEquals("rt_ml", s.refreshToken)
    }

    @Test
    fun magic_link_callback_rejects_url_without_token() = runTest {
        assertFailsWith<AuthioError.InvalidCallbackUrl> {
            client.consumeMagicLinkCallback("myapp://auth-callback")
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun api_error_envelope_is_decoded_into_typed_exception() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody(
                    """
                    {
                      "code":"auth.invalid_token",
                      "message":"token expired",
                      "request_id":"req_42"
                    }
                    """.trimIndent(),
                ),
        )

        val s = AuthioSession(
            sessionId = "sess",
            accessToken = "expired",
            expiresAt = "2026-01-01T00:00:00Z",
        )
        val e = assertFailsWith<AuthioError.Api> { client.me(s) }
        assertEquals("auth.invalid_token", e.code)
        assertEquals("token expired", e.message)
        assertEquals("req_42", e.requestId)
        assertEquals(401, e.status)
    }

    @Test
    fun verify_returns_false_on_401_instead_of_throwing() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"code":"auth.invalid_token","message":"expired"}"""),
        )

        val s = AuthioSession(
            sessionId = "sess",
            accessToken = "expired",
            expiresAt = "2026-01-01T00:00:00Z",
        )
        assertEquals(false, client.verify(s))
    }

    @Test
    fun list_my_organizations_decodes_membership_with_org_array() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "id":"mem_1",
                    "user_id":"user_1",
                    "organization_id":"org_acme",
                    "role":"owner",
                    "status":"active",
                    "organization":{"id":"org_acme","name":"Acme","slug":"acme"}
                  },
                  {
                    "id":"mem_2",
                    "user_id":"user_1",
                    "organization_id":"org_globex",
                    "role":"member",
                    "status":"active",
                    "organization":{"id":"org_globex","name":"Globex","slug":"globex"}
                  }
                ]
                """.trimIndent(),
            ),
        )
        val s = AuthioSession(
            sessionId = "sess",
            accessToken = "tok",
            expiresAt = "2026-01-01T00:00:00Z",
        )
        val orgs = client.listMyOrganizations(s)
        assertEquals(2, orgs.size)
        assertEquals("Acme", orgs[0].organization.name)
        assertEquals("member", orgs[1].role)
    }

    @Test
    fun switch_organization_posts_session_id_and_returns_envelope() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "session_id":"sess_2",
                  "access_token":"at_new",
                  "expires_at":"2026-05-13T20:00:00Z",
                  "active_organization":{"id":"org_globex","name":"Globex","slug":"globex"}
                }
                """.trimIndent(),
            ),
        )

        val before = AuthioSession(
            sessionId = "sess_1",
            accessToken = "tok_1",
            expiresAt = "2026-05-13T20:00:00Z",
        )
        val after = client.switchOrganization(before, to = "org_globex")

        val recorded = server.takeRequest()
        assertEquals("/v1/sessions/switch-org", recorded.path)
        assertEquals("Bearer tok_1", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"organization_id\":\"org_globex\""))
        assertEquals("at_new", after.accessToken)
        assertEquals("org_globex", after.orgId)
    }

    @Test
    fun passkey_login_options_extracts_request_id_and_keeps_options_verbatim() = runTest {
        // The server returns its WebAuthn options inline; we keep the
        // `options` JsonObject byte-for-byte so Credential Manager gets
        // exactly what auth-core emitted.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "request_id":"req_passkey_1",
                  "options":{
                    "challenge":"AAEC",
                    "rp":{"id":"authio.local","name":"Authio"},
                    "userVerification":"preferred",
                    "timeout":60000
                  }
                }
                """.trimIndent(),
            ),
        )

        val challenge = client.fetchPasskeyLoginOptions(email = null)
        assertEquals("req_passkey_1", challenge.requestId)
        assertTrue(challenge.optionsJson.contains("\"challenge\":\"AAEC\""))
        assertTrue(challenge.optionsJson.contains("\"rp\":{\"id\":\"authio.local\""))
    }
}
