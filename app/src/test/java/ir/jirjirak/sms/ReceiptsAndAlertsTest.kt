package ir.jirjirak.sms

import ir.jirjirak.sms.ui.unreadable
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure halves of «رسیدها», the approval notification and the widget. */
class ReceiptsAndAlertsTest {

    private val key = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcd"

    @Test
    fun `only the bot's manage code is taken as a manage key`() {
        assertEquals(key, Receipts.parseCode("jirjirak-manage:$key"))
        assertEquals(key, Receipts.parseCode("  jirjirak-manage:$key\n"))
        assertNull("the pairing code is a different power", Receipts.parseCode("jirjirak-sms:https://bot.example.com/api/bank-sms/$key"))
        assertNull(Receipts.parseCode("jirjirak-manage:short"))
        assertNull(Receipts.parseCode("jirjirak-manage:$key?x=1"))
        assertNull(Receipts.parseCode(null))
    }

    @Test
    fun `the receipts list reads every field and tolerates the missing ones`() {
        val body = """{"ok":true,"receipts":[
            {"id":36239,"amount":120234,"rail":"کارت‌به‌کارت","customer":"Sara","username":"sara","title":"۱ ماهه","tracking":"7E6A94F8","created_at":1792058814,"has_image":true,
             "auto":{"score":50,"threshold":70,"enough":false,"why":["ℹ️ چرا خودکار تأیید نشد"]}},
            {"id":2,"amount":5000,"rail":"کارت‌به‌کارت","customer":null,"username":null,"title":null,"tracking":null,"created_at":0,"has_image":false,"auto":null}
        ]}"""
        val list = Receipts.parseList(body)
        assertEquals(2, list.size)
        assertEquals(36239L, list[0].id)
        assertEquals("Sara", list[0].customer)
        assertEquals(1792058814000L, list[0].createdAt)
        assertEquals(50, list[0].score)
        assertEquals(listOf("ℹ️ چرا خودکار تأیید نشد"), list[0].why)
        assertNull("JSON null is not the text «null»", list[1].customer)
        assertNull(list[1].score)
        assertTrue(Receipts.parseList("""{"ok":true}""").isEmpty())
    }

    @Test
    fun `an approval in the bot's reply becomes the phone's notification`() {
        assertEquals(175013L to "Sara", Net.approval("""{"ok":true,"approved":true,"amount":175013,"customer":"Sara"}"""))
        assertEquals(175013L to null, Net.approval("""{"ok":true,"approved":true,"amount":175013,"customer":null}"""))
        assertNull("held for review: nothing to celebrate", Net.approval("""{"ok":true,"approved":false,"amount":175013}"""))
        assertNull(Net.approval("not json"))
    }

    @Test
    fun `the widget says the one thing that matters most`() {
        val now = 1_800_000_000_000L
        assertEquals("⚪️ وصل نیست", StatusWidget.lines(false, 0, 0, 0, 0, now).first)
        assertEquals("🟡 شمارهٔ بانک مجاز نشده", StatusWidget.lines(true, 0, now, 0, 0, now).first)
        assertEquals("📵 ارتباط قطع است", StatusWidget.lines(true, 1, now - 41 * 60_000, 3, 0, now).first)
        assertEquals("🟡 ۲ پیامک در صف", StatusWidget.lines(true, 1, now, 3, 2, now).first)
        val (title, sub) = StatusWidget.lines(true, 1, now - 60_000, 8, 0, now)
        assertEquals("🟢 فعال", title)
        assertTrue(sub, sub.startsWith("امروز ۸ پیامک"))
    }

    @Test
    fun `only an SMS the bot could not read offers to go to support`() {
        assertTrue(unreadable(JSONObject().put("kind", "sms").put("verdict", "ربات نتوانست مبلغ واریز را از این پیامک بخواند")))
        assertFalse(unreadable(JSONObject().put("kind", "sms").put("verdict", "با فاکتور تطبیق داده شد")))
        assertFalse(unreadable(JSONObject().put("kind", "ignored").put("detail", "نتوانست")))
    }

