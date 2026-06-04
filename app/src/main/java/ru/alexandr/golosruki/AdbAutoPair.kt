package ru.alexandr.golosruki

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Автосопряжение (Путь 3, как у Shizuku):
 * 1) ищем сервис сопряжения "_adb-tls-pairing._tcp" по mDNS (когда открыто окно сопряжения);
 * 2) как нашли — показываем уведомление с полем ввода кода;
 * 3) код вводится через уведомление, окно сопряжения при этом НЕ закрывается.
 */
object AdbAutoPair {
    const val CHANNEL = "golosruki_adb_pair"
    const val ACTION_CODE = "ru.alexandr.golosruki.ADB_PAIR_CODE"
    const val EXTRA_HOST = "host"
    const val EXTRA_PORT = "port"
    const val KEY_CODE = "code"
    const val NOTIF_ID = 7711

    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var resolving = false
    @Volatile private var posted = false

    fun start(ctx: Context, status: (String) -> Unit) {
        stop(ctx)
        resolving = false; posted = false
        ensureChannel(ctx)
        runCatching {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("golosruki-adb").apply { setReferenceCounted(false); acquire() }
        }
        val n = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = n
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String?) { status("Поиск окна сопряжения… Открой окно «Подключить с кодом».") }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("adb-tls-pairing") == true && !resolving && !posted) {
                    resolving = true
                    runCatching { n.resolveService(info, resolver(ctx, status)) }
                        .onFailure { resolving = false }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onStartDiscoveryFailed(t: String?, e: Int) { status("Не удалось начать поиск (код $e).") }
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
        }
        listener = l
        runCatching { n.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { status("mDNS недоступен. Используй ручной ввод ниже.") }
    }

    private fun resolver(ctx: Context, status: (String) -> Unit) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo?, e: Int) { resolving = false }
        override fun onServiceResolved(info: NsdServiceInfo) {
            resolving = false
            if (posted) return
            val host = info.host?.hostAddress ?: return
            val port = info.port
            posted = true
            postCodeNotification(ctx, host, port)
            status("Окно сопряжения найдено ($host:$port)! Открой шторку уведомлений и введи 6-значный код. Окно сопряжения НЕ закрывай.")
        }
    }

    private fun postCodeNotification(ctx: Context, host: String, port: Int) {
        val remote = RemoteInput.Builder(KEY_CODE).setLabel("Код сопряжения (6 цифр)").build()
        val intent = Intent(ctx, AdbPairReceiver::class.java).apply {
            action = ACTION_CODE
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(ctx, 1, intent, flags)
        val action = NotificationCompat.Action.Builder(0, "Ввести код и сопрячь", pi)
            .addRemoteInput(remote).build()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(ctx.applicationInfo.icon)
            .setContentTitle("Сопряжение ГолосРуки")
            .setContentText("Введите код из окна сопряжения (не закрывая его)")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(action)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }

    fun postResult(ctx: Context, text: String) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(ctx.applicationInfo.icon)
            .setContentTitle("Сопряжение ГолосРуки")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
            .setTimeoutAfter(12_000)   // само исчезнет через 12 c (чтобы не висело)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }

    /** Убрать уведомление сопряжения (например, после успеха). */
    fun cancelNotification(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) }
    }

    fun stop(ctx: Context) {
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
        runCatching { if (lock?.isHeld == true) lock?.release() }
        lock = null
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Сопряжение", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }
}
