package ru.alexandr.golosruki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Слушает микрофон офлайн (Vosk). Активация по слову (по умолч. «Иван»).
 * Через N сек простоя — сон. «<слово> привет» — разблокировка. Состояние — в шторке.
 */
class VoiceRecognitionService : Service(), RecognitionListener {

    private var model: Model? = null        // малая модель — команды (грамматика), всегда загружена
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null               // текущий распознаватель — закрываем при перезапуске (иначе утечка нативной памяти → краш, особенно с большой моделью)
    private var nsService: NsSpeechService? = null            // альтернативный движок с шумоподавлением
    @Volatile private var useNoiseSuppress = false            // тумблер (по умолчанию ВЫКЛ)
    private lateinit var personal: PersonalConfig
    private val handler = Handler(Looper.getMainLooper())

    private var wakeWord = "иван"
    private var idleMs = 30_000L
    private var ignoreMedia = true
    private var vibrateOnWake = true
    private var keepScreen = true
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }

    // --- Bluetooth-микрофон (SCO) с откатом на встроенный ---
    @Volatile private var btMicWanted = false
    @Volatile private var btScoOn = false
    private var btReceiver: android.content.BroadcastReceiver? = null
    private fun enableBtMic() {
        btMicWanted = true
        if (Build.VERSION.SDK_INT >= 31 && enableBtMicModern()) return
        enableBtMicLegacy()
    }

    /** Новый API (Android 12+): маршрутизация захвата звука на BT-устройство связи. */
    private fun enableBtMicModern(): Boolean {
        return try {
            val bt = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (bt == null) {
                Logger.log("MIC", "BT-устройство связи не найдено (новый API) — пробую старый способ")
                return false
            }
            val ok = audioManager.setCommunicationDevice(bt)
            if (ok) {
                btScoOn = true
                Logger.log("MIC", "Bluetooth-микрофон активен (новый API): ${bt.productName}")
                VoiceAccessibilityService.instance?.showStatus("🎧 Bluetooth-микрофон активен")
                if (model != null) restartListening()
            } else {
                Logger.log("MIC", "setCommunicationDevice вернул false — пробую старый способ")
            }
            ok
        } catch (e: Exception) {
            Logger.log("MIC", "Новый BT API не сработал: ${e.message}"); false
        }
    }

    /** Старый способ (Android < 12 либо если новый API не нашёл устройство): Bluetooth SCO. */
    private fun enableBtMicLegacy() {
        runCatching {
            if (btReceiver == null) {
                btReceiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        val st = i?.getIntExtra(android.media.AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ?: -1
                        when (st) {
                            android.media.AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                btScoOn = true
                                Logger.log("MIC", "Bluetooth-микрофон подключён")
                                VoiceAccessibilityService.instance?.showStatus("🎧 Bluetooth-микрофон активен")
                                if (model != null) restartListening()
                            }
                            android.media.AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                            android.media.AudioManager.SCO_AUDIO_STATE_ERROR -> {
                                if (btScoOn || btMicWanted) {
                                    btScoOn = false
                                    Logger.log("MIC", "Bluetooth-микрофон недоступен — откат на встроенный")
                                    VoiceAccessibilityService.instance?.showStatus("Bluetooth-микрофон недоступен — встроенный")
                                    if (model != null) restartListening()
                                }
                            }
                        }
                    }
                }
                val filter = android.content.IntentFilter(android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                if (Build.VERSION.SDK_INT >= 33)
                    registerReceiver(btReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else
                    registerReceiver(btReceiver, filter)
            }
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }
        // страховка: если за 8 c не подключилось — работаем на встроенном
        handler.postDelayed({
            if (btMicWanted && !btScoOn) {
                Logger.log("MIC", "BT SCO не подключился за 8с — встроенный микрофон")
                VoiceAccessibilityService.instance?.showStatus("Bluetooth-микрофон не подключился — встроенный")
            }
        }, 8000)
    }
    private fun disableBtMic() {
        btMicWanted = false; btScoOn = false
        if (Build.VERSION.SDK_INT >= 31) runCatching { audioManager.clearCommunicationDevice() }
        runCatching { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco() }
        runCatching { btReceiver?.let { unregisterReceiver(it) } }; btReceiver = null
    }

    /** Играет ли сейчас звук. isMusicActive = true только при реальном воспроизведении. */
    private fun isMediaPlaying(): Boolean = try { audioManager.isMusicActive } catch (e: Exception) { false }
    /** Активный разговор: сотовый (MODE_IN_CALL) или мессенджер (MODE_IN_COMMUNICATION), но не наш BT-SCO. */
    private fun inCall(): Boolean = try {
        val m = audioManager.mode
        // ВАЖНО: при включённом шумоподавлении мы сами открываем VOICE_COMMUNICATION-захват,
        // из-за чего система может выставить MODE_IN_COMMUNICATION. Это НЕ звонок — не учитываем,
        // пока работает наш NS-движок (иначе ломается гейт команд: всё требует слово активации).
        m == android.media.AudioManager.MODE_IN_CALL ||
            (m == android.media.AudioManager.MODE_IN_COMMUNICATION && !btScoOn && nsService == null)
    } catch (e: Exception) { false }
    fun wakeWordPublic(): String = wakeWord

    /** Экран включён (интерактивен)? */
    private fun isScreenOn(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    } catch (e: Exception) { true }

    private enum class State { ASLEEP, AWAKE }
    @Volatile private var state = State.ASLEEP
    @Volatile private var dictation = false
    @Volatile private var dictationDigits = false
    @Volatile var aiListening = false      // идёт свободный захват вопроса/текста для ИИ
    @Volatile private var aiAsk = true     // true — вопрос, false — сформулировать текст
    @Volatile private var aiThinking = false  // идёт генерация ответа (модель «думает»)
    private val dictBuffer = StringBuilder()
    @Volatile private var mediaControlMode = false
    private var mediaCode = "медиа"
    @Volatile private var screenOffMedia = false
    private var mediaWindowMs = 4000L
    private val screenOffMediaOff = Runnable { screenOffMedia = false; Logger.log("MEDIA", "Окно медиа (экран выкл) закрыто") }
    private val mediaModeOff = Runnable { mediaControlMode = false; Logger.log("MEDIA", "Медиа-режим выключен по таймауту") }
    private fun armScreenOffMedia() {
        screenOffMedia = true
        handler.removeCallbacks(screenOffMediaOff)
        handler.postDelayed(screenOffMediaOff, mediaWindowMs)
    }
    private fun armMediaMode() {
        // Медиа-режим держится до явного выключения командой «медиа» (или блокировки/выхода).
        // Авто-таймаут убран: при простое включается сон, но медиа-режим сохраняется и
        // возвращается после пробуждения.
        handler.removeCallbacks(mediaModeOff)
    }
    /** Принудительно выключить медиа-режим (выход из приложения, блокировка). */
    fun clearMediaMode() {
        if (mediaControlMode) {
            mediaControlMode = false
            handler.removeCallbacks(mediaModeOff)
            VoiceAccessibilityService.instance?.setStatusIcon(OverlayView.Icon.DOT)
            Logger.log("MEDIA", "Медиа-режим выключен (выход/блокировка)")
        }
    }
    private fun toggleMediaMode() {
        mediaControlMode = !mediaControlMode
        if (mediaControlMode) armMediaMode() else handler.removeCallbacks(mediaModeOff)
        if (paused) setPaused(false)
        resetIdle()
        VoiceAccessibilityService.instance?.setStatusIcon(
            if (mediaControlMode) OverlayView.Icon.MEDIA else OverlayView.Icon.DOT
        )
        VoiceAccessibilityService.instance?.showStatus(
            if (mediaControlMode) "🎵 Медиа-режим ВКЛ: пауза/играй/треки/листание без «${cap(wakeWord)}»"
            else "Медиа-режим выкл"
        )
    }
    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var isSpeaking = false
    private var ttsEnabled = true
    private var ttsPitch = 1.0f
    private var ttsRate = 1.0f
    private var ttsVoiceName = ""
    private var confirmCalls = false
    private var pendingCall: Pair<String, String>? = null   // имя -> номер (ожидает «да»)

    private val idleRunnable = Runnable {
        if (aiListening) { aiListening = false; restartListening() }   // заброшенный ИИ-запрос — сброс
        state = State.ASLEEP
        VoiceAccessibilityService.instance?.showStatus(stateText())
        VoiceAccessibilityService.instance?.keepScreenOn(false)
        refreshNotification()
    }

    companion object {
        const val CHANNEL_ID = "golosruki_voice"
        const val NOTIF_ID = 1
        const val ACTION_RESET = "ru.alexandr.golosruki.RESET"
        const val ACTION_RELOAD = "ru.alexandr.golosruki.RELOAD"   // перезагрузить модель (после смены пакета)
        const val ACTION_APPLY = "ru.alexandr.golosruki.APPLY"     // применить настройки на лету (без пересоздания службы)
        @Volatile var instance: VoiceRecognitionService? = null
        @Volatile private var paused = false
        fun setPaused(p: Boolean) {
            paused = p
            instance?.let { svc -> svc.handler.post { svc.refreshNotification() } }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET -> resetState()
            ACTION_RELOAD -> reloadModel()
            ACTION_APPLY -> applySettings()
        }
        return START_STICKY
    }

    /** Применить настройки на лету: перечитать конфиг и перезапустить распознавание.
     *  Надёжнее, чем stop/start службы (та может не пересоздаться и не перечитать поля). */
    fun applySettings() {
        personal = PersonalConfig.load(this)
        wakeWord = SettingsStore.getWake(this)
        idleMs = SettingsStore.getIdle(this) * 1000L
        ignoreMedia = SettingsStore.getIgnoreMedia(this)
        vibrateOnWake = SettingsStore.getVibrate(this)
        keepScreen = SettingsStore.getKeepScreen(this)
        mediaCode = SettingsStore.getMediaCode(this).lowercase().trim()
        if (mediaCode == "видео") { mediaCode = "медиа"; SettingsStore.setMediaCode(this, "медиа") }
        mediaWindowMs = SettingsStore.getMediaWindowSec(this) * 1000L
        ttsEnabled = SettingsStore.getTts(this)
        confirmCalls = SettingsStore.getConfirmCalls(this)
        ttsPitch = SettingsStore.getTtsPitch(this)
        ttsRate = SettingsStore.getTtsRate(this)
        ttsVoiceName = SettingsStore.getTtsVoice(this)
        CommandAliases.aliasMap = SettingsStore.getAliasMap(this)
        useNoiseSuppress = SettingsStore.getNoiseSuppress(this)
        Logger.log("CFG", "Настройки применены: слово='$wakeWord', сон=${idleMs/1000}с, медиа-игнор=$ignoreMedia, шумоподавление=$useNoiseSuppress")
        if (model != null && !dictation) restartListening()
        refreshNotification()
        VoiceAccessibilityService.instance?.showStatus("Настройки применены")
    }

    /** Перезагрузка модели (например, после скачивания большой). */
    fun reloadModel() {
        Logger.log("REC", "Обновление доступности большой модели")
        // если большую отключили/удалили — освобождаем память
        if (!dictation) restartListening()   // команды продолжают на малой
        VoiceAccessibilityService.instance?.showStatus("Настройки модели обновлены")
    }

    /** Полный сброс: снять паузу/диктовку, разбудить, перезапустить распознавание. */
    fun resetState() {
        Logger.log("REC", "СБРОС (перезапуск Ивана)")
        dictation = false
        setPaused(false)
        state = State.AWAKE
        if (model != null) restartListening()
        resetIdle()
        VoiceAccessibilityService.instance?.keepScreenOn(true)
        refreshNotification()
        VoiceAccessibilityService.instance?.showStatus("${cap(wakeWord)} перезапущен")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.init(this)
        Logger.log("SVC", "Служба запущена")
        paused = false
        personal = PersonalConfig.load(this)
        wakeWord = SettingsStore.getWake(this)
        idleMs = SettingsStore.getIdle(this) * 1000L
        ignoreMedia = SettingsStore.getIgnoreMedia(this)
        vibrateOnWake = SettingsStore.getVibrate(this)
        keepScreen = SettingsStore.getKeepScreen(this)
        mediaCode = SettingsStore.getMediaCode(this).lowercase().trim()
        if (mediaCode == "видео") { mediaCode = "медиа"; SettingsStore.setMediaCode(this, "медиа") }  // миграция
        mediaWindowMs = SettingsStore.getMediaWindowSec(this) * 1000L
        ttsEnabled = SettingsStore.getTts(this)
        confirmCalls = SettingsStore.getConfirmCalls(this)
        ttsPitch = SettingsStore.getTtsPitch(this)
        ttsRate = SettingsStore.getTtsRate(this)
        ttsVoiceName = SettingsStore.getTtsVoice(this)
        CommandAliases.aliasMap = SettingsStore.getAliasMap(this)   // персональные триггеры/коррекции
        useNoiseSuppress = SettingsStore.getNoiseSuppress(this)
        if (SettingsStore.getBtMic(this)) enableBtMic()
        if (ttsEnabled) {
            tts = android.speech.tts.TextToSpeech(this) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    runCatching { tts?.language = java.util.Locale("ru") }
                    runCatching { tts?.setPitch(ttsPitch) }
                    runCatching { tts?.setSpeechRate(ttsRate) }
                    if (ttsVoiceName.isNotBlank()) runCatching {
                        tts?.voices?.firstOrNull { it.name == ttsVoiceName }?.let { tts?.voice = it }
                    }
                    ttsReady = true
                }
            }
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) { isSpeaking = true; listeningSetPause(true) }
                override fun onDone(id: String?) { scheduleResume(500) }
                @Deprecated("deprecated") override fun onError(id: String?) { scheduleResume(500) }
            })
        }
        Logger.log("REC", "Старт службы. Слово активации: '$wakeWord', сон: ${idleMs / 1000}с")
        Logger.log("CFG", "Контактов: ${personal.contacts.size} ${personal.contacts.keys}; " +
            "своих команд: ${personal.customApps.size}; " +
            "SOS-код: ${if (personal.sosPin.isBlank()) "нет" else "'" + personal.sosPin + "'"}; " +
            "SOS-номер: ${if (personal.sosNumber.isBlank()) "НЕ ЗАДАН" else "есть"}")

        val notif = buildNotification("Запуск…")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, notif)
        initModel()
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun flushDictation() {
        val full = dictBuffer.toString()
        post {
            val kb = GolosRukiKeyboardService.instance
            if (kb != null && kb.isActiveInput()) kb.commitDictation(full)
            else VoiceAccessibilityService.instance?.commitDictation(full)
        }
    }

    /** Диктовка обычного текста с поддержкой пунктуации и переносов. */
    private fun applyDictation(text: String) {
        val tokens = text.split(" ").filter { it.isNotBlank() }
        var i = 0
        while (i < tokens.size) {
            val w = tokens[i]
            val two = if (i + 1 < tokens.size) "$w ${tokens[i + 1]}" else ""
            when {
                two == "новая строка" || two == "новый абзац" -> { dictBuffer.append("\n"); i += 2; continue }
                two == "восклицательный знак" -> { trimEndSpace(); dictBuffer.append("! "); i += 2; continue }
                two == "вопросительный знак" -> { trimEndSpace(); dictBuffer.append("? "); i += 2; continue }
                two == "с большой" -> { capNext = true; i += 2; continue }   // «с большой буквы»
                w == "запятая" -> { trimEndSpace(); dictBuffer.append(", ") }
                w == "точка" -> { trimEndSpace(); dictBuffer.append(". "); capNext = true }
                w == "вопрос" -> { trimEndSpace(); dictBuffer.append("? "); capNext = true }
                w == "двоеточие" -> { trimEndSpace(); dictBuffer.append(": ") }
                w == "тире" || w == "дефис" -> dictBuffer.append("— ")
                w == "пробел" -> dictBuffer.append(" ")
                w == "буквы" -> { /* хвост от «с большой буквы» */ }
                else -> {
                    val word = if (capNext) w.replaceFirstChar { it.uppercase() } else w
                    capNext = false
                    dictBuffer.append(word).append(" ")
                }
            }
            i++
        }
        flushDictation()
    }

    private var capNext = false
    private fun trimEndSpace() {
        while (dictBuffer.isNotEmpty() && dictBuffer.last() == ' ') dictBuffer.deleteCharAt(dictBuffer.length - 1)
    }

    private val resumeAfterSpeak = Runnable {
        isSpeaking = false
        listeningSetPause(false)
        Logger.log("REC", "Распознавание возобновлено после речи")
    }
    private fun scheduleResume(delay: Long) {
        handler.removeCallbacks(resumeAfterSpeak)
        handler.postDelayed(resumeAfterSpeak, delay)
    }

    fun speak(text: String) {
        if (!ttsEnabled || !ttsReady) return
        // ПОЛНОСТЬЮ глушим распознавание на время речи: Vosk не копит звук синтезатора (анти-петля)
        isSpeaking = true
        listeningSetPause(true)
        runCatching {
            tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "golosruki")
        }
        // страховка, если движок не сообщит об окончании
        scheduleResume(1800L + text.length * 90L)
    }

    private fun vibrateTick() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            }
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(40)
        } catch (e: Exception) { /* без вибро */ }
    }

    private fun stateText(): String = when {
        aiThinking -> "🧠 Думаю…"
        aiListening -> if (aiAsk) "🧠 Слушаю вопрос — говорите" else "🧠 Что сформулировать — говорите"
        dictation && dictationDigits -> "✍️ Диктовка цифрами — «готово» для выхода"
        dictation -> "✍️ Диктовка — говорите текст, «готово» для выхода"
        paused -> "⏸ Пауза — скажите «слушай»"
        state == State.AWAKE && mediaControlMode -> "🎵 Медиа-режим: пауза/играй/треки/листание без «${cap(wakeWord)}»"
        state == State.AWAKE -> "🎙 Слушаю команды"
        mediaControlMode -> "😴 Сон (медиа активен) — скажите «${cap(wakeWord)}»"
        else -> "😴 Сон — скажите «${cap(wakeWord)}»"
    }

    /** Базовый статус для авто-возврата оверлея (после кратких сообщений о командах). */
    fun baseStatusText(): String = stateText()

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Голосовое управление", NotificationManager.IMPORTANCE_LOW)
            )
        val tap = Intent(this, MainActivity::class.java)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, tap,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val resetIntent = Intent(this, VoiceRecognitionService::class.java).setAction(ACTION_RESET)
        val resetPi = if (Build.VERSION.SDK_INT >= 26)
            android.app.PendingIntent.getForegroundService(
                this, 1, resetIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        else android.app.PendingIntent.getService(
            this, 1, resetIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val resetAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_rotate, "Перезапустить Иван", resetPi
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ГолосРуки")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(resetAction)
            .build()
    }

    fun refreshNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(stateText()))
    }

    private fun initModel() {
        Thread {
            try {
                Logger.log("REC", "Установка модели команд (малая)…")
                        val path = ModelInstaller.ensureModel(this)   // малая модель — всегда для команд
                val m = Model(path)
                Logger.log("MODEL", "Малая модель: $path")
                handler.post { model = m; restartListening(); refreshNotification() }
            } catch (e: Exception) {
                Logger.log("REC", "Ошибка модели: ${e.message}")
                handler.post { VoiceAccessibilityService.instance?.showStatus("Ошибка модели: ${e.message}") }
            }
        }.start()
    }

    /** Перезапуск распознавателя под текущий режим: команды → малая+грамматика; диктовка → большая (если есть) свободно. */
    private fun listeningStop() {
        runCatching { nsService?.stop() }
        runCatching { nsService?.shutdown() }
        nsService = null
        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        speechService = null
        runCatching { recognizer?.close() }   // освобождаем нативную память распознавателя
        recognizer = null
    }

    private fun listeningSetPause(p: Boolean) {
        if (nsService != null) runCatching { nsService?.setPause(p) }
        else runCatching { speechService?.setPause(p) }
    }

    private fun restartListening() {
        listeningStop()
        handler.postDelayed({
            val m = model ?: return@postDelayed
            val rec = if (dictation || aiListening) {
                Recognizer(m, 16000.0f)   // диктовка/вопрос ИИ — свободное распознавание (без грамматики)
            } else {
                Recognizer(m, 16000.0f, Vocabulary.buildGrammar(personal, wakeWord, mediaCode))
            }
            recognizer = rec
            if (useNoiseSuppress) {
                val s = NsSpeechService(rec, 16000, this)
                if (s.start()) {
                    nsService = s
                } else {
                    Logger.log("NS", "Шумоподавление не запустилось — откат на штатный движок")
                    speechService = SpeechService(rec, 16000.0f).also { it.startListening(this) }
                }
            } else {
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
            }
            Logger.log("REC", "Слушаю (диктовка=$dictation, шумоподавление=${nsService != null})")
        }, 180)
    }

    private var savedIme: String? = null

    private fun imeReadyForAutoSwitch(): Boolean {
        if (!SettingsStore.getAutoIme(this)) return false
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun switchToVoiceIme() {
        if (!imeReadyForAutoSwitch()) return
        val ourId = "$packageName/.GolosRukiKeyboardService"
        val cur = android.provider.Settings.Secure.getString(contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        if (cur == ourId) return
        savedIme = cur
        runCatching {
            android.provider.Settings.Secure.putString(contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, ourId)
            Logger.log("IME", "Включена голосовая клавиатура")
        }
    }

    private fun switchBackIme() {
        val prev = savedIme ?: return
        savedIme = null
        runCatching {
            android.provider.Settings.Secure.putString(contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, prev)
            Logger.log("IME", "Возврат обычной клавиатуры")
        }
    }

    fun enterDictation(digits: Boolean = false) {
        val alreadyDictating = dictation
        dictation = true
        switchToVoiceIme()   // во время диктовки — наша клавиатура (если настроена)
        dictationDigits = digits
        // При первом входе — забираем уже имеющийся текст поля, чтобы не стереть его.
        // При переключении режима (текст↔цифры) — буфер НЕ трогаем.
        if (!alreadyDictating) {
            // Чистый старт: очищаем поле, чтобы не подмешивалась подсказка («Сообщение») или старый текст.
            dictBuffer.setLength(0)
            val kb = GolosRukiKeyboardService.instance
            if (kb != null && kb.isActiveInput()) kb.beginDictation()
            else VoiceAccessibilityService.instance?.beginDictationField()
        }
        VoiceAccessibilityService.instance?.setStatusIcon(if (digits) OverlayView.Icon.DIGITS else OverlayView.Icon.PEN)
        VoiceAccessibilityService.instance?.showStatus(stateText())
        restartListening()   // пока (или если без большой) — свободный режим на малой
        resetIdle(); refreshNotification()
    }

    private fun exitDictation() {
        dictation = false
        dictationDigits = false
        dictBuffer.setLength(0)
        switchBackIme()   // вернуть обычную клавиатуру
        VoiceAccessibilityService.instance?.setStatusIcon(OverlayView.Icon.DOT)
        VoiceAccessibilityService.instance?.showStatus("Команды активны")
        restartListening()   // обратно на малую + грамматику (быстро)
        resetIdle(); refreshNotification()
    }

    /** Старт свободного захвата вопроса/текста для ИИ (после «Иван спроси…» / «Иван сформулируй…»). */
    fun startAiQuery(ask: Boolean) {
        if (!AiProfile.load(this).enabled) {
            VoiceAccessibilityService.instance?.showStatus("🧠 ИИ выключен — включите в настройках")
            speak("ИИ-помощник выключен. Включите его в настройках.")
            return
        }
        aiAsk = ask
        aiListening = true
        VoiceAccessibilityService.instance?.showStatus(stateText())
        Logger.log("AI", "Захват ${if (ask) "вопроса" else "текста"} для ИИ")
        restartListening()   // свободное распознавание
        resetIdle()
    }

    private fun handleAi(ask: Boolean, query: String) {
        aiThinking = true
        handler.removeCallbacks(idleRunnable)   // не засыпать, пока модель думает
        VoiceAccessibilityService.instance?.showStatus("🧠 Думаю…")
        Logger.log("AI", "Запрос (${if (ask) "спроси" else "сформулируй"}): '$query'")
        Thread {
            val answer = LocalAi.answer(this, ask, query)
            post {
                aiThinking = false
                Logger.log("AI", "Ответ: '$answer'")
                val ok = answer.isNotBlank() && !answer.startsWith("Модель") &&
                    !answer.startsWith("ИИ-помощник выключен") && !answer.startsWith("Не удалось") &&
                    !answer.startsWith("Не расслышал")
                // «Сформулируй» — вставляем готовый текст в активное поле ввода
                if (!ask && ok) insertComposed(answer)
                VoiceAccessibilityService.instance?.showStatus("🧠 $answer")
                if (AiProfile.load(this).voiceAnswers) speak(answer)
                restartListening()   // вернуться к командам
                resetIdle()
            }
        }.start()
    }

    /** Вставить сформулированный ИИ текст в активное поле — как диктовка:
     *  через невидимую клавиатуру (работает в браузере/заметках), иначе через спец-возможности. */
    private fun insertComposed(text: String) {
        val kb = GolosRukiKeyboardService.instance
        when {
            kb != null && kb.isActiveInput() -> {
                kb.commitDictation(text)
                Logger.log("AI", "Текст вставлен (клавиатура уже активна)")
            }
            imeReadyForAutoSwitch() -> {
                // Включаем нашу клавиатуру, ждём подключения к полю, вставляем, возвращаем обычную.
                switchToVoiceIme()
                handler.postDelayed({
                    val k = GolosRukiKeyboardService.instance
                    if (k != null && k.isActiveInput()) {
                        k.commitDictation(text); Logger.log("AI", "Текст вставлен (клавиатура)")
                    } else {
                        VoiceAccessibilityService.instance?.commitDictation(text)
                        Logger.log("AI", "Текст вставлен (спец-возможности, клавиатура не подключилась)")
                    }
                    switchBackIme()
                }, 700)
            }
            else -> {
                VoiceAccessibilityService.instance?.commitDictation(text)
                Logger.log("AI", "Текст вставлен (спец-возможности)")
            }
        }
    }

    private fun resetIdle() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, idleMs)
    }

    override fun onResult(hypothesis: String?) {
        val raw = hypothesis?.let { JSONObject(it).optString("text") } ?: return
        val text = normalize(raw.replace("[unk]", " "))
        if (text.isBlank()) return
        Logger.log("HEARD", "'$text' | state=$state media=$mediaControlMode dict=$dictation digits=$dictationDigits call=${inCall()}")

        // Анти-петля: пока проигрывается синтезатор, не распознаём (иначе слышим сами себя)
        if (isSpeaking) { Logger.log("REC", "Игнор (говорит синтезатор): '$text'"); return }

        // Пока модель «думает» — игнорируем всё, чтобы шум не сбивал статус и не запускал команды.
        if (aiThinking) { Logger.log("AI", "(размышление) игнор: '$text'"); return }

        // Свободный захват вопроса/текста для ИИ — приоритетно (выше медиа/экрана),
        // чтобы вопрос всегда ловился, даже когда играет видео или активен медиа-режим.
        if (aiListening) {
            if (text.isBlank()) { resetIdle(); return }
            if (text.contains("стоп") || text.contains("отмена") || text.contains(wakeWord)) {
                aiListening = false
                VoiceAccessibilityService.instance?.showStatus(stateText())
                restartListening(); return
            }
            aiListening = false
            handleAi(aiAsk, text.trim())
            return
        }

        // ИДЁТ РАЗГОВОР (сотовый звонок или звонок в мессенджере): голос собеседника не должен
        // дёргать команды. Требуем слово активации; медиа-режим выключаем.
        if (inCall()) {
            clearMediaMode()
            if (!text.contains(wakeWord)) { Logger.log("REC", "Игнор (идёт звонок): '$text'"); return }
            val rest = stripWake(text).trim()
            resetIdle()
            if (rest.isBlank()) VoiceAccessibilityService.instance?.showStatus("🎙 Слушаю (звонок)")
            else handleCommand(rest)
            return
        }

        // ЭКРАН ВЫКЛЮЧЕН/ЗАБЛОКИРОВАН. Экран НЕ зажигаем. Разрешено:
        //  • «<слово> привет» — единственное, что включает/разблокирует экран;
        //  • «<слово> пауза/играй/следующее/предыдущее/громче/тише» — быстрое управление звуком;
        //  • «<слово> <код медиа>» — открыть окно медиа на mediaWindowMs: дальше те же аудио-команды
        //    можно говорить БЕЗ слова активации (экран по-прежнему не загорается).
        if (!isScreenOn()) {
            val hasWake = text.contains(wakeWord)
            // активное окно медиа — принимаем аудио-команды без слова активации
            if (screenOffMedia) {
                val c = CommandParser.parseAudioOnly(text)
                if (c != null) {
                    Logger.log("MEDIA", "Экран выкл, окно медиа: ${c.label()}")
                    armScreenOffMedia()
                    post { VoiceAccessibilityService.instance?.execute(c) }
                    return
                }
            }
            if (hasWake && text.contains("привет")) {
                state = State.AWAKE
                if (paused) setPaused(false)
                resetIdle()
                Logger.log("REC", "Экран выкл → пробуждение по «$wakeWord привет»")
                if (keepScreen) VoiceAccessibilityService.instance?.keepScreenOn(true)
                post { VoiceAccessibilityService.instance?.execute(Command.Unlock) }
                return
            }
            if (hasWake) {
                val rest = stripWake(text).trim()
                if (mediaCode.isNotBlank() && rest == mediaCode) {
                    armScreenOffMedia()
                    Logger.log("MEDIA", "Экран выкл → окно медиа открыто (${mediaWindowMs / 1000}с)")
                    return
                }
                val c = CommandParser.parseAudioOnly(rest)
                if (c != null) {
                    Logger.log("MEDIA", "Экран выкл, быстрая команда: ${c.label()}")
                    post { VoiceAccessibilityService.instance?.execute(c) }
                    return
                }
            }
            Logger.log("REC", "Игнор (экран выключен): '$text'")
            return
        }

        // Во время видео/музыки ИЛИ пока активен медиа-режим (держится даже на паузе)
        if (ignoreMedia && (isMediaPlaying() || mediaControlMode)) {
            val hasWake = text.contains(wakeWord)
            val rest = if (hasWake) stripWake(text).trim() else text.trim()
            // переключение МЕДИА-РЕЖИМА: «<слово> медиа» ИЛИ просто «медиа»
            // (распознаватель часто дробит «иван медиа» на две части — ловим обе)
            if (mediaCode.isNotBlank() && rest == mediaCode) {
                toggleMediaMode()
                return
            }
            if (hasWake) {
                Logger.log("MEDIA", "Активация при медиа: '$rest'")
                if (paused) setPaused(false)
                state = State.AWAKE
                resetIdle()
                if (mediaControlMode) VoiceAccessibilityService.instance?.setStatusIcon(OverlayView.Icon.MEDIA)
                if (rest.isBlank()) VoiceAccessibilityService.instance?.showStatus(stateText())
                else handleCommand(rest)
            } else if (mediaControlMode) {
                val c = CommandParser.parseMedia(text)
                if (c != null) {
                    Logger.log("MEDIA", "Медиа-режим: ${c.label()}")
                    resetIdle(); armMediaMode()
                    post { VoiceAccessibilityService.instance?.execute(c) }
                } else {
                    Logger.log("REC", "Медиа-режим: '$text' — не команда, игнор")
                }
            } else {
                Logger.log("REC", "Игнор (играет медиа): '$text'")
            }
            return
        }

        Logger.log("REC", "Распознано: '$text' (state=$state, paused=$paused, dict=$dictation)")

        if (dictation) {
            // Гарантированный выход: слово активации, «стоп», «отмена», «готово», «конец»
            if (text.contains(wakeWord) || text.contains("стоп") || text.contains("отмена") ||
                text.contains("готово") || text.contains("конец")) {
                exitDictation(); return
            }
            // Переключение режима БЕЗ выхода и БЕЗ стирания набранного:
            //  «цифры/цифрами» → ввод цифр; «буквы/текст/словами» → снова текст.
            if (!dictationDigits && (text.contains("цифр"))) {
                dictationDigits = true
                VoiceAccessibilityService.instance?.setStatusIcon(OverlayView.Icon.DIGITS)
                VoiceAccessibilityService.instance?.showStatus(stateText())
                resetIdle(); return
            }
            if (dictationDigits && (text.contains("букв") || text.contains("текст") || text.contains("словам") || text.contains("напиш") || text.contains("диктов"))) {
                dictationDigits = false
                VoiceAccessibilityService.instance?.setStatusIcon(OverlayView.Icon.PEN)
                VoiceAccessibilityService.instance?.showStatus(stateText())
                resetIdle(); return
            }
            resetIdle()
            if (dictationDigits) {
                val chunk = NumberWords.toDigits(text)
                if (chunk.isNotBlank()) {
                    if (dictBuffer.isNotEmpty() && dictBuffer.last() != ' ' && dictBuffer.last() != '\n') dictBuffer.append(" ")
                    dictBuffer.append(chunk); flushDictation()
                }
            } else {
                applyDictation(text)
            }
            return
        }

        val isWake = text.contains(wakeWord)
        val isResume = text.contains("слушай") || text.contains("продолжи")

        if (isWake || isResume) {
            state = State.AWAKE
            if (paused) { setPaused(false); Logger.log("REC", "Пауза снята") }
            resetIdle()
            if (keepScreen) VoiceAccessibilityService.instance?.keepScreenOn(true)
            val rest = if (isWake) stripWake(text) else stripResume(text)
            if (vibrateOnWake && rest.isBlank()) vibrateTick()
            refreshNotification()
            if (rest.isBlank()) {
                VoiceAccessibilityService.instance?.showStatus(stateText())
                return
            }
            handleCommand(rest)
            return
        }

        if (state == State.ASLEEP) return
        resetIdle()
        if (paused) { VoiceAccessibilityService.instance?.showStatus(stateText()); return }
        handleCommand(text)
    }

    private var lastCmdText: String? = null

    /** Приводит распознанный текст к чистому виду: нижний регистр, без лишних пробелов и дублей слов подряд. */
    private fun normalize(s: String): String {
        val tokens = s.lowercase().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = ArrayList<String>(tokens.size)
        for (w in tokens) if (out.isEmpty() || out.last() != w) out.add(w)  // «позвони позвони» -> «позвони»
        return out.joinToString(" ")
    }

    private fun handleCommand(text: String) {
        // Ожидание подтверждения звонка
        pendingCall?.let { pc ->
            when {
                text.contains("да") || text.contains("звони") || text.contains("подтвержда") ||
                    text.contains("ага") || text.contains("давай") || text.contains("вызывай") -> {
                    pendingCall = null
                    Logger.log("CMD", "Звонок подтверждён: ${pc.first}")
                    post { VoiceAccessibilityService.instance?.execute(Command.CallContact(pc.first, pc.second)) }
                    return
                }
                text.contains("нет") || text.contains("отмена") || text.contains("стоп") || text.contains("не надо") -> {
                    pendingCall = null
                    speak("Отменено")
                    VoiceAccessibilityService.instance?.showStatus("Звонок отменён")
                    return
                }
                else -> { pendingCall = null }  // иначе сбрасываем и обрабатываем как обычную команду
            }
        }
        if (text.contains("повтори")) {
            lastCmdText?.let { last ->
                Logger.log("CMD", "Повтор: $last")
                val cmd = CommandParser.parse(last, personal)
                post { VoiceAccessibilityService.instance?.execute(cmd) }
            }
            return
        }
        lastCmdText = text
        val cmd = CommandParser.parse(text, personal)
        Logger.log("CMD", "Команда: ${cmd.label()}")

        // Подтверждение звонка (если включено в настройках)
        if (confirmCalls && cmd is Command.CallContact) {
            pendingCall = cmd.name to cmd.number
            VoiceAccessibilityService.instance?.showStatus("Звоню ${cmd.name}? Скажите «да» или «нет»")
            speak("Звоню ${cmd.name}? Скажите да или нет")
            handler.postDelayed({ if (pendingCall != null) { pendingCall = null; Logger.log("CMD", "Подтверждение истекло") } }, 15000)
            return
        }

        if (cmd is Command.Unknown) {
            when {
                text.contains("позвони") || text.contains("набери") -> {
                    val names = personal.contacts.keys.joinToString(", ").ifBlank { "нет в настройках" }
                    VoiceAccessibilityService.instance?.showStatus("Скажите имя контакта: $names")
                }
                (text.contains("сос") || text.contains("спасите")) && personal.sosPin.isNotBlank() ->
                    VoiceAccessibilityService.instance?.showStatus("Скажите: сос ${personal.sosPin}")
            }
        }
        post { VoiceAccessibilityService.instance?.execute(cmd) }
    }

    private fun stripWake(t: String) = t.replace(wakeWord, "").trim()
    private fun stripResume(t: String) = t.replace("слушай", "").replace("продолжи", "").trim()
    private fun post(action: () -> Unit) = handler.post(action)

    override fun onFinalResult(hypothesis: String?) { onResult(hypothesis) }

    override fun onPartialResult(hypothesis: String?) {
        if (!dictation) return
        val p = hypothesis?.let { JSONObject(it).optString("partial") } ?: return
        if (p.isNotBlank()) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification("✍️ $p"))
        }
    }

    override fun onError(e: Exception?) {
        Logger.log("REC", "Ошибка распознавания: ${e?.message} — перезапуск через 1с")
        handler.postDelayed({ if (model != null) restartListening() }, 1000)
    }
    override fun onTimeout() {
        if (model != null) restartListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(idleRunnable)
        listeningStop()
        model?.close()
        disableBtMic()
        runCatching { tts?.stop(); tts?.shutdown() }
        instance = null
    }
}
