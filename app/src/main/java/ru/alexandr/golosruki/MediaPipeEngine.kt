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
    @Volatile private var lowResource = false          // текущий слот — слабая «простая» модель

    override fun setLowResource(v: Boolean) { lowResource = v }

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
        val pref = SettingsStore.getAiBackend(appContext)
        // GPU у части .task-бандлов считает бракованно (мусор на выходе) и при этом НЕ падает,
        // поэтому «Авто» = бэкенд MediaPipe по умолчанию (проверенно рабочий), GPU — только явный выбор.
        val order: List<LlmInference.Backend?> = when (pref) {
            "gpu" -> listOf(LlmInference.Backend.GPU)
            "cpu" -> listOf(LlmInference.Backend.CPU)
            else -> listOf(null)   // авто: не задаём preferredBackend
        }
        for (backend in order) {
            if (tryLoad(path, backend)) {
                loadedPath = path; loadError = null
                val name = when (backend) { LlmInference.Backend.GPU -> "GPU"; LlmInference.Backend.CPU -> "CPU"; else -> "по умолчанию" }
                Logger.log("AI", "Модель загружена (бэкенд: $name, режим: $pref)")
                return true
            }
        }
        return false
    }

    /** Одна попытка загрузки. backend=null → не задаём preferredBackend (дефолт MediaPipe). */
    private fun tryLoad(path: String, backend: LlmInference.Backend?): Boolean {
        return try {
            val b = LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(if (lowResource) 320 else 512)
            if (backend != null) b.setPreferredBackend(backend)
            llm = LlmInference.createFromOptions(appContext, b.build())
            true
        } catch (e: Throwable) {
            val f = File(path)
            val mb = if (f.exists()) f.length() / (1024 * 1024) else 0L
            val isZip = runCatching {
                f.inputStream().use { ins ->
                    val bb = ByteArray(2); ins.read(bb)
                    bb[0] == 0x50.toByte() && bb[1] == 0x4B.toByte() // сигнатура zip "PK"
                }
            }.getOrDefault(false)
            val bk = when (backend) { LlmInference.Backend.GPU -> "GPU"; LlmInference.Backend.CPU -> "CPU"; else -> "по умолчанию" }
            loadError = "[$bk] ${e.message ?: e.javaClass.simpleName} | файл: ${f.name}, ${mb} МБ, бандл(zip): ${if (isZip) "да" else "НЕТ — несовместимый формат"}"
            Logger.log("AI", "Загрузка ($bk) не удалась: ${e.message ?: e.javaClass.simpleName}")
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
            // Слабой модели — жёстче выборка: меньше блуждания и зацикленных повторов.
            val opts = LlmInferenceSessionOptions.builder()
                .setTopK(if (lowResource) 20 else 40)
                .setTopP(if (lowResource) 0.9f else 0.95f)
                .setTemperature(if (lowResource) 0.35f else 0.7f)
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
