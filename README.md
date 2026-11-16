# repediu-events

Repediu events SDK for Android apps. Sends screens, cart, orders and campaigns to the store's Repediu project, with the
same identity and event contract as the Repediu JavaScript tracker and the Flutter SDK.

## Install

```kotlin
dependencies {
    implementation("br.com.repediu:repediu-events:0.1.0")
}
```

The artifact is on Maven Central, so the project needs `mavenCentral()` in its repositories. Requires Android 7.0
(API 24) or newer. It works from Kotlin and Java, adds no dependency besides the Kotlin standard library, and brings
the internet permission with it.

## Getting started

```kotlin
import br.com.repediu.events.Repediu

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repediu.init(this, "STORE_WRITE_KEY")
    }
}
```

The write key is in the store's Repediu panel. If the app only knows it later, for example after loading the store from
its own backend, call `init` then: up to 100 calls made before it are kept and sent once it runs.

Call `Repediu.screen("/menu")` when each screen opens. It records a `page_view` and, for the previous screen, a
`page_leave` with the time spent on it. With Jetpack Navigation, one listener covers every destination:

```kotlin
navController.addOnDestinationChangedListener { _, destination, _ ->
    destination.route?.let { Repediu.screen(it) }
}
```

`page_leave` is also sent when the app goes to the background, and the screen is reopened when it comes back.

`debug = BuildConfig.DEBUG` in `init` prints every queued event and every sent batch to Logcat in debug builds. Keep it
off in release: the log includes customer data.

## Environments

`init` sends to production by default. Point `endpoint` to homologation while testing the integration:

| Environment | `endpoint` |
| --- | --- |
| Production (default) | `https://tracker.repediu.com.br/collect` |
| Homologation | `https://tracker-hml.repediu.com.br/collect` |

Each environment has its own write keys, and Repediu provides the homologation one. A key sent to the other
environment is accepted and dropped, so nothing shows up in the panel. Homologation may be offline outside business
hours (Brasília time); events stay queued in the app and are sent later.

Choose both at build time instead of hardcoding them:

```kotlin
android {
    buildFeatures { buildConfig = true }
    buildTypes {
        debug {
            buildConfigField("String", "REPEDIU_WRITE_KEY", "\"HOMOLOGATION_WRITE_KEY\"")
            buildConfigField("String", "REPEDIU_ENDPOINT", "\"https://tracker-hml.repediu.com.br/collect\"")
        }
        release {
            buildConfigField("String", "REPEDIU_WRITE_KEY", "\"STORE_WRITE_KEY\"")
            buildConfigField("String", "REPEDIU_ENDPOINT", "\"https://tracker.repediu.com.br/collect\"")
        }
    }
}
```

```kotlin
Repediu.init(this, BuildConfig.REPEDIU_WRITE_KEY, BuildConfig.REPEDIU_ENDPOINT)
```

## Customer

```kotlin
Repediu.identify(phone = "5511999998888", email = "customer@example.com", name = "Maria")
Repediu.reset()
```

Call `identify` on login or whenever the customer provides a phone or an email, and `reset` on logout.

## Orders

```kotlin
Repediu.order.viewCategory("Pizzas")
Repediu.order.viewItem("pepperoni-pizza", "Pepperoni Pizza", category = "Pizzas", price = 64.9)
Repediu.order.addToCart("pepperoni-pizza", "Pepperoni Pizza", price = 64.9, quantity = 2)
Repediu.order.cartSync(listOf(EcommerceItem("pepperoni-pizza", "Pepperoni Pizza", price = 64.9, quantity = 2)))
Repediu.order.beginCheckout(value = 129.8, itemCount = 2, deliveryType = DeliveryType.DELIVERY)
Repediu.order.placeOrder(
    "ORDER-123",
    129.8,
    paymentMethod = "pix",
    deliveryType = DeliveryType.DELIVERY,
    items = listOf(EcommerceItem("pepperoni-pizza", "Pepperoni Pizza", price = 64.9, quantity = 2)),
)
Repediu.order.cancelOrder("ORDER-123", reason = "customer gave up")
Repediu.search("pepperoni")
```

Send `cartSync` with the whole cart on every change, including when it becomes empty. Send `placeOrder` once, after the
store confirms the order, with the order id the store uses, the delivery type and the ordered items.

`DeliveryType.DELIVERY` and `DeliveryType.PICKUP` cover the usual cases; any other mode the store offers is
`DeliveryType("dine_in")`.

From Java, the entry point is the same (`Repediu.init(this, "STORE_WRITE_KEY")`, `Repediu.screen("/menu")`). The order
helpers are reached through `Repediu.getOrder()` and take every argument, with `null` for the optional ones.

## Several stores in one app

Each store has its own write key. Start with the default store and switch when the customer picks another one:

```kotlin
Repediu.setWriteKey(store.repediuWriteKey)
```

Every event goes to the store that was active when it happened, including events still waiting in the queue.

## Campaigns

Links that open an activity are read automatically: `rid` and `utm_*` are kept for the session and sent with all of its
events. An activity that receives links while already open has to hand them over in `onNewIntent`, either with
`setIntent(intent)` or directly:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let(Repediu::setCampaignFromUri)
}
```

## Banners and promotions

Report a banner or promotion when it appears on screen and when it is tapped. A view counts once per screen;
every tap counts. `url` is optional: where the promotion leads.

```kotlin
Repediu.promotionView("cashback-15", "Cashback 15%", url = "app:/promo/cashback")
Repediu.promotionClick("cashback-15", "Cashback 15%", url = "app:/promo/cashback")
```

## Privacy

```kotlin
Repediu.optOut()
Repediu.optIn()
```

`optOut` erases the ids and the queue stored on the device and stops all sending, also after the app restarts.

What the SDK sends to Repediu, for the Google Play data safety form:

- a random install id and session id, created by the SDK (no advertising id, no device id);
- the customer id given to `identify`, or a hash of the phone or e-mail; the phone, e-mail and name themselves only on
  the `identify` event;
- screen names, searches, viewed and carted items, and orders (id, value, items, payment method, delivery type);
- campaign parameters of the link that opened the app (`rid`, `utm_*`), with every event of that session;
- the platform (Android), the Android version, the device manufacturer and model, and the SDK version.

No location, contacts or other app data is read.

## Delivery

Events go out in batches every 3 seconds or every 20 events. The queue is saved on the device right after each event,
off the calling thread, so it survives the app being closed. The `identify` event, the only one with the phone, e-mail
and name, never goes to disk: it is sent as soon as it happens. Without network, or with Repediu unreachable, the batch
is kept and retried with a growing interval of up to 5 minutes. The queue holds at most 1,000 events. No SDK error ever
reaches the app. The SDK only sends to an `https` endpoint (except `localhost` and `10.0.2.2`, for local development).
