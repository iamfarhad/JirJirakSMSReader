package ir.jirjirak.sms

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Which copy this is: the GitHub one (the bot links its APK) or the Cafe Bazaar one. Bazaar's rules
 * decide what the second may do:
 *  - an app installed from Bazaar is updated only through Bazaar — no version check against GitHub
 *    or the bot, no download link, no «نسخهٔ تازه آمد» notification;
 *  - no mention of, or link to, another place the app is distributed (GitHub);
 *  - only the permissions the core feature needs — the inbox (READ_SMS) is not one of them, so
 *    recovery and picking a sender from the inbox are left out (src/bazaar/AndroidManifest.xml).
 */
object Edition {

    val bazaar: Boolean get() = BuildConfig.STORE == "bazaar"

    /** Reading the SMS inbox — recovery and «پیدا کردن خودکار شمارهٔ بانک». */
    val readsInbox: Boolean get() = !bazaar

    /** Updating from GitHub or the bot's link, and the source link — the GitHub copy only. */
    val selfUpdates: Boolean get() = !bazaar

    private const val PACKAGE = "ir.jirjirak.sms"

    /** The app's page in Bazaar: the Bazaar app when installed, its website otherwise. */
    fun openStorePage(context: Context) {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("bazaar://details?id=$PACKAGE"))
            .setPackage("com.farsitel.bazaar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(app) }.isSuccess) return
        Updates.open(context, "https://cafebazaar.ir/app/$PACKAGE")
    }
}
