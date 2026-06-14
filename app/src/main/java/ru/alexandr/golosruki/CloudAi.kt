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

    @Volatile var lastError: String = ""

    fun isConfigured(ctx: Context): Boolean =
        SettingsStore.getApiEnabled(ctx) &&
            SettingsStore.getApiUrl(ctx).isNotBlank() &&
            SettingsStore.getApiKey(ctx).isNotBlank()

    fun chat(ctx: Context, system: String, user: String): String? {
        var base = SettingsStore.getApiUrl(ctx).trim().trimEnd('/')
        val key = SettingsStore.getApiKey(ctx).trim()
        val model = SettingsStore.getApiModel(ctx).trim().ifBlank { "gpt-4o-mini" }
        if (base.isBlank()) { lastError = "Не указан адрес API."; return null }
        // Частая ошибка: для OpenRouter вписывают голый домен. База у него — /api/v1.
        if (base.contains("openrouter.ai") && !base.contains("/api")) base = "https://openrouter.ai/api/v1"
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
                lastError = "HTTP $code" + httpHint(code) + (parseApiError(resp)?.let { ": $it" } ?: "")
                Logger.log("AI", "Облако $endpoint → HTTP $code: ${resp.take(200)}")
                return null
            }
            val choices = JSONObject(resp).optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                lastError = parseApiError(resp) ?: "пустой ответ модели"
                return null
            }
            val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content")?.trim()
            if (content.isNullOrBlank()) { lastError = "пустой ответ модели"; return null }
            lastError = ""
            content
        } catch (e: Exception) {
            lastError = e.message ?: "ошибка сети"
            Logger.log("AI", "Облако ошибка: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpHint(code: Int): String = when (code) {
        401 -> " (неверный ключ API)"
        402 -> " (нет средств/лимит на аккаунте)"
        403 -> " (доступ запрещён)"
        404 -> " (неверный адрес API или имя модели)"
        429 -> " (слишком часто — лимит запросов)"
        else -> ""
    }

    /** Достаёт человекочитаемое сообщение об ошибке из тела ответа OpenAI-совместимого API. */
    private fun parseApiError(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }
}
