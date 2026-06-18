package ru.alexandr.golosruki

import android.content.Context
import org.json.JSONObject

/** Хранилище персональных настроек (контакты, SOS, слово активации, тайм-аут сна). */
object SettingsStore {
    private const val PREF = "golosruki"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Экспорт всех персональных настроек в JSON-строку. */
    fun exportJson(ctx: Context): String {
        val o = org.json.JSONObject()
        for ((k, v) in p(ctx).all) {
            when (v) {
                is Boolean -> o.put(k, v)
                is Int -> o.put(k, v)
                is Float -> o.put(k, v.toDouble())
                is Long -> o.put(k, v)
                is String -> o.put(k, v)
                else -> {}
            }
        }
        return o.toString(2)
    }

    /** Импорт настроек из JSON-строки (типы выводятся как при сохранении). */
    fun importJson(ctx: Context, json: String): Boolean {
        return try {
            val o = org.json.JSONObject(json)
            val e = p(ctx).edit()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                when (val v = o.get(k)) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Double -> e.putFloat(k, v.toFloat())
                    is Long -> e.putLong(k, v)
                    is String -> e.putString(k, v)
                    else -> {}
                }
            }
            e.apply(); true
        } catch (ex: Exception) { false }
    }

    fun getWake(ctx: Context): String = p(ctx).getString("wake", "иван")?.ifBlank { "иван" } ?: "иван"
    fun setWake(ctx: Context, v: String) = p(ctx).edit().putString("wake", v.lowercase().trim()).apply()

    fun getIdle(ctx: Context): Int = p(ctx).getInt("idle", 30)
    fun setIdle(ctx: Context, v: Int) = p(ctx).edit().putInt("idle", v).apply()

    fun getSosNumber(ctx: Context): String = p(ctx).getString("sos_num", "") ?: ""
    fun setSosNumber(ctx: Context, v: String) = p(ctx).edit().putString("sos_num", v.trim()).apply()

    fun getSosText(ctx: Context): String = p(ctx).getString("sos_text", "") ?: ""
    fun setSosText(ctx: Context, v: String) = p(ctx).edit().putString("sos_text", v).apply()

    fun getSosPin(ctx: Context): String = p(ctx).getString("sos_pin", "") ?: ""
    fun setSosPin(ctx: Context, v: String) = p(ctx).edit().putString("sos_pin", v.trim()).apply()

    fun getIgnoreMedia(ctx: Context): Boolean = p(ctx).getBoolean("ignore_media", true)
    fun setIgnoreMedia(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ignore_media", v).apply()

    fun getSwipeInvertV(ctx: Context): Boolean = p(ctx).getBoolean("swipe_inv_v", false)
    fun setSwipeInvertV(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("swipe_inv_v", v).apply()
    fun getSwipeInvertH(ctx: Context): Boolean = p(ctx).getBoolean("swipe_inv_h", false)
    fun setSwipeInvertH(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("swipe_inv_h", v).apply()
    fun getSwipeStrength(ctx: Context): Int = p(ctx).getInt("swipe_strength", 2)
    fun setSwipeStrength(ctx: Context, v: Int) = p(ctx).edit().putInt("swipe_strength", v).apply()

    /** Точка старта свайпа (% экрана вдоль оси движения) для каждого направления. */
    fun getSwipeStart(ctx: Context, dir: Direction): Int {
        val def = when (dir) { Direction.DOWN -> 30; Direction.UP -> 70; Direction.LEFT -> 70; Direction.RIGHT -> 30 }
        return p(ctx).getInt("swipe_start_" + dir.name, def)
    }
    fun setSwipeStart(ctx: Context, dir: Direction, v: Int) =
        p(ctx).edit().putInt("swipe_start_" + dir.name, v.coerceIn(5, 95)).apply()

    fun getVibrate(ctx: Context): Boolean = p(ctx).getBoolean("vibrate", true)
    fun setVibrate(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("vibrate", v).apply()
    fun getKeepScreen(ctx: Context): Boolean = p(ctx).getBoolean("keep_screen", true)
    fun setKeepScreen(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("keep_screen", v).apply()


    fun getMediaCode(ctx: Context): String = p(ctx).getString("media_code", "медиа")?.ifBlank { "медиа" } ?: "медиа"
    fun setMediaCode(ctx: Context, v: String) = p(ctx).edit().putString("media_code", v.trim()).apply()
    fun getMediaWindowSec(ctx: Context): Int = p(ctx).getInt("media_window", 4)
    fun setMediaWindowSec(ctx: Context, v: Int) = p(ctx).edit().putInt("media_window", v.coerceIn(2, 10)).apply()
    fun getLongPressMs(ctx: Context): Int = p(ctx).getInt("longpress_ms", 1000)
    fun setLongPressMs(ctx: Context, v: Int) = p(ctx).edit().putInt("longpress_ms", v.coerceIn(500, 3000)).apply()

    fun getTts(ctx: Context): Boolean = p(ctx).getBoolean("tts", true)
    fun setTts(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("tts", v).apply()
    fun getTtsPitch(ctx: Context): Float = p(ctx).getFloat("tts_pitch", 0.9f)   // ниже = «мужественнее/мягче»
    fun setTtsPitch(ctx: Context, v: Float) = p(ctx).edit().putFloat("tts_pitch", v).apply()
    fun getTtsRate(ctx: Context): Float = p(ctx).getFloat("tts_rate", 0.95f)     // чуть медленнее = спокойнее
    fun setTtsRate(ctx: Context, v: Float) = p(ctx).edit().putFloat("tts_rate", v).apply()
    fun getBtMic(ctx: Context): Boolean = p(ctx).getBoolean("bt_mic", false)
    fun setBtMic(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("bt_mic", v).apply()

    fun getNoiseSuppress(ctx: Context): Boolean = p(ctx).getBoolean("noise_suppress", false)
    fun setNoiseSuppress(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("noise_suppress", v).apply()

    // --- Профиль ИИ-помощника (входит в экспорт/импорт автоматически) ---
    fun getAiEnabled(ctx: Context): Boolean = p(ctx).getBoolean("ai_enabled", false)
    fun setAiEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ai_enabled", v).apply()
    fun getAiName(ctx: Context): String = p(ctx).getString("ai_name", "") ?: ""
    fun setAiName(ctx: Context, v: String) = p(ctx).edit().putString("ai_name", v).apply()
    fun getAiMode(ctx: Context): String = p(ctx).getString("ai_mode", "aid") ?: "aid"
    fun setAiMode(ctx: Context, v: String) = p(ctx).edit().putString("ai_mode", v).apply()
    fun getAiNotes(ctx: Context): String = p(ctx).getString("ai_notes", "") ?: ""
    fun setAiNotes(ctx: Context, v: String) = p(ctx).edit().putString("ai_notes", v).apply()
    fun getAiGoals(ctx: Context): String = p(ctx).getString("ai_goals", "") ?: ""
    fun setAiGoals(ctx: Context, v: String) = p(ctx).edit().putString("ai_goals", v).apply()
    fun getAiStyle(ctx: Context): String = p(ctx).getString("ai_style", "short") ?: "short"
    fun setAiStyle(ctx: Context, v: String) = p(ctx).edit().putString("ai_style", v).apply()
    fun getAiVoice(ctx: Context): Boolean = p(ctx).getBoolean("ai_voice", true)
    fun setAiVoice(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ai_voice", v).apply()
    fun getAiEngine(ctx: Context): String = p(ctx).getString("ai_engine", "universal") ?: "universal"
    fun setAiEngine(ctx: Context, v: String) = p(ctx).edit().putString("ai_engine", v).apply()
    fun getAiCaps(ctx: Context): String = p(ctx).getString("ai_caps", "") ?: ""
    fun setAiCaps(ctx: Context, v: String) = p(ctx).edit().putString("ai_caps", v).apply()
    // Авто-переключение на голосовую клавиатуру во время диктовки (нужно разрешение WRITE_SECURE_SETTINGS)
    fun getAutoIme(ctx: Context): Boolean = p(ctx).getBoolean("auto_ime", true)
    fun setAutoIme(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("auto_ime", v).apply()
    // Путь к активной модели ИИ (по умолчанию — llm/model.task в приватной папке)
    fun getAiModelPath(ctx: Context): String =
        p(ctx).getString("ai_model_path", null) ?: java.io.File(ctx.filesDir, "llm/model.task").absolutePath
    fun setAiModelPath(ctx: Context, v: String) = p(ctx).edit().putString("ai_model_path", v).apply()

    // Второй слот — «простая» (быстрая) модель для лёгких запросов / слабых устройств.
    fun getAiModelSimplePath(ctx: Context): String =
        p(ctx).getString("ai_model_simple_path", null) ?: java.io.File(ctx.filesDir, "llm/model_simple.task").absolutePath
    fun setAiModelSimplePath(ctx: Context, v: String) = p(ctx).edit().putString("ai_model_simple_path", v).apply()

    // Режим маршрутизации: "auto" | "simple" | "smart".
    fun getAiRouteMode(ctx: Context): String = p(ctx).getString("ai_route_mode", "auto") ?: "auto"
    fun setAiRouteMode(ctx: Context, v: String) = p(ctx).edit().putString("ai_route_mode", v).apply()

    // Бэкенд ускорения ИИ: "auto" (GPU с откатом на CPU), "gpu", "cpu".
    fun getAiBackend(ctx: Context): String = p(ctx).getString("ai_backend", "auto") ?: "auto"
    fun setAiBackend(ctx: Context, v: String) = p(ctx).edit().putString("ai_backend", v).apply()

    // Объявлять входящие уведомления голосом.
    fun getAnnounceNotifs(ctx: Context): Boolean = p(ctx).getBoolean("announce_notifs", false)
    fun setAnnounceNotifs(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("announce_notifs", v).apply()

    // Онлайн-модель (OpenAI-совместимый API): вкл, базовый URL, ключ, имя модели.
    fun getApiEnabled(ctx: Context): Boolean = p(ctx).getBoolean("api_enabled", false)
    fun setApiEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("api_enabled", v).apply()
    fun getApiUrl(ctx: Context): String = p(ctx).getString("api_url", "") ?: ""
    fun setApiUrl(ctx: Context, v: String) = p(ctx).edit().putString("api_url", v).apply()
    fun getApiKey(ctx: Context): String = p(ctx).getString("api_key", "") ?: ""
    fun setApiKey(ctx: Context, v: String) = p(ctx).edit().putString("api_key", v).apply()
    fun getApiModel(ctx: Context): String = p(ctx).getString("api_model", "") ?: ""
    fun setApiModel(ctx: Context, v: String) = p(ctx).edit().putString("api_model", v).apply()

    // Облачное распознавание речи (Whisper, OpenAI-совместимый /audio/transcriptions, напр. Groq).
    fun getSttEnabled(ctx: Context): Boolean = p(ctx).getBoolean("stt_enabled", false)
    fun setSttEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("stt_enabled", v).apply()
    fun getSttUrl(ctx: Context): String = p(ctx).getString("stt_url", "") ?: ""
    fun setSttUrl(ctx: Context, v: String) = p(ctx).edit().putString("stt_url", v).apply()
    fun getSttKey(ctx: Context): String = p(ctx).getString("stt_key", "") ?: ""
    fun setSttKey(ctx: Context, v: String) = p(ctx).edit().putString("stt_key", v).apply()
    fun getSttModel(ctx: Context): String = p(ctx).getString("stt_model", "") ?: ""
    fun setSttModel(ctx: Context, v: String) = p(ctx).edit().putString("stt_model", v).apply()

    // Тонкая настройка записи для онлайн-распознавания (VAD).
    fun getVadSilenceMs(ctx: Context): Int = p(ctx).getInt("vad_silence_ms", 1200)
    fun setVadSilenceMs(ctx: Context, v: Int) = p(ctx).edit().putInt("vad_silence_ms", v).apply()
    fun getVadMaxMs(ctx: Context): Int = p(ctx).getInt("vad_max_ms", 15000)
    fun setVadMaxMs(ctx: Context, v: Int) = p(ctx).edit().putInt("vad_max_ms", v).apply()
    fun getVadSensitivity(ctx: Context): Int = p(ctx).getInt("vad_sens", 5)   // 1..10, выше = ловит тише
    fun setVadSensitivity(ctx: Context, v: Int) = p(ctx).edit().putInt("vad_sens", v).apply()

    // Использовать контакты телефонной книги (в дополнение к заданным в приложении).
    fun getUseSystemContacts(ctx: Context): Boolean = p(ctx).getBoolean("use_sys_contacts", false)
    fun setUseSystemContacts(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("use_sys_contacts", v).apply()

    // Утренний голосовой брифинг по расписанию.
    fun getBriefingEnabled(ctx: Context): Boolean = p(ctx).getBoolean("briefing_enabled", false)
    fun setBriefingEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("briefing_enabled", v).apply()
    fun getBriefingHour(ctx: Context): Int = p(ctx).getInt("briefing_hour", 9)
    fun setBriefingHour(ctx: Context, v: Int) = p(ctx).edit().putInt("briefing_hour", v).apply()
    fun getBriefingMin(ctx: Context): Int = p(ctx).getInt("briefing_min", 0)
    fun setBriefingMin(ctx: Context, v: Int) = p(ctx).edit().putInt("briefing_min", v).apply()

    // Глубокий сон: режим, из которого можно выйти только точной фразой пробуждения.
    fun getDeepSleepEnabled(ctx: Context): Boolean = p(ctx).getBoolean("deep_sleep_enabled", false)
    fun setDeepSleepEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("deep_sleep_enabled", v).apply()
    fun getDeepSleepPhrase(ctx: Context): String =
        (p(ctx).getString("deep_sleep_phrase", "иван полный подъём") ?: "иван полный подъём").lowercase().trim()
    fun setDeepSleepPhrase(ctx: Context, v: String) = p(ctx).edit().putString("deep_sleep_phrase", v.trim()).apply()

    // Калибровка кнопки записи: доли экрана (x,y в 0..1) по пакету приложения.
    fun getRecPointFrac(ctx: Context, pkg: String): Pair<Float, Float>? {
        if (pkg.isBlank()) return null
        val s = p(ctx).getString("recpt_$pkg", "") ?: ""
        val parts = s.split(",")
        val x = parts.getOrNull(0)?.toFloatOrNull() ?: return null
        val y = parts.getOrNull(1)?.toFloatOrNull() ?: return null
        return x to y
    }
    fun setRecPointFrac(ctx: Context, pkg: String, x: Float, y: Float) =
        p(ctx).edit().putString("recpt_$pkg", "$x,$y").apply()
    // Токен HuggingFace для скачивания закрытых моделей (Gemma)
    fun getHfToken(ctx: Context): String = p(ctx).getString("hf_token", "") ?: ""
    fun setHfToken(ctx: Context, v: String) = p(ctx).edit().putString("hf_token", v.trim()).apply()

    /** Карта персональных триггеров/коррекций: фраза → ключ команды. */
    fun getAliasMap(ctx: Context): Map<String, String> {
        val raw = p(ctx).getString("aliases", "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return try {
            val o = org.json.JSONObject(raw); val m = HashMap<String, String>()
            val it = o.keys(); while (it.hasNext()) { val k = it.next(); m[k.lowercase().trim()] = o.getString(k) }
            m
        } catch (e: Exception) { emptyMap() }
    }
    fun setAliasMap(ctx: Context, map: Map<String, String>) {
        val o = org.json.JSONObject()
        for ((phrase, key) in map) if (phrase.isNotBlank()) o.put(phrase.lowercase().trim(), key)
        p(ctx).edit().putString("aliases", o.toString()).apply()
    }
    fun addAlias(ctx: Context, phrase: String, key: String) {
        val m = HashMap(getAliasMap(ctx)); m[phrase.lowercase().trim()] = key; setAliasMap(ctx, m)
    }
    fun getTtsVoice(ctx: Context): String = p(ctx).getString("tts_voice", "") ?: ""
    fun setTtsVoice(ctx: Context, v: String) = p(ctx).edit().putString("tts_voice", v).apply()
    fun getConfirmCalls(ctx: Context): Boolean = p(ctx).getBoolean("confirm_calls", false)
    fun setConfirmCalls(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("confirm_calls", v).apply()

    // Запуск приложений своими командами — только по «Иван <слово>» (защита от случайного слова в свободном режиме).
    fun getLaunchRequireWake(ctx: Context): Boolean = p(ctx).getBoolean("launch_require_wake", true)
    fun setLaunchRequireWake(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("launch_require_wake", v).apply()
    fun getSosNumber2(ctx: Context): String = p(ctx).getString("sos_num2", "") ?: ""
    fun setSosNumber2(ctx: Context, v: String) = p(ctx).edit().putString("sos_num2", v).apply()

    /** Контакты: имя(дат. падеж) -> номер. */
    fun getContacts(ctx: Context): Map<String, String> {
        val raw = p(ctx).getString("contacts", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val m = mutableMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) { val k = it.next(); m[k.lowercase()] = obj.optString(k) }
            m
        } catch (e: Exception) { emptyMap() }
    }

    fun setContacts(ctx: Context, contacts: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in contacts) if (k.isNotBlank() && v.isNotBlank()) obj.put(k.lowercase().trim(), v.trim())
        p(ctx).edit().putString("contacts", obj.toString()).apply()
    }

    /** Кастомные команды запуска: фраза -> package. */
    fun getOpenCommands(ctx: Context): Map<String, String> {
        val raw = p(ctx).getString("open_cmds", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val m = mutableMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) { val k = it.next(); m[k.lowercase()] = obj.optString(k) }
            m
        } catch (e: Exception) { emptyMap() }
    }

    fun setOpenCommands(ctx: Context, cmds: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in cmds) if (k.isNotBlank() && v.isNotBlank()) obj.put(k.lowercase().trim(), v.trim())
        p(ctx).edit().putString("open_cmds", obj.toString()).apply()
    }
}
