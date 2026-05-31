package ru.alexandr.golosruki

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Скачивание и распаковка большой русской модели Vosk (опционально, по желанию пользователя). */
object ModelDownloader {
    const val URL_BIG = "https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip"
    private const val DIR = "model-ru-big"
    private val ui = Handler(Looper.getMainLooper())
    @Volatile var running = false; private set

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
        File(ctx.filesDir, "model-big.zip").delete()
    }

    /** onProgress: 0..100. Колбэки приходят в главном потоке. */
    fun download(ctx: Context, onProgress: (Int) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        if (running) return
        running = true
        Thread {
            try {
                val zip = File(ctx.filesDir, "model-big.zip")
                val outDir = File(ctx.filesDir, DIR)
                outDir.deleteRecursively(); outDir.mkdirs()

                val conn = (URL(URL_BIG).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000; readTimeout = 30000; instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(zip).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int; var sum = 0L; var lastPct = -1
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read); sum += read
                            if (total > 0) {
                                val pct = (sum * 80 / total).toInt()   // 0..80% — скачивание
                                if (pct != lastPct) { lastPct = pct; ui.post { onProgress(pct) } }
                            }
                        }
                    }
                }
                ui.post { onProgress(82) }                              // распаковка
                ZipInputStream(zip.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val f = File(outDir, entry.name)
                        if (entry.isDirectory) f.mkdirs()
                        else { f.parentFile?.mkdirs(); FileOutputStream(f).use { zis.copyTo(it) } }
                        zis.closeEntry(); entry = zis.nextEntry
                    }
                }
                zip.delete()
                if (resolveModelDir(ctx) == null) throw Exception("в архиве не найдена модель")
                ui.post { onProgress(100) }
                running = false
                ui.post { onDone() }
            } catch (e: Exception) {
                running = false
                File(ctx.filesDir, "model-big.zip").delete()
                ui.post { onError(e.message ?: "ошибка") }
            }
        }.start()
    }
}
