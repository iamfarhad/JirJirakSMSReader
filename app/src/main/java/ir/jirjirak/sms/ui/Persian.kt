package ir.jirjirak.sms.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** «۳ دقیقه پیش» — Persian digits and the way people say time in Persian. */
object Persian {
    private const val DIGITS = "۰۱۲۳۴۵۶۷۸۹"

    fun digits(s: String): String = s.map { if (it in '0'..'9') DIGITS[it - '0'] else it }.joinToString("")

    fun number(n: Int): String = digits(n.toString())

    /**
     * A sender as it is written — «+98200» stays «+98200» inside right-to-left text instead of
     * becoming «98200+». Isolated left-to-right (U+2066 … U+2069), Persian digits kept.
     */
    fun sender(s: String): String = "⁦" + digits(s) + "⁩"

    /** A host name, a model number, a version: left-to-right and left alone. */
    fun latin(s: String): String = "⁦" + s + "⁩"

    private val LATIN_RUN = Regex("""[A-Za-z][A-Za-z0-9._@/:+-]*|[A-Za-z0-9._/+-]*\d[A-Za-z0-9._/+-]*[A-Za-z][A-Za-z0-9._@/:+-]*""")

    /**
     * Persian text with Latin tokens in it — a version, a host, a sender id — each token isolated so
     * «1.2.1-debug» does not come out as «debug-1.2.1». Text already isolated is left alone.
     */
    fun bidi(s: String): String = if (s.contains('\u2066')) s else LATIN_RUN.replace(s) { latin(it.value) }

    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0) return "هنوز نه"
        val s = (now - millis) / 1000
        return when {
            s < 60 -> "همین الان"
            s < 3600 -> number((s / 60).toInt()) + " دقیقه پیش"
            s < 86400 -> number((s / 3600).toInt()) + " ساعت پیش"
            else -> number((s / 86400).toInt()) + " روز پیش"
        }
    }

    fun clock(millis: Long): String = digits(SimpleDateFormat("HH:mm", Locale.US).format(Date(millis)))

    /** «۲۳ مهر» — or «امروز» / «دیروز» for the event log's day headers. */
    fun day(millis: Long, now: Long = System.currentTimeMillis()): String {
        val today = Jalali.of(now)
        val that = Jalali.of(millis)
        return when {
            that == today -> "امروز"
            that == Jalali.of(now - 86_400_000L) -> "دیروز"
            that.year == today.year -> that.dayMonth()
            else -> that.full()
        }
    }

    /** «۲۳ مهر ۱۴۰۵». */
    fun date(millis: Long): String = Jalali.of(millis).full()
}

/** The Iranian calendar, enough to print a date. Pure: unit-tested against known days. */
data class Jalali(val year: Int, val month: Int, val day: Int) {

    fun dayMonth() = Persian.number(day) + " " + MONTHS[month - 1]

    fun full() = dayMonth() + " " + Persian.number(year)

    companion object {
        val MONTHS = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")

        fun of(millis: Long): Jalali {
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            return fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }

        /** The common conversion (Roozbeh Pournader's), valid for every year the app will see. */
        fun fromGregorian(gy: Int, gm: Int, gd: Int): Jalali {
            val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
            var jy = if (gy <= 1600) 0 else 979
            val gy2 = gy - (if (gy <= 1600) 621 else 1600)
            val gy3 = if (gm > 2) gy2 + 1 else gy2
            var days = 365 * gy2 + (gy3 + 3) / 4 - (gy3 + 99) / 100 + (gy3 + 399) / 400 - 80 + gd + gdm[gm - 1]
            jy += 33 * (days / 12053)
            days %= 12053
            jy += 4 * (days / 1461)
            days %= 1461
            if (days > 365) {
                jy += (days - 1) / 365
                days = (days - 1) % 365
            }
            val jm = if (days < 186) 1 + days / 31 else 7 + (days - 186) / 30
            val jd = 1 + (if (days < 186) days % 31 else (days - 186) % 30)
            return Jalali(jy, jm, jd)
        }
    }
}
