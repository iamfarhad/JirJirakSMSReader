package ir.jirjirak.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dashboard, the receipts centre and a receipt's page read exactly what the bot sends. */
class InsightsParsersTest {

    @Test
    fun `the dashboard summary reads figures, actions, the week and the banks`() {
        val body = """{"ok":true,"range":"7d","from":1792000000,"auto_on":true,
            "level":{"threshold":50,"label":"آسان"},
            "metrics":{"auto_approved":12,"manual_approved":3,"rejected":1,"deposits_count":20,"deposits_total":48000000,"unmatched":2,"auto_rate":80,"avg_auto_seconds":14,"needs_review":4},
            "previous":{"auto_approved":6,"manual_approved":5,"rejected":0,"deposits_count":11,"deposits_total":20000000,"unmatched":0,"auto_rate":55,"avg_auto_seconds":0},
            "actions":[{"kind":"review","count":4,"title":"4 رسید در انتظار بررسی","hint":"نیاز به تأیید شما"}],
            "health":{"server":true,"last_ping":null,"senders":2,"sms_permission":null,"pending_on_phone":0},
            "series":[{"date":"2026-10-14","auto":5,"manual":1,"total":9000000},{"date":"2026-10-15","auto":7,"manual":2,"total":11000000}],
            "banks":[{"sender":"+98999","count":9,"total":30000000}],
            "recent":[{"kind":"auto_approved","at":1792058814,"amount":120000,"customer":"Sara","id":7},
                      {"kind":"unmatched","at":1792058000,"amount":50000,"customer":null,"sender":"+98999","id":3}]}"""
        val s = Receipts.parseSummary(body)
        assertTrue(s.autoOn)
        assertEquals("آسان", s.levelLabel)
        assertEquals(12, s.metrics.autoApproved)
        assertEquals(4, s.metrics.needsReview)
        assertEquals(48_000_000L, s.metrics.depositsTotal)
        assertEquals(80, s.metrics.autoRate)
        assertEquals(6, s.previous.autoApproved)
        assertEquals(0, s.previous.needsReview)
        assertEquals("review", s.actions.single().kind)
        assertEquals(4, s.actions.single().count)
        assertEquals(2, s.series.size)
        assertEquals(7, s.series[1].auto)
        assertEquals(11_000_000L, s.series[1].total)
        assertEquals("+98999", s.banks.single().sender)
        assertEquals(1792058814000L, s.recent[0].at)
        assertNull("JSON null is not the text «null»", s.recent[1].customer)
        assertEquals("+98999", s.recent[1].sender)
    }

    @Test
    fun `an empty shop's summary is zeros, not a crash`() {
        val s = Receipts.parseSummary("""{"ok":true,"auto_on":false,"metrics":{},"previous":{},"actions":[],"series":[],"banks":[],"recent":[]}""")
        assertFalse(s.autoOn)
        assertEquals(0, s.metrics.autoApproved)
        assertTrue(s.actions.isEmpty())
        assertTrue(s.series.isEmpty())
        assertEquals("", s.levelLabel)
    }

    @Test
    fun `a history page tells receipts from deposits and keeps the paging flag`() {
        val body = """{"ok":true,"filter":"all","page":1,"has_more":true,"items":[
            {"id":9,"state":"pending","via":null,"amount":120234,"rail":"کارت‌به‌کارت","customer":"Sara","username":"sara","title":"۱ ماهه","tracking":"7E6A","created_at":1792058814,"decided_at":null,"has_image":true,
             "reason":"امتیاز ۵۰ از ۷۰","auto":{"score":50,"threshold":70,"enough":false,"why":[]}},
            {"id":10,"state":"approved","via":"auto","amount":5000,"rail":"کارت‌به‌کارت","customer":null,"username":null,"title":null,"tracking":null,"created_at":1792058000,"decided_at":1792058010,"has_image":false,"reason":null,"auto":null}
        ]}"""
        val p = Receipts.parsePage(body)
        assertTrue(p.hasMore)
        assertEquals(2, p.items.size)
        val pending = p.items[0]
        assertFalse(pending.isDeposit)
        assertEquals("pending", pending.state)
        assertNull(pending.via)
        assertEquals(50, pending.score)
        assertEquals(70, pending.threshold)
        assertTrue(pending.hasImage)
        assertEquals("امتیاز ۵۰ از ۷۰", pending.reason)
        assertEquals("auto", p.items[1].via)
        assertNull("no score was given — not a zero", p.items[1].score)
        assertNull(p.items[1].customer)
    }

