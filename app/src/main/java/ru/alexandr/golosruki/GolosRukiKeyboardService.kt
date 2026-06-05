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

    /** Старт диктовки: НЕ стираем поле. Курсор в конец, дописываем только новое. */
    fun beginDictation() {
        val ic = currentInputConnection
        if (ic != null) {
            val before = ic.getTextBeforeCursor(100000, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(100000, 0)?.length ?: 0
            runCatching { ic.setSelection(before + after, before + after) }
        }
        dictCommittedLen = 0
    }

    /** Разовая вставка текста в позицию курсора (для «сформулируй»). */
    fun insertText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
    }

    /** Очистить всё поле. */
    fun clearAll() {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(100000, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(100000, 0)?.length ?: 0
        ic.deleteSurroundingText(before, after)
        ic.endBatchEdit()
    }

    /** Удалить символ перед курсором или выделенное.
     *  sendKeyEvent(DEL) — настоящая клавиша Backspace, работает даже в холсте (Samsung Notes). */
    fun deleteBack() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) { ic.commitText("", 1); return }
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
    }

    /** Выделить весь текст и стереть (через клавишу Backspace) — работает и в холсте. */
    fun selectAllAndDelete() {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
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
