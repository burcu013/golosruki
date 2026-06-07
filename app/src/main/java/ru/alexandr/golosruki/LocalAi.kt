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
            val w = Weather.describe(ctx); Logger.log("AI", "Погода: $w"); return w
        }
        // 4) Мета-вопросы о самом помощнике — мгновенно, без модели (экономия).
        if (ask) metaAnswer(userText)?.let { Logger.log("AI", "Мета-ответ: $it"); return it }
        if (!engine.isReady()) {
            return "Модель ИИ не установлена. Откройте Настройки → ИИ → загрузить модель. Запрос понял: «$userText»."
        }

        val sys: String
        val user: String
        if (ask) {
            sys = buildString {
                append("Ты — Иван, голосовой помощник. Отвечай по-русски, по делу, законченной мыслью, без воды и повторов. ")
                val now = java.text.SimpleDateFormat("d MMMM yyyy, EEEE, HH:mm", java.util.Locale("ru", "RU")).format(java.util.Date())
                append("Сейчас $now. ")
                append("Не здоровайся и не переспрашивай — сразу отвечай. ")
                append("Простое — 1–2 предложения; «порассуждай», «объясни», «посоветуй» — подробнее. ")
                append("Рассуждения, советы, мнения, общие знания — отвечай содержательно, не уходи в «не знаю». ")
                append("«Не уверен» — только про то, чего не можешь знать: свежие новости, цены, номера законов, статистику, точные даты; не выдумывай их. ")
                if (profile.capabilities.contains(AiProfile.CAP_SPEECH_ONLY)) {
                    append("У пользователя не действуют руки — не предлагай действий руками и фраз «попросите близкого». ")
                }
                if (profile.name.isNotBlank()) append("Имя пользователя: ${profile.name}. ")
            }
            user = buildAskPrompt(userText)
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

    /** Подмешиваем недавние обмены, чтобы модель «помнила» предыдущие вопросы. */
    private fun buildAskPrompt(q: String): String {
        if (history.isEmpty()) return q
        val ctx = history.joinToString("\n") { "П: ${it.first}\nО: ${it.second}" }
        return "Недавний разговор (для контекста):\n$ctx\n\nТекущий вопрос: $q"
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
     * Постобработка ответа модели: убрать служебные маркеры, схлопнуть повторяющиеся
     * подряд предложения (частая болезнь маленьких моделей) и лишние пробелы.
     */
    private fun clean(s: String): String {
        var t = s.trim()
        // Обрезаем всё, что модель могла дописать после конца своей реплики.
        listOf("<end_of_turn>", "<start_of_turn>", "<eos>").forEach { m ->
            val i = t.indexOf(m); if (i >= 0) t = t.substring(0, i)
        }
        t = t.replace("<end_of_turn>", " ").replace("<start_of_turn>", " ").trim()
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
        return t.ifBlank { "Пустой ответ." }
    }
}
