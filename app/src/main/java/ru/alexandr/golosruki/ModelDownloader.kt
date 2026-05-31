package ru.alexandr.golosruki

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Скачивание и распаковка большой русской модели Vosk.
 * Поддерживает докачку (HTTP Range) и отмену. Вызывается из ModelDownloadService.
 */
object ModelDownloader {
    const val URL_BIG = "https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip"
    private const val DIR = "model-ru-big"
    private const val ZIP = "model-big.zip"

    @Volatile var running = false
    @Volatile var cancelRequested = false
    @Volatile var lastError: String? = null
    @Volatile var progressPct = 0
    @Volatile var phase = ""

    fun isReady(ctx: Context): Boolean = resolveModelDir(ctx) != null

    /** Путь к готовой большой модели (папка с conf/am) или null. */
    fun resolveModelDir(ctx: Context): String? {
        val root = File(ctx.filesDir, DIR)
        if (!root.isDirectory) return null
        if (File(root, "conf").isDirectory || File(root, "am").isDirectory) return root.absolutePath
        root.listFiles()?.forEach { d ->
            if (d.isDirectory && (File(d, "conf").isDirectory || File(d, "am").isDirectory)) return d.absolutePath
        }
        return null
    }

    fun delete(ctx: Context) {
        File(ctx.filesDir, DIR).deleteRecursively()
        File(ctx.filesDir, ZIP).delete()
    }

    /**
     * Одна попытка: докачать архив (с места обрыва), распаковать. Синхронно (фоновый поток).
     * onUpdate вызывается при изменении прогресса. true — успех, false — отмена или ошибка (см. lastError).
     */
    fun runOnce(ctx: Context, onUpdate: () -> Unit): Boolean {
        val zip = File(ctx.filesDir, ZIP)
        val outDir = File(ctx.filesDir, DIR)
        // --- 1) Скачивание с докачкой ---
        try {
            var existing = if (zip.exists()) zip.length() else 0L
            val conn = (URL(URL_BIG).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000; readTimeout = 30000; instanceFollowRedirects = true
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            conn.connect()
            val code = conn.responseCode
            val append: Boolean
            val total: Long
            when {
                code == 206 -> { append = true; total = existing + conn.contentLengthLong }
                code in 200..299 -> { append = false; existing = 0L; total = conn.contentLengthLong }
                else -> throw IOException("HTTP $code")
            }
            phase = "Скачивание"; progressPct = if (total > 0) (existing * 80 / total).toInt() else 0; onUpdate()
            FileOutputStream(zip, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024); var read: Int; var sum = existing; var lastP = -1
                    while (input.read(buf).also { read = it } > 0) {
                        if (cancelRequested) return false
                        out.write(buf, 0, read); sum += read
                        if (total > 0) {
                            val p = (sum * 80 / total).toInt()
                            if (p != lastP) { lastP = p; progressPct = p; onUpdate() }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            lastError = e.message ?: "сбой сети"
            return false   // частичный архив остаётся — докачаем при следующей попытке
        }
        if (cancelRequested) return false
        // --- 2) Распаковка ---
        try {
            phase = "Распаковка"; progressPct = 82; onUpdate()
            outDir.deleteRecursively(); outDir.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (cancelRequested) return false
                    val f = File(outDir, entry.name)
                    if (entry.isDirectory) f.mkdirs()
                    else { f.parentFile?.mkdirs(); FileOutputStream(f).use { zis.copyTo(it) } }
                    zis.closeEntry(); entry = zis.nextEntry
                }
            }
            if (resolveModelDir(ctx) == null) throw IOException("в архиве не найдена модель")
            zip.delete()
            phase = "Готово"; progressPct = 100; onUpdate()
            return true
        } catch (e: Exception) {
            lastError = e.message ?: "ошибка распаковки"
            zip.delete()           // архив битый — скачаем заново
            outDir.deleteRecursively()
            return false
        }
    }
}
