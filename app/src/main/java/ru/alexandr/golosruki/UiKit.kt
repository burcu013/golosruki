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
        textSize = 28f
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
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
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        setTextColor(Color.parseColor("#0E7C7B"))
        setPadding(0, dp(ctx, 16), 0, dp(ctx, 8))
    }

    /** Квадратная кнопка-плитка: значок сверху, подпись снизу. */
    fun tile(ctx: Context, icon: String, label: String, bg: Int = R.drawable.btn_primary, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(bg)
            setPadding(dp(ctx, 12), dp(ctx, 18), dp(ctx, 12), dp(ctx, 18))
            isClickable = true
            setOnClickListener { onClick() }
            addView(TextView(ctx).apply {
                text = icon; textSize = 30f; gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = label; textSize = 14f; setTextColor(Color.WHITE)
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 6), 0, 0)
            })
        }

    /** Две плитки в ряд (равная ширина). */
    fun row2(ctx: Context, a: android.view.View, b: android.view.View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 10) }
            layoutParams = lp
            val g = dp(ctx, 5)
            a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = g }
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = g }
            addView(a); addView(b)
        }

    fun body(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor("#1C1E22"))
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    /** Мелкая подсказка/предупреждение приглушённым цветом. */
    fun hint(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#8A7320"))
        setBackgroundColor(Color.parseColor("#FFF6DA"))
        val p = dp(ctx, 8)
        setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 6); bottomMargin = dp(ctx, 4) }
        layoutParams = lp
    }

    /** Строка гайда: команда жирно/цветом, описание после «—» обычным. */
    fun cmdLine(ctx: Context, line: String): TextView = TextView(ctx).apply {
        textSize = 15f
        setPadding(dp(ctx, 4), dp(ctx, 5), 0, dp(ctx, 5))
        val sep = line.indexOf(" — ")
        if (sep > 0) {
            val cmd = line.substring(0, sep)
            val desc = line.substring(sep)
            val sb = android.text.SpannableStringBuilder("▸ $cmd$desc")
            val end = 2 + cmd.length
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, end, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#0E7C7B")), 0, end, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#5A6370")), end, sb.length, 0)
            text = sb
        } else {
            val sb = android.text.SpannableStringBuilder("▸ $line")
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#0E7C7B")), 0, sb.length, 0)
            text = sb
        }
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
