package ir.jirjirak.sms

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phases 2–4 on the phone, the pure halves: one SMS is one fingerprint however it is written, the
 * health test says what a seller must fix (and never mentions the inbox), a count is told once,
 * secrets are sealed, and the bot's reconciliation and format answers are read as written.
 */
class FingerprintAndHealthTest {

    // ── one SMS, one fingerprint ────────────────────────────────────────

    @Test
    fun `one SMS delivered twice, written two ways, is one fingerprint`() {
        val body = "واریز 1,000,000 ریال مانده 9,000,000"
        val stamp = 1_792_058_814_123L
        // One delivery says «+98999», the other «98999»; Persian digits in one copy.
        assertEquals(Rules.fingerprint("+98999", body, stamp), Rules.fingerprint("98999", body, stamp))
        assertEquals(Rules.fingerprint("09999", body, stamp), Rules.fingerprint("09999", "واریز ۱,۰۰۰,۰۰۰ ریال مانده ۹,۰۰۰,۰۰۰", stamp))
        assertEquals("milliseconds do not matter", Rules.fingerprint("9999", body, stamp), Rules.fingerprint("9999", body, stamp + 500))
    }

    @Test
    fun `two real deposits with the same text are two SMS`() {
        // A bank that prints neither balance nor time: the same amount on two days is two payments.
        val body = "واریز 250,347"
        assertNotEquals(Rules.fingerprint("9999", body, 1_792_000_000_000L), Rules.fingerprint("9999", body, 1_792_086_400_000L))
        assertNotEquals(Rules.fingerprint("9999", body, 1L), Rules.fingerprint("8888", body, 1L))
    }

    // ── the health test ─────────────────────────────────────────────────

    private val healthy = HealthCheck.Local(paired = true, smsPermission = true, batteryFree = true, senders = 1, queued = 0)

    @Test
    fun `a clean run says every step is fine`() {
        val body = JSONObject("""{"dry_run":true,"parsed":true,"amount":100000,"sender_allowed":true,"would_match":false,"auto_on":true}""")
        val steps = HealthCheck.steps(healthy, HealthCheck.Remote(200, null, body), sampleIsReal = true)

        assertEquals(HealthCheck.State.OK, HealthCheck.verdict(steps))
        assertTrue(steps.any { it.title == "خواندن پیامک بانک" && it.detail.contains("آخرین پیامک بانک شما") })
    }

    @Test
    fun `the step a seller must fix is named`() {
        val noPermission = HealthCheck.steps(healthy.copy(smsPermission = false), HealthCheck.Remote(200, null, JSONObject("""{"dry_run":true,"parsed":true}""")), true)
        assertEquals(HealthCheck.State.BAD, HealthCheck.verdict(noPermission))

        val revoked = HealthCheck.steps(healthy, HealthCheck.Remote(404, null, null), true)
        assertEquals(HealthCheck.State.BAD, revoked.last().state)
        assertTrue(revoked.last().detail.contains("کد اتصال باطل"))

        val unread = HealthCheck.steps(healthy, HealthCheck.Remote(200, null, JSONObject("""{"dry_run":true,"parsed":false,"note":"کلمهٔ واریز نبود","sender_allowed":true}""")), true)
        assertTrue(unread.first { it.title == "خواندن پیامک بانک" }.detail.contains("آموزش"))

        val gate = HealthCheck.steps(healthy, HealthCheck.Remote(200, null, JSONObject("""{"dry_run":true,"parsed":true,"sender_allowed":false}""")), true)
        assertEquals(HealthCheck.State.BAD, gate.first { it.title == "فرستندهٔ پیامک در ربات" }.state)
    }