    @Test
    fun `a mismatch page is deposits, an unreadable one has no amount`() {
        val body = """{"ok":true,"filter":"mismatch","page":2,"has_more":false,"items":[
            {"id":3,"kind":"deposit","state":"unmatched","amount":50000,"balance":900000,"sender":"+98999","card_tail":"1234","occurred_at":null,"created_at":1792058000,"seen":1,"note":null,"payment_id":null},
            {"id":4,"kind":"deposit","state":"unparsed","amount":null,"balance":null,"sender":"Bank","card_tail":null,"occurred_at":null,"created_at":1792057000,"seen":1,"note":"قالب ناشناخته","payment_id":null}
        ]}"""
        val p = Receipts.parsePage(body)
        assertFalse(p.hasMore)
        assertTrue(p.items.all { it.isDeposit })
        assertEquals(50_000L, p.items[0].amount)
        assertNull("an unreadable SMS has no amount, not a zero one", p.items[1].amount)
        assertEquals("unparsed", p.items[1].state)
        assertEquals("قالب ناشناخته", p.items[1].note)
        assertEquals(1792057000000L, p.items[1].at)
    }

    @Test
    fun `a receipt's page carries the score's signals, the suggestion and the timeline`() {
        val body = """{"ok":true,
            "receipt":{"id":9,"state":"pending","via":null,"amount":120000,"rail":"کارت‌به‌کارت","customer":"Sara","title":"۱ ماهه","tracking":"7E6A","created_at":1792058814,"has_image":false,"reason":null,"auto":{"score":50,"threshold":70,"enough":false,"why":[]}},
            "deposit":{"id":3,"kind":"deposit","state":"matched","amount":120000,"sender":"+98999","created_at":1792058900,"note":null},
            "analysis":{"score":50,"threshold":70,"enough":false,
              "signals":[{"key":"customer","label":"سابقهٔ مشتری (۳ خرید)","ok":true,"weight":50,"earned":50},
                         {"key":"chain","label":"زنجیرهٔ مانده","ok":false,"weight":30,"earned":0}],
              "lines":[],"why":["زنجیرهٔ مانده تأیید نشد"]},
            "suggestion":{"tone":"warn","title":"بررسی دستی توصیه می‌شود","text":"زنجیره تأیید نشد."},
            "timeline":[{"at":1792058814,"title":"فاکتور ساخته شد","tone":"info"},{"at":1792058900,"title":"نیاز به بررسی دستی (امتیاز 50)","tone":"warn"}]}"""
        val d = Receipts.parseDetail(body)
        assertEquals(9L, d.item.id)
        assertFalse("the receipt is not a deposit", d.item.isDeposit)
        assertTrue(d.deposit!!.isDeposit)
        assertEquals(120_000L, d.deposit!!.amount)
        assertEquals(50, d.score)
        assertEquals(70, d.threshold)
        assertFalse(d.enough)
        assertEquals(2, d.signals.size)
        assertEquals(50, d.signals[0].earned)
        assertFalse(d.signals[1].ok)
        assertEquals(listOf("زنجیرهٔ مانده تأیید نشد"), d.why)
        assertEquals("warn", d.suggestionTone)
        assertEquals("زنجیره تأیید نشد.", d.suggestionText)
        assertEquals(2, d.timeline.size)
        assertEquals(1792058900000L to ("نیاز به بررسی دستی (امتیاز 50)" to "warn"), d.timeline[1])
    }

    @Test
    fun `a receipt nobody scored has no analysis rather than a zero score`() {
        val body = """{"ok":true,"receipt":{"id":9,"state":"pending","amount":1000,"created_at":1792058814,"auto":null},
            "deposit":null,"analysis":null,"suggestion":{"tone":"warn","title":"واریزی مرتبط پیدا نشد","text":null},"timeline":[]}"""
        val d = Receipts.parseDetail(body)
        assertNull(d.score)
        assertNull(d.deposit)
        assertFalse(d.enough)
        assertTrue(d.signals.isEmpty())
        assertNull(d.suggestionText)
        assertEquals("واریزی مرتبط پیدا نشد", d.suggestionTitle)
    }
}
