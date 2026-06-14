package ru.alexandr.golosruki

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Задача/план секретаря. */
data class Task(
    val id: String,
    val title: String,
    val person: String,
    val project: String,
    val dueMillis: Long,        // 0 — без времени
    val reminderMin: Int,
    val note: String,
    var status: String,         // open / done
    val created: Long
)

/**
 * Память секретаря. Абстракция: сейчас локальная (JSON в файле), позже — Джарвис-сервер
 * (та же сигнатура, другая реализация). Данные хранятся в простом JSON — легко выгрузить и перенести.
 */
interface SecretaryMemory {
    fun addTask(t: Task)
    fun openTasks(): List<Task>
    fun searchTasks(query: String): List<Task>      // открытые задачи по ключевым словам запроса
    fun searchLog(query: String, limit: Int): List<String>  // записи лога по ключевым словам
    fun recentLog(limit: Int): List<String>
    fun completeTask(id: String)
    fun people(): List<String>
    fun projects(): List<String>
    fun learn(project: String?, person: String?)   // запомнить новый проект/человека
    fun log(entry: String)
    fun exportJson(): String
}

/** Локальная реализация: один JSON-файл в каталоге приложения. */
class LocalMemory(private val ctx: Context) : SecretaryMemory {
    private val file = File(ctx.filesDir, "secretary.json")
    private val root: JSONObject = load()

    private fun load(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun save() { try { file.writeText(root.toString()) } catch (e: Exception) { Logger.log("SEC", "Сохранение памяти: ${e.message}") } }

    private fun arr(key: String): JSONArray {
        if (!root.has(key)) root.put(key, JSONArray())
        return root.getJSONArray(key)
    }

    override fun addTask(t: Task) {
        val o = JSONObject().apply {
            put("id", t.id); put("title", t.title); put("person", t.person); put("project", t.project)
            put("due", t.dueMillis); put("reminder", t.reminderMin); put("note", t.note)
            put("status", t.status); put("created", t.created)
        }
        arr("tasks").put(o)
        learn(t.project, t.person)
        save()
    }

    override fun openTasks(): List<Task> {
        val a = arr("tasks"); val out = ArrayList<Task>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("status", "open") != "open") continue
            out.add(Task(o.optString("id"), o.optString("title"), o.optString("person"),
                o.optString("project"), o.optLong("due"), o.optInt("reminder"),
                o.optString("note"), o.optString("status", "open"), o.optLong("created")))
        }
        return out.sortedBy { if (it.dueMillis == 0L) Long.MAX_VALUE else it.dueMillis }
    }

    override fun completeTask(id: String) {
        val a = arr("tasks")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == id) { o.put("status", "done"); save(); return }
        }
    }

    private val stop = setOf("что", "как", "где", "когда", "кто", "это", "мне", "нас", "про",
        "дела", "делам", "деле", "иван", "справка", "секретарь", "статус", "был", "было", "были")

    private fun keywords(q: String): List<String> =
        q.lowercase().split(" ", ",", ".").map { it.trim() }
            .filter { it.length >= 3 && it !in stop }

    override fun searchTasks(query: String): List<Task> {
        val kws = keywords(query)
        val all = openTasks()
        if (kws.isEmpty()) return all
        return all.filter { t ->
            val hay = "${t.title} ${t.project} ${t.person} ${t.note}".lowercase()
            kws.any { hay.contains(it) }
        }
    }

    override fun searchLog(query: String, limit: Int): List<String> {
        val a = arr("log"); val kws = keywords(query)
        val out = ArrayList<String>()
        for (i in a.length() - 1 downTo 0) {
            val txt = a.getJSONObject(i).optString("text")
            if (txt.isBlank()) continue
            if (kws.isEmpty() || kws.any { txt.lowercase().contains(it) }) out.add(txt)
            if (out.size >= limit) break
        }
        return out
    }

    override fun recentLog(limit: Int): List<String> = searchLog("", limit)

    override fun people(): List<String> = arr("people").let { a -> (0 until a.length()).map { a.getString(it) } }
    override fun projects(): List<String> = arr("projects").let { a -> (0 until a.length()).map { a.getString(it) } }

    override fun learn(project: String?, person: String?) {
        fun addUnique(key: String, value: String?) {
            val v = value?.trim() ?: return
            if (v.isBlank()) return
            val a = arr(key)
            for (i in 0 until a.length()) if (a.getString(i).equals(v, true)) return
            a.put(v)
        }
        addUnique("projects", project); addUnique("people", person); save()
    }

    override fun log(entry: String) {
        arr("log").put(JSONObject().put("t", System.currentTimeMillis()).put("text", entry))
        save()
    }

    override fun exportJson(): String = root.toString(2)
}
