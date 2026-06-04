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
        Logger.init(this)
        val col = UiKit.column(this)

        col.addView(buildHeader())

        val setup = UiKit.card(this)
        setup.addView(UiKit.sectionHeader(this, "Настройка (по порядку)"))
        setup.addView(UiKit.iconButton(this, "①  Выдать разрешения") { requestPerms() })
        setup.addView(UiKit.iconButton(this, "②  Включить спец. возможности") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        setup.addView(UiKit.iconButton(this, "③  ▶ Запустить управление", R.drawable.btn_amber) { startVoice() })
        setup.addView(UiKit.iconButton(this, "🔁  Перезапустить Иван") { resetVoice() })
        setup.addView(UiKit.iconButton(this, "🔋  Не выключать в фоне (важно!)") { requestBattery() })
        col.addView(setup)

        val nav = UiKit.card(this)
        nav.addView(UiKit.sectionHeader(this, "Разделы"))
        nav.addView(UiKit.row2(this,
            UiKit.tile(this, "📖", "Гайд") { open(GuideActivity::class.java) },
            UiKit.tile(this, "⚙️", "Настройки", R.drawable.btn_amber) { open(SettingsActivity::class.java) }
        ))
        nav.addView(UiKit.iconButton(this, "⌨ Диктовка везде (мастер)") { open(SetupActivity::class.java) })
        col.addView(nav)

        val diag = UiKit.card(this)
        diag.addView(UiKit.sectionHeader(this, "Диагностика"))
        diag.addView(UiKit.row2(this,
            UiKit.tile(this, "🎤", "Тест и обучение") { open(TestActivity::class.java) },
            UiKit.tile(this, "📋", "Логи") { open(LogActivity::class.java) }
        ))
        diag.addView(UiKit.tile(this, "🛠", "Супер-режим", R.drawable.btn_amber) { open(DevModeActivity::class.java) }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(this@MainActivity, 10) }
        })
        col.addView(diag)

        col.addView(buildDevBanner())
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
            setPadding(p, UiKit.dp(this@MainActivity, 26), p, UiKit.dp(this@MainActivity, 24))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiKit.dp(this@MainActivity, 4) }
        }
        h.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_header)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@MainActivity, 232), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = UiKit.dp(this@MainActivity, 8)
                bottomMargin = UiKit.dp(this@MainActivity, 2)
            }
        })
        h.addView(TextView(this).apply {
            text = "ГолосРуки"; textSize = 32f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(UiKit.fontBold(this@MainActivity))
        })
        h.addView(TextView(this).apply {
            text = "Управление телефоном голосом"; textSize = 15f
            setTypeface(UiKit.fontRegular(this@MainActivity))
            setTextColor(Color.parseColor("#CFEAE9")); gravity = Gravity.CENTER
        })
        h.addView(TextView(this).apply {
            text = "  ОФЛАЙН  •  ПРИВАТНО  •  ДЛЯ ВСЕХ  "
            textSize = 12f; setTextColor(Color.WHITE)
            setTypeface(UiKit.fontSemi(this@MainActivity))
            setBackgroundResource(R.drawable.badge_bg)
            val pad = UiKit.dp(this@MainActivity, 8)
            setPadding(pad * 2, pad, pad * 2, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(this@MainActivity, 12) }
        })
        return h
    }

    private fun buildDevBanner(): LinearLayout {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.banner_bg)
            val p = UiKit.dp(this@MainActivity, 18)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(this@MainActivity, 14) }
            isClickable = true
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://donbassreklama.ru"))) }
            }
        }
        // логотип + название в ряд
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.donbass_logo)
            val s = UiKit.dp(this@MainActivity, 56)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = UiKit.dp(this@MainActivity, 14) }
        }
        headRow.addView(logo)
        headRow.addView(TextView(this).apply {
            text = "Донбасс Реклама"; textSize = 22f; setTextColor(Color.parseColor("#1C1E22"))
            setTypeface(UiKit.fontBold(this@MainActivity))
        })
        b.addView(headRow)
        b.addView(TextView(this).apply {
            text = "Рекламное агентство. Создание приложений и сайтов, интеграция бизнеса с AI, вывески и реклама."
            textSize = 14f; setTextColor(Color.parseColor("#5A6370"))
            setTypeface(UiKit.fontRegular(this@MainActivity))
            setPadding(0, UiKit.dp(this@MainActivity, 10), 0, UiKit.dp(this@MainActivity, 12))
        })
        b.addView(TextView(this).apply {
            text = "  🌐  donbassreklama.ru  →  "
            textSize = 14f; setTextColor(Color.WHITE)
            setTypeface(UiKit.fontSemi(this@MainActivity))
            setBackgroundResource(R.drawable.btn_link)
            val pad = UiKit.dp(this@MainActivity, 10)
            setPadding(pad * 2, pad, pad * 2, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        // лёгкая анимация: плавное появление баннера + «дыхание» логотипа
        b.alpha = 0f
        b.post {
            b.animate().alpha(1f).setDuration(550).start()
            android.animation.ObjectAnimator.ofPropertyValuesHolder(
                logo,
                android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
                android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f)
            ).apply {
                duration = 1600
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }
        return b
    }

    private fun buildAbout(): LinearLayout {
        val card = UiKit.card(this)
        card.addView(UiKit.sectionHeader(this, "О приложении"))
        card.addView(UiKit.body(this, "ГолосРуки — голосовое управление смартфоном для людей с ограниченными возможностями."))
        card.addView(UiKit.body(this, "Офлайн-распознавание речи, без интернета и без передачи данных."))
        card.addView(UiKit.body(this, "Версия 6.35 • Разработчик: Донбасс Реклама"))
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
        if (Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
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

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBattery() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            } else {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
