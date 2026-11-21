# MetricRaven for Android (Kotlin)

[![Maven Central](https://img.shields.io/maven-central/v/com.metricraven/metricraven-android)](https://central.sonatype.com/artifact/com.metricraven/metricraven-android)

The Android SDK for [MetricRaven](https://metricraven.com), privacy-friendly analytics with revenue
attribution: see which traffic actually pays. It records screen views, events and signed-in users from
native Android apps in Kotlin or Java, so app users sit next to your website visitors, payments and
subscriptions in one dashboard. No dependencies. Android 6.0 (API 23) and later.

## Install

Maven Central is in every new project's repositories.

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.metricraven:metricraven-android:0.2.0")
}
```

## Usage

In your site's **Settings → General → Site**, add your application ID (for example `com.example.app`)
under **Mobile apps**. Events from app IDs that aren't listed are rejected.

```kotlin
import android.app.Application
import com.metricraven.MetricRaven

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The app ID defaults to your application ID; version, build and device are read automatically.
        MetricRaven.configure(this, siteId = "ana_pub_…")
    }
}
```

```kotlin
MetricRaven.screen("Home")
MetricRaven.track("signup", mapOf("plan" to "pro"))

// After sign-in: the same user ID your server events, server identify and payments use.
MetricRaven.identify("user_42", mapOf("name" to "Ana", "email" to "ana@example.com"))
// Or verified, with a token your server signs (signIdentity in @metricraven/node):
MetricRaven.identifyWithToken(tokenFromYourServer)

// On sign-out.
MetricRaven.reset()
```

Open the app on a device or emulator: it shows as a live visitor in Traffic, under the person you
identified it as, and screens appear under Pages as paths like `/Home`.

## Features

- **Revenue per user and channel.** Identify with the same user ID you use for RevenueCat, Superwall,
  Adapty, Stripe or your server, and purchases and renewals are credited to where that user came from.
- **Paywall events.** Views, closes, cancels and taps from RevenueCat, Superwall, Adapty or your own
  paywall, with no dependency on their SDKs (see below).
- **Consent.** By default nothing is stored on the device and the server derives a daily visitor ID.
  `configure(…, consent = true)` or `setConsent(true)` keeps a random ID so a visitor lasts across days.
- **Offline queue.** Events queue in SharedPreferences (up to 500, for 7 days) and are delivered in order,
  with backoff on network errors, rate limits and server errors. The queue flushes when the app comes
  to the foreground, goes to the background, and when the network comes back.
- **Language and screen size.** Each screen view carries the device's language (`en-US`) and the
  screen size in dp (`411x914`, like CSS pixels and iOS points). Events never carry them.
- **Deep links.** `MetricRaven.openedFrom(intent.data)` attaches utm_* campaign parameters to the next screen.

### Paywall events

```kotlin
val paywall = MetricRaven.paywall(provider = "revenuecat", offeringId = offering.identifier)
paywall.impression()
// PaywallListener: onPurchaseCancelled → paywall.cancel(), onInteraction → paywall.interaction(event.rawProperties)
// Superwall: paywall.superwall(eventInfo.event.rawName, eventInfo.params)
```

Send paywall events from the app or forward them from your backend, not both, or they are counted
twice. See [Paywall events](https://metricraven.com/docs/paywall-events).

## Privacy

The library adds only the `INTERNET` and `ACCESS_NETWORK_STATE` permissions. It reads no advertising ID
or device identifier, and without consent it stores nothing on the device.

## Links

- Docs: https://metricraven.com/docs/android-sdk
- Paywall events: https://metricraven.com/docs/paywall-events
- Dashboard and sign-up: https://metricraven.com
- iOS and Swift: https://github.com/Dual-Ninja-LLC/metricraven-swift
- Support: support@metricraven.com

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

## License

MIT. See [LICENSE](LICENSE). Published by Dual Ninja LLC.
