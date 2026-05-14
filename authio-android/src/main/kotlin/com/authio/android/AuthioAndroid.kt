package com.authio.android

import android.app.Activity
import android.content.Context
import com.authio.AuthioError
import com.authio.AuthioSession
import com.authio.Client
import com.authio.MembershipWithOrg
import com.authio.OAuthProvider

/**
 * Android-flavored entry point. Wraps a pure-JVM [Client] and adds
 * platform-specific UX:
 *
 *  * Passkey ceremonies via AndroidX Credential Manager (the modern
 *    successor to FIDO2) — the host hands us an [Activity] and we
 *    drive the system UI.
 *  * OAuth via Chrome Custom Tabs — also Activity-bound.
 *  * Encrypted session storage via [AuthioStorage].
 *
 * Magic link `send` and `consume` flow through [Client] directly because
 * they don't need an Activity (the system calls back into your manifest
 * intent-filter, you parse the URL, you call the suspend function).
 *
 * Multi-org-first: [signInWithPasskey] / [signInWithOAuth] return a
 * session whose `activeOrganization` may be `null` if the user has more
 * than one membership and no `default_organization_id`. Apps should
 * inspect [AuthioSession.memberships] and call [switchOrganization] to
 * pick one before issuing org-scoped requests.
 */
class AuthioAndroid private constructor(
    val core: Client,
    private val passkeys: PasskeyCeremonies = PasskeyCeremonies(core),
    private val oauth: OAuthCustomTabs = OAuthCustomTabs(core),
) {

    /**
     * Sign in with an existing passkey. Pass an [Activity] because
     * Credential Manager's system UI needs it.
     *
     * If [email] is `null` we run a *discoverable* (a.k.a. usernameless)
     * ceremony — the device shows the user every passkey registered
     * with this RP. Pass an email to scope the prompt.
     *
     * @throws AuthioError.NoCredentialAvailable if the user has no
     *   passkeys registered for this RP.
     * @throws AuthioError.UserCancelled if the user dismisses the prompt.
     * @throws AuthioError.PasskeyCeremonyFailed for any other ceremony
     *   failure (device-bound key error, malformed challenge, etc.).
     */
    suspend fun signInWithPasskey(activity: Activity, email: String? = null): AuthioSession =
        passkeys.login(activity = activity, email = email)

    /**
     * Register a new passkey for [email] and return the resulting
     * session. The user must first prove email ownership — typically
     * via a magic link sent to [email]; auth-core's
     * `/v1/auth/passkey/register/options` endpoint enforces this.
     */
    suspend fun signUpWithPasskey(email: String, activity: Activity): AuthioSession =
        passkeys.register(activity = activity, email = email)

    /**
     * Send a magic link to [destination] (email or phone). Identical to
     * [Client.sendMagicLink]; exposed here for API symmetry.
     */
    suspend fun sendMagicLink(destination: String, redirectUri: String, organizationId: String? = null) {
        core.sendMagicLink(destination = destination, redirectUri = redirectUri, organizationId = organizationId)
    }

    /**
     * Consume a magic-link callback URL (the deep link your activity
     * received from `onNewIntent` / a manifest intent-filter).
     */
    suspend fun consumeMagicLinkCallback(url: String): AuthioSession =
        core.consumeMagicLinkCallback(url = url)

    /**
     * OAuth via Custom Tabs. Suspends until the user completes (or
     * cancels) the redirect flow.
     *
     * @throws AuthioError.UserCancelled if the user dismisses the
     *   browser tab without completing the flow.
     */
    suspend fun signInWithOAuth(
        provider: OAuthProvider,
        redirectUri: String,
        activity: Activity,
        organizationId: String? = null,
    ): AuthioSession = oauth.signIn(
        provider = provider,
        redirectUri = redirectUri,
        activity = activity,
        organizationId = organizationId,
    )

    /**
     * Hand a deep-link Intent's data URL into the SDK to complete an
     * OAuth flow. Call this from your activity's `onNewIntent` (or from
     * the dedicated callback activity declared in your manifest).
     * Returns `false` if the URL doesn't look like one of *our* OAuth
     * callbacks (so you can chain other handlers).
     */
    fun handleOAuthCallback(url: String): Boolean = oauth.handle(url)

    /** Best-effort verification (`GET /v1/me`). */
    suspend fun verify(session: AuthioSession): Boolean = core.verify(session)

    suspend fun switchOrganization(session: AuthioSession, to: String): AuthioSession =
        core.switchOrganization(session = session, to = to)

    suspend fun listMyOrganizations(session: AuthioSession): List<MembershipWithOrg> =
        core.listMyOrganizations(session = session)

    suspend fun revokeSession(session: AuthioSession) = core.revokeSession(session)

    /** Construct an [AuthioStorage] backed by EncryptedSharedPreferences. */
    fun storage(context: Context): AuthioStorage = AuthioStorage(context)

    companion object {
        fun create(
            publishableKey: String,
            apiUrl: String = Client.DEFAULT_API_URL,
        ): AuthioAndroid = AuthioAndroid(
            core = Client(publishableKey = publishableKey, apiUrl = apiUrl),
        )
    }
}
