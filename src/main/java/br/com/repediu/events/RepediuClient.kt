package br.com.repediu.events

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal val ALLOWED_EVENTS = setOf(
    "page_view",
    "page_leave",
    "scroll_depth",
    "item_visible",
    "item_hidden",
    "item_attention",
    "item_revisit",
    "promotion_visible",
    "promotion_click",
    "view_category",
    "view_item",
    "add_to_cart",
    "cart_sync",
    "begin_checkout",
    "purchase",
    "order_cancelled",
    "search",
    "identify",
)

internal val CAMPAIGN_KEYS = listOf(
    "rid",
    "utm_source",
    "utm_medium",
    "utm_campaign",
    "utm_term",
    "utm_content",
)

internal const val SDK_NAME = "android"
internal val PII_PARAMS = setOf("rpd_phone", "rpd_email")
internal val REPEDIU_ORIGIN_PARAMS = PII_PARAMS + setOf("rid", "utm_source")
internal val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

internal const val BATCH_MAX_SIZE = 20
internal const val BATCH_INTERVAL_MS = 3_000L
internal const val SESSION_TIMEOUT_MS = 30 * 60 * 1_000L
internal const val MAX_QUEUED_EVENTS = 1_000
internal const val MAX_RETRY_DELAY_MS = 5 * 60 * 1_000L
internal const val MAX_PROPERTY_DEPTH = 16

private const val ANONYMOUS_ID_KEY = "_rpd_aid"
private const val USER_ID_KEY = "_rpd_uid"
private const val SESSION_KEY = "_rpd_sid"
private const val OPT_OUT_KEY = "_rpd_opt"
internal const val QUEUE_KEY = "_rpd_queue"
private const val OPTED_OUT = "0"
private const val OPTED_IN = "1"
private const val WRITE_KEY_FIELD = "_write_key"

