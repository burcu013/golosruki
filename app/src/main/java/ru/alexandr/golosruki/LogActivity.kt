package ru.alexandr.golosruki

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Экран логов: показать, обновить, скопировать, очистить. */
class LogActivity : ComponentActivity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Обновить"; setOnClickListener { refresh() }
        })
        buttons.addView(Button(this).apply {
            text = "Копировать"; setOnClickListener { copy() }
        })
        buttons.addView(Button(this).apply {
            text = "Очистить"; setOnClickListener { Logger.clear(); refresh() }
        })
        root.addView(buttons)

        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(logView) })

        setContentView(root)
        refresh()
    }

    private fun refresh() { logView.text = Logger.dump() }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ГолосРуки лог", Logger.dump()))
        Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() { super.onResume(); refresh() }
}
