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
        if (!engine.isReady()) {
            return "Модель ИИ не установлена. Откройте Настройки → ИИ → загрузить модель. Запрос понял: «$userText»."
        }

        val sys: String
        val user: String
        if (ask) {
            sys = buildString {
                append("Ты — Иван, умный доброжелательный голосовой помощник. ")
                append("Свободно общаешься на любые темы: рассуждаешь, советуешь, придумываешь, делишься мнением. Отвечай по-русски, живо и по сути. ")
                val now = java.text.SimpleDateFormat("d MMMM yyyy, EEEE, HH:mm", java.util.Locale("ru", "RU")).format(java.util.Date())
                append("Сейчас $now. ")
                append("Не здоровайся и не задавай встречных вопросов — сразу отвечай. ")
                append("На мнения, рассуждения, советы и общие знания отвечай содержательно, не отговаривайся «не знаю». ")
                append("«Не уверен» говори только про факты, которых не можешь знать (свежие новости, точные цены, номера законов, статистику, даты) — их не выдумывай. ")
                if (profile.capabilities.contains(AiProfile.CAP_SPEECH_ONLY)) {
                    append("У пользователя не действуют руки — не предлагай действий руками и фраз «попросите близкого». ")
                }
                if (profile.name.isNotBlank()) append("Имя пользователя: ${profile.name}. ")
            }
            user = buildAskPrompt(userText)
        } else {
            sys = "Ты помогаешь составить готовый текст на русском языке (письмо, сообщение, заголовок, ответ — смотри по запросу). " +
                "Выдавай ТОЛЬКО итоговый текст, без пояснений. Не добавляй приветствие и подпись, если о них прямо не просят."
            user = "Составь текст по запросу: «$userText». Выдай только готовый текст."
        }

        val out = runCatching { engine.generate(sys, user) }
            .getOrElse { "Не удалось получить ответ ИИ. Попробуйте ещё раз." }

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
}
