package com.metricraven

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Answers each request with the next scripted status (200 once the script runs out; null = offline) and records bodies. */
class MockTransport(statuses: List<Int?> = emptyList(), private val retryAfter: Double? = null) : MetricRavenTransport {
    private val statuses = ArrayList(statuses)
    private val bodies = ArrayList<Map<String, Any?>>()
    @Volatile var visitorId: String? = null
    var requests = 0
        private set

    @Synchronized
    override fun post(body: ByteArray, url: String): TransportResponse {
        requests++
        val status = if (statuses.isEmpty()) 200 else statuses.removeAt(0)
        if (status == null) throw IOException("offline")
        @Suppress("UNCHECKED_CAST")
        if (status in 200..299) bodies.add(Json.decode(String(body, Charsets.UTF_8)) as Map<String, Any?>)
        val answer = visitorId?.let { "{\"visitorId\":\"$it\"}" } ?: "{}"
        return TransportResponse(status, if (status in 200..299) null else retryAfter, answer.toByteArray())
    }

    val sent: List<Map<String, Any?>> @Synchronized get() = ArrayList(bodies)
}

class ClientTest {
    private val fixedId = "0123456789abcdef0123456789abcdef"

    private fun client(
        transport: MetricRavenTransport,
        consent: Boolean = false,
        storage: MetricRavenStorage = MemoryStorage(),
        platform: AppPlatform = AppPlatform.ANDROID,
        language: String? = null,
        screenSize: String? = null,
        now: () -> Long = { 1_800_000_000_000L },
    ) = MetricRavenClient(
        MetricRavenConfiguration(
            siteId = "ana_pub_test",
            appId = "com.example.app",
            appVersion = "1.2.0",
            appBuild = "42",
            platform = platform,
            osVersion = "16",
            deviceType = DeviceType.MOBILE,
            language = language,
            screenSize = screenSize,
            consent = consent,
            storage = storage,
            transport = transport,
            now = now,
            randomId = { fixedId },
        ),
    )

    private fun MetricRavenClient.flushNow() = assertTrue("flush timed out", flushBlocking(5_000))

    @Test
    fun screenViewPayloadMatchesTheCollectSchema() {
        val transport = MockTransport()
        val c = client(transport)
        c.screen("Settings", mapOf("tab" to "profile"))
        c.flushNow()
        val body = transport.sent.first()
        assertEquals("ana_pub_test", body["siteId"])
        assertEquals("pageview", body["type"])
        assertEquals("Settings", body["screen"])
        assertEquals("2027-01-15T08:00:00.000Z", body["timestamp"])
        assertEquals(mapOf("tab" to "profile"), body["props"])
        assertNull("nothing is stored without consent", body["clientId"])
        assertEquals(
            mapOf("id" to "com.example.app", "version" to "1.2.0", "build" to "42", "platform" to "android", "osVersion" to "16", "deviceType" to "mobile"),
            body["app"],
        )
    }

    @Test
    fun languageAndScreenSizeGoWithScreenViewsOnly() {
        val transport = MockTransport()
        val c = client(transport, language = "pt_BR", screenSize = "411x914")
        c.screen("Home")
        c.track("purchase")
        c.identify("user_42")
        c.flushNow()
        assertEquals(3, transport.sent.size)
        assertEquals("pt-BR", transport.sent[0]["lang"])
        assertEquals("411x914", transport.sent[0]["screenSize"])
        for (body in transport.sent.drop(1)) {
            assertFalse(body.containsKey("lang"))
            assertFalse(body.containsKey("screenSize"))
        }
    }

    @Test
    fun eventsCarryTheCurrentScreenAndBadNamesAreDropped() {
        val transport = MockTransport()
        val c = client(transport)
        c.screen("Paywall")
        c.track("1bad name")
        c.track("purchase", mapOf("plan" to "pro", "seats" to 3, "trial" to false, "price" to 9.99, "skip" to listOf(1)))
        c.flushNow()
        assertEquals(2, transport.sent.size)
        val event = transport.sent[1]
        assertEquals("purchase", event["name"])
        assertEquals("Paywall", event["screen"])
        assertEquals(mapOf("plan" to "pro", "seats" to 3L, "trial" to false, "price" to 9.99), event["props"])
    }

