package ir.jirjirak.sms

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The vendor screens that decide whether an app may start and stay in the background. Android has no
 * standard one: Xiaomi calls it «Autostart», Huawei «App launch», Oppo/Realme/Vivo something else again
 * — and on those phones it is OFF for every new app, which kills a foreground service too.
 *
 * Each is tried in turn; the app's own system page is the last resort, which always exists.
 */
object Autostart {

    /** Does this phone have a vendor autostart screen worth sending the seller to? */
    val needed: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "vivo", "oneplus", "samsung", "asus", "meizu", "tecno", "infinix")

    private val screens = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        "com.transsion.phonemanager" to "com.itel.autobootmanager.activity.AutoBootMgrActivity",
    )

    fun open(context: Context) {
        for ((pkg, cls) in screens) {
            val intent = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
