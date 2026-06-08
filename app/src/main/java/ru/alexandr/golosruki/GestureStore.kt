package ru.alexandr.golosruki

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранилище жестов. Координаты хранятся в ДОЛЯХ экрана (0..1) + время в мс —
 * поэтому жест переносится между устройствами с разным размером/плотностью.
 *
 * Формат JSON: {"strokes":[ [[x,y,t],[x,y,t],...], ... ]}
 *  - strokes: массив штрихов (для мультитача — несколько штрихов);
 *  - каждый штрих: массив точек [xДоля, yДоля, tМс-от-начала-жеста].
 */
object GestureStore {
    private const val PREF = "golosruki"
    private const val MAX = 20
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // --- Кастомные жесты: слово -> JSON жеста (до 20) ---
    fun getCustomGestures(ctx: Context): Map<String, String> {
        val raw = p(ctx).getString("gestures", null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val m = LinkedHashMap<String, String>()
            val it = o.keys()
            while (it.hasNext()) { val k = it.next(); m[k.lowercase()] = o.optString(k) }
            m
        } catch (e: Exception) { emptyMap() }
    }

    /** Возвращает false, если достигнут лимит в 20 жестов (для нового слова). */
    fun saveCustomGesture(ctx: Context, word: String, json: String): Boolean {
        val w = word.lowercase().trim()
        if (w.isBlank() || json.isBlank()) return false
        val m = LinkedHashMap(getCustomGestures(ctx))
        if (!m.containsKey(w) && m.size >= MAX) return false
        m[w] = json
        writeGestures(ctx, m)
        return true
    }

    fun deleteCustomGesture(ctx: Context, word: String) {
        val m = LinkedHashMap(getCustomGestures(ctx))
        m.remove(word.lowercase().trim())
        writeGestures(ctx, m)
    }

    /** Жест «строгий» — срабатывает только если фраза содержала слово активации («Иван <слово>»). */
    fun isStrict(json: String): Boolean = try { JSONObject(json).optBoolean("strict", false) } catch (e: Exception) { false }

    fun setStrict(ctx: Context, word: String, strict: Boolean) {
        val w = word.lowercase().trim()
        val m = LinkedHashMap(getCustomGestures(ctx))
        val j = m[w] ?: return
        val o = try { JSONObject(j) } catch (e: Exception) { return }
        o.put("strict", strict)
        m[w] = o.toString()
        writeGestures(ctx, m)
    }

    private fun writeGestures(ctx: Context, m: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in m) if (k.isNotBlank() && v.isNotBlank()) o.put(k, v)
        p(ctx).edit().putString("gestures", o.toString()).apply()
    }

    /** Стрим-режим: после жеста Иван ждёт «Иван хватит/стоп» (флаг хранится в самом JSON жеста). */
    fun isLock(json: String): Boolean = try { JSONObject(json).optBoolean("lock", false) } catch (e: Exception) { false }

    fun getLock(ctx: Context, word: String): Boolean {
        val j = getCustomGestures(ctx)[word.lowercase().trim()] ?: return false
        return isLock(j)
    }

    fun setLock(ctx: Context, word: String, on: Boolean) {
        val w = word.lowercase().trim()
        val m = LinkedHashMap(getCustomGestures(ctx))
        val cur = m[w] ?: return
        val o = try { JSONObject(cur) } catch (e: Exception) { JSONObject() }
        if (on) o.put("lock", true) else o.remove("lock")
        m[w] = o.toString()
        writeGestures(ctx, m)
    }

    // --- Жест запуска записи голосового по конкретному приложению ---
    fun getRecGesture(ctx: Context, pkg: String): String? {
        if (pkg.isBlank()) return null
        val s = p(ctx).getString("recges_$pkg", null) ?: return null
        return if (s.isBlank()) null else s
    }
    fun setRecGesture(ctx: Context, pkg: String, json: String) {
        if (pkg.isBlank()) return
        p(ctx).edit().putString("recges_$pkg", json).apply()
    }
    fun clearRecGesture(ctx: Context, pkg: String) {
        if (pkg.isBlank()) return
        p(ctx).edit().remove("recges_$pkg").apply()
    }

    // --- Разбор для воспроизведения ---
    /** Один штрих: точки (xДоля, yДоля, tМс). */
    class Stroke(val pts: List<Triple<Float, Float, Long>>)

    fun parse(json: String): List<Stroke> {
        return try {
            val arr = JSONObject(json).optJSONArray("strokes") ?: JSONArray()
            val out = ArrayList<Stroke>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONArray(i) ?: continue
                val pts = ArrayList<Triple<Float, Float, Long>>()
                for (j in 0 until s.length()) {
                    val pt = s.optJSONArray(j) ?: continue
                    pts.add(Triple(pt.optDouble(0).toFloat(), pt.optDouble(1).toFloat(), pt.optLong(2)))
                }
                if (pts.isNotEmpty()) out.add(Stroke(pts))
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    /** Сериализация записанных штрихов (координаты уже в долях) в JSON. */
    fun serialize(strokes: List<List<Triple<Float, Float, Long>>>): String {
        val arr = JSONArray()
        for (st in strokes) {
            val sArr = JSONArray()
            for ((x, y, t) in st) {
                val pt = JSONArray(); pt.put(x.toDouble()); pt.put(y.toDouble()); pt.put(t)
                sArr.put(pt)
            }
            arr.put(sArr)
        }
        return JSONObject().put("strokes", arr).toString()
    }
}
