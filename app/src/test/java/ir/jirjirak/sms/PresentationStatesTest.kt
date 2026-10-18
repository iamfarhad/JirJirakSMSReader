package ir.jirjirak.sms

import ir.jirjirak.sms.ui.States
import ir.jirjirak.sms.ui.teachableAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The redesign's states are decided from real data; these lock the ones that could mislead a seller. */
class PresentationStatesTest {

    private val now = 1_800_000_000_000L

    @Test fun `the worst state wins the dashboard headline`() {
        assertEquals(States.HomeHero.REVOKED, States.homeHero(revoked = true, smsGranted = false, senders = 0, online = false, pending = 3, autoOn = true))
        assertEquals(States.HomeHero.NO_SMS, States.homeHero(false, smsGranted = false, senders = 2, online = true, pending = 0, autoOn = true))
        assertEquals(States.HomeHero.NO_SENDERS, States.homeHero(false, true, senders = 0, online = true, pending = 0, autoOn = true))
        assertEquals(States.HomeHero.OFFLINE, States.homeHero(false, true, 1, online = false, pending = 5, autoOn = true))
        assertEquals(States.HomeHero.QUEUE, States.homeHero(false, true, 1, true, pending = 2, autoOn = true))
    }

    @Test fun `auto approval is claimed only when the bot says it is on`() {
        assertEquals(States.HomeHero.AUTO_ON, States.homeHero(false, true, 1, true, 0, autoOn = true))
        assertEquals(States.HomeHero.AUTO_OFF, States.homeHero(false, true, 1, true, 0, autoOn = false))
        // No manage key, no summary: forwarding is all that is known.
        assertEquals(States.HomeHero.FORWARDING, States.homeHero(false, true, 1, true, 0, autoOn = null))
    }

    @Test fun `never pinged is not online`() {
        assertFalse(States.online(0, now))
        assertTrue(States.online(now - 60_000, now))
        assertFalse(States.online(now - States.ONLINE_MS - 1, now))
    }

    @Test fun `loading is not zero and a failure is not an empty result`() {
        assertEquals(States.Stats.NO_KEY, States.stats(hasKey = false, hasSummary = true, lastFailed = false, fetchedAt = now, now = now))
        assertEquals(States.Stats.LOADING, States.stats(true, hasSummary = false, lastFailed = false, fetchedAt = 0, now = now))
        assertEquals(States.Stats.ERROR, States.stats(true, hasSummary = false, lastFailed = true, fetchedAt = 0, now = now))
        assertEquals(States.Stats.FRESH, States.stats(true, true, false, now - 60_000, now))
    }

    @Test fun `old figures or a failed refresh are labelled stale, not passed off as now`() {
        assertEquals(States.Stats.STALE, States.stats(true, true, lastFailed = false, fetchedAt = now - States.STALE_MS - 1, now = now))
        assertEquals(States.Stats.STALE, States.stats(true, true, lastFailed = true, fetchedAt = now - 1_000, now = now))
    }

    @Test fun `a ping outcome maps to its own pairing state`() {
        assertEquals(States.Pair.REVOKED, States.pairAfter(Net.Ping.REVOKED))
        assertEquals(States.Pair.OFFLINE, States.pairAfter(Net.Ping.UNREACHABLE))
    }

    @Test fun `setup counts the two essentials apart from the extras`() {
        val s = States.setup(smsGranted = true, senders = 0, notifications = false, batteryFree = true, autostartNeeded = true, autostartConfirmed = false)
        assertEquals(1, s.essentialDone)
        assertEquals(2, s.essentialTotal)
        assertEquals(1, s.extrasDone)
        assertEquals(3, s.extrasTotal)
        assertFalse(s.ready)
        // Notifications refused do not make the phone «not ready»: SMS still flows.
        assertTrue(States.setup(true, 1, notifications = false, batteryFree = false, autostartNeeded = false, autostartConfirmed = false).ready)
    }

    @Test fun `the restricted settings guide only where Android has that lock`() {
        assertFalse(States.showRestrictedGuide(sdk = 30, asked = true, granted = false))
        assertFalse(States.showRestrictedGuide(sdk = 34, asked = false, granted = false))
        assertFalse(States.showRestrictedGuide(sdk = 34, asked = true, granted = true))
        assertTrue(States.showRestrictedGuide(sdk = 33, asked = true, granted = false))
    }

    @Test fun `a queued SMS says waiting, retrying or throttled`() {
        assertEquals(States.QueueRow.QUEUED, States.queueRow("queued", "در صف ارسال به ربات"))
        assertEquals(States.QueueRow.RETRY, States.queueRow("retry", "فرستاده نشد: خطای سرور ربات (502) — دوباره تلاش می‌شود"))
        assertEquals(States.QueueRow.THROTTLED, States.queueRow("retry", "فرستاده نشد: ربات گفت آهسته‌تر (محدودیت تعداد درخواست) — دوباره تلاش می‌شود"))
    }

