package ru.alexandr.golosruki

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Мастер «Диктовка везде»: включить клавиатуру + выдать разрешение для авто-переключения. */
class SetupActivity : ComponentActivity() {

    private lateinit var statusKb: TextView
    private lateinit var statusPerm: TextView
    private lateinit var hostField: android.widget.EditText
    private lateinit var portField: android.widget.EditText
    private lateinit var codeField: android.widget.EditText
    private lateinit var bResult: TextView

    private fun editText(hintText: String, type: Int): android.widget.EditText =
        android.widget.EditText(this).apply { hint = hintText; inputType = type }

    private fun runPairAndGrant() {
        val host = hostField.text.toString().trim()
        val port = portField.text.toString().trim().toIntOrNull()
        val code = codeField.text.toString().trim()
        if (host.isEmpty() || port == null || code.isEmpty()) {
            bResult.text = "Заполните адрес, порт и код."
            return
        }
        bResult.text = "Сопряжение…"
        Thread {
            val res = runCatching { AdbManager.pairAndGrant(this, host, port, code) }
                .getOrElse { "Ошибка: ${it.message ?: it.javaClass.simpleName}" }
            runOnUiThread { bResult.text = res; refresh() }
        }.start()
    }

    private val grantCmd =
        "adb shell pm grant ru.alexandr.golosruki android.permission.WRITE_SECURE_SETTINGS"

    private val fullBlock = """
        pkg install -y android-tools
        # 1) Беспроводная отладка → «Подключить с кодом сопряжения»: вставь адрес:порт оттуда
        adb pair АДРЕС:ПОРТ
        # 2) На экране «Беспроводная отладка» возьми адрес:порт и вставь:
        adb connect АДРЕС:ПОРТ
        # 3) Выдать разрешение приложению:
        adb shell pm grant ru.alexandr.golosruki android.permission.WRITE_SECURE_SETTINGS
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = UiKit.column(this)
        col.addView(UiKit.title(this, "Диктовка везде"))

        val info = UiKit.card(this)
        info.addView(UiKit.body(this, "Чтобы диктовка писала текст в любом приложении (браузер, заметки, сайты), приложению нужна голосовая клавиатура и разовое разрешение. После настройки во время «Иван, диктовка» клавиатура включается сама и сама возвращает обычную."))
        col.addView(info)

        // Шаг 1 — клавиатура
        val c1 = UiKit.card(this)
        c1.addView(UiKit.sectionHeader(this, "Шаг 1. Включить клавиатуру"))
        c1.addView(UiKit.body(this, "Включите «ГолосРуки» в списке клавиатур (галочка). Делать основной не нужно — приложение само переключит во время диктовки."))
        statusKb = UiKit.body(this, "")
        c1.addView(statusKb)
        c1.addView(UiKit.button(this, "Открыть список клавиатур") {
            runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        col.addView(c1)

        // Шаг 2 — разрешение через adb
        val c2 = UiKit.card(this)
        c2.addView(UiKit.sectionHeader(this, "Шаг 2. Разрешение (один раз)"))
        c2.addView(UiKit.body(this, "Включите «Беспроводную отладку» в разделе «Для разработчиков», затем в Termux выполните команды. Адрес, порт и код сопряжения показывает сама система."))
        statusPerm = UiKit.body(this, "")
        c2.addView(statusPerm)
        c2.addView(UiKit.button(this, "Открыть «Для разработчиков»") {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } }
        })
        c2.addView(UiKit.button(this, "📋 Скопировать команды для Termux") {
            copy(fullBlock); Toast.makeText(this, "Команды скопированы", Toast.LENGTH_SHORT).show()
        })
        c2.addView(UiKit.button(this, "📋 Скопировать только команду разрешения") {
            copy(grantCmd); Toast.makeText(this, "Команда скопирована", Toast.LENGTH_SHORT).show()
        })
        col.addView(c2)

        // Способ B — встроенный adb (без Termux)
        val cb = UiKit.card(this)
        cb.addView(UiKit.sectionHeader(this, "Способ B — без Termux"))
        cb.addView(UiKit.body(this, "Включите «Беспроводную отладку» → «Подключить с кодом сопряжения». Введите показанные адрес, порт и код сюда и нажмите кнопку — приложение само выдаст разрешение."))
        hostField = editText("Адрес (например 192.168.0.5)", android.text.InputType.TYPE_CLASS_TEXT)
        cb.addView(hostField)
        portField = editText("Порт сопряжения (например 37123)", android.text.InputType.TYPE_CLASS_NUMBER)
        cb.addView(portField)
        codeField = editText("Код сопряжения (6 цифр)", android.text.InputType.TYPE_CLASS_NUMBER)
        cb.addView(codeField)
        bResult = UiKit.body(this, "")
        cb.addView(bResult)
        cb.addView(UiKit.button(this, "🔗 Сопрячь и выдать разрешение") { runPairAndGrant() })
        col.addView(cb)

        col.addView(UiKit.button(this, "🔄 Проверить готовность") { refresh() })
        col.addView(UiKit.hint(this, "Если беспроводная отладка недоступна — то же можно сделать adb с компьютера по USB. Без этого разрешения авто-переключение работать не будет (диктовка тогда — только в обычных полях через спец-возможности)."))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val kbOn = imm.enabledInputMethodList.any { it.packageName == packageName }
        statusKb.text = if (kbOn) "Клавиатура: включена ✅" else "Клавиатура: не включена ❌"

        val permOn = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        statusPerm.text = if (permOn) "Разрешение: выдано ✅" else "Разрешение: не выдано ❌"
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("cmd", text))
    }
}
