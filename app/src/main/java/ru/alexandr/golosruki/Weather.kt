package ru.alexandr.golosruki

import android.content.Context
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Погода через Open-Meteo (бесплатно, без ключа) по последней известной геолокации.
 * Требует интернет и разрешение на геолокацию. Вызывать в фоновом потоке.
 */
object Weather {

    fun describe(ctx: Context): String {
        val loc = lastLocation(ctx)
            ?: return "Не могу определить местоположение для погоды. Нужны разрешение «Геолокация» и включённый GPS."
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}" +
            "&longitude=${loc.longitude}&current=temperature_2m,relative_humidity_2m," +
            "apparent_temperature,weather_code,wind_speed_10m&timezone=auto"
        return try {
            val body = httpGet(url)
            val cur = JSONObject(body).getJSONObject("current")
            val temp = cur.getDouble("temperature_2m").roundToInt()
            val feels = cur.getDouble("apparent_temperature").roundToInt()
            val code = cur.getInt("weather_code")
            val wind = cur.getDouble("wind_speed_10m").roundToInt()
            "Сейчас ${desc(code)}, $temp°C (ощущается как $feels°C), ветер $wind км/ч."
        } catch (e: Exception) {
            "Не удалось получить погоду — проверьте интернет."
        }
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun lastLocation(ctx: Context): Location? {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
    }

    /** Коды WMO → описание по-русски. */
    private fun desc(code: Int): String = when (code) {
        0 -> "ясно"
        1, 2 -> "переменная облачность"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51, 53, 55 -> "морось"
        56, 57 -> "ледяная морось"
        61, 63, 65 -> "дождь"
        66, 67 -> "ледяной дождь"
        71, 73, 75 -> "снег"
        77 -> "снежная крупа"
        80, 81, 82 -> "ливень"
        85, 86 -> "снегопад"
        95 -> "гроза"
        96, 99 -> "гроза с градом"
        else -> "переменно"
    }
}
