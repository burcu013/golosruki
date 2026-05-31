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
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }

    private enum class State { ASLEEP, AWAKE }
    @Volatile private var state = State.ASLEEP
    @Volatile private var dictation = false

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
        Logger.log("REC", "Старт службы. Слово активации: '$wakeWord', сон: ${idleMs / 1000}с")

        val notif = buildNotification("Запуск…")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, notif)
        initModel()
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

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
                val path = ModelInstaller.ensureModel(this)
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
        val rec = if (grammar)
            Recognizer(model, 16000.0f, Vocabulary.buildGrammar(personal, wakeWord))
        else
            Recognizer(model, 16000.0f)
        speechService = SpeechService(rec, 16000.0f)
        speechService?.startListening(this)
        Logger.log("REC", "Слушаю (грамматика=$grammar)")
    }

    private fun restart(grammar: Boolean) {
        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        speechService = null
        handler.postDelayed({ startListening(grammar) }, 200)
    }

    fun enterDictation() {
        dictation = true
        VoiceAccessibilityService.instance?.showStatus("Диктовка: говорите текст, «готово» — выход")
        restart(grammar = false)
        resetIdle(); refreshNotification()
    }

    private fun exitDictation() {
        dictation = false
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
            post { VoiceAccessibilityService.instance?.typeText(text) }
            return
        }

        val isWake = text.contains(wakeWord)
        val isResume = text.contains("слушай") || text.contains("продолжи")

        if (isWake || isResume) {
            state = State.AWAKE
            if (paused) { setPaused(false); Logger.log("REC", "Пауза снята") }
            resetIdle()
            VoiceAccessibilityService.instance?.keepScreenOn(true)
            val rest = if (isWake) stripWake(text) else stripResume(text)
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
