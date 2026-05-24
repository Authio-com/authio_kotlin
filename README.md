<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/logo-dark.png">
    <img alt="Authio" src=".github/logo-light.png" width="220">
  </picture>
</p>

# authio-kotlin

> Part of **[Authio Lobby](https://authio.com/products/lobby)** —
> Authio's drop-in passwordless authentication. Learn more at
> https://authio.com/products/lobby.

Official **Kotlin / Android SDK** for [Authio](https://authio.com) — the
100% passwordless, multi-org-first auth Platform-as-a-Service.

> Multi-org-first: a single end-user identity can belong to many
> organizations (work, side-project, client #1, client #2…). The SDK
> exposes that everywhere — `AuthioSession.memberships`,
> `listMyOrganizations()`, `switchOrganization()` — without forcing
> users to maintain multiple email aliases.

---

## Modules

| Module                          | What it is                                                                 | Build target |
|---------------------------------|----------------------------------------------------------------------------|--------------|
| `:authio-core`                  | Pure Kotlin/JVM. HTTP, JSON, types, magic-link, OAuth URL building, sessions. | JVM 17       |
| `:authio-android`               | Android library. Adds Credential Manager passkey, Custom Tabs OAuth, EncryptedSharedPreferences. | `compileSdk` 34, `minSdk` 28 |
| `:examples:jetpack-compose`     | Reference Compose app — sign-in, magic-link, org switching.                | Android app  |

The split keeps `:authio-core` unit-testable on plain JVM (no Android
SDK required) and gives a clean seam if a Kotlin-Multiplatform port
ships later.

---

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// build.gradle.kts (your Android app module)
dependencies {
    implementation("com.authio:authio-android:0.1.0-alpha.0")
}
```

> Maven Central publishing requires a Sonatype account + signing key.
> Until that lands you can pull from the GitHub Packages mirror or
> consume the `:authio-android` AAR built locally with
> `./gradlew :authio-android:assembleRelease`.

---

## Quickstart

```kotlin
import com.authio.AuthioSession
import com.authio.OAuthProvider
import com.authio.android.AuthioAndroid
import com.authio.android.AuthioStorage

val authio = AuthioAndroid.create(
    publishableKey = "pk_live_…",
    apiUrl = "https://api.authio.com", // or your self-hosted control plane
)

// Sign in with a passkey — pass an Activity (Credential Manager needs it).
val session: AuthioSession = authio.signInWithPasskey(activity = activity)

// Persist across app launches.
val store = AuthioStorage(context)
store.storeSession(session)

// On next app launch:
val cached = store.loadSession() ?: run {
    // Show your sign-in screen.
}
```

### Sign up a new passkey

```kotlin
val session = authio.signUpWithPasskey(
    email = "alice@example.com",
    activity = activity,
)
```

### Magic link (email or SMS)

```kotlin
authio.sendMagicLink(
    destination = "alice@example.com",
    redirectUri = "myapp://auth-callback",
)

// In your activity that owns the `myapp://auth-callback` intent-filter:
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val url = intent.data?.toString() ?: return
    lifecycleScope.launch {
        val session = authio.consumeMagicLinkCallback(url)
        store.storeSession(session)
    }
}
```

### OAuth via Custom Tabs

```kotlin
val session = authio.signInWithOAuth(
    provider = OAuthProvider.GOOGLE,
    redirectUri = "myapp://auth-callback",
    activity = activity,
)
```

…and in `onNewIntent`:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.toString()?.let { authio.handleOAuthCallback(it) }
}
```

The suspending `signInWithOAuth` resumes once the redirect returns.

### Multi-org flow

```kotlin
val orgs = authio.listMyOrganizations(session)

if (orgs.size > 1 && session.activeOrganization == null) {
    // Show the user a picker.
    val pick = orgs.first()
    val updated = authio.switchOrganization(session, to = pick.organization.id)
    store.storeSession(updated)
}
```

### Verifying a stored session

```kotlin
val stillValid = authio.verify(session)
if (!stillValid) {
    store.deleteSession()
    // Re-prompt for sign-in.
}
```

The current alpha calls `GET /v1/me` to check validity. A future
revision will additionally verify the access-token JWT against the
`/v1/.well-known/jwks.json` endpoint locally to skip the round-trip.

### Sign out

```kotlin
authio.revokeSession(session)
store.deleteSession()
```

---

## Error handling

Every failure surfaces as an `AuthioError` subclass:

```kotlin
try {
    authio.signInWithPasskey(activity)
} catch (e: AuthioError.NoCredentialAvailable) {
    // Show "Create a passkey" CTA
} catch (e: AuthioError.UserCancelled) {
    // User dismissed the system prompt — silently return
} catch (e: AuthioError.Network) {
    // Show retry UI
} catch (e: AuthioError.Api) {
    // Server-side failure — log e.requestId for support
    Log.w("authio", "${e.code} (${e.status}) — req=${e.requestId}")
}
```

All subclasses extend the sealed `AuthioError` so a single
`catch (e: AuthioError)` clause works as a fallback.

---

## Manifest setup

Declare the deep link in your `AndroidManifest.xml`:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTask">

    <intent-filter android:autoVerify="false">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="myapp" android:host="auth-callback" />
    </intent-filter>
</activity>
```

`singleTask` is important — without it the deep-link redirect would
spawn a second `MainActivity` instance and break the in-flight OAuth
flow.

For passkeys to work the relying-party domain must serve a
`.well-known/assetlinks.json` file authorising your app's signature.
See [Android docs](https://developer.android.com/training/sign-in/passkeys)
for the exact format.

---

## Security notes

* **Sessions live in `EncryptedSharedPreferences`** — AES-256 GCM keys
  backed by Android Keystore. No plaintext on disk.
* **Custom Tabs over WebView** — Custom Tabs share the system browser
  cookie jar, so users get SSO with Chrome / Edge automatically; WebView
  is a separate cookie jar that strands the user.
* **Passkey ceremony JSON is server-issued and server-verified** — the
  SDK never decodes the WebAuthn payload locally. The server is the
  source of truth on RP ID, allowed origins, and challenge expiry.
* **Publishable key in `BuildConfig`** is fine — `pk_…` keys are
  designed to be embedded in client apps; revoke them in the dashboard
  if leaked.

---

## Building

```bash
# Pure-JVM unit tests (no Android SDK needed):
./gradlew :authio-core:test

# Android library + example app (requires the Android SDK installed):
AUTHIO_INCLUDE_ANDROID=true ./gradlew :authio-android:assembleRelease
AUTHIO_INCLUDE_ANDROID=true ./gradlew :examples:jetpack-compose:assembleDebug
```

The default build excludes the Android modules so contributors without
the SDK installed can still run `:authio-core:test`. Set
`authio.includeAndroid=true` in `gradle.properties` (or export
`AUTHIO_INCLUDE_ANDROID=true`) to opt in.

The CI job sets the env var; Android Studio also sets it via the
`local.properties` SDK path.

### Publishing (when ready)

```bash
./gradlew :authio-core:publishToMavenLocal
./gradlew :authio-android:publishToMavenLocal
```

For Maven Central:

1. Add Sonatype credentials to `~/.gradle/gradle.properties`:
   ```properties
   sonatypeUsername=…
   sonatypePassword=…
   signing.keyId=…
   signing.password=…
   signing.secretKeyRingFile=…
   ```
2. Apply the `signing` plugin to each subproject.
3. `./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository`

(The signing plugin is intentionally not pre-applied — it requires the
publisher's PGP key.)

---

## License

MIT — see [LICENSE](LICENSE).
