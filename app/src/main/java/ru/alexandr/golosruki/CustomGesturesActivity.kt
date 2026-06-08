package ru.alexandr.golosruki

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Кастомные жесты: слово → записанный пальцем жест. Вызов голосом «Иван <слово>».
 * До 20 штук. Жест воспроизводится один раз.
 */
class CustomGesturesActivity : ComponentActivity() {

    private lateinit var listBox: LinearLayout
    private lateinit var wordInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = UiKit.column(this)
        col.addView(UiKit.title(this, "Кастомные жесты"))
        col.addView(UiKit.subtitle(this, "Своё слово → записанный пальцем жест. Вызов: «Иван <слово>». До 20 жестов, выполняется один раз."))

        val add = UiKit.card(this)
        add.addView(UiKit.sectionHeader(this, "Новый жест"))
        add.addView(UiKit.body(this, "1) Введите слово команды. 2) «Записать жест» — приложение свернётся, поверх экрана появится холст. 3) Нарисуйте жест пальцем и нажмите «Сохранить». Положение запоминается в долях экрана — переносится между устройствами."))
        wordInput = EditText(this).apply { hint = "слово, напр. «лайк»" }
        add.addView(wordInput)
        add.addView(UiKit.iconButton(this, "Записать жест") { startRecord() })
        col.addView(add)

        val listCard = UiKit.card(this)
        listCard.addView(UiKit.sectionHeader(this, "Сохранённые жесты"))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(listBox)
        col.addView(listCard)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); refreshList() }

    private fun startRecord() {
        val word = wordInput.text.toString().lowercase().trim()
        if (word.length < 2) { toast("Введите слово (минимум 2 буквы)"); return }
        val svc = VoiceAccessibilityService.instance
        if (svc == null) { toast("Сначала включите спец. возможности «Иван» в настройках Android"); return }
        svc.startCustomGestureRecording(word)
        toast("Нарисуйте жест поверх экрана и нажмите «Сохранить»")
        moveTaskToBack(true)
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val map = GestureStore.getCustomGestures(this)
        if (map.isEmpty()) { listBox.addView(UiKit.body(this, "Пока нет жестов.")); return }
        for ((word, json) in map) {
            listBox.addView(TextView(this).apply {
                text = "• «$word»  —  вызов: «Иван $word»"
                textSize = 18f
                setTypeface(UiKit.fontSemi(this@CustomGesturesActivity))
                setTextColor(android.graphics.Color.parseColor("#1C1E22"))
                setPadding(0, UiKit.dp(this@CustomGesturesActivity, 12), 0, 0)
            })
            listBox.addView(UiKit.switchView(this).apply {
                text = "Только по «Иван $word» (не в свободном режиме)"; textSize = 14f
                isChecked = GestureStore.isStrict(json)
                setOnCheckedChangeListener { _, v -> GestureStore.setStrict(this@CustomGesturesActivity, word, v) }
            })
            listBox.addView(UiKit.switchView(this).apply {
                text = "После жеста — сон (микрофон засыпает, статус «Сон», будить «Иван»)"; textSize = 14f
                isChecked = GestureStore.isLock(json)
                setOnCheckedChangeListener { _, v -> GestureStore.setLock(this@CustomGesturesActivity, word, v) }
            })
            listBox.addView(UiKit.iconButton(this, "🗑 Удалить «$word»", R.drawable.btn_amber) { delete(word) })
        }
    }

    private fun delete(word: String) {
        GestureStore.deleteCustomGesture(this, word)
        applyGrammar()
        refreshList()
    }

    private fun applyGrammar() {
        runCatching {
            val i = Intent(this, VoiceRecognitionService::class.java).setAction(VoiceRecognitionService.ACTION_APPLY)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
