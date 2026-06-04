package ru.alexandr.golosruki

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.io.File

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

    private val dlReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { runOnUiThread { showDlState() } }
    }

    private fun showDlState() {
        if (ModelDownloadService.active) {
            status.text = "Фоновая загрузка: ${ModelDownloadService.statusText}"
        } else {
            when (val r = ModelDownloadService.lastResult) {
                null -> {}
                "OK" -> { refresh(); }
                else -> status.text = "Загрузка не удалась: $r"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, dlReceiver,
            IntentFilter(ModelDownloadService.ACTION_PROGRESS), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (ModelDownloadService.active) showDlState() else refresh()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(dlReceiver) }
    }

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
        val dest = File(llmDir(), m.file).absolutePath
        val i = Intent(this, ModelDownloadService::class.java)
            .putExtra(ModelDownloadService.EX_URL, m.url)
            .putExtra(ModelDownloadService.EX_TOKEN, tok)
            .putExtra(ModelDownloadService.EX_DEST, dest)
            .putExtra(ModelDownloadService.EX_LABEL, m.title)
        runCatching { startForegroundService(i) }
            .onSuccess { status.text = "Загрузка «${m.title}» идёт в фоне — прогресс и скорость в шторке уведомлений. Окно можно закрыть." }
            .onFailure { status.text = "Не удалось запустить загрузку: ${it.message}" }
    }
}
