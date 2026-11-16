package br.com.repediu.events

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class RepediuClientTest {
    private class MemoryStorage : Storage {
        val values = mutableMapOf<String, String>()

        override fun get(key: String) = values[key]

        override fun set(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class Collector : Transport {
        val batches = mutableListOf<JSONObject>()
        var online = true
        var status = 200
        var attempts = 0

        override fun post(body: String): Int {
            attempts++
            if (!online) throw IOException("offline")
            if (status == 200) batches.add(JSONObject(body))
            return status
        }

        val events: List<JSONObject>
            get() = batches.flatMap { batch -> batch.getJSONArray("events").let { array -> List(array.length()) { array.getJSONObject(it) } } }
    }

    private lateinit var storage: MemoryStorage
    private lateinit var collector: Collector
    private val tasks = mutableListOf<() -> Unit>()
    private var now = 1_793_026_800_000L

    private fun startClient(started: Boolean = true) = RepediuClient(
        writeKey = "wk_test",
        platform = "android",
        userAgent = "RepediuAndroid/test",
        storage = storage,
        transport = collector,
        clock = { now },
        log = {},
        background = { tasks.add(it) },
    ).apply { if (started) start() }

    private fun names() = collector.events.map { it.getString("event") }

    @Before
    fun setUp() {
        storage = MemoryStorage()
        collector = Collector()
        tasks.clear()
    }

    @Test
    fun `hashes with the same FNV variant as the JavaScript tracker`() {
        assertEquals("af426ec6c64d5584", hashId("5517999998888"))
        assertEquals("d53cc68aada542bc", hashId("cliente@exemplo.com"))
        assertEquals("9446394582d87967", hashId("joão@pão.com.br"))
        assertEquals("811c9dc501000193", hashId(""))
    }

    @Test
    fun `prefers user id, then phone, then email`() {
        assertEquals("42", deriveUserId(userId = " 42 ", phone = "17999998888"))
        assertEquals("rpd:tel:af426ec6c64d5584", deriveUserId(phone = "(17) 99999-8888"))
        assertEquals("rpd:mail:d53cc68aada542bc", deriveUserId(email = " Cliente@Exemplo.com "))
        assertNull(deriveUserId(phone = "123"))
    }

    @Test
    fun `a batch follows the collector contract used by the web SDK`() {
        val client = startClient()
        client.screen("/cardapio")
        RepediuOrder(client::track).addToCart("pizza-calabresa", "Calabresa", price = 64.9)
        client.flush()

        val batch = collector.batches.single()
        assertEquals("wk_test", batch.getString("write_key"))
        assertEquals("android", batch.getString("sdk_name"))
        assertEquals(BuildConfig.SDK_VERSION, batch.getString("sdk_version"))
        assertEquals("2026-10-26T15:00:00.000Z", batch.getString("sent_at"))

        val addToCart = collector.events.last()
        assertEquals("add_to_cart", addToCart.getString("event"))
        val properties = addToCart.getJSONObject("properties")
        assertEquals(setOf("item_id", "item_name", "quantity", "price"), properties.keySet())
        assertEquals(64.9, properties.getDouble("price"), 0.0)
        assertEquals(client.anonymousId, addToCart.getString("anonymous_id"))
        assertTrue(addToCart.getString("session_id").isNotEmpty())
        assertFalse(addToCart.has("_write_key"))

        val context = addToCart.getJSONObject("context")
        assertEquals("android", context.getString("platform"))
        assertEquals("RepediuAndroid/test", context.getString("user_agent"))
        val page = context.getJSONObject("page")
        assertEquals("app:/cardapio", page.getString("url"))
        assertEquals("/cardapio", page.getString("path"))
        assertFalse(page.has("referrer"))
        assertTrue(client.pendingEvents.isEmpty())
    }

    @Test
    fun `queued events survive the app being killed before sending`() {
        collector.online = false
        startClient().track("view_category", mapOf("category" to "Pizzas"))
        tasks.first()()

        collector.online = true
        startClient().flush()

        assertEquals(listOf("view_category"), names())
    }

    @Test
    fun `a network failure keeps the batch and retries after the backoff`() {
        val client = startClient()
        collector.online = false
        client.track("search", mapOf("query" to "pizza"))
        client.flush()
        assertEquals(1, client.pendingEvents.size)

        collector.online = true
        client.flush()
        assertTrue(collector.batches.isEmpty())

        now += 7_000
        client.flush()
        assertEquals(listOf("search"), names())
        assertTrue(client.pendingEvents.isEmpty())
    }

    @Test
    fun `the session survives short pauses and rolls over after 30 minutes idle`() {
        val client = startClient()
        client.track("page_view")
        now += 29 * 60_000
        client.track("page_view")
        now += 31 * 60_000
        client.track("page_view")
        client.flush()

        val sessions = collector.events.map { it.getString("session_id") }
        assertEquals(sessions[0], sessions[1])
        assertNotEquals(sessions[1], sessions[2])
    }

    @Test
    fun `identify stamps the user on events queued before it`() {
        val client = startClient()
        client.track("view_item", mapOf("item_id" to "x"))
        client.identify(userId = null, phone = "17999998888", email = null, name = "Cliente")
        client.flush()

        assertTrue(collector.events.all { it.getString("user_id") == "rpd:tel:af426ec6c64d5584" })
        val properties = collector.events.last().getJSONObject("properties")
        assertEquals(setOf("user_id", "phone", "name"), properties.keySet())
        assertEquals("Cliente", properties.getString("name"))
    }

    @Test
    fun `campaign parameters from a deep link ride along until the session ends`() {
        val client = startClient()
        client.setCampaign(mapOf("utm_source" to "whatsapp", "rid" to "abc", "outro" to "1"))
        client.track("page_view")
        now += 60 * 60_000
        client.track("page_view")
        client.flush()

        val campaign = collector.events.first().getJSONObject("context").getJSONObject("campaign")
        assertEquals(setOf("utm_source", "rid"), campaign.keySet())
        assertEquals("abc", campaign.getString("rid"))
        assertFalse(collector.events.last().getJSONObject("context").has("campaign"))
    }

    @Test
    fun `a screen route keeps its query in the url but not in the path`() {
        val client = startClient()
        client.screen("/loja/rp-centro?rid=abc")
        client.flush()

        val page = collector.events.single().getJSONObject("context").getJSONObject("page")
        assertEquals("app:/loja/rp-centro?rid=abc", page.getString("url"))
        assertEquals("/loja/rp-centro", page.getString("path"))
    }

    @Test
    fun `the screen url never carries the phone or email from the link`() {
        val client = startClient()
        client.screen("/loja/rp-centro?rid=abc&rpd_phone=5517999990000&rpd_email=ana@x.com")
        client.screen("/cardapio?rpd_phone=5517999990000")
        client.flush()

        val urls = collector.events.filter { it.getString("event") == "page_view" }
            .map { it.getJSONObject("context").getJSONObject("page").getString("url") }
        assertEquals(listOf("app:/loja/rp-centro?rid=abc", "app:/cardapio"), urls)
    }

    @Test
    fun `leaving a screen reports how long it stayed in front`() {
        val client = startClient()
        client.screen("/cardapio")
        now += 5_000
        client.screen("/produto/calabresa")
        client.flush()

        assertEquals(listOf("page_view", "page_leave", "page_view"), names())
        val leave = collector.events[1]
        assertEquals(5_000L, leave.getJSONObject("properties").getLong("duration_ms"))
        assertEquals("/cardapio", leave.getJSONObject("context").getJSONObject("page").getString("path"))
    }

    @Test
    fun `going to background leaves the screen once`() {
        val client = startClient()
        client.screen("/cardapio")
        now += 3_000
        client.leaveScreen()
        now += 3_000
        client.leaveScreen()
        client.screen("/carrinho")
        client.flush()

        assertEquals(listOf("page_view", "page_leave", "page_view"), names())
        assertEquals(3_000L, collector.events[1].getJSONObject("properties").getLong("duration_ms"))
    }

    @Test
    fun `register merges store context into every event until reset`() {
        val client = startClient()
        client.register(mapOf("store_id" to "rp-centro"))
        client.track("view_category", mapOf("category" to "Pizzas"))
        client.reset()
        client.track("view_category", mapOf("category" to "Bebidas"))
        client.flush()

        assertEquals("rp-centro", collector.events.first().getJSONObject("properties").getString("store_id"))
        assertEquals(setOf("category"), collector.events.last().getJSONObject("properties").keySet())
    }

    @Test
    fun `opting out drops queued events and stops tracking until opt in`() {
        val client = startClient()
        client.track("page_view")
        client.optOut()
        client.track("page_view")
        client.flush()
        assertTrue(collector.batches.isEmpty())
        assertTrue(client.hasOptedOut)

        client.optIn()
        client.track("search", mapOf("query" to "pizza"))
        client.flush()
        assertEquals(listOf("search"), names())
    }

    @Test
    fun `switching the write key sends each event under the key active when it was tracked`() {
        val client = startClient()
        client.track("page_view")
        client.setWriteKey("wk_loja_damha")
        client.track("view_category", mapOf("category" to "Pizzas"))
        client.track("view_item", mapOf("item_id" to "x"))
        client.flush()

        assertEquals(listOf("wk_test", "wk_loja_damha"), collector.batches.map { it.getString("write_key") })
        val lastBatch: JSONArray = collector.batches.last().getJSONArray("events")
        assertEquals(listOf("view_category", "view_item"), List(lastBatch.length()) { lastBatch.getJSONObject(it).getString("event") })
    }

    private fun eventsByKey(): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        collector.batches.forEach { batch ->
            val events = batch.getJSONArray("events")
            result.getOrPut(batch.getString("write_key")) { mutableListOf() } += List(events.length()) {
                val event = events.getJSONObject(it)
                "${event.getString("event")} ${event.getJSONObject("context").getJSONObject("page").getString("path")}"
            }
        }
        return result
    }

    @Test
    fun `a customer hopping between stores lands every event in the project of the store it happened in`() {
        val client = startClient()
        val order = RepediuOrder(client::track)
        client.screen("/lojas")
        client.setWriteKey("wk_centro")
        client.screen("/loja/centro")
        order.addToCart("acai-500", "Açaí 500ml", price = 24.9)
        client.setWriteKey("wk_damha")
        client.screen("/loja/damha")
        order.viewItem("acai-300", "Açaí 300ml")
        client.setWriteKey("wk_centro")
        client.screen("/loja/centro")
        order.placeOrder("pedido-1", value = 24.9)
        client.flush()

        assertEquals(
            mapOf(
                "wk_test" to listOf("page_view /lojas", "page_leave /lojas"),
                "wk_centro" to listOf(
                    "page_view /loja/centro",
                    "add_to_cart /loja/centro",
                    "page_leave /loja/centro",
                    "page_view /loja/centro",
                    "purchase /loja/centro",
                ),
                "wk_damha" to listOf("page_view /loja/damha", "view_item /loja/damha", "page_leave /loja/damha"),
            ),
            eventsByKey(),
        )
    }

    @Test
    fun `events of several stores queued offline survive a restart and each reaches its own project`() {
        collector.online = false
        val client = startClient()
        client.setWriteKey("wk_centro")
        client.track("view_category", mapOf("category" to "Açaí"))
        client.setWriteKey("wk_damha")
        client.track("view_category", mapOf("category" to "Bebidas"))
        client.flush()
        assertTrue(collector.batches.isEmpty())

        collector.online = true
        startClient().flush()

        assertEquals(listOf("wk_centro", "wk_damha"), collector.batches.map { it.getString("write_key") })
        assertEquals(
            listOf("Açaí", "Bebidas"),
            collector.batches.map { it.getJSONArray("events").getJSONObject(0).getJSONObject("properties").getString("category") },
        )
    }

    @Test
    fun `events outside the contract are ignored`() {
        val client = startClient()
        client.track("app_open")
        client.flush()
        assertTrue(collector.batches.isEmpty())
    }

    @Test
    fun `a property the encoder cannot handle is converted and never crashes the app`() {
        val loop = mutableMapOf<String, Any?>()
        loop["self"] = loop
        collector.online = false
        val client = startClient()
        client.track("view_item", mapOf("item_id" to "x", "price" to Double.NaN, "raw" to Any()))
        client.track("view_item", mapOf("item_id" to "loop", "loop" to loop))
        client.track("search", mapOf("query" to "pizza"))
        tasks.first()()

        collector.online = true
        now += 10 * 60_000
        startClient().flush()

        assertEquals(listOf("view_item", "search"), names())
        val properties = collector.events.first().getJSONObject("properties")
        assertTrue(properties.isNull("price"))
        assertTrue(properties.getString("raw").startsWith("java.lang.Object@"))
    }

    @Test
    fun `storage corrupted by an older version or a tampered device does not stop tracking`() {
        listOf("not json", "{\"a\":1}", "[1, \"x\", null]").forEach { queue ->
            storage = MemoryStorage().apply {
                values["_rpd_queue"] = queue
                values["_rpd_sid"] = "{\"id\":"
                values["_rpd_opt"] = "talvez"
            }
            collector = Collector()

            val client = startClient()
            client.screen("/cardapio")
            client.flush()

            assertEquals(queue, listOf("page_view"), names())
            assertTrue(queue, collector.events.single().getString("session_id").isNotEmpty())
        }
    }

    @Test
    fun `a batch the collector refuses is dropped instead of retried forever`() {
        val client = startClient()
        listOf(400, 401, 403, 404, 413, 422).forEach { status ->
            collector.status = status
            client.track("search", mapOf("query" to "$status"))
            client.flush()
            assertTrue("$status", client.pendingEvents.isEmpty())
        }
    }

    @Test
    fun `a collector outage keeps the batch until the collector is back`() {
        val client = startClient()
        listOf(408, 429, 500, 502, 503).forEach { status ->
            collector.status = status
            client.track("search", mapOf("query" to "$status"))
            client.flush()
            assertEquals("$status", 1, client.pendingEvents.size)

            collector.status = 200
            now += MAX_RETRY_DELAY_MS
            client.flush()
            assertTrue("$status", client.pendingEvents.isEmpty())
        }
        assertEquals(listOf("408", "429", "500", "502", "503"), collector.events.map { it.getJSONObject("properties").getString("query") })
    }

    @Test
    fun `a long time offline keeps only the newest events, in memory and on disk`() {
        collector.online = false
        val client = startClient()
        repeat(MAX_QUEUED_EVENTS + 50) { client.track("search", mapOf("query" to "$it")) }

        assertEquals(MAX_QUEUED_EVENTS, client.pendingEvents.size)
        assertEquals("50", client.pendingEvents.first().getJSONObject("properties").getString("query"))
        tasks.first()()
        assertEquals(MAX_QUEUED_EVENTS, startClient().pendingEvents.size)
    }

    @Test
    fun `retries back off to one attempt every five minutes while the collector is down`() {
        collector.online = false
        val client = startClient()
        client.track("search", mapOf("query" to "pizza"))
        repeat(3_600) {
            client.flush()
            now += 1_000
        }
        assertTrue("${collector.attempts} attempts", collector.attempts <= 20)

        collector.online = true
        now += MAX_RETRY_DELAY_MS
        client.flush()
        assertEquals(listOf("search"), names())
    }

    @Test
    fun `a device clock set back does not freeze delivery`() {
        collector.online = false
        val client = startClient()
        client.track("search", mapOf("query" to "pizza"))
        client.flush()

        collector.online = true
        now -= 24 * 60 * 60_000
        now += MAX_RETRY_DELAY_MS
        client.flush()

        assertEquals(listOf("search"), names())
    }

    @Test
    fun `many threads tracking while the app flushes never lose nor duplicate an event`() {
        val client = startClient()
        val start = CountDownLatch(1)
        val trackers = (0 until 8).map { worker ->
            thread {
                start.await()
                repeat(100) { client.track("search", mapOf("query" to "$worker-$it")) }
            }
        }
        val flusher = thread {
            start.await()
            repeat(200) { client.flush() }
        }
        start.countDown()
        trackers.forEach { it.join() }
        flusher.join()
        client.flush()

        val queries = collector.events.map { it.getJSONObject("properties").getString("query") }
        assertEquals(800, queries.size)
        assertEquals(800, queries.toSet().size)
        assertTrue(client.pendingEvents.isEmpty())
    }

    @Test
    fun `opting out is remembered after a restart and nothing about the person stays on the device`() {
        val client = startClient()
        client.identify(userId = null, phone = "17999998888", email = null, name = null)
        client.screen("/cardapio")
        client.optOut()

        val restarted = startClient()
        restarted.screen("/cardapio")
        restarted.identify(userId = null, phone = "17999998888", email = null, name = null)
        RepediuOrder(restarted::track).placeOrder("pedido-1", value = 24.9)
        restarted.flush()

        assertTrue(collector.batches.isEmpty())
        assertEquals(setOf("_rpd_opt", "_rpd_queue"), storage.values.keys)
        assertEquals("[]", storage.values["_rpd_queue"])
    }

    @Test
    fun `a hostile link cannot switch the store, impersonate a user or inject fields`() {
        val client = startClient()
        client.setCampaign(
            mapOf(
                "write_key" to "wk_evil",
                "user_id" to "admin",
                "anonymous_id" to "a1",
                "event" to "purchase",
                "value" to "999",
                "rid" to "r1",
                "utm_source" to "zap",
            ),
        )
        client.screen("/loja/centro?write_key=wk_evil&user_id=admin")
        client.flush()

        val pageView = collector.events.single()
        assertEquals("wk_test", collector.batches.single().getString("write_key"))
        assertEquals("page_view", pageView.getString("event"))
        assertTrue(pageView.isNull("user_id"))
        assertEquals(client.anonymousId, pageView.getString("anonymous_id"))
        assertEquals(0, pageView.getJSONObject("properties").length())
        assertEquals(setOf("rid", "utm_source"), pageView.getJSONObject("context").getJSONObject("campaign").keySet())
    }

    @Test
    fun `a blank store key is ignored and the internal key field never leaves the device`() {
        val client = startClient()
        client.setWriteKey("   ")
        client.setWriteKey("")
        client.track("search", mapOf("query" to "pizza"))
        client.setWriteKey("  wk_centro  ")
        client.track("search", mapOf("query" to "acai"))
        client.flush()

        assertEquals(listOf("wk_test", "wk_centro"), collector.batches.map { it.getString("write_key") })
        collector.batches.forEach { assertEquals(setOf("write_key", "sdk_name", "sdk_version", "sent_at", "events"), it.keySet()) }
        collector.events.forEach {
            assertEquals(setOf("event_id", "event", "properties", "anonymous_id", "user_id", "session_id", "timestamp", "context"), it.keySet())
        }
    }

    @Test
    fun `the raw phone and email only travel on identify, every other event carries the hash`() {
        val client = startClient()
        client.identify(userId = null, phone = "(17) 99999-8888", email = "cliente@exemplo.com", name = null)
        client.track("search", mapOf("query" to "pizza"))
        client.flush()

        val others = collector.events.filter { it.getString("event") != "identify" }
        assertTrue(others.isNotEmpty())
        others.forEach {
            assertFalse(it.toString().contains("99999"))
            assertFalse(it.toString().contains("cliente@"))
            assertEquals("rpd:tel:af426ec6c64d5584", it.getString("user_id"))
        }
    }

    @Test
    fun `identity derivation matches the golden vectors shared by every SDK`() {
        val golden = contract("golden/identity.json")
        golden.getJSONArray("normalize_phone").objects().forEach {
            assertEquals(it.getString("input"), it.text("expected"), normalizePhone(it.getString("input")))
        }
        golden.getJSONArray("derive_user_id").objects().forEach {
            val input = it.getJSONObject("input")
            assertEquals(it.getString("name"), it.text("expected"), deriveUserId(input.text("user_id"), input.text("phone"), input.text("email")))
        }
    }

    @Test
    fun `the allowed events are exactly the ones in the contract file`() {
        assertEquals(contract("events.json").getJSONObject("events").keySet(), ALLOWED_EVENTS)
    }

    @Test
    fun `digits from other alphabets are not read as a phone`() {
        assertNull(normalizePhone("١٧٩٩٩٩٩٨٨٨٨"))
    }

    @Test
    fun `the phone, email and name of identify never rest on the device`() {
        collector.online = false
        val client = startClient()
        client.identify(userId = null, phone = "(17) 99999-8888", email = "cliente@exemplo.com", name = "Cliente")
        client.track("search", mapOf("query" to "pizza"))
        client.flush()

        val disk = storage.values.values.joinToString()
        assertFalse(disk.contains("99999"))
        assertFalse(disk.contains("cliente@"))
        assertFalse(disk.contains("Cliente"))
        assertTrue(storage.values.getValue("_rpd_queue").contains("search"))
    }

    @Test
    fun `a deep link opened after opting out leaves nothing on the device`() {
        val client = startClient()
        client.register(mapOf("store_id" to "rp-centro"))
        client.optOut()
        client.setCampaign(mapOf("rid" to "abc"))
        assertEquals(setOf("_rpd_opt", "_rpd_queue"), storage.values.keys)

        client.optIn()
        client.track("search", mapOf("query" to "pizza"))
        client.flush()
        assertEquals(setOf("query"), collector.events.single().getJSONObject("properties").keySet())
    }

    @Test
    fun `only https endpoints are accepted, except the local machine`() {
        assertTrue(allowedEndpoint("https://tracker.repediu.com.br/collect"))
        assertTrue(allowedEndpoint("http://10.0.2.2:5080/collect"))
        assertTrue(allowedEndpoint("http://localhost:5080/collect"))
        assertFalse(allowedEndpoint("http://tracker.repediu.com.br/collect"))
        assertFalse(allowedEndpoint("ftp://tracker.repediu.com.br"))
        assertFalse(allowedEndpoint("not a url"))
        assertFalse(allowedEndpoint(""))
    }

    @Test
    fun `a promotion is seen once per screen and its link never carries the phone`() {
        val client = startClient()
        client.screen("/home")
        client.promotionView("black-friday", "Black Friday", "https://loja.app/promo?rid=abc&rpd_phone=5517999990000")
        client.promotionView("black-friday", "Black Friday", null)
        client.promotionClick("black-friday", "Black Friday", null)
        client.screen("/cardapio")
        client.promotionView("black-friday", "Black Friday", null)
        client.flush()

        assertEquals(listOf("page_view", "promotion_visible", "promotion_click", "page_leave", "page_view", "promotion_visible"), names())
        val seen = collector.events[1].getJSONObject("properties")
        assertEquals("https://loja.app/promo?rid=abc", seen.getString("promotion_url"))
        assertTrue(seen.getBoolean("repediu_origin"))
        val clicked = collector.events[2].getJSONObject("properties")
        assertEquals(setOf("promotion_id", "promotion_name", "repediu_origin"), clicked.keySet())
        assertFalse(clicked.getBoolean("repediu_origin"))
    }

    @Test
    fun `coming back from the background reopens the screen, with a new page view only after the session expired`() {
        val client = startClient()
        client.screen("/cardapio")
        now += 2_000
        client.enterBackground()
        now += 60_000
        client.enterForeground()
        now += 4_000
        client.enterBackground()
        now += 31 * 60_000
        client.enterForeground()
        client.flush()

        assertEquals(listOf("page_view", "page_leave", "page_leave", "page_view"), names())
        assertEquals(listOf(2_000L, 4_000L), collector.events.subList(1, 3).map { it.getJSONObject("properties").getLong("duration_ms") })
    }

    @Test
    fun `events are saved off the calling thread and identify is sent right away`() {
        val client = startClient()
        client.track("search", mapOf("query" to "pizza"))
        assertNull(storage.values["_rpd_queue"])
        tasks.removeAt(0)()
        assertTrue(storage.values.getValue("_rpd_queue").contains("search"))

        client.identify(userId = "42", phone = null, email = null, name = null)
        tasks.toList().forEach { it() }
        assertEquals(listOf("search", "identify"), names())
    }

    @Test
    fun `a store can report a delivery mode of its own`() {
        val client = startClient()
        RepediuOrder(client::track).beginCheckout(deliveryType = DeliveryType("dine_in"))
        RepediuOrder(client::track).beginCheckout(deliveryType = DeliveryType.PICKUP)
        client.flush()

        assertEquals(listOf("dine_in", "pickup"), collector.events.map { it.getJSONObject("properties").getString("delivery_type") })
    }

    @Test
    fun `a screen opened over another one does not count as leaving the app, even when the SDK started late`() {
        val client = startClient()
        client.screen("/cardapio")
        client.activityPaused(1)
        client.activityStarted(2)
        client.activityStopped(1, changingConfigurations = false)
        client.flush()
        assertEquals(listOf("page_view"), names())

        now += 3_000
        client.activityPaused(2)
        client.activityStopped(2, changingConfigurations = false)
        tasks.toList().forEach { it() }
        assertEquals(listOf("page_view", "page_leave"), names())
    }

    @Test
    fun `rotating the screen is not leaving the app`() {
        val client = startClient()
        client.activityStarted(1)
        client.screen("/cardapio")
        client.activityStopped(1, changingConfigurations = true)
        client.activityStarted(2)
        client.flush()

        assertEquals(listOf("page_view"), names())
    }

    @Test
    fun `a damaged entry in the saved queue is dropped instead of being resent forever`() {
        storage.values["_rpd_queue"] = """[{"event":"view_item"},{"event_id":"e-1","event":"search","_write_key":"wk_test"}]"""
        val client = startClient()
        client.flush()
        client.flush()

        assertEquals(1, collector.attempts)
        assertEquals(listOf("search"), names())
        assertTrue(client.pendingEvents.isEmpty())
    }

    @Test
    fun `events tracked before the saved queue is loaded go after it and nothing is lost`() {
        storage.values["_rpd_queue"] = """[{"event_id":"e-1","event":"search","_write_key":"wk_test"}]"""
        val client = startClient(started = false)
        client.track("view_category", mapOf("category" to "Pizzas"))
        client.start()
        tasks.toList().forEach { it() }

        assertEquals(listOf("search", "view_category"), client.pendingEvents.map { it.getString("event") })
        assertTrue(storage.values.getValue("_rpd_queue").contains("e-1"))
    }

    private fun contract(name: String) = JSONObject(javaClass.getResource("/contract/$name")!!.readText())
}

private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)

private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
