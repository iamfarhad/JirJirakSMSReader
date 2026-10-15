package ir.jirjirak.sms

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ir.jirjirak.sms.ui.Persian

/**
 * The home-screen widget: «🟢 فعال · امروز ۸ پیامک» at a glance, without opening the app. Redrawn
 * whenever the phone sends or pings (Net), so it is never more than a beat behind.
 */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        draw(context, manager, ids)
    }

    companion object {
        /** The widget's two lines, from the Store. Pure enough to unit-test. */
        fun lines(paired: Boolean, senders: Int, lastPing: Long, sentToday: Int, pending: Int, now: Long): Pair<String, String> {
            val online = now - lastPing < 40 * 60 * 1000L
            val title = when {
                !paired -> "⚪️ وصل نیست"
                senders == 0 -> "🟡 شمارهٔ بانک مجاز نشده"
                !online -> "📵 ارتباط قطع است"
                pending > 0 -> "🟡 " + Persian.number(pending) + " پیامک در صف"
                else -> "🟢 فعال"
            }
            val sub = if (!paired) "برنامه را باز کنید و کد اتصال را بچسبانید" else "امروز " + Persian.number(sentToday) + " پیامک · " + Persian.ago(lastPing, now)
            return title to sub
        }

        /** Redraw every placed widget; a no-op when none is on the home screen. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) draw(context, manager, ids)
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val store = Store(context)
            val (title, sub) = lines(store.endpoint != null, store.senders.size, store.lastPing, store.sentToday(), store.queue().size, System.currentTimeMillis())
            val open = PendingIntent.getActivity(
                context, 50, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_status).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_sub, sub)
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            runCatching { manager.updateAppWidget(ids, views) }
        }
    }
}
