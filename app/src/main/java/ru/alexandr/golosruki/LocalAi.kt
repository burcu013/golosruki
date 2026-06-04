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

        val name = profile.name.trim()
        val sys: String
        val user: String
        if (ask) {
            sys = buildString {
                append("Ты — полезный ассистент. Отвечай на русском языке кратко и по существу. ")
                append("Дай прямой ответ на вопрос пользователя. ")
                append("НЕ задавай встречных вопросов, НЕ здоровайся, не представляйся. ")
                append("Если это пример (например, умножение) — посчитай и дай число. ")
                append("Если НЕ знаешь точного факта (номер и название закона, ссылку на источник, свежую дату, имя должностного лица) — честно скажи, что не уверен. НИКОГДА не выдумывай ссылки и источники. ")
                if (profile.style != "short") append("Можно 2–4 предложения. ") else append("Уложись в 1–2 коротких предложения. ")
            }
            user = buildAskPrompt(userText)
        } else {
            sys = "Ты помогаешь сформулировать короткое вежливое сообщение на русском языке. Выдавай только готовый текст, без пояснений и приветствий."
            val who = if (name.isNotBlank()) " от имени $name" else ""
            user = "Сформулируй короткое сообщение$who на основе: «$userText». Только текст сообщения."
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