    @Test fun `the unpair warning names the real queue and that it is deleted`() {
        val text = States.unpairText(4)
        assertTrue(text.contains("۴ پیامک"))
        assertTrue(text.contains("حذف"))
        assertTrue(text.contains("منتقل نمی‌شوند"))
        assertFalse(States.unpairText(0).contains("۰ پیامک"))
    }

    // ── money: an unclear answer is unknown, not a success and not a failure ──

    @Test fun `a decision with no reply is unknown`() {
        assertEquals(Receipts.Decision.Done, Receipts.decisionOutcome(200, "{}"))
        assertTrue(Receipts.decisionOutcome(0, "ربات دیر جواب داد") is Receipts.Decision.Unknown)
        assertTrue(Receipts.decisionOutcome(504, "") is Receipts.Decision.Unknown)
        assertTrue(Receipts.decisionOutcome(500, "") is Receipts.Decision.Unknown)
    }

    @Test fun `a decision someone else took refreshes, a refusal says why`() {
        assertEquals(Receipts.Decision.AlreadyDecided, Receipts.decisionOutcome(409, "{}"))
        assertEquals(Receipts.Decision.NeedKey, Receipts.decisionOutcome(401, ""))
        // 403 is the subscription and 404 the pairing: never «paste the key again».
        assertTrue((Receipts.decisionOutcome(403, "") as Receipts.Decision.Failed).message.contains("اشتراک"))
        assertTrue((Receipts.decisionOutcome(404, "") as Receipts.Decision.Failed).message.contains("نمی‌شناسد"))
        assertTrue(Receipts.decisionOutcome(429, "") is Receipts.Decision.Failed)
    }

    @Test fun `tying a deposit with no reply is unknown too`() {
        assertTrue(Receipts.assignOutcome(0, "ربات دیر جواب داد") is Receipts.Assign.Unknown)
        assertTrue(Receipts.assignOutcome(502, "") is Receipts.Assign.Unknown)
    }

    @Test fun `a pasted manage code is judged by the bot, three refusals apart`() {
        assertEquals(Receipts.KeyCheck.OK, Receipts.keyCheck(200))
        assertEquals(Receipts.KeyCheck.WRONG, Receipts.keyCheck(401))
        assertEquals(Receipts.KeyCheck.FORBIDDEN, Receipts.keyCheck(403))
        assertEquals(Receipts.KeyCheck.NOT_FOUND, Receipts.keyCheck(404))
        assertEquals(Receipts.KeyCheck.OFFLINE, Receipts.keyCheck(0))
        assertEquals(Receipts.KeyCheck.FAILED, Receipts.keyCheck(500))
    }

    @Test fun `a signed number cannot be taught as a deposit`() {
        assertTrue(teachableAmount("1,500,000"))
        assertFalse(teachableAmount("-1,500,000"))
        assertFalse(teachableAmount("−250000"))
        assertFalse(teachableAmount("250000-"))
        assertFalse(teachableAmount(""))
    }

    // ── health: a test that did not run is not a healthy result ──

    private val local = HealthCheck.Local(paired = true, smsPermission = true, batteryFree = true, senders = 1, queued = 0)

    @Test fun `an old bot without a dry run gives a partial result, not a healthy one`() {
        val steps = HealthCheck.steps(local, HealthCheck.Remote(200, null, null), sampleIsReal = false)
        assertEquals(HealthCheck.State.OK, HealthCheck.verdict(steps))
        assertEquals(HealthCheck.Outcome.PARTIAL, HealthCheck.outcome(steps))
        assertTrue(steps.any { it.key == "dry_run_missing" })
    }

    @Test fun `a dry run that read the SMS is a healthy result`() {
        val body = org.json.JSONObject().put("dry_run", true).put("parsed", true).put("amount", 50000).put("sender_allowed", true).put("auto_on", true)
        val steps = HealthCheck.steps(local, HealthCheck.Remote(200, null, body), sampleIsReal = true)
        assertEquals(HealthCheck.Outcome.GOOD, HealthCheck.outcome(steps))
    }

    @Test fun `a revoked pairing is bad whatever else is fine`() {
        val steps = HealthCheck.steps(local, HealthCheck.Remote(404, null, null), sampleIsReal = false)
        assertEquals(HealthCheck.Outcome.BAD, HealthCheck.outcome(steps))
    }
}

class BidiTest {
    @Test fun `latin tokens inside persian text are isolated once`() {
        val out = ir.jirjirak.sms.ui.Persian.bidi("برنامه به‌روز شد: نسخهٔ 1.2.1-debug")
        assertTrue(out.contains("⁦1.2.1-debug⁩"))
        assertEquals(out, ir.jirjirak.sms.ui.Persian.bidi(out))
        assertEquals("فقط فارسی ۱۲", ir.jirjirak.sms.ui.Persian.bidi("فقط فارسی ۱۲"))
    }
}
