package ru.alexandr.golosruki

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }

        root.addView(TextView(this).apply { text = "ГолосРуки"; textSize = 30f })
        root.addView(TextView(this).apply {
            text = "Управление телефоном голосом. Выполните 3 шага по порядку."
            textSize = 16f; setPadding(0, 16, 0, 28)
        })

        root.addView(Button(this).apply {
            text = "1. Выдать разрешения"
            setOnClickListener { requestPerms() }
        })
        root.addView(Button(this).apply {
            text = "2. Включить службу спец. возможностей"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "3. Запустить голосовое управление"
            setOnClickListener { startVoice() }
        })

        root.addView(Button(this).apply {
            text = "🎤 ТЕСТ микрофона (диагностика)"
            setOnClickListener { startActivity(Intent(this@MainActivity, TestActivity::class.java)) }
        })

        root.addView(TextView(this).apply {
            text = """
                АКТИВАЦИЯ: скажите «Иван». После 30 сек тишины — снова «Иван».
                Разблокировка экрана: «Иван привет».

                Примеры команд (после «Иван»):
                • назад, домой, недавние, шторка
                • номера → нажми 5
                • сетка → нажми 7 → нажми 3  (точное попадание, 12 ячеек)
                • листай вверх / вниз / влево / вправо
                • позвони жене, открой телеграм
                • напиши … / диктовка … готово
                • сос  (звонок + СМС с геолокацией)
                • стоп / слушай, помощь

                Персональные контакты и приложения — в файле
                assets/personal_commands.json (отредактируйте под себя).
            """.trimIndent()
            textSize = 15f; setPadding(0, 36, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
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
}
