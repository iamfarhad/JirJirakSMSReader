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
}
