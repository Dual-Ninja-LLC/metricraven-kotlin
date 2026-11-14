package com.metricraven

import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** How the client talks to MetricRaven. Only [siteId] and [appId] are required. */
public class MetricRavenConfiguration @JvmOverloads constructor(
    /** Your site ID (ana_pub_…). */
    public val siteId: String,
    /** Application ID, e.g. com.example.app. Add it under Settings, General, Site, Mobile apps. */
    public val appId: String,
    public val appVersion: String? = null,
    public val appBuild: String? = null,
    public val platform: AppPlatform = AppPlatform.OTHER,
    public val osVersion: String? = null,
    public val deviceType: DeviceType? = null,
    /** Device language, e.g. "en-US". Sent with screen views only. `MetricRaven.configure` reads it from the device. */
    public val language: String? = null,
    /** Screen size in dp, "411x914". Sent with screen views only. `MetricRaven.configure` reads it from the device. */
    public val screenSize: String? = null,
    /** Default https://metricraven.com. */
    public val apiHost: String = "https://metricraven.com",
    /** Full collect URL, overriding apiHost (e.g. your own proxy). */
    public val endpoint: String? = null,
    /**
     * Analytics storage consent already granted. Default false: nothing is stored on the device and
     * the server derives a daily visitor ID. With consent a random ID is kept in [storage].
     */
    public val consent: Boolean = false,
    public val storage: MetricRavenStorage = MemoryStorage(),
    public val transport: MetricRavenTransport = HttpUrlConnectionTransport(),
    /** Events kept while offline; the oldest are dropped beyond this. Default 500. */
    public val maxQueueSize: Int = 500,
    /** Queued events older than this are dropped. Default 7 days. */
    public val maxEventAgeMillis: Long = 7 * 86_400_000L,
    /** Turns tracking off entirely (e.g. in development). */
    public val disabled: Boolean = false,
    public val debug: Boolean = false,
    /** Where debug messages go. Default: standard output. */
    public val logger: (String) -> Unit = { println("[metricraven] $it") },
    /** For tests: the clock, in epoch milliseconds. */
    public val now: () -> Long = { System.currentTimeMillis() },
    /** For tests: the consented visitor ID generator. */
    public val randomId: () -> String = { MetricRavenClient.randomClientId() },
)

/** Why a client can't be created. */
public sealed class MetricRavenException(message: String) : IllegalArgumentException(message) {
    public class MissingSiteId : MetricRavenException("siteId is required")
    public class InvalidAppId(public val appId: String) : MetricRavenException("$appId is not an application ID like com.example.app")
}

/**
 * MetricRaven for native apps: screen views, events, identify, consent and an offline queue. Calls
 * return at once and run in order on one background thread; events are delivered in order, retried
 * with backoff while offline or rate limited, and kept across launches by [MetricRavenStorage].
 */
public class MetricRavenClient(configuration: MetricRavenConfiguration) {
    private val engine: Engine
    private val executor: ScheduledExecutorService

