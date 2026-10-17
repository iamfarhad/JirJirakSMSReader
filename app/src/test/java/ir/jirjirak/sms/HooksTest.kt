package ir.jirjirak.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** The seller's own webhooks: which address is one, which SMS go there, and what the request says. */
class HooksTest {

    private val panel = "https://cardholder.example.cloud/Bank/Message?userId=24057271"

    @Test
    fun `a full https address with its parameters is kept as typed`() {
        assertEquals(panel, Hooks.parseUrl(panel))
        assertEquals(panel, Hooks.parseUrl("  $panel \n"))
        assertEquals("https://hook.example.com:8443/in", Hooks.parseUrl("https://hook.example.com:8443/in"))
    }

    @Test
    fun `cleartext to the internet, a missing scheme and garbage are refused`() {
        assertNull("bank SMS never cross the internet in clear", Hooks.parseUrl("http://cardholder.example.cloud/Bank/Message?userId=1"))
        assertNull(Hooks.parseUrl("cardholder.example.cloud/Bank/Message"))
        assertNull(Hooks.parseUrl("ftp://example.com/x"))
        assertNull(Hooks.parseUrl("https://"))
        assertNull(Hooks.parseUrl("https://exa mple.com/x"))
        assertNull(Hooks.parseUrl(""))
        assertNull(Hooks.parseUrl(null))
    }

    @Test
    fun `plain http only to this phone itself`() {
        assertEquals("http://127.0.0.1:8080/hook", Hooks.parseUrl("http://127.0.0.1:8080/hook"))
        assertEquals("http://localhost/hook", Hooks.parseUrl("http://localhost/hook"))
    }

    @Test
    fun `an SMS goes to the switched-on webhooks of its sender, in any spelling`() {
        val mellat = Hook("a", panel, "+98200")
        val maskan = Hook("b", panel, "BankMaskan")
        val off = Hook("c", panel, "98200", on = false)
        val hooks = listOf(mellat, maskan, off)
        assertEquals(listOf(mellat), Hooks.matching("98200", hooks))
        assertEquals(listOf(mellat), Hooks.matching("+98200", hooks))
        assertEquals(listOf(maskan), Hooks.matching("bankmaskan", hooks))
        assertTrue(Hooks.matching("9999", hooks).isEmpty())
    }

    @Test
    fun `persian digits in the saved sender meet the phone's ascii ones`() {
        val hook = Hook("a", panel, "۹۸۲۰۰")
        assertEquals(listOf(hook), Hooks.matching("+98200", listOf(hook)))
    }

    @Test
    fun `the request is a form with the SMS text in Message, exactly, and the sender`() {
        val text = "بانک ملت\nواریز: 1,250,000+\nمانده: 3,400,000\n1405/07/25-11:34 & co"
        val form = Hooks.form(text, "+98200")
        val fields = form.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        assertEquals(setOf("Message", "from"), fields.keys)
        assertEquals("the text arrives byte for byte, line breaks and the ampersand included", text, fields["Message"])
        assertEquals("+98200", fields["from"])
    }

    @Test
    fun `one SMS has one seen key per target, so the bot and each webhook get it once`() {
        val fp = Rules.fingerprint("98200", "واریز", 1_700_000_000_000)
        assertNotEquals(fp, Hooks.seenKey(fp, "a"))
        assertNotEquals(Hooks.seenKey(fp, "a"), Hooks.seenKey(fp, "b"))
        assertEquals(Hooks.seenKey(fp, "a"), Hooks.seenKey(Rules.fingerprint("+98200", "واریز", 1_700_000_000_400), "a"))
    }

    @Test
    fun `a webhook's refusal is named as an address problem, never as a revoked pairing`() {
        assertTrue(Net.hookReason(Net.Reply(404, "")).contains("آدرس وب‌هوک پیدا نشد"))
        assertTrue(Net.hookReason(Net.Reply(403, "")).contains("اجازه نداد"))
        assertTrue(Net.hookReason(Net.Reply(500, "")).contains("خطای سرور وب‌هوک"))
        assertTrue(Net.hookReason(Net.Reply(0, "", "به سرور وب‌هوک وصل نشد")).contains("وب‌هوک"))
        assertTrue(Net.hookReason(Net.Reply(422, "")).contains("نپذیرفت (422)"))
    }

    @Test
    fun `the host is what the list shows`() {
        assertEquals("cardholder.example.cloud", Hook("a", panel, "1").host)
    }
}
