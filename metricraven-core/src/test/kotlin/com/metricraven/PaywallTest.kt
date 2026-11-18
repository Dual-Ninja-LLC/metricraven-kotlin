package com.metricraven

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PaywallTest {
    private val calls = ArrayList<Pair<String, Map<String, Any?>?>>()
    private val sink: (String, Map<String, Any?>?) -> Unit = { name, props -> calls.add(name to props) }

    @Test fun sendsTheFiveEventsWithProviderPaywallAndOffering() {
        val paywall = MetricRavenPaywall(provider = "revenuecat", paywallId = "pw1", offeringId = "premium", sink = sink)
        paywall.impression(); paywall.close(); paywall.cancel(); paywall.exitOffer()
        paywall.interaction(mapOf("component_type" to "package"))
        assertEquals(MetricRavenPaywall.Event.values().map { it.eventName }, calls.map { it.first })
        for ((_, props) in calls.take(4)) assertEquals(mapOf("provider" to "revenuecat", "paywall_id" to "pw1", "offering_id" to "premium"), props)
    }

    @Test fun noPropsWhenNothingIsKnown() {
        MetricRavenPaywall(sink = sink).impression()
        assertEquals(listOf("paywall_impression" to null), calls)
    }

    @Test fun interactionMapsRawPropertiesPreferringTheirPaywallAndOffering() {
        val raw = mapOf(
            "paywall_id" to "pw903", "paywall_name" to "Subscription Paywall", "offering_id" to "premium",
            "component_type" to "package", "component_name" to "AnnualPackage", "component_value" to "yearly",
            "origin_product_id" to "starter", "destination_product_id" to "yearly",
            "session_id" to "dropped", "dark_mode" to false, "nested" to mapOf("x" to 1),
        )
        val props = MetricRavenPaywall(provider = "revenuecat", offeringId = "fallback", props = mapOf("screen" to "onboarding")).interactionProps(raw)
        assertEquals(mapOf(
            "provider" to "revenuecat", "paywall_id" to "pw903", "paywall_name" to "Subscription Paywall", "offering_id" to "premium",
            "component_type" to "package", "component_name" to "AnnualPackage", "component_value" to "yearly",
            "origin_product_id" to "starter", "destination_product_id" to "yearly", "screen" to "onboarding",
        ), props)
    }

    @Test fun paywallFieldsWinOverExtraProps() {
        val raw = mapOf("component_type" to "tab", "component_name" to "a", "component_value" to "b", "origin_product_id" to "e", "destination_product_id" to "f")
        val extra = (0 until 12).associate { "k$it" to it }
        val props = MetricRavenPaywall("revenuecat", "p", "n", "o", "x", extra).interactionProps(raw)
        assertEquals(10, props.size)
        assertFalse(props.containsKey("k0"))
    }

    @Test fun productSelected() {
        MetricRavenPaywall(provider = "adapty", placement = "onboarding", sink = sink).productSelected("annual")
        assertEquals(listOf("paywall_component_interacted" to mapOf(
            "provider" to "adapty", "placement" to "onboarding", "component_type" to "package", "destination_product_id" to "annual",
        )), calls)
    }

    @Test fun superwallMapsOpenCloseAndAbandonInEitherNameStyle() {
        val paywall = MetricRavenPaywall(placement = "campaign_trigger", sink = sink)
        paywall.superwall("paywall_open", mapOf("paywall_identifier" to "annual-v2", "paywall_name" to "Annual", "other" to 1))
        paywall.superwall("paywallClose", mapOf("paywall_id" to "42"))
        paywall.superwall("transactionAbandon", mapOf("abandoned_product_id" to "annual", "presented_by_event_name" to ""))
        paywall.superwall("paywallOpen", mapOf("paywall_identifier" to "x", "presented_by_event_name" to "settings_upgrade"))
        paywall.superwall("paywall_decline")
        paywall.superwall("app_open")
        assertEquals(listOf("paywall_impression", "paywall_close", "paywall_cancel", "paywall_impression"), calls.map { it.first })
        assertEquals(mapOf("provider" to "superwall", "placement" to "campaign_trigger", "destination_product_id" to "annual"), calls[2].second)
        assertEquals(mapOf("provider" to "superwall", "paywall_id" to "x", "placement" to "settings_upgrade"), calls[3].second)
        assertEquals(mapOf("provider" to "superwall", "paywall_id" to "annual-v2", "paywall_name" to "Annual", "placement" to "campaign_trigger"), calls[0].second)
        assertEquals(mapOf("provider" to "superwall", "paywall_id" to "42", "placement" to "campaign_trigger"), calls[1].second)
    }

    @Test fun sendsThroughAClient() {
        val transport = MockTransport()
        val client = MetricRavenClient(MetricRavenConfiguration(
            siteId = "ana_pub_test", appId = "com.example.app", platform = AppPlatform.ANDROID,
            storage = MemoryStorage(), transport = transport,
        ))
        MetricRavenPaywall(client, provider = "revenuecat", offeringId = "premium").impression()
        client.flushBlocking()
        assertEquals("paywall_impression", transport.sent.single()["name"])
        assertEquals(mapOf("provider" to "revenuecat", "offering_id" to "premium"), transport.sent.single()["props"])
    }
}
