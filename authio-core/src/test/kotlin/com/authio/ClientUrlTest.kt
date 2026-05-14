package com.authio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientUrlTest {

    @Test
    fun trims_trailing_slashes_on_api_url() {
        val c = Client(publishableKey = "pk_test_x", apiUrl = "https://api.authio.com/")
        assertEquals("https://api.authio.com", c.apiUrl)
    }

    @Test
    fun rejects_blank_publishable_key() {
        assertFailsWith<AuthioError.Config> {
            Client(publishableKey = "", apiUrl = "https://api.authio.com")
        }
    }

    @Test
    fun rejects_invalid_api_url() {
        assertFailsWith<AuthioError.Config> {
            Client(publishableKey = "pk_test_x", apiUrl = "not-a-url")
        }
    }

    @Test
    fun custom_api_url_is_preserved() {
        val c = Client(
            publishableKey = "pk_test_x",
            apiUrl = "https://staging.example.com/v2",
        )
        assertEquals("https://staging.example.com/v2", c.apiUrl)
    }

    @Test
    fun build_oauth_authorize_url_includes_required_params() {
        val c = Client(publishableKey = "pk_live_abc", apiUrl = "https://api.authio.com")
        val url = c.buildOAuthAuthorizeUrl(
            provider = OAuthProvider.GOOGLE,
            redirectUri = "myapp://auth-callback",
            state = "s_123",
        )
        assertEquals(true, url.startsWith("https://api.authio.com/v1/auth/oauth/google/authorize?"))
        assertEquals(true, url.contains("redirect_uri=myapp%3A%2F%2Fauth-callback"))
        assertEquals(true, url.contains("state=s_123"))
        assertEquals(true, url.contains("publishable_key=pk_live_abc"))
    }

    @Test
    fun build_oauth_authorize_url_appends_org_when_provided() {
        val c = Client(publishableKey = "pk_test", apiUrl = "https://api.authio.com")
        val url = c.buildOAuthAuthorizeUrl(
            provider = OAuthProvider.MICROSOFT,
            redirectUri = "https://app.example.com/cb",
            state = "s",
            organizationId = "org_42",
        )
        assertEquals(true, url.contains("organization_id=org_42"))
    }

    @Test
    fun parse_oauth_callback_extracts_session_fields() {
        val c = Client(publishableKey = "pk_test", apiUrl = "https://api.authio.com")
        val cb = c.parseOAuthCallback(
            "myapp://auth-callback?session_id=sess_1&access_token=tok_2&state=s_42",
        )
        assertEquals("sess_1", cb.sessionId)
        assertEquals("tok_2", cb.accessToken)
        assertEquals("s_42", cb.state)
    }

    @Test
    fun parse_oauth_callback_throws_on_error_response() {
        val c = Client(publishableKey = "pk_test", apiUrl = "https://api.authio.com")
        val ex = assertFailsWith<AuthioError.Api> {
            c.parseOAuthCallback(
                "myapp://auth-callback?error=access_denied&error_description=user+said+no",
            )
        }
        assertEquals("oauth.access_denied", ex.code)
        assertEquals("user said no", ex.message)
    }

    @Test
    fun parse_oauth_callback_rejects_missing_fields() {
        val c = Client(publishableKey = "pk_test", apiUrl = "https://api.authio.com")
        assertFailsWith<AuthioError.InvalidCallbackUrl> {
            c.parseOAuthCallback("myapp://auth-callback?state=s")
        }
    }
}
