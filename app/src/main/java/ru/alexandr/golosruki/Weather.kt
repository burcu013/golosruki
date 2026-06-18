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

    @Volatile private var lastSummary: String = ""
    @Volatile private var lastAt: Long = 0L

    /** Последняя успешно полученная сводка текущей погоды, если не старше maxAgeMs (для подмешивания в диалог). */
    fun cached(maxAgeMs: Long = 2 * 60 * 60 * 1000L): String? =
        if (lastSummary.isNotBlank() && System.currentTimeMillis() - lastAt < maxAgeMs) lastSummary else null

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
            val out = "Сейчас ${desc(code)}, $temp°C (ощущается как $feels°C), ветер $wind км/ч."
            lastSummary = out; lastAt = System.currentTimeMillis()
            out
        } catch (e: Exception) {
            "Не удалось получить погоду — проверьте интернет."
        }
    }

    /** Прогноз на день: offset 1 = завтра, 2 = послезавтра. */
    fun daySummary(ctx: Context, offset: Int): String {
        if (lastLocation(ctx) == null) return NO_LOC
        val d = dailyOrNull(ctx, offset + 1) ?: return NET_FAIL
        return try {
            val times = d.getJSONArray("time")
            "${labelCap(offset, times.getString(offset))}: ${dayLine(d, offset)}."
        } catch (e: Exception) { NET_FAIL }
    }

    fun tomorrow(ctx: Context): String = daySummary(ctx, 1)

    /** Прогноз на ближайшие дни (завтра и далее). */
    fun week(ctx: Context): String {
        if (lastLocation(ctx) == null) return NO_LOC
        val d = dailyOrNull(ctx, 7) ?: return NET_FAIL
        return try {
            val times = d.getJSONArray("time")
            val sb = StringBuilder("Погода на ближайшие дни. ")
            for (i in 1 until times.length()) {
                sb.append(labelCap(i, times.getString(i))).append(": ").append(dayLine(d, i)).append(". ")
            }
            sb.toString().trim()
        } catch (e: Exception) { NET_FAIL }
    }

    private const val NO_LOC = "Не могу определить местоположение для погоды. Нужны разрешение «Геолокация» и включённый GPS."
    private const val NET_FAIL = "Не удалось получить погоду — проверьте интернет."

    private fun dailyOrNull(ctx: Context, days: Int): JSONObject? {
        val loc = lastLocation(ctx) ?: return null
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}" +
            "&longitude=${loc.longitude}&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&forecast_days=$days&timezone=auto"
        return try { JSONObject(httpGet(url)).getJSONObject("daily") } catch (e: Exception) { null }
    }

    private fun dayLine(d: JSONObject, i: Int): String {
        val code = d.getJSONArray("weather_code").getInt(i)
        val tmax = d.getJSONArray("temperature_2m_max").getDouble(i).roundToInt()
        val tmin = d.getJSONArray("temperature_2m_min").getDouble(i).roundToInt()
        return "${desc(code)}, от $tmin до $tmax°C"
    }

    private fun labelCap(offset: Int, dateStr: String): String = when (offset) {
        1 -> "Завтра"
        2 -> "Послезавтра"
        else -> weekday(dateStr)
    }

    private fun weekday(dateStr: String): String = try {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale("ru", "RU")).parse(dateStr)
        java.text.SimpleDateFormat("EEEE", java.util.Locale("ru", "RU")).format(date!!)
            .replaceFirstChar { it.uppercase() }
    } catch (e: Exception) { "Этот день" }

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
