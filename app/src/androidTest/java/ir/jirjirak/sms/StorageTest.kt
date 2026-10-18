package ir.jirjirak.sms

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the app keeps things, on a device: the event log lives in its own file — a ping's stamps
 * never rewrite it — and a log written by 1.4.4 or 1.0 moves there once, nothing lost or doubled.
 * A ping that says what the last one said writes nothing.
 */
@RunWith(AndroidJUnit4::class)
class StorageTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = Store(context)
    private val settings get() = context.getSharedPreferences("jirjirak_sms", Context.MODE_PRIVATE)
    private val log get() = context.getSharedPreferences("jirjirak_events", Context.MODE_PRIVATE)

    @Before fun setUp() = reset()

    @After fun tearDown() = reset()

    private fun reset() {
        store.clearEvents()
        store.shop = null
        store.shopInfo = JSONObject()
        store.lastError = null
    }

    @Test
    fun events_are_written_to_their_own_file_not_the_settings() {
        val id = store.event("sms", "queued", "پیامک بانک", from = "9999", body = "واریز 1,000,000")
        store.updateEvent(id, "sent", verdict = "رسید")

        assertFalse(settings.contains("events"))
        assertTrue(log.getString("events", "")!!.contains(id))
        assertEquals("sent", store.events().first().optString("status"))
    }

    @Test
    fun a_log_from_1_4_4_and_1_0_moves_once_newest_first_nothing_doubled() {
        val kept = store.event("net", "ok", "ارتباط با ربات برقرار شد")
        val old = JSONArray()
            .put(JSONObject().put("id", "e2").put("at", 2).put("kind", "sms").put("status", "sent").put("title", "پیامک بانک"))
            .put(JSONObject().put("id", kept).put("at", 1).put("kind", "net").put("status", "ok").put("title", "تکرار"))
        val oldest = JSONArray().put(JSONObject().put("id", "l1").put("at", 0).put("ok", true).put("from", "9999"))
        settings.edit().putString("events", old.toString()).putString("log", oldest.toString()).commit()

        val rows = store.events()

        assertEquals(listOf(kept, "e2", "l1"), rows.map { it.optString("id") })
        assertEquals("the copy already moved wins", "ارتباط با ربات برقرار شد", rows.first().optString("title"))
        assertEquals("1.0 rows become SMS events", "sent", rows.last().optString("status"))
        assertFalse(settings.contains("events"))
        assertFalse(settings.contains("log"))
    }

    @Test
    fun a_ping_that_repeats_the_last_one_writes_nothing() {
        store.shop = "فروشگاه"
        store.shopInfo = JSONObject().put("shop", "فروشگاه").put("features", JSONArray().put("dry_run"))
        val before = Store.revision.intValue

        store.shop = "فروشگاه"
        store.shopInfo = JSONObject().put("shop", "فروشگاه").put("features", JSONArray().put("dry_run"))
        store.lastError = null

        assertEquals(before, Store.revision.intValue)
        store.shop = "فروشگاه تازه"
        assertTrue(Store.revision.intValue > before)
    }
}