    @Test
    fun `an older bot is told apart from a broken one`() {
        // Connected, but no dry run: the SMS test is skipped and said so — never sent as real money.
        val steps = HealthCheck.steps(healthy, HealthCheck.Remote(200, null, null), sampleIsReal = false)

        assertEquals(HealthCheck.State.OK, steps.first { it.title == "اتصال به ربات" }.state)
        assertEquals(HealthCheck.State.INFO, steps.last().state)
        assertEquals(HealthCheck.State.OK, HealthCheck.verdict(steps))
        assertEquals("unreachable stops at the connection", HealthCheck.State.BAD, HealthCheck.steps(healthy, HealthCheck.Remote(0, "اینترنت ندارد", null), true).last().state)
    }

    @Test
    fun `a dry run goes only to a bot that says it has one`() {
        assertTrue(HealthCheck.shouldDryRun(JSONObject("""{"features":["dry_run","devices"]}""")))
        assertFalse("an older bot would store the sample as money", HealthCheck.shouldDryRun(JSONObject("""{"shop":"x"}""")))
        assertFalse(HealthCheck.shouldDryRun(JSONObject("""{"features":["devices"]}""")))
        assertFalse(HealthCheck.shouldDryRun(JSONObject()))
    }

    @Test
    fun `the open mismatch count is the list's own`() {
        assertEquals(3, Receipts.parseSummary("""{"metrics":{},"open":{"review":1,"mismatch":3}}""").openMismatch)
        assertEquals("an older bot: from its actions", 2, Receipts.parseSummary("""{"metrics":{},"actions":[{"kind":"unmatched","count":1},{"kind":"unreadable","count":1},{"kind":"review","count":5}]}""").openMismatch)
    }

    // ── a count is told once ────────────────────────────────────────────

    @Test
    fun `a waiting count is told when it grows, not every quarter hour`() {
        assertTrue(Alerts.tell(count = 2, told = 0, enabled = true, quietNights = false, hour = 12).notify)
        assertFalse("the same two receipts are not news", Alerts.tell(2, 2, true, false, 12).notify)
        assertTrue("a third arrived", Alerts.tell(3, 2, true, false, 12).notify)
        assertEquals("one decided: the notice quietly says the new number", Alerts.Tell(false, 1, false, refresh = true), Alerts.tell(1, 3, true, false, 12))
        assertEquals("all decided: withdrawn", Alerts.Tell(false, 0, true), Alerts.tell(0, 3, true, false, 12))
        assertFalse("switched off", Alerts.tell(3, 0, false, false, 12).notify)
    }

    @Test
    fun `quiet nights hold the news for the morning`() {
        val night = Alerts.tell(2, 0, enabled = true, quietNights = true, hour = 2)
        assertFalse(night.notify)
        assertEquals("not marked told", 0, night.told)
        assertTrue("23:00 is night", !Alerts.tell(2, 0, true, true, 23).notify)
        assertTrue("08:00 the morning says it", Alerts.tell(2, night.told, true, true, 8).notify)
        assertTrue("without quiet nights the night speaks", Alerts.tell(2, 0, true, false, 2).notify)
    }

    // ── secrets at rest ─────────────────────────────────────────────────

    /** Reversible and not the identity: enough to prove the format and the migration. */
    private object FakeBox : Secrets.Box {
        override fun seal(plain: ByteArray) = byteArrayOf(9) + plain.reversedArray()
        override fun open(sealed: ByteArray): ByteArray {
            require(sealed.first() == 9.toByte())
            return sealed.copyOfRange(1, sealed.size).reversedArray()
        }
    }

    @Test
    fun `a secret is sealed at rest and opened back`() {
        val before = Secrets.box
        Secrets.box = FakeBox
        try {
            val key = "https://bot.example.com/api/bank-sms/AbCdEfGhIjKlMnOpQrSt"
            val stored = Secrets.seal(key)
            assertTrue(Secrets.isSealed(stored))
            assertFalse("the secret is not in the stored text", stored.contains("AbCdEf"))
            assertEquals(key, Secrets.open(stored))
            assertEquals("a 1.1 plaintext value is read as it is", key, Secrets.open(key))
            assertNull(Secrets.open(null))
            assertNull("a key that cannot open it is a fresh pairing, not a crash", Secrets.open("v1:zz"))
        } finally {
            Secrets.box = before
        }
    }

