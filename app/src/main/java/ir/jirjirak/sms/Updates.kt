package ir.jirjirak.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * «نسخهٔ تازه آمده» — the app learns about a new release and says so; «دانلود» opens the download
 * link in the browser, and the browser hands the APK to Android's installer.
 *
 * The app does not download or install anything itself any more. That needed
 * REQUEST_INSTALL_PACKAGES — one more powerful permission on an app that already reads bank SMS, and
 * exactly the combination Play Protect holds against an app installed from outside Google Play.
 *
 * WHERE THE VERSION COMES FROM. First the bot: its ping reply names the latest version and a
 * download address the seller's own server can mirror, which matters because GitHub is slow or
 * blocked on many Iranian networks. Then GitHub itself — but `releases/latest` as a page, not the
 * API: the API allows 60 unsigned calls an hour PER ADDRESS, and sellers behind a carrier's shared
 * address exhausted it between them, so «بررسی ممکن نشد» was the normal answer. The page answers
 * with a redirect whose Location carries the tag, and that costs nothing.
 *
 * An APK installed by hand never updates itself, and an old copy is a silent risk: a fix to what may
 * leave the phone only protects the sellers who install it.
 *
 * The Bazaar copy checks nothing and announces nothing: Bazaar updates it (Edition).
 */
object Updates {

    private const val REPO = "https://github.com/iamfarhad/JirJirakSMSReader"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2
    private const val EVERY_MS = 6 * 60 * 60 * 1000L

    data class Latest(val version: String, val url: String)

    /**
     * Ask now: the bot first — a fresh ping, so its answer is today's — then GitHub. Remembered in
     * the Store; null when neither answered. GitHub is slow or blocked on many Iranian networks,
     * which is why the bot is asked first and GitHub gets a short timeout.
     */
    fun check(context: Context): Latest? {
        val store = Store(context)
        store.lastUpdateCheck = System.currentTimeMillis()
        if (store.endpoint != null) runCatching { Net.ping(context) }
        if (!Edition.selfUpdates) return null
        val latest = fromBot(store) ?: fromGitHub() ?: return null
        store.latestVersion = latest.version
        store.latestUrl = latest.url
        return latest
    }

    /** From the background beat: at most every 6 hours; a new version is announced once, with its link. */
    fun checkInBackground(context: Context) {
        if (!Edition.selfUpdates) return
        val store = Store(context)
        if (System.currentTimeMillis() - store.lastUpdateCheck < EVERY_MS) return
        val latest = check(context) ?: return
        if (!SemVer.isNewer(latest.version, BuildConfig.VERSION_NAME)) return
        if (store.notifiedVersion == latest.version) return
        store.event("update", "info", "نسخهٔ تازه آمد: " + latest.version, "برای دانلود، اعلان را بزنید یا در «بیشتر ← به‌روزرسانی» «دانلود» را.")
        notify(context, latest)
        store.notifiedVersion = latest.version
    }

    /**
     * The newer version already known, or null when this copy is up to date. The bot's word from
     * the last ping counts too, so a release the bot announced shows up without waiting for the
     * next six-hourly check.
     */
    fun available(context: Context): Latest? {
        if (!Edition.selfUpdates) return null
        val store = Store(context)
        val known = store.latestVersion?.let { v -> store.latestUrl?.let { Latest(v, it) } }
        val bot = fromBot(store)
        val latest = when {
            known == null -> bot
            bot == null -> known
            SemVer.isNewer(bot.version, known.version) -> bot
            else -> known
        } ?: return null
        return if (SemVer.isNewer(latest.version, BuildConfig.VERSION_NAME)) latest else null
    }

    private fun fromBot(store: Store): Latest? {
        val app = store.shopInfo.optJSONObject("app") ?: return null
        val v = SemVer.parse(app.optString("latest"))?.toString() ?: return null
        val url = app.optString("url").takeIf { it.startsWith("https://") } ?: return null
        return Latest(v, url)
    }

    private fun fromGitHub(): Latest? = try {
        val con = URL("$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            con.instanceFollowRedirects = false
            con.connectTimeout = 8_000
            con.readTimeout = 8_000
            con.setRequestProperty("User-Agent", "jirjirak-sms/" + BuildConfig.VERSION_NAME)
            val code = con.responseCode
            if (code in 300..399) parseRedirect(con.getHeaderField("Location")) else null
        } finally {
            con.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** «…/releases/tag/v1.2.3» → the release. Pure: unit-tested. */
    fun parseRedirect(location: String?): Latest? {
        val tag = Regex("""/releases/tag/(v?\d+\.\d+\.\d+)""").find(location ?: return null)?.groupValues?.get(1) ?: return null
        val v = SemVer.parse(tag)?.toString() ?: return null
        return Latest(v, "$REPO/releases/download/v$v/JirjirakSMS.apk")
    }

    // ── getting it ──────────────────────────────────────────────────────

    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Files and keys of the in-app downloader up to 1.2.0, gone after an update: the APKs it left in
     * the app's own Download folder are a megabyte or two each, and nothing reads them any more.
     */
    fun forgetOldDownloads(context: Context) {
        runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()
                ?.filter { it.name.startsWith("JirjirakSMS") && it.name.endsWith(".apk") }?.forEach(File::delete)
        }
        Store(context).forgetDownloads()
    }

    /** «نسخهٔ تازه آمد» — a tap opens the download link itself, in the browser. */
    private fun notify(context: Context, latest: Latest) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "به‌روزرسانی", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val download = PendingIntent.getActivity(
            context, 1, Intent(Intent.ACTION_VIEW, Uri.parse(latest.url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                builder.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("نسخهٔ تازهٔ جیرجیرک پیامک آمد")
                    .setContentText("نسخهٔ " + latest.version + " — برای دانلود بزنید")
                    .setContentIntent(download)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
