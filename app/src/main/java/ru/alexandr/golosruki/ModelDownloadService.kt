package ru.alexandr.golosruki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Фоновая загрузка большой модели: статус в шторке, авто-докачка при сбоях, действие «Отмена». */
class ModelDownloadService : Service() {

    companion object {
        const val ACTION_START = "ru.alexandr.golosruki.DL_START"
        const val ACTION_CANCEL = "ru.alexandr.golosruki.DL_CANCEL"
        private const val CH = "model_dl"
        private const val NID = 4242
        private const val NID_DONE = 4243
        private const val MAX_ATTEMPTS = 1000   // повторяем почти бесконечно, пока не выйдет или «Отмена»
    }

    private val handler = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        when (intent?.action) {
            ACTION_CANCEL -> {
                ModelDownloader.cancelRequested = true
                nm.cancel(NID)
                stopForegroundCompat(); stopSelf()
                return START_NOT_STICKY
            }
            else -> startDownload()
        }
        return START_STICKY
    }

    private fun startDownload() {
        startForegroundCompat(buildProgress(0, "Подготовка…"))
        if (ModelDownloader.running) return
        ModelDownloader.running = true
        ModelDownloader.cancelRequested = false
        ModelDownloader.lastError = null
        Thread {
            var attempt = 0
            var ok = false
            while (!ModelDownloader.cancelRequested && attempt < MAX_ATTEMPTS) {
                attempt++
                ok = ModelDownloader.runOnce(this) {
                    handler.post { nm.notify(NID, buildProgress(ModelDownloader.progressPct, ModelDownloader.phase)) }
                }
                if (ok || ModelDownloader.cancelRequested) break
                // ошибка — показываем и ждём перед повтором
                handler.post {
                    nm.notify(NID, buildRetry(attempt, ModelDownloader.lastError ?: "сбой"))
                }
                var waited = 0
                while (waited < 15000 && !ModelDownloader.cancelRequested) { Thread.sleep(500); waited += 500 }
            }
            ModelDownloader.running = false
            handler.post {
                nm.cancel(NID)
                if (ok) {
                    SettingsStore.setBigModel(this, true)   // включаем автоматически
                    nm.notify(NID_DONE, buildDone())
                    // перезагружаем модель в голосовой службе (если она запущена)
                    val r = Intent(this, VoiceRecognitionService::class.java)
                        .setAction(VoiceRecognitionService.ACTION_RELOAD)
                    runCatching { startService(r) }
                }
                stopForegroundCompat(); stopSelf()
            }
        }.start()
    }

    // --- Уведомления ---
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "Загрузка модели", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun cancelAction(): Notification.Action {
        val pi = PendingIntent.getService(
            this, 1,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", pi).build()
    }

    private fun base(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun buildProgress(pct: Int, phase: String): Notification {
        val b = base()
            .setContentTitle("Загрузка большой модели")
            .setContentText(if (phase == "Распаковка") "Распаковка…" else "$phase… $pct%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(cancelAction())
        b.setProgress(100, pct.coerceIn(0, 100), phase == "Распаковка" && pct >= 82)
        return b.build()
    }

    private fun buildRetry(attempt: Int, err: String): Notification {
        return base()
            .setContentTitle("Повтор загрузки (попытка $attempt)")
            .setContentText("Сбой: $err. Продолжу автоматически…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(cancelAction())
            .build()
    }

    private fun buildDone(): Notification {
        val pi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return base()
            .setContentTitle("Большая модель готова ✅")
            .setContentText("Точное распознавание включено. Подробности — в Гайде.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NID, n)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }
}