    // ── what the bot says back ──────────────────────────────────────────

    @Test
    fun `an assign answer is read as the bot meant it`() {
        assertEquals(Receipts.Assign.Done, Receipts.assignOutcome(200, """{"ok":true}"""))
        assertEquals(Receipts.Assign.NeedKey, Receipts.assignOutcome(401, ""))
        assertEquals(
            Receipts.Assign.AmountDiffers(89000, 90000),
            Receipts.assignOutcome(422, """{"ok":false,"error":"amount_differs","deposit_amount":89000,"expected":90000,"message":"مبلغ…"}"""),
        )
        // Settled first in the bot or on another phone: not an error, the screen reads the bot again.
        assertEquals(
            Receipts.Assign.AlreadyDecided,
            Receipts.assignOutcome(409, """{"ok":false,"error":"deposit_taken","message":"این واریز دیگر آزاد نیست؛ صفحه را تازه کنید."}"""),
        )
        // A server fault after the request went out may still have tied it: unknown, read it again.
        assertTrue((Receipts.assignOutcome(500, "Server Error") as Receipts.Assign.Unknown).message.contains("۵۰۰"))
    }

    @Test
    fun `candidates, formats and an unread sms are read whole`() {
        val (deposit, items) = Receipts.parseCandidates(
            """{"ok":true,"deposit":{"id":7,"kind":"deposit","state":"unmatched","amount":90000,"sender":"9999","created_at":1792058000},
               "items":[{"id":3,"state":"pending","amount":90000,"customer":"سارا","created_at":1792057000,"auto":null}]}""",
        )
        assertTrue(deposit.isDeposit)
        assertEquals(90000L, deposit.amount)
        assertEquals(listOf(3L), items.map { it.id })

        val formats = Receipts.parseFormats("""{"ok":true,"formats":[{"id":1,"sender":"+98999","sample":"x","unit":"rial","amount_slot":1,"balance_slot":null,"hits":4,"last_hit_at":1792058000}]}""")
        assertNull(formats.single().balanceSlot)
        assertEquals(4, formats.single().hits)
        assertEquals(1792058000000L, formats.single().lastHit)

        val unread = Receipts.parsePage(
            """{"ok":true,"has_more":false,"items":[{"id":9,"kind":"deposit","state":"unparsed","amount":null,"sender":"Bank","created_at":1,
               "raw":"مبلغ 1,200,000 مانده 5,000","numbers":[{"slot":0,"text":"1,200,000"},{"slot":1,"text":"5,000"}],"learned":false}]}""",
        ).items.single()
        assertEquals("مبلغ 1,200,000 مانده 5,000", unread.raw)
        assertEquals(listOf("1,200,000", "5,000"), unread.numbers.map { it.text })
    }

    @Test
    fun `the dashboard reads every phone and marks this one`() {
        val s = Receipts.parseSummary(
            """{"ok":true,"metrics":{},"previous":{},"health":{"devices":[
               {"model":"Samsung","at":1792058000,"quiet":false,"senders":2,"version":"1.2.0","this":true},
               {"model":"Xiaomi","at":1792050000,"quiet":true,"senders":1,"version":"1.1.2","this":false}]}}""",
        )
        assertEquals(listOf("Samsung", "Xiaomi"), s.devices.map { it.model })
        assertArrayEquals(booleanArrayOf(true, false), s.devices.map { it.mine }.toBooleanArray())
        assertTrue(s.devices[1].quiet)
        assertEquals(1792058000000L, s.devices[0].at)
    }

    @Test
    fun `the health test has no inbox step, the app never reads the inbox`() {
        val steps = HealthCheck.steps(healthy, null, false)
        assertTrue(steps.none { it.title.contains("بازیابی") || it.detail.contains("صندوق") })
    }
}
