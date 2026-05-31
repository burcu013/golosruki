package ru.alexandr.golosruki

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Помощники для аккуратного программного UI без XML-вёрстки. */
object UiKit {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun column(ctx: Context, pad: Int = 20): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, pad)
        setPadding(p, dp(ctx, 28), p, p)
    }

    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 26f
        setTextColor(Color.parseColor("#1C1E22"))
        setPadding(0, 0, 0, dp(ctx, 4))
    }

    fun subtitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor("#5A6370"))
        setPadding(0, 0, 0, dp(ctx, 16))
    }

    fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 19f
        setTextColor(Color.parseColor("#0E7C7B"))
        val t = dp(ctx, 18)
        setPadding(0, t, 0, dp(ctx, 6))
    }

    fun body(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor("#1C1E22"))
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    fun button(ctx: Context, text: String, bg: Int = R.drawable.btn_primary, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            isAllCaps = false
            setBackgroundResource(bg)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 10)
            layoutParams = lp
            val ph = dp(ctx, 16)
            setPadding(ph, ph, ph, ph)
            setOnClickListener { onClick() }
        }

    /** Компактная кнопка со значком, текст слева. */
    fun iconButton(ctx: Context, text: String, bg: Int = R.drawable.btn_primary, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setBackgroundResource(bg)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 8)
            layoutParams = lp
            setPadding(dp(ctx, 18), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11))
            setOnClickListener { onClick() }
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        val p = dp(ctx, 18)
        setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(ctx, 12)
        layoutParams = lp
    }
}
