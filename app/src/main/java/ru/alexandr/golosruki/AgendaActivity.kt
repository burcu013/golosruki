package ru.alexandr.golosruki

import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран «Дела»: единый список — события дня (календарь), открытые задачи и активные напоминания.
 * Нажатие на задачу закрывает её, на напоминание — отменяет (с подтверждением).
 * Только чтение/изменение памяти секретаря и снятие будильников — рабочую службу не трогает.
 */
class AgendaActivity : ComponentActivity() {

    private val ru = Locale("ru")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()   // вернулись на экран — пересобрать актуальный список
    }

    private fun render() {
        val col = UiKit.column(this)
        col.addView(UiKit.title(this, "Дела"))
        col.addView(UiKit.subtitle(this, "Нажмите на задачу, чтобы закрыть; на напоминание — чтобы отменить. Голосом: «повестка», «мои напоминания»."))

        // События сегодня
        val ev = UiKit.card(this)
        ev.addView(UiKit.sectionHeader(this, "📅 События сегодня"))
        val hasCal = runCatching { CalendarReader.hasAccess(this) }.getOrDefault(false)
        if (hasCal) {
            val s = runCatching { CalendarReader.daySummary(this, 0) }.getOrDefault("—")
            ev.addView(UiKit.body(this, s.ifBlank { "На сегодня событий нет." }))
        } else {
            ev.addView(UiKit.body(this, "Доступ к календарю не дан. Настройки → «Дела»."))
        }
        col.addView(ev)

        val m = Secretary.mem(this)

        // Задачи
        val tasks = runCatching { m.openTasks() }.getOrDefault(emptyList())
        val tc = UiKit.card(this)
        tc.addView(UiKit.sectionHeader(this, "🗂 Задачи (${tasks.size})"))
        if (tasks.isEmpty()) tc.addView(UiKit.body(this, "Открытых задач нет"))
        else for (t in tasks) tc.addView(taskRow(t))
        col.addView(tc)

        // Напоминания
        val rem = runCatching { m.reminders() }.getOrDefault(emptyList())
        val rc = UiKit.card(this)
        rc.addView(UiKit.sectionHeader(this, "🔔 Напоминания (${rem.size})"))
        if (rem.isEmpty()) rc.addView(UiKit.body(this, "Активных напоминаний нет"))
        else for (r in rem) rc.addView(reminderRow(r))
        col.addView(rc)

        col.addView(UiKit.glassButton(this, "⟳ Обновить") { render() })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun rowPad(v: TextView): TextView {
        val p = UiKit.dp(this, 9)
        v.setPadding(0, p, 0, p)
        v.isClickable = true
        return v
    }

    private fun taskRow(t: Task): TextView {
        val df = SimpleDateFormat("d MMM HH:mm", ru)
        val due = if (t.dueMillis > 0) "   •   " + df.format(Date(t.dueMillis)) else ""
        val proj = if (t.project.isNotBlank()) " · ${t.project}" else ""
        return rowPad(UiKit.body(this, "☐  ${t.title}$proj$due")).apply {
            setOnClickListener {
                AlertDialog.Builder(this@AgendaActivity)
                    .setTitle("Закрыть задачу?")
                    .setMessage(t.title)
                    .setPositiveButton("Закрыть") { _, _ ->
                        runCatching {
                            val mem = Secretary.mem(this@AgendaActivity)
                            mem.completeTask(t.id); mem.log("Задача выполнена (экран): ${t.title}")
                            ReminderScheduler.cancelReminder(this@AgendaActivity, t.id.hashCode())
                        }
                        render()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
    }

    private fun reminderRow(r: Reminder): TextView {
        val df = SimpleDateFormat("d MMM HH:mm", ru)
        val sub = if (r.repeatMin > 0) Secretary.repeatPhrase(r.repeatMin) else "   •   " + df.format(Date(r.atMillis))
        return rowPad(UiKit.body(this, "🔔  ${r.text}$sub")).apply {
            setOnClickListener {
                AlertDialog.Builder(this@AgendaActivity)
                    .setTitle("Отменить напоминание?")
                    .setMessage(r.text)
                    .setPositiveButton("Отменить") { _, _ ->
                        runCatching {
                            ReminderScheduler.cancelReminderItem(this@AgendaActivity, r.id)
                            Secretary.mem(this@AgendaActivity).completeReminder(r.id)
                        }
                        render()
                    }
                    .setNegativeButton("Назад", null)
                    .show()
            }
        }
    }
}
