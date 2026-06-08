package ru.alexandr.golosruki

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Слушатель уведомлений: хранит последние и (опционально) озвучивает входящие.
 * Чтение по команде «Иван что нового». Требует разрешения «доступ к уведомлениям».
 */
class NotificationService : NotificationListenerService() {

    class Item(val app: String, val title: String, val text: String)

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return                                   // не объявляем сами себя
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return     // постоянные (плеер, навигация)
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return     // сводки групп
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        var text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        if (text.isBlank()) text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val app = appLabel(pkg)
        synchronized(buffer) {
            buffer.add(Item(app, title, text))
            while (buffer.size > 40) buffer.removeAt(0)
        }
        if (SettingsStore.getAnnounceNotifs(this)) {
            val key = "$pkg|$title|$text"
            val now = System.currentTimeMillis()
            if (key != lastKey || now - lastAt > 4000) {            // антидубль
                lastKey = key; lastAt = now
                val spoken = buildString {
                    append(app)
                    if (title.isNotBlank()) { append(", "); append(title) }
                    if (text.isNotBlank()) { append(". "); append(text.take(160)) }
                }
                VoiceRecognitionService.instance?.speak(spoken)
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    companion object {
        @Volatile var instance: NotificationService? = null
        private val buffer = ArrayList<Item>()
        private var lastKey = ""
        private var lastAt = 0L

        fun hasAccess(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(ctx.packageName)
        }

        /** Текст для зачитывания последних N уведомлений. */
        fun recentSummary(ctx: Context, n: Int): String {
            if (!hasAccess(ctx)) return "Нет доступа к уведомлениям. Включите его в настройках приложения, раздел «Уведомления»."
            val items = synchronized(buffer) { buffer.takeLast(n).reversed() }
            if (items.isEmpty()) return "Новых уведомлений нет."
            val sb = StringBuilder("Последние уведомления. ")
            for (it in items) {
                sb.append(it.app)
                if (it.title.isNotBlank()) { sb.append(": "); sb.append(it.title) }
                if (it.text.isNotBlank()) { sb.append(". "); sb.append(it.text.take(120)) }
                sb.append(". ")
            }
            return sb.toString()
        }
    }
}
