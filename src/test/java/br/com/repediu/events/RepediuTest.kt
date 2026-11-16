package br.com.repediu.events

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepediuTest {
    private val received = CopyOnWriteArrayList<JSONObject>()
    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        Repediu::class.java.getDeclaredField("client").apply { isAccessible = true }.set(null, null)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/collect") { exchange ->
            val events = JSONObject(exchange.requestBody.readBytes().decodeToString()).getJSONArray("events")
            repeat(events.length()) { received.add(events.getJSONObject(it)) }
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun init() = Repediu.init(RuntimeEnvironment.getApplication(), "wk_test", "http://127.0.0.1:${server.address.port}/collect")

    private fun events(count: Int): List<JSONObject> {
        val deadline = System.currentTimeMillis() + 5_000
        while (received.size < count && System.currentTimeMillis() < deadline) {
            Repediu.flush()
            Thread.sleep(50)
        }
        return received.toList()
    }

    private fun names(count: Int) = events(count).map { "${it.getString("event")} ${it.getJSONObject("context").getJSONObject("page").getString("path")}" }

    private fun link(query: String, flags: Int = 0) = Intent(Intent.ACTION_VIEW, Uri.parse("loja://abrir?$query")).addFlags(flags)

    @Test
    fun `calls made before init are sent once the SDK starts`() {
        Repediu.screen("/antes")
        Repediu.track("search", mapOf("query" to "pizza"))
        init()

        assertEquals(listOf("page_view /antes", "search /antes"), names(2))
    }

    @Test
    fun `opening a second screen after a late init is not leaving the app`() {
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        init()
        Repediu.screen("/cardapio")
        first.pause()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        first.stop()
        Repediu.track("search", mapOf("query" to "pizza"))
        second.pause().stop()

        assertEquals(listOf("page_view /cardapio", "search /cardapio", "page_leave /cardapio"), names(3))
    }

    @Test
    fun `leaving the app after a late init closes the screen`() {
        val only = Robolectric.buildActivity(Activity::class.java).setup()
        init()
        Repediu.screen("/cardapio")
        only.pause().stop()

        assertEquals(listOf("page_view /cardapio", "page_leave /cardapio"), names(2))
    }

    @Test
    fun `a campaign link is read once, without customer data, and never from a restored or recent screen`() {
        init()
        val opened = Robolectric.buildActivity(Activity::class.java, link("rid=abc&utm_source=whatsapp&rpd_phone=17999998888")).setup()
        Repediu.track("search", mapOf("query" to "pizza"))
        Repediu.reset()
        opened.pause().stop()
        Robolectric.buildActivity(Activity::class.java).setup().pause().stop()
        opened.restart().start().resume()
        Robolectric.buildActivity(Activity::class.java, link("rid=recentes", Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)).setup()
        Robolectric.buildActivity(Activity::class.java, link("rid=restaurada")).setup(Bundle())
        Repediu.track("search", mapOf("query" to "esfiha"))

        val searches = events(2).filter { it.getString("event") == "search" }.map { it.getJSONObject("context") }
        assertEquals("""{"rid":"abc","utm_source":"whatsapp"}""", searches[0].getJSONObject("campaign").toString())
        assertFalse(searches[1].has("campaign"))
    }
}
