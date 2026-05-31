package ru.alexandr.golosruki

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Преднастройка под конкретного человека: слово активации, тайм-аут, SOS, быстрые контакты. */
class SettingsActivity : ComponentActivity() {

    private lateinit var wake: EditText
    private lateinit var idle: EditText
    private lateinit var sosNum: EditText
    private lateinit var sosTxt: EditText
    private val nameFields = mutableListOf<EditText>()
    private val numberFields = mutableListOf<EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = UiKit.column(this)
        col.addView(UiKit.title(this, "Настройки"))
        col.addView(UiKit.subtitle(this, "Подстройте приложение под человека. После сохранения голосовое управление перезапустится."))

        val a = UiKit.card(this)
        a.addView(UiKit.sectionHeader(this, "Активация"))
        a.addView(UiKit.body(this, "Слово активации:"))
        wake = field(InputType.TYPE_CLASS_TEXT).also { it.setText(SettingsStore.getWake(this)) }
        a.addView(wake)
        a.addView(UiKit.body(this, "Сон через (секунд тишины):"))
        idle = field(InputType.TYPE_CLASS_NUMBER).also { it.setText(SettingsStore.getIdle(this).toString()) }
        a.addView(idle)
        col.addView(a)

        val s = UiKit.card(this)
        s.addView(UiKit.sectionHeader(this, "SOS"))
        s.addView(UiKit.body(this, "Номер для вызова помощи:"))
        sosNum = field(InputType.TYPE_CLASS_PHONE).also { it.setText(SettingsStore.getSosNumber(this)) }
        s.addView(sosNum)
        s.addView(UiKit.body(this, "Текст SOS-сообщения:"))
        sosTxt = field(InputType.TYPE_CLASS_TEXT).also {
            val t = SettingsStore.getSosText(this)
            it.setText(if (t.isBlank()) "SOS! Нужна срочная помощь." else t)
        }
        s.addView(sosTxt)
        col.addView(s)

        val c = UiKit.card(this)
        c.addView(UiKit.sectionHeader(this, "Быстрые контакты"))
        c.addView(UiKit.body(this, "Имя в дательном падеже («позвони КОМУ») и номер. Пустые строки игнорируются."))
        val saved = SettingsStore.getContacts(this).toList()
        for (i in 0 until 6) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val name = field(InputType.TYPE_CLASS_TEXT)
            val num = field(InputType.TYPE_CLASS_PHONE)
            if (i < saved.size) { name.setText(saved[i].first); num.setText(saved[i].second) }
            name.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            num.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
            row.addView(name); row.addView(num)
            nameFields.add(name); numberFields.add(num)
            c.addView(row)
        }
        col.addView(c)

        col.addView(UiKit.button(this, "💾 Сохранить и перезапустить", R.drawable.btn_amber) { save() })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun field(type: Int): EditText = EditText(this).apply {
        inputType = type
        textSize = 16f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val p = UiKit.dp(this@SettingsActivity, 8)
        setPadding(p, p, p, p)
    }

    private fun save() {
        SettingsStore.setWake(this, wake.text.toString().ifBlank { "иван" })
        SettingsStore.setIdle(this, idle.text.toString().toIntOrNull()?.coerceIn(10, 300) ?: 30)
        SettingsStore.setSosNumber(this, sosNum.text.toString())
        SettingsStore.setSosText(this, sosTxt.text.toString())

        val contacts = mutableMapOf<String, String>()
        for (i in nameFields.indices) {
            val n = nameFields[i].text.toString().trim()
            val num = numberFields[i].text.toString().trim()
            if (n.isNotBlank() && num.isNotBlank()) contacts[n.lowercase()] = num
        }
        SettingsStore.setContacts(this, contacts)

        val intent = Intent(this, VoiceRecognitionService::class.java)
        stopService(intent)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)

        Toast.makeText(this, "Сохранено и применено", Toast.LENGTH_SHORT).show()
        finish()
    }
}