internal interface Storage {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

internal fun interface Transport {
    fun post(body: String): Int
}

internal fun allowedEndpoint(endpoint: String): Boolean {
    val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
    if ("https".equals(uri.scheme, ignoreCase = true)) return true
    return "http".equals(uri.scheme, ignoreCase = true) && uri.host in LOCAL_HOSTS
}

internal class RepediuClient(
    writeKey: String,
    private val platform: String,
    private val userAgent: String,
    private val storage: Storage,
    private val transport: Transport,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val background: (() -> Unit) -> Unit,
) {
    private val lock = Any()
    private val superProperties = linkedMapOf<String, Any?>()
    private val promotionsSeen = mutableSetOf<String>()
    private val pending = mutableListOf<JSONObject>()
    private val visibleActivities = mutableSetOf<Int>()
    private var screenPath = "/"
    private var screenTitle = ""
    private var screenUrl = "app:/"
    private var screenEnteredAt: Long? = null
    private var leftScreenOnBackground = false
    private var queueChanged = false
    private var sending = false
    private var failures = 0
    private var retryAt: Long? = null

    var writeKey = writeKey
        private set

    val hasOptedOut get() = storage.get(OPT_OUT_KEY) == OPTED_OUT

    val userId get() = storage.get(USER_ID_KEY)

    val anonymousId: String
        get() = synchronized(lock) {
            storage.get(ANONYMOUS_ID_KEY) ?: UUID.randomUUID().toString().also {
                storage.set(ANONYMOUS_ID_KEY, it)
                log("New anonymous visitor: $it")
            }
        }

    val pendingEvents: List<JSONObject> get() = synchronized(lock) { pending.toList() }

    fun start() = guarded("start") {
        pending.addAll(0, loadQueue())
        if (hasOptedOut) {
            log("User has opted out — tracking disabled")
            return@guarded
        }
        anonymousId
        log("=== Repediu Android SDK v${BuildConfig.SDK_VERSION} ===")
    }

    fun track(event: String, properties: Map<String, Any?> = emptyMap()) = guarded("track $event") {
        if (hasOptedOut) return@guarded
        if (event !in ALLOWED_EVENTS) {
            log("Event ignored: \"$event\" is not part of the contract")
            return@guarded
        }
        val sessionId = sessionId()
        val campaign = sessionData()?.optJSONObject("campaign")
        val page = JSONObject().put("url", screenUrl).put("path", screenPath).put("title", screenTitle)
        val context = JSONObject().put("page", page)
        if (campaign != null && campaign.length() > 0) context.put("campaign", campaign)
        context.put("platform", platform).put("user_agent", userAgent)
        push(
            JSONObject()
                .put(WRITE_KEY_FIELD, writeKey)
                .put("event_id", UUID.randomUUID().toString())
                .put("event", event)
                .put("properties", json(superProperties + properties))
                .put("anonymous_id", anonymousId)
                .put("user_id", userId ?: JSONObject.NULL)
                .put("session_id", sessionId)
                .put("timestamp", iso(clock()))
                .put("context", context),
        )
    }

    fun screen(name: String) = guarded("screen") {
        leaveScreen()
        promotionsSeen.clear()
        val route = withoutPii(if (name.startsWith("/")) name else "/$name")
        screenUrl = "app:$route"
        screenPath = route.substringBefore('?').substringBefore('#')
        screenTitle = screenPath
        screenEnteredAt = clock()
        track("page_view")
    }

    private fun withoutPii(route: String): String {
        val path = route.substringBefore('?')
        val query = route.substringAfter('?', "").split('&').filterNot { it.substringBefore('=') in PII_PARAMS }
        return if (query.all { it.isEmpty() }) path else "$path?${query.joinToString("&")}"
    }

    fun promotionView(id: String, name: String, url: String?) = guarded("promotion") {
        if (promotionsSeen.add(id)) track("promotion_visible", promotion(id, name, url))
    }

    fun promotionClick(id: String, name: String, url: String?) = guarded("promotion") {
        track("promotion_click", promotion(id, name, url))
    }

    private fun promotion(id: String, name: String, url: String?) = mapOf(
        "promotion_id" to id,
        "promotion_name" to name,
        "promotion_url" to url?.takeIf { it.isNotEmpty() }?.let(::withoutPii),
        "repediu_origin" to (url != null && queryKeys(url).any { it in REPEDIU_ORIGIN_PARAMS }),
    ).filterValues { it != null }

    private fun queryKeys(url: String) = url.substringAfter('?', "").substringBefore('#').split('&').map { it.substringBefore('=') }

    fun leaveScreen() = guarded("page_leave") {
        val enteredAt = screenEnteredAt ?: return@guarded
        screenEnteredAt = null
        track("page_leave", mapOf("duration_ms" to clock() - enteredAt))
    }

    fun activityStarted(id: Int) = guarded("foreground") {
        if (visibleActivities.add(id) && visibleActivities.size == 1) enterForeground()
    }

    fun activityPaused(id: Int) = guarded("pause") { visibleActivities.add(id) }

    fun activityStopped(id: Int, changingConfigurations: Boolean) = guarded("background") {
        if (!visibleActivities.remove(id) || visibleActivities.isNotEmpty() || changingConfigurations) return@guarded
        enterBackground()
        background(::flush)
    }

    fun enterBackground() = guarded("background") {
        leftScreenOnBackground = leftScreenOnBackground || screenEnteredAt != null
        leaveScreen()
    }

    fun enterForeground() = guarded("foreground") {
        if (!leftScreenOnBackground || hasOptedOut) return@guarded
        leftScreenOnBackground = false
        val idle = clock() - (sessionData()?.optLong("last") ?: 0)
        screenEnteredAt = clock()
        if (idle >= SESSION_TIMEOUT_MS) track("page_view")
    }

    fun identify(userId: String?, phone: String?, email: String?, name: String?) = guarded("identify") {
        if (hasOptedOut) return@guarded
        val derived = deriveUserId(userId, phone, email)
        if (derived == null) {
            log("identify() requires userId, phone or email")
            return@guarded
        }
        storage.set(USER_ID_KEY, derived)
        log("User identified: $derived")
        track("identify", mapOf("user_id" to derived, "phone" to phone, "email" to email, "name" to name).filterValues { it != null })
        background(::flush)
    }

    fun setWriteKey(key: String) = guarded("setWriteKey") {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed == writeKey) return@guarded
        leaveScreen()
        writeKey = trimmed
        log("Write key switched: $trimmed")
    }

    fun register(properties: Map<String, Any?>) = guarded("register") {
        superProperties.putAll(properties)
        log("Super properties registered: $properties")
    }

    fun unregister(key: String) = guarded("unregister") { superProperties.remove(key) }

    fun setCampaign(parameters: Map<String, String?>) = guarded("campaign") {
        if (hasOptedOut) return@guarded
        val campaign = CAMPAIGN_KEYS.mapNotNull { key -> parameters[key]?.takeIf { it.isNotEmpty() }?.let { key to it } }.toMap()
        if (campaign.isEmpty()) return@guarded
        sessionId()
        val session = sessionData() ?: return@guarded
        storage.set(SESSION_KEY, session.put("campaign", json(campaign)).toString())
        log("Campaign captured: $campaign")
    }

    fun reset() = guarded("reset") {
        storage.remove(USER_ID_KEY)
        storage.remove(SESSION_KEY)
        superProperties.clear()
        log("SDK reset — identity and super properties cleared")
    }

    fun optOut() = guarded("optOut") {
        storage.set(OPT_OUT_KEY, OPTED_OUT)
        storage.remove(USER_ID_KEY)
        storage.remove(SESSION_KEY)
        storage.remove(ANONYMOUS_ID_KEY)
        superProperties.clear()
        pending.clear()
        persistQueue()
        log("User opted out — tracking disabled, identity cleared")
    }

