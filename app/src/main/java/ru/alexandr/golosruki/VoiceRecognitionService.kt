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

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private lateinit var personal: PersonalConfig
    private val handler = Handler(Looper.getMainLooper())

    private var wakeWord = "иван"
    private var idleMs = 30_000L
    private var ignoreMedia = true
    private var vibrateOnWake = true
    private var keepScreen = true
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }

    private enum class State { ASLEEP, AWAKE }
    @Volatile private var state = State.ASLEEP
    @Volatile private var dictation = false
    @Volatile private var dictationDigits = false
    private val dictBuffer = StringBuilder()
    @Volatile private var bigModelActive = false

    private val idleRunnable = Runnable {
        state = State.ASLEEP
        VoiceAccessibilityService.instance?.showStatus("Сон. Скажите: ${cap(wakeWord)}")
        VoiceAccessibilityService.instance?.keepScreenOn(false)
        refreshNotification()
    }

    companion object {
        const val CHANNEL_ID = "golosruki_voice"
        const val NOTIF_ID = 1
        const val ACTION_RESET = "ru.alexandr.golosruki.RESET"
        @Volatile var instance: VoiceRecognitionService? = null
        @Volatile private var paused = false
        fun setPaused(p: Boolean) {
            paused = p
            instance?.let { svc -> svc.handler.post { svc.refreshNotification() } }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESET) resetState()
        return START_STICKY
    }

    /** Полный сброс: снять паузу/диктовку, разбудить, перезапустить распознавание. */
    fun resetState() {
        Logger.log("REC", "СБРОС (перезапуск Ивана)")
        dictation = false
        setPaused(false)
        state = State.AWAKE
        if (model != null) restart(grammar = true)
        resetIdle()
        VoiceAccessibilityService.instance?.keepScreenOn(true)
        refreshNotification()
        VoiceAccessibilityService.instance?.showStatus("${cap(wakeWord)} перезапущен")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        paused = false
        personal = PersonalConfig.load(this)
        wakeWord = SettingsStore.getWake(this)
        idleMs = SettingsStore.getIdle(this) * 1000L
        ignoreMedia = SettingsStore.getIgnoreMedia(this)
        vibrateOnWake = SettingsStore.getVibrate(this)
        keepScreen = SettingsStore.getKeepScreen(this)
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
        dictation -> "✍️ Диктовка — говорите текст, «готово» для выхода"
        paused -> "⏸ Пауза — скажите «слушай»"
        state == State.AWAKE -> "🎙 Слушаю команды"
        else -> "😴 Сон — скажите «${cap(wakeWord)}»"
    }

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
                Logger.log("REC", "Установка модели…")
                val useBig = SettingsStore.getBigModel(this) && ModelDownloader.isReady(this)
                val path = if (useBig) ModelDownloader.resolveModelDir(this)!! else ModelInstaller.ensureModel(this)
                bigModelActive = useBig
                Logger.log("MODEL", if (useBig) "Большая модель: $path" else "Малая модель: $path")
                val m = Model(path)
                Logger.log("REC", "Модель загружена, запуск прослушивания")
                handler.post { model = m; startListening(grammar = true); refreshNotification() }
            } catch (e: Exception) {
                Logger.log("REC", "Ошибка модели: ${e.message}")
                handler.post { VoiceAccessibilityService.instance?.showStatus("Ошибка модели: ${e.message}") }
            }
        }.start()
    }

    private fun startListening(grammar: Boolean) {
        val useGrammar = grammar && !bigModelActive   // большая модель статична — только свободный режим
        val rec = if (useGrammar)
            Recognizer(model, 16000.0f, Vocabulary.buildGrammar(personal, wakeWord))
        else
            Recognizer(model, 16000.0f)
        speechService = SpeechService(rec, 16000.0f)
        speechService?.startListening(this)
        Logger.log("REC", "Слушаю (грамматика=$useGrammar)")
    }

    private fun restart(grammar: Boolean) {
        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        speechService = null
        handler.postDelayed({ startListening(grammar) }, 200)
    }

    fun enterDictation(digits: Boolean = false) {
        dictation = true
        dictationDigits = digits
        dictBuffer.setLength(0)
        val hint = if (digits) "Диктовка цифрами: говорите номер, «готово» — выход"
        else "Диктовка: говорите текст, «готово» — выход"
        VoiceAccessibilityService.instance?.showStatus(hint)
        restart(grammar = false)
        resetIdle(); refreshNotification()
    }

    private fun exitDictation() {
        dictation = false
        dictationDigits = false
        dictBuffer.setLength(0)
        VoiceAccessibilityService.instance?.showStatus("Команды активны")
        restart(grammar = true)
        resetIdle(); refreshNotification()
    }

    private fun resetIdle() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, idleMs)
    }

    override fun onResult(hypothesis: String?) {
        val raw = hypothesis?.let { JSONObject(it).optString("text") } ?: return
        val text = raw.replace("[unk]", " ").trim()
        if (text.isBlank()) return

        // Во время видео/музыки: работает только «слово активации + команда потока»
        if (ignoreMedia && audioManager.isMusicActive()) {
            if (text.contains(wakeWord)) {
                val rest = stripWake(text)
                val c = CommandParser.parseMedia(rest)
                if (c != null) {
                    Logger.log("MEDIA", "Команда при медиа: ${c.label()}")
                    post { VoiceAccessibilityService.instance?.execute(c) }
                } else {
                    VoiceAccessibilityService.instance?.showStatus(
                        "При медиа: ${cap(wakeWord)} + пауза/играй/громче/тише/назад"
                    )
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
            resetIdle()
            val chunk = if (dictationDigits) NumberWords.toDigits(text) else text
            if (chunk.isNotBlank()) {
                if (!dictationDigits && dictBuffer.isNotEmpty()) dictBuffer.append(" ")
                dictBuffer.append(chunk)
                val full = dictBuffer.toString()
                post { VoiceAccessibilityService.instance?.setFieldText(full) }
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
                VoiceAccessibilityService.instance?.showStatus("${cap(wakeWord)} слушает")
                return
            }
            handleCommand(rest)
            return
        }

        if (state == State.ASLEEP) return
        resetIdle()
        if (paused) { VoiceAccessibilityService.instance?.showStatus("Пауза. Скажите: слушай"); return }
        handleCommand(text)
    }

    private var lastCmdText: String? = null

    private fun handleCommand(text: String) {
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

    override fun onError(e: Exception?) { Logger.log("REC", "Ошибка: ${e?.message}") }
    override fun onTimeout() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(idleRunnable)
        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        model?.close()
        instance = null
    }
}
