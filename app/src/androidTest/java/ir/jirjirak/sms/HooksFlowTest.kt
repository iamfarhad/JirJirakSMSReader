package ir.jirjirak.sms

import android.Manifest
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.jirjirak.sms.ui.App
import ir.jirjirak.sms.ui.JirjirakTheme
import ir.jirjirak.sms.ui.SummaryCache
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLDecoder

/**
 * «وب‌هوک‌های دیگر» on a device: off and silent until turned on in «بیشتر»; then an SMS of a
 * webhook's sender is posted to it as a form, on its own queue, independent of the bot both ways.
 * The bot and the webhook are two [FakeBot]s on localhost.
 */
@RunWith(AndroidJUnit4::class)
class HooksFlowTest {

    @get:Rule val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = Store(context)
    private lateinit var bot: FakeBot
    private lateinit var hookServer: FakeBot
    private val deposit = "بانک ملت\nواریز: 1,250,000+\nمانده: 3,400,000\n1405/07/25-11:34"
    /** The SMS-centre stamp, new in every test: one SMS seen in a test must not be «seen» in the next. */
    private var stamp = 0L

    companion object {
        private var next = System.currentTimeMillis()
    }

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECEIVE_SMS)
        reset()
        store.theme = "light"
        store.lastUpdateCheck = System.currentTimeMillis()
        SummaryCache.clear()
        bot = FakeBot { _, path, _ -> if (path == "/ping") FakeBot.ping() else 200 to """{"status":"matched"}""" }
        hookServer = FakeBot { _, _, _ -> 200 to "OK" }
        next += 10_000
        stamp = next
    }

    @After fun tearDown() {
        bot.close()
        hookServer.close()
        reset()
    }

    private fun reset() {
        store.hooks.forEach { store.deleteHook(it.id) }
        store.hooksOn = false
        store.unpair()
        store.clearEvents()
        store.senders = emptyList()
        store.recoveryOn = false
    }

    private fun paired() {
        store.endpoint = bot.endpoint
        store.senders = listOf("9999")
        store.lastPing = System.currentTimeMillis()
        store.shopInfo = JSONObject(FakeBot.ping().second)
    }

    private val hookUrl get() = "http://127.0.0.1:${hookServer.port}/Bank/Message?userId=24057271"

    private fun hook(sender: String = "8888"): Hook = Hook("h-$sender", hookUrl, sender).also { store.saveHook(it) }

    private fun fields(body: String) = body.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }

    private fun lastEvent(kind: String) = store.events().first { it.optString("kind") == kind }

    // ── off by default ──────────────────────────────────────────────────

    @Test fun offByDefault_noWebhookGetsAnything() {
        paired()
        hook()
        assertFalse(store.hooksOn)
        assertFalse(Forward.accept(store, "8888", deposit, stamp))
        assertEquals(0, store.hookQueueSize())
    }

    @Test fun moreTab_offersTheFeature_andTurningItOnOpensTheList() {
        paired()
        launch()
        waitFor("روشن کردن")
        tap("وب‌هوک‌های دیگر")
        rule.onNodeWithText("روشن کن").performClick()
        rule.waitUntil(5_000) { store.hooksOn }
        waitFor("افزودن وب‌هوک")
        tap("خاموش کردن این قابلیت")
        rule.onNodeWithText("خاموش کن").performClick()
        rule.waitUntil(5_000) { !store.hooksOn }
    }

    // ── delivery ────────────────────────────────────────────────────────

    @Test fun hookOnlySender_isPostedAsAForm_andNotToTheBot() {
        paired()
        store.hooksOn = true
        hook("8888")
        assertTrue(Forward.accept(store, "8888", deposit, stamp))
        assertEquals("not a bot sender", 0, store.queueSize())
        assertEquals(1, store.hookQueueSize())

        Net.flush(context)

        val sent = hookServer.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("/Bank/Message?userId=24057271", sent.path)
        assertTrue(sent.contentType, sent.contentType.startsWith("application/x-www-form-urlencoded"))
        assertEquals(deposit, fields(sent.body)["Message"])
        assertTrue("the bot got no SMS", bot.requests.none { it.path.isEmpty() })
        assertEquals(0, store.hookQueueSize())
        assertEquals("sent", lastEvent("hook").optString("status"))
        assertEquals(true, store.hooks.single().lastOk)
    }

    @Test fun oneSenderForBotAndWebhook_eachGetsTheSmsOnce() {
        paired()
        store.hooksOn = true
        hook("9999")
        assertTrue(Forward.accept(store, "9999", deposit, stamp))
        assertFalse("the same SMS again (receiver and recovery racing)", Forward.accept(store, "9999", deposit, stamp))
        Net.flush(context)
        assertEquals(1, bot.requests.count { it.path.isEmpty() })
        assertEquals(1, hookServer.requests.size)
    }

    @Test fun webhookDown_neverHoldsTheBot_andItsSmsWaits() {
        paired()
        store.hooksOn = true
        hook("9999")
        hookServer.handler = { _, _, _ -> 500 to "boom" }
        Forward.accept(store, "9999", deposit, stamp)
        Net.flush(context)
        assertEquals("the bot got it", 1, bot.requests.count { it.path.isEmpty() })
        assertEquals(0, store.queueSize())
        assertEquals("the webhook's copy waits for a retry", 1, store.hookQueueSize())
        assertEquals("retry", lastEvent("hook").optString("status"))
        assertEquals(false, store.hooks.single().lastOk)

        hookServer.handler = { _, _, _ -> 200 to "OK" }
        Net.flush(context)
        assertEquals(0, store.hookQueueSize())
    }

    @Test fun webhook404_dropsThatSms_andNamesTheAddress() {
        paired()
        store.hooksOn = true
        hook()
        hookServer.handler = { _, _, _ -> 404 to "" }
        Forward.accept(store, "8888", deposit, stamp)
        Net.flush(context)
        assertEquals(0, store.hookQueueSize())
        val e = lastEvent("hook")
        assertEquals("failed", e.optString("status"))
        assertTrue(e.optString("detail"), e.optString("detail").contains("آدرس وب‌هوک پیدا نشد"))
        assertEquals("never the bot's «revoked»", null, store.lastError)
    }

    @Test fun aPassword_neverGoesToAWebhook() {
        paired()
        store.hooksOn = true
        hook()
        assertFalse(Forward.accept(store, "8888", "رمز پویا: 482913 بانک ملت", stamp))
        assertEquals(0, store.hookQueueSize())
    }

    @Test fun featureTurnedOff_queuedSmsWaits() {
        paired()
        store.hooksOn = true
        hook()
        Forward.accept(store, "8888", deposit, stamp)
        store.hooksOn = false
        Net.flush(context)
        assertTrue(hookServer.requests.isEmpty())
        assertEquals(1, store.hookQueueSize())
    }

    @Test fun deletingAWebhook_dropsItsQueue() {
        paired()
        store.hooksOn = true
        val h = hook()
        hookServer.handler = { _, _, _ -> 500 to "" }
        Forward.accept(store, "8888", deposit, stamp)
        Net.flush(context)
        store.deleteHook(h.id)
        assertEquals(0, store.hookQueueSize())
    }

    // ── the screens ─────────────────────────────────────────────────────

    @Test fun addEditSwitchOffAndDelete_fromTheList() {
        paired()
        store.hooksOn = true
        launch()
        tap("وب‌هوک‌های دیگر")
        tap("افزودن وب‌هوک")
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("http://cardholder.example.cloud/Bank/Message?userId=1")
        fields[1].performTextInput("8888")
        tap("ذخیرهٔ وب‌هوک")
        waitFor("آدرس باید کامل باشد")
        assertTrue(store.hooks.isEmpty())

        rule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput(hookUrl)
        tap("ذخیرهٔ وب‌هوک")
        rule.waitUntil(5_000) { store.hooks.size == 1 }
        assertEquals("8888", store.hooks.single().sender)
        assertTrue(store.hooks.single().on)

        waitFor("127.0.0.1")
        tap("127.0.0.1")
        rule.onNodeWithText("خاموش").performScrollTo().performClick()
        tap("ذخیرهٔ تغییرات")
        rule.waitUntil(5_000) { !store.hooks.single().on }

        tap("127.0.0.1")
        tap("حذف این وب‌هوک")
        rule.onNodeWithText("حذف وب‌هوک").performClick()
        rule.waitUntil(5_000) { store.hooks.isEmpty() }
    }

    // ── the queue's database, upgraded in place ─────────────────────────

    @Test fun aVersion1Queue_isKeptAsTheBotsAfterTheUpgrade() {
        Outbox.forget()
        val file = context.getDatabasePath("outbox.db")
        context.deleteDatabase("outbox.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE outbox (id TEXT PRIMARY KEY, body TEXT NOT NULL, fingerprint TEXT, attempts INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE seen (fingerprint TEXT PRIMARY KEY, at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO outbox (id, body, created_at) VALUES ('old', '{\"id\":\"old\",\"message\":\"واریز\",\"from\":\"9999\"}', 1)")
            db.version = 1
        }
        try {
            assertEquals(1, store.queueSize())
            assertEquals("old", store.queue().single().optString("id"))
            assertEquals(0, store.hookQueueSize())
        } finally {
            Outbox.forget()
            context.deleteDatabase("outbox.db")
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun launch() {
        rule.setContent { JirjirakTheme { App(0, MainActivity.TAB_MORE, 0, 0) } }
    }

    private fun waitFor(text: String, timeout: Long = 10_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun tap(text: String) = rule.onAllNodesWithText(text, substring = true)[0].performScrollTo().performClick()
}
