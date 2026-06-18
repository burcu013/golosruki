package ru.alexandr.golosruki

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Планировщик точных будильников: голосовые напоминания о задачах и утренний брифинг.
 * Будильник запускает фоновую службу с действием → служба озвучивает.
 */
object ReminderScheduler {

    const val ACTION_REMINDER = "ru.alexandr.golosruki.REMINDER"
    const val ACTION_BRIEFING = "ru.alexandr.golosruki.BRIEFING"
    const val ACTION_REARM = "ru.alexandr.golosruki.REARM"
    private const val BRIEFING_REQ = 990001

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun svcPending(ctx: Context, req: Int, action: String, text: String?): PendingIntent {
        val i = Intent(ctx, VoiceRecognitionService::class.java).apply {
            this.action = action
            if (text != null) putExtra("text", text)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getForegroundService(ctx, req, i, flags)
    }

    private fun setExact(ctx: Context, at: Long, pi: PendingIntent) {
        val a = am(ctx)
        try {
            a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // нет права на точные будильники — ставим неточный (с допуском в Doze)
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Голосовое напоминание о задаче. id — стабильный код (по id задачи). */
    fun scheduleReminder(ctx: Context, id: Int, atMillis: Long, text: String) {
        if (atMillis <= System.currentTimeMillis()) return
        setExact(ctx, atMillis, svcPending(ctx, id, ACTION_REMINDER, text))
        Logger.log("SEC", "Напоминание запланировано (#$id): $text")
    }

    fun cancelReminder(ctx: Context, id: Int) {
        runCatching { am(ctx).cancel(svcPending(ctx, id, ACTION_REMINDER, "")) }
    }

    // --- Напоминания-объекты (разовые и повторяющиеся), отдельно от событий календаря ---

    private fun reminderPending(ctx: Context, r: Reminder): PendingIntent {
        val i = Intent(ctx, VoiceRecognitionService::class.java).apply {
            action = ACTION_REMINDER
            putExtra("text", "Напоминание. ${r.text}")
            putExtra("rid", r.id)
            putExtra("repeat_min", r.repeatMin)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getForegroundService(ctx, r.id.hashCode(), i, flags)
    }

    /** Ближайшее будущее срабатывание для повторяющегося напоминания (пропуская прошедшие). */
    fun nextOccurrence(atMillis: Long, repeatMin: Int): Long {
        if (repeatMin <= 0) return atMillis
        var t = atMillis
        val now = System.currentTimeMillis()
        val step = repeatMin * 60_000L
        while (t <= now) t += step
        return t
    }

    fun scheduleReminderItem(ctx: Context, r: Reminder) {
        val at = if (r.repeatMin > 0) nextOccurrence(r.atMillis, r.repeatMin) else r.atMillis
        if (at <= System.currentTimeMillis()) return
        setExact(ctx, at, reminderPending(ctx, r))
        Logger.log("SEC", "Напоминание поставлено: '${r.text}' на $at" + if (r.repeatMin > 0) " (повтор каждые ${r.repeatMin} мин)" else "")
    }

    fun cancelReminderItem(ctx: Context, id: String) {
        val dummy = Reminder(id, "", 0, 0, "active", 0)
        runCatching { am(ctx).cancel(reminderPending(ctx, dummy)) }
    }

    /** Перевзвести все активные напоминания (после перезагрузки/перезапуска). */
    fun rearmReminders(ctx: Context) {
        val m = Secretary.mem(ctx)
        for (r in m.reminders()) {
            if (r.repeatMin > 0) {
                val next = nextOccurrence(r.atMillis, r.repeatMin)
                if (next != r.atMillis) m.updateReminderTime(r.id, next)
                scheduleReminderItem(ctx, r.copy(atMillis = next))
            } else if (r.atMillis > System.currentTimeMillis()) {
                scheduleReminderItem(ctx, r)
            }
        }
    }

    /** Текст голосового напоминания о задаче. */
    fun reminderText(t: Task): String {
        val sb = StringBuilder("Напоминание. ${t.title}")
        if (t.project.isNotBlank()) sb.append(", проект ${t.project}")
        if (t.dueMillis > 0) {
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru"))
            sb.append(", в ${df.format(java.util.Date(t.dueMillis))}")
        }
        return sb.toString()
    }

    /** Ставит/перевзводит ежедневный брифинг на ближайшее время hour:min (одноразовый, перевзвод при срабатывании). */
    fun scheduleDailyBriefing(ctx: Context) {
        val hour = SettingsStore.getBriefingHour(ctx)
        val min = SettingsStore.getBriefingMin(ctx)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
        setExact(ctx, cal.timeInMillis, svcPending(ctx, BRIEFING_REQ, ACTION_BRIEFING, null))
        Logger.log("SEC", "Брифинг запланирован на ${hour}:${"%02d".format(min)}")
    }

    fun cancelBriefing(ctx: Context) {
        runCatching { am(ctx).cancel(svcPending(ctx, BRIEFING_REQ, ACTION_BRIEFING, null)) }
    }

    /** Перевзвести брифинг по настройке. */
    fun rearmBriefing(ctx: Context) {
        if (SettingsStore.getBriefingEnabled(ctx)) scheduleDailyBriefing(ctx) else cancelBriefing(ctx)
    }

    /** Перевзвести все будущие напоминания по открытым задачам (после перезагрузки). */
    fun rearmTasks(ctx: Context) {
        val now = System.currentTimeMillis()
        for (t in Secretary.mem(ctx).openTasks()) {
            if (t.reminderMin > 0 && t.dueMillis > 0) {
                val at = t.dueMillis - t.reminderMin * 60_000L
                if (at > now) scheduleReminder(ctx, t.id.hashCode(), at, reminderText(t))
            }
        }
    }

    fun rearmAll(ctx: Context) { rearmBriefing(ctx); rearmTasks(ctx); rearmReminders(ctx) }
}
