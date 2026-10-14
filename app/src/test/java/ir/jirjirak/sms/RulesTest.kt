package ir.jirjirak.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What leaves the phone. The app reads SMS, so every rule here is a line nothing may cross. */
class RulesTest {

    private val url = "https://bot.example.com/api/bank-sms/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcd"

    @Test
    fun `the bot's code pairs, anything else on the clipboard does not`() {
        assertEquals(url, Rules.parseCode("jirjirak-sms:$url"))
        assertEquals(url, Rules.parseCode("  $url \n"))
        assertNull(Rules.parseCode("vless://uuid@host:443"))
        assertNull(Rules.parseCode("6037991234567890"))
        assertNull(Rules.parseCode("https://evil.example.com/steal?x=/api/bank-sms/short"))
        assertNull(Rules.parseCode(null))
    }

    @Test
    fun `one number however it is spelled`() {
        assertEquals("09121234567", Rules.normalize("+989121234567"))
        assertEquals("09121234567", Rules.normalize("00989121234567"))
        assertEquals("09121234567", Rules.normalize("0912 123 4567"))
        assertEquals("9999", Rules.normalize("9999"))
        assertEquals("bankmellat", Rules.normalize("BankMellat"))
    }

    @Test
    fun `nothing is forwarded until the seller allows a sender`() {
        val deposit = "واریز 250,000 ریال\nمانده 1,200,000"
        assertFalse(Rules.shouldForward("9999", deposit, emptyList()))
        assertTrue(Rules.shouldForward("9999", deposit, listOf("9999")))
        assertTrue(Rules.shouldForward("+989121234567", deposit, listOf("09121234567")))
        assertFalse(Rules.shouldForward("9998", deposit, listOf("9999")), "a neighbour number is not the same sender")
    }

    @Test
    fun `a one-time password never leaves the phone, even from an allowed sender`() {
        assertFalse(Rules.shouldForward("9999", "رمز پویا: 123456 مبلغ 250,000", listOf("9999")))
        assertFalse(Rules.shouldForward("9999", "کد تایید شما 4821", listOf("9999")))
        assertFalse(Rules.shouldForward("9999", "Your OTP is 123456", listOf("9999")))
    }

    @Test
    fun `a bank-looking SMS can be suggested, a personal one is not`() {
        assertTrue(Rules.looksLikeBank("واریز 50,000 به حساب"))
        assertFalse(Rules.looksLikeBank("سلام، فردا میای؟"))
    }

    private fun assertFalse(condition: Boolean, message: String) = org.junit.Assert.assertFalse(message, condition)
}
