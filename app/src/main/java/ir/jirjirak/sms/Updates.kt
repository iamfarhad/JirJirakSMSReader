package ir.jirjirak.sms

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * «نسخهٔ تازه آمده» — the app learns about a new release, fetches the APK itself and hands it to
 * the installer; the seller only confirms the install (Android allows no less for a sideloaded app).
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
 */
object Updates {

    private const val REPO = "https://github.com/iamfarhad/JirJirakSMSReader"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2
    private const val EVERY_MS = 6 * 60 * 60 * 1000L
    const val APK = "application/vnd.android.package-archive"

    data class Latest(val version: String, val url: String)

    /** Ask now: the bot's word if it gave one, else GitHub. Remembered in the Store; null when neither answered. */
    fun check(context: Context): Latest? {
        val store = Store(context)
        store.lastUpdateCheck = System.currentTimeMillis()
        val latest = fromBot(store) ?: fromGitHub() ?: return null
        store.latestVersion = latest.version
        store.latestUrl = latest.url
        return latest
    }

    /** From the background beat: at most every 6 hours; a new version is downloaded and announced once. */
    fun checkInBackground(context: Context) {
        val store = Store(context)
        if (System.currentTimeMillis() - store.lastUpdateCheck < EVERY_MS) return
        val latest = check(context) ?: return
        if (!SemVer.isNewer(latest.version, BuildConfig.VERSION_NAME)) return
        if (store.downloadedVersion == latest.version || store.downloadVersion == latest.version) return
        store.event("update", "info", "نسخهٔ تازه پیدا شد: " + latest.version, "در حال دانلود در پس‌زمینه")
        download(context, latest)
    }

    /**
     * The newer version already known, or null when this copy is up to date. The bot's word from
     * the last ping counts too, so a release the bot announced shows up without waiting for the
     * next six-hourly check.
     */
    fun available(context: Context): Latest? {
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
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
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

    // ── download and install ────────────────────────────────────────────

    /** Fetch the APK with the system's DownloadManager; [DownloadReceiver] takes it from there. */
    fun download(context: Context, latest: Latest): Boolean {
        val store = Store(context)
        val dm = context.getSystemService(DownloadManager::class.java) ?: return false
        return runCatching {
            val name = "JirjirakSMS-" + latest.version + ".apk"
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, name).delete() }
            val request = DownloadManager.Request(Uri.parse(latest.url))
                .setTitle("جیرجیرک پیامک " + latest.version)
                .setDescription("دانلود نسخهٔ تازه")
                .setMimeType(APK)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
            store.downloadId = dm.enqueue(request)
            store.downloadVersion = latest.version
            true
        }.getOrDefault(false)
    }

    /** Bytes so far and in total of the running download, or null when none is running. */
    fun progress(context: Context): Pair<Long, Long>? {
        val store = Store(context)
        val id = store.downloadId.takeIf { it >= 0 } ?: return null
        val dm = context.getSystemService(DownloadManager::class.java) ?: return null
        return runCatching {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return null
                when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED -> null
                    else -> Pair(
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    )
                }
            }
        }.getOrNull()
    }

    /** The downloaded APK of the latest version, if it is on the phone. */
    fun installable(context: Context): File? {
        val store = Store(context)
        val latest = available(context) ?: return null
        if (store.downloadedVersion != latest.version) return null
        val f = File(store.downloadedFile ?: return null)
        return f.takeIf { it.exists() && it.length() > 0 }
    }

    /** Android asks the seller to confirm; a sideloaded app may do no more. */
    fun install(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /** May this app open the installer? Android 8+ asks once per app; [openInstallPermission] goes there. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** The download finished: remember the file, tell the seller once. */
    fun downloaded(context: Context, id: Long) {
        val store = Store(context)
        if (id != store.downloadId) return
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        val version = store.downloadVersion ?: return
        val file = runCatching {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return@use null
                if (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) return@use null
                Uri.parse(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))).path?.let { File(it) }
            }
        }.getOrNull()
        store.downloadId = -1
        store.downloadVersion = null
        if (file == null || !file.exists()) {
            store.event("update", "warn", "دانلود نسخهٔ $version ناتمام ماند", "از «بیشتر ← به‌روزرسانی» دوباره تلاش کنید.")
            return
        }
        store.downloadedVersion = version
        store.downloadedFile = file.absolutePath
        store.event("update", "ok", "نسخهٔ $version دانلود شد", "برای نصب، در «بیشتر» روی «نصب» بزنید.")
        if (store.notifiedVersion != version) {
            notify(context, version)
            store.notifiedVersion = version
        }
    }

    private fun notify(context: Context, version: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "به‌روزرسانی", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).putExtra(MainActivity.TAB, MainActivity.TAB_MORE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                builder.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("نسخهٔ تازهٔ جیرجیرک پیامک دانلود شد")
                    .setContentText("نسخهٔ " + version + " — برای نصب بزنید")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}

/** DownloadManager says a download ended; if it is ours, [Updates.downloaded] takes over. */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id >= 0) Updates.downloaded(context, id)
    }
}
