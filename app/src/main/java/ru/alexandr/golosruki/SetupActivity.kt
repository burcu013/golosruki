package ru.alexandr.golosruki

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
    private lateinit var autoResult: TextView

    private val notifPerm = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginAutoPair()
        else autoResult.text = "Без разрешения на уведомления автосопряжение не сможет показать поле для кода. Разрешите его или введите вручную."
    }

    private fun startAutoPair() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        beginAutoPair()
    }

    private fun beginAutoPair() {
        autoResult.text = "Запускаю поиск…"
        AdbAutoPair.start(this) { msg -> runOnUiThread { autoResult.text = msg } }
    }

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

        // Шаг 2 — разрешение через adb (запасной способ — Termux)
        val c2 = UiKit.card(this)
        c2.addView(UiKit.sectionHeader(this, "Запасной способ: через Termux"))
        c2.addView(UiKit.body(this, "Если способ ниже (автосопряжение) не сработал. Включите «Беспроводную отладку» в разделе «Для разработчиков», затем в Termux выполните команды. Адрес, порт и код сопряжения показывает сама система."))
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
        cb.addView(UiKit.sectionHeader(this, "Шаг 2 (рекомендуется): разрешение без Termux"))
        cb.addView(UiKit.body(this, "Включите «Беспроводную отладку». Затем нажмите кнопку ниже и откройте «Подключить с помощью кода подключения» — приложение само найдёт окно, а код вы введёте через уведомление, НЕ закрывая окно сопряжения."))
        autoResult = UiKit.body(this, "")
        cb.addView(autoResult)
        cb.addView(UiKit.button(this, "🔍 Автосопряжение (рекомендуется)") { startAutoPair() })
        cb.addView(UiKit.hint(this, "Если автопоиск не сработает — введите адрес, порт и код вручную ниже:"))
        hostField = editText("Адрес (например 10.0.0.1)", android.text.InputType.TYPE_CLASS_TEXT)
        cb.addView(hostField)
        portField = editText("Порт сопряжения (из окна с кодом!)", android.text.InputType.TYPE_CLASS_NUMBER)
        cb.addView(portField)
        codeField = editText("Код сопряжения (6 цифр)", android.text.InputType.TYPE_CLASS_NUMBER)
        cb.addView(codeField)
        bResult = UiKit.body(this, "")
        cb.addView(bResult)
        cb.addView(UiKit.button(this, "🔗 Сопрячь вручную") { runPairAndGrant() })
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
