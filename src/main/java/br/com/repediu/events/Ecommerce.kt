package br.com.repediu.events

public data class DeliveryType(val code: String) {
    public companion object {
        @JvmField
        public val DELIVERY: DeliveryType = DeliveryType("delivery")

        @JvmField
        public val PICKUP: DeliveryType = DeliveryType("pickup")
    }
}

public class EcommerceItem(
    public val itemId: String,
    public val itemName: String,
    public val category: String? = null,
    public val price: Double? = null,
    public val quantity: Int? = null,
    public val currency: String? = null,
) {
    public fun toMap(): Map<String, Any?> = compact(
        "item_id" to itemId,
        "item_name" to itemName,
        "category" to category,
        "price" to price,
        "quantity" to quantity,
        "currency" to currency,
    )
}

public class RepediuOrder internal constructor(private val track: (String, Map<String, Any?>) -> Unit) {
    public fun viewItem(id: String, name: String, category: String? = null, price: Double? = null, currency: String? = null): Unit =
        track("view_item", compact("item_id" to id, "item_name" to name, "category" to category, "price" to price, "currency" to currency))

    public fun addToCart(id: String, name: String, category: String? = null, price: Double? = null, quantity: Int = 1, currency: String? = null): Unit =
        track(
            "add_to_cart",
            compact("item_id" to id, "item_name" to name, "quantity" to quantity, "category" to category, "price" to price, "currency" to currency),
        )

    public fun cartSync(items: List<EcommerceItem>): Unit = track("cart_sync", mapOf("items" to items.map { it.toMap() }))

    public fun viewCategory(category: String): Unit = track("view_category", mapOf("category" to category))

    public fun beginCheckout(
        step: String? = null,
        value: Double? = null,
        currency: String? = null,
        itemCount: Int? = null,
        coupon: String? = null,
        deliveryType: DeliveryType? = null,
    ): Unit = track(
        "begin_checkout",
        compact(
            "step" to step,
            "value" to value,
            "currency" to currency,
            "item_count" to itemCount,
            "coupon" to coupon,
            "delivery_type" to deliveryType?.code,
        ),
    )

    public fun placeOrder(
        orderId: String,
        value: Double,
        currency: String = "BRL",
        subtotal: Double? = null,
        shipping: Double? = null,
        discount: Double? = null,
        paymentMethod: String? = null,
        coupon: String? = null,
        deliveryType: DeliveryType? = null,
        items: List<EcommerceItem>? = null,
    ): Unit = track(
        "purchase",
        compact(
            "order_id" to orderId,
            "value" to value,
            "currency" to currency,
            "subtotal" to subtotal,
            "shipping" to shipping,
            "discount" to discount,
            "payment_method" to paymentMethod,
            "coupon" to coupon,
            "delivery_type" to deliveryType?.code,
            "items" to items?.map { it.toMap() },
        ),
    )

    public fun cancelOrder(orderId: String, reason: String? = null, value: Double? = null, currency: String? = null): Unit =
        track("order_cancelled", compact("order_id" to orderId, "reason" to reason, "value" to value, "currency" to currency))
}

private fun compact(vararg pairs: Pair<String, Any?>): Map<String, Any?> = pairs.filter { it.second != null }.toMap()
