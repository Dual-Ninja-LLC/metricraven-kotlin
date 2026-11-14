package com.metricraven

import java.net.URI
import java.net.URLDecoder

/** Where the app runs, as the server's collect schema names it. */
public enum class AppPlatform(public val wire: String) {
    IOS("ios"), ANDROID("android"), WEB("web"), MACOS("macos"), WINDOWS("windows"), OTHER("other");

    internal companion object {
        fun of(wire: String?): AppPlatform = values().firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("platform $wire")
    }
}

public enum class DeviceType(public val wire: String) {
    MOBILE("mobile"), TABLET("tablet"), DESKTOP("desktop");

    internal companion object {
        fun of(wire: String?): DeviceType? = wire?.let { w -> values().firstOrNull { it.wire == w } ?: throw IllegalArgumentException("deviceType $w") }
    }
}

/**
 * Sent with every request instead of a page URL. The app ID (the application ID / package name) must
 * be listed in the site's settings (Settings, General, Site, Mobile apps).
 */
internal data class AppInfo(
    val id: String,
    val version: String? = null,
    val build: String? = null,
    val platform: AppPlatform,
    val osVersion: String? = null,
    val deviceType: DeviceType? = null,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id, "version" to version, "build" to build, "platform" to platform.wire,
        "osVersion" to osVersion, "deviceType" to deviceType?.wire,
    )

    companion object {
        fun fromJson(m: Map<*, *>) = AppInfo(
            id = m["id"] as String,
            version = m["version"] as String?,
            build = m["build"] as String?,
            platform = AppPlatform.of(m["platform"] as String?),
            osVersion = m["osVersion"] as String?,
            deviceType = DeviceType.of(m["deviceType"] as String?),
        )
    }
}

internal data class Utm(
    val source: String? = null,
    val medium: String? = null,
    val campaign: String? = null,
    val term: String? = null,
    val content: String? = null,
) {
    val isEmpty: Boolean get() = listOf(source, medium, campaign, term, content).all { it == null }

    fun toJson(): Map<String, Any?> = mapOf("source" to source, "medium" to medium, "campaign" to campaign, "term" to term, "content" to content)

    companion object {
        fun fromJson(m: Map<*, *>) = Utm(m["source"] as String?, m["medium"] as String?, m["campaign"] as String?, m["term"] as String?, m["content"] as String?)
    }
}

internal enum class Kind(val wire: String) { PAGEVIEW("pageview"), EVENT("event"), LINK("link"), IDENTIFY("identify") }

/**
 * The collect payload POST /api/v1/collect accepts from a native app (backend lib/schemas.ts
 * collectSchema; the same shape as the Swift SDK and @metricraven/react-native). Prop and trait values
 * are String, Double or Boolean.
 */
internal data class CollectPayload(
    val siteId: String,
    val type: Kind,
    val timestamp: String,
    val app: AppInfo,
    val clientId: String? = null,
    val link: Boolean? = null,
    val screen: String? = null,
    val name: String? = null,
    val props: Map<String, Any>? = null,
    val userId: String? = null,
    val traits: Map<String, Any>? = null,
    val identityToken: String? = null,
    val utm: Utm? = null,
    /** Device language as a BCP 47 tag ("en-US"). Screen views only. */
    val lang: String? = null,
    /** Screen size in dp ("411x914"), like CSS pixels on the web and points on iOS. Screen views only. */
    val screenSize: String? = null,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "siteId" to siteId, "type" to type.wire, "timestamp" to timestamp, "app" to app.toJson(),
        "clientId" to clientId, "link" to link, "screen" to screen, "name" to name, "props" to props,
        "userId" to userId, "traits" to traits, "identityToken" to identityToken, "utm" to utm?.toJson(),
        "lang" to lang, "screenSize" to screenSize,
    )

    companion object {
        fun fromJson(m: Map<*, *>): CollectPayload = CollectPayload(
            siteId = m["siteId"] as String,
            type = Kind.values().first { it.wire == m["type"] },
            timestamp = m["timestamp"] as String,
            app = AppInfo.fromJson(m["app"] as Map<*, *>),
            clientId = m["clientId"] as String?,
            link = m["link"] as Boolean?,
            screen = m["screen"] as String?,
            name = m["name"] as String?,
            props = values(m["props"]),
            userId = m["userId"] as String?,
            traits = values(m["traits"]),
            identityToken = m["identityToken"] as String?,
            utm = (m["utm"] as Map<*, *>?)?.let(Utm::fromJson),
            lang = m["lang"] as String?,
            screenSize = m["screenSize"] as String?,
        )

        private fun values(raw: Any?): Map<String, Any>? = (raw as Map<*, *>?)?.entries?.associate { (k, v) ->
            k.toString() to when (v) {
                is String, is Boolean -> v
                is Number -> v.toDouble()
                else -> throw IllegalArgumentException("unsupported value for $k")
            }
        }
    }
}

