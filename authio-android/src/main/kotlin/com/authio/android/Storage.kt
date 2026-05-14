package com.authio.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.authio.AuthioJsonAccess
import com.authio.AuthioSession

/**
 * Encrypted on-device session store. Wraps `EncryptedSharedPreferences`
 * (AES-256 GCM keys backed by the Android Keystore). Multiple accounts
 * can be stored concurrently — distinguish them with the [account] key,
 * which is useful for our multi-org-friendly users that want to keep
 * separate sessions for, say, their work and personal Authio identities.
 *
 * Threading: SharedPreferences is process-safe; commit is synchronous,
 * apply is async. We use `commit` here because session writes happen
 * around app-launch boundaries where you want the bytes flushed before
 * the activity proceeds.
 */
class AuthioStorage(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "authio.sessions",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun storeSession(session: AuthioSession, account: String = DEFAULT_ACCOUNT) {
        val payload = AuthioJsonAccess.json.encodeToString(AuthioSession.serializer(), session)
        prefs.edit().putString(key(account), payload).commit()
    }

    fun loadSession(account: String = DEFAULT_ACCOUNT): AuthioSession? {
        val raw = prefs.getString(key(account), null) ?: return null
        return runCatching {
            AuthioJsonAccess.json.decodeFromString(AuthioSession.serializer(), raw)
        }.getOrNull()
    }

    fun deleteSession(account: String = DEFAULT_ACCOUNT) {
        prefs.edit().remove(key(account)).commit()
    }

    fun listAccounts(): List<String> = prefs.all.keys
        .filter { it.startsWith(KEY_PREFIX) }
        .map { it.removePrefix(KEY_PREFIX) }

    private fun key(account: String): String = "$KEY_PREFIX$account"

    companion object {
        const val DEFAULT_ACCOUNT: String = "default"
        private const val KEY_PREFIX: String = "session:"
    }
}
