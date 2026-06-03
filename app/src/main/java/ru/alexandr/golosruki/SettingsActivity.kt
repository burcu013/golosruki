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

    companion object { private const val REQ_EXPORT = 2001; private const val REQ_IMPORT = 2002 }

    private lateinit var wake: EditText
    private lateinit var mediaCodeField: EditText
    private lateinit var ttsCheck: android.widget.Switch
    private lateinit var confirmCheck: android.widget.Switch
    private lateinit var btMicCheck: android.widget.Switch
    private lateinit var noiseCheck: android.widget.Switch
    private lateinit var aiEnableCheck: android.widget.Switch
    private lateinit var aiNameField: EditText
    private lateinit var aiModeCheck: android.widget.Switch
    private lateinit var aiNotesField: EditText
    private lateinit var aiGoalChecks: List<android.widget.Switch>
    private lateinit var aiCapChecks: List<android.widget.Switch>
    private lateinit var aiStyleCheck: android.widget.Switch
    private lateinit var aiVoiceCheck: android.widget.Switch
    private lateinit var aiEngineCheck: android.widget.Switch
    private lateinit var sosNum2: EditText
    private lateinit var voiceSpinner: android.widget.Spinner
    private lateinit var pitchBar: android.widget.SeekBar
    private lateinit var rateBar: android.widget.SeekBar
    private lateinit var idle: EditText
    private lateinit var mediaWindow: EditText
    private lateinit var sosNum: EditText
    private lateinit var sosTxt: EditText
    private lateinit var sosPin: EditText
    private lateinit var ignoreMediaCheck: android.widget.Switch
    private lateinit var vibrateCheck: android.widget.Switch
    private lateinit var keepScreenCheck: android.widget.Switch
    private lateinit var invVCheck: android.widget.Switch
    private lateinit var invHCheck: android.widget.Switch
    private lateinit var strengthField: EditText
    private lateinit var startDown: EditText
    private lateinit var startUp: EditText
    private lateinit var startLeft: EditText
    private lateinit var startRight: EditText
    private lateinit var longPressField: EditText
    private fun labeledNum(box: LinearLayout, label: String, value: Int): EditText {
        box.addView(UiKit.body(this, label))
        val f = field(InputType.TYPE_CLASS_NUMBER).also { it.setText(value.toString()) }
        box.addView(f)
        return f
    }
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
        ignoreMediaCheck = UiKit.switchView(this).apply {
            text = "Не реагировать во время видео/музыки"
            textSize = 15f
            isChecked = SettingsStore.getIgnoreMedia(this@SettingsActivity)
        }
        a.addView(ignoreMediaCheck)
        vibrateCheck = UiKit.switchView(this).apply {
            text = "Вибро-отклик при активации"; textSize = 15f
            isChecked = SettingsStore.getVibrate(this@SettingsActivity)
        }
        a.addView(vibrateCheck)
        keepScreenCheck = UiKit.switchView(this).apply {
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
        ttsCheck = UiKit.switchView(this).apply {
            text = "Голосовые подтверждения (озвучивание действий)"; textSize = 15f
            isChecked = SettingsStore.getTts(this@SettingsActivity)
        }
        a.addView(ttsCheck)
        confirmCheck = UiKit.switchView(this).apply {
            text = "Спрашивать подтверждение перед звонком"; textSize = 15f
            isChecked = SettingsStore.getConfirmCalls(this@SettingsActivity)
        }
        a.addView(confirmCheck)
        btMicCheck = UiKit.switchView(this).apply {
            text = "Bluetooth-микрофон (гарнитура)"; textSize = 15f
            isChecked = SettingsStore.getBtMic(this@SettingsActivity)
        }
        a.addView(btMicCheck)
        a.addView(UiKit.hint(this, "🎧 Если гарнитура недоступна или села — Иван автоматически вернётся на встроенный микрофон. Поддержка зависит от модели телефона/гарнитуры."))
        noiseCheck = UiKit.switchView(this).apply {
            text = "Шумоподавление (эксперимент)"; textSize = 15f
            isChecked = SettingsStore.getNoiseSuppress(this@SettingsActivity)
        }
        a.addView(noiseCheck)
        a.addView(UiKit.hint(this, "🔇 Включает аппаратное подавление шума и эха микрофона. ПО УМОЛЧАНИЮ ВЫКЛ — включайте, только если в шумной обстановке/при эхе команды распознаются хуже. Если станет хуже — выключите. Поддержка и качество зависят от модели телефона."))
        col.addView(a)

        // Калибровка свайпов
        val cal = UiKit.card(this)
        cal.addView(UiKit.sectionHeader(this, "Калибровка свайпов"))
        cal.addView(UiKit.body(this, "Свайп = жест пальцем. Если направление непривычно (или планшет повёрнут) — поменяйте местами."))
        invVCheck = UiKit.switchView(this).apply {
            text = "Поменять местами верх/вниз"; textSize = 15f
            isChecked = SettingsStore.getSwipeInvertV(this@SettingsActivity)
        }
        cal.addView(invVCheck)
        invHCheck = UiKit.switchView(this).apply {
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
        cal.addView(UiKit.body(this, "Длительность долгого нажатия (мс), 500–3000:"))
        longPressField = field(InputType.TYPE_CLASS_NUMBER).also { it.setText(SettingsStore.getLongPressMs(this).toString()) }
        cal.addView(longPressField)
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

        // --- ИИ-помощник (преднастройка) ---
        val aiP = AiProfile.load(this)
        val ai = UiKit.card(this)
        ai.addView(UiKit.sectionHeader(this, "🤖 ИИ-помощник (бета)"))
        ai.addView(UiKit.body(this, "Офлайн-ИИ для ответов на вопросы и «причёсывания» диктовки. Здесь — преднастройка под вас; сам движок (модель) подключается на следующем этапе. Управление телефоном всегда остаётся на быстрых командах."))
        aiEnableCheck = UiKit.switchView(this).apply {
            text = "Включить ИИ-помощника"; textSize = 15f; isChecked = aiP.enabled
        }
        ai.addView(aiEnableCheck)
        ai.addView(UiKit.body(this, "Имя (как к вам обращаться):"))
        aiNameField = field(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
            setText(aiP.name); hint = "Например: Александр"
        }
        ai.addView(aiNameField)
        aiModeCheck = UiKit.switchView(this).apply {
            text = "Режим спец-доступа (управление только голосом)"; textSize = 15f
            isChecked = aiP.mode == "aid"
        }
        ai.addView(aiModeCheck)
        ai.addView(UiKit.body(this, "Возможности (ИИ подстроит ответы под вас):"))
        aiCapChecks = AiProfile.CAPS.map { c ->
            UiKit.switchView(this).apply { text = c; textSize = 15f; isChecked = aiP.capabilities.contains(c) }.also { ai.addView(it) }
        }
        ai.addView(UiKit.body(this, "Особенности (что важно учитывать ИИ):"))
        aiNotesField = field(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            setText(aiP.notes); hint = "Например: отвечай коротко, я управляю голосом"
            minLines = 2
        }
        ai.addView(aiNotesField)
        ai.addView(UiKit.body(this, "Цели использования:"))
        aiGoalChecks = AiProfile.GOALS.map { g ->
            UiKit.switchView(this).apply { text = g; textSize = 15f; isChecked = aiP.goals.contains(g) }.also { ai.addView(it) }
        }
        aiStyleCheck = UiKit.switchView(this).apply {
            text = "Подробные ответы (иначе — кратко)"; textSize = 15f; isChecked = aiP.style == "detailed"
        }
        ai.addView(aiStyleCheck)
        aiVoiceCheck = UiKit.switchView(this).apply {
            text = "Озвучивать ответы ИИ"; textSize = 15f; isChecked = aiP.voiceAnswers
        }
        ai.addView(aiVoiceCheck)
        aiEngineCheck = UiKit.switchView(this).apply {
            text = "Топ-режим (MediaPipe, для мощных телефонов)"; textSize = 15f; isChecked = aiP.engine == "top"
        }
        ai.addView(aiEngineCheck)
        ai.addView(UiKit.hint(this, "🔌 Универсальный режим (по умолчанию) — llama.cpp, работает на большинстве телефонов. Топ-режим — MediaPipe, быстрее на мощных. Модель ИИ скачивается отдельно (появится на следующем этапе)."))
        col.addView(ai)

        // --- Голос озвучки ---
        val vc = UiKit.card(this)
        vc.addView(UiKit.sectionHeader(this, "Голос озвучки (TTS)"))
        vc.addView(UiKit.body(this, "По умолчанию голос настроен мягче и чуть ниже (спокойный мужской тембр). Конкретный голос (мужской/женский) зависит от установленного в системе движка. Если голосов мало — поставьте «Речевой синтезатор Google» и скачайте русские голоса. Тембр и скорость — ползунками ниже."))

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

        val bc = UiKit.card(this)
        bc.addView(UiKit.sectionHeader(this, "Резервная копия настроек"))
        bc.addView(UiKit.body(this, "Сохраните все настройки (слово активации, контакты, команды, голос, калибровка) в файл — поделиться или перенести на другой телефон."))
        bc.addView(UiKit.button(this, "📤 Экспорт в файл") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "golosruki_settings.json")
            }
            runCatching { startActivityForResult(i, REQ_EXPORT) }
        })
        bc.addView(UiKit.button(this, "📥 Импорт из файла", R.drawable.btn_amber) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }
            runCatching { startActivityForResult(i, REQ_IMPORT) }
        })
        col.addView(bc)

        col.addView(UiKit.button(this, "💾 Сохранить и перезапустить", R.drawable.btn_amber) { save() })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(SettingsStore.exportJson(this).toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "Настройки сохранены в файл", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this, "Ошибка экспорта", Toast.LENGTH_SHORT).show() }
            REQ_IMPORT -> {
                val txt = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                if (txt != null && SettingsStore.importJson(this, txt)) {
                    Toast.makeText(this, "Настройки импортированы — перезапуск", Toast.LENGTH_LONG).show()
                    val s = Intent(this, VoiceRecognitionService::class.java)
                    stopService(s)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
                    recreate()
                } else {
                    Toast.makeText(this, "Не удалось прочитать файл настроек", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
        SettingsStore.setBtMic(this, btMicCheck.isChecked)
        SettingsStore.setNoiseSuppress(this, noiseCheck.isChecked)
        AiProfile.save(this, AiProfile.Profile(
            enabled = aiEnableCheck.isChecked,
            name = aiNameField.text.toString().trim(),
            mode = if (aiModeCheck.isChecked) "aid" else "free",
            notes = aiNotesField.text.toString().trim(),
            goals = aiGoalChecks.indices.filter { aiGoalChecks[it].isChecked }.map { AiProfile.GOALS[it] }.toSet(),
            style = if (aiStyleCheck.isChecked) "detailed" else "short",
            voiceAnswers = aiVoiceCheck.isChecked,
            engine = if (aiEngineCheck.isChecked) "top" else "universal",
            capabilities = aiCapChecks.indices.filter { aiCapChecks[it].isChecked }.map { AiProfile.CAPS[it] }.toSet()
        ))
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
        SettingsStore.setSwipeInvertV(this, invVCheck.isChecked)
        SettingsStore.setSwipeInvertH(this, invHCheck.isChecked)
        SettingsStore.setSwipeStrength(this, strengthField.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 2)
        SettingsStore.setSwipeStart(this, Direction.DOWN, startDown.text.toString().toIntOrNull() ?: 30)
        SettingsStore.setSwipeStart(this, Direction.UP, startUp.text.toString().toIntOrNull() ?: 70)
        SettingsStore.setSwipeStart(this, Direction.LEFT, startLeft.text.toString().toIntOrNull() ?: 70)
        SettingsStore.setSwipeStart(this, Direction.RIGHT, startRight.text.toString().toIntOrNull() ?: 30)
        SettingsStore.setLongPressMs(this, longPressField.text.toString().toIntOrNull() ?: 1000)

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

        val intent = Intent(this, VoiceRecognitionService::class.java).setAction(VoiceRecognitionService.ACTION_APPLY)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)

        Toast.makeText(this, "Сохранено и применено", Toast.LENGTH_SHORT).show()
        finish()
    }
}