    @Test
    fun `only 401 asks for a new key — 404 is a lost pairing, and English never reaches the seller`() {
        val ok = Receipts.outcome(200, "{}") { "parsed" }
        assertEquals("parsed", (ok as Receipts.Result.Ok).value)
        assertEquals(Receipts.Result.NeedKey, Receipts.outcome(401, "") { })
        assertEquals(Receipts.Result.AlreadyDecided, Receipts.outcome(409, """{"message":"این رسید قبلاً بررسی شده است."}""") { })
        val lost = Receipts.outcome(404, "") { } as Receipts.Result.Failed
        assertTrue(lost.message, lost.message.contains("دوباره وصل"))
        val throttled = Receipts.outcome(429, """{"message":"Too Many Attempts."}""") { } as Receipts.Result.Failed
        assertFalse(throttled.message, throttled.message.contains("Too Many"))
        val inactive = Receipts.outcome(403, """{"message":"Tenant not active"}""") { } as Receipts.Result.Failed
        assertFalse(inactive.message.contains("Tenant"))
        val ours = Receipts.outcome(500, """{"message":"تأیید انجام نشد؛ از ربات امتحان کنید."}""") { } as Receipts.Result.Failed
        assertEquals("تأیید انجام نشد؛ از ربات امتحان کنید.", ours.message)
    }

    @Test
    fun `a huge receipt photo is decoded at screen size`() {
        assertEquals(1, Receipts.sampleSize(1080, 1920))
        assertEquals(4, Receipts.sampleSize(3000, 4000))
        assertEquals(2, Receipts.sampleSize(1200, 1600))
    }

    @Test
    fun `reasons parse, and a blank code is dropped`() {
        val r = Receipts.parseReasons("""{"ok":true,"reasons":[{"code":"amount","label":"مبلغ اشتباه"},{"code":"","label":"x"}]}""")
        assertEquals(listOf(Receipts.Reason("amount", "مبلغ اشتباه")), r)
        assertTrue(Receipts.parseReasons("{}").isEmpty())
    }

    @Test
    fun `low battery is told once, and cleared when plugged in`() {
        assertEquals(Alerts.Decision.WARN, Alerts.batteryDecision(10, charging = false, warned = false))
        assertEquals(Alerts.Decision.NONE, Alerts.batteryDecision(10, charging = false, warned = true))
        assertEquals(Alerts.Decision.CLEAR, Alerts.batteryDecision(10, charging = true, warned = true))
        assertEquals(Alerts.Decision.CLEAR, Alerts.batteryDecision(35, charging = false, warned = true))
        assertEquals("between the bars: keep it", Alerts.Decision.NONE, Alerts.batteryDecision(20, charging = false, warned = true))
        assertEquals("unknown is not low", Alerts.Decision.NONE, Alerts.batteryDecision(-1, charging = false, warned = false))
    }

    @Test
    fun `the bot out of reach is told once, never before a first ping, and cleared when it answers`() {
        val now = 1_800_000_000_000L
        assertEquals(Alerts.Decision.WARN, Alerts.offlineDecision(true, now - 41 * 60_000, now, warned = false))
        assertEquals("the widget's 40 minutes, not 30", Alerts.Decision.NONE, Alerts.offlineDecision(true, now - 35 * 60_000, now, warned = false))
        assertEquals(Alerts.Decision.NONE, Alerts.offlineDecision(true, 0, now, warned = false))
        assertEquals(Alerts.Decision.CLEAR, Alerts.offlineDecision(true, now - 60_000, now, warned = true))
        assertEquals("unpaired", Alerts.Decision.CLEAR, Alerts.offlineDecision(false, 0, now, warned = true))
    }

