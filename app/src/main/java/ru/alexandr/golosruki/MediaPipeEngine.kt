package ru.alexandr.golosruki

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
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

    companion object {
        fun modelFile(ctx: Context): File = File(SettingsStore.getAiModelPath(ctx))
        /** Считаем модель установленной, если файл есть и весит разумно (>50 МБ). */
        fun modelInstalled(ctx: Context): Boolean =
            modelFile(ctx).let { it.exists() && it.length() > 50_000_000L }
    }

    override fun isReady(): Boolean = llm != null || modelInstalled(appContext)

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (llm != null) return true
        if (!modelInstalled(appContext)) { loadError = "файл модели не найден"; return false }
        return try {
            val opts = LlmInferenceOptions.builder()
                .setModelPath(modelFile(appContext).absolutePath)
                .setMaxTokens(512)
                .build()
            llm = LlmInference.createFromOptions(appContext, opts)
            loadError = null
            true
        } catch (e: Throwable) {
            val f = modelFile(appContext)
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
        return try {
            engine.generateResponse(prompt)?.trim().orEmpty().ifBlank { "Пустой ответ от модели." }
        } catch (e: Throwable) {
            "Ошибка генерации: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun unload() {
        runCatching { llm?.close() }
        llm = null
    }
}
