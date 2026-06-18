package ru.alexandr.golosruki

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Запускает голосовую службу после перезагрузки телефона (best-effort). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            a == "android.intent.action.QUICKBOOT_POWERON") {
            runCatching {
                val i = Intent(context, VoiceRecognitionService::class.java)
                    .setAction(ReminderScheduler.ACTION_REARM)   // полный старт + перевзвод напоминаний/брифинга
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }
    }
}
