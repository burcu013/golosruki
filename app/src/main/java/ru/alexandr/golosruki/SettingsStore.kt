package ru.alexandr.golosruki

import android.content.Context
import org.json.JSONObject

/** Хранилище персональных настроек (контакты, SOS, слово активации, тайм-аут сна). */
object SettingsStore {
    private const val PREF = "golosruki"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getWake(ctx: Context): String = p(ctx).getString("wake", "иван")?.ifBlank { "иван" } ?: "иван"
    fun setWake(ctx: Context, v: String) = p(ctx).edit().putString("wake", v.lowercase().trim()).apply()

    fun getIdle(ctx: Context): Int = p(ctx).getInt("idle", 30)
    fun setIdle(ctx: Context, v: Int) = p(ctx).edit().putInt("idle", v).apply()

    fun getSosNumber(ctx: Context): String = p(ctx).getString("sos_num", "") ?: ""
    fun setSosNumber(ctx: Context, v: String) = p(ctx).edit().putString("sos_num", v.trim()).apply()

    fun getSosText(ctx: Context): String = p(ctx).getString("sos_text", "") ?: ""
    fun setSosText(ctx: Context, v: String) = p(ctx).edit().putString("sos_text", v).apply()

    fun getIgnoreMedia(ctx: Context): Boolean = p(ctx).getBoolean("ignore_media", true)
    fun setIgnoreMedia(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ignore_media", v).apply()

    /** Контакты: имя(дат. падеж) -> номер. */
    fun getContacts(ctx: Context): Map<String, String> {
        val raw = p(ctx).getString("contacts", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val m = mutableMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) { val k = it.next(); m[k.lowercase()] = obj.optString(k) }
            m
        } catch (e: Exception) { emptyMap() }
    }

    fun setContacts(ctx: Context, contacts: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in contacts) if (k.isNotBlank() && v.isNotBlank()) obj.put(k.lowercase().trim(), v.trim())
        p(ctx).edit().putString("contacts", obj.toString()).apply()
    }

    /** Кастомные команды запуска: фраза -> package. */
    fun getOpenCommands(ctx: Context): Map<String, String> {
        val raw = p(ctx).getString("open_cmds", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val m = mutableMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) { val k = it.next(); m[k.lowercase()] = obj.optString(k) }
            m
        } catch (e: Exception) { emptyMap() }
    }

    fun setOpenCommands(ctx: Context, cmds: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in cmds) if (k.isNotBlank() && v.isNotBlank()) obj.put(k.lowercase().trim(), v.trim())
        p(ctx).edit().putString("open_cmds", obj.toString()).apply()
    }
}
