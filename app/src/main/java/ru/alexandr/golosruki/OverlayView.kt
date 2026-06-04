package ru.alexandr.golosruki

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** Прозрачное наложение: номера на элементах, сетка и строка статуса Ивана. */
class OverlayView(context: Context) : View(context) {

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // корректная тень статуса

    enum class Icon { DOT, MEDIA, PEN, DIGITS }

    data class Target(val number: Int, val rect: Rect)

    private var targets: List<Target> = emptyList()
    private var gridMode: Boolean = false
    private var status: String = ""
    private var statusMaxLines: Int = 1
    private var icon: Icon = Icon.DOT

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
    private val statusText = Paint().apply {
        color = Color.parseColor("#F2F2F0"); textSize = 38f; isAntiAlias = true
    }
    private val cDot = Paint().apply { color = Color.parseColor("#E8923B"); isAntiAlias = true }
    private val cMedia = Paint().apply { color = Color.parseColor("#A874FF"); isAntiAlias = true }
    private val cPen = Paint().apply {
        color = Color.parseColor("#7FD1FF"); isAntiAlias = true
        style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
    }
    private val cPenTip = Paint().apply { color = Color.parseColor("#FFD27F"); isAntiAlias = true }
    private val cDigBg = Paint().apply { color = Color.parseColor("#E8923B"); isAntiAlias = true }
    private val cDigText = Paint().apply {
        color = Color.WHITE; textSize = 26f; isAntiAlias = true
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    fun setTargets(list: List<Target>, grid: Boolean) { targets = list; gridMode = grid; invalidate() }
    fun clearTargets() { targets = emptyList(); gridMode = false; invalidate() }
    fun setStatus(text: String, maxLines: Int = 1) { status = text; statusMaxLines = maxLines.coerceIn(1, 5); invalidate() }
    fun setIcon(i: Icon) { icon = i; invalidate() }

    private fun wrap(text: String, maxW: Float, maxLines: Int): List<String> {
        if (maxLines <= 1) return listOf(ellipsize(text, maxW))
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (statusText.measureText(candidate) <= maxW) {
                cur = StringBuilder(candidate)
            } else {
                if (cur.isNotEmpty()) lines.add(cur.toString())
                cur = StringBuilder(w)
                if (lines.size == maxLines - 1) break
            }
        }
        if (lines.size < maxLines && cur.isNotEmpty()) lines.add(cur.toString())
        val joined = lines.joinToString(" ")
        if (joined.length < text.length && lines.isNotEmpty()) {
            lines[lines.size - 1] = ellipsize(lines.last() + " …", maxW)
        }
        return lines
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (statusText.measureText(s) <= maxW) return s
        var str = s
        while (str.length > 2 && statusText.measureText("$str…") > maxW) str = str.dropLast(1)
        return "$str…"
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float) {
        when (icon) {
            Icon.DOT -> canvas.drawCircle(cx, cy, 7f, cDot)
            Icon.MEDIA -> {
                val r = 12f
                val p = Path().apply {
                    moveTo(cx - r * 0.7f, cy - r); lineTo(cx - r * 0.7f, cy + r); lineTo(cx + r, cy); close()
                }
                canvas.drawPath(p, cMedia)
            }
            Icon.PEN -> {
                canvas.drawLine(cx - 11f, cy + 11f, cx + 8f, cy - 8f, cPen)
                val tip = Path().apply {
                    moveTo(cx + 8f, cy - 8f); lineTo(cx + 14f, cy - 14f); lineTo(cx + 14f, cy - 6f); close()
                }
                canvas.drawPath(tip, cPenTip)
            }
            Icon.DIGITS -> {
                canvas.drawCircle(cx, cy, 14f, cDigBg)
                canvas.drawText("1", cx, cy - (cDigText.descent() + cDigText.ascent()) / 2, cDigText)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val loc = IntArray(2); getLocationOnScreen(loc)
        val ox = loc[0].toFloat(); val oy = loc[1].toFloat()

        if (gridMode) for (t in targets) {
            canvas.drawRect(t.rect.left - ox, t.rect.top - oy, t.rect.right - ox, t.rect.bottom - oy, gridLine)
        }
        for (t in targets) {
            val cx = t.rect.centerX() - ox; val cy = t.rect.centerY() - oy
            canvas.drawCircle(cx, cy, 34f, badgeBg)
            val ty = cy - (badgeText.descent() + badgeText.ascent()) / 2
            canvas.drawText(t.number.toString(), cx, ty, badgeText)
        }

        if (status.isNotEmpty()) {
            val marginTop = 18f; val marginLeft = 16f
            val padX = 30f; val padY = 16f; val iconGap = 26f
            val maxTextW = width - marginLeft * 2 - padX * 2 - iconGap
            val lines = wrap(status, maxTextW, statusMaxLines)
            val fm = statusText.fontMetrics
            val lineH = (fm.descent - fm.ascent)
            val widest = lines.maxOf { statusText.measureText(it) }
            val left = marginLeft; val top = marginTop
            val right = left + iconGap + widest + padX * 2
            val bottom = top + lineH * lines.size + padY * 2
            canvas.drawRoundRect(RectF(left, top, right, bottom), 30f, 30f, statusBg)
            drawIcon(canvas, left + padX, (top + bottom) / 2f)
            var ty = top + padY - fm.ascent
            for (ln in lines) { canvas.drawText(ln, left + padX + iconGap, ty, statusText); ty += lineH }
        }
    }
}
