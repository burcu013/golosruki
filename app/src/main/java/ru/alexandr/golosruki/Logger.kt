package ru.alexandr.golosruki

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Логгер для разработки: память (показ на экране) + файл (история для анализа/тестировщиков).
 * Запись в файл идёт в отдельном потоке — на скорость распознавания НЕ влияет.
 * Файл ограничен по размеру с ротацией (хранится текущий + один предыдущий).
 */
object Logger {
    private val buf = ArrayDeque<String>()
    private const val MAX = 4000                 // строк в памяти (для экрана «Логи»)
    private const val FILE_MAX = 1_500_000L      // ~1.5 МБ, затем ротация
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        runCatching {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            file = File(dir, "golosruki_log.txt")
        }
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
        android.util.Log.d("GolosRuki", line)
        val f = file ?: return
        io.execute {
            runCatching {
                if (f.exists() && f.length() > FILE_MAX) {
                    val bak = File(f.parentFile, "golosruki_log.1.txt")
                    if (bak.exists()) bak.delete()
                    f.renameTo(bak)
                }
                f.appendText(line + "\n")
            }
        }
    }

    @Synchronized
    fun dump(): String = if (buf.isEmpty()) "Лог пуст" else buf.joinToString("\n")

    /** Полная история для экспорта: предыдущий файл (если был) + текущий. */
    fun fullText(): String {
        val f = file ?: return dump()
        return runCatching {
            val bak = File(f.parentFile, "golosruki_log.1.txt")
            val sb = StringBuilder()
            if (bak.exists()) sb.append(bak.readText())
            if (f.exists()) sb.append(f.readText())
            if (sb.isEmpty()) dump() else sb.toString()
        }.getOrDefault(dump())
    }

    @Synchronized
    fun clear() {
        buf.clear()
        val f = file ?: return
        io.execute {
            runCatching {
                f.writeText("")
                File(f.parentFile, "golosruki_log.1.txt").delete()
            }
        }
    }
}
