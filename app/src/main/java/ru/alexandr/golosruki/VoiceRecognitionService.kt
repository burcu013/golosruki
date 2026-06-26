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
    @Volatile private var callActive = false                  // идёт мобильный звонок (NS на это время выключаем)
    private var phoneListener: android.telephony.PhoneStateListener? = null
    // v8.14/8.15/8.16: приглушение музыки, чтобы микрофон слышал команды/«иван ответь» поверх музыки
    // из колонок (в авто AEC не вытягивает — динамики далеко от мика). v8.15: через АУДИОФОКУС (MAY_DUCK),
    // а не setStreamVolume — иначе абсолютная громкость A2DP дрейфовала и терялась при рестарте сервиса.
    // v8.16: отпускаем фокус с ДЕБАУНСОМ — частые «отпустил→снова приглушил» оставляли плеер залипшим
    // тихим (не успевал доехать до полной громкости). Теперь короткие переходы фокус не дёргают.
    @Volatile private var musicDucked = false
    private var duckFocusRequest: android.media.AudioFocusRequest? = null
    // слушатель обязателен на части прошивок для корректного дакинга; мы ничего не воспроизводим — no-op
    private val duckFocusListener = android.media.AudioManager.OnAudioFocusChangeListener { }
    private val releaseDuckRunnable = Runnable { releaseDuckNow() }
    private lateinit var personal: PersonalConfig
    private val handler = Handler(Looper.getMainLooper())

    private var wakeWord = "иван"
    private var idleMs = 30_000L
    @Volatile private var deepSleep = false                 // глубокий сон — выход только точной фразой
    private var deepSleepEnabled = false
    private var deepSleepPhrase = "иван полный подъём"
    private var ignoreMedia = true
    private var vibrateOnWake = true
    private var keepScreen = true
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }

    // --- Bluetooth-микрофон (SCO) с откатом на встроенный ---
    @Volatile private var btMicWanted = false
    @Volatile private var btScoOn = false
    private var btReceiver: android.content.BroadcastReceiver? = null
    private fun enableBtMic() {
        // Намеренно НИЧЕГО не захватываем: больше не поднимаем Bluetooth SCO и не включаем режим связи.
        // Запись идёт с обычного микрофона (как у всех приложений) — музыка, магнитола, звонки работают сами.
        // Гарнитура/авто-микрофон используются системой автоматически, без «телефонного» канала и без переключателей.
        btMicWanted = false; btScoOn = false; btSuspended = false; routingGuardActive = false
        runCatching { audioManager.mode = android.media.AudioManager.MODE_NORMAL }
    }

    /** Новый API (Android 12+): маршрутизация захвата звука на BT-устройство связи. */
    private fun enableBtMicModern(): Boolean {
        return try {
            val devs = audioManager.availableCommunicationDevices
            Logger.log("MIC", "Устройства связи: " + (devs.joinToString { "${audioDevTypeName(it.type)}:${it.productName}" }.ifBlank { "нет" }))
            val bt = devs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (bt == null) {
                Logger.log("MIC", "BT-устройство связи (SCO) не найдено — пробую старый способ")
                return false
            }
            // Режим связи помогает маршрутизации захвата на гарнитуру.
            runCatching { audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION }
            val ok = audioManager.setCommunicationDevice(bt)
            if (ok) {
                btScoOn = true
                Logger.log("MIC", "Bluetooth-микрофон активен (новый API): ${bt.productName}")
                VoiceAccessibilityService.instance?.showStatus("🎧 Bluetooth-микрофон активен")
                // даём маршрутизации устояться, затем пересоздаём распознаватель на гарнитуре
                if (model != null) handler.postDelayed({ if (btMicWanted) restartListening() }, 400)
            } else {
                Logger.log("MIC", "setCommunicationDevice вернул false — пробую старый способ")
                runCatching { audioManager.mode = android.media.AudioManager.MODE_NORMAL }
            }
            ok
        } catch (e: Exception) {
            Logger.log("MIC", "Новый BT API не сработал: ${e.message}"); false
        }
    }

    /** Читабельное имя типа аудиоустройства — для диагностики маршрутизации микрофона. */
    private fun audioDevTypeName(t: Int): String = when (t) {
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "ВСТРОЕННЫЙ_МИК"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "ПРОВОДНАЯ_ГАРН"
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        android.media.AudioDeviceInfo.TYPE_TELEPHONY -> "ТЕЛЕФОНИЯ"
        else -> "тип$t"
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
            // КЛЮЧЕВОЕ: режим связи — без него многие телефоны не поднимают SCO и микрофон остаётся встроенным.
            runCatching { audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION }
            startScoWithRetry(0)
        }
    }

    /** Поднять BT SCO с несколькими попытками (некоторые гарнитуры подключаются не с первого раза). */
    private fun startScoWithRetry(attempt: Int) {
        if (!btMicWanted || btScoOn) return
        if (attempt >= 4) {
            Logger.log("MIC", "BT SCO не поднялся после 4 попыток — встроенный микрофон")
            VoiceAccessibilityService.instance?.showStatus("Bluetooth-микрофон не подключился — встроенный")
            runCatching { audioManager.mode = android.media.AudioManager.MODE_NORMAL }
            return
        }
        runCatching { audioManager.stopBluetoothSco() }
        runCatching {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }
        Logger.log("MIC", "BT SCO попытка ${attempt + 1}…")
        handler.postDelayed({ if (btMicWanted && !btScoOn) startScoWithRetry(attempt + 1) }, 2500)
    }
    private fun disableBtMic() {
        btMicWanted = false; btScoOn = false; btSuspended = false
        routingGuardActive = false; handler.removeCallbacks(routingGuard)
        if (Build.VERSION.SDK_INT >= 31) runCatching { audioManager.clearCommunicationDevice() }
        runCatching { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco() }
        runCatching { audioManager.mode = android.media.AudioManager.MODE_NORMAL }
        runCatching { btReceiver?.let { unregisterReceiver(it) } }; btReceiver = null
    }

    @Volatile private var btSuspended = false   // BT-микрофон временно отпущен (играет звук или идёт звонок)
    @Volatile private var routingGuardActive = false   // запущен ли таймер-сторож маршрутизации

    /** Отпустить SCO и режим связи, НО сохранить намерение использовать BT (btMicWanted=true),
     *  чтобы вернуть гарнитуру, когда звук/звонок закончатся. */
    private fun releaseScoKeepWanted() {
        btScoOn = false
        if (Build.VERSION.SDK_INT >= 31) runCatching { audioManager.clearCommunicationDevice() }
        runCatching { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco() }
        runCatching { audioManager.mode = android.media.AudioManager.MODE_NORMAL }
    }

    /**
     * Сторож аудио-маршрутизации. Главное правило: режим связи и SCO держим ТОЛЬКО когда мы
     * просто слушаем и ничего не воспроизводится. Как только играет звук (музыка, видео, наш TTS)
     * или идёт звонок — отпускаем SCO, чтобы звук шёл штатно (A2DP/динамик/CarPlay), а не «как в звонке».
     * Когда BT-микрофон не нужен вовсе — гарантируем обычный режим (чиним «утечку» MODE_IN_COMMUNICATION).
     * Возвращает true, если состояние изменилось и нужно пересоздать распознаватель.
     */
    private fun ensureAudioRouting(): Boolean {
        if (!btMicWanted) {
            // BT не нужен — не оставляем телефон в режиме связи (иначе глохнет медиа и «фантомный звонок»)
            if (nsService == null && audioModeIs(android.media.AudioManager.MODE_IN_COMMUNICATION)) {
                runCatching { audioManager.mode = android.media.AudioManager.MODE_NORMAL }
                Logger.log("MIC", "Сброс лишнего режима связи → обычный")
            }
            return false
        }
        // Во время диктовки НЕ трогаем маршрутизацию: переключение SCO пересоздаёт распознаватель
        // прямо посреди набора и рвёт текст (слипание/повторы). Держим микрофон стабильным.
        if (dictation) return false
        val mustRelease = isMediaPlaying() || inCall()
        if (mustRelease && !btSuspended) {
            btSuspended = true
            releaseScoKeepWanted()
            Logger.log("MIC", "BT-микрофон отпущен (играет звук/звонок) — аудио идёт штатно")
            return true
        }
        if (!mustRelease && btSuspended) {
            btSuspended = false
            Logger.log("MIC", "Звук/звонок закончились — возвращаю BT-микрофон")
            enableBtMic()   // вновь поднимет SCO и пересоздаст распознаватель
            return false    // enableBtMic сам перезапустит слушание
        }
        return false
    }

    private fun audioModeIs(m: Int): Boolean = try { audioManager.mode == m } catch (e: Exception) { false }

    /** Периодическая проверка маршрутизации (каждые 3 c): ловит начало/конец музыки и звонка. */
    private val routingGuard = object : Runnable {
        override fun run() {
            if (!routingGuardActive) return
            if (ensureAudioRouting()) restartListening()
            handler.postDelayed(this, 3000)
        }
    }

    /** Играет ли сейчас звук. isMusicActive = true только при реальном воспроизведении. */
    private fun isMediaPlaying(): Boolean = try { audioManager.isMusicActive } catch (e: Exception) { false }
    /** Активный разговор: сотовый (MODE_IN_CALL) или мессенджер (MODE_IN_COMMUNICATION), но не наш BT-SCO. */
    private fun audioModeName(): String = try {
        when (audioManager.mode) {
            android.media.AudioManager.MODE_NORMAL -> "NORMAL"
            android.media.AudioManager.MODE_RINGTONE -> "RINGTONE"
            android.media.AudioManager.MODE_IN_CALL -> "IN_CALL"
            android.media.AudioManager.MODE_IN_COMMUNICATION -> "IN_COMM"
            else -> "m${audioManager.mode}"
        }
    } catch (e: Exception) { "?" }

    private fun telStateName(): String = try {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        @Suppress("DEPRECATION")
        when (tm.callState) {
            android.telephony.TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            android.telephony.TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "?"
        }
    } catch (e: SecurityException) { "нет_разрешения" } catch (e: Exception) { "?" }

    private fun inCall(): Boolean {
        // 1) Телефония (сотовый звонок) — надёжно, не зависит от нашего NS-захвата мика.
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            @Suppress("DEPRECATION")
            val st = tm.callState
            if (st != android.telephony.TelephonyManager.CALL_STATE_IDLE) return true
        } catch (_: Exception) {}
        // 2) Аудио-режим (VoIP), но не путать с нашим собственным NS-захватом.
        return try {
            val m = audioManager.mode
            m == android.media.AudioManager.MODE_IN_CALL ||
                (m == android.media.AudioManager.MODE_IN_COMMUNICATION && !btScoOn && !btMicWanted && nsService == null)
        } catch (e: Exception) { false }
    }
    fun wakeWordPublic(): String = wakeWord

    /** Экран включён (интерактивен)? */
    private fun isScreenOn(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    } catch (e: Exception) { true }

    private enum class State { ASLEEP, AWAKE }
    @Volatile private var state = State.ASLEEP
    @Volatile private var dictation = false
    @Volatile private var dictationDigits = false
    @Volatile private var recordingVoice = false   // идёт запись голосового: глушим всё, кроме «Иван отправь/отмена»
    @Volatile private var heardWakeInUtterance = false  // в текущей распознанной фразе было слово активации
    private var lastResultText = ""                     // антидубль: последний обработанный финал…
    private var lastResultAt = 0L                       // …и когда (Vosk повторяет один и тот же результат подряд)

    private val recordingOff = Runnable { recordingVoice = false; Logger.log("REC", "Запись голосового: авто-сброс по таймауту") }
    /** Включает/выключает режим записи голосового. В нём команды не парсятся — нужен явный «Иван отправь/отмена». */
    fun setRecordingVoice(v: Boolean) {
        recordingVoice = v
        handler.removeCallbacks(recordingOff)
        if (v) handler.postDelayed(recordingOff, 120_000)   // страховка от залипания
        // v8.13: recordingVoice не входит в wantHeadsetMic, поэтому refreshMicForState под запись только
        // ГАРАНТИРУЕТ NORMAL-режим (если SCO остался от предыдущего захвата — опустит), но НЕ поднимает
        // comm-режим. Telegram пишет со встроенного в NORMAL — иначе запись срывается, см. wantHeadsetMic.
        refreshMicForState()
        VoiceAccessibilityService.instance?.showStatus(stateText())
    }
    @Volatile var aiListening = false      // идёт свободный захват вопроса/текста для ИИ
    @Volatile private var aiDialog = false // идёт режим диалога с ИИ (до «хватит»)
    @Volatile private var lastAiQuestion = "" // последний вопрос к ИИ (для «подробнее»)
    @Volatile private var answerHeld = false  // на экране ответ ИИ/брифинг/задачи — держим до конца озвучки + дочитывания
    @Volatile private var answerStartedAt = 0L // когда начался показ ответа (для выбора: после короткого — в команды, после долгого — в сон)
    @Volatile private var aiAsk = true     // true — вопрос, false — сформулировать текст
    @Volatile private var aiThinking = false  // идёт генерация ответа (модель «думает»)
    @Volatile private var planning = false    // свободный захват — это план для секретаря (не вопрос)
    @Volatile private var querying = false     // свободный захват — это вопрос к памяти секретаря
    private val dictBuffer = StringBuilder()
    private var lastDictApplied = ""           // v8.8 анти-дубль диктовки: последний применённый финал
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
    /** v8.7 АНТИ-ЭХО (kill-switch). Чинит самотриггер: гейт «идёт озвучка» снимался раньше, чем Иван
     *  реально договорил (страховочный таймер срабатывал на длинной сводке), и хвост его же речи из
     *  колонок исполнялся как команда — отсюда фантомные «Погода»/«Календарь»/«SOS» в машине.
     *  Если новая логика начнёт мешать — поставь false и пересобери: вернётся прежнее поведение 1:1. */
    private val antiEcho = true
    private val speechTailGraceMs = if (antiEcho) 1300L else 900L   // после окончания речи ещё глушим вход: хвост звука/эхо в динамике
    @Volatile private var afterSpeak: (() -> Unit)? = null   // одноразовое действие после окончания речи
    private var ttsEnabled = true
    private var ttsPitch = 1.0f
    private var ttsRate = 1.0f
    private var ttsVoiceName = ""
    private var confirmCalls = false
    private var pendingCall: Pair<String, String>? = null   // имя -> номер (ожидает «да»)
    private var pendingContactChoice: List<Contacts.C>? = null   // несколько совпадений — ждём номер выбора
    private var pendingPlan: PlanResult? = null   // разобранный план — ждёт подтверждения «да/нет»
    private var planKind = "event"                // тип создаваемой записи: event | task | reminder
    private var pendingTaskDone: List<Task>? = null   // выбор задачи для завершения (по номеру)
    private var pendingReminderCancel: List<Reminder>? = null   // выбор напоминания для отмены (по номеру)
    private var pendingEventCancel: List<CalendarReader.EventItem>? = null   // выбор события для отмены (по номеру)
    private var pendingGestureCalib = false                  // ожидает подтверждения записи жеста

    private val idleRunnable = Runnable {
        if (aiListening) { aiListening = false; restartListening() }   // заброшенный ИИ-запрос — сброс
        state = State.ASLEEP
        refreshMicForState()   // ушли в сон — отпустить микрофон гарнитуры (если был взят)
        if (!answerHeld) {   // во время долгого ответа не подменяем текст на «сон»
            VoiceAccessibilityService.instance?.showStatus(stateText())
            VoiceAccessibilityService.instance?.keepScreenOn(false)
        }
        refreshNotification()
    }

    /** Снять удержание ответа на экране (после озвучки + времени на дочитывание). */
    private val clearAnswerHold = Runnable {
        answerHeld = false
        if (!hasPendingHold()) {
            VoiceAccessibilityService.instance?.releaseStatusHold()
            val engagedMs = System.currentTimeMillis() - answerStartedAt
            if (engagedMs < idleMs) {
                // Короткий ответ (уложился в окно простоя) — возвращаем обычный командный режим (бодрствование).
                state = State.AWAKE
                VoiceAccessibilityService.instance?.showStatus(stateText())
                resetIdle()
            } else {
                // Длинный ответ — управление возвращаем в сон (ждём «Иван»). «Подробнее» — фразой «<слово> подробнее».
                goToSleep()
            }
        }
    }

    /** Удерживать ответ на экране на время озвучки + дочитывания (длиннее текст — дольше). */
    private fun holdAnswer(text: String) {
        answerHeld = true
        answerStartedAt = System.currentTimeMillis()
        handler.removeCallbacks(clearAnswerHold)
        handler.postDelayed(clearAnswerHold, (text.length * 70L + 5000L).coerceIn(8000L, 90000L))
    }

    /** Озвучить длинный ответ и держать его текст на экране до конца озвучки + дочитывания. */
    private fun speakAndHold(spoken: String, icon: String = "🧠") {
        holdAnswer(spoken)
        VoiceAccessibilityService.instance?.showStatusHold("$icon " + formatForScreen(spoken), 26, 0L)
        speak(spoken)
    }

    companion object {
        const val CHANNEL_ID = "golosruki_voice"
        const val NOTIF_ID = 1
        const val ACTION_RESET = "ru.alexandr.golosruki.RESET"
        const val ACTION_RELOAD = "ru.alexandr.golosruki.RELOAD"   // перезагрузить модель (после смены пакета)
        const val ACTION_APPLY = "ru.alexandr.golosruki.APPLY"     // применить настройки на лету (без пересоздания службы)
        const val ACTION_DEEP_SLEEP = "ru.alexandr.golosruki.DEEP_SLEEP"   // войти в глубокий сон (кнопка/уведомление)
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
            ACTION_DEEP_SLEEP -> enterDeepSleep()
            ReminderScheduler.ACTION_REMINDER -> {
                intent.getStringExtra("text")?.let { speakReminderSoon(it, 0) }
                // повторяющееся напоминание — перевзвести на следующий раз
                val rid = intent.getStringExtra("rid")
                if (rid != null) {
                    val m = Secretary.mem(this)
                    val rem = m.reminders().firstOrNull { it.id == rid }
                    if (rem != null && rem.repeatMin > 0) {
                        val next = ReminderScheduler.nextOccurrence(System.currentTimeMillis() + 1000, rem.repeatMin)
                        m.updateReminderTime(rid, next)
                        ReminderScheduler.scheduleReminderItem(this, rem.copy(atMillis = next))
                    }
                }
            }
            ReminderScheduler.ACTION_BRIEFING -> {
                handler.postDelayed({ speakBriefing() }, 1500)   // дать TTS/службе подняться, если старт «холодный»
                ReminderScheduler.rearmBriefing(this)            // перевзвести на следующий день
            }
            ReminderScheduler.ACTION_REARM -> ReminderScheduler.rearmAll(this)
        }
        return START_STICKY
    }

    /** Озвучить напоминание; если TTS ещё не готов (холодный старт по будильнику) — подождать и повторить. */
    private fun speakReminderSoon(text: String, attempt: Int) {
        if (ttsReady && ttsEnabled) {
            VoiceAccessibilityService.instance?.showStatus("🔔 $text")
            speak(text)
            Logger.log("SEC", "Напоминание озвучено: $text")
        } else if (attempt < 12) {
            handler.postDelayed({ speakReminderSoon(text, attempt + 1) }, 600)
        } else {
            VoiceAccessibilityService.instance?.showStatus("🔔 $text")
            Logger.log("SEC", "Напоминание без озвучки (TTS недоступен): $text")
        }
    }

    /** Применить настройки на лету: перечитать конфиг и перезапустить распознавание.
     *  Надёжнее, чем stop/start службы (та может не пересоздаться и не перечитать поля). */
    fun applySettings() {
        personal = PersonalConfig.load(this)
        wakeWord = SettingsStore.getWake(this)
        idleMs = SettingsStore.getIdle(this) * 1000L
        ignoreMedia = SettingsStore.getIgnoreMedia(this)
        deepSleepEnabled = SettingsStore.getDeepSleepEnabled(this)
        deepSleepPhrase = SettingsStore.getDeepSleepPhrase(this)
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
        // Синхронизировать BT-микрофон с настройкой: выключение должно срабатывать сразу
        // (и возвращать обычный режим аудио, иначе глохнет медиа / «фантомный звонок»).
        val wantBt = SettingsStore.getBtMic(this)
        if (wantBt && !btMicWanted) enableBtMic()
        else if (!wantBt && btMicWanted) disableBtMic()
        Logger.log("CFG", "Настройки применены: слово='$wakeWord', сон=${idleMs/1000}с, медиа-игнор=$ignoreMedia, шумоподавление=$useNoiseSuppress, BT-мик=$wantBt")
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
        if (AudioDiag.ENABLED) handler.postDelayed(audioDiagPoll, 1000L)   // v8.11 диагностика аудио
        // Удаляем «обрезки» недокачанной модели, чтобы не пухли данные.
        runCatching {
            java.io.File(filesDir, "llm").listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        }
        paused = false
        personal = PersonalConfig.load(this)
        wakeWord = SettingsStore.getWake(this)
        idleMs = SettingsStore.getIdle(this) * 1000L
        ignoreMedia = SettingsStore.getIgnoreMedia(this)
        deepSleepEnabled = SettingsStore.getDeepSleepEnabled(this)
        deepSleepPhrase = SettingsStore.getDeepSleepPhrase(this)
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
        registerCallListener()
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
                override fun onStart(id: String?) { isSpeaking = true; handler.removeCallbacks(resumeAfterSpeak) }
                override fun onDone(id: String?) {
                    // НЕ сбрасываем isSpeaking мгновенно: даём «хвосту» речи/эху в динамике утихнуть,
                    // иначе распознаватель ловит собственные слова на стыке (самотриггер «сводка горит» и т.п.).
                    val cb = afterSpeak
                    if (cb != null) {
                        afterSpeak = null
                        handler.postDelayed({ fireAfterSpeak(cb) }, speechTailGraceMs)
                    } else {
                        scheduleResume(speechTailGraceMs)
                        post { if (!hasPendingHold()) VoiceAccessibilityService.instance?.releaseStatusHold() }
                    }
                }
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

    private val resumeAfterSpeak: Runnable = object : Runnable {
        override fun run() {
            // v8.7 анти-эхо: если синтезатор ещё реально говорит (длинная сводка/медленная озвучка),
            // НЕ снимаем гейт — переносим попытку. Иначе свежая речь Ивана из колонок пролезает как команда.
            if (antiEcho && runCatching { tts?.isSpeaking == true }.getOrDefault(false)) {
                handler.postDelayed(this, 400L)
                return
            }
            isSpeaking = false
            listeningSetPause(false)
            Logger.log("REC", "Распознавание возобновлено после речи")
        }
    }
    private fun scheduleResume(delay: Long) {
        handler.removeCallbacks(resumeAfterSpeak)
        handler.postDelayed(resumeAfterSpeak, delay)
    }
    /** v8.12: выполнить отложенное действие (afterSpeak) ТОЛЬКО когда синтезатор реально замолчал —
     *  тот же анти-эхо-гейт, что в resumeAfterSpeak. Чинит самопетлю «поговорим»: ре-арм следующего хода
     *  происходил, пока хвост ответа ещё звучал в динамике, и Vosk ловил собственную речь как новый вопрос. */
    private fun fireAfterSpeak(cb: () -> Unit) {
        if (antiEcho && runCatching { tts?.isSpeaking == true }.getOrDefault(false)) {
            handler.postDelayed({ fireAfterSpeak(cb) }, 400L)
            return
        }
        isSpeaking = false
        cb()
    }
    // v8.11 ДИАГНОСТИКА: периодический опрос аудио-состояния (только логи). Самоперепланируется.
    private val audioDiagPoll: Runnable = object : Runnable {
        override fun run() {
            AudioDiag.poll(audioManager)
            handler.postDelayed(this, 300L)
        }
    }

    /** Активно ли окно, ожидающее выбора/подтверждения голосом (его нельзя гасить по окончании речи). */
    private fun hasPendingHold(): Boolean =
        pendingContactChoice != null || pendingPlan != null || pendingTaskDone != null || pendingReminderCancel != null || pendingEventCancel != null || answerHeld

    /** Прервать текущую озвучку голосом и вернуться к слушанию. */
    private fun stopSpeaking() {
        runCatching { tts?.stop() }
        handler.removeCallbacks(resumeAfterSpeak)
        isSpeaking = false
        answerHeld = false; handler.removeCallbacks(clearAnswerHold)   // «стоп/хватит» — убрать и удержание ответа
        post {
            if (!hasPendingHold()) {
                VoiceAccessibilityService.instance?.releaseStatusHold()
                VoiceAccessibilityService.instance?.showStatus(stateText())
            }
        }
        resetIdle()
    }

    fun speak(text: String) {
        if (!ttsEnabled || !ttsReady) return
        isSpeaking = true
        runCatching {
            tts?.speak(cleanForSpeech(text), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "golosruki")
        }
        // страховка, если движок не сообщит об окончании
        scheduleResume(1800L + text.length * 90L + speechTailGraceMs)
    }

    /** Убирает markdown, чтобы синтезатор не читал «звёздочка/решётка»; заголовки/жирный → пауза. */
    /** Готовит текст к озвучке: убирает разметку и расставляет паузы/интонацию (для человекоподобной речи). */
    private fun cleanForSpeech(s: String): String {
        var t = s
        // разметка off
        t = t.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1. ")            // **жирный/заголовок** → текст + пауза
        t = t.replace(Regex("__(.+?)__"), "$1. ")
        t = t.replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1")           // [текст](ссылка) → текст
        t = t.replace(Regex("[*_`#>]"), "")
        // Нумерованные пункты ПРОИЗНОСИМ, но только если это реально пункт в НАЧАЛЕ строки («1. …» → «Пункт 1: …»).
        // Числа внутри предложения («стоит 34 рубля», «в 2026») НЕ трогаем, чтобы не читать их как «пункт».
        t = t.replace(Regex("(?m)^\\s*(\\d{1,2})[.)]\\s+"), "Пункт $1: ")
        // маркеры списка → короткая пауза
        t = t.replace(Regex("(?m)^\\s*[-–—•·]\\s+"), "")
        t = t.replace(Regex("(?<=\\S)\\s+[-–—•·]\\s+"), ", ")
        // переводы строк → паузы (абзац — длиннее, строка — короче)
        t = t.replace(Regex("\\n{2,}"), ". ")
        t = t.replace(Regex("\\n"), ", ")
        // нормализация пауз
        t = t.replace(Regex("\\.{3,}"), "…")                        // многоточие = естественная пауза
        t = t.replace(Regex("\\s+([.!?,;:…])"), "$1")               // пробел перед знаком убрать
        t = t.replace(Regex("([.!?,;:])(?=[.!?,;:])"), "")          // дубли пунктуации («. ,» → «,»? оставляем первый)
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        return t.trim()
    }

    /** Готовит длинный ответ к ПОКАЗУ на экране: убирает разметку и расставляет переносы/отступы для читабельности. */
    private fun formatForScreen(s: String): String {
        var t = s.trim()
        t = t.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        t = t.replace(Regex("__(.+?)__"), "$1")
        t = t.replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1")
        t = t.replace(Regex("[*_`#>]"), "")
        // нумерованные пункты — каждый с новой строки
        t = t.replace(Regex("(?<=\\S) *(\\d{1,2})[.)]\\s+"), "\n$1. ")
        // маркеры списка — с новой строки и единым значком
        t = t.replace(Regex("(?<=\\S) *[-–—•·]\\s+"), "\n• ")
        t = t.replace(Regex("(?m)^\\s*[-–—•·]\\s+"), "• ")
        // лишние пробелы и пустые строки
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        t = t.replace(Regex("(?m)^[ \\t]+"), "")
        return t.trim()
    }

    /** Сказать фразу и затем (после окончания речи) выполнить действие. Используется для голосовых подсказок перед записью. */
    fun speakThen(text: String, action: () -> Unit) {
        if (!ttsEnabled || !ttsReady) { action(); return }
        afterSpeak = action
        isSpeaking = true
        handler.removeCallbacks(resumeAfterSpeak)   // НЕ возобновлять Vosk автоматически — действие решит само
        runCatching { tts?.speak(cleanForSpeech(text), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "golosruki_then") }
        // страховка, если onDone не придёт
        handler.postDelayed({ val cb = afterSpeak; if (cb != null) { afterSpeak = null; fireAfterSpeak(cb) } }, 1800L + text.length * 90L + speechTailGraceMs)
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
        deepSleep -> "🌙 Глубокий сон — скажите «${cap(deepSleepPhrase)}»"
        recordingVoice -> "🎙 Запись: «${cap(wakeWord)} отправь» или «${cap(wakeWord)} отмена»"
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
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ГолосРуки")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(resetAction)
        // Кнопка «Глубокий сон» в уведомлении — если функция включена.
        if (deepSleepEnabled && !deepSleep) {
            val dsIntent = Intent(this, VoiceRecognitionService::class.java).setAction(ACTION_DEEP_SLEEP)
            val dsPi = if (Build.VERSION.SDK_INT >= 26)
                android.app.PendingIntent.getForegroundService(this, 2, dsIntent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            else android.app.PendingIntent.getService(this, 2, dsIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            @Suppress("DEPRECATION")
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_lock_idle_lock, "🌙 Глубокий сон", dsPi).build())
        }
        return builder.build()
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

    /** Есть ли РЕАЛЬНО подключённый микрофон гарнитуры/HFP (а не просто A2DP-музыка). Без ретраев. */
    private fun btScoMicAvailable(): Boolean = try {
        Build.VERSION.SDK_INT >= 31 &&
            audioManager.availableCommunicationDevices.any { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    } catch (e: Exception) { false }

    /** Когда осмысленно взять микрофон гарнитуры: пользователь общается с Иваном, музыка/звонок НЕ идут,
     *  и реально есть HFP-микрофон. В авто при играющей музыке → false (музыка не рвётся). Без переключателей.
     *  v8.9: уважаем настройку «BT-микрофон». Когда она ВЫКЛ (по умолчанию) — НЕ входим в режим связи вообще,
     *  иначе магнитола читает вход/выход MODE_IN_COMMUNICATION как конец звонка и САМА возобновляет музыку. */
    private fun wantHeadsetMic(): Boolean =
        SettingsStore.getBtMic(this) &&
        // v8.12: SCO поднимаем ТОЛЬКО на окно реального захвата (диктовка / вопрос-ИИ / диалог), а не на
        // всю AWAKE-сессию. Иначе MODE_IN_COMMUNICATION висел до сна и магнитола (LADA Vesta = HFP)
        // держала ложный «вызов». aiDialog держит SCO между ходами «поговорим» — без мигания канала.
        // v8.13: recordingVoice УБРАН. Под запись голосового вход в MODE_IN_COMMUNICATION посреди записи
        // Telegram рвал её (срывалась за ~0.5 c, голосовое уходило пустым) и зажигал ложный «вызов».
        // Мессенджер пишет со встроенного в NORMAL-режиме — аудиорежим под запись трогать нельзя.
        (dictation || aiListening || aiDialog) &&
        !callActive && !inCall() && !isMediaPlaying() && btScoMicAvailable()

    /** Берём/отпускаем микрофон гарнитуры через канал связи. Вызывается ТОЛЬКО при реальном наличии HFP. */
    private fun applyMicRouting(headset: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            if (headset) {
                val bt = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (bt != null && !btScoOn) {
                    audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                    AudioDiag.markScoRequested()   // v8.11 диагностика: отметка t0 для замера задержки
                    val ok = audioManager.setCommunicationDevice(bt)
                    btScoOn = ok
                    Logger.log("MIC", "Гарнитурный микрофон ВКЛ (${bt.productName}, ok=$ok)")
                }
            } else if (btScoOn || audioModeIs(android.media.AudioManager.MODE_IN_COMMUNICATION)) {
                audioManager.clearCommunicationDevice()
                audioManager.mode = android.media.AudioManager.MODE_NORMAL
                btScoOn = false
                Logger.log("MIC", "Микрофон гарнитуры отпущен → встроенный")
            }
        }
    }

    /** Переключить движок под текущую цель (гарнитура↔встроенный) только если решение изменилось. */
    private fun refreshMicForState() {
        updateMusicDuck()   // v8.14: при любой смене состояния пересчитать приглушение музыки
        if (wantHeadsetMic() != btScoOn) restartListening()
    }

    /** v8.14: музыку приглушаем поверх музыки из колонок, чтобы мик слышал голос (AEC не вытягивает —
     *  динамики далеко от мика). v8.17: ТОЛЬКО на звонке/входящем. На командах/«думай» приглушение
     *  через фокус оставляло плеер залипшим тихим (плеер игнорирует возврат фокуса — проверено логом
     *  17:52→17:54: одно чистое отпускание, музыка не вернулась). На звонке возврат тянет телефония
     *  (надёжно), поэтому там оставляем. Прямой setStreamVolume не вариант — дрейфует по A2DP. */
    private fun wantMusicDuck(): Boolean = callActive

    private fun updateMusicDuck() {
        val want = wantMusicDuck()
        if (want) {
            handler.removeCallbacks(releaseDuckRunnable)   // отменить отложенное отпускание
            if (!musicDucked) acquireDuckNow()
        } else if (musicDucked) {
            // отпускаем НЕ мгновенно: короткий «сон→снова команда» не должен дёргать плеер (залипал тихим)
            handler.removeCallbacks(releaseDuckRunnable)
            handler.postDelayed(releaseDuckRunnable, 3000)
        }
    }

    private fun acquireDuckNow() {
        runCatching {
            val req = android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setWillPauseWhenDucked(false)   // музыка не пауза, а тише — нам нужна тишина для мика
                .setOnAudioFocusChangeListener(duckFocusListener, handler)
                .build()
            val res = audioManager.requestAudioFocus(req)
            duckFocusRequest = req
            musicDucked = true
            Logger.log("MIC", "Музыка приглушена (аудиофокус MAY_DUCK, res=$res)")
        }
    }

    private fun releaseDuckNow() {
        if (!musicDucked) return
        runCatching { duckFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) } }
        duckFocusRequest = null
        musicDucked = false
        Logger.log("MIC", "Музыка восстановлена (аудиофокус отпущен)")
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
            // Автоматическая гарнитура: если пользователь общается с Иваном и музыка/звонок не идут —
            // берём микрофон гарнитуры; иначе пишем со встроенного (музыка/CarPlay/звонки не рвутся).
            val headset = wantHeadsetMic()
            applyMicRouting(headset)
            // SCO-микрофон гарнитуры захватывается источником VOICE_COMMUNICATION → тогда нужен NS-движок.
            val useComm = (useNoiseSuppress || headset) && !callActive
            if (useComm) {
                val s = NsSpeechService(rec, 16000, this, this)
                if (s.start()) {
                    nsService = s
                } else {
                    Logger.log("NS", "VOICE_COMMUNICATION-движок не запустился — откат на штатный")
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
        lastResultText = ""        // сброс антидубля при смене режима
        dictation = true
        switchToVoiceIme()   // во время диктовки — наша клавиатура (если настроена)
        dictationDigits = digits
        // При первом входе — забираем уже имеющийся текст поля, чтобы не стереть его.
        // При переключении режима (текст↔цифры) — буфер НЕ трогаем.
        if (!alreadyDictating) {
            // Чистая модель: НЕ стираем поле и НЕ переносим текст в буфер.
            // Буфер пустой, дописываем только НОВОЕ в позицию курсора.
            dictBuffer.setLength(0)
            lastDictApplied = ""
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
        lastResultText = ""        // сброс антидубля при смене режима
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
        // Вопрос/текст захватываем свободным офлайн-распознаванием (Vosk) — надёжно и без сети.
        // (Облачный Whisper для диктовки вопроса отключён: на нестабильной сети он давал таймауты.)
        aiListening = true
        VoiceAccessibilityService.instance?.showStatus(stateText())
        Logger.log("AI", "Захват ${if (ask) "вопроса" else "текста"} для ИИ")
        restartListening()   // свободное распознавание
        resetIdle()
    }

    @Volatile private var cloudCapturing = false

    /** Облачный захват речи: освобождаем мик у Vosk, пишем сегмент, шлём в Whisper, результат → ИИ. */
    private fun startCloudCapture(ask: Boolean) {
        cloudCapturing = true
        handler.removeCallbacks(idleRunnable)
        VoiceAccessibilityService.instance?.showStatus("🎤 Слушаю (онлайн)…")
        Logger.log("STT", "Облачный захват (${if (ask) "вопрос" else "текст"})")
        listeningStop()   // освободить микрофон от Vosk
        Thread {
            try { Thread.sleep(200) } catch (e: InterruptedException) {}   // дать мику освободиться
            val wav = MicRecorder.recordWav(
                maxMs = SettingsStore.getVadMaxMs(this),
                silenceMs = SettingsStore.getVadSilenceMs(this),
                sensitivity = SettingsStore.getVadSensitivity(this)
            )
            val text = if (wav != null) CloudStt.transcribe(this, wav) else null
            post {
                cloudCapturing = false
                if (text.isNullOrBlank()) {
                    Logger.log("STT", "Пусто/ошибка: ${CloudStt.lastError}")
                    // Откат: свободный захват через Vosk (офлайн-распознавание).
                    aiListening = true
                    VoiceAccessibilityService.instance?.showStatus(stateText())
                    restartListening(); resetIdle()
                } else {
                    Logger.log("STT", "Whisper: '$text'")
                    handleAi(ask, text)
                }
            }
        }.start()
    }

    private fun handleAi(ask: Boolean, query: String) {
        if (ask) lastAiQuestion = query     // для команды «подробнее»
        aiThinking = true
        handler.removeCallbacks(idleRunnable)   // не засыпать, пока модель думает
        // v8.13: во время «думания» микрофон не нужен → отпустить SCO, чтобы магнитольный ложный «вызов»
        // не висел всё время генерации (на плохой сети это были минуты). В диалоге «поговорим» (aiDialog)
        // канал остаётся поднятым — wantHeadsetMic его удержит, без мигания между ходами.
        refreshMicForState()
        val online = CloudAi.isConfigured(this) && Net.isOnline(this)
        VoiceAccessibilityService.instance?.showStatus(if (online) "🧠 Думаю (онлайн)…" else "🧠 Думаю…")
        Logger.log("AI", "Запрос (${if (ask) "спроси" else "сформулируй"}): '$query'")
        Thread {
            val answer = LocalAi.answer(this, ask, query)
            post {
                aiThinking = false
                Logger.log("AI", "Ответ: '$answer'")
                val ok = answer.isNotBlank() && !answer.startsWith("Модель") &&
                    !answer.startsWith("ИИ-помощник выключен") && !answer.startsWith("Не удалось") &&
                    !answer.startsWith("Не расслышал")
                val composedOk = !ask && ok
                // «Сформулируй» — вставляем готовый текст в активное поле ввода
                if (composedOk) insertComposed(answer)
                val voice = AiProfile.load(this).voiceAnswers
                // Ответ держим на экране до конца озвучки + время на дочитывание (не подменяется «сном»).
                answerHeld = true
                answerStartedAt = System.currentTimeMillis()
                handler.removeCallbacks(clearAnswerHold)
                VoiceAccessibilityService.instance?.showStatusHold("🧠 " + formatForScreen(answer), 26, 0L)
                val continueDialog = aiDialog && ask
                if (voice) {
                    val toSpeak = if (composedOk) "Сгенерировал текст" else answer
                    // озвучить и ТОЛЬКО после речи — дать дочитать, затем продолжить
                    speakThen(toSpeak) {
                        if (continueDialog) { answerHeld = false; startAiQuery(true) }   // сразу слушаем следующую реплику
                        else { handler.postDelayed(clearAnswerHold, 5000L); restartListening(); resetIdle() }
                    }
                } else {
                    val readMs = estimateReadMs(answer)
                    if (continueDialog) handler.postDelayed({ answerHeld = false; startAiQuery(true) }, readMs)
                    else { handler.postDelayed(clearAnswerHold, readMs); restartListening(); resetIdle() }
                }
            }
        }.start()
    }

    /** Вставить сформулированный ИИ текст в активное поле — как диктовка:
     *  через невидимую клавиатуру (работает в браузере/заметках), иначе через спец-возможности. */
    /** Очистка (all=true) или удаление одного символа/выделенного (all=false).
     *  Делаем через клавиатуру (симуляция Backspace — работает даже в заметках/холсте);
     *  если авто-клавиатура недоступна — через спец-возможности. */
    fun clearViaKeyboard(all: Boolean) {
        val kb = GolosRukiKeyboardService.instance
        when {
            kb != null && kb.isActiveInput() -> {
                if (all) kb.selectAllAndDelete() else kb.deleteBack()
            }
            imeReadyForAutoSwitch() -> {
                switchToVoiceIme()
                handler.postDelayed({
                    val k = GolosRukiKeyboardService.instance
                    if (k != null && k.isActiveInput()) {
                        if (all) k.selectAllAndDelete() else k.deleteBack()
                    } else {
                        VoiceAccessibilityService.instance?.clearOrDeleteAcc(all)
                    }
                    switchBackIme()
                }, 700)
            }
            else -> VoiceAccessibilityService.instance?.clearOrDeleteAcc(all)
        }
    }

    private fun insertComposed(text: String) {
        val kb = GolosRukiKeyboardService.instance
        when {
            kb != null && kb.isActiveInput() -> {
                kb.insertText(text)
                Logger.log("AI", "Текст вставлен (клавиатура уже активна)")
            }
            imeReadyForAutoSwitch() -> {
                // Включаем нашу клавиатуру, ждём подключения к полю, вставляем, возвращаем обычную.
                switchToVoiceIme()
                handler.postDelayed({
                    val k = GolosRukiKeyboardService.instance
                    if (k != null && k.isActiveInput()) {
                        k.insertText(text); Logger.log("AI", "Текст вставлен (клавиатура)")
                    } else {
                        VoiceAccessibilityService.instance?.insertText(text)
                        Logger.log("AI", "Текст вставлен (спец-возможности, клавиатура не подключилась)")
                    }
                    switchBackIme()
                }, 700)
            }
            else -> {
                VoiceAccessibilityService.instance?.insertText(text)
                Logger.log("AI", "Текст вставлен (спец-возможности)")
            }
        }
    }

    /** Примерное время «прочитать» ответ глазами (для удержания статуса без озвучки). */
    private fun estimateReadMs(t: String): Long = (3000L + t.length * 45L).coerceIn(3000L, 25000L)

    private fun resetIdle() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, idleMs)
    }

    /** Принудительно увести микрофон в сон (используется жестом с включённым флагом «сон после жеста»). */
    fun goToSleep() {
        handler.removeCallbacks(idleRunnable)
        if (aiListening) { aiListening = false }
        state = State.ASLEEP
        refreshMicForState()   // v8.12: уходя в сон — отпустить SCO (гаснет ложный «вызов» на магнитоле)
        if (!answerHeld) {   // не подменять текст ответа на «сон», пока идёт озвучка/чтение
            VoiceAccessibilityService.instance?.showStatus(stateText())
            VoiceAccessibilityService.instance?.keepScreenOn(false)
        }
        refreshNotification()
        Logger.log("REC", "Микрофон уведён в сон")
    }

    /** Войти в глубокий сон (если разрешён). Выход — только точной фразой пробуждения. */
    fun enterDeepSleep() {
        if (!deepSleepEnabled) {
            speak("Глубокий сон выключен в настройках.")
            VoiceAccessibilityService.instance?.showStatus("Глубокий сон выключен в настройках")
            return
        }
        handler.removeCallbacks(idleRunnable)
        aiListening = false; aiDialog = false; planning = false; querying = false
        pendingPlan = null; pendingTaskDone = null; pendingReminderCancel = null; pendingEventCancel = null; pendingContactChoice = null; pendingCall = null
        if (dictation) exitDictation()
        deepSleep = true
        state = State.ASLEEP
        VoiceAccessibilityService.instance?.keepScreenOn(false)
        refreshNotification()
        VoiceAccessibilityService.instance?.releaseStatusHold()
        VoiceAccessibilityService.instance?.showStatus("🌙 Глубокий сон — скажите «${cap(deepSleepPhrase)}»")
        speak("Глубокий сон включён. Чтобы разбудить меня, скажите: $deepSleepPhrase")
        Logger.log("REC", "Глубокий сон ВКЛ. Фраза выхода: '$deepSleepPhrase'")
    }

    /** Разрешение «позвони X»: поиск контактов → звонок (1), окно выбора (несколько) или захват имени голосом/подсказка (0). */
    private fun resolveCall(query: String) {
        val cands = Contacts.search(this, query)
        when {
            cands.isEmpty() -> {
                if (CloudStt.isConfigured(this) && Net.isOnline(this)) startNameCapture()
                else {
                    speak("Не нашёл контакт. Назовите имя")
                    VoiceAccessibilityService.instance?.showStatus("Контакт не найден — назовите имя")
                }
            }
            cands.size == 1 -> startCall(cands[0].name, cands[0].number)
            else -> showContactChoice(cands)
        }
    }

    private fun showContactChoice(cands: List<Contacts.C>) {
        pendingContactChoice = cands
        val lines = cands.mapIndexed { i, c -> "${i + 1}. ${capWords(c.name)}" }.joinToString("\n")
        VoiceAccessibilityService.instance?.showStatusHold("Кого набрать?\n$lines\nСкажите номер или «отмена»", cands.size + 3, 21000)
        val spoken = "Нашёл несколько. " +
            cands.mapIndexed { i, c -> "${ordinal(i + 1)} — ${c.name}" }.joinToString(". ") +
            ". Скажите номер."
        speak(spoken)
        handler.postDelayed({ if (pendingContactChoice === cands) { pendingContactChoice = null; Logger.log("CMD", "Выбор контакта истёк") } }, 20000)
    }

    /** Гибрид: имя контакта не распозналось офлайн — просим назвать и распознаём облаком (Whisper). */
    private fun startNameCapture() {
        VoiceAccessibilityService.instance?.showStatus("🎤 Назовите имя…")
        speakThen("Назовите имя") {
            isSpeaking = false
            listeningStop()   // освободить микрофон под запись
            Thread {
                try { Thread.sleep(150) } catch (e: InterruptedException) {}
                val wav = MicRecorder.recordWav(
                    maxMs = 8000,
                    silenceMs = SettingsStore.getVadSilenceMs(this),
                    sensitivity = SettingsStore.getVadSensitivity(this)
                )
                val name = if (wav != null) CloudStt.transcribe(this, wav) else null
                post {
                    restartListening(); resetIdle()   // вернуть Vosk (для выбора/подтверждения)
                    if (name.isNullOrBlank()) {
                        Logger.log("STT", "Имя не распознано: ${CloudStt.lastError}")
                        speak("Не расслышал имя")
                    } else {
                        Logger.log("STT", "Имя: '$name'")
                        val cands = Contacts.search(this, name)
                        when {
                            cands.isEmpty() -> speak("Не нашёл контакт $name")
                            cands.size == 1 -> startCall(cands[0].name, cands[0].number)
                            else -> showContactChoice(cands)
                        }
                    }
                }
            }.start()
        }
    }

    private fun startCall(name: String, number: String) {
        if (confirmCalls) {
            pendingCall = name to number
            VoiceAccessibilityService.instance?.showStatus("Звоню $name? Скажите «да» или «нет»")
            speak("Звоню $name? Скажите да или нет")
            handler.postDelayed({ if (pendingCall != null) { pendingCall = null; Logger.log("CMD", "Подтверждение истекло") } }, 15000)
        } else {
            post { VoiceAccessibilityService.instance?.execute(Command.CallContact(name, number)) }
        }
    }

    private fun choiceIndex(t: String): Int = when {
        t.contains("перв") || t.contains("один") || t.contains("одна") -> 1
        t.contains("втор") || t.contains("два") || t.contains("две") -> 2
        t.contains("трет") || t.contains("три") -> 3
        t.contains("четверт") || t.contains("четыре") -> 4
        t.contains("пят") -> 5
        else -> 0
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "первый"; 2 -> "второй"; 3 -> "третий"; 4 -> "четвёртый"; 5 -> "пятый"; else -> "$n"
    }

    private fun capWords(s: String): String =
        s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    // ===== Секретарь (этап A) =====

    /** Старт свободного захвата плана. Требует онлайн-модель. */
    fun startPlanQuery(kind: String = "event") {
        if (!(CloudAi.isConfigured(this) && Net.isOnline(this))) {
            VoiceAccessibilityService.instance?.showStatus("📝 Для секретаря нужны онлайн-модель и интернет")
            speak("Для планирования нужна онлайн-модель и интернет. Включите её в настройках.")
            return
        }
        planKind = kind
        planning = true
        aiListening = true
        aiAsk = false
        val prompt = when (kind) {
            "reminder" -> "🔔 Слушаю напоминание… когда и о чём напомнить"
            "task" -> "🗂 Слушаю задачу… что добавить в дела"
            else -> "📝 Слушаю план… что запланировать"
        }
        VoiceAccessibilityService.instance?.showStatus(prompt)
        Logger.log("SEC", "Захват ($kind)")
        restartListening()   // свободное распознавание
        resetIdle()
    }

    private fun handlePlan(text: String) {
        if (text.isBlank()) { restartListening(); resetIdle(); return }
        aiThinking = true
        handler.removeCallbacks(idleRunnable)
        VoiceAccessibilityService.instance?.showStatus("📝 Обрабатываю…")
        Logger.log("SEC", "План ($planKind): '$text'")
        val kind = planKind
        Thread {
            val p = Secretary.plan(this, text, kind)
            val conflict = if (p.ok && p.kind == "event" && !p.allDay) {
                val busy = CalendarReader.busyBetween(this, p.startMillis, p.startMillis + p.durationMin * 60_000L)
                if (busy.isNotEmpty()) "Внимание: в это время уже есть — ${busy.joinToString(", ")}. " else ""
            } else ""
            post {
                aiThinking = false
                restartListening(); resetIdle()
                if (!p.ok) {
                    speak("Не получилось: ${p.error}. Повторите по-другому.")
                    VoiceAccessibilityService.instance?.showStatus("Не понял: ${p.error}")
                } else if (p.kind == "task") {
                    addPlannedTask(p)   // задачу добавляем сразу, без «да/нет»
                } else {
                    pendingPlan = p
                    val card = Secretary.confirmPhrase(p).removeSuffix(" Скажите да или нет.")
                    VoiceAccessibilityService.instance?.showStatusHold("$conflict$card\nСкажите «да» или «нет»", 7, 31000)
                    speak(conflict + Secretary.confirmPhrase(p))
                    handler.postDelayed({ if (pendingPlan === p) { pendingPlan = null; Logger.log("SEC", "Подтверждение истекло") } }, 30000)
                }
            }
        }.start()
    }

    /** Добавить задачу сразу (без подтверждения) — это «дело» со статусом, без блокировки времени. */
    private fun addPlannedTask(p: PlanResult) {
        val m = Secretary.mem(this)
        val task = Task(java.util.UUID.randomUUID().toString(), p.title, p.person, p.project,
            p.startMillis, 0, p.note, "open", System.currentTimeMillis())
        m.addTask(task)
        m.log("Задача: ${p.title}; проект=${p.project}; с=${p.person}")
        val due = if (p.startMillis > 0)
            " на " + java.text.SimpleDateFormat("d MMMM", java.util.Locale("ru")).format(java.util.Date(p.startMillis)) else ""
        speak("Добавил задачу: ${p.title}$due")
        VoiceAccessibilityService.instance?.showStatus("🗂 Задача: ${p.title}")
    }

    private fun commitPlan(p: PlanResult) {
        if (p.kind == "reminder") {
            val r = Reminder(java.util.UUID.randomUUID().toString(), p.title, p.startMillis, p.repeatMin,
                "active", System.currentTimeMillis())
            Thread {
                val m = Secretary.mem(this)
                m.addReminder(r)
                ReminderScheduler.scheduleReminderItem(this, r)
                m.log("Напоминание: ${p.title}; повтор=${p.repeatMin}")
                post {
                    speak("Готово. Буду напоминать: ${p.title}${Secretary.repeatPhrase(p.repeatMin)}")
                    VoiceAccessibilityService.instance?.showStatus("🔔 ${p.title}")
                    restartListening(); resetIdle()
                }
            }.start()
            return
        }
        // Событие календаря
        VoiceAccessibilityService.instance?.showStatus("📝 Записываю…")
        val taskId = java.util.UUID.randomUUID().toString()
        Thread {
            val ok = CalendarWriter.create(this, p.title, p.startMillis, p.durationMin, p.reminderMin, Secretary.description(p), p.allDay)
            val m = Secretary.mem(this)
            val task = Task(taskId, p.title, p.person, p.project, p.startMillis, p.reminderMin, p.note, "open", System.currentTimeMillis())
            m.addTask(task)
            m.log("Запланировано: ${p.title}; проект=${p.project}; с=${p.person}; время=${p.startMillis}")
            // Голосовое напоминание от Ивана (в дополнение к напоминанию календаря).
            if (p.reminderMin > 0 && p.startMillis > 0) {
                val at = p.startMillis - p.reminderMin * 60_000L
                if (at > System.currentTimeMillis())
                    ReminderScheduler.scheduleReminder(this, taskId.hashCode(), at, ReminderScheduler.reminderText(task))
            }
            post {
                if (ok) { speak("Готово, запланировал"); VoiceAccessibilityService.instance?.showStatus("✅ ${p.title}") }
                else {
                    speak("Сохранил в задачи, но в календарь записать не смог. Дайте доступ к календарю в настройках секретаря.")
                    VoiceAccessibilityService.instance?.showStatus("Задача сохранена; календарь недоступен")
                }
                restartListening(); resetIdle()
            }
        }.start()
    }

    private fun speakTasks() {
        val t = Secretary.mem(this).openTasks()
        if (t.isEmpty()) { speak("Открытых задач нет"); VoiceAccessibilityService.instance?.showStatus("Задач нет"); return }
        val df = java.text.SimpleDateFormat("d MMMM HH:mm", java.util.Locale("ru"))
        val spoken = t.take(7).joinToString(". ") { tk ->
            tk.title + if (tk.dueMillis > 0) " — " + df.format(java.util.Date(tk.dueMillis)) else ""
        }
        speak("Задачи: $spoken")
        val lines = t.take(8).joinToString("\n") { tk ->
            "• ${tk.title}" + if (tk.dueMillis > 0) " (${df.format(java.util.Date(tk.dueMillis))})" else ""
        }
        holdAnswer("Задачи: $spoken")
        VoiceAccessibilityService.instance?.showStatusHold(formatForScreen("Задачи:\n$lines"), 26, 0L)
    }

    /** Единая повестка: события дня + задачи на сегодня/просроченные + активные напоминания. */
    private fun speakAgenda() {
        VoiceAccessibilityService.instance?.showStatus("🗂 Собираю повестку…")
        Thread {
            val m = Secretary.mem(this)
            val endToday = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59); set(java.util.Calendar.SECOND, 59)
            }.timeInMillis
            val events = if (CalendarReader.hasAccess(this)) CalendarReader.daySummary(this, 0) else "Календарь недоступен."
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru"))
            val open = m.openTasks()
            val dueToday = open.filter { it.dueMillis in 1..endToday }
            val undated = open.count { it.dueMillis == 0L }
            val rem = m.reminders()
            val sb = StringBuilder(events.trimEnd('.', ' ')).append(". ")
            if (dueToday.isNotEmpty()) sb.append("Задачи на сегодня: ").append(dueToday.joinToString(", ") { it.title }).append(". ")
            else sb.append("Задач на сегодня нет. ")
            if (undated > 0) sb.append("Ещё $undated без срока. ")
            if (rem.isNotEmpty()) sb.append("Напоминания: ").append(rem.take(6).joinToString(", ") {
                it.text + (if (it.repeatMin > 0) Secretary.repeatPhrase(it.repeatMin) else " в " + df.format(java.util.Date(it.atMillis)))
            }).append(".")
            val text = sb.toString().trim()
            post { speakAndHold(text, "🗂") }
        }.start()
    }

    /** Список активных напоминаний. */
    private fun speakReminders() {
        val rem = Secretary.mem(this).reminders()
        if (rem.isEmpty()) { speak("Активных напоминаний нет"); VoiceAccessibilityService.instance?.showStatus("Напоминаний нет"); return }
        val df = java.text.SimpleDateFormat("d MMMM HH:mm", java.util.Locale("ru"))
        val spoken = "Напоминания: " + rem.take(7).joinToString(". ") { r ->
            r.text + if (r.repeatMin > 0) Secretary.repeatPhrase(r.repeatMin) else ", " + df.format(java.util.Date(r.atMillis))
        }
        speakAndHold(spoken, "🔔")
    }

    /** Отмена напоминания по номеру (нумерованный выбор голосом). */
    private fun startReminderCancel() {
        val rem = Secretary.mem(this).reminders()
        if (rem.isEmpty()) { speak("Активных напоминаний нет"); VoiceAccessibilityService.instance?.showStatus("Напоминаний нет"); return }
        val top = rem.take(5)
        pendingReminderCancel = top
        val lines = top.mapIndexed { i, r -> "${i + 1}. ${r.text}" }.joinToString("\n")
        VoiceAccessibilityService.instance?.showStatusHold("Какое напоминание отменить?\n$lines\nСкажите номер или «отмена»", top.size + 3, 21000)
        speak("Какое напоминание отменить? " + top.mapIndexed { i, r -> "${ordinal(i + 1)} — ${r.text}" }.joinToString(". ") + ". Скажите номер.")
        handler.postDelayed({ if (pendingReminderCancel === top) { pendingReminderCancel = null; Logger.log("SEC", "Выбор напоминания истёк") } }, 20000)
    }

    /** Свободные окна дня голосом («когда я свободен»). */
    private fun speakFreeWindows(dayOffset: Int) {
        if (!CalendarReader.hasAccess(this)) { speak("Нет доступа к календарю. Дайте его в настройках."); VoiceAccessibilityService.instance?.showStatus("Календарь недоступен"); return }
        VoiceAccessibilityService.instance?.showStatus("🗓 Считаю свободное время…")
        Thread {
            val wins = CalendarReader.freeWindows(this, dayOffset)
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru"))
            val whenWord = if (dayOffset == 0) "сегодня" else "завтра"
            val text = if (wins.isEmpty()) "Свободных окон $whenWord нет."
                else "Свободно $whenWord: " + wins.take(6).joinToString(", ") {
                    "${df.format(java.util.Date(it.first))}–${df.format(java.util.Date(it.second))}"
                } + "."
            post { speakAndHold(text, "🗓") }
        }.start()
    }

    /** «Что горит»: просроченные задачи + ближайшие напоминания + ближайшее событие. */
    private fun speakUrgent() {
        VoiceAccessibilityService.instance?.showStatus("⚠️ Проверяю срочное…")
        Thread {
            val now = System.currentTimeMillis()
            val m = Secretary.mem(this)
            val overdue = m.openTasks().filter { it.dueMillis in 1 until now }
            val soonRem = m.reminders().filter { it.atMillis in now..(now + 3 * 3600_000L) }
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru"))
            val nextEvent = if (CalendarReader.hasAccess(this))
                CalendarReader.events(this, 0).firstOrNull { !it.allDay && it.begin > now } else null
            val sb = StringBuilder()
            if (overdue.isNotEmpty()) sb.append("Просрочено: ").append(overdue.joinToString(", ") { it.title }).append(". ")
            if (soonRem.isNotEmpty()) sb.append("Скоро напоминания: ").append(soonRem.joinToString(", ") { it.text + " в " + df.format(java.util.Date(it.atMillis)) }).append(". ")
            if (nextEvent != null) sb.append("Ближайшее событие: ${nextEvent.title} в ${df.format(java.util.Date(nextEvent.begin))}.")
            val text = sb.toString().trim().ifBlank { "Ничего срочного. Просроченных задач нет." }
            post { speakAndHold(text, "⚠️") }
        }.start()
    }

    /** Вечерний разбор / план на завтра: события + задачи на завтра + свободные окна. */
    private fun speakTomorrowPlan() {
        VoiceAccessibilityService.instance?.showStatus("🌙 Планирую завтра…")
        Thread {
            val m = Secretary.mem(this)
            val cal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis; val end = start + 24L * 60 * 60 * 1000
            val events = if (CalendarReader.hasAccess(this)) CalendarReader.daySummary(this, 1) else "Календарь недоступен."
            val tasks = m.openTasks().filter { it.dueMillis in start until end }
            val wins = if (CalendarReader.hasAccess(this)) CalendarReader.freeWindows(this, 1) else emptyList()
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru"))
            val sb = StringBuilder(events.trimEnd('.', ' ')).append(". ")
            if (tasks.isNotEmpty()) sb.append("Задачи на завтра: ").append(tasks.joinToString(", ") { it.title }).append(". ")
            if (wins.isNotEmpty()) sb.append("Свободно: ").append(wins.take(4).joinToString(", ") {
                "${df.format(java.util.Date(it.first))}–${df.format(java.util.Date(it.second))}"
            }).append(".")
            post { speakAndHold(sb.toString().trim(), "🌙") }
        }.start()
    }

    /** Отмена события календаря по номеру (ближайшие сегодня+завтра). */
    private fun startEventCancel() {
        if (!CalendarReader.hasAccess(this)) { speak("Нет доступа к календарю."); VoiceAccessibilityService.instance?.showStatus("Календарь недоступен"); return }
        VoiceAccessibilityService.instance?.showStatus("🗓 Ищу события…")
        Thread {
            val now = System.currentTimeMillis()
            val list = (CalendarReader.events(this, 0) + CalendarReader.events(this, 1))
                .filter { !it.allDay && it.end > now }.take(6)
            post {
                if (list.isEmpty()) { speak("Ближайших событий нет"); VoiceAccessibilityService.instance?.showStatus("Событий нет"); return@post }
                pendingEventCancel = list
                val df = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("ru"))
                val lines = list.mapIndexed { i, e -> "${i + 1}. ${e.title} (${df.format(java.util.Date(e.begin))})" }.joinToString("\n")
                VoiceAccessibilityService.instance?.showStatusHold("Какое событие отменить?\n$lines\nСкажите номер или «отмена»", list.size + 3, 21000)
                speak("Какое событие отменить? " + list.mapIndexed { i, e -> "${ordinal(i + 1)} — ${e.title}" }.joinToString(". ") + ". Скажите номер.")
                handler.postDelayed({ if (pendingEventCancel === list) { pendingEventCancel = null; Logger.log("SEC", "Выбор события истёк") } }, 20000)
            }
        }.start()
    }

    /** Старт свободного захвата вопроса к памяти. */
    /** Завершение задачи: показываем открытые задачи нумерованным списком, ждём номер. */
    private fun startTaskComplete() {
        val tasks = Secretary.mem(this).openTasks()
        if (tasks.isEmpty()) {
            speak("Открытых задач нет"); VoiceAccessibilityService.instance?.showStatus("Задач нет"); return
        }
        val top = tasks.take(5)
        pendingTaskDone = top
        val lines = top.mapIndexed { i, t -> "${i + 1}. ${t.title}" }.joinToString("\n")
        VoiceAccessibilityService.instance?.showStatusHold("Какую задачу закрыть?\n$lines\nСкажите номер или «отмена»", top.size + 3, 21000)
        speak("Какую задачу закрыть? " + top.mapIndexed { i, t -> "${ordinal(i + 1)} — ${t.title}" }.joinToString(". ") + ". Скажите номер.")
        handler.postDelayed({ if (pendingTaskDone === top) { pendingTaskDone = null; Logger.log("SEC", "Выбор задачи истёк") } }, 20000)
    }

    fun startQuery() {
        querying = true
        aiListening = true
        aiAsk = false
        VoiceAccessibilityService.instance?.showStatus("📋 Слушаю вопрос по делам…")
        Logger.log("SEC", "Захват вопроса к памяти")
        restartListening()
        resetIdle()
    }

    private fun speakBriefing() {
        aiThinking = true
        handler.removeCallbacks(idleRunnable)
        VoiceAccessibilityService.instance?.showStatus("📋 Готовлю сводку…")
        Thread {
            val text = Secretary.briefing(this)
            post {
                aiThinking = false
                speak(text)
                holdAnswer(text)
                VoiceAccessibilityService.instance?.showStatusHold(formatForScreen(text), 26, 0L)
                restartListening(); resetIdle()
            }
        }.start()
    }

    private fun handleQuery(question: String) {
        if (question.isBlank()) { restartListening(); resetIdle(); return }
        aiThinking = true
        handler.removeCallbacks(idleRunnable)
        VoiceAccessibilityService.instance?.showStatus("📋 Смотрю в памяти…")
        Logger.log("SEC", "Вопрос: '$question'")
        Thread {
            val ans = Secretary.answer(this, question)
            post {
                aiThinking = false
                speak(ans)
                holdAnswer(ans)
                VoiceAccessibilityService.instance?.showStatusHold("🧠 " + formatForScreen(ans), 26, 0L)
                restartListening(); resetIdle()
            }
        }.start()
    }

    override fun onResult(hypothesis: String?) {
        handleResult(hypothesis)
        updateMusicDuck()   // v8.14: после обработки результата синхронизировать приглушение с новым state
    }

    private fun handleResult(hypothesis: String?) {
        val raw = hypothesis?.let { JSONObject(it).optString("text") } ?: return
        val text = normalize(raw.replace("[unk]", " "))
        if (text.isBlank()) return
        Logger.log("HEARD", "'$text' | state=$state media=$mediaControlMode dict=$dictation digits=$dictationDigits call=${inCall()} mode=${audioModeName()} tel=${telStateName()}")
        heardWakeInUtterance = text.contains(wakeWord)   // фиксируем ДО любого вырезания «иван»

        // АНТИДУБЛЬ: Vosk иногда отдаёт один и тот же финал десятки раз подряд (без перезапуска прослушки).
        // Это рождает «напиши напиши напиши…», «126 126 126…» в диктовке и спам команд. Гасим повтор того же
        // текста, пока он сыпется непрерывно (окно обновляется на каждом дубле).
        run {
            val nowMs = System.currentTimeMillis()
            if (text == lastResultText && nowMs - lastResultAt < 1200) {
                lastResultAt = nowMs
                return
            }
            lastResultText = text
            lastResultAt = nowMs
        }

        // ГЛУБОКИЙ СОН: игнорируем ВСЁ, кроме точной фразы пробуждения (2–3 слова, точное совпадение).
        if (deepSleep) {
            if (text.trim() == deepSleepPhrase) {
                deepSleep = false
                state = State.AWAKE
                if (paused) setPaused(false)
                resetIdle()
                refreshNotification()
                VoiceAccessibilityService.instance?.releaseStatusHold()
                VoiceAccessibilityService.instance?.showStatus(stateText())
                speak("С возвращением. Я снова слушаю.")
                Logger.log("REC", "Выход из глубокого сна по фразе: '$text'")
            } else {
                Logger.log("REC", "Глубокий сон: игнор '$text'")
            }
            return
        }

        // Во время озвучки: реагируем ТОЛЬКО на «Иван + хватит/стоп» (прервать) или «Иван + подробнее» (расширить).
        if (isSpeaking) {
            val stopWord = text.contains("хватит") || text.contains("стоп") || text.contains("молчи") ||
                text.contains("замолчи") || text.contains("тихо") || text.contains("прекрати")
            val moreWord = text.contains("подробнее") || text.contains("подробней") ||
                text.contains("поподробнее") || text.contains("развёрнут") || text.contains("развернут")
            when {
                text.contains(wakeWord) && stopWord -> {
                    stopSpeaking(); Logger.log("REC", "Озвучка прервана голосом: '$text'")
                }
                text.contains(wakeWord) && moreWord && lastAiQuestion.isNotBlank() -> {
                    stopSpeaking()
                    Logger.log("REC", "Озвучка прервана — запрошено подробнее")
                    handleAi(true, "$lastAiQuestion. Ответь подробно и развёрнуто.")
                }
                else -> Logger.log("REC", "Игнор (говорит синтезатор): '$text'")
            }
            return
        }

        // Пока модель «думает» — игнорируем всё, чтобы шум не сбивал статус и не запускал команды.
        if (aiThinking) { Logger.log("AI", "(размышление) игнор: '$text'"); return }

        // ИДЁТ ЗАПИСЬ ГОЛОСОВОГО. Речь пользователя пишется в сообщение и НЕ должна дёргать команды.
        // Принимаем только «<слово> отправь/отправить» (тап по кнопке записи = отправка) и «<слово> отмена» (Назад).
        if (recordingVoice) {
            val hasWake = text.contains(wakeWord)
            val rest = if (hasWake) stripWake(text) else text
            when {
                hasWake && rest.contains("отправ") -> {
                    Logger.log("REC", "Запись голосового → отправка")
                    post { VoiceAccessibilityService.instance?.execute(Command.RecordSend) }
                }
                hasWake && (rest.contains("отмен") || rest.contains("назад") || rest.contains("стоп")) -> {
                    Logger.log("REC", "Запись голосового → отмена")
                    post { VoiceAccessibilityService.instance?.execute(Command.RecordCancel) }
                }
                else -> Logger.log("REC", "Запись голосового: игнор '$text'")
            }
            return
        }

        // Свободный захват вопроса/текста для ИИ — приоритетно (выше медиа/экрана),
        // чтобы вопрос всегда ловился, даже когда играет видео или активен медиа-режим.
        if (aiListening) {
            // Убираем эхо слова активации из самой фразы-триггера, чтобы захват не срывался сразу.
            var t = text
            if (t.contains(wakeWord)) t = stripWake(t)
            t = t.trim()
            if (t.isBlank()) { resetIdle(); return }   // пусто/эхо «иван» — продолжаем слушать вопрос
            // Выход только по явным стоп-словам (не по слову активации — иначе «иван» в вопросе обрывал захват).
            val first = t.split(" ").firstOrNull() ?: ""
            val stopExit = first == "стоп" || first == "отмена" || first == "отменить" || first == "хватит" ||
                first == "молчи" || t.contains("закончили") || t.contains("достаточно") ||
                t.contains("конец разговора") || t.contains("спасибо хватит")
            if (stopExit) {
                aiListening = false; aiDialog = false; planning = false; querying = false
                VoiceAccessibilityService.instance?.showStatus(stateText())
                restartListening(); return
            }
            aiListening = false
            when {
                planning -> { planning = false; handlePlan(t) }
                querying -> { querying = false; handleQuery(t) }
                else -> handleAi(aiAsk, t)
            }
            return
        }

        // ИДЁТ РАЗГОВОР (сотовый звонок или звонок в мессенджере): голос собеседника не должен
        // дёргать команды. Требуем слово активации; медиа-режим выключаем.
        // Во время звонка (мобильного или в приложении) — управление ТОЛЬКО через «Иван…».
        // ВАЖНО: не перехватываем команду своим путём (старый блок так делал — и это ломало звонок).
        // Просто отсекаем речь без «Иван», а «иван команду» пускаем дальше штатным путём,
        // который доказанно работает (как в 6.45) — сброс/сетка/номера отрабатывают нормально.
        if (inCall() && !text.contains(wakeWord)) {
            Logger.log("REC", "Игнор (звонок, без «Иван»): '$text'")
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
                // Управление звонком при погашенном экране — важно (телефон у уха).
                val cc = CommandParser.parse(rest, personal)
                if (cc is Command.RejectCall || cc is Command.AnswerCall) {
                    Logger.log("CALL", "Экран выкл, звонок: ${cc.label()}")
                    post { VoiceAccessibilityService.instance?.execute(cc) }
                    return
                }
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
            val t = text.trim()
            // Выход ТОЛЬКО по слову «готово» (и его частом мисхире «готова») — отдельным словом.
            // Никаких стоп/отмена/конец, чтобы лишнее слово в потоке речи не прерывало набор.
            // Слово активации в начале фразы — осознанная команда, тоже выходит.
            if (t == "готово" || t == "готова" || t.startsWith(wakeWord)) {
                exitDictation(); return
            }
            // Одиночное командное слово входа в диктовку НЕ печатаем (эхо триггера, причина «напиши» в поле).
            if (t in setOf("напиши", "напишите", "диктовка", "печатать", "печатай", "введи", "пиши")) {
                resetIdle(); return
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
            // v8.8 анти-дубль диктовки: Vosk нередко повторяет или наращивает финал одной фразы,
            // отчего текст множился («…из автомобиля …из автомобиля»). Применяем только новую часть.
            var toApply = text.trim()
            when {
                toApply.isBlank() -> return
                toApply == lastDictApplied -> return                                          // точный повтор
                lastDictApplied.isNotBlank() && lastDictApplied.startsWith(toApply) -> return  // усечённый повтор
                lastDictApplied.isNotBlank() && toApply.startsWith(lastDictApplied) ->         // нарастающий финал → только дельта
                    toApply = toApply.removePrefix(lastDictApplied).trim()
            }
            lastDictApplied = text.trim()
            if (toApply.isBlank()) return
            if (dictationDigits) {
                val chunk = NumberWords.toDigits(toApply)
                if (chunk.isNotBlank()) {
                    if (dictBuffer.isNotEmpty() && dictBuffer.last() != ' ' && dictBuffer.last() != '\n') dictBuffer.append(" ")
                    dictBuffer.append(chunk); flushDictation()
                }
            } else {
                applyDictation(toApply)
            }
            return
        }

        val isWake = text.contains(wakeWord)
        // «слушай/продолжи» — это ВОЗОБНОВЛЕНИЕ после явной паузы, а НЕ общее пробуждение.
        // Иначе бытовое «слушай…» от людей рядом (и собственное «я снова слушаю») будило ассистента.
        // Будит только слово активации «$wakeWord».
        val isResume = paused && (text == "слушай" || text == "продолжи" || text == "продолжай" ||
            text.startsWith("слушай") || text.startsWith("продолж"))

        if (isWake || isResume) {
            state = State.AWAKE
            if (paused) { setPaused(false); Logger.log("REC", "Пауза снята") }
            resetIdle()
            refreshMicForState()   // проснулись — взять микрофон гарнитуры, если уместно (без музыки/звонка)
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
        // Ожидание подтверждения плана секретаря
        pendingPlan?.let { p ->
            when {
                text.contains("да") || text.contains("давай") || text.contains("подтвержда") ||
                    text.contains("ага") || text.contains("верно") -> {
                    pendingPlan = null
                    VoiceAccessibilityService.instance?.releaseStatusHold()
                    Logger.log("SEC", "План подтверждён: ${p.title}")
                    commitPlan(p)
                    return
                }
                text.contains("нет") || text.contains("отмена") || text.contains("стоп") || text.contains("не надо") -> {
                    pendingPlan = null
                    VoiceAccessibilityService.instance?.releaseStatusHold()
                    speak("Отменено"); VoiceAccessibilityService.instance?.showStatus("План отменён")
                    return
                }
                else -> { return }   // ждём «да»/«нет»
            }
        }
        // Ожидание выбора задачи для завершения (по номеру)
        pendingTaskDone?.let { list ->
            when {
                text.contains("отмена") || text.contains("нет") || text.contains("стоп") -> {
                    pendingTaskDone = null
                    VoiceAccessibilityService.instance?.releaseStatusHold()
                    speak("Отменено"); VoiceAccessibilityService.instance?.showStatus("Отменено")
                    return
                }
                else -> {
                    val idx = choiceIndex(text)
                    if (idx in 1..list.size) {
                        val t = list[idx - 1]; pendingTaskDone = null
                        VoiceAccessibilityService.instance?.releaseStatusHold()
                        val m = Secretary.mem(this)
                        m.completeTask(t.id); m.log("Задача выполнена: ${t.title}")
                        ReminderScheduler.cancelReminder(this, t.id.hashCode())
                        Logger.log("SEC", "Задача закрыта: ${t.title}")
                        speak("Закрыл: ${t.title}")
                        VoiceAccessibilityService.instance?.showStatus("✅ ${t.title}")
                    } else Logger.log("SEC", "Выбор задачи не распознан: '$text'")
                    return
                }
            }
        }
        pendingReminderCancel?.let { list ->
            when {
                text.contains("отмена") || text.contains("нет") || text.contains("стоп") -> {
                    pendingReminderCancel = null
                    VoiceAccessibilityService.instance?.releaseStatusHold()
                    speak("Отменено"); VoiceAccessibilityService.instance?.showStatus("Отменено")
                    return
                }
                else -> {
                    val idx = choiceIndex(text)
                    if (idx in 1..list.size) {
                        val r = list[idx - 1]; pendingReminderCancel = null
                        VoiceAccessibilityService.instance?.releaseStatusHold()
                        val m = Secretary.mem(this)
                        ReminderScheduler.cancelReminderItem(this, r.id)
                        m.completeReminder(r.id); m.log("Напоминание отменено: ${r.text}")
                        Logger.log("SEC", "Напоминание отменено: ${r.text}")
                        speak("Отменил напоминание: ${r.text}")
                        VoiceAccessibilityService.instance?.showStatus("🔕 ${r.text}")
                    } else Logger.log("SEC", "Выбор напоминания не распознан: '$text'")
                    return
                }
            }
        }
        pendingEventCancel?.let { list ->
            when {
                text.contains("отмена") || text.contains("нет") || text.contains("стоп") -> {
                    pendingEventCancel = null
                    VoiceAccessibilityService.instance?.releaseStatusHold()
                    speak("Отменено"); VoiceAccessibilityService.instance?.showStatus("Отменено")
                    return
                }
                else -> {
                    val idx = choiceIndex(text)
                    if (idx in 1..list.size) {
                        val ev = list[idx - 1]; pendingEventCancel = null
                        VoiceAccessibilityService.instance?.releaseStatusHold()
                        VoiceAccessibilityService.instance?.showStatus("🗓 Отменяю…")
                        Thread {
                            val ok = CalendarWriter.delete(this, ev.id)
                            Secretary.mem(this).log("Событие отменено: ${ev.title}")
                            Logger.log("SEC", "Событие отменено: ${ev.title} (ok=$ok)")
                            post {
                                speak(if (ok) "Отменил событие: ${ev.title}" else "Не смог отменить — нет доступа на запись календаря")
                                VoiceAccessibilityService.instance?.showStatus(if (ok) "🗑 ${ev.title}" else "Нет доступа к записи календаря")
                            }
                        }.start()
                    } else Logger.log("SEC", "Выбор события не распознан: '$text'")
                    return
                }
            }
        }
        // Ожидание выбора контакта из нескольких совпадений
        pendingContactChoice?.let { list ->
            when {
                text.contains("отмена") || text.contains("нет") || text.contains("стоп") || text.contains("не надо") -> {
                    pendingContactChoice = null
                    VoiceAccessibilityService.instance?.releaseStatusHold()
                    speak("Отменено"); VoiceAccessibilityService.instance?.showStatus("Отменено")
                    return
                }
                else -> {
                    val idx = choiceIndex(text)
                    if (idx in 1..list.size) {
                        val c = list[idx - 1]
                        pendingContactChoice = null
                        VoiceAccessibilityService.instance?.releaseStatusHold()
                        Logger.log("CMD", "Выбран контакт #$idx: ${c.name}")
                        startCall(c.name, c.number)
                    } else {
                        Logger.log("CMD", "Выбор не распознан: '$text' — ждём")
                    }
                    return   // ждём корректный номер или «отмена»
                }
            }
        }
        // Ожидание подтверждения записи жеста (защита от случайного запуска холста)
        if (pendingGestureCalib) {
            when {
                text.contains("да") || text.contains("подтвержда") || text.contains("начинай") ||
                    text.contains("записывай") || text.contains("давай") -> {
                    pendingGestureCalib = false
                    Logger.log("CMD", "Калибровка жеста подтверждена")
                    post { VoiceAccessibilityService.instance?.execute(Command.CalibrateRecordGesture) }
                    return
                }
                text.contains("нет") || text.contains("отмена") || text.contains("стоп") || text.contains("не надо") -> {
                    pendingGestureCalib = false
                    speak("Отменено")
                    VoiceAccessibilityService.instance?.showStatus("Калибровка жеста отменена")
                    return
                }
                else -> { pendingGestureCalib = false }  // иначе сбрасываем и обрабатываем как обычную команду
            }
        }
        // Глубокий сон по голосу: «глубокий сон» / «крепкий сон»
        if (text.contains("глубокий сон") || text.contains("крепкий сон")) {
            enterDeepSleep(); return
        }
        // ИИ-помощник: «думай/спроси/сформулируй/поговорим/подробнее» — РАНЬШЕ погоды/календаря/секретаря,
        // иначе «думай как сегодня» уходило в Календарь («сегодня»).
        run {
            val askTriggers = listOf("поразмышляй", "порассуждай", "подумай", "размышляй", "рассуждай", "спросить", "спроси", "думай")
            val composeTriggers = listOf("сформулируй", "сформулир")
            val dialogTriggers = listOf("поговорим", "побеседуем", "пообщаемся", "поболтаем")
            val moreTriggers = listOf("подробнее", "подробней", "развёрнуто", "развернуто", "поподробнее")
            when {
                moreTriggers.any { text.contains(it) } && lastAiQuestion.isNotBlank() -> {
                    handleAi(true, "$lastAiQuestion. Ответь подробно и развёрнуто."); return
                }
                dialogTriggers.any { text.contains(it) } -> {
                    aiDialog = true
                    // прогреем погоду в фоне — чтобы в светской беседе («как дела») Иван мог её обыграть
                    Thread { runCatching { if (Weather.cached() == null) Weather.describe(this) } }.start()
                    // слушать начинаем ТОЛЬКО после вступления (иначе рестарт распознавания во время речи)
                    speakThen("Давайте поговорим. Чтобы закончить — скажите «хватит».") { startAiQuery(true) }
                    return
                }
                composeTriggers.any { text.contains(it) } -> { startAiQuery(false); return }
                askTriggers.any { text.contains(it) } -> { startAiQuery(true); return }
                else -> {}
            }
        }

        // Секретарь: планирование/брифинг/задачи — РАНЬШЕ погоды/календаря («запланируй» содержит «план»)
        run {
            val reminderCreate = listOf("напомни", "напоминай", "напоминать", "напоминание")
            val taskCreate = listOf("добавь задачу", "новая задача", "поставь задачу", "запиши задачу",
                "создай задачу", "не забудь", "не забыть")
            val eventCreate = listOf("запланируй", "запланировать", "назначь", "назначить",
                "добавь встречу", "добавь событие", "поставь встречу", "запиши план", "планирую", "событие", "созвон")
            val taskTriggers = listOf("мои задачи", "список задач", "какие задачи", "что в задачах", "задачи на сегодня")
            val briefTriggers = listOf("брифинг", "сводка", "сводку", "план на день", "доброе утро", "что на сегодня")
            // Устойчивое распознавание по ОСНОВАМ слов (Vosk даёт варианты: «задачи/задачу», «выполнено/выполнена»).
            val hasTask = text.contains("задач")
            val clearTask = (text.contains("очисти") && (text.contains("выполнен") || hasTask)) ||
                (text.contains("удали") && text.contains("выполнен"))
            val doneTask = (hasTask && (text.contains("заверши") || text.contains("закро") ||
                text.contains("закрыть") || text.contains("отметь") || text.contains("выполн"))) ||
                text.trim() == "выполнено" || text.trim() == "готово"
            val agendaTriggers = listOf("повестка", "повестку", "мои дела", "все дела", "что по плану")
            val remindersList = listOf("мои напоминания", "какие напоминания", "список напоминаний",
                "все напоминания", "покажи напоминания")
            val cancelReminder = (text.contains("отмени") || text.contains("удали") || text.contains("убери")) &&
                text.contains("напоминан")
            val cancelEvent = (text.contains("отмени") || text.contains("удали") || text.contains("убери")) &&
                (text.contains("встреч") || text.contains("событи"))
            val freeQuery = text.contains("свободен") || text.contains("свободное время") || text.contains("когда свободен")
            val urgentQuery = text.contains("что горит") || text.contains("что срочно") ||
                text.contains("просрочк") || text.contains("просрочен") || text.contains("что важно")
            val tomorrowPlan = text.contains("спланируй завтра") || text.contains("разбор дня") ||
                text.contains("план на завтра") || text.contains("планы на завтра")
            when {
                cancelReminder -> { startReminderCancel(); return }
                cancelEvent -> { startEventCancel(); return }
                freeQuery -> { speakFreeWindows(if (text.contains("завтра")) 1 else 0); return }
                urgentQuery -> { speakUrgent(); return }
                tomorrowPlan -> { speakTomorrowPlan(); return }
                remindersList.any { text.contains(it) } -> { speakReminders(); return }
                agendaTriggers.any { text.contains(it) } -> { speakAgenda(); return }
                reminderCreate.any { text.contains(it) } -> { startPlanQuery("reminder"); return }
                taskCreate.any { text.contains(it) } -> { startPlanQuery("task"); return }
                briefTriggers.any { text.contains(it) } -> { speakBriefing(); return }
                clearTask -> {
                    val n = Secretary.mem(this).clearDone()
                    speak(if (n > 0) "Удалил $n выполненных задач" else "Выполненных задач нет")
                    return
                }
                doneTask -> { startTaskComplete(); return }
                taskTriggers.any { text.contains(it) } || text.trim() == "задачи" -> { speakTasks(); return }
                eventCreate.any { text.contains(it) } -> { startPlanQuery("event"); return }
                else -> {}
            }
        }
        if (text.contains("погод")) {
            Logger.log("CMD", "Погода")
            resetIdle()
            VoiceAccessibilityService.instance?.showStatus("☁️ Узнаю погоду…")
            val s = text
            Thread {
                val w = when {
                    s.contains("послезавтра") -> Weather.daySummary(this, 2)
                    s.contains("завтра") -> Weather.tomorrow(this)
                    s.contains("недел") || s.contains("ближайш") || s.contains("несколько дн") || s.contains("на дни") -> Weather.week(this)
                    else -> Weather.describe(this)
                }
                post { speakAndHold(w, "☁️") }
            }.start()
            return
        }
        if (text.contains("нового") || text.contains("новенького") ||
            text.contains("прочитай уведомл") || text.contains("зачитай уведомл") || text.contains("какие уведомл")) {
            Logger.log("CMD", "Чтение уведомлений")
            speakAndHold(NotificationService.recentSummary(this, 5), "🔔")
            return
        }
        run {
            val dayWord = text.contains("сегодня") || text.contains("завтра")
            val dateTimeQ = text.contains("числ") || text.contains("врем") || text.contains("час") ||
                text.contains("погод") || text.contains("недел") || text.contains("дата")
            if ((text.contains("план") && !text.contains("заплан") && !text.contains("планир")) ||
                text.contains("календар") || text.contains("расписани") ||
                (dayWord && !dateTimeQ)) {
                val off = if (text.contains("завтра")) 1 else 0
                Logger.log("CMD", "Календарь (день +$off)")
                speakAndHold(CalendarReader.daySummary(this, off), "🗓")
                return
            }
        }
        // Секретарь: запрос к памяти — ПОСЛЕ погоды/календаря/уведомлений, чтобы не перехватывать «что нового/сегодня/погода»
        run {
            val queryTriggers = listOf("что по", "статус по", "как дела по", "что у нас по",
                "справк", "по делам", "что известно")
            if (queryTriggers.any { text.contains(it) }) {
                val rest = afterAny(text, queryTriggers)
                if (rest.length >= 3) handleQuery(text) else startQuery()
                return
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

        // ИИ-помощник: ловим здесь, чтобы текст ПОСЛЕ триггера не терялся (запрос одной фразой),
        // плюс режим диалога «поговорим … хватит».
        val cmd = CommandParser.parse(text, personal)
        Logger.log("CMD", "Команда: ${cmd.label()}")

        if (cmd is Command.CallQuery) { resolveCall(cmd.query); return }

        // «Только по Иван»: строгий жест — лишь если в фразе было слово активации
        if (cmd is Command.CustomGesture && GestureStore.isStrict(cmd.json) && !heardWakeInUtterance) {
            Logger.log("CMD", "Жест «${cmd.name}» только по «$wakeWord» — пропуск")
            return
        }
        // Запуск приложений — по настройке только по «Иван <слово>»
        if (cmd is Command.OpenApp && SettingsStore.getLaunchRequireWake(this) && !heardWakeInUtterance) {
            Logger.log("CMD", "Запуск «${cmd.name}» только по «$wakeWord» — пропуск")
            return
        }

        // Подтверждение звонка (если включено в настройках)
        if (confirmCalls && cmd is Command.CallContact) {
            pendingCall = cmd.name to cmd.number
            VoiceAccessibilityService.instance?.showStatus("Звоню ${cmd.name}? Скажите «да» или «нет»")
            speak("Звоню ${cmd.name}? Скажите да или нет")
            handler.postDelayed({ if (pendingCall != null) { pendingCall = null; Logger.log("CMD", "Подтверждение истекло") } }, 15000)
            return
        }

        // Калибровка жеста — только после подтверждения (защита от случайного открытия холста)
        if (cmd is Command.CalibrateRecordGesture) {
            pendingGestureCalib = true
            VoiceAccessibilityService.instance?.showStatus("Записать жест? Скажите «да» — начать, «отмена» — выйти")
            speak("Записать жест? Скажите да чтобы начать, или отмена")
            handler.postDelayed({ if (pendingGestureCalib) { pendingGestureCalib = false; Logger.log("CMD", "Калибровка жеста: подтверждение истекло") } }, 12000)
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
    private fun afterAny(text: String, triggers: List<String>): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (i in words.indices) {
            if (triggers.any { tr -> words[i] == tr || words[i].startsWith(tr) }) {
                return words.drop(i + 1).joinToString(" ").trim()
            }
        }
        return ""
    }
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

    /** Слушатель состояния звонка: на время мобильного вызова временно выключаем
     *  шумоподавление (его VOICE_COMMUNICATION-захват конфликтует со звонком),
     *  после вызова — возвращаем как было. */
    private fun registerCallListener() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val l = object : android.telephony.PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    val active = state != android.telephony.TelephonyManager.CALL_STATE_IDLE
                    if (active != callActive) {
                        callActive = active
                        updateMusicDuck()   // v8.14: на входящем приглушить музыку, чтобы «иван ответь» расслышался
                        Logger.log("CALL", if (active) "Звонок — шумоподавление временно ВЫКЛ"
                                           else "Звонок завершён — шумоподавление восстановлено")
                        restartListening()     // во время звонка — встроенный мик без NS; после — обратно
                    }
                }
            }
            @Suppress("DEPRECATION")
            tm.listen(l, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            phoneListener = l
        } catch (e: Exception) { Logger.log("CALL", "Слушатель телефона не запущен: ${e.message}") }
    }

    private fun unregisterCallListener() {
        runCatching {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            @Suppress("DEPRECATION")
            phoneListener?.let { tm.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE) }
        }
        phoneListener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(audioDiagPoll)
        handler.removeCallbacks(idleRunnable)
        unregisterCallListener()
        handler.removeCallbacks(releaseDuckRunnable)
        releaseDuckNow()   // вернуть музыку (на всякий — ОС и сама снимет фокус при смерти процесса)
        listeningStop()
        model?.close()
        disableBtMic()
        runCatching { tts?.stop(); tts?.shutdown() }
        instance = null
    }
}
