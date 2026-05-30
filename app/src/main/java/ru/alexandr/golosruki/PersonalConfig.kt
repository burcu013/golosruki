package ru.alexandr.golosruki

import android.content.Context
import org.json.JSONObject

/** Персональные команды из assets/personal_commands.json. */
data class PersonalConfig(
    val contacts: Map<String, String>,   // имя(дат. падеж) -> номер
    val apps: Map<String, String>,        // название -> package
    val sosNumber: String,
    val sosText: String
) {
    companion object {
        fun load(context: Context): PersonalConfig {
            return try {
                val json = context.assets.open("personal_commands.json")
                    .bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                val contacts = obj.optJSONObject("контакты").toMap()
                val apps = obj.optJSONObject("приложения").toMap()
                val sos = obj.optJSONObject("sos")
                PersonalConfig(
                    contacts = contacts,
                    apps = apps,
                    sosNumber = sos?.optString("номер") ?: "",
                    sosText = sos?.optString("текст") ?: "Нужна срочная помощь"
                )
            } catch (e: Exception) {
                PersonalConfig(emptyMap(), emptyMap(), "", "Нужна срочная помощь")
            }
        }

        private fun JSONObject?.toMap(): Map<String, String> {
            if (this == null) return emptyMap()
            val m = mutableMapOf<String, String>()
            val it = keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k.startsWith("_")) continue
                m[k.lowercase()] = optString(k)
            }
            return m
        }
    }
}
