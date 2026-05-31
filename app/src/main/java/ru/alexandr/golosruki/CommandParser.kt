package ru.alexandr.golosruki

/** Преобразует распознанный русский текст в команду. */
object CommandParser {

    private val numberWords = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8,
        "девять" to 9, "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12,
        "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15,
        "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19, "двадцать" to 20, "тридцать" to 30, "сорок" to 40,
        "пятьдесят" to 50
    )

    fun parse(raw: String, personal: PersonalConfig): Command {
        val t = raw.lowercase().trim()
        if (t.isEmpty()) return Command.Unknown

        // 0. Разблокировка («привет» после «Иван»)
        if (t.contains("привет")) return Command.Unlock

        // 0.1 SOS — отдельные слова, чтобы не путать с «помощь» (это help)
        if (t.contains("сос") || t.contains("спасите") || t.contains("тревога") || t.contains("помогите"))
            return Command.Sos

        // 0.15 Управление звонком и звуком
        if (t.contains("ответь") || t.contains("ответить") || t.contains("прими")) return Command.AnswerCall
        if (t.contains("сбрось") || t.contains("отклони") || t.contains("заверши") ||
            t.contains("положи трубку") || t.contains("отбой")) return Command.RejectCall
        if (t.contains("беззвучно") || t.contains("заглуши") || t.contains("тихо")) return Command.SilenceRinger
        if (t.contains("без звука") || t.contains("выключи звук") || t.contains("отключи звук")) return Command.VolumeMute
        if (t.contains("громче")) return Command.VolumeUp
        if (t.contains("тише")) return Command.VolumeDown

        // 0.2 Диктовка / ввод текста — только через свободный распознаватель
        if (t.contains("цифр") || t.contains("номер телефона")) return Command.DictationDigits
        if (t.contains("диктовка") || t.contains("печатать") ||
            t.contains("напиши") || t.contains("введи") || t.contains("набрать текст"))
            return Command.Dictation

        // 0.3 Персональные: позвонить
        if (t.contains("позвони") || t.contains("набери")) {
            for ((name, num) in personal.contacts) {
                if (num.isNotBlank() && t.contains(name)) return Command.CallContact(name, num)
            }
        }
        // 0.35 Кастомные команды запуска (своя фраза -> приложение)
        for ((phrase, pkg) in personal.customApps) {
            if (pkg.isNotBlank() && phrase.isNotBlank() && t.contains(phrase))
                return Command.OpenApp(phrase, pkg)
        }
        // 0.4 Персональные: открыть приложение
        if (t.contains("открой") || t.contains("запусти")) {
            for ((name, pkg) in personal.apps) {
                if (pkg.isNotBlank() && t.contains(name)) return Command.OpenApp(name, pkg)
            }
        }

        // 1. Навигация и управление
        when {
            t.contains("назад") || t.contains("выход") -> return Command.Back
            t.contains("домой") || t.contains("главный экран") -> return Command.Home
            t.contains("недавн") || t.contains("переключатель") -> return Command.Recents
            t.contains("шторк") || t.contains("уведомлен") -> return Command.Notifications
            t.contains("быстрые настройки") -> return Command.QuickSettings
            t.contains("заблокир") || t == "блокировка" -> return Command.Lock
            t.contains("скриншот") || t.contains("снимок экрана") -> return Command.Screenshot
            t.contains("помощь") || t.contains("команды") -> return Command.Help
            t.contains("молчи") || t.contains("замолчи") || t.contains("замри") -> return Command.Pause
            t.contains("пауза") || t.contains("останови") || t.contains("стоп") || t.contains("хватит") -> return Command.MediaPause
            t.contains("воспроизв") || t.contains("играй") || t.contains("плей") -> return Command.MediaPlay
            t.contains("слушай") || t.contains("продолжи") -> return Command.Resume
            t.contains("скрой") || t.contains("убери") || t.contains("спрячь") || t.contains("отмена") -> return Command.HideOverlay
            t.contains("номера") || t.contains("кнопки") -> return Command.ShowNumbers
            t.contains("сетк") -> return Command.Grid
            t.contains("ввод") || t.contains("отправ") || t.contains("энтер") -> return Command.EnterKey
            t.contains("вставь") || t.contains("вставить") -> return Command.Paste
            t.contains("выдел") -> return Command.SelectAll
            t.contains("очист") -> return Command.ClearText
            (t.contains("удали") || t.contains("стер") || t.contains("сотри")) && t.contains("всё") -> return Command.ClearText
            t.contains("удали") || t.contains("стереть") || t.contains("сотри") -> return Command.DeleteText
        }

        // перетаскивание: «перетащи 3 на 7», «потяни 2 к 5»
        if (t.contains("перетащи") || t.contains("потяни") || t.contains("перенеси") || t.contains("перетянуть")) {
            val nums = NumberWords.allNumbers(t)
            if (nums.size >= 2) return Command.Drag(nums[0], nums[1])
        }

        // листать до конца: «в самый низ», «вниз до конца», «в начало»
        if (t.contains("до конца") || t.contains("самый") || t.contains("в конец") || t.contains("в начало")) {
            val dir = when {
                t.contains("низ") || t.contains("вниз") || t.contains("конец") -> Direction.DOWN
                t.contains("верх") || t.contains("вверх") || t.contains("начал") || t.contains("наверх") -> Direction.UP
                else -> null
            }
            if (dir != null) return Command.ScrollEdge(dir)
        }

        // свайп конкретного элемента: «3 влево», «смахни 5 вправо» (число + направление в одной фразе)
        run {
            val numI = extractNumber(t)
            val dir = dirOf(t)
            if (numI != null && dir != null) return Command.SwipeItem(numI, dir)
            if ((t.contains("смахни") || t.contains("свайпни") || t.contains("смахн")) && numI != null)
                return Command.SwipeItem(numI, dir ?: Direction.LEFT)
        }

        // 2. Свайпы
        when {
            t.contains("вверх") -> return Command.Swipe(Direction.UP)
            t.contains("вниз") -> return Command.Swipe(Direction.DOWN)
            t.contains("влево") -> return Command.Swipe(Direction.LEFT)
            t.contains("вправо") -> return Command.Swipe(Direction.RIGHT)
        }

        // 3. Нажатия с номером
        val num = extractNumber(t)
        if (num != null) {
            return when {
                t.contains("долг") || t.contains("удерж") -> Command.LongPress(num)
                t.contains("двойн") || t.contains("дважды") -> Command.DoubleTap(num)
                else -> Command.Tap(num)
            }
        }

        // 4. (Ввод текста выполняется в режиме диктовки — см. п.0.2)

        return Command.Unknown
    }

    /** Разрешённые команды во время воспроизведения медиа (после слова активации). */
    fun parseMedia(raw: String): Command? {
        val t = raw.lowercase()
        return when {
            t.contains("громче") -> Command.VolumeUp
            t.contains("тише") -> Command.VolumeDown
            t.contains("без звука") || t.contains("выключи звук") -> Command.VolumeMute
            t.contains("пауза") || t.contains("останови") || t.contains("стоп") -> Command.MediaPause
            t.contains("воспроизв") || t.contains("играй") || t.contains("плей") || t.contains("продолж") -> Command.MediaPlay
            t.contains("назад") || t.contains("выход") || t.contains("закрой") -> Command.Back
            t.contains("домой") -> Command.Home
            else -> null
        }
    }

    private fun dirOf(t: String): Direction? = when {
        t.contains("вверх") -> Direction.UP
        t.contains("вниз") -> Direction.DOWN
        t.contains("влево") -> Direction.LEFT
        t.contains("вправо") -> Direction.RIGHT
        else -> null
    }

    private fun extractNumber(t: String): Int? {
        Regex("\\d+").find(t)?.let { return it.value.toIntOrNull() }
        val tokens = t.split(" ", ",", "-")
        var total = 0
        var found = false
        for (tok in tokens) {
            val v = numberWords[tok]
            if (v != null) { total += v; found = true }
        }
        return if (found) total else null
    }
}
