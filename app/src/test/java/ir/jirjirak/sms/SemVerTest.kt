package ir.jirjirak.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    // ── versions: an update must always be a HIGHER number ──────────────

    @Test
    fun `versions compare as numbers, not text`() {
        assertTrue(SemVer.isNewer("v1.10.0", "1.9.3"))
        assertTrue(SemVer.isNewer("1.0.1", "1.0.0"))
        assertTrue(SemVer.isNewer("2.0.0", "1.99.99"))
        assertFalse(SemVer.isNewer("1.0.0", "1.0.0"))
        assertFalse(SemVer.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun `a debug suffix is the same version, garbage is no version`() {
        assertFalse(SemVer.isNewer("1.0.0", "1.0.0-debug"))
        assertTrue(SemVer.isNewer("1.0.1", "1.0.0-debug"))
        assertNull(SemVer.parse("latest-build"))
        assertNull(SemVer.parse("1.0"))
        assertFalse("an unreadable tag never claims an update", SemVer.isNewer("nightly", "1.0.0"))
    }
}
