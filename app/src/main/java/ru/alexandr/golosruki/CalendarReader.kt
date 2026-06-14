package ru.alexandr.golosruki

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Чтение событий календаря для голосовой выдачи. Требует разрешения READ_CALENDAR. */
object CalendarReader {

    fun hasAccess(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** dayOffset: 0 — сегодня, 1 — завтра. */
    fun daySummary(ctx: Context, dayOffset: Int): String {
        if (!hasAccess(ctx)) return "Нет доступа к календарю. Включите его в настройках приложения, раздел «Календарь»."
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val end = start + 24L * 60 * 60 * 1000

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, end)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY
        )
        val events = ArrayList<Triple<Long, String, Boolean>>()
        try {
            ctx.contentResolver.query(builder.build(), proj, null, null,
                CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val title = (c.getString(0)?.trim().orEmpty()).ifBlank { "(без названия)" }
                    val begin = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    events.add(Triple(begin, title, allDay))
                }
            }
        } catch (e: Exception) {
            return "Не удалось прочитать календарь."
        }

        val whenWord = when (dayOffset) { 0 -> "Сегодня"; 1 -> "Завтра"; else -> "В этот день" }
        if (events.isEmpty()) return "$whenWord событий нет."
        val sb = StringBuilder("$whenWord ${events.size} ${plural(events.size)}. ")
        val tf = SimpleDateFormat("HH:mm", Locale("ru"))
        for ((begin, title, allDay) in events) {
            if (allDay) sb.append("весь день — $title. ")
            else sb.append("${tf.format(Date(begin))} — $title. ")
        }
        return sb.toString()
    }

    /** Названия событий, пересекающихся с интервалом [start, end). Для проверки конфликтов слотов. */
    fun busyBetween(ctx: Context, start: Long, end: Long): List<String> {
        if (!hasAccess(ctx)) return emptyList()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start - 1)
        ContentUris.appendId(builder, end + 1)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
        val out = ArrayList<String>()
        try {
            ctx.contentResolver.query(builder.build(), proj, null, null,
                CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val title = (c.getString(0)?.trim().orEmpty()).ifBlank { "(без названия)" }
                    val b = c.getLong(1); val e = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    if (allDay) continue
                    if (b < end && e > start) out.add(title)   // пересечение
                }
            }
        } catch (ex: Exception) { return emptyList() }
        return out
    }

    private fun plural(n: Int): String {
        val m10 = n % 10; val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> "событие"
            m10 in 2..4 && m100 !in 12..14 -> "события"
            else -> "событий"
        }
    }
}
