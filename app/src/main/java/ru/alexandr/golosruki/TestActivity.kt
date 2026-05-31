package ru.alexandr.golosruki

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Диагностика: показывает в реальном времени, что слышит микрофон.
 *  Если внизу появляется текст — микрофон и модель работают.
 *  Удалить после тестов. */
class TestActivity : ComponentActivity(), RecognitionListener {

    private var model: Model? = null
    private var speech: SpeechService? = null
    private var listening = false

    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var btn: Button

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startTest() else status.text = "❌ Микрофон НЕ разрешён. Дайте разрешение в настройках."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }
        root.addView(TextView(this).apply { text = "🎤 Тест микрофона"; textSize = 26f })

        status = TextView(this).apply {
            text = "Нажмите «Старт» и говорите."; textSize = 18f; setPadding(0, 24, 0, 24)
        }
        root.addView(status)

        heard = TextView(this).apply {
            text = "—"; textSize = 32f; gravity = Gravity.CENTER; setPadding(0, 40, 0, 40)
        }
        root.addView(heard)

        btn = Button(this).apply { text = "Старт"; setOnClickListener { toggle() } }
        root.addView(btn)

        root.addView(TextView(this).apply {
            text = "Говорите любые слова — внизу должен появляться распознанный текст.\n\n" +
                "• Идёт текст → микрофон и модель РАБОТАЮТ (значит проблема в активации — проверьте, что служба спец. возможностей ВКЛЮЧЕНА).\n" +
                "• «ОШИБКА модели» → модель не попала в APK (смотрите лог сборки на GitHub).\n" +
                "• Пусто/ошибка микрофона → не выдано разрешение RECORD_AUDIO."
            textSize = 14f; setPadding(0, 32, 0, 0)
        })

        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun toggle() {
        if (listening) { stopMic(); return }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startTest() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startTest() {
        status.text = "Загрузка модели…"
        Logger.log("TEST", "Старт теста, загрузка модели")
        Thread {
            try {
                val path = ModelInstaller.ensureModel(this)
                val m = Model(path)
                runOnUiThread {
                    model = m
                    status.text = "✅ Модель загружена. Говорите!"
                    Logger.log("TEST", "Модель ОК, запуск микрофона")
                    startMic()
                }
            } catch (e: Exception) {
                Logger.log("TEST", "Ошибка модели: ${e.message}")
                runOnUiThread { status.text = "❌ ${e.message}" }
            }
        }.start()
    }

    private fun startMic() {
        try {
            val rec = Recognizer(model, 16000.0f)   // свободное распознавание для теста
            speech = SpeechService(rec, 16000.0f)
            speech?.startListening(this)
            listening = true
            btn.text = "Стоп"
        } catch (e: Exception) {
            status.text = "❌ Ошибка запуска микрофона: ${e.message}"
        }
    }

    private fun stopMic() {
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
        listening = false
        btn.text = "Старт"
        status.text = "Остановлено"
    }

    override fun onPartialResult(hypothesis: String?) {
        val p = hypothesis?.let { JSONObject(it).optString("partial") } ?: return
        if (p.isNotBlank()) heard.text = "… $p"
    }

    override fun onResult(hypothesis: String?) {
        val t = hypothesis?.let { JSONObject(it).optString("text") } ?: return
        if (t.isNotBlank()) { heard.text = "✓ $t"; Logger.log("TEST", "Распознано: $t") }
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(e: Exception?) { status.text = "❌ Ошибка: ${e?.message}" }
    override fun onTimeout() {}

    override fun onDestroy() {
        super.onDestroy()
        stopMic()
        model?.close()
    }
}
