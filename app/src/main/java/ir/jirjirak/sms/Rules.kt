package ir.jirjirak.sms

/**
 * The rules that decide what leaves the phone — pure functions, unit-tested, no Android in them.
 *
 * The app reads SMS, so the default is NOTHING: a message is forwarded only when its sender is one
 * the seller chose by hand, and never when it looks like a one-time password, whoever sent it.
 */
object Rules {

    /** The code the bot hands out: «jirjirak-sms:» and the shop's ingest URL. */
    private val CODE = Regex("""^https?://[^\s/]+(/\S*)?/api/bank-sms/[A-Za-z0-9]{20,80}$""")

    /** The ingest URL inside a pairing code, or null for anything else on the clipboard. */
    fun parseCode(raw: String?): String? {
        var s = raw?.trim() ?: return null
        if (s.startsWith("jirjirak-sms:")) {
            s = s.removePrefix("jirjirak-sms:").trim()
        }
        return if (CODE.matches(s)) s else null
    }

    /**
     * One spelling per sender, so «+989121234567», «00989121234567» and «09121234567» are the same
     * number, and «BankMellat» matches «bankmellat».
     */
    fun normalize(sender: String): String {
        val s = sender.trim().replace(" ", "").replace("-", "")
        val digits = s.removePrefix("+")
        if (digits.isNotEmpty() && digits.all { it.isDigit() }) {
            return when {
                digits.startsWith("0098") -> "0" + digits.removePrefix("0098")
                digits.startsWith("98") && digits.length > 10 -> "0" + digits.removePrefix("98")
                else -> digits
            }
        }
        return s.lowercase()
    }

    fun isAllowed(sender: String, allowed: Collection<String>): Boolean {
        val n = normalize(sender)
        return n.isNotEmpty() && allowed.any { normalize(it) == n }
    }

    /** One-time passwords and verification codes never leave the phone, from any sender. */
    private val SECRET = listOf("رمز", "پویا", "کد تایید", "کد تأیید", "کدتایید", "otp", "password", "verification code", "یکبار مصرف", "یک بار مصرف")

    fun isSecret(body: String): Boolean {
        val b = body.lowercase()
        return SECRET.any { b.contains(it) }
    }

    /** Words a bank's transaction SMS carries — used only to SUGGEST a sender, never to forward. */
    private val BANKISH = listOf("مانده", "موجودی", "واریز", "برداشت", "انتقال", "حساب", "کارت", "مبلغ")

    fun looksLikeBank(body: String): Boolean = BANKISH.any { body.contains(it) }

    /** Forward this SMS? Only from a chosen sender, and never a password. */
    fun shouldForward(sender: String, body: String, allowed: Collection<String>): Boolean =
        isAllowed(sender, allowed) && !isSecret(body)
}
