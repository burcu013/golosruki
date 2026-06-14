package ru.alexandr.golosruki

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Облачная модель через OpenAI-совместимый эндпоинт (OpenAI, OpenRouter, свой сервер и т.п.).
 * Только текстовый диалог/составление текста. Команды, будилка и распознавание — всегда офлайн.
 * Вызывать в фоновом потоке. Возвращает текст ответа или null при любой ошибке (тогда — откат на локальную модель).
 */
object CloudAi {

    fun isConfigured(ctx: Context): Boolean =
        SettingsStore.getApiEnabled(ctx) &&
            SettingsStore.getApiUrl(ctx).isNotBlank() &&
            SettingsStore.getApiKey(ctx).isNotBlank()

    fun chat(ctx: Context, system: String, user: String): String? {
        val base = SettingsStore.getApiUrl(ctx).trim().trimEnd('/')
        val key = SettingsStore.getApiKey(ctx).trim()
        val model = SettingsStore.getApiModel(ctx).trim().ifBlank { "gpt-4o-mini" }
        if (base.isBlank()) return null
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
            put("temperature", 0.6)
            put("max_tokens", 700)
            put("stream", false)
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 13000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Logger.log("AI", "Облако HTTP $code: ${resp.take(160)}")
                return null
            }
            val choices = JSONObject(resp).optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content")?.trim()
            if (content.isNullOrBlank()) null else content
        } catch (e: Exception) {
            Logger.log("AI", "Облако ошибка: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
