package ru.alexandr.golosruki

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
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
    private val listener: RecognitionListener,
    private val ctx: Context
) {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private val main = Handler(Looper.getMainLooper())

    // Программный фронт-энд (до Vosk): high-pass от гула + AGC, если аппаратного AGC нет.
    private var swAgc = false
    private var agcGain = 1.0f
    private var hpPrevIn = 0
    private var hpPrevOut = 0f

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

        // Запись с обычного (системного) микрофона. SCO/«телефонный» канал не используем,
        // поэтому музыка и звонки работают штатно. Если система сама направляет вход на гарнитуру — ок.

        val sid = rec.audioSessionId
        var fx = ""
        if (AcousticEchoCanceler.isAvailable()) { aec = runCatching { AcousticEchoCanceler.create(sid)?.apply { enabled = true } }.getOrNull(); if (aec != null) fx += "AEC " }
        if (NoiseSuppressor.isAvailable()) { ns = runCatching { NoiseSuppressor.create(sid)?.apply { enabled = true } }.getOrNull(); if (ns != null) fx += "NS " }
        if (AutomaticGainControl.isAvailable()) { agc = runCatching { AutomaticGainControl.create(sid)?.apply { enabled = true } }.getOrNull(); if (agc != null) fx += "AGC " }
        // Если аппаратного AGC нет — включаем программный (важно для тихой/дальней речи).
        swAgc = (agc == null)
        if (swAgc) fx += "AGC(прогр.) "
        fx += "HPF "   // программный high-pass от низкочастотного гула — всегда
        Logger.log("NS", "Звуковой фронт-энд: ${if (fx.isBlank()) "нет (источник VOICE_COMMUNICATION)" else fx.trim()}")

        running = true
        runCatching { rec.startRecording() }
        // Диагностика: с какого микрофона реально идёт запись (BT_SCO или встроенный).
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            val rd = rec.routedDevice
            val name = when (rd?.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO (гарнитура) ✅"
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "ВСТРОЕННЫЙ микрофон"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "проводная гарнитура"
                null -> "неизвестно"
                else -> "тип${rd.type}"
            }
            Logger.log("MIC", "Запись идёт с: $name")
        }
        thread = Thread {
            val buffer = ShortArray(3200)  // ~0.2 c
            while (running) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n > 0 && !paused) {
                    enhance(buffer, n)   // фронт-энд: high-pass + (при необходимости) программный AGC
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

    /** Звуковой фронт-энд перед Vosk: high-pass от гула + мягкий AGC (если нет аппаратного). */
    private fun enhance(buf: ShortArray, n: Int) {
        // 1) One-pole high-pass (DC-blocker, ~срез ниже ~60 Гц) — убирает низкочастотный гул/рокот.
        val a = 0.97f
        for (i in 0 until n) {
            val x = buf[i].toInt()
            val y = a * (hpPrevOut + x - hpPrevIn)
            hpPrevIn = x
            hpPrevOut = y
            buf[i] = y.toInt().coerceIn(-32768, 32767).toShort()
        }
        // 2) Программный AGC по пику — только если нет аппаратного.
        if (!swAgc) return
        var peak = 1
        for (i in 0 until n) { val v = kotlin.math.abs(buf[i].toInt()); if (v > peak) peak = v }
        if (peak < 250) return            // тишина/шумовой пол — не усиливаем (иначе раздуем шум)
        val target = 9000                 // целевой пик из 32767 (комфортный уровень для ASR)
        val desired = (target.toFloat() / peak).coerceIn(0.5f, 6.0f)
        agcGain += (desired - agcGain) * 0.25f   // плавная атака, без «дёрганья»
        if (kotlin.math.abs(agcGain - 1.0f) < 0.05f) return
        for (i in 0 until n) {
            buf[i] = (buf[i] * agcGain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

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
