package ru.alexandr.golosruki

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener

/**
 * АЛЬТЕРНАТИВНЫЙ движок захвата звука с аппаратным шумо-/эхоподавлением.
 * Используется ТОЛЬКО когда включён тумблер в настройках. По умолчанию приложение
 * работает на штатном Vosk SpeechService (этот класс не задействован).
 *
 * Источник VOICE_COMMUNICATION включает платформенную обработку голоса, а
 * AcousticEchoCanceler/NoiseSuppressor/AutomaticGainControl — дополнительное подавление.
 * PCM-кадры вручную подаются в тот же Recognizer, что и в штатном режиме.
 */
class NsSpeechService(
    private val recognizer: Recognizer,
    private val sampleRate: Int,
    private val listener: RecognitionListener
) {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private val main = Handler(Looper.getMainLooper())

    /** true — запуск удался; false — нужно откатиться на штатный SpeechService. */
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufSize = maxOf(minBuf, sampleRate)  // ~1 сек запас
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Exception) {
            Logger.log("NS", "AudioRecord не создан: ${e.message}"); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { runCatching { rec.release() }; return false }
        record = rec
        val sid = rec.audioSessionId
        var fx = ""
        if (AcousticEchoCanceler.isAvailable()) { aec = runCatching { AcousticEchoCanceler.create(sid)?.apply { enabled = true } }.getOrNull(); if (aec != null) fx += "AEC " }
        if (NoiseSuppressor.isAvailable()) { ns = runCatching { NoiseSuppressor.create(sid)?.apply { enabled = true } }.getOrNull(); if (ns != null) fx += "NS " }
        if (AutomaticGainControl.isAvailable()) { agc = runCatching { AutomaticGainControl.create(sid)?.apply { enabled = true } }.getOrNull(); if (agc != null) fx += "AGC " }
        Logger.log("NS", "Шумоподавление включено. Эффекты: ${if (fx.isBlank()) "нет (источник VOICE_COMMUNICATION)" else fx}")

        running = true
        runCatching { rec.startRecording() }
        thread = Thread {
            val buffer = ShortArray(3200)  // ~0.2 c
            while (running) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n > 0 && !paused) {
                    val end = recognizer.acceptWaveForm(buffer, n)
                    if (end) {
                        val r = recognizer.result
                        main.post { listener.onResult(r) }
                    } else {
                        val p = recognizer.partialResult
                        main.post { listener.onPartialResult(p) }
                    }
                }
            }
        }.also { it.start() }
        return true
    }

    /** Пауза распознавания (анти-петля во время TTS): читаем, но не подаём в распознаватель. */
    fun setPause(p: Boolean) { paused = p }

    fun stop() {
        running = false
        runCatching { thread?.join(300) }
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        record = null; aec = null; ns = null; agc = null
        runCatching { val fr = recognizer.finalResult; main.post { listener.onFinalResult(fr) } }
    }

    fun shutdown() = stop()
}
