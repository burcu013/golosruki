package ru.alexandr.golosruki

import android.content.Context

/**
 * Связующий слой ИИ: профиль → системный промпт → движок.
 * Сейчас движок — заглушка (StubAiEngine). На следующем этапе сюда подключается
 * MediaPipe («Топ») или llama.cpp («Универсальный»), не меняя остальной логики.
 */
object LocalAi {
    @Volatile var engine: AiEngine = StubAiEngine()

    /** ask=true — вопрос; ask=false — сформулировать текст. Блокирующий вызов (в фоне). */
    fun answer(ctx: Context, ask: Boolean, userText: String): String {
        val profile = AiProfile.load(ctx)
        if (!profile.enabled) return "ИИ-помощник выключен. Включите его в настройках."
        if (userText.isBlank()) return "Не расслышал. Повторите, пожалуйста."
        if (!engine.isReady()) {
            return "ИИ ещё не подключён — движок добавим на следующем шаге. Запрос я понял: «$userText»."
        }
        val sys = AiProfile.buildSystemPrompt(profile)
        val prompt = if (ask) userText
            else "Сформулируй короткое вежливое сообщение на основе: «$userText». Выдай только итоговый текст без пояснений."
        return runCatching { engine.generate(sys, prompt) }
            .getOrElse { "Не удалось получить ответ ИИ. Попробуйте ещё раз." }
    }
}
