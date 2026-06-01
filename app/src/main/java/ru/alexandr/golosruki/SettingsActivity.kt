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
    private lateinit var mediaCodeField: EditText
    private lateinit var ttsCheck: android.widget.CheckBox
    private lateinit var confirmCheck: android.widget.CheckBox
    private lateinit var sosNum2: EditText
    private lateinit var voiceSpinner: android.widget.Spinner
    private lateinit var pitchBar: android.widget.SeekBar
    private lateinit var rateBar: android.widget.SeekBar
    private lateinit var idle: EditText
    private lateinit var mediaWindow: EditText
    private lateinit var sosNum: EditText
    private lateinit var sosTxt: EditText
    private lateinit var sosPin: EditText
    private lateinit var ignoreMediaCheck: android.widget.CheckBox
    private lateinit var vibrateCheck: android.widget.CheckBox
    private lateinit var keepScreenCheck: android.widget.CheckBox
    private lateinit var invVCheck: android.widget.CheckBox
    private lateinit var invHCheck: android.widget.CheckBox
    private lateinit var strengthField: EditText
    private lateinit var startDown: EditText
    private lateinit var startUp: EditText
    private lateinit var startLeft: EditText
    private lateinit var startRight: EditText
    private fun labeledNum(box: LinearLayout, label: String, value: Int): EditText {
        box.addView(UiKit.body(this, label))
        val f = field(InputType.TYPE_CLASS_NUMBER).also { it.setText(value.toString()) }
        box.addView(f)
        return f
    }
    private lateinit var bigModelCheck: android.widget.CheckBox
    private lateinit var modelStatus: android.widget.TextView
    private val nameFields = mutableListOf<EditText>()
    private val numberFields = mutableListOf<EditText>()
    private val openPhraseFields = mutableListOf<EditText>()
    private val openButtons = mutableListOf<android.widget.Button>()
    private val openPkgs = mutableListOf<String>()
    private lateinit var contactsBox: LinearLayout
    private lateinit var openBox: LinearLayout

    private fun addContactRow(n: String, num: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val name = field(InputType.TYPE_CLASS_TEXT).also { it.setText(n) }
        val number = field(InputType.TYPE_CLASS_PHONE).also { it.setText(num) }
        name.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        number.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
        row.addView(name); row.addView(number)
        nameFields.add(name); numberFields.add(number)
        contactsBox.addView(row)
    }

    private fun addOpenRow(ph: String, pkg: String) {
        val phrase = field(InputType.TYPE_CLASS_TEXT).also { it.setText(ph) }
        openPkgs.add(pkg)
        val idx = openButtons.size
        val btn = UiKit.button(this, appLabel(pkg)) { pickApp(idx) }
        openPhraseFields.add(phrase)
        openButtons.add(btn)
        openBox.addView(phrase)
        openBox.addView(btn)
    }

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
        a.addView(UiKit.hint(this, "⚠️ Берите 2–3-сложное необычное слово (иван, соня, ассистент). Короткие/частые слова («да», «стоп», «эй») путаются с командами."))
        a.addView(UiKit.body(this, "Сон через (секунд тишины):"))
        idle = field(InputType.TYPE_CLASS_NUMBER).also { it.setText(SettingsStore.getIdle(this).toString()) }
        a.addView(idle)
        ignoreMediaCheck = android.widget.CheckBox(this).apply {
            text = "Не реагировать во время видео/музыки"
            textSize = 15f
            isChecked = SettingsStore.getIgnoreMedia(this@SettingsActivity)
        }
        a.addView(ignoreMediaCheck)
        vibrateCheck = android.widget.CheckBox(this).apply {
            text = "Вибро-отклик при активации"; textSize = 15f
            isChecked = SettingsStore.getVibrate(this@SettingsActivity)
        }
        a.addView(vibrateCheck)
        keepScreenCheck = android.widget.CheckBox(this).apply {
            text = "Не гасить экран, пока активен"; textSize = 15f
            isChecked = SettingsStore.getKeepScreen(this@SettingsActivity)
        }
        a.addView(keepScreenCheck)
        a.addView(UiKit.body(this, "Кодовое слово медиа-режима (видео и музыка). Скажите «<слово активации> <код>» — включится управление плеером (пауза/играй/треки/листание) БЕЗ повторения слова активации; повторите — выключится:"))
        mediaCodeField = field(InputType.TYPE_CLASS_TEXT).also { it.setText(SettingsStore.getMediaCode(this)) }
        a.addView(mediaCodeField)
        a.addView(UiKit.body(this, "Окно медиа при ВЫКЛЮЧЕННОМ экране (сек). Скажете «<слово> <код>» — телефон ждёт «играй/пауза/следующее/громче» столько секунд, не зажигая экран:"))
        mediaWindow = field(InputType.TYPE_CLASS_NUMBER).also { it.setText(SettingsStore.getMediaWindowSec(this).toString()) }
        a.addView(mediaWindow)
        ttsCheck = android.widget.CheckBox(this).apply {
            text = "Голосовые подтверждения (озвучивание действий)"; textSize = 15f
            isChecked = SettingsStore.getTts(this@SettingsActivity)
        }
        a.addView(ttsCheck)
        confirmCheck = android.widget.CheckBox(this).apply {
            text = "Спрашивать подтверждение перед звонком"; textSize = 15f
            isChecked = SettingsStore.getConfirmCalls(this@SettingsActivity)
        }
        a.addView(confirmCheck)
        col.addView(a)

        // Калибровка свайпов
        val cal = UiKit.card(this)
        cal.addView(UiKit.sectionHeader(this, "Калибровка свайпов"))
        cal.addView(UiKit.body(this, "Свайп = жест пальцем. Если направление непривычно (или планшет повёрнут) — поменяйте местами."))
        invVCheck = android.widget.CheckBox(this).apply {
            text = "Поменять местами верх/вниз"; textSize = 15f
            isChecked = SettingsStore.getSwipeInvertV(this@SettingsActivity)
        }
        cal.addView(invVCheck)
        invHCheck = android.widget.CheckBox(this).apply {
            text = "Поменять местами влево/вправо"; textSize = 15f
            isChecked = SettingsStore.getSwipeInvertH(this@SettingsActivity)
        }
        cal.addView(invHCheck)
        cal.addView(UiKit.body(this, "Сила свайпа (1 очень мягкий – 5 сильный):"))
        strengthField = field(InputType.TYPE_CLASS_NUMBER).also { it.setText(SettingsStore.getSwipeStrength(this).toString()) }
        cal.addView(strengthField)
        cal.addView(UiKit.hint(this, "💡 В списках (контакты, чаты) говорите «чуть вниз» / «немного вверх» — короткий медленный свайп без перелистывания. Для лент/постов — обычные «вниз»/«вверх»."))
        cal.addView(UiKit.body(this, "Точка старта свайпа (имитация пальца), % экрана вдоль оси. Меняйте, если свайп цепляет не ту область:"))
        startDown = labeledNum(cal, "Старт «вниз» (сверху ↓):", SettingsStore.getSwipeStart(this, Direction.DOWN))
        startUp = labeledNum(cal, "Старт «вверх» (снизу ↑):", SettingsStore.getSwipeStart(this, Direction.UP))
        startLeft = labeledNum(cal, "Старт «влево» (справа ←):", SettingsStore.getSwipeStart(this, Direction.LEFT))
        startRight = labeledNum(cal, "Старт «вправо» (слева →):", SettingsStore.getSwipeStart(this, Direction.RIGHT))
        col.addView(cal)

        val s = UiKit.card(this)
        s.addView(UiKit.sectionHeader(this, "SOS"))
        s.addView(UiKit.body(this, "Номер для вызова помощи:"))
        sosNum = field(InputType.TYPE_CLASS_PHONE).also { it.setText(SettingsStore.getSosNumber(this)) }
        s.addView(sosNum)
        s.addView(UiKit.body(this, "Второй номер (эскалация) — СМС уйдёт на оба:"))
        sosNum2 = field(InputType.TYPE_CLASS_PHONE).also { it.setText(SettingsStore.getSosNumber2(this)) }
        s.addView(sosNum2)
        s.addView(UiKit.body(this, "Текст SOS-сообщения:"))
        sosTxt = field(InputType.TYPE_CLASS_TEXT).also {
            val t = SettingsStore.getSosText(this)
            it.setText(if (t.isBlank()) "SOS! Нужна срочная помощь." else t)
        }
        s.addView(sosTxt)
        s.addView(UiKit.body(this, "Код-подтверждение. Если задан — SOS только по «сос + код», напр. «сос 911». ЛУЧШЕ ЧИСЛО (911, 123) — распознаётся точнее редкого слова. Пусто — без защиты:"))
        sosPin = field(InputType.TYPE_CLASS_TEXT).also { it.setText(SettingsStore.getSosPin(this)) }
        s.addView(sosPin)
        col.addView(s)

        val c = UiKit.card(this)
        c.addView(UiKit.sectionHeader(this, "Быстрые контакты"))
        c.addView(UiKit.body(this, "Имя в дательном падеже («позвони КОМУ») и номер. Используйте ПРОСТЫЕ слова/имена (жене, маме, сыну, врачу) — редкие/выдуманные имена распознаются плохо."))
        contactsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(contactsBox)
        val saved = SettingsStore.getContacts(this).toList()
        val nContacts = maxOf(saved.size + 1, 4)
        for (i in 0 until nContacts) addContactRow(
            if (i < saved.size) saved[i].first else "",
            if (i < saved.size) saved[i].second else ""
        )
        c.addView(UiKit.button(this, "➕ Добавить контакт") { addContactRow("", "") })
        col.addView(c)

        // Кастомные команды запуска приложений
        val oc = UiKit.card(this)
        oc.addView(UiKit.sectionHeader(this, "Свои команды запуска"))
        oc.addView(UiKit.body(this, "Задайте слово-команду и выберите приложение. Скажете слово — приложение откроется."))
        openBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        oc.addView(openBox)
        val savedOpen = SettingsStore.getOpenCommands(this).toList()
        val nOpen = maxOf(savedOpen.size + 1, 3)
        for (i in 0 until nOpen) addOpenRow(
            if (i < savedOpen.size) savedOpen[i].first else "",
            if (i < savedOpen.size) savedOpen[i].second else ""
        )
        oc.addView(UiKit.button(this, "➕ Добавить команду") { addOpenRow("", "") })
        col.addView(oc)

        // --- Точность распознавания (большая модель) ---
        val mc = UiKit.card(this)
        mc.addView(UiKit.sectionHeader(this, "Точность распознавания"))
        mc.addView(UiKit.body(this, "Большая модель (≈1.8 ГБ) — это СЕРВЕРНАЯ модель. На телефоне она работает МЕДЛЕННЕЕ (заметная задержка отклика) и для коротких команд часто не точнее малой. Рекомендуется для диктовки длинного текста, а для управления командами лучше малая (быстрее и стабильнее). Если включили и стало тормозить — снимите галочку. Загрузка в фоне, с авто-докачкой и отменой; ~4 ГБ места."))
        bigModelCheck = android.widget.CheckBox(this).apply {
            text = "Использовать большую модель"; textSize = 15f
            isChecked = SettingsStore.getBigModel(this@SettingsActivity)
            isEnabled = ModelDownloader.isReady(this@SettingsActivity)
        }
        mc.addView(bigModelCheck)
        modelStatus = UiKit.body(this, modelStatusText())
        mc.addView(modelStatus)
        mc.addView(UiKit.button(this, "⬇️ Скачать большую модель (1.8 ГБ)") {
            val i = Intent(this, ModelDownloadService::class.java).setAction(ModelDownloadService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            modelStatus.text = "Загрузка идёт в фоне — статус в шторке уведомлений. Можно свернуть приложение."
            Toast.makeText(this, "Загрузка началась (в фоне)", Toast.LENGTH_SHORT).show()
        })
        mc.addView(UiKit.button(this, "✖️ Отменить загрузку", R.drawable.btn_danger) {
            startService(Intent(this, ModelDownloadService::class.java).setAction(ModelDownloadService.ACTION_CANCEL))
            modelStatus.text = "Загрузка отменена. Частично скачанное сохранено — повторная загрузка продолжит с места обрыва."
        })
        mc.addView(UiKit.button(this, "🗑 Удалить большую модель", R.drawable.btn_danger) {
            ModelDownloader.delete(this)
            SettingsStore.setBigModel(this, false)
            bigModelCheck.isChecked = false; bigModelCheck.isEnabled = false
            modelStatus.text = "Статус: большая модель удалена"
        })
        col.addView(mc)

        // --- Голос озвучки ---
        val vc = UiKit.card(this)
        vc.addView(UiKit.sectionHeader(this, "Голос озвучки (TTS)"))
        vc.addView(UiKit.body(this, "Выберите голос (мужской/женский — из доступных в системе), тембр и скорость. Если голосов мало или качество низкое — установите «Речевой синтезатор Google» и языки в системных настройках."))

        vc.addView(UiKit.body(this, "Голос:"))
        voiceSpinner = android.widget.Spinner(this)
        vc.addView(voiceSpinner)

        vc.addView(UiKit.body(this, "Тембр (ниже — «мужественнее», выше — «звонче»):"))
        pitchBar = android.widget.SeekBar(this).apply {
            max = 150; progress = ((SettingsStore.getTtsPitch(this@SettingsActivity) * 100).toInt() - 50).coerceIn(0, 150)
        }
        vc.addView(pitchBar)

        vc.addView(UiKit.body(this, "Скорость речи:"))
        rateBar = android.widget.SeekBar(this).apply {
            max = 150; progress = ((SettingsStore.getTtsRate(this@SettingsActivity) * 100).toInt() - 50).coerceIn(0, 150)
        }
        vc.addView(rateBar)

        vc.addView(UiKit.button(this, "🔊 Проверить голос") { testVoice() })
        vc.addView(UiKit.button(this, "⚙️ Системные настройки синтеза речи") {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(this, "Откройте: Настройки → Спец.возможности → Синтез речи", Toast.LENGTH_LONG).show() }
        })
        col.addView(vc)
        initVoices()

        col.addView(UiKit.button(this, "💾 Сохранить и перезапустить", R.drawable.btn_amber) { save() })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private var voiceTts: android.speech.tts.TextToSpeech? = null
    private val voiceNames = ArrayList<String>()      // "" = авто, далее имена голосов
    private fun initVoices() {
        voiceTts = android.speech.tts.TextToSpeech(this) { status ->
            if (status != android.speech.tts.TextToSpeech.SUCCESS) return@TextToSpeech
            runCatching { voiceTts?.language = java.util.Locale("ru") }
            val labels = ArrayList<String>()
            voiceNames.clear()
            voiceNames.add(""); labels.add("Авто (по умолчанию)")
            runCatching {
                voiceTts?.voices
                    ?.filter { it.locale.language == "ru" && !it.isNetworkConnectionRequired }
                    ?.sortedBy { it.name }
                    ?.forEachIndexed { i, v ->
                        voiceNames.add(v.name)
                        labels.add("Голос ${i + 1}${if (v.name.contains("male", true)) " (муж)" else ""}")
                    }
            }
            runOnUiThread {
                val ad = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                voiceSpinner.adapter = ad
                val saved = SettingsStore.getTtsVoice(this)
                val idx = voiceNames.indexOf(saved).coerceAtLeast(0)
                voiceSpinner.setSelection(idx)
            }
        }
    }

    private fun curPitch() = (50 + pitchBar.progress) / 100f
    private fun curRate() = (50 + rateBar.progress) / 100f
    private fun selectedVoice(): String = voiceNames.getOrElse(voiceSpinner.selectedItemPosition) { "" }

    private fun testVoice() {
        val t = voiceTts ?: return
        runCatching {
            t.setPitch(curPitch()); t.setSpeechRate(curRate())
            val vn = selectedVoice()
            if (vn.isNotBlank()) t.voices?.firstOrNull { it.name == vn }?.let { t.voice = it }
            t.speak("Привет! Это проверка голоса. Звоню жене.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "test")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { voiceTts?.stop(); voiceTts?.shutdown() }
        voiceTts = null
    }

    private fun modelStatusText(): String = when {
        ModelDownloader.running -> "Идёт фоновая загрузка — статус в шторке уведомлений"
        ModelDownloader.isReady(this) -> "Статус: большая модель загружена ✅"
        else -> "Статус: большая модель не загружена"
    }

    override fun onResume() {
        super.onResume()
        if (::modelStatus.isInitialized) {
            modelStatus.text = modelStatusText()
            bigModelCheck.isEnabled = ModelDownloader.isReady(this)
            bigModelCheck.isChecked = SettingsStore.getBigModel(this) && ModelDownloader.isReady(this)
        }
    }

    private fun field(type: Int): EditText = EditText(this).apply {
        inputType = type
        textSize = 16f
        setBackgroundResource(R.drawable.field_bg)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = UiKit.dp(this@SettingsActivity, 4); bottomMargin = UiKit.dp(this@SettingsActivity, 8) }
        val ph = UiKit.dp(this@SettingsActivity, 12)
        setPadding(ph, ph, ph, ph)
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
        SettingsStore.setMediaCode(this, mediaCodeField.text.toString().ifBlank { "медиа" })
        SettingsStore.setMediaWindowSec(this, mediaWindow.text.toString().toIntOrNull() ?: 4)
        SettingsStore.setTts(this, ttsCheck.isChecked)
        SettingsStore.setConfirmCalls(this, confirmCheck.isChecked)
        SettingsStore.setSosNumber2(this, sosNum2.text.toString())
        if (::pitchBar.isInitialized) SettingsStore.setTtsPitch(this, curPitch())
        if (::rateBar.isInitialized) SettingsStore.setTtsRate(this, curRate())
        if (::voiceSpinner.isInitialized) SettingsStore.setTtsVoice(this, selectedVoice())
        SettingsStore.setIdle(this, idle.text.toString().toIntOrNull()?.coerceIn(10, 300) ?: 30)
        SettingsStore.setSosNumber(this, sosNum.text.toString())
        SettingsStore.setSosText(this, sosTxt.text.toString())
        SettingsStore.setSosPin(this, sosPin.text.toString())
        SettingsStore.setIgnoreMedia(this, ignoreMediaCheck.isChecked)
        SettingsStore.setVibrate(this, vibrateCheck.isChecked)
        SettingsStore.setKeepScreen(this, keepScreenCheck.isChecked)
        SettingsStore.setBigModel(this, bigModelCheck.isChecked && ModelDownloader.isReady(this))
        SettingsStore.setSwipeInvertV(this, invVCheck.isChecked)
        SettingsStore.setSwipeInvertH(this, invHCheck.isChecked)
        SettingsStore.setSwipeStrength(this, strengthField.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 2)
        SettingsStore.setSwipeStart(this, Direction.DOWN, startDown.text.toString().toIntOrNull() ?: 30)
        SettingsStore.setSwipeStart(this, Direction.UP, startUp.text.toString().toIntOrNull() ?: 70)
        SettingsStore.setSwipeStart(this, Direction.LEFT, startLeft.text.toString().toIntOrNull() ?: 70)
        SettingsStore.setSwipeStart(this, Direction.RIGHT, startRight.text.toString().toIntOrNull() ?: 30)

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
