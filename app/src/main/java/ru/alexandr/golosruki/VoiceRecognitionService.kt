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
 * Слушает микрофон офлайн (Vosk).
 * Активация по имени «Иван». Через 30 сек простоя — сон (нужно снова сказать «Иван»).
 * «Иван привет» — пробуждение/разблокировка. Грамматика повышает точность.
 */
class VoiceRecognitionService : Service(), RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private lateinit var personal: PersonalConfig
    private val handler = Handler(Looper.getMainLooper())

    private enum class State { ASLEEP, AWAKE }
    @Volatile private var state = State.ASLEEP
    @Volatile private var dictation = false

    private val idleRunnable = Runnable {
        state = State.ASLEEP
        VoiceAccessibilityService.instance?.showStatus("Сон. Скажите: Иван")
    }

    companion object {
        const val CHANNEL_ID = "golosruki_voice"
        const val IDLE_MS = 30_000L
        const val WAKE = "иван"
        @Volatile var instance: VoiceRecognitionService? = null
        @Volatile private var paused = false
        fun setPaused(p: Boolean) { paused = p }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        personal = PersonalConfig.load(this)
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, notif)
        initModel()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Голосовое управление", NotificationManager.IMPORTANCE_LOW)
            )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ГолосРуки активен")
            .setContentText("Скажите «Иван» для активации")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun initModel() {
        Thread {
            try {
                Logger.log("REC", "Установка модели…")
                val path = ModelInstaller.ensureModel(this)
                val m = Model(path)
                Logger.log("REC", "Модель загружена, запуск прослушивания")
                handler.post { model = m; startListening(grammar = true) }
            } catch (e: Exception) {
                Logger.log("REC", "Ошибка модели: ${e.message}")
                handler.post {
                    VoiceAccessibilityService.instance?.showStatus("Ошибка модели: ${e.message}")
                }
            }
        }.start()
    }

    private fun startListening(grammar: Boolean) {
        val rec = if (grammar)
            Recognizer(model, 16000.0f, Vocabulary.buildGrammar(personal))
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

    /** Включить режим диктовки (свободный текст). */
    fun enterDictation() {
        dictation = true
        VoiceAccessibilityService.instance?.showStatus("Диктовка: говорите текст, «готово» — выход")
        restart(grammar = false)
        resetIdle()
    }

    private fun exitDictation() {
        dictation = false
        VoiceAccessibilityService.instance?.showStatus("Команды активны")
        restart(grammar = true)
        resetIdle()
    }

    private fun resetIdle() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, IDLE_MS)
    }

    override fun onResult(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("text") } ?: return
        if (text.isBlank()) return
        Logger.log("REC", "Распознано: '$text' (state=$state, dict=$dictation)")

        // Режим диктовки: всё печатаем, кроме слов выхода
        if (dictation) {
            if (text.contains("готово") || text.contains("конец")) { exitDictation(); return }
            resetIdle()
            post { VoiceAccessibilityService.instance?.typeText(text) }
            return
        }

        val hasWake = text.contains(WAKE)

        if (state == State.ASLEEP) {
            if (!hasWake) return                       // спим — ждём «Иван»
            state = State.AWAKE
            resetIdle()
            val rest = stripWake(text)
            if (rest.isBlank()) {
                VoiceAccessibilityService.instance?.showStatus("Иван слушает")
                return
            }
            handleCommand(rest)
        } else {                                        // AWAKE
            resetIdle()
            val cleaned = if (hasWake) stripWake(text) else text
            if (cleaned.isBlank()) return
            handleCommand(cleaned)
        }
    }

    private fun handleCommand(text: String) {
        if (paused) {
            // на паузе слушаем только «слушай/продолжи»
            if (!(text.contains("слушай") || text.contains("продолжи"))) return
        }
        val cmd = CommandParser.parse(text, personal)
        Logger.log("CMD", "Команда: ${cmd.label()}")
        post { VoiceAccessibilityService.instance?.execute(cmd) }
    }

    private fun stripWake(t: String): String = t.replace(WAKE, "").trim()

    private fun post(action: () -> Unit) = handler.post(action)

    override fun onFinalResult(hypothesis: String?) { onResult(hypothesis) }
    override fun onPartialResult(hypothesis: String?) {}
    override fun onError(e: Exception?) { Log.e("GolosRuki", "Ошибка: ${e?.message}") }
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
