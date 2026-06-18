package ru.alexandr.golosruki

import android.content.Context

/**
 * Связующий слой ИИ: профиль → промпт → движок (MediaPipe).
 *
 * Важно про слабые модели (напр. Gemma-3 1B): тяжёлый «персональный» системный промпт
 * душит маленькую модель — она начинает отыгрывать роль вместо ответа. Поэтому для вопросов
 * используем МИНИМАЛЬНЫЙ промпт с прямой командой отвечать. Персона из профиля пригодится
 * для более крупных моделей позже.
 */
object LocalAi {
    @Volatile var engine: AiEngine = StubAiEngine()
    @Volatile private var initialized = false

    // Короткая память: последние обмены «вопрос → ответ» (для «помнит предыдущий вопрос»).
    private val history = ArrayDeque<Pair<String, String>>()
    private const val HISTORY_MAX = 1


    fun clearHistory() { history.clear() }

    private fun ensureEngine(ctx: Context) {
        if (initialized) return
        initialized = true
        engine = MediaPipeEngine(ctx.applicationContext)
    }

    /** ask=true — вопрос; ask=false — сформулировать текст. Блокирующий вызов (в фоне). */
    fun answer(ctx: Context, ask: Boolean, userText: String): String {
        ensureEngine(ctx)
        val profile = AiProfile.load(ctx)
        if (!profile.enabled) return "ИИ-помощник выключен. Включите его в настройках."
        if (userText.isBlank()) return "Не расслышал. Повторите, пожалуйста."
        // 1) Точная арифметика — в коде, без модели.
        if (ask) LocalMath.eval(userText)?.let { Logger.log("AI", "Математика в коде: $it"); return it }
        // 2) Данные устройства (время/дата/день/заряд) — мгновенно из системы.
        if (ask) LocalFacts.answer(ctx, userText)?.let { Logger.log("AI", "Факт устройства: $it"); return it }
        // 3) Погода — Open-Meteo по геолокации (нужен интернет).
        if (ask && userText.lowercase().contains("погод")) {
            val s = userText.lowercase()
            val w = when {
                s.contains("послезавтра") -> Weather.daySummary(ctx, 2)
                s.contains("завтра") -> Weather.tomorrow(ctx)
                s.contains("недел") || s.contains("ближайш") || s.contains("несколько дн") -> Weather.week(ctx)
                else -> Weather.describe(ctx)
            }
            Logger.log("AI", "Погода: $w"); return w
        }
        // 4) Мета-вопросы о самом помощнике — мгновенно, без модели (экономия).
        if (ask) metaAnswer(userText)?.let { Logger.log("AI", "Мета-ответ: $it"); return it }

        // 5) Облачная модель — если включена и есть интернет (приоритет, с откатом на локальную).
        if (CloudAi.isConfigured(ctx) && Net.isOnline(ctx)) {
            val sysC: String
            val userC: String
            if (ask) {
                val now = java.text.SimpleDateFormat("d MMMM yyyy, EEEE, HH:mm", java.util.Locale("ru", "RU")).format(java.util.Date())
                sysC = buildString {
                    append("Ты — Иван, голосовой помощник и собеседник. Отвечай по-русски живой, естественной речью. ")
                    append("Сейчас $now. Не повторяй формальные приветствия («здравствуйте, чем могу помочь») и не перечисляй свои возможности — сразу по сути. ")
                    append("Глубину ответа определяй по вопросу. На простые, бытовые и светские реплики (как дела, привет, спасибо, как настроение, как сам) — КОРОТКО и по-человечески: одна-две живые фразы, можно с лёгким настроением и встречным вопросом по имени. Без «воды» и перечислений ради объёма. ")
                    append("На сложные вопросы, требующие пояснения, — отвечай развёрнуто и понятно, по делу. ")
                    Weather.cached()?.let { append("Текущая погода по датчику: $it Можешь естественно обыграть её в светской беседе (солнечно — бодро; пасмурно/дождь — «несмотря на пасмурный день, всё отлично»). Не зачитывай погоду, если о ней не спрашивали — лишь как настроение. ") }
                    append("Ответ зачитывается вслух: пиши обычным текстом, без markdown, списков, звёздочек и заголовков. ")
                    append(AiProfile.buildPersona(profile, full = true))
                }
                userC = buildAskPrompt(userText, true)
            } else {
                sysC = "Ты составляешь готовый текст на русском (письмо, сообщение, заголовок — по запросу). " +
                    "Выдавай ТОЛЬКО итоговый текст, без пояснений, приветствий и подписи, если о них прямо не просят."
                userC = "Составь текст по запросу: «$userText». Выдай только готовый текст."
            }
            val cloud = CloudAi.chat(ctx, sysC, userC)
            if (!cloud.isNullOrBlank()) {
                Logger.log("AI", "Облачный ответ")
                val out = clean(cloud)
                if (ask && out.isNotBlank()) {
                    history.addLast(userText to out)
                    while (history.size > HISTORY_MAX) history.removeFirst()
                }
                return out
            }
            Logger.log("AI", "Облако недоступно — откат на локальную модель")
        }

        // Маршрутизация между слотами моделей (умная/простая) по режиму и сложности.
        val smartPath = SettingsStore.getAiModelPath(ctx)
        val simplePath = SettingsStore.getAiModelSimplePath(ctx)
        val smartOk = MediaPipeEngine.modelInstalledAt(smartPath)
        val simpleOk = MediaPipeEngine.modelInstalledAt(simplePath)
        if (!smartOk && !simpleOk) {
            return "Модель ИИ не установлена. Откройте Настройки → ИИ → загрузить модель. Запрос понял: «$userText»."
        }
        val mode = SettingsStore.getAiRouteMode(ctx)
        val chosen = when {
            mode == "simple" && simpleOk -> simplePath
            mode == "smart" && smartOk -> smartPath
            !ask -> if (smartOk) smartPath else simplePath           // составление текста — лучше умной
            isComplex(userText) -> if (smartOk) smartPath else simplePath
            else -> if (simpleOk) simplePath else smartPath          // лёгкое — простой, если есть
        }
        val useSmart = (chosen == smartPath)
        engine.useModel(chosen)
        engine.setLowResource(!useSmart)
        Logger.log("AI", "Модель: ${if (useSmart) "умная" else "простая"}")

        val sys: String
        val user: String
        if (ask) {
            sys = buildString {
                append("Ты — Иван, голосовой помощник. Отвечай по-русски, по делу, законченной мыслью, без воды и повторов. ")
                if (useSmart) {
                    val now = java.text.SimpleDateFormat("d MMMM yyyy, EEEE, HH:mm", java.util.Locale("ru", "RU")).format(java.util.Date())
                    append("Сейчас $now. ")
                }
                append("Не здоровайся и не переспрашивай — сразу отвечай. ")
                append("Простое — 1–2 предложения; «порассуждай», «объясни», «посоветуй» — подробнее. ")
                append("Рассуждения, советы, мнения, общие знания — отвечай содержательно, не уходи в «не знаю». ")
                append("«Не уверен» — только про то, чего не можешь знать: свежие новости, цены, номера законов, статистику, точные даты; не выдумывай их. ")
                // Персона из профиля: полная для умной модели, облегчённая для простой.
                append(AiProfile.buildPersona(profile, full = useSmart))
                // Слабая модель ломается на длинных ответах и списках — держим её в коротком формате.
                if (!useSmart) append("Отвечай коротко: одно-два законченных предложения, без списков и перечислений. ")
            }
            user = buildAskPrompt(userText, useSmart)
        } else {
            sys = "Ты составляешь готовый текст на русском (письмо, сообщение, заголовок — смотри по запросу). " +
                "Выдавай ТОЛЬКО итоговый текст, без пояснений, приветствий и подписи, если о них прямо не просят."
            user = "Составь текст по запросу: «$userText». Выдай только готовый текст."
        }

        val raw = runCatching { engine.generate(sys, user) }
            .getOrElse { "Не удалось получить ответ ИИ. Попробуйте ещё раз." }
        val out = if (raw.startsWith("Модель") || raw.startsWith("Ошибка") || raw.startsWith("Не удалось")) raw else clean(raw)

        if (ask && out.isNotBlank() && !out.startsWith("Модель") && !out.startsWith("Ошибка")) {
            history.addLast(userText to out)
            while (history.size > HISTORY_MAX) history.removeFirst()
        }
        return out
    }

