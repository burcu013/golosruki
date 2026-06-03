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

    /** Системный промпт для ИИ на основе профиля. */
    fun buildSystemPrompt(p: Profile): String {
        val sb = StringBuilder()
        sb.append("Ты — голосовой помощник в приложении управления смартфоном. Отвечай на русском языке. ")
        if (p.name.isNotBlank()) sb.append("Обращайся к пользователю по имени: ${p.name}. ")
        if (p.mode == "aid") {
            sb.append("Пользователь управляет телефоном только голосом (ограниченная подвижность). ")
            sb.append("Будь максимально понятным и бережным, предлагай помощь по шагам, не используй сложные термины. ")
        }
        if (p.capabilities.contains(CAP_SPEECH_ONLY)) {
            sb.append("ВАЖНО: пользователь не может действовать руками или телом — только речь и телефон. ")
            sb.append("Никогда не предлагай физических действий («сходи», «возьми», «принеси», «нажми рукой»). ")
            sb.append("Переформулируй их как просьбу к окружающим: «попросите кого-нибудь принести/сходить». ")
            sb.append("Всё, что можно сделать через телефон, предлагай сделать голосом. ")
        }
        if (p.capabilities.contains(CAP_LOW_VISION)) {
            sb.append("У пользователя слабое зрение: не ссылайся на мелкий текст, цвет или картинки; объясняй словами, по шагам, чётко. ")
        }
        if (p.capabilities.contains(CAP_LOW_HEARING)) {
            sb.append("Пользователь плохо слышит: отвечай коротко и ясно, ключевое дублируй текстом на экране. ")
        }
        if (p.notes.isNotBlank()) sb.append("Важно учитывать: ${p.notes}. ")
        if (p.goals.isNotEmpty()) sb.append("Основные цели использования: ${p.goals.joinToString(", ")}. ")
        if (p.style == "short") sb.append("Отвечай кратко — 1–2 фразы, без лишней воды. ")
        else sb.append("Можно отвечать развёрнуто, но по делу. ")
        sb.append("Никогда не выполняй необратимых действий (звонки, отправка сообщений) без явного подтверждения «да». ")
        sb.append("Если не уверен — переспроси.")
        return sb.toString()
    }
}
