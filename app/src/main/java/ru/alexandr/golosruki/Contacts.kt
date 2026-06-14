package ru.alexandr.golosruki

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Контакты для звонков: заданные в приложении + (опц.) телефонная книга.
 * Даёт токены имён для грамматики Vosk и нечёткий поиск кандидатов (для окна выбора).
 */
object Contacts {
    data class C(val name: String, val number: String)

    @Volatile private var cache: List<C>? = null
    @Volatile private var tokensCache: List<String>? = null

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun invalidate() { cache = null; tokensCache = null }

    /** Заданные в приложении + системные (если включено и разрешено). */
    fun all(ctx: Context): List<C> {
        cache?.let { return it }
        val list = ArrayList<C>()
        for ((n, num) in SettingsStore.getContacts(ctx)) if (num.isNotBlank()) list.add(C(n.lowercase().trim(), num))
        if (SettingsStore.getUseSystemContacts(ctx) && hasPermission(ctx)) {
            try {
                val seen = HashSet<String>()
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ), null, null, null
                )?.use { c ->
                    val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val nm = c.getString(ni)?.lowercase()?.trim() ?: continue
                        val num = c.getString(pi)?.replace(" ", "")?.trim() ?: continue
                        if (nm.isBlank() || num.isBlank()) continue
                        if (!seen.add(nm)) continue   // один номер на имя
                        list.add(C(nm, num))
                        if (list.size > 2000) break
                    }
                }
            } catch (e: Exception) { Logger.log("CONTACTS", "Ошибка чтения: ${e.message}") }
        }
        cache = list
        return list
    }

    /** Токены имён для грамматики Vosk (слова из имён, дедуп, ограничение). */
    fun nameTokens(ctx: Context): List<String> {
        tokensCache?.let { return it }
        val set = LinkedHashSet<String>()
        loop@ for (c in all(ctx)) {
            for (w in c.name.split(" ", "-")) {
                val t = w.trim()
                if (t.length >= 3 && t.all { ch -> ch.isLetter() }) set.add(t)
                if (set.size >= 400) break@loop
            }
        }
        val res = set.toList()
        tokensCache = res
        return res
    }

    /** Нечёткий поиск кандидатов по произнесённому имени. Лучшие — первыми, до 5. */
    fun search(ctx: Context, query: String): List<C> {
        val skip = setOf("позвони", "набери", "звонок", "вызови", "напиши", "иван", "номер", "телефон")
        val words = query.lowercase().split(" ").map { it.trim() }.filter { it.isNotBlank() && it !in skip }
        if (words.isEmpty()) return emptyList()
        val joined = words.joinToString("")
        val phrase = words.joinToString(" ")
        val ql = query.lowercase()

        data class Scored(val c: C, val tier: Int, val dist: Int)
        val scored = ArrayList<Scored>()
        for (c in all(ctx)) {
            val name = c.name
            val last = name.substringAfterLast(" ")
            val nameJoined = name.replace(" ", "")
            var tier = 99; var dist = 99
            if (phrase.contains(name) || ql.contains(name)) { tier = 0; dist = 0 }
            else if (last.length >= 3 && words.any { it == last }) { tier = 0; dist = 0 }
            else if (nameJoined.length >= 5 && (joined.contains(nameJoined) || nameJoined.contains(joined))) { tier = 0; dist = 1 }
            else {
                for (tok in name.split(" ").filter { it.length >= 3 }) {
                    val stem = tok.take(maxOf(3, tok.length - 2))
                    if (words.any { it.startsWith(stem) || tok.startsWith(it.take(maxOf(3, it.length - 1))) }) { tier = 1; dist = 0; break }
                }
                if (tier > 1) {
                    for (tok in name.split(" ").filter { it.length >= 3 }) {
                        val thr = if (tok.length <= 4) 1 else 2
                        val d = words.filter { it.length >= 3 }.minOfOrNull { levenshtein(it, tok) } ?: continue
                        if (d <= thr && d < dist) { tier = 2; dist = d }
                    }
                }
            }
            if (tier <= 2) scored.add(Scored(c, tier, dist))
        }
        return scored.sortedWith(compareBy({ it.tier }, { it.dist }, { it.c.name.length }))
            .map { it.c }.distinctBy { it.number }.take(5)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