    /**
     * Подмешиваем недавние обмены, чтобы модель «помнила» предыдущие вопросы.
     * Только для умной модели: слабая (простая) не отделяет контекст от вопроса и «заражается»
     * прошлой темой (вода → термодинамика → собаки…), поэтому ей историю не даём.
     */
    private fun buildAskPrompt(q: String, useHistory: Boolean): String {
        if (!useHistory || history.isEmpty()) return q
        val ctx = history.joinToString("\n") { "П: ${it.first}\nО: ${it.second}" }
        return "Недавний разговор (для контекста):\n$ctx\n\nТекущий вопрос: $q"
    }

    /** Тяжёлый ли запрос — для выбора умной модели в авто-режиме. */
    private fun isComplex(q: String): Boolean {
        val s = q.lowercase()
        val kw = listOf(
            "порассужд", "рассужд", "размышл", "объясни", "почему", "сравни", "разниц",
            "посоветуй", "совет", "придумай", "сочини", "напиши", "составь", "план",
            "идеи", "как лучше", "что лучше", "как мне", "стих", "расскажи про", "расскажи о"
        )
        if (kw.any { s.contains(it) }) return true
        return q.trim().split(Regex("\\s+")).size > 8
    }

    /** Мгновенные ответы про самого помощника — без обращения к модели. */
    private fun metaAnswer(q: String): String? {
        val s = q.lowercase()
        val about = s.contains("как тебя зовут") || s.contains("как тебя звать") ||
            s.contains("кто ты") || s.contains("ты кто") || s.contains("как тебя") && s.contains("имя")
        if (about) return "Меня зовут Иван — ваш голосовой помощник."
        if (s.contains("что ты умеешь") || s.contains("на что ты способен") || s.contains("твои возможности"))
            return "Я отвечаю на вопросы и рассуждаю, считаю, говорю время, дату, заряд и погоду, набираю текст и помогаю управлять телефоном голосом."
        return null
    }