    init {
        if (configuration.siteId.isEmpty()) throw MetricRavenException.MissingSiteId()
        if (!Validate.appId.matches(configuration.appId)) throw MetricRavenException.InvalidAppId(configuration.appId)
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "metricraven").apply { isDaemon = true }
        }
        engine = Engine(configuration, executor)
        post { engine.load() }
    }

    private fun post(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Shut down: calls are ignored.
        }
    }

    /** A screen view (a pageview whose path is the screen name or route). */
    @JvmOverloads
    public fun screen(name: String, props: Map<String, Any?>? = null): Unit = post { engine.screen(name, props) }

    /** A custom event (goal). Names: letters, digits, _ : . - ; up to 100 characters. Up to 10 props. */
    @JvmOverloads
    public fun track(name: String, props: Map<String, Any?>? = null): Unit = post { engine.track(name, props) }

    /**
     * Links this device's visitor to your user ID. Unsigned it is self-asserted, and a server
     * identify (secret key) takes precedence. Use the same user ID as your server events.
     */
    @JvmOverloads
    public fun identify(userId: String, traits: Map<String, Any?>? = null): Unit = post { engine.identify(userId, traits, null) }

    /**
     * Signed identify: a token your server made with a site secret key (signIdentity in
     * @metricraven/node). Verified; the user ID and traits come from the token.
     */
    @JvmOverloads
    public fun identifyWithToken(token: String, userId: String? = null): Unit = post { engine.identify(userId, null, token) }

    /** On logout: forgets identify state and, with consent, starts a new visitor ID. */
    public fun reset(): Unit = post { engine.reset() }

    public fun setConsent(granted: Boolean): Unit = post { engine.consent(granted) }

    /** Campaign parameters (utm_*) from a deep link, attached to the next screen view. */
    public fun openedFrom(url: String?): Unit = post { engine.openedFrom(url) }

    /** The visitor ID events are recorded under (the consented ID, or the server's daily ID). */
    public val visitorId: String? get() = engine.visibleId

    /** Events waiting to be delivered. */
    public val pending: Int get() = engine.visibleCount

    /**
     * Sends everything queued now (e.g. when the app returns to the foreground), skipping a pending
     * backoff but never a server's Retry-After. [onComplete] runs on the SDK thread when done.
     */
    @JvmOverloads
    public fun flush(onComplete: (() -> Unit)? = null) {
        try {
            executor.execute { engine.forceFlush { onComplete?.invoke() } }
        } catch (_: RejectedExecutionException) {
            onComplete?.invoke()
        }
    }

    /**
     * [flush], waiting up to [timeoutMillis] for it to finish. For background work and tests; never
     * call it on the main thread. Returns false on timeout.
     */
    @JvmOverloads
    public fun flushBlocking(timeoutMillis: Long = 30_000): Boolean {
        val done = CountDownLatch(1)
        flush { done.countDown() }
        return done.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** Stops delivery; queued events stay in storage for the next launch. */
    public fun shutdown() {
        post { engine.shutdown() }
        executor.shutdown()
    }

    public companion object {
        private val random = SecureRandom()

        /** 32 lowercase hex characters. */
        @JvmStatic
        public fun randomClientId(): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return bytes.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
        }

        /** Retry later on network errors, timeouts, rate limits and server errors; drop other rejections. */
        @JvmStatic
        public fun shouldRetry(status: Int?): Boolean = status == null || status == 408 || status == 429 || status >= 500

        /** Seconds to wait before the next attempt: the server's Retry-After, else 1, 2, 4… capped at 5 minutes. */
        @JvmStatic
        public fun backoff(attempts: Int, retryAfter: Double?): Double {
            val cap = 300.0
            if (retryAfter != null && retryAfter > 0) return minOf(cap, retryAfter)
            return minOf(cap, Math.pow(2.0, maxOf(0, attempts - 1).toDouble()))
        }
    }
}

internal class Queued(val payload: CollectPayload, var attempts: Int, val queuedAt: Long) {
    fun toJson(): Map<String, Any?> = mapOf("payload" to payload.toJson(), "attempts" to attempts, "queuedAt" to queuedAt)

    companion object {
        fun fromJson(m: Map<*, *>) = Queued(
            CollectPayload.fromJson(m["payload"] as Map<*, *>),
            (m["attempts"] as Number).toInt(),
            (m["queuedAt"] as Number).toLong(),
        )
    }
}

/** All state lives on the client's single executor thread; only the visible* fields are read elsewhere. */
internal class Engine(private val config: MetricRavenConfiguration, private val executor: ScheduledExecutorService) {
    private val app = AppInfo(
        id = config.appId,
        version = Validate.short(config.appVersion),
        build = Validate.short(config.appBuild),
        platform = config.platform,
        osVersion = Validate.short(config.osVersion),
        deviceType = config.deviceType,
    )
    private val language = Validate.language(config.language)
    private val screenSize = Validate.screenSize(config.screenSize)
    private val endpoint = config.endpoint ?: (config.apiHost.trimEnd('/') + "/api/v1/collect")
    private val queueKey = "metricraven:queue:${config.siteId}"
    private val idKey = "metricraven:client-id:${config.siteId}"
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val queue = ArrayList<Queued>()
    private var clientId: String? = null
    private var serverVisitorId: String? = null
    private var currentScreen: String? = null
    private var pendingUtm: Utm? = null
    private var lastIdentify: String? = null
    private var pendingLink = false
    private var draining = false
    private var retry: ScheduledFuture<*>? = null
    private var notBefore = Long.MIN_VALUE
    private var stopped = config.disabled
    private val flushWaiters = ArrayList<() -> Unit>()

