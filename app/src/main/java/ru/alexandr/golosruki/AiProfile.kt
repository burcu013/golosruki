package ru.alexandr.golosruki

import android.content.Context

/**
 * Преднастройка офлайн-ИИ под конкретного пользователя («синергия»).
 * Профиль превращается в системный промпт, который подставляется в каждый запрос к ИИ.
 * Хранится в обычных настройках → автоматически попадает в экспорт/импорт.
 */
object AiProfile {

    val GOALS = listOf(
        "Общение и сообщения",
        "Поиск информации",
        "Развлечения",
        "Помощь по телефону"
    )

    // Возможности пользователя — ИИ подстраивает ответы (исключает/переформулирует действия)
    const val CAP_SPEECH_ONLY = "Могу только говорить (руки/тело не действуют)"
    const val CAP_WALK = "Могу ходить"
    const val CAP_LOW_VISION = "Плохо вижу"
    const val CAP_LOW_HEARING = "Плохо слышу"
    val CAPS = listOf(CAP_SPEECH_ONLY, CAP_WALK, CAP_LOW_VISION, CAP_LOW_HEARING)

    data class Profile(
        val enabled: Boolean,
        val name: String,
        val mode: String,          // "aid" — инвалид/спец-доступ, "free" — свободное пользование
        val notes: String,         // особенности, что важно учитывать
        val goals: Set<String>,
        val style: String,         // "short" | "detailed"
        val voiceAnswers: Boolean, // озвучивать ли ответы
        val engine: String,        // "universal" (llama.cpp) | "top" (MediaPipe)
        val capabilities: Set<String>
    )

    fun load(ctx: Context): Profile = Profile(
        enabled = SettingsStore.getAiEnabled(ctx),
        name = SettingsStore.getAiName(ctx),
        mode = SettingsStore.getAiMode(ctx),
        notes = SettingsStore.getAiNotes(ctx),
        goals = SettingsStore.getAiGoals(ctx).split("|").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        style = SettingsStore.getAiStyle(ctx),
        voiceAnswers = SettingsStore.getAiVoice(ctx),
        engine = SettingsStore.getAiEngine(ctx),
        capabilities = SettingsStore.getAiCaps(ctx).split("|").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    )

    fun save(ctx: Context, p: Profile) {
        SettingsStore.setAiEnabled(ctx, p.enabled)
        SettingsStore.setAiName(ctx, p.name)
        SettingsStore.setAiMode(ctx, p.mode)
        SettingsStore.setAiNotes(ctx, p.notes)
        SettingsStore.setAiGoals(ctx, p.goals.joinToString("|"))
        SettingsStore.setAiStyle(ctx, p.style)
        SettingsStore.setAiVoice(ctx, p.voiceAnswers)
        SettingsStore.setAiEngine(ctx, p.engine)
        SettingsStore.setAiCaps(ctx, p.capabilities.joinToString("|"))
    }

    /**
     * Персональная часть промпта из профиля (без «движковой» обвязки про дату/качество ответа —
     * её добавляет LocalAi). Подмешивается в каждый запрос к ИИ.
     *
     * @param full полный вес персоны (для умной модели) или облегчённый (для простой/слабой).
     *   Слабую модель тяжёлый персональный промпт «душит» — она отыгрывает роль вместо ответа,
     *   поэтому для простого слота берём только ключевое и короткими формулировками.
     */
    fun buildPersona(p: Profile, full: Boolean): String {
        val sb = StringBuilder()
        if (p.name.isNotBlank()) sb.append("Обращайся к пользователю по имени: ${p.name}. ")

        if (full && p.mode == "aid") {
            sb.append("Пользователь управляет телефоном только голосом (ограниченная подвижность); ")
            sb.append("будь бережным, помогай по шагам, без сложных терминов. ")
        }

        if (p.capabilities.contains(CAP_SPEECH_ONLY)) {
            if (full) sb.append("У пользователя не действуют руки — не предлагай физических действий руками; что нельзя сделать голосом, переформулируй как просьбу к окружающим. ")
            else sb.append("У пользователя не действуют руки — не предлагай физических действий руками и фраз «попросите близкого». ")
        }
        if (p.capabilities.contains(CAP_LOW_VISION))
            sb.append("Слабое зрение: объясняй словами и по шагам, без отсылок к мелкому тексту, цвету или картинкам. ")

        val lowHearing = p.capabilities.contains(CAP_LOW_HEARING)
        if (lowHearing)
            sb.append("Пользователь плохо слышит: отвечай коротко и ясно, главное — в первой фразе. ")

        if (p.notes.isNotBlank()) {
            val n = if (full) p.notes else p.notes.take(140)
            sb.append("Учитывай особенности пользователя: $n. ")
        }
        if (full && p.goals.isNotEmpty())
            sb.append("Основные цели использования: ${p.goals.joinToString(", ")}. ")

        // Стиль. Доступность важнее предпочтения: при «плохо слышит» всегда кратко.
        when {
            lowHearing || p.style == "short" -> sb.append("Отвечай кратко — 1–2 фразы, без лишней воды. ")
            p.style == "detailed" -> sb.append("Пользователь не против развёрнутых ответов — можно подробнее, но строго по делу. ")
        }
        return sb.toString()
    }

    /** Полный системный промпт для ИИ на основе профиля (standalone). */
    fun buildSystemPrompt(p: Profile): String {
        val sb = StringBuilder()
        sb.append("Ты — голосовой помощник в приложении управления смартфоном. Отвечай на русском языке. ")
        sb.append(buildPersona(p, full = true))
        sb.append("Никогда не выполняй необратимых действий (звонки, отправка сообщений) без явного подтверждения «да». ")
        sb.append("Если не уверен — переспроси.")
        return sb.toString()
    }
}
