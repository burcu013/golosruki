package ru.alexandr.golosruki

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Запись короткого сегмента речи с микрофона (16 кГц, моно, PCM16) с авто-остановкой по тишине.
 * Возвращает WAV-байты или null (нет речи / ошибка). Вызывать в фоновом потоке.
 * Микрофон должен быть свободен (Vosk остановлен) перед вызовом.
 */
object MicRecorder {

    private const val RATE = 16000

    fun recordWav(
        maxMs: Int = 15000,        // максимум записи
        silenceMs: Int = 1200,     // тишина после речи → стоп
        startTimeoutMs: Int = 6000, // не начал говорить → отмена
        sensitivity: Int = 5       // 1..10, выше = ловит более тихую речь (ниже порог)
    ): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val sensFactor = sensitivity.coerceIn(1, 10) / 5.0   // 0.2..2.0
        val bufSize = maxOf(minBuf, RATE / 5 * 2) // ~200 мс
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) { Logger.log("STT", "AudioRecord не создан: ${e.message}"); return null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); Logger.log("STT", "AudioRecord не инициализирован"); return null }

        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(bufSize / 2)
        var noiseFloor = 0.0
        var calibFrames = 0
        var threshold = 700.0
        var started = false
        val startT = System.currentTimeMillis()
        var lastVoiceT = startT
        // Пред-ролл: храним ~600 мс звука ДО начала речи, чтобы не срезать первый слог.
        val preRoll = ArrayList<ByteArray>()
        var preRollBytes = 0
        val preRollCap = RATE * 2 * 600 / 1000

        return try {
            rec.startRecording()
            while (true) {
                val now = System.currentTimeMillis()
                if (now - startT > maxMs) break
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
                val rms = sqrt(sum / n)

                if (calibFrames < 2) {
                    noiseFloor = if (calibFrames == 0) rms else (noiseFloor + rms) / 2
                    threshold = maxOf(600.0, noiseFloor * 2.5 + 350.0) / sensFactor
                    calibFrames++
                }

                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    bytes[i * 2] = (buf[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((buf[i].toInt() shr 8) and 0xFF).toByte()
                }

                val voiced = rms > threshold
                if (voiced && !started) {
                    started = true
                    Logger.log("STT", "Речь началась (порог ${threshold.toInt()})")
                    for (b in preRoll) pcm.write(b)   // добавляем накопленный пред-ролл
                    preRoll.clear(); preRollBytes = 0
                }
                if (voiced) lastVoiceT = now

                if (started) {
                    pcm.write(bytes)
                    if (now - lastVoiceT > silenceMs) break        // пауза после речи → стоп
                } else {
                    preRoll.add(bytes); preRollBytes += bytes.size
                    while (preRollBytes > preRollCap && preRoll.isNotEmpty()) preRollBytes -= preRoll.removeAt(0).size
                    if (now - startT > startTimeoutMs) { Logger.log("STT", "Речи не было — отмена"); break }
                }
            }
            rec.stop()
            val data = pcm.toByteArray()
            if (!started || data.size < RATE) return null            // < 0.5 с полезного звука
            wrapWav(data)
        } catch (e: Exception) {
            Logger.log("STT", "Ошибка записи: ${e.message}"); null
        } finally {
            runCatching { rec.release() }
        }
    }

    /** Короткий беззвучный WAV — для проверки доступности STT-сервиса (ключ/адрес). */
    fun silentWav(ms: Int): ByteArray = wrapWav(ByteArray(RATE * ms / 1000 * 2))

    /** Оборачивает PCM16 mono в WAV-контейнер. */
    private fun wrapWav(pcm: ByteArray): ByteArray {
        val total = 36 + pcm.size
        val byteRate = RATE * 2
        val h = ByteArrayOutputStream()
        fun str(s: String) = h.write(s.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) { h.write(v and 0xFF); h.write((v shr 8) and 0xFF); h.write((v shr 16) and 0xFF); h.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { h.write(v and 0xFF); h.write((v shr 8) and 0xFF) }
        str("RIFF"); i32(total); str("WAVE")
        str("fmt "); i32(16); i16(1); i16(1); i32(RATE); i32(byteRate); i16(2); i16(16)
        str("data"); i32(pcm.size)
        h.write(pcm)
        return h.toByteArray()
    }
}
