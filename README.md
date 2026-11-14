# MetricRaven for Android (Kotlin)

MetricRaven analytics for native Android apps, in Kotlin or Java: screen views, events, identify,
signed identify, consent and an offline queue. No dependencies. Android 6.0 (API 23) and later.

```kotlin
// app/build.gradle.kts
implementation("com.metricraven:metricraven-android:0.1.0")
```

```kotlin
import com.metricraven.MetricRaven

// In Application.onCreate. The app ID (your application ID) must be listed under
// Settings → General → Site → Mobile apps.
MetricRaven.configure(this, siteId = "ana_pub_…")

MetricRaven.screen("Home")
MetricRaven.track("signup", mapOf("plan" to "pro"))

// After sign-in: the same user ID your server events and server identify use.
MetricRaven.identify("user_42", mapOf("name" to "Ana", "email" to "ana@example.com"))
// Or verified, with a token your server signs (signIdentity in @metricraven/node):
MetricRaven.identifyWithToken(tokenFromYourServer)

// On sign-out.
MetricRaven.reset()
```

- **Consent.** By default nothing is stored on the device and the server derives a daily visitor ID.
  `configure(…, consent = true)` or `setConsent(true)` keeps a random ID so a visitor lasts across days.
- **Offline.** Events queue in SharedPreferences (up to 500, for 7 days) and are delivered in order,
  with backoff on network errors, rate limits and server errors. The queue flushes when the app comes
  to the foreground, goes to the background, and when the network comes back.
- **Language and screen size.** Each screen view carries the device's language (`en-US`) and the
  screen size in dp (`411x914`, like CSS pixels and iOS points). Events never carry them.
- **Deep links.** `MetricRaven.openedFrom(intent.data)` attaches utm_* campaign parameters to the next screen.

Docs: https://metricraven.com/docs/android-sdk

## Modules

- `metricraven-core` (`com.metricraven:metricraven-core`): pure Kotlin/JVM. The collect protocol,
  validation, the offline queue and an `HttpURLConnection` transport. `MetricRavenClient` can be used
  directly (any JVM, custom storage or transport).
- `metricraven-android` (`com.metricraven:metricraven-android`): `MetricRaven.configure(context, …)`,
  device info, `SharedPreferencesStorage`, lifecycle and network flushes.

## Development

Needs a JDK (17+) and, for the Android module, the Android SDK (`ANDROID_HOME` or `local.properties`).

```sh
./gradlew :metricraven-core:test            # unit + contract tests on the plain JVM
./gradlew :metricraven-android:assembleRelease :metricraven-android:lintRelease
```

The contract: `ContractTest` round-trips the Swift SDK's
`../sdk-swift/Tests/MetricRavenTests/contract-payloads.json` (a standalone copy keeps it in
`metricraven-core/src/test/resources/`) and asserts the client sends exactly `android-payloads.json`.
The backend's `swift-sdk-contract.test.ts` parses both files with `collectSchema`.

End to end against a **local** backend (never production): a JVM test and an on-device test.

```sh
METRICRAVEN_E2E_ENDPOINT=http://127.0.0.1:5221/api/v1/collect METRICRAVEN_E2E_SITE_ID=ana_pub_… \
METRICRAVEN_E2E_SECRET_KEY=ana_sec_… METRICRAVEN_E2E_APP_ID=com.metricraven.e2e \
  ./gradlew :metricraven-core:test --tests com.metricraven.LocalBackendE2ETest

# Emulator (10.0.2.2 is the host); the site must list com.metricraven.android.test.
./gradlew :metricraven-android:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.endpoint=http://10.0.2.2:5221/api/v1/collect \
  -Pandroid.testInstrumentationRunnerArguments.siteId=ana_pub_… \
  -Pandroid.testInstrumentationRunnerArguments.identityToken=<signIdentity token>
```