    @Test
    fun `the approval notification reads well with or without its parts`() {
        assertEquals("۱۷۵٬۰۱۳ تومان — سفارش سارا", Alerts.approvedText(175013, "سارا"))
        assertEquals("سفارش سارا", Alerts.approvedText(0, "سارا"))
        assertEquals("یک سفارش", Alerts.approvedText(0, null))
    }

    @Test
    fun `a widget that never heard from the bot does not say it is online`() {
        assertEquals("📵 ارتباط قطع است", StatusWidget.lines(true, 1, 0, 0, 0, 1_800_000_000_000L).first)
    }

    // ── following the bot: decided there, gone here ─────────────────────

    @Test
    fun `a count read in the app only ever lowers the review notice`() {
        assertEquals("one decided in the bot: the notice says the new number at once",
            Alerts.Tell(false, 2, false, refresh = true), Alerts.tellFromApp(2, 3, true, false, 12))
        assertEquals("all decided in the bot: withdrawn", Alerts.Tell(false, 0, true), Alerts.tellFromApp(0, 3, true, false, 12))
        assertEquals("a new one is the background check's to announce: told stays put",
            Alerts.Tell(false, 1, false), Alerts.tellFromApp(4, 1, true, false, 12))
        assertFalse("never a sound from a list being read", Alerts.tellFromApp(5, 0, true, false, 12).notify)
        assertEquals("nothing told, nothing waiting: nothing to do", Alerts.Tell(false, 0, false), Alerts.tellFromApp(0, 0, true, false, 12))
    }

    private val page = Receipts.parsePage("""{"has_more":false,"items":[
        {"id":9,"state":"pending","amount":120000,"created_at":1792058814},
        {"id":10,"state":"approved","via":"auto","amount":5000,"created_at":1792058000},
        {"id":11,"kind":"deposit","state":"unmatched","amount":7000,"created_at":1792058000}
    ]}""")

    @Test
    fun `only a complete pending list is a pending count`() {
        assertEquals(1, ir.jirjirak.sms.ui.States.pendingFromList("pending", page.items, hasMore = false))
        assertEquals("an empty pending list is zero, not unknown", 0, ir.jirjirak.sms.ui.States.pendingFromList("pending", emptyList(), false))
        assertNull("a further page may hold more", ir.jirjirak.sms.ui.States.pendingFromList("pending", page.items, hasMore = true))
        assertNull("«همه» says nothing about the count", ir.jirjirak.sms.ui.States.pendingFromList("all", page.items, false))
    }

    @Test
    fun `a receipt that stopped waiting while this phone was not deciding was decided in the bot`() {
        assertTrue(ir.jirjirak.sms.ui.States.decidedElsewhere("pending", "approved", deciding = false))
        assertTrue(ir.jirjirak.sms.ui.States.decidedElsewhere("pending", "rejected", deciding = false))
        assertFalse("this phone's own decision", ir.jirjirak.sms.ui.States.decidedElsewhere("pending", "approved", deciding = true))
        assertFalse("still waiting", ir.jirjirak.sms.ui.States.decidedElsewhere("pending", "pending", false))
        assertFalse("already decided when opened", ir.jirjirak.sms.ui.States.decidedElsewhere("approved", "approved", false))
        assertFalse("first read", ir.jirjirak.sms.ui.States.decidedElsewhere(null, "approved", false))
    }

    @Test
    fun `the job pings only when the beat has gone quiet`() {
        val now = 1_792_058_814_000L
        assertFalse("the beat pinged a minute ago", Beat.pingDue(now - 60_000, now))
        assertFalse("Doze spaces the beat ~9 minutes", Beat.pingDue(now - 9 * 60_000, now))
        assertTrue("two beats missed", Beat.pingDue(now - 10 * 60_000, now))
        assertTrue("never pinged", Beat.pingDue(0, now))
        // The worst gap the job leaves (10 + its 15) stays under the bot's quiet threshold.
        assertTrue(10 * 60_000 + 15 * 60_000 < Alerts.OFFLINE_MS)
    }
}
