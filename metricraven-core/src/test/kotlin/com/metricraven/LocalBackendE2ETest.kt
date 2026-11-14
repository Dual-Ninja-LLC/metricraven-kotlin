package com.metricraven

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * End to end against a running backend (never production): screen views, events, identify, signed
 * identify, consent, reset and an offline-queue replay, through the real HttpURLConnection transport.
 * Skipped unless METRICRAVEN_E2E_ENDPOINT, _SITE_ID, _SECRET_KEY and _APP_ID are set; see the README.
 * Prints the visitor IDs it saw so the stored events can be checked in the database.
 */
class LocalBackendE2ETest {
    private val endpoint = System.getenv("METRICRAVEN_E2E_ENDPOINT")
    private val siteId = System.getenv("METRICRAVEN_E2E_SITE_ID")
    private val secretKey = System.getenv("METRICRAVEN_E2E_SECRET_KEY")
    private val appId = System.getenv("METRICRAVEN_E2E_APP_ID")
    private val unreachable = System.getenv("METRICRAVEN_E2E_OFFLINE_ENDPOINT") ?: "http://127.0.0.1:9/api/v1/collect"

    /** Every response, so a rejection or an ignored event fails the test. */
    private class Recording(private val inner: MetricRavenTransport) : MetricRavenTransport {
        val answers = ArrayList<String>()
        override fun post(body: ByteArray, url: String): TransportResponse {
            val response = inner.post(body, url)
            val type = (Json.decode(String(body, Charsets.UTF_8)) as Map<*, *>)["type"]
            synchronized(answers) { answers.add("$type ${response.status} ${String(response.body, Charsets.UTF_8)}") }
            return response
        }
    }

    private fun config(transport: MetricRavenTransport, storage: MetricRavenStorage, url: String = endpoint) = MetricRavenConfiguration(
        siteId = siteId,
        appId = appId,
        appVersion = "0.1.0-e2e",
        appBuild = "7",
        platform = AppPlatform.ANDROID,
        osVersion = "16",
        deviceType = DeviceType.MOBILE,
        language = "es_ES",
        screenSize = "411x914",
        endpoint = url,
        storage = storage,
        transport = transport,
        debug = true,
    )

    /** HS256 over the SHA-256 hex of the secret key, exactly like signIdentity in @metricraven/node. */
    private fun signIdentity(userId: String, email: String, name: String): String {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val now = System.currentTimeMillis() / 1000
        val header = b64.encodeToString(Json.encode(linkedMapOf("alg" to "HS256", "typ" to "JWT")).toByteArray())
        val claims = b64.encodeToString(
            Json.encode(mapOf("site_id" to siteId, "user_id" to userId, "email" to email, "name" to name, "iat" to now, "exp" to now + 3600)).toByteArray(),
        )
        val keyHex = MessageDigest.getInstance("SHA-256").digest(secretKey.toByteArray()).joinToString("") { String.format("%02x", it) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keyHex.toByteArray(), "HmacSHA256")) }
        val signature = b64.encodeToString(mac.doFinal("$header.$claims".toByteArray()))
        return "$header.$claims.$signature"
    }

    private fun MetricRavenClient.flushNow() = assertTrue("flush timed out", flushBlocking(30_000))

    @Test
    fun aSessionLandsInTheLocalBackend() {
        assumeTrue("set METRICRAVEN_E2E_* to run", endpoint != null && siteId != null && secretKey != null && appId != null)
        val transport = Recording(HttpUrlConnectionTransport())
        val client = MetricRavenClient(config(transport, MemoryStorage()))

        // Without consent: the server's daily visitor ID.
        client.openedFrom("e2eapp://open?utm_source=kotlin-e2e&utm_campaign=jvm")
        client.screen("Home", mapOf("variant" to "a"))
        client.track("signup", mapOf("plan" to "pro", "seats" to 3, "trial" to false))
        client.identify("kotlin_user_1", mapOf("name" to "Kotlin Ana", "email" to "ana-kotlin@example.com", "country" to "ES"))
        client.flushNow()
        val dailyId = client.visitorId!!
        assertEquals(24, dailyId.length)

        // Consent: a stored random ID; the link request relabels today's daily-ID events.
        client.setConsent(true)
        client.screen("Settings")
        client.flushNow()
        val consentedId = client.visitorId!!
        assertTrue(Regex("^[0-9a-f]{32}$").matches(consentedId))

        // Sign-out: a new consented ID.
        client.reset()
        client.screen("AfterReset")
        client.flushNow()
        val afterReset = client.visitorId!!
        assertNotEquals(consentedId, afterReset)

        // Signed identify for the next person, verified by the server.
        client.identifyWithToken(signIdentity("kotlin_user_2", "verified-kotlin@example.com", "Verified Kotlin"))
        client.flushNow()

        // Consent withdrawn: back to the daily ID.
        client.setConsent(false)
        client.screen("NoConsent")
        client.flushNow()
        assertEquals(dailyId, client.visitorId)
        client.shutdown()

        // Offline: queued against an unreachable host, kept in storage, replayed by the next launch.
        val storage = MemoryStorage()
        val offline = MetricRavenClient(config(transport, storage, unreachable))
        offline.screen("OfflineA")
        offline.track("offline_event", mapOf("queued" to true))
        offline.flushNow()
        assertEquals(2, offline.pending)
        offline.shutdown()
        Thread.sleep(2_000) // so the stored time (when queued) visibly differs from when it arrived
        val relaunched = MetricRavenClient(config(transport, storage))
        relaunched.flushNow()
        assertEquals(0, relaunched.pending)

        println("E2E answers:\n" + transport.answers.joinToString("\n"))
        println("E2E ids: daily=$dailyId consented=$consentedId afterReset=$afterReset")
        // 8 online + 2 replayed (the offline attempts never reached a server).
        assertEquals(10, transport.answers.size)
        for (answer in transport.answers) {
            assertTrue(answer, answer.split(" ")[1] == "200" && "\"ok\":true" in answer && "ignored" !in answer)
        }
        assertTrue(transport.answers.any { it.startsWith("identify") && "\"verified\":true" in it })
    }
}
