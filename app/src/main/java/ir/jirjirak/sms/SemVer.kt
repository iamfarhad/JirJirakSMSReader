package ir.jirjirak.sms

/** MAJOR.MINOR.PATCH, compared as numbers — «1.10.0» is newer than «1.9.3». Pure, unit-tested. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

        /** «v1.2.3», «1.2.3», «1.2.3-debug» — or null for anything else. */
        fun parse(raw: String?): SemVer? {
            val m = PATTERN.matchEntire(raw?.trim() ?: return null) ?: return null
            val (a, b, c) = m.destructured
            return runCatching { SemVer(a.toInt(), b.toInt(), c.toInt()) }.getOrNull()
        }

        /** Is `latest` a newer version than `installed`? False when either cannot be read. */
        fun isNewer(latest: String?, installed: String?): Boolean {
            val l = parse(latest) ?: return false
            val i = parse(installed) ?: return false
            return l > i
        }
    }
}
