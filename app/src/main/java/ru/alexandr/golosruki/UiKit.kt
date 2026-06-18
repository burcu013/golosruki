package ru.alexandr.golosruki

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Помощники для аккуратного программного UI без XML-вёрстки. */
object UiKit {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private var tfReg: Typeface? = null
    private var tfBold: Typeface? = null
    private var tfSemi: Typeface? = null
    fun fontRegular(ctx: Context): Typeface? { if (tfReg == null) tfReg = runCatching { ResourcesCompat.getFont(ctx, R.font.montserrat_regular) }.getOrNull(); return tfReg }
    fun fontBold(ctx: Context): Typeface? { if (tfBold == null) tfBold = runCatching { ResourcesCompat.getFont(ctx, R.font.montserrat_bold) }.getOrNull(); return tfBold }
    fun fontSemi(ctx: Context): Typeface? { if (tfSemi == null) tfSemi = runCatching { ResourcesCompat.getFont(ctx, R.font.montserrat_semibold) }.getOrNull(); return tfSemi }

    fun column(ctx: Context, pad: Int = 20): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, pad)
        setPadding(p, dp(ctx, 28), p, p)
    }

    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 28f
        setTypeface(fontBold(ctx))
        setTextColor(Color.parseColor("#1C1E22"))
        setPadding(0, 0, 0, dp(ctx, 4))
    }

    fun subtitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTypeface(fontRegular(ctx))
        setTextColor(Color.parseColor("#5A6370"))
        setPadding(0, 0, 0, dp(ctx, 16))
    }

    fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 19f
        setTypeface(fontBold(ctx))
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
                setTypeface(fontSemi(ctx))
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
        setTypeface(fontRegular(ctx))
        setTextColor(Color.parseColor("#1C1E22"))
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    /** Мелкая подсказка/предупреждение приглушённым цветом. */
    fun hint(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTypeface(fontRegular(ctx))
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
        setTypeface(fontRegular(ctx))
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
            setTypeface(fontSemi(ctx))
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
            setTypeface(fontSemi(ctx))
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

    /** Стильный переключатель (зелёный во включённом состоянии). */
    fun switchView(ctx: Context): android.widget.Switch = android.widget.Switch(ctx).apply {
        textSize = 15f
        setTypeface(fontRegular(ctx))
        setTextColor(Color.parseColor("#1C1E22"))
        val checkedStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = android.content.res.ColorStateList(checkedStates,
            intArrayOf(Color.parseColor("#2FAE6A"), Color.parseColor("#FFFFFF")))
        trackTintList = android.content.res.ColorStateList(checkedStates,
            intArrayOf(Color.parseColor("#882FAE6A"), Color.parseColor("#C2CBCB")))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(ctx, 6)
        layoutParams = lp
        setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        elevation = dp(ctx, 7).toFloat()   // мягкая тень → ощущение «стекла» над фоном
        val p = dp(ctx, 18)
        setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(ctx, 12)
        layoutParams = lp
    }

    /** Стеклянная (полупрозрачная) кнопка для второстепенных действий. */
    fun glassButton(ctx: Context, text: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor("#0E7C7B"))
            textSize = 16f; isAllCaps = false
            setTypeface(fontSemi(ctx))
            setBackgroundResource(R.drawable.btn_glass)
            elevation = dp(ctx, 3).toFloat()
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ); lp.topMargin = dp(ctx, 10); layoutParams = lp
            val ph = dp(ctx, 15); setPadding(ph, ph, ph, ph)
            setOnClickListener { onClick() }
        }

    /** Нижний навбар-вкладки (значок + подпись), с подсветкой активной вкладки. */
    fun navBar(ctx: Context, items: List<Pair<String, String>>, initial: Int, onSelect: (Int) -> Unit): LinearLayout {
        val cells = ArrayList<LinearLayout>()
        fun select(idx: Int) {
            cells.forEachIndexed { i, cell ->
                cell.setBackgroundResource(if (i == idx) R.drawable.nav_pill else 0)
                (cell.getChildAt(1) as TextView).setTextColor(Color.parseColor(if (i == idx) "#0E7C7B" else "#5A6370"))
            }
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.nav_bg)
            elevation = dp(ctx, 14).toFloat()
            val p = dp(ctx, 6); setPadding(p, dp(ctx, 8), p, dp(ctx, 10))
        }
        items.forEachIndexed { i, (icon, label) ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
                val pv = dp(ctx, 7); setPadding(dp(ctx, 2), pv, dp(ctx, 2), pv)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { val m = dp(ctx, 3); marginStart = m; marginEnd = m }
                addView(TextView(ctx).apply { text = icon; textSize = 19f; gravity = Gravity.CENTER })
                addView(TextView(ctx).apply {
                    text = label; textSize = 11f; setTypeface(fontSemi(ctx)); gravity = Gravity.CENTER
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
                setOnClickListener { select(i); onSelect(i) }
            }
            cells.add(cell); bar.addView(cell)
        }
        select(initial)
        return bar
    }
}
