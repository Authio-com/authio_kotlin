package com.authio.android

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.authio.AuthioError
import com.authio.AuthioSession
import com.authio.Client
import com.authio.OAuthProvider
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuth via Chrome Custom Tabs.
 *
 * Why Custom Tabs (and not WebView): Custom Tabs share the user's
 * existing browser session, so single-sign-on with Chrome / Edge "just
 * works"; WebView is a separate cookie jar that strands the user.
 * They're also far less spoof-vulnerable.
 *
 * The flow:
 *  1. Build the authorize URL with a unique `state`.
 *  2. Park a [CompletableDeferred] in [InFlightOAuthRegistry] keyed by
 *     that state.
 *  3. Launch the Custom Tab.
 *  4. The user completes the IdP redirect; auth-core sends them to
 *     `redirectUri` with `?session_id=…&access_token=…&state=…`.
 *  5. Your activity / callback activity catches the deep link, calls
 *     [AuthioAndroid.handleOAuthCallback], which resolves the deferred
 *     and the suspending [signIn] returns.
 */
internal class OAuthCustomTabs(private val core: Client) {

    suspend fun signIn(
        provider: OAuthProvider,
        redirectUri: String,
        activity: Activity,
        organizationId: String?,
    ): AuthioSession {
        val state = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<AuthioSession>()
        InFlightOAuthRegistry.register(state, deferred)

        val url = core.buildOAuthAuthorizeUrl(
            provider = provider,
            redirectUri = redirectUri,
            state = state,
            organizationId = organizationId,
        )

        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(activity, Uri.parse(url))
        } catch (t: Throwable) {
            InFlightOAuthRegistry.cancel(state)
            throw AuthioError.PasskeyCeremonyFailed(
                "Failed to launch OAuth Custom Tab: ${t.message}",
                cause = t,
            )
        }

        return deferred.await()
    }

    /**
     * Surface the deep-link result to the parked deferred. Returns
     * `false` if the URL doesn't match an in-flight state (so the host
     * activity knows it can chain other handlers).
     */
    fun handle(url: String): Boolean {
        val cb = try {
            core.parseOAuthCallback(url)
        } catch (e: AuthioError) {
            // If the URL has a `state` we recognise, propagate the
            // error to that deferred so the caller's `await()` throws.
            // `Client.parseQueryString` is `internal` to :authio-core, so
            // we keep a small in-module parser here rather than widening
            // the SDK's public API just for one cross-module callsite.
            val state = parseStateParam(url)
            if (state != null && InFlightOAuthRegistry.fail(state, e)) {
                return true
            }
            return false
        }
        val state = cb.state ?: return false
        return InFlightOAuthRegistry.complete(state, cb.toSession())
    }

    private companion object {
        /**
         * Extract `state` from any OAuth callback URL. Mirrors the
         * in-core [com.authio.Client.parseQueryString] helper but lives
         * here so :authio-core can keep its parser `internal`.
         */
        fun parseStateParam(url: String): String? {
            val raw = runCatching { java.net.URI(url) }.getOrNull()?.rawQuery
                ?: return null
            if (raw.isEmpty()) return null
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val idx = pair.indexOf('=')
                val name = if (idx < 0) pair else pair.substring(0, idx)
                if (java.net.URLDecoder.decode(name, Charsets.UTF_8) != "state") continue
                val value = if (idx < 0) "" else pair.substring(idx + 1)
                return java.net.URLDecoder.decode(value, Charsets.UTF_8)
            }
            return null
        }
    }
}

/**
 * Tiny static registry of in-flight OAuth flows. Keyed by `state` so
 * the redirect handler can find the right [CompletableDeferred] no
 * matter which activity hosts the callback intent-filter.
 *
 * Static state is justified here: the OAuth dance crosses an
 * intent-filter boundary, so the activity instance that *launched* the
 * tab and the one that *receives* the deep link can be different
 * (different task, even).
 */
internal object InFlightOAuthRegistry {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<AuthioSession>>()

    fun register(state: String, deferred: CompletableDeferred<AuthioSession>) {
        pending[state] = deferred
    }

    fun complete(state: String, session: AuthioSession): Boolean {
        val deferred = pending.remove(state) ?: return false
        deferred.complete(session)
        return true
    }

    fun fail(state: String, cause: Throwable): Boolean {
        val deferred = pending.remove(state) ?: return false
        deferred.completeExceptionally(cause)
        return true
    }

    fun cancel(state: String) {
        pending.remove(state)?.completeExceptionally(AuthioError.UserCancelled())
    }
}