    /**
     * Срез повтора подстроки без границ слов: «order)order)order)…», «abcabcabc…».
     * Ищем кусок длиной 3–16 символов, повторяющийся ≥4 раз подряд, и обрезаем в его начале.
     */
    private fun stripCharLoop(t: String): String {
        if (t.length < 24) return t
        for (len in 3..16) {
            var i = 0
            while (i + len * 4 <= t.length) {
                val seg = t.substring(i, i + len)
                if (seg.isNotBlank()) {
                    var reps = 1; var j = i + len
                    while (j + len <= t.length && t.regionMatches(j, seg, 0, len)) { reps++; j += len }
                    if (reps >= 4) return t.substring(0, i).trim()
                }
                i++
            }
        }
        return t
    }

    /**
     * Срез зацикленных повторов — болезнь слабых моделей: «с натяжкой с натяжкой…»,
     * «слягальщик слягальщик…», «с помощью сбора данных…» по кругу. Ищем самое раннее место,
     * где блок из 1–6 слов повторяется ≥3 раз подряд, и обрезаем вывод там (оставляем «здоровый» префикс).
     */
    private fun stripRepetition(t: String): String {
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 6) return t
        val lw = words.map { it.lowercase() }
        var cut = -1
        loop@ for (i in words.indices) {
            for (l in 1..6) {
                if (i + 3 * l > words.size) continue
                var same = true
                for (k in 0 until l) {
                    if (lw[i + k] != lw[i + l + k] || lw[i + k] != lw[i + 2 * l + k]) { same = false; break }
                }
                if (same) { cut = i; break@loop }
            }
        }
        if (cut < 0) return t
        return words.subList(0, cut).joinToString(" ")
    }

    /**
     * Обрезка оборванного хвоста (модель упёрлась в лимит токенов и закончила на полуслове:
     * «…с хоро», «…* М», «3. За»). Короткие ответы не трогаем — они обычно цельные.
     * Длинный ответ без финальной точки режем до последней границы предложения и убираем
     * висячий маркер списка. Если границы нет — отрезаем оборванное слово и ставим «…».
     */
    private fun trimUnfinished(t: String): String {
        val s = t.trimEnd()
        if (s.isEmpty()) return s
        if (s.last() == '.' || s.last() == '!' || s.last() == '?' || s.last() == '…') return s
        if (s.split(Regex("\\s+")).size <= 10) return s   // короткое — вероятно закончено
        var idx = -1
        for (i in s.indices) { val c = s[i]; if (c == '.' || c == '!' || c == '?' || c == '…') idx = i }
        var r = if (idx >= 2) s.substring(0, idx + 1)
                else s.substringBeforeLast(' ', s).trimEnd() + "…"
        // Убираем оставшийся висячий маркер списка в конце («… 3.», «… *»).
        r = r.replace(Regex("\\s*\\d+\\.\\s*$"), "").replace(Regex("\\s*[*•]\\s*$"), "").trimEnd()
        return r.ifBlank { s }
    }

    /**
     * Постобработка ответа модели: убрать служебные маркеры, срезать зацикленные повторы,
     * схлопнуть повторяющиеся подряд предложения, обрезать оборванный хвост и лишние пробелы.
     */
    private fun clean(s: String): String {
        var t = s.trim()
        // Обрезаем всё, что модель могла дописать после конца своей реплики.
        listOf("<end_of_turn>", "<start_of_turn>", "<eos>").forEach { m ->
            val i = t.indexOf(m); if (i >= 0) t = t.substring(0, i)
        }
        t = t.replace("<end_of_turn>", " ").replace("<start_of_turn>", " ").trim()
        // Срез зацикленных повторов (до дедупликации предложений).
        val stripped = stripRepetition(stripCharLoop(t))
        val wasCut = stripped.length < t.length
        t = stripped
        // Схлопываем подряд идущие одинаковые предложения.
        val parts = t.split(Regex("(?<=[.!?…])\\s+"))
        val uniq = ArrayList<String>()
        for (p in parts) {
            val norm = p.trim().lowercase()
            if (norm.isNotEmpty() && (uniq.isEmpty() || uniq.last().trim().lowercase() != norm)) uniq.add(p.trim())
        }
        t = uniq.joinToString(" ")
        // Чистим пробелы/переносы.
        t = t.replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        // Если после среза повторов остался лишь обрывок — честный ответ вместо мусора.
        val wc = t.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (wasCut && wc < 3)
            return "Не получилось внятно ответить — простая модель не справилась. Для сложных вопросов включите умную модель в Настройки → Модели ИИ."
        // Обрезаем оборванный по лимиту токенов хвост.
        t = trimUnfinished(t)
        return t.ifBlank { "Пустой ответ." }
    }
}
