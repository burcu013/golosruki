package ru.alexandr.golosruki

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import java.io.File

/**
 * Движок ИИ на устройстве через MediaPipe LLM Inference.
 * Модель (.task, напр. Gemma-3 1B int4) кладётся в приватную папку приложения:
 *   filesDir/llm/model.task
 * Загрузка ленивая — при первом запросе (может занять несколько секунд).
 */
class MediaPipeEngine(private val appContext: Context) : AiEngine {

    @Volatile private var llm: LlmInference? = null
    @Volatile private var loadError: String? = null
    @Volatile private var activePath: String? = null   // выбранный путь (null → путь по умолчанию)
    @Volatile private var loadedPath: String? = null   // что реально загружено сейчас

    companion object {
        fun modelFile(ctx: Context): File = File(SettingsStore.getAiModelPath(ctx))
        /** Считаем модель установленной, если файл есть и весит разумно (>50 МБ). */
        fun modelInstalled(ctx: Context): Boolean =
            modelFile(ctx).let { it.exists() && it.length() > 50_000_000L }
        /** Проверка установки конкретного файла модели (для маршрутизации между слотами). */
        fun modelInstalledAt(path: String): Boolean =
            File(path).let { it.exists() && it.length() > 50_000_000L }
    }

    override fun isReady(): Boolean = llm != null || modelInstalled(appContext)

    /** Выбрать модель по пути. Если это другая модель — выгружаем, чтобы при следующей генерации загрузилась нужная. */
    override fun useModel(path: String) {
        if (path.isBlank() || path == loadedPath) return
        runCatching { llm?.close() }
        llm = null; loadError = null
        activePath = path
        Logger.log("AI", "Переключение модели → ${File(path).name}")
    }

    private fun currentPath(): String = activePath ?: SettingsStore.getAiModelPath(appContext)

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (llm != null) return true
        val path = currentPath()
        if (!modelInstalledAt(path)) { loadError = "файл модели не найден"; return false }
        return try {
            val opts = LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(512)
                .build()
            llm = LlmInference.createFromOptions(appContext, opts)
            loadedPath = path
            loadError = null
            true
        } catch (e: Throwable) {
            val f = File(path)
            val mb = if (f.exists()) f.length() / (1024 * 1024) else 0L
            val isZip = runCatching {
                f.inputStream().use { ins ->
                    val b = ByteArray(2); ins.read(b)
                    b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() // сигнатура zip "PK"
                }
            }.getOrDefault(false)
            loadError = "${e.message ?: e.javaClass.simpleName} | файл: ${f.name}, ${mb} МБ, бандл(zip): ${if (isZip) "да" else "НЕТ — несовместимый формат"}"
            false
        }
    }

    override fun generate(systemPrompt: String, userText: String): String {
        if (!ensureLoaded()) return "Модель не загрузилась: ${loadError ?: "нет файла"}. Откройте Настройки → ИИ."
        val engine = llm ?: return "Модель не готова."
        // Формат разговора Gemma: системные инструкции вкладываем в реплику пользователя.
        val prompt = buildString {
            append("<start_of_turn>user\n")
            if (systemPrompt.isNotBlank()) { append(systemPrompt.trim()); append("\n\n") }
            append(userText.trim())
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        Logger.logSync("AI", "Старт генерации (длина запроса ${prompt.length})")
        return try {
            // Сначала пробуем сессию с параметрами выборки (фокуснее, меньше блуждания).
            val r = generateWithSession(engine, prompt) ?: engine.generateResponse(prompt)
            val out = r?.trim().orEmpty().ifBlank { "Пустой ответ от модели." }
            Logger.log("AI", "Генерация завершена (${out.length} симв.)")
            out
        } catch (e: Throwable) {
            "Ошибка генерации: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Генерация с управляемой выборкой. Возвращает null, если сессионный API недоступен (тогда откат). */
    private fun generateWithSession(engine: LlmInference, prompt: String): String? {
        return try {
            val opts = LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTopP(0.95f)
                .setTemperature(0.7f)
                .build()
            val session = LlmInferenceSession.createFromOptions(engine, opts)
            session.addQueryChunk(prompt)
            val r = session.generateResponse()
            runCatching { session.close() }
            r
        } catch (e: Throwable) {
            Logger.log("AI", "Сессия недоступна (${e.javaClass.simpleName}) — обычный режим")
            null
        }
    }

    override fun unload() {
        runCatching { llm?.close() }
        llm = null
    }
}
