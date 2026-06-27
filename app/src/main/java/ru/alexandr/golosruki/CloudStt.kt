package ru.alexandr.golosruki

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Облачное распознавание речи (Whisper) через OpenAI-совместимый /audio/transcriptions
 * (например, Groq: https://api.groq.com/openai/v1, модель whisper-large-v3).
 * Вызывать в фоновом потоке. Возвращает текст (может быть пустым) или null при ошибке.
 */
object CloudStt {

    @Volatile var lastError: String = ""

    fun isConfigured(ctx: Context): Boolean =
        SettingsStore.getSttEnabled(ctx) &&
            SettingsStore.getSttUrl(ctx).isNotBlank() &&
            SettingsStore.getSttKey(ctx).isNotBlank()

    fun transcribe(ctx: Context, wav: ByteArray): String? {
        val base = SettingsStore.getSttUrl(ctx).trim().trimEnd('/')
        val key = SettingsStore.getSttKey(ctx).trim()
        val model = SettingsStore.getSttModel(ctx).trim().ifBlank { "whisper-large-v3" }
        if (base.isBlank()) { lastError = "Не указан адрес STT."; return null }
        val endpoint = if (base.endsWith("/audio/transcriptions")) base else "$base/audio/transcriptions"
        val boundary = "----GolosRuki${System.currentTimeMillis()}"
        val nl = "\r\n"

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 8000   // v8.19: было 20с — на плохой сети висело 23с перед откатом на Vosk
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            val head = ByteArrayOutputStream()
            fun field(name: String, value: String) {
                head.write(("--$boundary$nl").toByteArray())
                head.write(("Content-Disposition: form-data; name=\"$name\"$nl$nl$value$nl").toByteArray())
            }
            field("model", model)
            field("language", "ru")
            field("response_format", "json")
            head.write(("--$boundary$nl").toByteArray())
            head.write(("Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"$nl").toByteArray())
            head.write(("Content-Type: audio/wav$nl$nl").toByteArray())
            val tail = ("$nl--$boundary--$nl").toByteArray()

            conn.outputStream.use { os ->
                os.write(head.toByteArray())
                os.write(wav)
                os.write(tail)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                lastError = "HTTP $code" + when (code) {
                    401 -> " (неверный ключ)"; 404 -> " (неверный адрес или модель)"
                    413 -> " (запись слишком большая)"; 429 -> " (лимит запросов)"; else -> ""
                } + (parseErr(resp)?.let { ": $it" } ?: "")
                Logger.log("STT", "Облако $endpoint → HTTP $code: ${resp.take(200)}")
                return null
            }
            lastError = ""
            JSONObject(resp).optString("text").trim()
        } catch (e: Exception) {
            lastError = e.message ?: "ошибка сети"
            Logger.log("STT", "Облако ошибка: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseErr(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }
}
