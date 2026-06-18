package ru.alexandr.golosruki

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Мгновенные ответы из данных устройства (без ИИ-модели): время, дата, день недели, заряд.
 * Возвращает строку ответа или null, если вопрос не про эти данные (тогда — в ИИ).
 */
object LocalFacts {

    private val ru = Locale("ru", "RU")

    fun answer(ctx: Context, raw: String): String? {
        val t = raw.lowercase()

        // Заряд батареи. ВАЖНО: «зарядка/разминка/упражнения» — это НЕ про батарею (была ложная сработка
        // на «придумай план для зарядки»). Требуем явный батарейный контекст и исключаем «зарядк/разрядк».
        val exerciseCtx = t.contains("зарядк") || t.contains("разминк") || t.contains("упражнен") ||
            t.contains("тренировк") || t.contains("разрядк")
        val batteryCtx = t.contains("батаре") || t.contains("аккумулятор") ||
            (t.contains("заряд") && !exerciseCtx)
        if (batteryCtx && !exerciseCtx) {
            val (level, charging) = battery(ctx)
            if (level < 0) return "Не могу определить уровень заряда."
            val chg = if (charging) " Телефон заряжается." else ""
            return if (t.contains("заряжает")) {
                if (charging) "Да, телефон заряжается. Сейчас $level%." else "Нет, телефон не заряжается. Заряд $level%."
            } else "Заряд батареи $level%.$chg"
        }

        // Время
        if (t.contains("который час") || t.contains("сколько на часах") ||
            (t.contains("сколько") && t.contains("врем")) || t.contains("точное время")) {
            return "Сейчас ${SimpleDateFormat("HH:mm", ru).format(Date())}."
        }

        // Дата / число
        if ((t.contains("какое") && (t.contains("число") || t.contains("дата"))) ||
            (t.contains("какая") && t.contains("дата")) ||
            (t.contains("число") && t.contains("сегодня")) ||
            t.contains("сегодняшнюю дату") || t.contains("сегодняшняя дата")) {
            val d = SimpleDateFormat("d MMMM yyyy 'года'", ru).format(Date())
            val day = SimpleDateFormat("EEEE", ru).format(Date())
            return "Сегодня $d, $day."
        }

        // День недели
        if (t.contains("день недели") || (t.contains("какой") && t.contains("сегодня") && t.contains("день"))) {
            return "Сегодня ${SimpleDateFormat("EEEE", ru).format(Date())}."
        }

        return null
    }

    /** Уровень заряда (%) и признак зарядки. level = -1, если не удалось. */
    private fun battery(ctx: Context): Pair<Int, Boolean> {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            level to charging
        } catch (e: Exception) { -1 to false }
    }
}
