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
    private const val HISTORY_MAX = 2

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
                append("Ты — умный, доброжелательный голосовой помощник по имени Иван. ")
                append("Свободно и охотно общаешься на любые темы: рассуждаешь, объясняешь, советуешь, придумываешь, делишься мнением, поддерживаешь беседу. ")
                append("Отвечай на русском языке — живо, по сути, без лишней воды. ")
                val now = java.text.SimpleDateFormat("d MMMM yyyy 'года', EEEE, HH:mm", java.util.Locale("ru", "RU")).format(java.util.Date())
                append("Текущие дата и время: $now. ")
                append("Не здоровайся, не представляйся и не задавай лишних встречных вопросов — сразу отвечай. ")
                append("Если просят порассуждать, придумать, объяснить, посоветовать или поговорить — делай это уверенно и настолько развёрнуто, насколько нужно. ")
                append("НЕ отвечай «не знаю» на вопросы про мнение, рассуждение, творчество, советы или общие знания — на них всегда отвечай содержательно. ")
                append("«Не знаю» или «не уверен» говори ТОЛЬКО когда спрашивают конкретный проверяемый факт, который ты не можешь знать: свежие новости и события, точные цены, номера законов и статей, статистику, точные даты, чьи-то личные данные. В этих случаях не выдумывай — честно скажи, что не уверен. ")
                append("Числа и арифметику считай аккуратно. ")
                if (profile.capabilities.contains(AiProfile.CAP_SPEECH_ONLY)) {
                    append("У пользователя не действуют руки — не предлагай физических действий руками. ")
                    append("Не добавляй фразы вроде «обратитесь за помощью» или «попросите близкого» — просто дай прямой ответ. ")
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
