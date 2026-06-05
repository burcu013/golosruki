package ru.alexandr.golosruki

import kotlin.math.abs

/**
 * Точный расчёт простой арифметики прямо в коде (без ИИ-модели).
 * Маленькие квантованные модели часто ошибаются в простых числах,
 * поэтому выражения вида «98 плюс 2», «восемь плюс два минус один»,
 * «сто двадцать пять умножить на два» считаем сами и гарантированно верно.
 *
 * Возвращает строку ответа («98 + 2 = 100») или null, если это НЕ чистое
 * арифметическое выражение (тогда вопрос уходит в ИИ как обычно).
 */
object LocalMath {

    private val ops = mapOf(
        "плюс" to '+', "прибавить" to '+', "прибавь" to '+', "сложить" to '+', "плюсовать" to '+',
        "минус" to '-', "отнять" to '-', "отними" to '-', "вычесть" to '-', "вычти" to '-',
        "умножить" to '*', "умножь" to '*', "умнож" to '*', "умноженное" to '*',
        "разделить" to '/', "раздели" to '/', "делить" to '/', "поделить" to '/', "подели" to '/'
    )

    // Служебные слова, которые можно игнорировать в выражении.
    private val fillers = setOf(
        "сколько", "будет", "это", "равно", "равняется", "чему", "посчитай", "вычисли",
        "посчитать", "получится", "сумма", "разность", "произведение", "частное", "итого",
        "и", "на", "ну", "а", "же", "сколько-то"
    )

    fun eval(raw: String): String? {
        val words = raw.lowercase()
            .replace(Regex("[?!.,;:]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val tokens = mutableListOf<Any>()   // Int (число) или Char (операция)
        val numAcc = mutableListOf<Int>()
        fun flush() { if (numAcc.isNotEmpty()) { tokens.add(foldNumber(numAcc)); numAcc.clear() } }

        var hasOp = false
        for (w in words) {
            val op = ops[w]
            when {
                w.isNotEmpty() && w.all { it.isDigit() } -> { flush(); tokens.add(w.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
                NumberWords.map.containsKey(w) -> numAcc.add(NumberWords.map[w]!!)
                op != null -> { flush(); tokens.add(op); hasOp = true }
                w in fillers -> { /* игнор */ }
                else -> return null   // незнакомое слово → это не чистая арифметика
            }
        }
        flush()

        if (!hasOp || tokens.size < 3) return null
        // Должно быть строго: ЧИСЛО (ОП ЧИСЛО)+
        if (tokens.first() !is Int || tokens.last() !is Int) return null
        for (i in tokens.indices) {
            val expectNum = i % 2 == 0
            if (expectNum && tokens[i] !is Int) return null
            if (!expectNum && tokens[i] !is Char) return null
        }

        val result = compute(tokens) ?: return "На ноль делить нельзя."
        return buildString {
            for (i in tokens.indices) {
                if (tokens[i] is Int) append(tokens[i] as Int)
                else append(" ${symbol(tokens[i] as Char)} ")
            }
            append(" = ")
            append(fmt(result))
        }
    }

    /** Свернуть последовательность числительных в одно число: [90,8]=98, [100,20,5]=125, [2,1000]=2000. */
    private fun foldNumber(vals: List<Int>): Int {
        var total = 0; var cur = 0
        for (v in vals) {
            if (v == 1000) { if (cur == 0) cur = 1; total += cur * 1000; cur = 0 }
            else cur += v
        }
        return total + cur
    }

    /** Вычислить с приоритетом × ÷ над + −. */
    private fun compute(tokens: List<Any>): Double? {
        // первый проход: умножение/деление
        val nums = mutableListOf<Double>()
        val addOps = mutableListOf<Char>()
        nums.add((tokens[0] as Int).toDouble())
        var i = 1
        while (i < tokens.size) {
            val op = tokens[i] as Char
            val n = (tokens[i + 1] as Int).toDouble()
            when (op) {
                '*' -> nums[nums.lastIndex] = nums.last() * n
                '/' -> { if (abs(n) < 1e-9) return null; nums[nums.lastIndex] = nums.last() / n }
                else -> { addOps.add(op); nums.add(n) }
            }
            i += 2
        }
        // второй проход: сложение/вычитание
        var res = nums[0]
        for (k in addOps.indices) res = if (addOps[k] == '+') res + nums[k + 1] else res - nums[k + 1]
        return res
    }

    private fun symbol(op: Char): String = when (op) {
        '+' -> "+"; '-' -> "−"; '*' -> "×"; '/' -> "÷"; else -> op.toString()
    }

    private fun fmt(v: Double): String {
        return if (abs(v - Math.round(v)) < 1e-9) Math.round(v).toString()
        else String.format("%.2f", v).trimEnd('0').trimEnd('.', ',')
    }
}
