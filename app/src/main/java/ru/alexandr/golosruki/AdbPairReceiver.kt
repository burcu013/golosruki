package ru.alexandr.golosruki

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Получает код из уведомления и выполняет сопряжение + выдачу разрешения. */
class AdbPairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdbAutoPair.ACTION_CODE) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AdbAutoPair.KEY_CODE)?.toString()?.trim()
        val host = intent.getStringExtra(AdbAutoPair.EXTRA_HOST)
        val port = intent.getIntExtra(AdbAutoPair.EXTRA_PORT, 0)
        if (code.isNullOrEmpty() || host == null || port == 0) {
            AdbAutoPair.postResult(context, "Нет кода или адреса — попробуйте ещё раз.")
            return
        }
        AdbAutoPair.postResult(context, "Сопряжение…")
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            val res = runCatching { AdbManager.pairAndGrant(app, host, port, code) }
                .getOrElse { "Ошибка: ${it.message ?: it.javaClass.simpleName}" }
            AdbAutoPair.postResult(app, res)
            runCatching { AdbAutoPair.stop(app) }
            pending.finish()
        }.start()
    }
}