    @Volatile var visibleId: String? = null
        private set
    @Volatile var visibleCount: Int = 0
        private set

    private fun log(message: () -> String) {
        if (config.debug) config.logger(message())
    }

    private fun publish() {
        visibleId = clientId ?: serverVisitorId
        visibleCount = queue.size
    }

    fun load() {
        if (stopped) return
        config.storage.getString(queueKey)?.let { raw ->
            try {
                (Json.decode(raw) as List<*>).forEach { queue.add(Queued.fromJson(it as Map<*, *>)) }
            } catch (e: Exception) {
                log { "dropped an unreadable stored queue: $e" }
                queue.clear()
            }
        }
        if (config.consent) {
            config.storage.getString(idKey)?.takeIf { Validate.clientId.matches(it) }?.let { clientId = it }
            grant(silent = true)
        } else {
            stripConsentedIds()
        }
        publish()
        if (queue.isNotEmpty()) startFlush()
    }

    fun screen(name: String, props: Map<String, Any?>?) {
        if (stopped) return
        val screen = Validate.screen(name) ?: return
        currentScreen = screen
        val utm = pendingUtm
        pendingUtm = null
        enqueue(Kind.PAGEVIEW, screen = screen, props = Validate.props(props), utm = utm)
    }

    fun track(name: String, props: Map<String, Any?>?) {
        if (stopped) return
        if (!Validate.eventName.matches(name)) return log { "ignored event name \"$name\"" }
        enqueue(Kind.EVENT, screen = currentScreen, name = name, props = Validate.props(props))
    }

    fun identify(rawUserId: String?, traits: Map<String, Any?>?, rawToken: String?) {
        if (stopped) return
        val token = rawToken?.trim()
        if (rawToken != null && (token.isNullOrEmpty() || token.length > 4096 || !Validate.identityToken.matches(token)))
            return log { "identify: ignoring a malformed identity token" }
        val userId = rawUserId?.let(Validate::userId)
        if (userId == null && token == null) return
        // With a token the server reads the profile from it only, so unsigned traits aren't sent.
        val cleanTraits = if (token == null) Validate.traits(traits) else null
        val key = "${clientId ?: serverVisitorId ?: ""}\n${userId ?: ""}\n${token ?: ""}\n${cleanTraits?.let(Json::encode) ?: ""}"
        if (key == lastIdentify) return
        lastIdentify = key
        enqueue(Kind.IDENTIFY, userId = userId, traits = cleanTraits, identityToken = token)
    }

    fun reset() {
        lastIdentify = null
        if (clientId == null) return // nothing is stored without consent
        clientId = config.randomId()
        serverVisitorId = null
        config.storage.setString(idKey, clientId)
        publish()
    }

    fun consent(granted: Boolean) {
        if (stopped) return
        if (granted) return grant(silent = false)
        clientId = null
        serverVisitorId = null
        pendingLink = false
        // Withdrawn consent: queued events no longer carry the stored ID.
        stripConsentedIds()
        config.storage.setString(idKey, null)
        publish()
    }

    fun openedFrom(url: String?) {
        Validate.utm(url)?.let { pendingUtm = it }
    }

    fun shutdown() {
        stopped = true
        retry?.cancel(false)
        retry = null
        finishWaiters()
    }

    private fun grant(silent: Boolean) {
        if (clientId != null && !silent) return
        if (clientId == null) clientId = config.randomId()
        config.storage.setString(idKey, clientId)
        // Relabel today's daily-ID events from this device to the consented ID: right away, or with the
        // next event when consent was already granted at launch.
        pendingLink = true
        publish()
        if (!silent) enqueue(Kind.LINK)
    }

    /** Without consent, nothing queued may carry the stored ID. */
    private fun stripConsentedIds() {
        val before = queue.size
        var changed = false
        for (i in queue.indices) {
            val item = queue[i]
            if (item.payload.clientId != null || item.payload.link != null) {
                queue[i] = Queued(item.payload.copy(clientId = null, link = null), item.attempts, item.queuedAt)
                changed = true
            }
        }
        queue.removeAll { it.payload.type == Kind.LINK }
        if (changed || queue.size != before) persist()
    }

