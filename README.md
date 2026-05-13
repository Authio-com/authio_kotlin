# authio-kotlin

Authio Android / Kotlin SDK. Uses Android Credential Manager for native passkeys.

## Install

Gradle:

```kotlin
dependencies {
    implementation("com.authio:authio:0.1.0-alpha")
}
```

## Quick start

```kotlin
import com.authio.Authio

val authio = Authio(apiKey = BuildConfig.AUTHIO_SECRET_KEY)
val memberships = authio.listMemberships(userId = "user_01HX...")
```

## License

MIT
