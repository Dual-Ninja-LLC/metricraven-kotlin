package com.metricraven.android

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.metricraven.MetricRaven
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a device or emulator, against a local backend (never production): the real `MetricRaven.configure`
 * with device info, SharedPreferences and HttpURLConnection. Runs only with instrumentation arguments:
 *
 *   ./gradlew :metricraven-android:connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.endpoint=http://10.0.2.2:5221/api/v1/collect \
 *     -Pandroid.testInstrumentationRunnerArguments.siteId=ana_pub_… \
 *     -Pandroid.testInstrumentationRunnerArguments.identityToken=<signIdentity(…) from your server>
 *
 * The site must list this test app's ID, com.metricraven.android.test, under Mobile apps.
 */
@RunWith(AndroidJUnit4::class)
class DeviceE2ETest {
    private fun log(message: String) {
        Log.i("MetricRavenE2E", message)
        println("MetricRavenE2E $message")
    }

    private fun flush() = assertTrue("flush timed out", MetricRaven.shared!!.flushBlocking(30_000))

    @Test
    fun aDeviceSessionLandsInTheLocalBackend() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("endpoint")
        val siteId = args.getString("siteId")
        val token = args.getString("identityToken")
        assumeTrue("pass endpoint, siteId and identityToken instrumentation arguments", endpoint != null && siteId != null && token != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val prefs = context.getSharedPreferences("metricraven", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        log("device language=${DeviceInfo.language(context)} screen=${DeviceInfo.screenSize(context)} os=${DeviceInfo.osVersion} type=${DeviceInfo.deviceType(context)}")
        assertTrue(MetricRaven.configure(context, siteId!!, endpoint = endpoint, debug = true))
        assertEquals(false, MetricRaven.configure(context, siteId, appId = "not an app id"))
        assertTrue(MetricRaven.configure(context, siteId, endpoint = endpoint, debug = true))

        MetricRaven.openedFrom(Uri.parse("e2eapp://open?utm_source=android-e2e&utm_campaign=emulator"))
        MetricRaven.screen("DeviceHome", mapOf("variant" to "emulator"))
        MetricRaven.track("device_signup", mapOf("plan" to "pro", "seats" to 2))
        MetricRaven.identify("android_user_1", mapOf("name" to "Android Ana", "email" to "android-ana@example.com"))
        flush()
        val dailyId = MetricRaven.shared!!.visitorId
        assertEquals(24, dailyId?.length)

        MetricRaven.setConsent(true)
        MetricRaven.screen("DeviceSettings")
        flush()
        val consentedId = MetricRaven.shared!!.visitorId!!
        assertEquals(consentedId, prefs.getString("metricraven:client-id:$siteId", null))

        MetricRaven.reset()
        MetricRaven.screen("DeviceAfterReset")
        MetricRaven.identifyWithToken(token!!)
        flush()
        val afterReset = MetricRaven.shared!!.visitorId!!
        assertNotEquals(consentedId, afterReset)

        // Offline: queued against an unreachable port, persisted in SharedPreferences, replayed by the
        // next configure (a relaunch) with the stored consented ID.
        assertTrue(MetricRaven.configure(context, siteId, consent = true, endpoint = "http://10.0.2.2:9/api/v1/collect", debug = true))
        MetricRaven.screen("DeviceOffline")
        flush()
        assertEquals(1, MetricRaven.shared!!.pending)
        assertNotNull(prefs.getString("metricraven:queue:$siteId", null))
        Thread.sleep(2_000)
        assertTrue(MetricRaven.configure(context, siteId, consent = true, endpoint = endpoint, debug = true))
        flush()
        assertEquals(0, MetricRaven.shared!!.pending)
        assertEquals(afterReset, MetricRaven.shared!!.visitorId)
        assertEquals(null, prefs.getString("metricraven:queue:$siteId", null))

        log("ids daily=$dailyId consented=$consentedId afterReset=$afterReset")
    }
}
