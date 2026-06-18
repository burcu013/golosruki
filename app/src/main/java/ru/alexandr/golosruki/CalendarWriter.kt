package ru.alexandr.golosruki

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/** Создание событий в календаре. Требует WRITE_CALENDAR. */
object CalendarWriter {

    fun canWrite(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Полночь UTC той же календарной даты (для корректных событий «на весь день»). */
    private fun midnightUtc(localMillis: Long): Long {
        val local = java.util.Calendar.getInstance().apply { timeInMillis = localMillis }
        val utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH), local.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
        }
        return utc.timeInMillis
    }

    /** ID основного (или первого видимого) календаря. */
    private fun primaryCalendarId(ctx: Context): Long? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        return try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj, null, null,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC"
            )?.use { c ->
                val idI = c.getColumnIndex(CalendarContract.Calendars._ID)
                val visI = c.getColumnIndex(CalendarContract.Calendars.VISIBLE)
                val lvlI = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                var firstVisible: Long? = null
                while (c.moveToNext()) {
                    val id = c.getLong(idI)
                    val visible = c.getInt(visI) == 1
                    val writable = c.getInt(lvlI) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                    if (visible && writable) { if (firstVisible == null) firstVisible = id }
                }
                firstVisible
            }
        } catch (e: Exception) { Logger.log("SEC", "Календари: ${e.message}"); null }
    }

    /**
     * Создаёт событие. start — millis начала; durationMin — длительность; reminderMin — за сколько напомнить (0 — без).
     * allDay — событие на весь день. Возвращает true при успехе.
     */
    fun create(
        ctx: Context, title: String, start: Long, durationMin: Int,
        reminderMin: Int, description: String, allDay: Boolean
    ): Boolean {
        if (!canWrite(ctx)) { Logger.log("SEC", "Нет WRITE_CALENDAR"); return false }
        val calId = primaryCalendarId(ctx)
        if (calId == null) { Logger.log("SEC", "Нет доступного календаря для записи"); return false }
        val tz = TimeZone.getDefault().id
        // Для события «на весь день» DTSTART должен быть полночью в UTC, иначе оно «уезжает» на соседний день.
        val dtStart = if (allDay) midnightUtc(start) else start
        val end = if (allDay) dtStart + 24L * 60 * 60 * 1000 else dtStart + durationMin.coerceAtLeast(15) * 60_000L
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            if (description.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.DTSTART, dtStart)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else tz)
            if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
        }
        return try {
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return false
            val eventId = ContentUris.parseId(uri)
            if (reminderMin > 0) {
                val rv = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, reminderMin)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                runCatching { ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, rv) }
            }
            Logger.log("SEC", "Событие создано: '$title' (cal=$calId, id=$eventId)")
            true
        } catch (e: Exception) { Logger.log("SEC", "Запись события: ${e.message}"); false }
    }

    /** Удаляет событие календаря по EVENT_ID. Требует WRITE_CALENDAR. */
    fun delete(ctx: Context, eventId: Long): Boolean {
        if (!canWrite(ctx)) { Logger.log("SEC", "Нет WRITE_CALENDAR для удаления"); return false }
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val n = ctx.contentResolver.delete(uri, null, null)
            Logger.log("SEC", "Событие удалено (id=$eventId): строк $n")
            n > 0
        } catch (e: Exception) { Logger.log("SEC", "Удаление события: ${e.message}"); false }
    }
}
