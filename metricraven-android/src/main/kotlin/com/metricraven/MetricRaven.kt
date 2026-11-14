package com.metricraven

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.metricraven.android.DeviceInfo
import com.metricraven.android.SharedPreferencesStorage

/**
 * The shared client for an Android app, set up once at launch (Application.onCreate):
 *
 * ```kotlin
 * MetricRaven.configure(this, siteId = "ana_pub_…")
 * MetricRaven.screen("Home")
 * MetricRaven.track("signup", mapOf("plan" to "pro"))
 * MetricRaven.identify("user_42", mapOf("name" to "Ana", "email" to "ana@example.com"))
 * ```
 *
 * Calls before `configure` (or after a failed one) do nothing.
 */
public object MetricRaven {
    private const val TAG = "MetricRaven"
    private val lock = Any()
    @Volatile private var client: MetricRavenClient? = null
    private var observing = false

    /** The configured client, for anything the shortcuts below don't cover. */
    @JvmStatic
    public val shared: MetricRavenClient? get() = client

    /**
     * Starts tracking. App version and build, Android version, device type, language and screen size
     * are read from the package and device; the app ID defaults to the application ID (package name).
     * Returns false when the app ID isn't an application ID like com.example.app.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(
        context: Context,
        siteId: String,
        appId: String? = null,
        consent: Boolean = false,
        apiHost: String = "https://metricraven.com",
        endpoint: String? = null,
        disabled: Boolean = false,
        debug: Boolean = false,
    ): Boolean {
        val app = context.applicationContext ?: context
        val (version, build) = DeviceInfo.appVersion(app)
        val configuration = MetricRavenConfiguration(
            siteId = siteId,
            appId = appId ?: app.packageName,
            appVersion = version,
            appBuild = build,
            platform = AppPlatform.ANDROID,
            osVersion = DeviceInfo.osVersion,
            deviceType = DeviceInfo.deviceType(app),
            language = DeviceInfo.language(app),
            screenSize = DeviceInfo.screenSize(app),
            apiHost = apiHost,
            endpoint = endpoint,
            consent = consent,
            storage = SharedPreferencesStorage(app),
            disabled = disabled,
            debug = debug,
            logger = { Log.d(TAG, it) },
        )
        val created = try {
            MetricRavenClient(configuration)
        } catch (e: MetricRavenException) {
            if (debug) Log.w(TAG, "configure failed: ${e.message}")
            return false
        }
        synchronized(lock) {
            client?.shutdown()
            client = created
        }
        observe(app)
        return true
    }

    @JvmStatic @JvmOverloads
    public fun screen(name: String, props: Map<String, Any?>? = null) { client?.screen(name, props) }

    @JvmStatic @JvmOverloads
    public fun track(name: String, props: Map<String, Any?>? = null) { client?.track(name, props) }

    @JvmStatic @JvmOverloads
    public fun identify(userId: String, traits: Map<String, Any?>? = null) { client?.identify(userId, traits) }

    /** Signed identify with a token your server signed (signIdentity in @metricraven/node). */
    @JvmStatic
    public fun identifyWithToken(token: String) { client?.identifyWithToken(token) }

    @JvmStatic
    public fun reset() { client?.reset() }

    @JvmStatic
    public fun setConsent(granted: Boolean) { client?.setConsent(granted) }

    /** utm_* campaign parameters from a deep link (e.g. `intent.data`), attached to the next screen view. */
    @JvmStatic
    public fun openedFrom(uri: Uri?) { client?.openedFrom(uri?.toString()) }

    @JvmStatic
    public fun openedFrom(url: String?) { client?.openedFrom(url) }

    /** Sends what is queued now. [onComplete] runs on the SDK's background thread. */
    @JvmStatic @JvmOverloads
    public fun flush(onComplete: (() -> Unit)? = null) {
        client?.flush(onComplete) ?: onComplete?.invoke()
    }

    /**
     * Back in the foreground or back online: deliver what queued while offline. Going to the
     * background: send what is pending before the process can be frozen. No AndroidX dependency.
     */
    private fun observe(context: Context) {
        synchronized(lock) {
            if (observing) return
            observing = true
        }
        (context as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) flush()
            }
            override fun onActivityStopped(activity: Activity) {
                if (started > 0 && --started == 0) flush()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        try {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = flush()
            }
            if (Build.VERSION.SDK_INT >= 24) {
                connectivity.registerDefaultNetworkCallback(callback)
            } else {
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    callback,
                )
            }
        } catch (e: Exception) {
            // Without ACCESS_NETWORK_STATE the queue still flushes on foreground and background.
            Log.w(TAG, "network callback unavailable: $e")
        }
    }
}
