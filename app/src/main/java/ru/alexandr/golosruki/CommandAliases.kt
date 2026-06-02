package ru.alexandr.golosruki

/**
 * Слой персонализации: пользователь может задать СВОИ слова-триггеры для команд
 * (супер-режим разработчика) и «дообучить» Ивана, сопоставив реально расслышанную
 * фразу нужной команде (словарь коррекций). И то, и другое — одна карта: фраза → ключ команды.
 *
 * Слой проверяется ПЕРВЫМ в парсере, поэтому свои слова имеют приоритет над дефолтными,
 * но НЕ ломают встроенные команды (если совпадения нет — работает обычный разбор).
 */
object CommandAliases {

    data class Entry(val key: String, val label: String, val make: () -> Command)

    // Переназначаемые команды без параметров (понятные ключи + русские названия)
    val registry: List<Entry> = listOf(
        Entry("back", "Назад", { Command.Back }),
        Entry("home", "Домой", { Command.Home }),
        Entry("recents", "Недавние приложения", { Command.Recents }),
        Entry("notifications", "Шторка уведомлений", { Command.Notifications }),
        Entry("quick", "Быстрые настройки", { Command.QuickSettings }),
        Entry("lock", "Заблокировать", { Command.Lock }),
        Entry("scroll_down", "Листать вниз", { Command.Swipe(Direction.DOWN) }),
        Entry("scroll_up", "Листать вверх", { Command.Swipe(Direction.UP) }),
        Entry("scroll_left", "Свайп влево", { Command.Swipe(Direction.LEFT) }),
        Entry("scroll_right", "Свайп вправо", { Command.Swipe(Direction.RIGHT) }),
        Entry("numbers", "Показать номера", { Command.ShowNumbers }),
        Entry("grid", "Сетка", { Command.Grid }),
        Entry("tap_center", "Тап по центру", { Command.TapCenter }),
        Entry("pause_media", "Пауза (видео/музыка)", { Command.MediaPause }),
        Entry("play_media", "Играть", { Command.MediaPlay }),
        Entry("next", "Следующий трек", { Command.MediaNext }),
        Entry("prev", "Предыдущий трек", { Command.MediaPrev }),
        Entry("vol_up", "Громче", { Command.VolumeUp }),
        Entry("vol_down", "Тише", { Command.VolumeDown }),
        Entry("mute", "Без звука", { Command.VolumeMute }),
        Entry("dictation", "Диктовка текста", { Command.Dictation }),
        Entry("digits", "Диктовка цифрами", { Command.DictationDigits }),
        Entry("copy", "Копировать", { Command.CopyText }),
        Entry("paste", "Вставить", { Command.Paste }),
        Entry("select_all", "Выделить всё", { Command.SelectAll }),
        Entry("clear", "Очистить поле", { Command.ClearText }),
        Entry("delete", "Удалить символ", { Command.DeleteText }),
        Entry("enter", "Ввод (Enter)", { Command.EnterKey }),
        Entry("answer", "Ответить на звонок", { Command.AnswerCall }),
        Entry("reject", "Сбросить звонок", { Command.RejectCall }),
        Entry("sos", "SOS", { Command.Sos }),
        Entry("help", "Помощь", { Command.Help }),
        Entry("hide", "Скрыть наложение", { Command.HideOverlay })
    )

    fun labelFor(key: String): String = registry.find { it.key == key }?.label ?: key
    fun commandFor(key: String): Command? = registry.find { it.key == key }?.make?.invoke()

    /** Текущая карта: фраза(в нижнем регистре) → ключ команды. Устанавливается службой при загрузке. */
    @Volatile
    var aliasMap: Map<String, String> = emptyMap()

    /** Подобрать команду по пользовательской фразе (точное вхождение фразы в распознанный текст). */
    fun match(text: String): Command? {
        if (aliasMap.isEmpty()) return null
        // сначала более длинные фразы (точнее), затем короткие
        for ((phrase, key) in aliasMap.entries.sortedByDescending { it.key.length }) {
            if (phrase.isNotBlank() && text.contains(phrase)) return commandFor(key)
        }
        return null
    }

    /** Слова из пользовательских фраз — добавляются в грамматику распознавания. */
    fun aliasWords(): List<String> =
        aliasMap.keys.flatMap { it.split(" ") }.filter { it.isNotBlank() }
}
