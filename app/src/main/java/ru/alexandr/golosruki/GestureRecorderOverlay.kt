package ru.alexandr.golosruki

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Полноэкранный оверлей записи жеста. Перехватывает касания (в т.ч. мультитач),
 * рисует траекторию и сверху панель с кнопками «Сохранить / Очистить / Отмена».
 * Координаты сохраняются в долях экрана (0..1), время — в мс от начала жеста.
 */
@SuppressLint("ViewConstructor")
class GestureRecorderOverlay(
    context: Context,
    private val title: String,
    private val onSave: (String) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val bg = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val barBg = Paint().apply { color = Color.argb(235, 24, 26, 30) }
    private val btnBg = Paint().apply { color = Color.argb(255, 46, 50, 58) }
    private val btnSave = Paint().apply { color = Color.argb(255, 28, 130, 70) }
    private val btnCancel = Paint().apply { color = Color.argb(255, 150, 60, 50) }
    private val txt = Paint().apply { color = Color.WHITE; textSize = 46f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val hint = Paint().apply { color = Color.argb(220, 255, 255, 255); textSize = 42f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val stroke = Paint().apply {
        color = Color.argb(255, 90, 200, 255); style = Paint.Style.STROKE
        strokeWidth = 10f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private val barH = 180f
    private var t0 = 0L
    private val active = HashMap<Int, ArrayList<Triple<Float, Float, Long>>>()   // pointerId -> точки (px)
    private val completed = ArrayList<List<Triple<Float, Float, Long>>>()

    private fun rSave() = RectF(0f, 0f, width / 3f, barH)
    private fun rClear() = RectF(width / 3f, 0f, width * 2f / 3f, barH)
    private fun rCancel() = RectF(width * 2f / 3f, 0f, width.toFloat(), barH)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, barH, width.toFloat(), height.toFloat(), bg)
        // подсказка
        canvas.drawText(title, width / 2f, height / 2f - 30f, hint)
        canvas.drawText("Нарисуйте жест пальцем, затем «Сохранить»", width / 2f, height / 2f + 40f, hint)
        // нарисованные штрихи
        drawStrokes(canvas)
        // панель
        canvas.drawRect(0f, 0f, width.toFloat(), barH, barBg)
        drawButton(canvas, rSave(), btnSave, "Сохранить")
        drawButton(canvas, rClear(), btnBg, "Очистить")
        drawButton(canvas, rCancel(), btnCancel, "Отмена")
    }

    private fun drawButton(c: Canvas, r: RectF, p: Paint, label: String) {
        val pad = 12f
        c.drawRoundRect(RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad), 24f, 24f, p)
        c.drawText(label, r.centerX(), r.centerY() + 16f, txt)
    }

    private fun drawStrokes(c: Canvas) {
        fun line(pts: List<Triple<Float, Float, Long>>) {
            for (i in 1 until pts.size) c.drawLine(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second, stroke)
        }
        for (s in completed) line(s)
        for (s in active.values) line(s)
    }

    private fun now() = SystemClock.uptimeMillis() - t0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val x = event.getX(idx); val y = event.getY(idx)
                if (y < barH) { handleBar(x); return true }   // касание панели — это кнопка, не жест
                if (active.isEmpty() && completed.isEmpty()) t0 = SystemClock.uptimeMillis()
                active[event.getPointerId(idx)] = arrayListOf(Triple(x, y, now()))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    active[id]?.add(Triple(event.getX(i), event.getY(i), now()))
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                active.remove(id)?.let { if (it.size >= 1) completed.add(it) }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { active.clear(); invalidate() }
        }
        return true
    }

    private fun handleBar(x: Float) {
        when {
            rSave().contains(x, 1f) -> save()
            rClear().contains(x, 1f) -> { completed.clear(); active.clear(); invalidate() }
            rCancel().contains(x, 1f) -> onCancel()
        }
    }

    private fun save() {
        // докинуть незавершённые
        for (s in active.values) if (s.isNotEmpty()) completed.add(ArrayList(s))
        active.clear()
        val w = width.toFloat().coerceAtLeast(1f); val h = height.toFloat().coerceAtLeast(1f)
        val strokes = completed
            .filter { it.size >= 2 || (it.size == 1) }     // одиночная точка = тап
            .take(10)                                      // ограничение dispatchGesture
            .map { st -> st.map { Triple(it.first / w, it.second / h, it.third) } }
        if (strokes.isEmpty()) { onCancel(); return }
        onSave(GestureStore.serialize(strokes))
    }
}
