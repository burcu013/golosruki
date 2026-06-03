package ru.alexandr.golosruki

/**
 * Интерфейс офлайн-движка ИИ (на устройстве).
 * Реализации подключаются по этапам:
 *  - MediaPipeEngine («Топ» — для мощных телефонов),
 *  - LlamaCppEngine («Универсальный» — llama.cpp + GGUF).
 * Управление телефоном остаётся на грамматике; ИИ — только текст/ответы.
 */
interface AiEngine {
    fun isReady(): Boolean
    /** Сгенерировать ответ. Блокирующий вызов — выполнять в фоновом потоке. */
    fun generate(systemPrompt: String, userText: String): String
    fun unload()
}

/** Заглушка: движок ещё не подключён (реальный MediaPipe — следующий этап). */
class StubAiEngine : AiEngine {
    override fun isReady(): Boolean = false
    override fun generate(systemPrompt: String, userText: String): String =
        "ИИ-движок ещё не подключён — это преднастройка. Модель появится на следующем этапе."
    override fun unload() {}
}
