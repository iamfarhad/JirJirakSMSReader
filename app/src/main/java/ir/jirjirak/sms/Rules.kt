package ir.jirjirak.sms

/**
 * The rules that decide what leaves the phone — pure functions, unit-tested, no Android in them.
 *
 * The app reads SMS, so the default is NOTHING: a message is forwarded only when its sender is one
 * the seller chose by hand, and never when it looks like a one-time password, whoever sent it.
 */
object Rules {

    /** The code the bot hands out: «jirjirak-sms:» and the shop's ingest URL — https only. */
    private val CODE = Regex("""^https://[^\s/?#]+(/[^\s?#]*)?/api/bank-sms/[A-Za-z0-9]{20,80}$""")

    /** The ingest URL inside a pairing code, or null for anything else on the clipboard. */
    fun parseCode(raw: String?): String? {
        var s = raw?.trim() ?: return null
        if (s.startsWith("jirjirak-sms:")) {
            s = s.removePrefix("jirjirak-sms:").trim()
        }
        return if (CODE.matches(s)) s else null
    }

    /**
     * Persian text as banks write it, made comparable: Arabic ك/ي/ى and أ/إ folded to their Persian
     * forms, the zero-width non-joiner dropped (یک‌بار = یکبار), Persian and Arabic digits to ASCII.
     * Many Iranian banks send Arabic letters; a word list in Persian letters alone misses them.
     */
    fun canon(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                'ك' -> sb.append('ک')
                'ي', 'ى' -> sb.append('ی')
                'أ', 'إ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                '‌', '‏', '‎' -> {}
                in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * One spelling per sender, so «+989121234567», «00989121234567», «۰۹۱۲ ۱۲۳ ۴۵۶۷» and
     * «(0912) 123-4567» are the same number, and «BankMellat» matches «bankmellat». A seller typing
     * on a Persian keyboard writes Persian digits; the phone reports ASCII ones.
     */
    fun normalize(sender: String): String {
        val s = canon(sender).filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' || it == ' ' }
        val international = s.startsWith("+") || s.startsWith("00")
        val digits = s.removePrefix("+").let { if (it.startsWith("00")) it.removePrefix("00") else it }
        if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) {
            return when {
                // A country code was written: +98… and 0098… are the national 0…
                international && digits.startsWith("98") -> "0" + digits.removePrefix("98")
                // 98 + a ten-digit mobile, written without the plus.
                !international && digits.length == 12 && digits.startsWith("989") -> "0" + digits.removePrefix("98")
                else -> digits
            }
        }
        return s.removePrefix("+").lowercase()
    }

    /**
     * Every way this sender may be written: the phone shows «98200» where the seller saved «+98200»,
     * and a country code may or may not be part of what a short code looks like. Two senders are the
     * same when any of their spellings meet.
     */
    fun variants(sender: String): Set<String> {
        val n = normalize(sender)
        if (n.isEmpty()) return emptySet()
        if (!n.all { it in '0'..'9' }) return setOf(n)
        val out = mutableSetOf(n)
        if (n.startsWith("0") && n.length > 1) out += "98" + n.drop(1)
        if (n.startsWith("98") && n.length >= 5) out += "0" + n.drop(2)
        return out
    }

    fun isAllowed(sender: String, allowed: Collection<String>): Boolean {
        val mine = variants(sender)
        return mine.isNotEmpty() && allowed.any { other -> variants(other).any { it in mine } }
    }

    /** Phrases of a one-time password or a login/verification code. Matched on canon() text. */
    private val SECRET = listOf(
        "رمز پویا", "رمزپویا", "رمز یکبار", "رمز یک بار", "یکبار مصرف", "یک بار مصرف", "رمز دوم", "رمز اینترنتی",
        "کد تایید", "کد تأیید", "کدتایید", "کد ورود", "کد فعالسازی", "کد فعال سازی", "کد امنیتی", "کد یکبار",
        "otp", "one-time", "one time password", "verification code", "your code", "code:", "password", "passcode",
    )

    /** «رمز» followed closely by a 4–8 digit code — «رمز: 123456», «رمز شما 8812». */
    private val CODE_AFTER_RAMZ = Regex("""رمز(?!ارز)[^\d\n]{0,10}\d{4,8}(?!\d)""")

    /**
     * One-time passwords and verification codes never leave the phone, from any sender. Bare «رمز»
     * and «پویا» are NOT enough: «پویا» is a name a deposit can come from (واریز پایا از پویا …), and
     * banks put «رمز خود را در اختیار دیگران قرار ندهید» under real transactions.
     */
    fun isSecret(body: String): Boolean {
        val b = canon(body).lowercase()
        return SECRET.any { b.contains(it) } || CODE_AFTER_RAMZ.containsMatchIn(b)
    }

    /** Words a bank's transaction SMS carries — used only to SUGGEST a sender, never to forward. */
    private val BANKISH = listOf("مانده", "موجودی", "واریز", "برداشت", "انتقال", "حساب", "کارت", "مبلغ", "بانک")

    fun looksLikeBank(body: String): Boolean = canon(body).let { b -> BANKISH.any { b.contains(it) } }

    /**
     * One SMS, however often Android delivers it: the same SMS heard twice must be called the same
     * thing or it is sent twice. Sender (any spelling), text, and the SMS centre's own timestamp
     * (`timestampMillis`). The stamp is what keeps two real deposits with identical text (a bank
     * that prints neither balance nor time, the same amount on two days) from being taken for one.
     */
    fun fingerprint(sender: String, body: String, sentAtMillis: Long): String {
        val who = variants(sender).minOrNull() ?: normalize(sender)
        val raw = who + "\n" + canon(body).trim() + "\n" + (sentAtMillis / 1000)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Forward this SMS? Only from a chosen sender, and never a password. */
    fun shouldForward(sender: String, body: String, allowed: Collection<String>): Boolean =
        isAllowed(sender, allowed) && !isSecret(body)
}