    private fun enqueue(
        type: Kind,
        screen: String? = null,
        name: String? = null,
        props: Map<String, Any>? = null,
        utm: Utm? = null,
        userId: String? = null,
        traits: Map<String, Any>? = null,
        identityToken: String? = null,
    ) {
        var link: Boolean? = null
        if (clientId != null && pendingLink) {
            link = true
            pendingLink = false
        }
        val payload = CollectPayload(
            siteId = config.siteId, type = type, timestamp = timestampFormat.format(Date(config.now())), app = app,
            clientId = clientId, link = link, screen = screen, name = name, props = props, userId = userId,
            traits = traits, identityToken = identityToken, utm = utm,
            lang = if (type == Kind.PAGEVIEW) language else null,
            screenSize = if (type == Kind.PAGEVIEW) screenSize else null,
        )
        queue.add(Queued(payload, 0, config.now()))
        val limit = maxOf(1, config.maxQueueSize)
        while (queue.size > limit) queue.removeAt(0)
        persist()
        startFlush()
    }

    private fun persist() {
        publish()
        if (queue.isEmpty()) return config.storage.setString(queueKey, null)
        config.storage.setString(queueKey, Json.encode(queue.map { it.toJson() }))
    }

    /** Delivers in the background, one event per task so calls made meanwhile keep their place. */
    private fun startFlush() {
        if (draining || retry != null || stopped || queue.isEmpty()) return
        draining = true
        schedule { drainStep() }
    }

    private fun schedule(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            draining = false
        }
    }

    /**
     * The public flush: waits for a delivery in progress, skips a pending backoff but never a server's
     * Retry-After, then sends what is left. [done] runs when the queue is empty or delivery stopped.
     */
    fun forceFlush(done: () -> Unit) {
        if (retry != null) {
            if (config.now() < notBefore) return done()
            retry?.cancel(false)
            retry = null
        }
        if (stopped || (queue.isEmpty() && !draining)) return done()
        flushWaiters.add(done)
        startFlush()
    }

    private fun finishWaiters() {
        val waiting = ArrayList(flushWaiters)
        flushWaiters.clear()
        waiting.forEach { it() }
    }

    /** Sends the head of the queue, then schedules the next one, or a retry. */
    private fun drainStep() {
        if (stopped || queue.isEmpty()) {
            draining = false
            return finishWaiters()
        }
        val item = queue[0]
        if (config.now() - item.queuedAt > config.maxEventAgeMillis) {
            queue.removeAt(0)
            persist()
            return schedule { drainStep() }
        }
        when (val delivery = deliver(item)) {
            null -> {
                if (queue.firstOrNull() === item) queue.removeAt(0)
                persist()
                schedule { drainStep() }
            }
            else -> {
                if (queue.firstOrNull() === item) item.attempts += 1
                persist()
                draining = false
                retry = try {
                    executor.schedule({ retry = null; startFlush() }, (delivery * 1000).toLong(), TimeUnit.MILLISECONDS)
                } catch (_: RejectedExecutionException) {
                    null
                }
                finishWaiters()
            }
        }
    }

    /** null when done (delivered or dropped); otherwise the seconds to wait before retrying. */
    private fun deliver(item: Queued): Double? {
        val body = Json.encode(item.payload.toJson()).toByteArray(Charsets.UTF_8)
        var status: Int? = null
        var retryAfter: Double? = null
        try {
            val response = config.transport.post(body, endpoint)
            status = response.status
            if (response.status in 200..299) {
                try {
                    ((Json.decode(String(response.body, Charsets.UTF_8)) as? Map<*, *>)?.get("visitorId") as? String)?.let {
                        serverVisitorId = it
                        publish()
                    }
                } catch (_: Exception) {
                }
                return null
            }
            retryAfter = response.retryAfter
            retryAfter?.let { notBefore = config.now() + (it * 1000).toLong() }
        } catch (e: IOException) {
            log { "offline or unreachable, will retry: $e" }
        } catch (e: RuntimeException) {
            log { "request failed, will retry: $e" }
        }
        if (!MetricRavenClient.shouldRetry(status)) {
            log { "dropped a ${item.payload.type.wire} (HTTP ${status ?: 0})" }
            return null
        }
        return MetricRavenClient.backoff(item.attempts + 1, retryAfter)
    }
}
