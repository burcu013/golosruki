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
    private const val HISTORY_MAX = 3

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
        if (!engine.isReady()) {
            return "Модель ИИ не установлена. Откройте Настройки → ИИ → загрузить модель. Запрос понял: «$userText»."
        }

        val sys: String
        val user: String
        if (ask) {
            sys = buildString {
                append("Ты — голосовой помощник. Отвечай на русском языке кратко и точно, сразу по сути вопроса. ")
                append("Не здоровайся, не представляйся, не задавай встречных вопросов. ")
                append("Арифметику считай аккуратно и перепроверяй результат перед ответом. ")
                append("Не выдумывай факты, законы, цены, даты и ссылки — если точно не знаешь, коротко скажи «не знаю» или «не уверен». ")
                if (profile.capabilities.contains(AiProfile.CAP_SPEECH_ONLY)) {
                    append("У пользователя не действуют руки — не предлагай ему физических действий руками. ")
                    append("ВАЖНО: НЕ добавляй в ответ фразы вроде «обратитесь за помощью», «попросите близкого человека», «воспользуйтесь помощником» — просто дай прямой ответ на вопрос. ")
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
