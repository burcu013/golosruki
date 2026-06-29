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
    val error: String = "",
    val kind: String = "event",     // event | task | reminder
    val repeatMin: Int = 0          // напоминание: 0 разовое, 1440 ежедневно, 10080 еженедельно, N каждые N мин
)

/** Планировщик-секретарь: свободная фраза → структурированное событие (через облачный LLM). */
object Secretary {

    @Volatile private var memCache: SecretaryMemory? = null
    fun mem(ctx: Context): SecretaryMemory =
        memCache ?: LocalMemory(ctx.applicationContext).also { memCache = it }

    private val ru = Locale("ru")

    fun plan(ctx: Context, text: String, hintKind: String = "event"): PlanResult {
        val m = mem(ctx)
        val now = Calendar.getInstance()
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm, EEEE", ru).format(now.time)
        val projects = m.projects().joinToString(", ").ifBlank { "—" }
        val people = m.people().joinToString(", ").ifBlank { "—" }

        val system = "Ты — планировщик-секретарь. Преобразуй фразу пользователя в ОДНУ запись. " +
            "Ответь ТОЛЬКО объектом JSON, без пояснений и markdown. Поля: " +
            "title (о чём, кратко), person (с кем или пустая строка), project (проект/дело или пустая строка), " +
            "date (YYYY-MM-DD или пустая строка, если дата не нужна), time (HH:MM в 24-часовом формате или пустая строка), " +
            "duration_min (целое, по умолчанию 60), reminder_min (за сколько минут напомнить о событии, по умолчанию 0), " +
            "repeat_min (для повторяющихся напоминаний: 0 — разово, 1440 — каждый день, 10080 — каждую неделю, 60 — каждый час, либо число минут для «каждые N минут/часов»), " +
            "note (детали или пустая строка). " +
            "Относительные даты («сегодня», «завтра», «в четверг», «через неделю») переводи в абсолютную дату от текущей. " +
            "Время суток словами: утро=10:00, день=14:00, вечер=19:00. Если время не названо — оставь time пустым. " +
            "Сопоставляй упомянутые проекты и имена с известными по смыслу. " +
            "Известные проекты: $projects. Известные люди: $people."
        val user = "Текущие дата и время: $nowStr. Фраза: \"$text\""

        // v8.21: ГИБРИДНЫЙ разбор. Приоритет:
        //   1) Облако (tryCloud 8с) — лучшее качество, сопоставление проектов/людей.
        //   2) Локальный ДЕТЕРМИНИРОВАННЫЙ разбор (localParse) — regex по русским датам/времени/повтору.
        //      Мгновенно, без 30-секундной локальной LLM, никогда не выдаёт мусор. Покрывает явные фразы
        //      («сегодня», «завтра в 9», «через час», «каждый день»). Для task почти всегда достаточен.
        //   3) Локальная LLM (generateRaw) — последний шанс, если localParse не уверен (нет даты для event).
        // Раньше (8.20) при 429 шли сразу в локальную LLM — она плохо держит JSON и выдавала мусор
        // («посчитать зарплату» → «не понял»). Детерминированный разбор это чинит.
        val online = CloudAi.isConfigured(ctx) && Net.isOnline(ctx)
        var raw: String? = null
        if (online) {
            raw = LocalAi.tryCloud(8000) { CloudAi.chat(ctx, system, user) }
            if (raw.isNullOrBlank()) Logger.log("SEC", "Планировщик: облако недоступно/429 — локальный разбор")
        }
        if (raw.isNullOrBlank()) {
            // 2) детерминированный разбор — строит тот же JSON-формат, что и облако
            raw = localParse(text, hintKind, now)
            if (!raw.isNullOrBlank()) Logger.log("SEC", "Планировщик: разобрано локально (без модели)")
        }
        if (raw.isNullOrBlank()) {
            // 3) локальная LLM — крайний случай
            raw = LocalAi.generateRaw(ctx, system, user, preferSmart = true)
            if (!raw.isNullOrBlank()) Logger.log("SEC", "Планировщик: разобрано локальной моделью")
        }
        if (raw.isNullOrBlank()) {
            return PlanResult(false, "", "", "", "", 60, 0, 0, false,
                if (online) "не понял" else "нет связи и локальная модель недоступна")
        }
        val obj = extractJson(raw) ?: return PlanResult(false, "", "", "", "", 60, 0, 0, false, "не понял")

        val title = obj.optString("title").trim()
        val person = obj.optString("person").trim()
        val project = obj.optString("project").trim()
        val note = obj.optString("note").trim()
        val duration = obj.optInt("duration_min", 60).coerceIn(15, 600)
        val reminder = obj.optInt("reminder_min", 0).coerceIn(0, 10080)
        val repeatMin = obj.optInt("repeat_min", 0).coerceIn(0, 100000)
        val date = obj.optString("date").trim()
        val time = obj.optString("time").trim()

        if (title.isBlank()) return PlanResult(false, "", "", "", "", duration, reminder, 0, false, "не понял суть")

        val hasDate = date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
        val hasTime = time.matches(Regex("\\d{1,2}:\\d{2}"))
        // Вычисляем момент времени, если есть дата (и опц. время).
        var startMillis = 0L
        if (hasDate) {
            val cal = Calendar.getInstance()
            try {
                val d = SimpleDateFormat("yyyy-MM-dd", ru).parse(date)!!
                cal.time = d
                if (hasTime) { val hm = time.split(":"); cal.set(Calendar.HOUR_OF_DAY, hm[0].toInt()); cal.set(Calendar.MINUTE, hm[1].toInt()) }
                else { cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0) }
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                startMillis = cal.timeInMillis
            } catch (e: Exception) { startMillis = 0L }
        }
        val allDay = !hasTime

