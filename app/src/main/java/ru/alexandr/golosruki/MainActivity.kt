package ru.alexandr.golosruki

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
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
        val col = UiKit.column(this)

        col.addView(buildHeader())

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

        // О приложении / разработчик
        col.addView(buildAbout())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4F6F6"))
            addView(col)
        })
    }

    private fun buildHeader(): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.header_bg)
            val p = UiKit.dp(this@MainActivity, 22)
            setPadding(p, UiKit.dp(this@MainActivity, 28), p, UiKit.dp(this@MainActivity, 26))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiKit.dp(this@MainActivity, 4) }
        }
        h.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            val s = UiKit.dp(this@MainActivity, 96)
            layoutParams = LinearLayout.LayoutParams(s, s)
        })
        h.addView(TextView(this).apply {
            text = "ГолосРуки"; textSize = 30f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        h.addView(TextView(this).apply {
            text = "Управление телефоном голосом"; textSize = 15f
            setTextColor(Color.parseColor("#CFEAE9")); gravity = Gravity.CENTER
        })
        h.addView(TextView(this).apply {
            text = "  ОФЛАЙН  •  ПРИВАТНО  •  ДЛЯ ВСЕХ  "
            textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.badge_bg)
            val pad = UiKit.dp(this@MainActivity, 8)
            setPadding(pad * 2, pad, pad * 2, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(this@MainActivity, 12) }
        })
        return h
    }

    private fun buildAbout(): LinearLayout {
        val card = UiKit.card(this)
        card.addView(UiKit.sectionHeader(this, "О приложении"))
        card.addView(UiKit.body(this, "Версия 3.6 • офлайн-распознавание речи"))
        card.addView(UiKit.body(this, "Разработчик: Донбасс Реклама"))
        card.addView(UiKit.button(this, "🌐 donbassreklama.ru", R.drawable.btn_primary) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://donbassreklama.ru")))
            }
        })
        return card
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
