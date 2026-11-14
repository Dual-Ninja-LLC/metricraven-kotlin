package com.metricraven

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The payload contract. contract-payloads.json (in packages/sdk-swift) is round-tripped by the Swift
 * SDK's ContractTests and parsed by the backend's collectSchema (swift-sdk-contract.test.ts), and
 * android-payloads.json is exactly what this client sends for a scripted session; the backend parses
 * it with collectSchema too. So the Kotlin SDK, the Swift SDK and the server can't drift apart.
 */
class ContractTest {
    private fun fixtures(property: String): List<Map<*, *>> {
        val path = System.getProperty(property) ?: error("$property is not set (run through Gradle)")
        val file = File(path)
        assertTrue("missing $path", file.exists())
        return (Json.decode(file.readText()) as List<*>).map { it as Map<*, *> }
    }

    /** JSON trees compared with every number as a Double (3 and 3.0 are the same value). */
    private fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) -> k to normalize(v) }
        is List<*> -> value.map(::normalize)
        is Number -> value.toDouble()
        else -> value
    }

    @Test
    fun theSharedPayloadsRoundTripThroughTheKotlinTypes() {
        val payloads = fixtures("metricraven.contractPayloads")
        assertTrue(payloads.size >= 5)
        for (raw in payloads) {
            val again = Json.decode(Json.encode(CollectPayload.fromJson(raw).toJson()))
            assertEquals(normalize(raw), normalize(again))
        }
    }

    @Test
    fun screenViewsCarryLanguageAndScreenSize() {
        val pageview = fixtures("metricraven.contractPayloads").map(CollectPayload::fromJson).first { it.type == Kind.PAGEVIEW }
        assertEquals("en-US", pageview.lang)
        assertEquals("390x844", pageview.screenSize)
        assertEquals("en-US", Validate.language("en_US"))
        assertNull(Validate.language("not a tag!"))
        assertNull(Validate.language("und"))
        assertNull(Validate.screenSize("big"))
        assertEquals("390x844", Validate.screenSize(390.4, 843.6))
        assertEquals("412x915", Validate.screenSize(411.5, 914.5))
    }

    @Test
    fun theClientSendsExactlyTheAndroidFixtures() {
        val transport = MockTransport()
        val client = MetricRavenClient(
            MetricRavenConfiguration(
                siteId = "ana_pub_test",
                appId = "com.example.app",
                appVersion = "1.2.0",
                appBuild = "42",
                platform = AppPlatform.ANDROID,
                osVersion = "16",
                deviceType = DeviceType.MOBILE,
                language = "en-US",
                screenSize = "411x914",
                transport = transport,
                now = { 1_800_000_000_000L },
                randomId = { "0123456789abcdef0123456789abcdef" },
            ),
        )
        client.openedFrom("myapp://open?utm_source=tiktok&utm_campaign=launch")
        client.screen("Settings", mapOf("tab" to "profile"))
        client.identify("user_42", mapOf("name" to "Ana", "email" to "ana@example.com", "country" to "ES"))
        client.identifyWithToken("aaa.bbb.ccc")
        client.setConsent(true)
        client.screen("Paywall")
        client.track("purchase", mapOf("plan" to "pro", "seats" to 3, "trial" to false))
        assertTrue(client.flushBlocking(5_000))
        assertEquals(normalize(fixtures("metricraven.androidPayloads")), normalize(transport.sent))
    }
}
