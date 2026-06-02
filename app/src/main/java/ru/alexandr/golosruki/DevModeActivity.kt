package ru.alexandr.golosruki

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.content.Intent

/**
 * Супер-режим разработчика (вход по PIN 17931793).
 * Позволяет задать СВОИ слова-триггеры почти для всех команд.
 * Слой не ломает встроенные команды: если своя фраза не совпала — работает обычный разбор.
 */
class DevModeActivity : ComponentActivity() {

    private val PIN = "17931793"
    private val fields = HashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPinGate()
    }

    private fun showPinGate() {
        val col = UiKit.column(this)
        col.addView(UiKit.title(this, "Супер-режим"))
        col.addView(UiKit.subtitle(this, "Вход только по PIN-коду. Здесь можно переназначить слова команд."))
        val card = UiKit.card(this)
        card.addView(UiKit.body(this, "Введите PIN:"))
        val pin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        card.addView(pin)
        card.addView(UiKit.button(this, "Войти") {
            if (pin.text.toString().trim() == PIN) showEditor()
            else Toast.makeText(this, "Неверный PIN", Toast.LENGTH_SHORT).show()
        })
        col.addView(card)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun showEditor() {
        fields.clear()
        val col = UiKit.column(this)
        col.addView(UiKit.title(this, "Свои слова команд"))
        col.addView(UiKit.subtitle(this, "Для любой команды впишите свои слова-триггеры через запятую. Иван будет распознавать их в первую очередь. Пусто — работает стандартный набор."))

        val current = SettingsStore.getAliasMap(this)   // фраза → ключ
        // соберём по ключам: ключ → список фраз
        val byKey = HashMap<String, MutableList<String>>()
        for ((phrase, key) in current) byKey.getOrPut(key) { mutableListOf() }.add(phrase)

        val card = UiKit.card(this)
        for (e in CommandAliases.registry) {
            card.addView(UiKit.body(this, e.label + ":"))
            val ed = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "свои слова через запятую"
                setText(byKey[e.key]?.joinToString(", ") ?: "")
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            fields[e.key] = ed
            card.addView(ed)
        }
        col.addView(card)

        col.addView(UiKit.button(this, "💾 Сохранить и перезапустить", R.drawable.btn_amber) { save() })
        col.addView(UiKit.button(this, "Очистить все свои слова", R.drawable.btn_danger) {
            SettingsStore.setAliasMap(this, emptyMap())
            CommandAliases.aliasMap = emptyMap()
            restartService()
            Toast.makeText(this, "Сброшено", Toast.LENGTH_SHORT).show()
            showEditor()
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun save() {
        val map = HashMap<String, String>()
        for ((key, ed) in fields) {
            ed.text.toString().split(",").forEach { raw ->
                val phrase = raw.lowercase().trim()
                if (phrase.isNotBlank()) map[phrase] = key
            }
        }
        SettingsStore.setAliasMap(this, map)
        CommandAliases.aliasMap = map
        restartService()
        Toast.makeText(this, "Сохранено: ${map.size} своих слов", Toast.LENGTH_LONG).show()
    }

    private fun restartService() {
        val s = Intent(this, VoiceRecognitionService::class.java)
        stopService(s)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
    }
}
