package ru.alexandr.golosruki

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = UiKit.column(this)

        col.addView(UiKit.title(this, "ГолосРуки"))
        col.addView(UiKit.subtitle(this, "Управление телефоном голосом. Активация словом «Иван»."))

        // Настройка
        val setup = UiKit.card(this)
        setup.addView(UiKit.sectionHeader(this, "Настройка (по порядку)"))
        setup.addView(UiKit.button(this, "1. Выдать разрешения") { requestPerms() })
        setup.addView(UiKit.button(this, "2. Включить службу спец. возможностей") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        setup.addView(UiKit.button(this, "3. Запустить голосовое управление", R.drawable.btn_amber) { startVoice() })
        setup.addView(UiKit.button(this, "🔁 Перезапустить Иван (сброс)") { resetVoice() })
        col.addView(setup)

        // Разделы
        val nav = UiKit.card(this)
        nav.addView(UiKit.sectionHeader(this, "Разделы"))
        nav.addView(UiKit.button(this, "📖 Гайд по управлению") { open(GuideActivity::class.java) })
        nav.addView(UiKit.button(this, "⚙️ Настройки под человека") { open(SettingsActivity::class.java) })
        col.addView(nav)

        // Диагностика
        val diag = UiKit.card(this)
        diag.addView(UiKit.sectionHeader(this, "Диагностика (удалить после тестов)"))
        diag.addView(UiKit.button(this, "🎤 Тест микрофона") { open(TestActivity::class.java) })
        diag.addView(UiKit.button(this, "📋 Логи") { open(LogActivity::class.java) })
        col.addView(diag)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun open(c: Class<*>) = startActivity(Intent(this, c))

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun startVoice() {
        val intent = Intent(this, VoiceRecognitionService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun resetVoice() {
        val intent = Intent(this, VoiceRecognitionService::class.java).setAction(VoiceRecognitionService.ACTION_RESET)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
}
