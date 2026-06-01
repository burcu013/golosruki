package ru.alexandr.golosruki

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/** Наложение поверх любых приложений через TYPE_ACCESSIBILITY_OVERLAY. */
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: OverlayView? = null

    private fun ensureView() {
        if (view != null) return
        val v = OverlayView(context)
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 22)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, params)
        view = v
    }

    fun showStatus(text: String) { ensureView(); view?.setStatus(text, 1) }
    fun showStatusMultiline(text: String, maxLines: Int) { ensureView(); view?.setStatus(text, maxLines) }
    fun setStatusIcon(icon: OverlayView.Icon) { ensureView(); view?.setIcon(icon) }
    fun showNumbers(list: List<Pair<Int, Rect>>) {
        ensureView(); view?.setTargets(list.map { OverlayView.Target(it.first, it.second) }, false)
    }
    fun showGrid(list: List<Pair<Int, Rect>>) {
        ensureView(); view?.setTargets(list.map { OverlayView.Target(it.first, it.second) }, true)
    }
    fun showHelp() {
        ensureView()
        view?.setStatus(
            "Иван — разбудить. Команды: номера · нажми N · нажми «надпись» · вниз / чуть вниз · " +
                "в самый низ · открой X · позвони X · громкость N · медиа · диктовка · копировать · сос · домой · назад",
            3
        )
    }
    fun clearTargets() { view?.clearTargets() }

    /** Держать экран включённым через флаг окна наложения. */
    fun setKeepScreenOn(on: Boolean) {
        val v = view ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (on)
            lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else
            lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        runCatching { wm.updateViewLayout(v, lp) }
    }

    fun clearAll() { view?.let { runCatching { wm.removeView(it) } }; view = null }
}
