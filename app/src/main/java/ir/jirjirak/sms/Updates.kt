package ir.jirjirak.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * «نسخهٔ تازه آمده» — the app asks GitHub for its latest release a few times a day, notifies once per
 * new version, and the screen shows it with a download button.
 *
 * An APK installed by hand never updates itself, and an old copy is a silent risk: a fix to what may
 * leave the phone only protects the sellers who install it.
 */
object Updates {

    private const val LATEST = "https://api.github.com/repos/iamfarhad/JirJirakSMSReader/releases/latest"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2
    private const val EVERY_MS = 6 * 60 * 60 * 1000L

    data class Latest(val version: String, val url: String)

    /** Ask GitHub now. The answer is remembered in the Store; null when it could not be asked. */
    fun check(context: Context): Latest? {
        val store = Store(context)
        store.lastUpdateCheck = System.currentTimeMillis()
        val latest = fetch() ?: return null
        store.latestVersion = latest.version
        store.latestUrl = latest.url
        return latest
    }

    /** From the background beat: at most every 6 hours, and one notification per new version. */
    fun checkInBackground(context: Context) {
        val store = Store(context)
        if (System.currentTimeMillis() - store.lastUpdateCheck < EVERY_MS) return
        val latest = check(context) ?: return
        if (SemVer.isNewer(latest.version, BuildConfig.VERSION_NAME) && store.notifiedVersion != latest.version) {
            notify(context, latest)
            store.notifiedVersion = latest.version
        }
    }

    /** The newer version already known, or null when this copy is up to date. */
    fun available(context: Context): Latest? {
        val store = Store(context)
        val v = store.latestVersion ?: return null
        val url = store.latestUrl ?: return null
        return if (SemVer.isNewer(v, BuildConfig.VERSION_NAME)) Latest(v, url) else null
    }

    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun fetch(): Latest? = try {
        val con = URL(LATEST).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.setRequestProperty("Accept", "application/vnd.github+json")
            con.setRequestProperty("User-Agent", "jirjirak-sms/" + BuildConfig.VERSION_NAME)
            if (con.responseCode != 200) {
                null
            } else {
                val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
                val version = SemVer.parse(json.optString("tag_name"))?.toString()
                val assets = json.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == "JirjirakSMS.apk" }?.optString("browser_download_url")
                if (version != null && !apk.isNullOrEmpty()) Latest(version, apk) else null
            }
        } finally {
            con.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun notify(context: Context, latest: Latest) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "به‌روزرسانی", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                builder.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("نسخهٔ تازهٔ جیرجیرک پیامک آمده")
                    .setContentText("نسخهٔ " + latest.version + " — برای دریافت بزنید")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
