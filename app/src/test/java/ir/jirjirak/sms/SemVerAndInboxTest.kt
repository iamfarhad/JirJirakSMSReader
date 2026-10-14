package ir.jirjirak.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerAndInboxTest {

    // ── versions: an update must always be a HIGHER number ──────────────

    @Test
    fun `versions compare as numbers, not text`() {
        assertTrue(SemVer.isNewer("v1.10.0", "1.9.3"))
        assertTrue(SemVer.isNewer("1.0.1", "1.0.0"))
        assertTrue(SemVer.isNewer("2.0.0", "1.99.99"))
        assertFalse(SemVer.isNewer("1.0.0", "1.0.0"))
        assertFalse(SemVer.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun `a debug suffix is the same version, garbage is no version`() {
        assertFalse(SemVer.isNewer("1.0.0", "1.0.0-debug"))
        assertTrue(SemVer.isNewer("1.0.1", "1.0.0-debug"))
        assertNull(SemVer.parse("latest-build"))
        assertNull(SemVer.parse("1.0"))
        assertFalse("an unreadable tag never claims an update", SemVer.isNewer("nightly", "1.0.0"))
    }

    // ── «انتخاب از پیامک‌ها» ─────────────────────────────────────────────

    private fun sms(from: String, body: String, at: Long) = Inbox.Sms(from, body, at)

    @Test
    fun `one row per sender however it is written, banks first, then the newest`() {
        val rows = Inbox.group(
            listOf(
                sms("+989121234567", "سلام، فردا میای؟", 5),
                sms("09121234567", "باشه", 9),
                sms("9999", "واریز 250,000 ریال مانده 1,200,000", 3),
                sms("9999", "برداشت 50,000 ریال", 4),
                sms("Irancell", "بستهٔ اینترنت شما فعال شد", 8),
            ),
            alreadyAllowed = emptyList(),
        )

        assertEquals(listOf("9999", "09121234567", "Irancell"), rows.map { it.from })
        assertTrue(rows.first().bankish)
        assertEquals(2, rows.first().count)
        assertEquals(2, rows[1].count)
    }

    @Test
    fun `an allowed sender is not offered again, and a password shows no preview`() {
        val rows = Inbox.group(
            listOf(
                sms("9999", "واریز 250,000", 1),
                sms("98200", "رمز پویا: 123456", 2),
            ),
            alreadyAllowed = listOf("۹۹۹۹"),
        )

        assertEquals(listOf("98200"), rows.map { it.from })
        assertEquals("allowed as «+98200», the inbox's «98200» is the same sender", emptyList<String>(),
            Inbox.group(listOf(sms("98200", "واریز", 1)), alreadyAllowed = listOf("+98200")).map { it.from })
        assertNull("a one-time password is never shown", rows.single().preview)
        assertFalse("an OTP alone does not make a sender look like a bank", rows.single().bankish)
    }
}
