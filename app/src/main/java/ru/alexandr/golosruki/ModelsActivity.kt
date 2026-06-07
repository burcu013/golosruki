package ru.alexandr.golosruki

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Менеджер моделей ИИ: каталог (от лёгкой к мощной), скачивание по токену HF,
 * два слота — «умная» и «простая» — и режим маршрутизации (Авто / Только простая / Только умная).
 * В памяти держится одна модель: в «Авто» смена слота = выгрузка+загрузка (несколько секунд).
 */
class ModelsActivity : ComponentActivity() {

    private data class ModelDef(
        val id: String, val title: String, val size: String, val note: String,
        val url: String, val file: String
    )

    // Курируемый список: от лёгкой к мощной. Все — формат .task (бандл для MediaPipe), под лицензией Gemma.
    private val catalog = listOf(
        ModelDef("g3-1b", "Gemma 3 1B (int4)", "~550 МБ",
            "Самая лёгкая, быстрая. Хороша как «простая». Слабая в математике и рассуждениях.",
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
    private lateinit var customUrl: EditText

    private val statusViews = HashMap<String, TextView>()
    private val smartBtns = HashMap<String, Button>()
    private val simpleBtns = HashMap<String, Button>()
    private var pendingSlot = "smart"   // в какой слот класть выбранный с устройства файл

    private lateinit var modeAuto: Button
    private lateinit var modeSimple: Button
    private lateinit var modeSmart: Button
    private lateinit var bkAuto: Button
    private lateinit var bkGpu: Button
    private lateinit var bkCpu: Button
    private lateinit var slotSmartView: TextView
    private lateinit var slotSimpleView: TextView

    private val pickModel = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) copyLocal(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = UiKit.column(this)
        col.addView(UiKit.title(this, "Модели ИИ"))

        status = UiKit.body(this, "")
        col.addView(status)

        // Режим маршрутизации
        val mc = UiKit.card(this)
        mc.addView(UiKit.sectionHeader(this, "Режим ответов"))
        mc.addView(UiKit.body(this, "Как выбирать модель для «Иван, спроси…». «Авто» — лёгкие вопросы на простую, сложные на умную (если установлены обе). В памяти держится одна модель: при «Авто» переключение занимает несколько секунд."))
        modeAuto = UiKit.iconButton(this, "Авто") { applyMode("auto") }
        modeSimple = UiKit.iconButton(this, "Только простая") { applyMode("simple") }
        modeSmart = UiKit.iconButton(this, "Только умная") { applyMode("smart") }
        mc.addView(modeAuto); mc.addView(modeSimple); mc.addView(modeSmart)
        col.addView(mc)

        // Бэкенд ускорения
        val bc = UiKit.card(this)
        bc.addView(UiKit.sectionHeader(this, "Ускорение (движок)"))
        bc.addView(UiKit.body(this, "На чём считать модель. «Авто» — пробует GPU, при сбое откатывается на CPU. GPU обычно быстрее, но дольше первая загрузка и выше нагрев/расход батареи. Какой бэкенд реально поднялся — видно в логах."))
        bkAuto = UiKit.iconButton(this, "Авто") { applyBackend("auto") }
        bkGpu = UiKit.iconButton(this, "GPU") { applyBackend("gpu") }
        bkCpu = UiKit.iconButton(this, "CPU") { applyBackend("cpu") }
        bc.addView(bkAuto); bc.addView(bkGpu); bc.addView(bkCpu)
        col.addView(bc)

        // Текущие слоты
        val sc = UiKit.card(this)
        sc.addView(UiKit.sectionHeader(this, "Текущие слоты"))
        slotSmartView = UiKit.body(this, ""); sc.addView(slotSmartView)
        slotSimpleView = UiKit.body(this, ""); sc.addView(slotSimpleView)
        sc.addView(UiKit.hint(this, "Слабому телефону хватит одной «простой» модели и режима «Только простая». На мощном — поставьте обе и «Авто»."))
        col.addView(sc)

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
            statusViews[m.id] = st
            val sb = UiKit.iconButton(this, "") { setSlot(m, "smart") }
            val pb = UiKit.iconButton(this, "", R.drawable.btn_amber) { setSlot(m, "simple") }
            smartBtns[m.id] = sb; simpleBtns[m.id] = pb
            c.addView(sb); c.addView(pb)
            col.addView(c)
        }

        // Своя модель: своя ссылка или файл с устройства — с выбором слота
        val cu = UiKit.card(this)
        cu.addView(UiKit.sectionHeader(this, "Своя модель (.task)"))
        cu.addView(UiKit.body(this, "Если модель уже лежит в файлах телефона или есть прямая ссылка — добавьте её и выберите слот: умная или простая. Файлы слотов хранятся отдельно и не затирают друг друга."))
        customUrl = EditText(this).apply { hint = "https://…/model.task (для скачивания по ссылке)" }
        cu.addView(customUrl)
        cu.addView(UiKit.body(this, "Скачать по ссылке в слот:"))
        cu.addView(UiKit.iconButton(this, "🧠 По ссылке → умная") { downloadCustom("smart") })
        cu.addView(UiKit.iconButton(this, "⚡ По ссылке → простая", R.drawable.btn_amber) { downloadCustom("simple") })
        cu.addView(UiKit.body(this, "Выбрать файл с устройства в слот:"))
        cu.addView(UiKit.iconButton(this, "🧠 Файл с устройства → умная") { pickInto("smart") })
        cu.addView(UiKit.iconButton(this, "⚡ Файл с устройства → простая", R.drawable.btn_amber) { pickInto("simple") })
        col.addView(cu)

        col.addView(UiKit.hint(this, "После смены модели первый ответ будет с задержкой — модель загружается в память."))

        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

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
                "OK" -> { refresh() }
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

    private fun applyBackend(mode: String) {
        SettingsStore.setAiBackend(this, mode)
        LocalAi.engine.unload()   // переподнимется на выбранном бэкенде при следующем запросе
        styleBackend()
        status.text = when (mode) {
            "gpu" -> "Ускорение: только GPU (первый ответ — с задержкой загрузки)."
            "cpu" -> "Ускорение: только CPU."
            else -> "Ускорение: Авто (GPU, при сбое — CPU)."
        }
    }

    private fun styleBackend() {
        val b = SettingsStore.getAiBackend(this)
        bkAuto.setBackgroundResource(if (b == "auto") R.drawable.btn_primary else R.drawable.btn_amber)
        bkGpu.setBackgroundResource(if (b == "gpu") R.drawable.btn_primary else R.drawable.btn_amber)
        bkCpu.setBackgroundResource(if (b == "cpu") R.drawable.btn_primary else R.drawable.btn_amber)
        bkAuto.text = (if (b == "auto") "✓ " else "") + "Авто"
        bkGpu.text = (if (b == "gpu") "✓ " else "") + "GPU"
        bkCpu.text = (if (b == "cpu") "✓ " else "") + "CPU"
    }

    private fun applyMode(mode: String) {
        SettingsStore.setAiRouteMode(this, mode)
        styleMode()
        status.text = when (mode) {
            "simple" -> "Режим: только простая модель."
            "smart" -> "Режим: только умная модель."
            else -> "Режим: Авто (лёгкое — простой, сложное — умной)."
        }
    }

    private fun styleMode() {
        val mode = SettingsStore.getAiRouteMode(this)
        modeAuto.setBackgroundResource(if (mode == "auto") R.drawable.btn_primary else R.drawable.btn_amber)
        modeSimple.setBackgroundResource(if (mode == "simple") R.drawable.btn_primary else R.drawable.btn_amber)
        modeSmart.setBackgroundResource(if (mode == "smart") R.drawable.btn_primary else R.drawable.btn_amber)
        modeAuto.text = (if (mode == "auto") "✓ " else "") + "Авто"
        modeSimple.text = (if (mode == "simple") "✓ " else "") + "Только простая"
        modeSmart.text = (if (mode == "smart") "✓ " else "") + "Только умная"
    }

    private fun installed(f: File): Boolean = f.exists() && f.length() > 50_000_000L

    /** Назначить модель в слот; если файл ещё не скачан — скачать его сразу в нужный слот. */
    private fun setSlot(m: ModelDef, slot: String) {
        val f = File(llmDir(), m.file)
        if (installed(f)) {
            assign(f.absolutePath, slot)
            status.text = "«${m.title}» — теперь ${if (slot == "smart") "умная" else "простая"}."
        } else {
            download(m, slot)
        }
    }

    private fun assign(path: String, slot: String) {
        if (slot == "smart") SettingsStore.setAiModelPath(this, path)
        else SettingsStore.setAiModelSimplePath(this, path)
        LocalAi.engine.unload(); LocalAi.clearHistory()
        refresh()
    }

    private fun refresh() {
        val smart = SettingsStore.getAiModelPath(this)
        val simple = SettingsStore.getAiModelSimplePath(this)
        val smartOk = MediaPipeEngine.modelInstalledAt(smart)
        val simpleOk = MediaPipeEngine.modelInstalledAt(simple)

        slotSmartView.text = "🧠 Умная: " + if (smartOk) "${File(smart).name} ✅" else "не выбрана ❌"
        slotSimpleView.text = "⚡ Простая: " + if (simpleOk) "${File(simple).name} ✅" else "не выбрана ❌"
        styleMode()
        styleBackend()

        val parts = ArrayList<String>()
        if (smartOk) parts.add("умная")
        if (simpleOk) parts.add("простая")
        status.text = if (parts.isEmpty()) "Модель не установлена ❌ — скачайте ниже."
            else "Установлено: ${parts.joinToString(", ")}."

        for (m in catalog) {
            val f = File(llmDir(), m.file)
            val present = installed(f)
            val isSmart = present && f.absolutePath == smart
            val isSimple = present && f.absolutePath == simple
            statusViews[m.id]?.text = if (!present) "Не скачана" else buildString {
                append("Скачана ✓")
                if (isSmart) append(" · сейчас умная ⭐")
                if (isSimple) append(" · сейчас простая ⭐")
            }
            smartBtns[m.id]?.text = if (present) "🧠 Сделать умной" else "📥 Скачать как умную"
            simpleBtns[m.id]?.text = if (present) "⚡ Сделать простой" else "📥 Скачать как простую"
        }
    }

    private fun slotWord(slot: String): String = when (slot) {
        "smart" -> "умной"; "simple" -> "простой"; else -> "активной"
    }

    private fun pickInto(slot: String) {
        pendingSlot = slot
        runCatching { pickModel.launch(arrayOf("*/*")) }
            .onFailure { status.text = "Не удалось открыть выбор файла." }
    }

    private fun downloadCustom(slot: String) {
        val u = customUrl.text.toString().trim()
        if (!u.startsWith("http")) { status.text = "Вставьте корректную ссылку на .task."; return }
        val file = if (slot == "simple") "custom_simple.task" else "custom_smart.task"
        download(ModelDef("custom", "своя модель", "", "", u, file), slot)
    }

    private fun copyLocal(uri: Uri) {
        val slot = pendingSlot
        val slotRu = if (slot == "simple") "простая" else "умная"
        status.text = "Копирую файл модели в слот «$slotRu»… не закрывайте экран."
        Thread {
            val name = if (slot == "simple") "picked_simple.task" else "picked_smart.task"
            val dst = File(llmDir(), name)
            val tmp = File(llmDir(), "$name.part")
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
                    assign(res.getOrThrow(), slot)
                    status.text = "Готово ✅ Модель установлена как $slotRu."
                } else { tmp.delete(); status.text = "Не удалось скопировать: ${res.exceptionOrNull()?.message}" }
            }
        }.start()
    }

    private fun download(m: ModelDef, slot: String) {
        val tok = SettingsStore.getHfToken(this)
        val dest = File(llmDir(), m.file).absolutePath
        val i = Intent(this, ModelDownloadService::class.java)
            .putExtra(ModelDownloadService.EX_URL, m.url)
            .putExtra(ModelDownloadService.EX_TOKEN, tok)
            .putExtra(ModelDownloadService.EX_DEST, dest)
            .putExtra(ModelDownloadService.EX_LABEL, m.title)
            .putExtra(ModelDownloadService.EX_SLOT, slot)
        runCatching { startForegroundService(i) }
            .onSuccess { status.text = "Загрузка «${m.title}» идёт в фоне — прогресс в шторке уведомлений. По завершении станет ${slotWord(slot)}. Окно можно закрыть." }
            .onFailure { status.text = "Не удалось запустить загрузку: ${it.message}" }
    }
}
