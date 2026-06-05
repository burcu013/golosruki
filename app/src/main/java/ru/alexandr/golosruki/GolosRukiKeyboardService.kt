package ru.alexandr.golosruki

import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout

/**
 * Системная клавиатура «ГолосРуки».
 * Назначение:
 *  1) надёжный ввод текста диктовкой в ЛЮБОЕ приложение (через штатный канал ввода, не Accessibility);
 *  2) обычный набор руками на крупных клавишах (ЙЦУКЕН + цифры/символы).
 * Голосовая диктовка из VoiceRecognitionService пишет сюда через commitDictation().
 */
class GolosRukiKeyboardService : InputMethodService() {

    companion object {
        @Volatile var instance: GolosRukiKeyboardService? = null
    }

    private var dictCommittedLen = 0

    override fun onCreateInputView(): View {
        instance = this
        // Невидимая голосовая клавиатура: тонкая полоска-индикатор, экранную клавиатуру не показываем.
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0E7C7B"))
            setPadding(24, 14, 24, 14)
            val label = android.widget.TextView(this@GolosRukiKeyboardService).apply {
                text = "🎙 ГолосРуки — диктовка"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            }
            addView(label)
            val sw = Button(this@GolosRukiKeyboardService).apply {
                text = "⌨"
                isAllCaps = false
                setOnClickListener { switchAway() }
            }
            addView(sw)
        }
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        dictCommittedLen = 0
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun switchAway() {
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { switchToNextInputMethod(false) }
        } else {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        }
    }

    // --- Канал диктовки: вызывается из VoiceRecognitionService ---

    /** Есть ли активное поле ввода (наша клавиатура — текущая и поле в фокусе). */
    fun isActiveInput(): Boolean = currentInputConnection != null

    /** Старт диктовки: НЕ стираем поле — возвращаем имеющийся текст, курсор в конец. */
    fun beginDictation(): String {
        val ic = currentInputConnection ?: run { dictCommittedLen = 0; return "" }
        val before = ic.getTextBeforeCursor(100000, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(100000, 0)?.toString() ?: ""
        val full = before + after
        runCatching { ic.setSelection(full.length, full.length) }  // курсор в конец
        dictCommittedLen = full.length
        return full
    }

    /** Зафиксировать текст диктовки — вставляется только НОВЫЙ кусок (без дублей). */
    fun commitDictation(full: String) {
        val ic = currentInputConnection ?: return
        val from = dictCommittedLen.coerceIn(0, full.length)
        val delta = full.substring(from)
        if (delta.isNotEmpty()) ic.commitText(delta, 1)
        dictCommittedLen = full.length
    }
}
