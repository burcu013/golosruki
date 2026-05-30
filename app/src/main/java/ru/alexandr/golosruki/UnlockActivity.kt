package ru.alexandr.golosruki

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/** Будит экран и снимает блокировку.
 *  Примечание: PIN/графический ключ/пароль программно НЕ обходятся (защита Android).
 *  Для полной разблокировки голосом блокировка экрана должна быть «Нет» или «Свайп». */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= 26) {
            km.requestDismissKeyguard(this, null)
        }

        // Закрываем активность, чтобы она не висела поверх экрана
        window.decorView.postDelayed({ finish() }, 1500)
    }
}
