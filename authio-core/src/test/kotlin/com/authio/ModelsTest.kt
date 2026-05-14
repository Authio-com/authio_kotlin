package com.authio

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun authio_session_round_trips_with_snake_case_wire_format() {
        val raw = """
            {
              "session_id": "sess_123",
              "access_token": "eyJhbGc.payload.sig",
              "refresh_token": "rt_abc",
              "expires_at": "2026-05-13T20:00:00Z",
              "user": {
                "id": "user_1",
                "project_id": "proj_x",
                "email": "alice@example.com",
                "email_verified": true
              },
              "active_organization": {
                "id": "org_acme",
                "project_id": "proj_x",
                "name": "Acme",
                "slug": "acme"
              },
              "active_role": "admin"
            }
        """.trimIndent()

        val s = json.decodeFromString(AuthioSession.serializer(), raw)
        assertEquals("sess_123", s.sessionId)
        assertEquals("eyJhbGc.payload.sig", s.accessToken)
        assertEquals("rt_abc", s.refreshToken)
        assertEquals("2026-05-13T20:00:00Z", s.expiresAt)
        assertEquals("user_1", s.userId)
        assertEquals("org_acme", s.orgId)
        assertEquals("admin", s.role)
        assertEquals("alice@example.com", s.user?.email)
        assertEquals(true, s.user?.emailVerified)
    }

    @Test
    fun authio_session_serializes_back_to_snake_case() {
        val s = AuthioSession(
            sessionId = "sess_1",
            accessToken = "tok",
            refreshToken = null,
            expiresAt = "2026-01-01T00:00:00Z",
        )
        val out = json.encodeToString(AuthioSession.serializer(), s)
        // Must use snake_case keys so the server accepts it.
        assertEquals(true, out.contains("\"session_id\":\"sess_1\""))
        assertEquals(true, out.contains("\"access_token\":\"tok\""))
        assertEquals(true, out.contains("\"expires_at\":\"2026-01-01T00:00:00Z\""))
        // Null refresh_token should be omitted (encodeDefaults = false).
        assertEquals(false, out.contains("refresh_token"))
    }

    @Test
    fun membership_status_decodes_lowercase() {
        val raw = """
            {
              "id": "mem_1",
              "user_id": "user_1",
              "organization_id": "org_1",
              "role": "admin",
              "status": "active"
            }
        """.trimIndent()
        val m = json.decodeFromString(Membership.serializer(), raw)
        assertEquals(MembershipStatus.ACTIVE, m.status)
    }

    @Test
    fun membership_with_org_round_trip() {
        val raw = """
            {
              "id": "mem_1",
              "user_id": "user_1",
              "organization_id": "org_acme",
              "role": "owner",
              "status": "active",
              "organization": {
                "id": "org_acme",
                "name": "Acme",
                "slug": "acme"
              }
            }
        """.trimIndent()
        val m = json.decodeFromString(MembershipWithOrg.serializer(), raw)
        assertEquals("Acme", m.organization.name)
        assertEquals("owner", m.role)
        assertNotNull(m.organization)
    }

    @Test
    fun oauth_provider_parses_case_insensitively() {
        assertEquals(OAuthProvider.GOOGLE, OAuthProvider.parse("google"))
        assertEquals(OAuthProvider.MICROSOFT, OAuthProvider.parse("Microsoft"))
        assertEquals(OAuthProvider.GITHUB, OAuthProvider.parse("GITHUB"))
        assertEquals(null, OAuthProvider.parse("yahoo"))
        assertEquals(null, OAuthProvider.parse(null))
    }
}