        // Маршрутизация по намерению (тип задаёт фраза-триггер).
        var kind = hintKind
        // Напоминание без конкретного времени превращаем в задачу (нечего «звонить»).
        if (kind == "reminder" && (!hasDate || !hasTime)) kind = "task"

        return when (kind) {
            "task" -> PlanResult(true, title, person, project, note, duration, reminder,
                startMillis, allDay, "", "task", 0)
            "reminder" -> {
                var sm = startMillis
                if (repeatMin == 0 && sm in 1..System.currentTimeMillis()) sm += 86_400_000L  // время уже прошло — на завтра
                PlanResult(true, title, person, project, note, duration, 0,
                    sm, false, "", "reminder", repeatMin)
            }
            else -> { // event
                if (startMillis == 0L) return PlanResult(false, title, person, project, note, duration, reminder, 0, false, "не понял дату")
                val reminderFinal = if (reminder > 0) reminder else if (allDay) 540 else 30
                PlanResult(true, title, person, project, note, duration, reminderFinal, startMillis, allDay, "", "event", 0)
            }
        }
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
    /** Человеческое описание периодичности напоминания. */
    fun repeatPhrase(repeatMin: Int): String = when {
        repeatMin <= 0 -> ""
        repeatMin == 1440 -> ", каждый день"
        repeatMin == 10080 -> ", каждую неделю"
        repeatMin % 1440 == 0 -> ", каждые ${repeatMin / 1440} дн."
        repeatMin % 60 == 0 -> ", каждые ${repeatMin / 60} ч."
        else -> ", каждые $repeatMin мин."
    }

    fun confirmPhrase(p: PlanResult): String {
        if (p.kind == "reminder") {
            val whenStr = SimpleDateFormat("EEEE d MMMM, HH:mm", ru).format(Date(p.startMillis))
            return "Напоминать: ${p.title} — $whenStr${repeatPhrase(p.repeatMin)}. Скажите да или нет."
        }
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
        val now = System.currentTimeMillis()
        val open = mem(ctx).openTasks()
        val overdue = open.filter { it.dueMillis in 1 until now }
        val tasks = open.filter { it.dueMillis in now..endToday }
        val tasksStr = if (tasks.isEmpty()) "Задач на сегодня нет."
            else "Задачи на сегодня: " + tasks.joinToString(", ") { it.title } + "."
        val overdueStr = if (overdue.isEmpty()) "" else " Просрочено: " + overdue.joinToString(", ") { it.title } + "."
        val weather = runCatching { Weather.describe(ctx) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.contains("не удал", true) && !it.contains("ошибк", true) && !it.contains("нет доступ", true) }
        val sb = StringBuilder("$greet. $events $tasksStr$overdueStr")
        if (weather != null) sb.append(" Погода: $weather")
        return sb.toString()
    }

