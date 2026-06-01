package ru.alexandr.golosruki

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/** Прозрачное наложение: номера на элементах, сетка и строка статуса. */
class OverlayView(context: Context) : View(context) {

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // чтобы корректно рисовалась тень статуса

    data class Target(val number: Int, val rect: Rect)

    private var targets: List<Target> = emptyList()
    private var gridMode: Boolean = false
    private var status: String = ""

    private val badgeBg = Paint().apply { color = Color.parseColor("#E6E8923B"); isAntiAlias = true }
    private val badgeText = Paint().apply {
        color = Color.WHITE; textSize = 40f; isAntiAlias = true
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val gridLine = Paint().apply {
        color = Color.parseColor("#88E8923B"); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val statusBg = Paint().apply {
        color = Color.parseColor("#F21C1E22"); isAntiAlias = true
        setShadowLayer(16f, 0f, 4f, Color.parseColor("#80000000"))
    }
    private val statusAccent = Paint().apply { color = Color.parseColor("#E8923B"); isAntiAlias = true }
    private val statusText = Paint().apply {
        color = Color.parseColor("#F2F2F0"); textSize = 38f; isAntiAlias = true
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (statusText.measureText(s) <= maxW) return s
        var str = s
        while (str.length > 4 && statusText.measureText("$str…") > maxW) str = str.dropLast(1)
        return "$str…"
    }

    fun setTargets(list: List<Target>, grid: Boolean) {
        targets = list; gridMode = grid; invalidate()
    }
    fun clearTargets() { targets = emptyList(); gridMode = false; invalidate() }
    fun setStatus(text: String) { status = text; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Координаты целей — физические (экранные). Сдвигаем рисование на положение окна,
        // чтобы бейдж совпадал с точкой, куда реально уходит жест/клик.
        val loc = IntArray(2); getLocationOnScreen(loc)
        val ox = loc[0].toFloat(); val oy = loc[1].toFloat()

        if (gridMode) for (t in targets) {
            canvas.drawRect(
                t.rect.left - ox, t.rect.top - oy,
                t.rect.right - ox, t.rect.bottom - oy, gridLine
            )
        }
        for (t in targets) {
            val cx = t.rect.centerX() - ox; val cy = t.rect.centerY() - oy
            canvas.drawCircle(cx, cy, 34f, badgeBg)
            val ty = cy - (badgeText.descent() + badgeText.ascent()) / 2
            canvas.drawText(t.number.toString(), cx, ty, badgeText)
        }
        if (status.isNotEmpty()) {
            val marginTop = 18f; val marginLeft = 16f
            val padX = 30f; val padY = 16f; val accentR = 7f; val accentGap = 22f
            val maxTextW = width - marginLeft * 2 - padX * 2 - accentGap
            val shown = ellipsize(status, maxTextW)
            val tw = statusText.measureText(shown)
            val fm = statusText.fontMetrics
            val textH = fm.descent - fm.ascent
            val left = marginLeft
            val top = marginTop
            val right = left + accentGap + tw + padX * 2
            val bottom = top + textH + padY * 2
            val radius = (bottom - top) / 2f
            val rect = android.graphics.RectF(left, top, right, bottom)
            canvas.drawRoundRect(rect, radius, radius, statusBg)
            val cy = (top + bottom) / 2f
            canvas.drawCircle(left + padX, cy, accentR, statusAccent)
            canvas.drawText(shown, left + padX + accentGap, cy - (fm.descent + fm.ascent) / 2, statusText)
        }
    }
}
