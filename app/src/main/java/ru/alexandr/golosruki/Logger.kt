package ru.alexandr.golosruki

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Простой логгер с буфером, который можно показать на экране логов. */
object Logger {
    private val buf = ArrayDeque<String>()
    private const val MAX = 400
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
        android.util.Log.d("GolosRuki", line)
    }

    @Synchronized
    fun dump(): String = if (buf.isEmpty()) "Лог пуст" else buf.joinToString("\n")

    @Synchronized
    fun clear() { buf.clear() }
}
