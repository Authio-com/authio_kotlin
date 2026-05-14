package com.authio.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.authio.AuthioError
import com.authio.AuthioSession
import com.authio.MembershipWithOrg
import com.authio.android.AuthioAndroid
import com.authio.android.AuthioStorage
import kotlinx.coroutines.launch

private val authio = AuthioAndroid.create(
    publishableKey = BuildConfig.AUTHIO_PUBLISHABLE_KEY,
    apiUrl = BuildConfig.AUTHIO_API_URL,
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drain any inbound deep link (cold-start path).
        intent?.data?.toString()?.let(::handleDeepLink)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthioExampleApp(activity = this)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let(::handleDeepLink)
    }

    private fun handleDeepLink(url: String) {
        // OAuth callbacks land in the OAuthCustomTabs registry — the
        // suspending signInWithOAuth() call resumes from there.
        if (authio.handleOAuthCallback(url)) return
        // Magic-link callbacks need a coroutine; the screen consumes
        // them via `consumeMagicLinkCallback` and updates UI state.
        // (See AuthioExampleApp for the suspending call.)
    }
}

/**
 * The actual UI. Encapsulates:
 *   * Cold-start session restore from EncryptedSharedPreferences
 *   * Sign-in with passkey (the marquee feature)
 *   * Sign-in via magic link
 *   * Org switching when memberships > 1
 *   * Sign out
 */
@Composable
private fun AuthioExampleApp(activity: ComponentActivity) {
    val context = LocalContext.current
    val storage = remember { AuthioStorage(context) }
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf(storage.loadSession()) }
    var memberships by remember { mutableStateOf<List<MembershipWithOrg>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var orgMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Authio Compose example", style = MaterialTheme.typography.headlineSmall)

        if (session == null) {
            Text("You're signed out.", style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxSize(0.95f),
            )

            Button(onClick = {
                scope.launch {
                    runCatching { authio.signInWithPasskey(activity = activity, email = email.ifBlank { null }) }
                        .onSuccess {
                            session = it
                            storage.storeSession(it)
                            memberships = authio.listMyOrganizations(it)
                            status = "Signed in as ${it.user?.email}"
                        }
                        .onFailure { status = it.toUiMessage() }
                }
            }) { Text("Sign in with passkey") }

            OutlinedButton(onClick = {
                scope.launch {
                    runCatching {
                        authio.signUpWithPasskey(email = email, activity = activity)
                    }.onSuccess {
                        session = it
                        storage.storeSession(it)
                        status = "Created passkey for ${it.user?.email}"
                    }.onFailure { status = it.toUiMessage() }
                }
            }) { Text("Create new passkey for this email") }

            OutlinedButton(onClick = {
                scope.launch {
                    runCatching {
                        authio.sendMagicLink(
                            destination = email,
                            redirectUri = "myapp://auth-callback",
                        )
                    }
                        .onSuccess { status = "Magic link sent — check your inbox." }
                        .onFailure { status = it.toUiMessage() }
                }
            }) { Text("Send magic link") }
        } else {
            val s = session!!
            Text("Signed in as ${s.user?.email ?: s.userId}")
            Text("Active org: ${s.activeOrganization?.name ?: "(none selected)"}")
            Text("Role: ${s.role ?: "—"}")

            if (memberships.size > 1) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { orgMenuOpen = true }) {
                    Text("Switch organization")
                }
                DropdownMenu(expanded = orgMenuOpen, onDismissRequest = { orgMenuOpen = false }) {
                    memberships.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("${m.organization.name} (${m.role})") },
                            onClick = {
                                orgMenuOpen = false
                                scope.launch {
                                    runCatching { authio.switchOrganization(s, to = m.organization.id) }
                                        .onSuccess {
                                            session = it
                                            storage.storeSession(it)
                                            status = "Switched to ${it.activeOrganization?.name}"
                                        }
                                        .onFailure { status = it.toUiMessage() }
                                }
                            },
                        )
                    }
                }
            }

            Button(onClick = {
                scope.launch {
                    runCatching { authio.revokeSession(s) }
                    storage.deleteSession()
                    session = null
                    memberships = emptyList()
                    status = "Signed out."
                }
            }) { Text("Sign out") }
        }

        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun Throwable.toUiMessage(): String = when (this) {
    is AuthioError.NoCredentialAvailable -> "No passkey is registered for this account on this device."
    is AuthioError.UserCancelled -> "Cancelled."
    is AuthioError.Network -> "Network error — check your connection."
    is AuthioError.Api -> "$code: $message"
    else -> message ?: this::class.java.simpleName
}
