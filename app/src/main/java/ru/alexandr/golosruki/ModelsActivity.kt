package ru.alexandr.golosruki

import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Менеджер моделей ИИ: список (лёгкая → мощная), скачивание по токену HF, выбор активной. */
class ModelsActivity : ComponentActivity() {

    private data class ModelDef(
        val id: String, val title: String, val size: String, val note: String,
        val url: String, val file: String
    )

    // Курируемый список: от лёгкой к мощной. Все — формат .task (бандл для MediaPipe), под лицензией Gemma.
    private val catalog = listOf(
        ModelDef("g3-1b", "Gemma 3 1B (int4)", "~550 МБ",
            "Самая лёгкая, для теста. Слабая в математике и рассуждениях.",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true",
            "gemma3-1b-int4.task"),
        ModelDef("g3n-e2b", "Gemma 3n E2B (int4) — рекомендуется", "~3 ГБ",
            "Заметный шаг вверх от 1B. Заточена под Android. Принять лицензию на странице gemma-3n-E2B-it-litert-preview.",
            "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task?download=true",
            "gemma-3n-e2b-int4.task"),
        ModelDef("g3n-e4b", "Gemma 3n E4B (int4) — максимум", "~4.4 ГБ",
            "Качество выше, тяжелее (RAM ~6 ГБ). Принять лицензию на странице gemma-3n-E4B-it-litert-preview.",
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task?download=true",
            "gemma-3n-e4b-int4.task")
    )

    private lateinit var status: TextView
    private lateinit var token: EditText

