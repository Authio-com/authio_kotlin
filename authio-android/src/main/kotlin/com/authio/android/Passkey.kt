package com.authio.android

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.authio.AuthioError
import com.authio.AuthioSession
import com.authio.Client

/**
 * The marquee feature: real WebAuthn passkey ceremonies via the modern
 * AndroidX Credential Manager.
 *
 * The big advantage over the legacy FIDO2 API: Credential Manager
 * exchanges the W3C-spec JSON shape directly. We hand the server-issued
 * options JSON to `GetPublicKeyCredentialOption(requestJson)` verbatim,
 * and the resulting `PublicKeyCredential.authenticationResponseJson`
 * is exactly the body our server expects. No manual base64url-encoding
 * of `clientDataJSON` / `authenticatorData` / `signature` / `userHandle`
 * — the framework does it all for us.
 */
internal class PasskeyCeremonies(private val core: Client) {

    suspend fun login(activity: Activity, email: String?): AuthioSession {
        val challenge = core.fetchPasskeyLoginOptions(email)
        val cm = CredentialManager.create(activity)

        val request = GetCredentialRequest(
            credentialOptions = listOf(
                GetPublicKeyCredentialOption(requestJson = challenge.optionsJson),
            ),
        )

        val response: GetCredentialResponse = try {
            cm.getCredential(context = activity, request = request)
        } catch (e: NoCredentialException) {
            throw AuthioError.NoCredentialAvailable(
                "No passkey is registered for this app on this device. " +
                    "Sign up with a passkey first, or use a magic link.",
            )
        } catch (e: GetCredentialCancellationException) {
            throw AuthioError.UserCancelled()
        } catch (e: GetCredentialException) {
            throw AuthioError.PasskeyCeremonyFailed(
                message = "Credential Manager failed: ${e.type} — ${e.message}",
                cause = e,
            )
        }

        val credential = response.credential
        if (credential !is PublicKeyCredential) {
            throw AuthioError.PasskeyCeremonyFailed(
                "Credential Manager returned a non-passkey credential type: ${credential.type}",
            )
        }
        return core.verifyPasskeyLogin(
            credentialJson = credential.authenticationResponseJson,
            challenge = challenge,
        )
    }

    suspend fun register(activity: Activity, email: String): AuthioSession {
        val challenge = core.fetchPasskeyRegistrationOptions(email)
        val cm = CredentialManager.create(activity)

        val request = CreatePublicKeyCredentialRequest(
            requestJson = challenge.optionsJson,
            preferImmediatelyAvailableCredentials = false,
        )

        val response = try {
            cm.createCredential(context = activity, request = request)
        } catch (e: CreateCredentialCancellationException) {
            throw AuthioError.UserCancelled()
        } catch (e: CreateCredentialException) {
            throw AuthioError.PasskeyCeremonyFailed(
                message = "Credential Manager failed to create passkey: ${e.type} — ${e.message}",
                cause = e,
            )
        }

        if (response !is CreatePublicKeyCredentialResponse) {
            throw AuthioError.PasskeyCeremonyFailed(
                "Credential Manager returned an unexpected response: ${response.type}",
            )
        }

        return core.verifyPasskeyRegistration(
            credentialJson = response.registrationResponseJson,
            challenge = challenge,
            email = email,
        )
    }
}
