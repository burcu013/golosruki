package ru.alexandr.golosruki

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Копирует модель Vosk из assets/model-ru во внутреннюю память без StorageService/uuid. */
object ModelInstaller {

    private const val ASSET = "model-ru"

    /** Возвращает путь к готовой модели. Бросает исключение с понятным текстом, если модели нет. */
    fun ensureModel(ctx: Context): String {
        val target = File(ctx.filesDir, ASSET)
        val marker = File(target, ".installed")
        if (marker.exists()) {
            Logger.log("MODEL", "Модель уже установлена: ${target.absolutePath}")
            return target.absolutePath
        }
        if (target.exists()) target.deleteRecursively()

        val am = ctx.assets
        val top = am.list(ASSET) ?: emptyArray()
        Logger.log("MODEL", "В assets/$ASSET найдено элементов: ${top.size}")
        if (top.isEmpty())
            throw IOException("В APK нет папки assets/$ASSET — модель не собралась. Проверьте лог сборки на GitHub.")

        var count = copyDir(am, ASSET, target)
        marker.writeText("ok")
        Logger.log("MODEL", "Скопировано файлов: $count")
        return target.absolutePath
    }

    /** Рекурсивно копирует ассет (файл или папку). Возвращает число скопированных файлов. */
    private fun copyDir(am: AssetManager, path: String, out: File): Int {
        val items = am.list(path) ?: emptyArray()
        if (items.isEmpty()) {
            // это файл
            out.parentFile?.mkdirs()
            am.open(path).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
            return 1
        }
        out.mkdirs()
        var n = 0
        for (item in items) n += copyDir(am, "$path/$item", File(out, item))
        return n
    }
}
