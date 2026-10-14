package ir.jirjirak.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What leaves the phone. The app reads SMS, so every rule here is a line nothing may cross. */
class RulesTest {

    private val secret = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcd"
    private val url = "https://bot.example.com/api/bank-sms/$secret"

    // ── pairing ─────────────────────────────────────────────────────────

    @Test
    fun `the bot's code pairs, anything else on the clipboard does not`() {
        assertEquals(url, Rules.parseCode("jirjirak-sms:$url"))
        assertEquals(url, Rules.parseCode("  $url \n"))
        assertEquals("https://bot.example.com:8443/api/bank-sms/$secret", Rules.parseCode("https://bot.example.com:8443/api/bank-sms/$secret"))
        assertNull(Rules.parseCode("vless://uuid@host:443"))
        assertNull(Rules.parseCode("6037991234567890"))
        assertNull(Rules.parseCode(null))
    }

    @Test
    fun `plain http, a smuggled query and a wrong-length secret are refused`() {
        assertNull("cleartext is blocked by Android anyway", Rules.parseCode("http://bot.example.com/api/bank-sms/$secret"))
        assertNull(Rules.parseCode("https://evil.example.com?/api/bank-sms/$secret"))
        assertNull(Rules.parseCode("https://evil.example.com/steal?x=/api/bank-sms/$secret"))
        assertNull(Rules.parseCode("https://bot.example.com/api/bank-sms/" + "a".repeat(19)))
        assertNull(Rules.parseCode("https://bot.example.com/api/bank-sms/" + "a".repeat(81)))
    }

    // ── senders ─────────────────────────────────────────────────────────

    @Test
    fun `one number however it is spelled`() {
        assertEquals("09121234567", Rules.normalize("+989121234567"))
        assertEquals("09121234567", Rules.normalize("00989121234567"))
        assertEquals("09121234567", Rules.normalize("989121234567"))
        assertEquals("09121234567", Rules.normalize("0912 123 4567"))
        assertEquals("09121234567", Rules.normalize("(0912) 123-4567"))
        assertEquals("9999", Rules.normalize("9999"))
        assertEquals("bankmellat", Rules.normalize("BankMellat"))
    }

    @Test
    fun `a number typed on a Persian keyboard is the number the phone reports`() {
        assertEquals("9999", Rules.normalize("۹۹۹۹"))
        assertEquals("09121234567", Rules.normalize("۰۹۱۲۱۲۳۴۵۶۷"))
        assertEquals("9999", Rules.normalize("٩٩٩٩"))
        assertTrue(Rules.isAllowed("9999", listOf("۹۹۹۹")))
    }

    @Test
    fun `a country code written either way is one sender`() {
        assertEquals(Rules.normalize("+989999"), Rules.normalize("00989999"))
        assertEquals("98200", Rules.normalize("98200"), "a short code that merely starts with 98 is left alone")
    }

    @Test
    fun `an empty or punctuation-only sender is never allowed`() {
        assertFalse(Rules.isAllowed("", listOf("")))
        assertFalse(Rules.isAllowed(" ", listOf(" ")))
        assertFalse(Rules.isAllowed("-", listOf("+")))
    }

    // ── what is forwarded ───────────────────────────────────────────────

    @Test
    fun `nothing is forwarded until the seller allows a sender`() {
        val deposit = "واریز 250,000 ریال\nمانده 1,200,000"
        assertFalse(Rules.shouldForward("9999", deposit, emptyList()))
        assertTrue(Rules.shouldForward("9999", deposit, listOf("9999")))
        assertTrue(Rules.shouldForward("+989121234567", deposit, listOf("09121234567")))
        assertFalse("a neighbour number is not the same sender", Rules.shouldForward("9998", deposit, listOf("9999")))
    }

    @Test
    fun `a one-time password never leaves the phone, even from an allowed sender`() {
        for (otp in listOf(
            "رمز پویا: 123456 مبلغ 250,000",
            "کد تایید شما 4821",
            "كد تاييد شما 4821",            // Arabic kaf and yeh, as many banks send
            "رمز یک‌بار مصرف شما 5521",       // with a zero-width non-joiner
            "رمز دوم پویا 123456",
            "کد ورود: 5521",
            "رمز: 884412",
            "Your OTP is 123456",
            "Your code: 1234",
        )) {
            assertTrue(otp, Rules.isSecret(otp))
            assertFalse(otp, Rules.shouldForward("9999", otp, listOf("9999")))
        }
    }

    @Test
    fun `a real deposit is not mistaken for a password`() {
        for (deposit in listOf(
            "واریز پایا از پویا احمدی مبلغ 2,000,000 ریال مانده 5,000,000",
            "واریز 500,000 ریال مانده 9,200,000\nرمز خود را در اختیار دیگران قرار ندهید",
            "خرید رمزارز 1500000 ریال",
        )) {
            assertFalse(deposit, Rules.isSecret(deposit))
            assertTrue(deposit, Rules.shouldForward("9999", deposit, listOf("9999")))
        }
    }

    @Test
    fun `a bank-looking SMS can be suggested, a personal one is not`() {
        assertTrue(Rules.looksLikeBank("واریز 50,000 به حساب"))
        assertTrue("Arabic yeh", Rules.looksLikeBank("واريز 50,000"))
        assertFalse(Rules.looksLikeBank("سلام، فردا میای؟"))
    }

    private fun assertEquals(expected: String, actual: String, message: String) =
        org.junit.Assert.assertEquals(message, expected, actual)
}
