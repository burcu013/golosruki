package ru.alexandr.golosruki

import android.content.Context
import org.json.JSONObject

/** Персональные команды из assets/personal_commands.json. */
data class PersonalConfig(
    val contacts: Map<String, String>,   // имя(дат. падеж) -> номер
    val apps: Map<String, String>,        // название -> package («открой X»)
    val sosNumber: String,
    val sosText: String,
    val customApps: Map<String, String> = emptyMap()  // своя фраза -> package
) {
    companion object {
        fun load(context: Context): PersonalConfig {
            val asset = loadAsset(context)
            // объединяем: контакты телефона + из JSON, сверху — заданные в приложении (приоритет)
            val merged = LinkedHashMap<String, String>()
            merged.putAll(asset.contacts)
            merged.putAll(loadDeviceContacts(context))
            merged.putAll(SettingsStore.getContacts(context))
            val sosNum = SettingsStore.getSosNumber(context).ifBlank { asset.sosNumber }
            val sosTxt = SettingsStore.getSosText(context).ifBlank { asset.sosText }
            val customApps = SettingsStore.getOpenCommands(context)
            return PersonalConfig(merged, asset.apps, sosNum, sosTxt, customApps)
        }

        /** Контакты из телефонной книги (если выдано разрешение). Ключ — имя в нижнем регистре. */
        private fun loadDeviceContacts(context: Context): Map<String, String> {
            try {
                if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyMap()
                val out = LinkedHashMap<String, String>()
                val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val cols = arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                    val ni = 0; val pi = 1
                    var count = 0
                    while (c.moveToNext() && count < 250) {
                        val name = c.getString(ni) ?: continue
                        val number = c.getString(pi) ?: continue
                        // добавляем полное имя и первое слово (имя)
                        val full = name.trim().lowercase()
                        val first = full.split(" ").firstOrNull() ?: continue
                        if (first.length >= 2 && first.all { it.isLetter() } && !out.containsKey(first)) {
                            out[first] = number; count++
                        }
                    }
                }
                return out
            } catch (e: Exception) { return emptyMap() }
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
