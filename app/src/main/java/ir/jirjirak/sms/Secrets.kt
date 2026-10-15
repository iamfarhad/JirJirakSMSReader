package ir.jirjirak.sms

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The pairing address (it carries the shop's ingest secret) and the manage key, encrypted at rest
 * with a key that lives in the Android Keystore and never leaves it. A backup, a rooted file browser
 * or a stolen prefs file holds ciphertext; only this app on this phone can read it.
 *
 * Stored as «v1:» + hex(iv ‖ ciphertext) — hex, not Base64: android.util.Base64 is a stub on a JVM
 * test and java.util.Base64 needs API 26. A value without the prefix is a 1.1 plaintext one —
 * read as is and sealed on the next write ([Store] does that once).
 */
object Secrets {

    private const val ALIAS = "jirjirak-prefs"
    private const val PREFIX = "v1:"

    /** What the Keystore does, swappable so the format and the migration are unit-tested on a JVM. */
    interface Box {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    @Volatile var box: Box = KeystoreBox

    /**
     * Decrypted values, by their ciphertext. A Keystore operation costs a millisecond or more, and the
     * screen reads the address on every frame that shows the shop — once per value is enough.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun isSealed(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    fun seal(plain: String): String =
        (PREFIX + hex(box.seal(plain.toByteArray(Charsets.UTF_8)))).also { cache[it] = plain }

    /**
     * The plain value: a sealed one opened, a legacy plaintext one as it is, null when the key is gone
     * (the app's data was restored onto another phone) — the seller pairs again, nothing is guessed.
     */
    fun open(stored: String?): String? {
        if (stored == null) return null
        if (!isSealed(stored)) return stored
        cache[stored]?.let { return it }
        return runCatching {
            String(box.open(unhex(stored.removePrefix(PREFIX))), Charsets.UTF_8)
        }.getOrNull()?.also { cache[stored] = it }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private object KeystoreBox : Box {
        private const val IV = 12

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }

        override fun seal(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            return c.iv + c.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, IV)))
            return c.doFinal(sealed, IV, sealed.size - IV)
        }
    }
}
