package ir.jirjirak.sms.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «۳ دقیقه پیش» — Persian digits and the way people say time in Persian. */
object Persian {
    private const val DIGITS = "۰۱۲۳۴۵۶۷۸۹"

    fun digits(s: String): String = s.map { if (it in '0'..'9') DIGITS[it - '0'] else it }.joinToString("")

    fun number(n: Int): String = digits(n.toString())

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
}
