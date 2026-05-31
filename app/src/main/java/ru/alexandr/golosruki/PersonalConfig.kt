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
            val asset = loadAsset(context)
            // Настройки из приложения имеют приоритет
            val savedContacts = SettingsStore.getContacts(context)
            val contacts = if (savedContacts.isNotEmpty()) savedContacts else asset.contacts
            val sosNum = SettingsStore.getSosNumber(context).ifBlank { asset.sosNumber }
            val sosTxt = SettingsStore.getSosText(context).ifBlank { asset.sosText }
            return PersonalConfig(contacts, asset.apps, sosNum, sosTxt)
        }

        private fun loadAsset(context: Context): PersonalConfig {
            return try {
                val json = context.assets.open("personal_commands.json")
                    .bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                PersonalConfig(
                    contacts = obj.optJSONObject("контакты").toMap(),
                    apps = obj.optJSONObject("приложения").toMap(),
                    sosNumber = obj.optJSONObject("sos")?.optString("номер") ?: "",
                    sosText = obj.optJSONObject("sos")?.optString("текст") ?: "Нужна срочная помощь"
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
