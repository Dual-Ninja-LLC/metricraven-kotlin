package com.metricraven.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.metricraven.DeviceType
import com.metricraven.MetricRavenStorage
import java.util.Locale

/** SharedPreferences ("metricraven" by default): the offline queue (at most 500 events) and, with consent, the visitor ID. */
public class SharedPreferencesStorage(private val preferences: SharedPreferences) : MetricRavenStorage {
    public constructor(context: Context, name: String = "metricraven") :
        this(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun setString(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }
}

/** What the SDK reads from the device: nothing that identifies the person or the device. */
public object DeviceInfo {
    /** Android's version, e.g. "16". */
    @JvmStatic
    public val osVersion: String get() = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    /** Desktop for ChromeOS and Android PCs, tablet from a smallest width of 600 dp, otherwise mobile. */
    @JvmStatic
    public fun deviceType(context: Context): DeviceType {
        val pm = context.packageManager
        if (pm.hasSystemFeature("android.hardware.type.pc")) return DeviceType.DESKTOP
        return if (context.resources.configuration.smallestScreenWidthDp >= 600) DeviceType.TABLET else DeviceType.MOBILE
    }

    /** The user's first preferred language as a BCP 47 tag, e.g. "en-US". */
    @JvmStatic
    public fun language(context: Context): String? {
        val configuration: Configuration = context.resources.configuration
        val locale: Locale? = if (Build.VERSION.SDK_INT >= 24) {
            configuration.locales.takeIf { !it.isEmpty }?.get(0)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale
        }
        return (locale ?: Locale.getDefault()).toLanguageTag()
    }

    /**
     * The whole screen in dp ("411x914"), like CSS pixels on the web and points on iOS; in the current
     * orientation, like the Swift SDK. Null when unknown.
     */
    @JvmStatic
    @SuppressLint("NewApi")
    public fun screenSize(context: Context): String? = try {
        val density = context.resources.displayMetrics.density
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (width, height) = if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            size.x to size.y
        }
        if (density > 0 && width > 0 && height > 0) "${Math.round(width / density)}x${Math.round(height / density)}" else null
    } catch (_: Exception) {
        null
    }

    /** versionName and versionCode from the app's package. */
    internal fun appVersion(context: Context): Pair<String?, String?> = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        info.versionName to code.toString()
    } catch (_: Exception) {
        null to null
    }
}
