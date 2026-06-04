package ru.alexandr.golosruki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Фоновая загрузка моделей ИИ. Работает как foreground-сервис: не отменяется при выходе
 * из окна, показывает в шторке прогресс, объём и скорость. По завершении делает модель активной.
 */
class ModelDownloadService : Service() {

    companion object {
        const val CH = "golosruki_dl"
        const val NID = 7720
        const val ACTION_STOP = "ru.alexandr.golosruki.DL_STOP"
        const val ACTION_PROGRESS = "ru.alexandr.golosruki.DL_PROGRESS"
        const val EX_URL = "url"; const val EX_TOKEN = "token"; const val EX_DEST = "dest"; const val EX_LABEL = "label"
        @Volatile var active = false
        @Volatile var statusText = ""
        @Volatile var lastResult: String? = null   // null — нет данных; "OK" — успех; иначе текст ошибки
        @Volatile private var cancelFlag = false
    }

    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()
        if (intent?.action == ACTION_STOP) { cancelFlag = true; statusText = "Отмена…"; return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        val url = intent?.getStringExtra(EX_URL) ?: return stopNow()
        val token = intent.getStringExtra(EX_TOKEN) ?: ""
        val dest = intent.getStringExtra(EX_DEST) ?: return stopNow()
        val label = intent.getStringExtra(EX_LABEL) ?: "модель"
        cancelFlag = false; active = true; lastResult = null; statusText = "Подготовка…"
        startForeground(NID, build(label, "Подготовка…", 0, true))
        Thread { run(url, token, dest, label) }.start()
        return START_NOT_STICKY
    }

    private fun stopNow(): Int { stopSelf(); return START_NOT_STICKY }

    private fun run(url: String, token: String, dest: String, label: String) {
        val dstFile = File(dest); dstFile.parentFile?.mkdirs()
        val tmp = File("$dest.part")
        val res = runCatching {
            var u = url
            var conn = open(u, token)
            var hops = 0
            while (conn.responseCode in 300..399 && hops < 6) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect(); u = loc
                conn = open(u, if (u.contains("huggingface.co")) token else ""); hops++
            }
            when (val code = conn.responseCode) {
                200 -> {}
                401, 403 -> throw RuntimeException("Нет доступа ($code). Проверьте токен HF и лицензию модели.")
                404 -> throw RuntimeException("Файл не найден (404). Имя файла другое — возьмите ссылку из вкладки Files.")
                else -> throw RuntimeException("HTTP $code")
            }
            val total = conn.contentLengthLong
            var done = 0L; var winBytes = 0L; var winStart = System.currentTimeMillis(); var speed = 0.0; var lastUi = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20); var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        if (cancelFlag) throw RuntimeException("Отменено")
                        out.write(buf, 0, n); done += n; winBytes += n
                        val now = System.currentTimeMillis()
                        if (now - winStart >= 1000) { speed = winBytes * 1000.0 / (now - winStart); winBytes = 0; winStart = now }
                        if (now - lastUi >= 700) {
                            lastUi = now
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            val txt = buildString {
                                if (total > 0) append("$pct% · ")
                                append(mb(done)); if (total > 0) append(" из ${mb(total)}")
                                if (speed > 0) append(" · ${mbs(speed)}")
                            }
                            statusText = txt
                            nm.notify(NID, build(label, txt, pct, total <= 0))
                            sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName))
                        }
                    }
                }
            }
            conn.disconnect()
            if (dstFile.exists()) dstFile.delete()
            tmp.renameTo(dstFile)
            dstFile.absolutePath
        }
        if (res.isSuccess) {
            SettingsStore.setAiModelPath(this, res.getOrThrow())
            runCatching { LocalAi.engine.unload(); LocalAi.clearHistory() }
            lastResult = "OK"; statusText = "Готово ✅ «$label» установлена"
            notifyDone("Готово ✅", "«$label» скачана и выбрана")
        } else {
            tmp.delete()
            val msg = res.exceptionOrNull()?.message ?: "ошибка"
            lastResult = msg; statusText = "Не вышло: $msg"
            notifyDone("Загрузка не удалась", msg)
        }
        active = false
        sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun open(url: String, token: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 30000; c.readTimeout = 120000
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        c.connect(); return c
    }

    private fun mb(b: Long) = if (b >= 1024L * 1024 * 1024) String.format("%.2f ГБ", b / 1073741824.0) else "${b / 1048576} МБ"
    private fun mbs(bps: Double) = if (bps >= 1024 * 1024) String.format("%.1f МБ/с", bps / 1048576.0) else String.format("%.0f КБ/с", bps / 1024.0)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Загрузка моделей ИИ", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun build(label: String, text: String, pct: Int, indet: Boolean): Notification {
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 2,
            Intent(this, ModelsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Скачивание: $label")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, pct, indet)
            .setContentIntent(openPi)
            .addAction(0, "Отмена", stopPi)
            .build()
    }

    private fun notifyDone(title: String, text: String) {
        val openPi = PendingIntent.getActivity(this, 2,
            Intent(this, ModelsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NID + 1, NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(openPi).build())
    }
}
