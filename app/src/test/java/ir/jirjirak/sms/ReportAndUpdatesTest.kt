package ir.jirjirak.sms

import ir.jirjirak.sms.ui.Jalali
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the log, the dates it shows, and how an update is found. */
class ReportAndUpdatesTest {

    // ── the Iranian calendar ────────────────────────────────────────────

    @Test
    fun `known days convert`() {
        assertEquals(Jalali(1405, 7, 23), Jalali.fromGregorian(2026, 10, 15))   // the Bank Melli SMS's «07/23»
        assertEquals(Jalali(1403, 1, 1), Jalali.fromGregorian(2024, 3, 20))     // Nowruz 1403
        assertEquals(Jalali(1404, 1, 1), Jalali.fromGregorian(2025, 3, 21))     // Nowruz 1404
        assertEquals(Jalali(1402, 12, 30), Jalali.fromGregorian(2024, 3, 19))   // a leap Esfand
        assertEquals(Jalali(1404, 10, 11), Jalali.fromGregorian(2026, 1, 1))
        assertEquals("۲۳ مهر ۱۴۰۵", Jalali.fromGregorian(2026, 10, 15).full())
    }

    // ── what the bot said about a delivered SMS ─────────────────────────

    @Test
    fun `the bot's reply becomes a sentence the seller can act on`() {
        assertTrue(Net.verdict("""{"ok":true,"duplicate":true,"status":"matched"}""").contains("تکراری"))
        assertTrue(Net.verdict("""{"ok":true,"parsed":false,"status":"unparsed"}""").contains("نتوانست"))
        val approved = Net.verdict("""{"ok":true,"parsed":true,"status":"matched","approved":true,"amount":175013}""")
        assertTrue(approved, approved.contains("تأیید شد") && approved.contains("۱۷۵٬۰۱۳ تومان"))
        val held = Net.verdict("""{"ok":true,"parsed":true,"status":"matched","approved":false,"amount":90272}""")
        assertTrue(held, held.contains("بررسی مدیر") && !held.contains("تأیید شد"))
        assertTrue(Net.verdict("""{"ok":true,"parsed":true,"status":"unmatched","amount":5000}""").contains("فاکتوری با این مبلغ پیدا نشد"))
        assertEquals("رسید", Net.verdict("not json"))
    }

    @Test
    fun `money reads as Persian thousands`() {
        assertEquals("۱۷۵٬۰۱۳ تومان", Money.toman(175013))
        assertEquals("۵۰۰ تومان", Money.toman(500))
        assertEquals("۱٬۰۰۰٬۰۰۰ تومان", Money.toman(1_000_000))
    }

    // ── which failures are worth a retry ────────────────────────────────

    @Test
    fun `no answer, a server fault and a throttle retry; a refusal of this message does not`() {
        assertTrue(Net.Reply(0, "", "timeout").retryable)
        assertTrue(Net.Reply(503, "").retryable)
        assertTrue(Net.Reply(429, "").retryable)
        assertFalse("413 for ever is not a queue, it is a cork", Net.Reply(413, "").retryable)
        assertFalse(Net.Reply(422, "").retryable)
        assertTrue(Net.Reply(404, "").revoked)
        assertTrue(Net.Reply(403, "").revoked)
        assertFalse(Net.Reply(404, "").retryable)
        assertEquals("timeout", Net.Reply(0, "", "timeout").reason)
    }

    // ── finding the latest release without the rate-limited API ─────────

    @Test
    fun `the releases page redirect names the version and the stable APK address`() {
        val latest = Updates.parseRedirect("https://github.com/iamfarhad/JirJirakSMSReader/releases/tag/v1.2.3")
        assertEquals("1.2.3", latest?.version)
        assertEquals("https://github.com/iamfarhad/JirJirakSMSReader/releases/download/v1.2.3/JirjirakSMS.apk", latest?.url)
        assertNull("no release yet: the page redirects to the list", Updates.parseRedirect("https://github.com/iamfarhad/JirJirakSMSReader/releases"))
        assertNull(Updates.parseRedirect(null))
    }
}