    fun optIn() = guarded("optIn") {
        storage.set(OPT_OUT_KEY, OPTED_IN)
        log("User opted in — tracking enabled")
    }

    fun flush() {
        saveQueue()
        try {
            while (sendNextBatch()) Unit
        } catch (error: Exception) {
            synchronized(lock) { sending = false }
            log("Flush failed: $error")
        }
    }

    private fun sendNextBatch(): Boolean {
        val batch: List<JSONObject>
        val body: String
        synchronized(lock) {
            if (sending || pending.isEmpty() || hasOptedOut || inBackoff()) return false
            sending = true
            val key = keyOf(pending.first())
            batch = pending.filter { keyOf(it) == key }.take(BATCH_MAX_SIZE)
            userId?.let { id -> batch.filter { it.isNull("user_id") }.forEach { it.put("user_id", id) } }
            body = JSONObject()
                .put("write_key", key)
                .put("sdk_name", SDK_NAME)
                .put("sdk_version", BuildConfig.SDK_VERSION)
                .put("sent_at", iso(clock()))
                .put("events", JSONArray().apply { batch.forEach { put(JSONObject(it.toString()).apply { remove(WRITE_KEY_FIELD) }) } })
                .toString()
            log("Sending batch with ${batch.size} event(s)")
        }

        val delivered = post(body, batch.size)

        synchronized(lock) {
            sending = false
            if (!delivered) {
                failures++
                val delay = BATCH_INTERVAL_MS shl minOf(failures, 7)
                retryAt = clock() + minOf(delay, MAX_RETRY_DELAY_MS)
                log("Batch kept for retry in ${(retryAt!! - clock()) / 1_000}s")
                return false
            }
            val sentIds = batch.map { it.getString("event_id") }.toSet()
            pending.removeAll { it.getString("event_id") in sentIds }
            persistQueue()
            failures = 0
            retryAt = null
            log("Batch sent (${batch.size} event(s))")
            return true
        }
    }

    private fun post(body: String, count: Int): Boolean = try {
        val status = transport.post(body)
        val retryable = status >= 500 || status == 408 || status == 429
        if (status >= 400 && !retryable) log("Dropping $count event(s) — collector answered $status")
        !retryable
    } catch (error: Exception) {
        log("Network error: $error")
        false
    }

    private fun keyOf(event: JSONObject) = event.optString(WRITE_KEY_FIELD, writeKey)

    private fun inBackoff() = retryAt?.let { it - clock() in 1..MAX_RETRY_DELAY_MS } ?: false

    private fun push(event: JSONObject) {
        pending.add(event)
        while (pending.size > MAX_QUEUED_EVENTS) pending.removeAt(0)
        queueChanged = true
        background(::saveQueue)
        log("Event queued: ${event.getString("event")}")
        if (pending.size >= BATCH_MAX_SIZE) background(::flush)
    }

    private fun sessionId(): String {
        val now = clock()
        sessionData()?.let { session ->
            if (now - session.optLong("last") < SESSION_TIMEOUT_MS) {
                storage.set(SESSION_KEY, session.put("last", now).toString())
                return session.getString("id")
            }
        }
        val id = UUID.randomUUID().toString()
        storage.set(SESSION_KEY, JSONObject().put("id", id).put("start", now).put("last", now).toString())
        log("New session started: $id")
        return id
    }

    private fun sessionData(): JSONObject? = storage.get(SESSION_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun loadQueue(): List<JSONObject> {
        val raw = storage.get(QUEUE_KEY) ?: return emptyList()
        return runCatching { JSONArray(raw).let { array -> List(array.length()) { array.getJSONObject(it) } } }
            .getOrDefault(emptyList())
            .filter { it.has("event_id") && it.has("event") }
    }

    private fun saveQueue() = guarded("queue save") {
        if (queueChanged) persistQueue()
    }

    private fun persistQueue() {
        queueChanged = false
        storage.set(QUEUE_KEY, JSONArray().apply { pending.filter { it.getString("event") != "identify" }.forEach { put(it) } }.toString())
    }

    private inline fun guarded(operation: String, body: () -> Unit) {
        try {
            synchronized(lock) { body() }
        } catch (error: Exception) {
            log("$operation failed: $error")
        }
    }
}

private fun json(value: Any?, depth: Int = 0): Any = when {
    depth > MAX_PROPERTY_DEPTH -> throw IllegalArgumentException("properties nested deeper than $MAX_PROPERTY_DEPTH levels")
    value == null -> JSONObject.NULL
    value is Map<*, *> -> JSONObject().apply { value.forEach { (key, item) -> put(key.toString(), json(item, depth + 1)) } }
    value is Iterable<*> -> JSONArray().apply { value.forEach { put(json(it, depth + 1)) } }
    value is Number && !value.toDouble().isFinite() -> JSONObject.NULL
    else -> value
}

private fun iso(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
