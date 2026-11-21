package ir.jirjirak.sms

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboxMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file get() = context.getDatabasePath("outbox.db")

    @Before
    @After
    fun resetDatabase() {
        Outbox.forget()
        context.deleteDatabase("outbox.db")
    }

    @Test
    fun versionOneMessagesRemainQueued() {
        legacyDatabase(1) { db ->
            insert(db, "first", "first-fingerprint", 2)
        }
        val box = Outbox.get(context)
        assertEquals(listOf("first"), box.all().map { it.getString("id") })
        assertEquals(3, box.readableDatabase.version)
    }

    @Test
    fun versionTwoKeepsOnlyBotMessagesInOrderAndRetainsTheirDeduplication() {
        legacyDatabase(2) { db ->
            insert(db, "bot-1", "first-fingerprint", 2, "bot")
            insert(db, "external", "first-fingerprint#custom", 5, "custom")
            insert(db, "bot-2", "second-fingerprint", 1, "bot")
        }
        val box = Outbox.get(context)
        assertEquals(listOf("bot-1", "bot-2"), box.all().map { it.getString("id") })
        assertEquals(2, box.size())
        assertTrue(box.seen("first-fingerprint"))
        assertFalse(box.seen("first-fingerprint#custom"))
        assertFalse(box.enqueue(JSONObject().put("id", "repeat"), "first-fingerprint"))
        box.readableDatabase.rawQuery("SELECT attempts FROM outbox WHERE id = ?", arrayOf("bot-1")).use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }
    }

    private fun legacyDatabase(version: Int, populate: (SQLiteDatabase) -> Unit) {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val targetColumn = if (version == 2) ", target TEXT NOT NULL DEFAULT 'bot'" else ""
            db.execSQL(
                "CREATE TABLE outbox (id TEXT PRIMARY KEY, body TEXT NOT NULL, fingerprint TEXT, " +
                    "attempts INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL$targetColumn)",
            )
            db.execSQL("CREATE TABLE seen (fingerprint TEXT PRIMARY KEY, at INTEGER NOT NULL)")
            populate(db)
            db.version = version
        }
    }

    private fun insert(db: SQLiteDatabase, id: String, fingerprint: String, attempts: Int, target: String? = null) {
        val body = JSONObject().put("id", id).put("message", "واریز").toString()
        val values = arrayOf<Any>(id, body, fingerprint, attempts, 1)
        if (target == null) {
            db.execSQL("INSERT INTO outbox (id, body, fingerprint, attempts, created_at) VALUES (?, ?, ?, ?, ?)", values)
        } else {
            db.execSQL("INSERT INTO outbox (id, body, fingerprint, attempts, created_at, target) VALUES (?, ?, ?, ?, ?, ?)", values + target)
        }
        db.execSQL("INSERT INTO seen (fingerprint, at) VALUES (?, ?)", arrayOf(fingerprint, 1))
    }
}