    private val pickModel = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) copyLocal(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = UiKit.column(this)
        col.addView(UiKit.title(this, "Модели ИИ"))

        status = UiKit.body(this, "")
        col.addView(status)

        // Токен HuggingFace
        val tk = UiKit.card(this)
        tk.addView(UiKit.sectionHeader(this, "Токен HuggingFace"))
        tk.addView(UiKit.body(this, "Модели Gemma закрыты лицензией — для скачивания нужен бесплатный токен. На huggingface.co: примите лицензию модели и создайте токен (Settings → Access Tokens). Вставьте его сюда — он сохранится."))
        token = EditText(this).apply { hint = "hf_..."; setText(SettingsStore.getHfToken(this@ModelsActivity)) }
        tk.addView(token)
        tk.addView(UiKit.button(this, "Сохранить токен") {
            SettingsStore.setHfToken(this, token.text.toString())
            status.text = "Токен сохранён."
        })
        col.addView(tk)

        // Каталог
        for (m in catalog) {
            val c = UiKit.card(this)
            c.addView(UiKit.sectionHeader(this, m.title))
            c.addView(UiKit.body(this, "${m.size}. ${m.note}"))
            val st = UiKit.body(this, "")
            c.addView(st)
            c.addView(UiKit.button(this, "📥 Скачать и выбрать") { download(m) })
            c.addView(UiKit.button(this, "✓ Использовать (если уже скачана)") { useModel(m) })
            statusViews[m.id] = st
            col.addView(c)
        }

        // Своя ссылка
        val cu = UiKit.card(this)
        cu.addView(UiKit.sectionHeader(this, "Своя ссылка (.task)"))
        cu.addView(UiKit.body(this, "Если у модели другое имя файла или другой источник — вставьте прямую ссылку на .task."))
        customUrl = EditText(this).apply { hint = "https://…/model.task" }
        cu.addView(customUrl)
        cu.addView(UiKit.button(this, "📥 Скачать по ссылке") {
            val u = customUrl.text.toString().trim()
            if (u.startsWith("http")) download(ModelDef("custom", "своя модель", "", "", u, "custom.task"))
            else status.text = "Вставьте корректную ссылку."
        })
        cu.addView(UiKit.button(this, "📂 Выбрать файл с устройства (.task)") {
            runCatching { pickModel.launch(arrayOf("*/*")) }.onFailure { status.text = "Не удалось открыть выбор файла." }
        })
        col.addView(cu)

        col.addView(UiKit.hint(this, "Активная модель используется для «Иван, спроси…». После смены модели первый ответ будет с задержкой (загрузка)."))

        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    private val statusViews = HashMap<String, TextView>()
    private lateinit var customUrl: EditText

    private fun llmDir(): File = File(filesDir, "llm").apply { mkdirs() }

    private fun refresh() {
        val active = SettingsStore.getAiModelPath(this)
        val af = File(active)
        status.text = if (af.exists() && af.length() > 50_000_000L)
            "Активная модель: ${af.name} (${af.length() / (1024 * 1024)} МБ) ✅"
        else "Активная модель не выбрана ❌"
        for (m in catalog) {
            val f = File(llmDir(), m.file)
            val isActive = f.absolutePath == active
            statusViews[m.id]?.text = when {
                f.exists() && isActive -> "Скачана и активна ⭐"
                f.exists() -> "Скачана ✓ (нажмите «Использовать»)"
                else -> "Не скачана"
            }
        }
    }

    private fun useModel(m: ModelDef) {
        val f = File(llmDir(), m.file)
        if (!f.exists() || f.length() < 50_000_000L) { status.text = "Сначала скачайте «${m.title}»."; return }
        SettingsStore.setAiModelPath(this, f.absolutePath)
        LocalAi.engine.unload(); LocalAi.clearHistory()
        refresh(); status.text = "Выбрана модель «${m.title}»."
    }

    private fun copyLocal(uri: Uri) {
        status.text = "Копирую файл модели… не закрывайте экран."
        Thread {
            val dst = File(llmDir(), "picked.task")
            val tmp = File(llmDir(), "picked.task.part")
            val res = runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20); var total = 0L; var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n); total += n
                            if (total % (50L * 1024 * 1024) < (1 shl 20))
                                runOnUiThread { status.text = "Копирую… ${total / (1024 * 1024)} МБ" }
                        }
                    }
                }
                if (dst.exists()) dst.delete(); tmp.renameTo(dst); dst.absolutePath
            }
            runOnUiThread {
                if (res.isSuccess) {
                    SettingsStore.setAiModelPath(this, res.getOrThrow())
                    LocalAi.engine.unload(); LocalAi.clearHistory(); refresh()
                    status.text = "Готово ✅ Модель установлена и выбрана."
                } else { tmp.delete(); status.text = "Не удалось скопировать: ${res.exceptionOrNull()?.message}" }
            }
        }.start()
    }

    private fun download(m: ModelDef) {
        val tok = SettingsStore.getHfToken(this)
        status.text = "Скачиваю «${m.title}»…"
        Thread {
            val dst = File(llmDir(), m.file)
            val tmp = File(llmDir(), m.file + ".part")
            val res = runCatching {
                var u = m.url
                var conn = open(u, tok)
                var hops = 0
                while (conn.responseCode in 300..399 && hops < 6) {
                    val loc = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    u = loc
                    // На CDN (не huggingface.co) токен не нужен и может мешать.
                    conn = open(u, if (u.contains("huggingface.co")) tok else "")
                    hops++
                }
                when (val code = conn.responseCode) {
                    200 -> {}
                    401, 403 -> throw RuntimeException("Нет доступа ($code). Проверьте токен HF и что приняли лицензию модели на huggingface.co.")
                    404 -> throw RuntimeException("Файл не найден (404). Возможно, имя файла изменилось — используйте «Свою ссылку».")
                    else -> throw RuntimeException("HTTP $code")
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20); var done = 0L; var n: Int; var lastPct = -1
                        while (input.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n); done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct && pct % 2 == 0) { lastPct = pct; runOnUiThread { status.text = "Скачиваю «${m.title}»… $pct% (${done / 1048576} МБ)" } }
                            } else runOnUiThread { status.text = "Скачиваю «${m.title}»… ${done / 1048576} МБ" }
                        }
                    }
                }
                conn.disconnect()
                if (dst.exists()) dst.delete(); tmp.renameTo(dst); dst.absolutePath
            }
            runOnUiThread {
                if (res.isSuccess) {
                    SettingsStore.setAiModelPath(this, res.getOrThrow())
                    LocalAi.engine.unload(); LocalAi.clearHistory(); refresh()
                    status.text = "Готово ✅ «${m.title}» скачана и выбрана."
                } else { tmp.delete(); status.text = "Не вышло: ${res.exceptionOrNull()?.message}" }
            }
        }.start()
    }

    private fun open(url: String, token: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 30000; c.readTimeout = 120000
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        c.connect()
        return c
    }
}
