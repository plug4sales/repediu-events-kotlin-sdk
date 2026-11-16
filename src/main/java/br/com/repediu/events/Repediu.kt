package br.com.repediu.events

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

public object Repediu {
    public const val DEFAULT_ENDPOINT: String = "https://tracker.repediu.com.br/collect"

    private const val TAG = "Repediu"
    private const val MAX_EARLY_CALLS = 100
    private const val SOCKET_TAG = 0x525044

    @Volatile
    private var client: RepediuClient? = null

    private val early = mutableListOf<(RepediuClient) -> Unit>()

    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "repediu-events").apply { isDaemon = true } }

    @JvmStatic
    public val order: RepediuOrder = RepediuOrder { event, properties -> track(event, properties) }

    @JvmStatic
    @JvmOverloads
    public fun init(context: Context, writeKey: String, endpoint: String = DEFAULT_ENDPOINT, debug: Boolean = false) {
        synchronized(early) {
            if (client != null) return
            if (writeKey.isBlank()) {
                Log.e(TAG, "Error: writeKey is required")
                return
            }
            if (!allowedEndpoint(endpoint)) {
                Log.e(TAG, "Error: endpoint must use https")
                return
            }
            try {
                val application = context.applicationContext as Application
                val created = RepediuClient(
                    writeKey = writeKey.trim(),
                    platform = "android",
                    userAgent = "RepediuAndroid/${BuildConfig.SDK_VERSION} (Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL}; Mobile)",
                    storage = PreferencesStorage(
                        state = application.getSharedPreferences("repediu_events", Context.MODE_PRIVATE),
                        queue = application.getSharedPreferences("repediu_events_queue", Context.MODE_PRIVATE),
                    ),
                    transport = HttpTransport(endpoint),
                    clock = System::currentTimeMillis,
                    log = if (debug) { message -> Log.d(TAG, message) } else { _ -> },
                    background = { executor.execute(it) },
                )
                executor.execute(created::start)
                client = created
                early.forEach { it(created) }
                early.clear()
                application.registerActivityLifecycleCallbacks(LifecycleTracker(created))
                executor.scheduleWithFixedDelay(created::flush, BATCH_INTERVAL_MS, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS)
            } catch (error: Exception) {
                Log.e(TAG, "Error: init failed", error)
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    public fun track(event: String, properties: Map<String, Any?> = emptyMap()): Unit = call { it.track(event, properties) }

    @JvmStatic
    public fun screen(name: String): Unit = call { it.screen(name) }

    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String? = null, phone: String? = null, email: String? = null, name: String? = null): Unit =
        call { it.identify(userId, phone, email, name) }

    @JvmStatic
    @JvmOverloads
    public fun search(query: String, properties: Map<String, Any?> = emptyMap()): Unit = track("search", mapOf("query" to query) + properties)

    @JvmStatic
    @JvmOverloads
    public fun promotionView(id: String, name: String, url: String? = null): Unit = call { it.promotionView(id, name, url) }

    @JvmStatic
    @JvmOverloads
    public fun promotionClick(id: String, name: String, url: String? = null): Unit = call { it.promotionClick(id, name, url) }

    @JvmStatic
    public fun setWriteKey(key: String): Unit = call { it.setWriteKey(key) }

    @JvmStatic
    public fun register(properties: Map<String, Any?>): Unit = call { it.register(properties) }

    @JvmStatic
    public fun unregister(key: String): Unit = call { it.unregister(key) }

    @JvmStatic
    public fun setCampaignFromUri(uri: Uri) {
        if (!uri.isHierarchical) return
        val parameters = uri.queryParameterNames.associateWith(uri::getQueryParameter)
        call { it.setCampaign(parameters) }
    }

    @JvmStatic
    public fun reset(): Unit = call { it.reset() }

    @JvmStatic
    public fun optOut(): Unit = call { it.optOut() }

    @JvmStatic
    public fun optIn(): Unit = call { it.optIn() }

    @JvmStatic
    public val hasOptedOut: Boolean get() = client?.hasOptedOut ?: false

    @JvmStatic
    public val anonymousId: String? get() = client?.takeUnless { it.hasOptedOut }?.anonymousId

    @JvmStatic
    public val userId: String? get() = client?.userId

    @JvmStatic
    public fun flush() {
        val current = client ?: return
        executor.execute(current::flush)
    }

    private fun call(action: (RepediuClient) -> Unit) {
        val current = client ?: synchronized(early) {
            if (client == null && early.size < MAX_EARLY_CALLS) early.add(action)
            client
        } ?: return
        action(current)
    }

    private class LifecycleTracker(private val client: RepediuClient) : Application.ActivityLifecycleCallbacks {
        private val handledIntents = Collections.newSetFromMap(WeakHashMap<Intent, Boolean>())

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (savedInstanceState != null) activity.intent?.let(handledIntents::add)
        }

        override fun onActivityStarted(activity: Activity) = client.activityStarted(System.identityHashCode(activity))

        override fun onActivityResumed(activity: Activity) {
            val intent = activity.intent ?: return
            if (!handledIntents.add(intent) || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
            intent.data?.let(::setCampaignFromUri)
        }

        override fun onActivityPaused(activity: Activity) = client.activityPaused(System.identityHashCode(activity))

        override fun onActivityStopped(activity: Activity) =
            client.activityStopped(System.identityHashCode(activity), activity.isChangingConfigurations)

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private class PreferencesStorage(private val state: SharedPreferences, private val queue: SharedPreferences) : Storage {
        override fun get(key: String): String? = file(key).getString(key, null)

        override fun set(key: String, value: String) = file(key).edit().putString(key, value).apply()

        override fun remove(key: String) = file(key).edit().remove(key).apply()

        private fun file(key: String) = if (key == QUEUE_KEY) queue else state
    }

    private class HttpTransport(private val endpoint: String) : Transport {
        override fun post(body: String): Int {
            TrafficStats.setThreadStatsTag(SOCKET_TAG)
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                return connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }
}