internal object Validate {
    val eventName = Regex("^[a-zA-Z][a-zA-Z0-9_:.-]{0,99}$")
    val appId = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$")
    val clientId = Regex("^[0-9a-f]{32}$")
    val identityToken = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
    private val traitKey = Regex("^[a-z][a-z0-9_-]{0,63}$")
    private val email = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val imageUrl = Regex("^https://[A-Za-z0-9.-]+(?::\\d{1,5})?(?:[/?#]\\S*)?$")
    private val languageTag = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
    private val screenSizePattern = Regex("^\\d{2,5}x\\d{2,5}$")

    /** The first [max] UTF-16 units (the server's limits count them too), never splitting a surrogate pair. */
    fun prefix(value: String, max: Int): String {
        if (value.length <= max) return value
        val end = if (Character.isHighSurrogate(value[max - 1])) max - 1 else max
        return value.substring(0, end)
    }

    private fun trimmed(value: String?, max: Int): String? {
        val v = value?.trim() ?: return null
        return if (v.isEmpty()) null else prefix(v, max)
    }

    private fun withoutControls(value: String): String = value.filter { it.code >= 0x20 && it.code != 0x7f }

    /** A finite number, a string or a boolean; anything else is dropped. Numbers become Double. */
    private fun value(raw: Any?, maxString: Int): Any? = when (raw) {
        is String -> prefix(raw, maxString)
        is Boolean -> raw
        is Number -> raw.toDouble().takeIf { it.isFinite() }
        is Char -> raw.toString()
        else -> null
    }

    /** Up to 10 props; keys up to 64 characters, strings up to 500. Invalid entries are dropped. */
    fun props(props: Map<String, Any?>?): Map<String, Any>? {
        if (props == null) return null
        val out = LinkedHashMap<String, Any>()
        for (key in props.keys.sorted()) {
            if (out.size >= 10 || key.isEmpty() || key.length > 64) continue
            out[key] = value(props[key], 500) ?: continue
        }
        return out.ifEmpty { null }
    }

    /** name, email, image (https profile picture) and up to 10 custom traits with lowercase keys. */
    fun traits(traits: Map<String, Any?>?): Map<String, Any> {
        if (traits == null) return emptyMap()
        val out = LinkedHashMap<String, Any>()
        var custom = 0
        for (key in traits.keys.sorted()) {
            val raw = traits[key] ?: continue
            when (key) {
                "name" -> (raw as? String)?.let { trimmed(it, 200) }?.let { out[key] = it }
                "email" -> (raw as? String)?.let { trimmed(it, 320) }?.takeIf { email.matches(it) }?.let { out[key] = it }
                "image" -> (raw as? String)?.let(::imageUrlValue)?.let { out[key] = it }
                else -> {
                    if (custom >= 10 || !traitKey.matches(key)) continue
                    out[key] = value(raw, 255) ?: continue
                    custom++
                }
            }
        }
        return out
    }

    fun imageUrlValue(value: String): String? {
        val raw = value.trim()
        return if (raw.length <= 500 && imageUrl.matches(raw) && withoutControls(raw) == raw) raw else null
    }

    fun userId(value: String): String? = trimmed(value, 200)

    /** Screen names and routes as sent; the server turns "Settings" into the path "/Settings". */
    fun screen(value: String): String? {
        val clean = withoutControls(value).trim()
        return if (clean.isEmpty()) null else prefix(clean, 300)
    }

    fun short(value: String?): String? = trimmed(value, 50)

    /** A language tag such as "en-US" (at most 35 characters); "en_US" becomes "en-US". "und" is dropped. */
    fun language(value: String?): String? {
        val tag = trimmed(value, 100)?.replace('_', '-') ?: return null
        return if (tag.length <= 35 && languageTag.matches(tag) && !tag.equals("und", ignoreCase = true)) tag else null
    }

    /** "WxH", e.g. "411x914"; anything else is dropped. */
    fun screenSize(value: String?): String? = trimmed(value, 20)?.takeIf { screenSizePattern.matches(it) }

    /** "WxH" from a size in dp (or points), rounded. */
    fun screenSize(width: Double, height: Double): String? {
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || width >= 100_000 || height >= 100_000) return null
        return screenSize("${Math.round(width)}x${Math.round(height)}")
    }

    /** Campaign parameters from a deep link (utm_source…); nothing else from the URL is kept. */
    fun utm(url: String?): Utm? {
        val query = try {
            URI(url ?: return null).rawQuery
        } catch (_: Exception) {
            null
        } ?: return null
        var utm = Utm()
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = decode(pair.substring(0, eq)) ?: continue
            val value = decode(pair.substring(eq + 1))?.takeIf { it.isNotEmpty() } ?: continue
            val v = prefix(value, 200)
            utm = when (name) {
                "utm_source" -> utm.copy(source = v)
                "utm_medium" -> utm.copy(medium = v)
                "utm_campaign" -> utm.copy(campaign = v)
                "utm_term" -> utm.copy(term = v)
                "utm_content" -> utm.copy(content = v)
                else -> utm
            }
        }
        return if (utm.isEmpty) null else utm
    }

    /** Percent-decoding as URLComponents does it: a "+" stays a plus. */
    private fun decode(raw: String): String? = try {
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        null
    }
}