    @Test
    fun identifyCleansTraitsAndIsNotRepeated() {
        val transport = MockTransport()
        val c = client(transport)
        val traits = mapOf("name" to "Ana", "email" to "not-an-email", "Plan" to "pro", "country" to "ES")
        c.identify("user_42", traits)
        c.identify("user_42", traits)
        c.flushNow()
        assertEquals(1, transport.sent.size)
        val body = transport.sent[0]
        assertEquals("identify", body["type"])
        assertEquals("user_42", body["userId"])
        assertEquals(mapOf("name" to "Ana", "country" to "ES"), body["traits"])
    }

    @Test
    fun signedIdentifySendsOnlyTheToken() {
        val transport = MockTransport()
        val c = client(transport)
        c.identifyWithToken("not a token")
        c.identifyWithToken("aaa.bbb.ccc")
        c.flushNow()
        assertEquals(1, transport.sent.size)
        assertEquals("aaa.bbb.ccc", transport.sent[0]["identityToken"])
        assertNull(transport.sent[0]["traits"])
    }

    @Test
    fun consentKeepsAnIdAndLinksTheFirstEvent() {
        val transport = MockTransport()
        val storage = MemoryStorage()
        val c = client(transport, consent = true, storage = storage)
        c.screen("Home")
        c.screen("Feed")
        c.flushNow()
        assertEquals(fixedId, transport.sent[0]["clientId"])
        assertEquals(true, transport.sent[0]["link"])
        assertNull(transport.sent[1]["link"])
        assertEquals(fixedId, storage.getString("metricraven:client-id:ana_pub_test"))
        assertEquals(fixedId, c.visitorId)

        c.setConsent(false)
        c.screen("Settings")
        c.flushNow()
        assertNull(transport.sent[2]["clientId"])
        assertNull(storage.getString("metricraven:client-id:ana_pub_test"))
    }

    @Test
    fun grantingConsentLaterSendsALinkRequest() {
        val transport = MockTransport()
        val c = client(transport)
        c.screen("Home")
        c.setConsent(true)
        c.screen("Feed")
        c.flushNow()
        assertEquals(listOf("pageview", "link", "pageview"), transport.sent.map { it["type"] })
        assertNull(transport.sent[0]["clientId"])
        assertEquals(fixedId, transport.sent[1]["clientId"])
        assertEquals(true, transport.sent[1]["link"])
        assertEquals(fixedId, transport.sent[2]["clientId"])
        assertNull(transport.sent[2]["link"])
    }

    @Test
    fun resetStartsANewConsentedIdAndAllowsTheSameIdentifyAgain() {
        val transport = MockTransport()
        val ids = ArrayDeque(listOf("a".repeat(32), "b".repeat(32)))
        val c = MetricRavenClient(
            MetricRavenConfiguration(
                siteId = "ana_pub_test", appId = "com.example.app", consent = true,
                transport = transport, randomId = { ids.removeFirst() },
            ),
        )
        c.identify("user_42")
        c.reset()
        c.identify("user_42")
        c.flushNow()
        assertEquals(listOf("a".repeat(32), "b".repeat(32)), transport.sent.map { it["clientId"] })
        assertEquals("b".repeat(32), c.visitorId)
    }

    @Test
    fun retriesServerErrorsInOrderAndDropsRejections() {
        val transport = MockTransport(listOf(503, null, 400))
        val c = client(transport)
        c.screen("A") // 503: kept
        c.flushNow() // offline: kept
        c.flushNow() // 400: A dropped
        c.screen("B")
        c.flushNow()
        assertEquals(listOf("B"), transport.sent.map { it["screen"] })
        assertEquals(0, c.pending)
    }

    @Test
    fun aServersRetryAfterIsNeverSkipped() {
        val transport = MockTransport(listOf(429), retryAfter = 60.0)
        val c = client(transport, now = { System.currentTimeMillis() })
        c.screen("A")
        c.flushNow()
        c.flushNow()
        assertEquals(1, transport.requests)
        assertEquals(1, c.pending)
    }

    @Test
    fun offlineEventsAreDeliveredInOrderLater() {
        val transport = MockTransport(listOf(null))
        val c = client(transport)
        c.screen("A")
        c.screen("B")
        c.flushNow() // A fails offline, backs off
        c.flushNow()
        assertEquals(listOf("A", "B"), transport.sent.map { it["screen"] })
    }

