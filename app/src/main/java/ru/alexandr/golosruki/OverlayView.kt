package ru.alexandr.golosruki

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/** Прозрачное наложение: номера на элементах, сетка и строка статуса. */
class OverlayView(context: Context) : View(context) {

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
    private val statusBg = Paint().apply { color = Color.parseColor("#CC1C1E22"); isAntiAlias = true }
    private val statusText = Paint().apply { color = Color.WHITE; textSize = 42f; isAntiAlias = true }

    fun setTargets(list: List<Target>, grid: Boolean) {
        targets = list; gridMode = grid; invalidate()
    }
    fun clearTargets() { targets = emptyList(); gridMode = false; invalidate() }
    fun setStatus(text: String) { status = text; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridMode) for (t in targets) canvas.drawRect(t.rect, gridLine)
        for (t in targets) {
            val cx = t.rect.centerX().toFloat(); val cy = t.rect.centerY().toFloat()
            canvas.drawCircle(cx, cy, 34f, badgeBg)
            val ty = cy - (badgeText.descent() + badgeText.ascent()) / 2
            canvas.drawText(t.number.toString(), cx, ty, badgeText)
        }
        if (status.isNotEmpty()) {
            val pad = 26f
            val tw = statusText.measureText(status)
            canvas.drawRect(0f, 0f, tw + pad * 2, 82f, statusBg)
            canvas.drawText(status, pad, 56f, statusText)
        }
    }
}
