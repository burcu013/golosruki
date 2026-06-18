package ru.alexandr.golosruki

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Результат разбора плана. ok=false — не удалось определить дату/смысл. */
data class PlanResult(
    val ok: Boolean,
    val title: String,
    val person: String,
    val project: String,
    val note: String,
    val durationMin: Int,
    val reminderMin: Int,
    val startMillis: Long,
    val allDay: Boolean,
    val error: String = ""
)

/** Планировщик-секретарь: свободная фраза → структурированное событие (через облачный LLM). */
object Secretary {

    @Volatile private var memCache: SecretaryMemory? = null
    fun mem(ctx: Context): SecretaryMemory =
        memCache ?: LocalMemory(ctx.applicationContext).also { memCache = it }

    private val ru = Locale("ru")

    fun plan(ctx: Context, text: String): PlanResult {
        val m = mem(ctx)
        val now = Calendar.getInstance()
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm, EEEE", ru).format(now.time)
        val projects = m.projects().joinToString(", ").ifBlank { "—" }
        val people = m.people().joinToString(", ").ifBlank { "—" }

        val system = "Ты — планировщик-секретарь. Преобразуй фразу пользователя в ОДНО событие календаря. " +
            "Ответь ТОЛЬКО объектом JSON, без пояснений и markdown. Поля: " +
            "title (о чём, кратко), person (с кем или пустая строка), project (проект/дело или пустая строка), " +
            "date (YYYY-MM-DD), time (HH:MM в 24-часовом формате или пустая строка), " +
            "duration_min (целое, по умолчанию 60), reminder_min (за сколько минут напомнить, по умолчанию 0), " +
            "note (детали или пустая строка). " +
            "Относительные даты («сегодня», «завтра», «в четверг», «через неделю») переводи в абсолютную дату от текущей. " +
            "Время суток словами: утро=10:00, день=14:00, вечер=19:00. Если время не названо — оставь time пустым. " +
            "Сопоставляй упомянутые проекты и имена с известными по смыслу. " +
            "Известные проекты: $projects. Известные люди: $people."
        val user = "Текущие дата и время: $nowStr. Фраза: \"$text\""

        val raw = CloudAi.chat(ctx, system, user)
            ?: return PlanResult(false, "", "", "", "", 60, 0, 0, false, CloudAi.lastError.ifBlank { "нет ответа модели" })
        val obj = extractJson(raw) ?: return PlanResult(false, "", "", "", "", 60, 0, 0, false, "не понял план")

        val title = obj.optString("title").trim()
        val person = obj.optString("person").trim()
        val project = obj.optString("project").trim()
        val note = obj.optString("note").trim()
        val duration = obj.optInt("duration_min", 60).coerceIn(15, 600)
        val reminder = obj.optInt("reminder_min", 0).coerceIn(0, 10080)
        val date = obj.optString("date").trim()
        val time = obj.optString("time").trim()

        if (title.isBlank()) return PlanResult(false, "", "", "", "", duration, reminder, 0, false, "не понял суть")
        if (date.isBlank() || !date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            return PlanResult(false, title, person, project, note, duration, reminder, 0, false, "не понял дату")

        val allDay = time.isBlank() || !time.matches(Regex("\\d{1,2}:\\d{2}"))
        // Напоминание по умолчанию, если не названо: за 30 мин (со временем) / заранее (на весь день).
        val reminderFinal = if (reminder > 0) reminder else if (allDay) 540 else 30
        val cal = Calendar.getInstance()
        try {
            val d = SimpleDateFormat("yyyy-MM-dd", ru).parse(date) ?: throw IllegalArgumentException()
            cal.time = d
            if (!allDay) {
                val hm = time.split(":")
                cal.set(Calendar.HOUR_OF_DAY, hm[0].toInt())
                cal.set(Calendar.MINUTE, hm[1].toInt())
            } else {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            }
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        } catch (e: Exception) {
            return PlanResult(false, title, person, project, note, duration, reminder, 0, false, "не понял дату")
        }
        return PlanResult(true, title, person, project, note, duration, reminderFinal, cal.timeInMillis, allDay)
    }

    /** Ответ на вопрос по памяти (этап B). Онлайн — LLM по релевантному срезу; офлайн — локальный список. */
    fun answer(ctx: Context, question: String): String {
        val m = mem(ctx)
        val tasks = m.searchTasks(question)
        val log = m.searchLog(question, 12)
        val online = CloudAi.isConfigured(ctx) && Net.isOnline(ctx)
        val df = SimpleDateFormat("d MMMM HH:mm", ru)

        fun listTasks(ts: List<Task>) = ts.take(10).joinToString("\n") { t ->
            "- ${t.title}" +
                (if (t.project.isNotBlank()) ", проект ${t.project}" else "") +
                (if (t.person.isNotBlank()) ", с ${t.person}" else "") +
                (if (t.dueMillis > 0) ", ${df.format(Date(t.dueMillis))}" else "")
        }

        if (!online) {
            if (tasks.isEmpty() && log.isEmpty()) return "По этому запросу в памяти ничего нет."
            val sb = StringBuilder()
            if (tasks.isNotEmpty()) sb.append("Задачи:\n").append(listTasks(tasks))
            if (log.isNotEmpty()) { if (sb.isNotEmpty()) sb.append("\n"); sb.append("Записи:\n").append(log.take(6).joinToString("\n") { "- $it" }) }
            return sb.toString()
        }

        val ctxText = buildString {
            append("Открытые задачи по запросу:\n").append(if (tasks.isNotEmpty()) listTasks(tasks) else "нет")
            append("\n\nЗаписи журнала по запросу:\n").append(if (log.isNotEmpty()) log.joinToString("\n") { "- $it" } else "нет")
            append("\n\nИзвестные проекты: ").append(m.projects().joinToString(", ").ifBlank { "—" })
            append("\nИзвестные люди: ").append(m.people().joinToString(", ").ifBlank { "—" })
        }
        val system = "Ты — личный секретарь. Отвечай КРАТКО и для озвучки вслух, ТОЛЬКО по данным из памяти ниже. " +
            "Если данных по вопросу нет — честно скажи, что в памяти ничего нет. Без markdown, без списков-маркеров, обычной речью."
        val user = "Память:\n$ctxText\n\nВопрос: $question"
        return CloudAi.chat(ctx, system, user)?.takeIf { it.isNotBlank() }
            ?: if (tasks.isEmpty() && log.isEmpty()) "В памяти ничего нет по этому вопросу." else listTasks(tasks)
    }

    /** Фраза для голосового подтверждения. */
    fun confirmPhrase(p: PlanResult): String {
        val whenStr = if (p.allDay)
            SimpleDateFormat("EEEE d MMMM", ru).format(Date(p.startMillis)) + ", весь день"
        else
            SimpleDateFormat("EEEE d MMMM, HH:mm", ru).format(Date(p.startMillis))
        val sb = StringBuilder("Запланировать: ${p.title}")
        if (p.person.isNotBlank()) sb.append(" с ${p.person}")
        if (p.project.isNotBlank()) sb.append(" по ${p.project}")
        sb.append(" — $whenStr")
        if (p.reminderMin > 0) {
            val r = if (p.reminderMin % 60 == 0) "${p.reminderMin / 60} ч" else "${p.reminderMin} мин"
            sb.append(", напомню за $r")
        }
        sb.append(". Скажите да или нет.")
        return sb.toString()
    }

    fun description(p: PlanResult): String {
        val parts = ArrayList<String>()
        if (p.person.isNotBlank()) parts.add("С кем: ${p.person}")
        if (p.project.isNotBlank()) parts.add("Проект: ${p.project}")
        if (p.note.isNotBlank()) parts.add(p.note)
        parts.add("Создано Иваном (ГолосРуки)")
        return parts.joinToString("\n")
    }

    /** Утренняя сводка/брифинг (этап C): приветствие + события дня + задачи на сегодня + погода. */
    fun briefing(ctx: Context): String {
        val cal = Calendar.getInstance()
        val greet = when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 0..4 -> "Доброй ночи"; in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; else -> "Добрый вечер"
        }
        val events = if (CalendarReader.hasAccess(ctx)) CalendarReader.daySummary(ctx, 0)
            else "Доступ к календарю не дан."
        val endToday = cal.apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
        }.timeInMillis
        val tasks = mem(ctx).openTasks().filter { it.dueMillis in 1..endToday }
        val tasksStr = if (tasks.isEmpty()) "Задач на сегодня нет."
            else "Задачи на сегодня: " + tasks.joinToString(", ") { it.title } + "."
        val weather = runCatching { Weather.describe(ctx) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.contains("не удал", true) && !it.contains("ошибк", true) && !it.contains("нет доступ", true) }
        val sb = StringBuilder("$greet. $events $tasksStr")
        if (weather != null) sb.append(" Погода: $weather")
        return sb.toString()
    }

    private fun extractJson(s: String): JSONObject? {
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(s.substring(a, b + 1)) } catch (e: Exception) { null }
    }
}