    @Test
    fun theQueueSurvivesARelaunch() {
        val storage = MemoryStorage()
        val offline = MockTransport(listOf(null, null, null))
        val first = client(offline, storage = storage)
        first.screen("Saved")
        first.flushNow()
        first.shutdown()
        assertNotNull(storage.getString("metricraven:queue:ana_pub_test"))

        val online = MockTransport()
        val second = client(online, storage = storage)
        second.flushNow()
        assertEquals(listOf("Saved"), online.sent.map { it["screen"] })
        assertNull(storage.getString("metricraven:queue:ana_pub_test"))
    }

    @Test
    fun theQueueKeepsTheNewestEventsAndDropsStaleOnes() {
        val storage = MemoryStorage()
        var clock = 1_800_000_000_000L
        val offline = MockTransport(listOf(null, null, null, null))
        val c = MetricRavenClient(
            MetricRavenConfiguration(
                siteId = "ana_pub_test", appId = "com.example.app", storage = storage, transport = offline,
                maxQueueSize = 2, now = { clock },
            ),
        )
        c.screen("A")
        c.screen("B")
        c.screen("C")
        c.flushNow()
        assertEquals(2, c.pending)
        c.shutdown()
        clock += 8 * 86_400_000L
        val online = MockTransport()
        val later = MetricRavenClient(
            MetricRavenConfiguration(siteId = "ana_pub_test", appId = "com.example.app", storage = storage, transport = online, now = { clock }),
        )
        later.flushNow()
        assertEquals(emptyList<Any>(), online.sent)
        assertEquals(0, later.pending)
    }

    @Test
    fun theServerVisitorIdIsReported() {
        val transport = MockTransport()
        transport.visitorId = "ffffffffffffffffffffffff"
        val c = client(transport)
        c.screen("Home")
        c.flushNow()
        assertEquals("ffffffffffffffffffffffff", c.visitorId)
    }

    @Test
    fun deepLinkCampaignGoesOnTheNextScreen() {
        val transport = MockTransport()
        val c = client(transport)
        c.openedFrom("myapp://open?utm_source=tiktok&utm_campaign=launch%20day&other=1")
        c.screen("Home")
        c.screen("Feed")
        c.flushNow()
        assertEquals(mapOf("source" to "tiktok", "campaign" to "launch day"), transport.sent[0]["utm"])
        assertNull(transport.sent[1]["utm"])
    }

    @Test
    fun disabledClientsSendNothing() {
        val transport = MockTransport()
        val c = MetricRavenClient(MetricRavenConfiguration(siteId = "ana_pub_test", appId = "com.example.app", transport = transport, disabled = true))
        c.screen("Home")
        c.track("signup")
        c.flushNow()
        assertEquals(0, transport.requests)
    }

    @Test
    fun rejectsAnAppIdThatIsNotAnApplicationId() {
        try {
            MetricRavenClient(MetricRavenConfiguration(siteId = "s", appId = "not an app id"))
            fail("expected InvalidAppId")
        } catch (e: MetricRavenException.InvalidAppId) {
            assertEquals("not an app id", e.appId)
        }
        try {
            MetricRavenClient(MetricRavenConfiguration(siteId = "", appId = "com.example.app"))
            fail("expected MissingSiteId")
        } catch (_: MetricRavenException.MissingSiteId) {
        }
    }

    @Test
    fun backoffDoublesUpToFiveMinutesAndHonoursRetryAfter() {
        assertEquals(1.0, MetricRavenClient.backoff(1, null), 0.0)
        assertEquals(8.0, MetricRavenClient.backoff(4, null), 0.0)
        assertEquals(300.0, MetricRavenClient.backoff(30, null), 0.0)
        assertEquals(60.0, MetricRavenClient.backoff(1, 60.0), 0.0)
        assertTrue(MetricRavenClient.shouldRetry(null))
        assertTrue(MetricRavenClient.shouldRetry(429))
        assertTrue(MetricRavenClient.shouldRetry(502))
        assertFalse(MetricRavenClient.shouldRetry(403))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(MetricRavenClient.randomClientId()))
    }

    @Test
    fun jsonRoundTripsEscapesAndNumbers() {
        val text = Json.encode(mapOf("b" to "line\n\"q\" é \u0001", "a" to listOf(1, 2.5, true, null), "n" to null))
        assertEquals("{\"a\":[1,2.5,true,null],\"b\":\"line\\n\\\"q\\\" é \\u0001\"}", text)
        assertEquals(mapOf("a" to listOf(1L, 2.5, true, null), "b" to "line\n\"q\" é \u0001"), Json.decode(text))
        assertEquals("😀", Json.decode("\"\\ud83d\\ude00\""))
    }
}
