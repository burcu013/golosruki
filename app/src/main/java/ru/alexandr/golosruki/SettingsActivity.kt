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
    private val openPhraseFields = mutableListOf<EditText>()
    private val openButtons = mutableListOf<android.widget.Button>()
    private val openPkgs = mutableListOf<String>()

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

        // Кастомные команды запуска приложений
        val oc = UiKit.card(this)
        oc.addView(UiKit.sectionHeader(this, "Свои команды запуска"))
        oc.addView(UiKit.body(this, "Задайте слово-команду и выберите приложение. Скажете слово — приложение откроется."))
        val savedOpen = SettingsStore.getOpenCommands(this).toList()
        for (i in 0 until 4) {
            val phrase = field(InputType.TYPE_CLASS_TEXT)
            val savedPkg = if (i < savedOpen.size) savedOpen[i].second else ""
            if (i < savedOpen.size) phrase.setText(savedOpen[i].first)
            openPkgs.add(savedPkg)
            val idx = i
            val btn = UiKit.button(this, appLabel(savedPkg)) { pickApp(idx) }
            openPhraseFields.add(phrase)
            openButtons.add(btn)
            oc.addView(phrase)
            oc.addView(btn)
        }
        col.addView(oc)

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

    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return "Выбрать приложение"
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            "Приложение: " + packageManager.getApplicationLabel(ai)
        } catch (e: Exception) { "Приложение: $pkg" }
    }

    private fun pickApp(rowIndex: Int) {
        val pm = packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val items = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        val labels = items.map { it.first }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Выберите приложение")
            .setItems(labels) { _, which ->
                openPkgs[rowIndex] = items[which].second
                openButtons[rowIndex].text = "Приложение: " + items[which].first
            }
            .show()
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

        val openCmds = mutableMapOf<String, String>()
        for (i in openPhraseFields.indices) {
            val phrase = openPhraseFields[i].text.toString().trim()
            val pkg = openPkgs.getOrNull(i) ?: ""
            if (phrase.isNotBlank() && pkg.isNotBlank()) openCmds[phrase.lowercase()] = pkg
        }
        SettingsStore.setOpenCommands(this, openCmds)

        val intent = Intent(this, VoiceRecognitionService::class.java)
        stopService(intent)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)

        Toast.makeText(this, "Сохранено и применено", Toast.LENGTH_SHORT).show()
        finish()
    }
}