    /**
     * v8.21: ДЕТЕРМИНИРОВАННЫЙ локальный разбор фразы в JSON (тот же формат, что отдаёт облако).
     * Без LLM — regex/словарь по русской речи. Мгновенно и без мусора. Возвращает JSON-строку или null
     * (если совсем ничего не вытащил — тогда выше попробуют локальную LLM).
     * Покрывает явные случаи; хитрые формулировки оставляем облаку/LLM.
     */
    private fun localParse(text: String, hintKind: String, now: Calendar): String? {
        val t = " " + text.lowercase(ru).trim() + " "
        val cal = now.clone() as Calendar
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        var hasDate = false
        var hasTime = false
        var repeatMin = 0

        // --- ПОВТОР ---
        when {
            Regex("кажд\\w* день|ежедневн").containsMatchIn(t) -> { repeatMin = 1440 }
            Regex("кажд\\w* недел|еженедельн").containsMatchIn(t) -> { repeatMin = 10080 }
            Regex("кажд\\w* час|ежечасн").containsMatchIn(t) -> { repeatMin = 60 }
            else -> {
                Regex("кажд\\w* (\\d+) (минут|час)").find(t)?.let { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: 0
                    repeatMin = if (m.groupValues[2].startsWith("час")) n * 60 else n
                }
            }
        }

        // --- ОТНОСИТЕЛЬНАЯ ДАТА ---
        when {
            t.contains("послезавтра") -> { cal.add(Calendar.DAY_OF_YEAR, 2); hasDate = true }
            t.contains("завтра") -> { cal.add(Calendar.DAY_OF_YEAR, 1); hasDate = true }
            t.contains("сегодня") -> { hasDate = true }
            else -> {
                // «через N часов/минут» — от текущего момента
                Regex("через (\\d+|час|полчаса|минут\\w*) ?(час\\w*|минут\\w*)?").find(t)?.let { m ->
                    val g1 = m.groupValues[1]; val g2 = m.groupValues[2]
                    when {
                        g1 == "час" || g2.startsWith("час") -> {
                            val n = g1.toIntOrNull() ?: 1
                            cal.add(Calendar.HOUR_OF_DAY, n); hasDate = true; hasTime = true
                        }
                        g1 == "полчаса" -> { cal.add(Calendar.MINUTE, 30); hasDate = true; hasTime = true }
                        g2.startsWith("минут") -> {
                            val n = g1.toIntOrNull() ?: 0
                            if (n > 0) { cal.add(Calendar.MINUTE, n); hasDate = true; hasTime = true }
                        }
                    }
                }
                // «через N дней/недель»
                Regex("через (\\d+) (дн\\w*|недел\\w*)").find(t)?.let { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: 0
                    if (m.groupValues[2].startsWith("недел")) cal.add(Calendar.DAY_OF_YEAR, n * 7)
                    else cal.add(Calendar.DAY_OF_YEAR, n)
                    hasDate = true
                }
                // «через неделю» без числа
                if (!hasDate && Regex("через недел\\w*").containsMatchIn(t)) { cal.add(Calendar.DAY_OF_YEAR, 7); hasDate = true }
                // День недели: «в понедельник» … — ближайший будущий
                if (!hasDate) {
                    val dows = listOf("воскресенье" to Calendar.SUNDAY, "понедельник" to Calendar.MONDAY,
                        "вторник" to Calendar.TUESDAY, "сред" to Calendar.WEDNESDAY, "четверг" to Calendar.THURSDAY,
                        "пятниц" to Calendar.FRIDAY, "суббот" to Calendar.SATURDAY)
                    for ((word, dow) in dows) {
                        if (t.contains(" $word") || t.contains("в $word") || t.contains("во $word")) {
                            var add = (dow - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                            if (add == 0) add = 7   // «в понедельник», когда сегодня понедельник → следующий
                            cal.add(Calendar.DAY_OF_YEAR, add); hasDate = true; break
                        }
                    }
                }
            }
        }

        // --- ВРЕМЯ (если ещё не задано через «через N часов») ---
        if (!hasTime) {
            // «в 9», «в 14:30», «в 9 утра/вечера»
            val tm = Regex("в (\\d{1,2})[:.](\\d{2})").find(t) ?: Regex("в (\\d{1,2}) (час\\w*|утра|вечера|дня|ночи)?").find(t)
            if (tm != null) {
                var h = tm.groupValues[1].toIntOrNull() ?: -1
                val mn = tm.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                val suffix = tm.groupValues.getOrNull(2) ?: ""
                if (h in 0..23) {
                    // «вечера»/«дня» → +12 для 1..11
                    if ((suffix == "вечера" || suffix == "дня") && h in 1..11) h += 12
                    if (suffix == "ночи" && h == 12) h = 0
                    cal.set(Calendar.HOUR_OF_DAY, h)
                    cal.set(Calendar.MINUTE, if (mn in 0..59) mn else 0)
                    hasTime = true
                    if (!hasDate) hasDate = true   // время названо без даты — считаем сегодня
                }
            } else {
                // словесное время суток
                when {
                    t.contains("утром") || t.contains(" утра") -> { cal.set(Calendar.HOUR_OF_DAY, 10); cal.set(Calendar.MINUTE, 0); hasTime = true; if (!hasDate) hasDate = true }
                    t.contains("днём") || t.contains("днем") -> { cal.set(Calendar.HOUR_OF_DAY, 14); cal.set(Calendar.MINUTE, 0); hasTime = true; if (!hasDate) hasDate = true }
                    t.contains("вечером") -> { cal.set(Calendar.HOUR_OF_DAY, 19); cal.set(Calendar.MINUTE, 0); hasTime = true; if (!hasDate) hasDate = true }
                }
            }
        }

        // --- ЗАГОЛОВОК: чистим служебные слова даты/времени/повтора ---
        var title = text.trim()
        val strip = listOf(
            "\\bпослезавтра\\b", "\\bзавтра\\b", "\\bсегодня\\b", "\\bобязательно\\b",
            "через \\d+ (?:дн\\w*|недел\\w*|час\\w*|минут\\w*)", "через недел\\w*", "через час\\w*", "через полчаса",
            "в \\d{1,2}[:.]\\d{2}", "в \\d{1,2} (?:час\\w*|утра|вечера|дня|ночи)", "в \\d{1,2}\\b",
            "кажд\\w* день", "кажд\\w* недел\\w*", "кажд\\w* час", "ежедневн\\w*", "еженедельн\\w*",
            "утром", "вечером", "днём", "днем", "\\bв понедельник\\b", "\\bво вторник\\b", "\\bв среду\\b",
            "\\bв четверг\\b", "\\bв пятницу\\b", "\\bв субботу\\b", "\\bв воскресенье\\b"
        )
        for (p in strip) title = title.replace(Regex(p, RegexOption.IGNORE_CASE), " ")
        title = title.replace(Regex("\\s+"), " ").trim().trimStart(',', '.', ' ').trim()
        if (title.isBlank()) title = text.trim()   // если вычистили всё — вернём исходное

        // если совсем ничего не распознали (ни даты, ни повтора) и это event — пусть пробует LLM
        if (!hasDate && repeatMin == 0 && hintKind == "event") return null

        // Собираем JSON в формате облака
        val date = if (hasDate) SimpleDateFormat("yyyy-MM-dd", ru).format(cal.time) else ""
        val time = if (hasTime) SimpleDateFormat("HH:mm", ru).format(cal.time) else ""
        val obj = JSONObject()
        obj.put("title", title)
        obj.put("person", "")
        obj.put("project", "")
        obj.put("date", date)
        obj.put("time", time)
        obj.put("duration_min", 60)
        obj.put("reminder_min", 0)
        obj.put("repeat_min", repeatMin)
        obj.put("note", "")
        return obj.toString()
    }

    private fun extractJson(s: String): JSONObject? {
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(s.substring(a, b + 1)) } catch (e: Exception) { null }
    }
}
