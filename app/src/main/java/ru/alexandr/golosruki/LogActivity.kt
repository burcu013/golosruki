package ru.alexandr.golosruki

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Экран логов: показать, обновить, копировать, экспорт в файл, очистить. */
class LogActivity : ComponentActivity() {

    private lateinit var logView: TextView
    private val REQ_EXPORT = 3001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply { text = "Обновить"; setOnClickListener { refresh() } })
        buttons.addView(Button(this).apply { text = "Копировать"; setOnClickListener { copy() } })
        buttons.addView(Button(this).apply { text = "Очистить"; setOnClickListener { Logger.clear(); refresh() } })
        root.addView(buttons)

        root.addView(Button(this).apply {
            text = "📤 Экспорт логов в файл"
            setOnClickListener {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "golosruki_log.txt")
                }
                runCatching { startActivityForResult(i, REQ_EXPORT) }
            }
        })

        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(logView) })

        setContentView(root)
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(Logger.fullText().toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "Логи сохранены в файл", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this, "Ошибка экспорта", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun refresh() { logView.text = Logger.dump() }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ГолосРуки лог", Logger.dump()))
        Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() { super.onResume(); refresh() }
}
