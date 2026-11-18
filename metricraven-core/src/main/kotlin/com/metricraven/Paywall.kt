package com.metricraven

/**
 * Paywall events from the app (RevenueCat, Superwall, Adapty or your own paywall): impression, close,
 * cancel, exit offer and component interactions, as MetricRaven goals. No dependency on any paywall
 * SDK: call these from its callbacks. Names and properties match RevenueCat's paywall webhook events
 * forwarded from a backend (metricraven.com/docs/paywall-events), so either route gives the same goals.
 * Use one route, not both: events sent both ways are counted twice.
 *
 * ```kotlin
 * val paywall = MetricRaven.paywall(provider = "revenuecat", offeringId = offering.identifier)
 * paywall.impression()
 * PaywallListener: onPurchaseCancelled { paywall.cancel() }, onInteraction { paywall.interaction(it.rawProperties) }
 * ```
 *
 * @param provider which paywall tool shows it ("revenuecat", "superwall", "adapty"); sent as `provider`.
 * @param offeringId RevenueCat's offering shown (offering.identifier). Its close and cancel callbacks don't say.
 * @param placement where the paywall was shown (Superwall or Adapty placement, or your own name).
 * @param props extra properties on every event; at most 10 properties per event in total.
 */
public class MetricRavenPaywall @JvmOverloads constructor(
    public val provider: String? = null,
    public val paywallId: String? = null,
    public val paywallName: String? = null,
    public val offeringId: String? = null,
    public val placement: String? = null,
    public val props: Map<String, Any?>? = null,
    private val sink: (String, Map<String, Any?>?) -> Unit = { _, _ -> },
) {
    /** Sends through a client of your own (MetricRaven.paywall uses the shared one). */
    @JvmOverloads
    public constructor(
        client: MetricRavenClient,
        provider: String? = null,
        paywallId: String? = null,
        paywallName: String? = null,
        offeringId: String? = null,
        placement: String? = null,
        props: Map<String, Any?>? = null,
    ) : this(provider, paywallId, paywallName, offeringId, placement, props, { name, p -> client.track(name, p) })

    public enum class Event(public val eventName: String) {
        IMPRESSION("paywall_impression"),
        CLOSE("paywall_close"),
        CANCEL("paywall_cancel"),
        EXIT_OFFER("paywall_exit_offer"),
        INTERACTION("paywall_component_interacted"),
    }

    /** Call when the paywall is shown (RevenueCat has no callback for it; Adapty: onPaywallShown). */
    public fun impression(): Unit = send(Event.IMPRESSION, finish(base()))

    /** RevenueCat dismissRequest / PaywallActivityLauncher result; Adapty onPaywallClosed. */
    public fun close(): Unit = send(Event.CLOSE, finish(base()))

    /** RevenueCat PaywallListener.onPurchaseCancelled; Adapty a purchase finished as cancelled. */
    public fun cancel(): Unit = send(Event.CANCEL, finish(base()))

    /** An exit offer was shown. */
    public fun exitOffer(): Unit = send(Event.EXIT_OFFER, finish(base()))

    /** RevenueCat PaywallListener.onInteraction: pass `event.rawProperties`. */
    public fun interaction(rawProperties: Map<String, *>): Unit = send(Event.INTERACTION, interactionProps(rawProperties))

    /** A product was picked on the paywall (Adapty onProductSelected, or your own paywall). */
    public fun productSelected(productId: String) {
        val out = base()
        out["component_type"] = "package"
        text(productId)?.let { out["destination_product_id"] = it }
        send(Event.INTERACTION, finish(out))
    }

    /**
     * Superwall's handleSuperwallEvent: pass `eventInfo.event.rawName` and `eventInfo.params`.
     * paywall_open, paywall_close and transaction_abandon are sent; other events are ignored.
     */
    @JvmOverloads
    public fun superwall(name: String, params: Map<String, *> = emptyMap<String, Any?>()) {
        val event = SUPERWALL[snake(name)] ?: return
        // Superwall's PaywallInfo params; abandoned_product_id comes with transaction_abandon.
        val me = MetricRavenPaywall(
            provider ?: "superwall",
            text(params["paywall_identifier"]) ?: text(params["paywall_id"]) ?: paywallId,
            text(params["paywall_name"]) ?: paywallName,
            offeringId,
            text(params["presented_by_event_name"]) ?: placement,
            props, sink,
        )
        val out = me.base()
        if (event == Event.CANCEL) text(params["abandoned_product_id"])?.let { out["destination_product_id"] = it }
        me.send(event, me.finish(out))
    }

    /** The properties of one RevenueCat component interaction: paywall, offering and the component fields. */
    public fun interactionProps(raw: Map<String, *>): Map<String, Any?> {
        val me = MetricRavenPaywall(
            provider,
            text(raw["paywall_id"]) ?: paywallId,
            text(raw["paywall_name"]) ?: paywallName,
            text(raw["offering_id"]) ?: offeringId,
            placement, props, sink,
        )
        val out = me.base()
        for (key in INTERACTION_KEYS) {
            when (val value = raw[key]) {
                is String, is Boolean -> out[key] = value
                is Number -> if (value.toDouble().isFinite()) out[key] = value
                is Enum<*> -> out[key] = value.name
            }
        }
        return me.finish(out)
    }

    private fun base(): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        listOf("provider" to provider, "paywall_id" to paywallId, "paywall_name" to paywallName,
            "offering_id" to offeringId, "placement" to placement)
            .forEach { (key, value) -> text(value)?.let { out[key] = it } }
        return out
    }

    /** Extra props fill up to 10 without replacing the paywall fields. */
    private fun finish(out: LinkedHashMap<String, Any?>): Map<String, Any?> {
        for (key in (props ?: emptyMap()).keys.sorted()) {
            if (out.size >= 10) break
            if (key !in out) Validate.props(mapOf(key to props!![key]))?.get(key)?.let { out[key] = it }
        }
        return out
    }

    private fun send(event: Event, props: Map<String, Any?>) = sink(event.eventName, props.ifEmpty { null })

    public companion object {
        /** The component interaction properties kept (RevenueCat's snake_case keys). */
        @JvmField
        public val INTERACTION_KEYS: List<String> =
            listOf("component_type", "component_name", "component_value", "origin_product_id", "destination_product_id")

        private val SUPERWALL = mapOf(
            "paywall_open" to Event.IMPRESSION,
            "paywall_close" to Event.CLOSE,
            "transaction_abandon" to Event.CANCEL,
        )

        private fun text(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() }

        /** paywallOpen or paywall_open → paywall_open. */
        private fun snake(name: String): String =
            name.trim().replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    }
}
