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

    /** Событие с идентификатором (для отмены/переноса). */
    data class EventItem(val id: Long, val begin: Long, val end: Long, val title: String, val allDay: Boolean)

    private fun dayStart(dayOffset: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Список событий дня с EVENT_ID (для голосовой отмены по номеру). */
    fun events(ctx: Context, dayOffset: Int): List<EventItem> {
        if (!hasAccess(ctx)) return emptyList()
        val start = dayStart(dayOffset); val end = start + 24L * 60 * 60 * 1000
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start); ContentUris.appendId(builder, end)
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY
        )
        val out = ArrayList<EventItem>()
        try {
            ctx.contentResolver.query(builder.build(), proj, null, null,
                CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val b = c.getLong(1); val e = c.getLong(2)
                    val title = (c.getString(3)?.trim().orEmpty()).ifBlank { "(без названия)" }
                    val allDay = c.getInt(4) == 1
                    out.add(EventItem(id, b, e, title, allDay))
                }
            }
        } catch (ex: Exception) { return emptyList() }
        return out
    }

    /** Свободные окна в пределах рабочих часов [startHour, endHour) дня. Минимальная длина — minMinutes. */
    fun freeWindows(ctx: Context, dayOffset: Int, startHour: Int = 9, endHour: Int = 21, minMinutes: Int = 30): List<Pair<Long, Long>> {
        val day0 = dayStart(dayOffset)
        var cursor = day0 + startHour * 3600_000L
        val winEnd = day0 + endHour * 3600_000L
        if (dayOffset == 0) cursor = maxOf(cursor, System.currentTimeMillis() + 5 * 60_000L)
        if (cursor >= winEnd) return emptyList()
        val busy = events(ctx, dayOffset).filter { !it.allDay && it.end > cursor && it.begin < winEnd }
            .map { it.begin to it.end }.sortedBy { it.first }
        val gaps = ArrayList<Pair<Long, Long>>()
        val minMs = minMinutes * 60_000L
        for ((b, e) in busy) {
            if (b > cursor && b - cursor >= minMs) gaps.add(cursor to minOf(b, winEnd))
            cursor = maxOf(cursor, e)
            if (cursor >= winEnd) break
        }
        if (cursor < winEnd && winEnd - cursor >= minMs) gaps.add(cursor to winEnd)
        return gaps
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
