package ru.alexandr.golosruki

enum class Direction { UP, DOWN, LEFT, RIGHT }

/** Все поддерживаемые команды. */
sealed class Command {
    object Back : Command()
    object Home : Command()
    object Recents : Command()
    object Notifications : Command()
    object QuickSettings : Command()
    object Lock : Command()
    object Screenshot : Command()
    object Unlock : Command()                 // «Иван привет» — разблокировка/пробуждение
    object Sos : Command()                    // звонок + СМС с геолокацией
    object AnswerCall : Command()             // ответить на входящий
    object RejectCall : Command()             // отклонить / завершить
    object SilenceRinger : Command()          // беззвучно (заглушить звонок)
    object VolumeUp : Command()
    object VolumeDown : Command()
    object VolumeMute : Command()
    data class SetVolume(val level: Int) : Command()    // точная громкость 1..10
    data class RecordVoice(val number: Int) : Command() // голосовое: зажать микрофон + свайп вверх (блокировка)
    object RecordSend : Command()                        // отправить записанное голосовое
    object RecordCancel : Command()                      // отменить запись голосового (Назад)
    object CalibrateRecord : Command()                   // калибровка точки кнопки записи (выбором номера)
    object CalibrateRecordGesture : Command()            // запись жеста запуска голосового (для приложения)
    data class CustomGesture(val name: String, val json: String) : Command()  // воспроизвести кастомный жест
    object MediaPause : Command()             // пауза воспроизведения
    object MediaPlay : Command()              // воспроизвести
    object MediaNext : Command()              // следующий трек
    object MediaPrev : Command()              // предыдущий трек
    data class Swipe(val direction: Direction, val fine: Boolean = false) : Command()
    object ShowNumbers : Command()
    object Grid : Command()                   // сетка 3×4 = 12 ячеек, двухуровневая
    object HideOverlay : Command()
    data class Tap(val number: Int) : Command()
    data class LongPress(val number: Int) : Command()
    data class DoubleTap(val number: Int) : Command()
    data class TypeText(val text: String) : Command()
    object DeleteText : Command()
    object SelectAll : Command()              // выделить всё в поле
    object CopyText : Command()               // копировать выделенное
    object ClearText : Command()              // очистить поле
    object EnterKey : Command()
    data class SwipeItem(val number: Int, val direction: Direction) : Command()  // свайп элемента по номеру
    object Dictation : Command()              // режим диктовки (свободный текст)
    object DictationDigits : Command()        // диктовка цифрами (телефон)
    data class Drag(val from: Int, val to: Int) : Command()   // перетащить элемент N на M
    data class ScrollEdge(val direction: Direction) : Command() // листать до конца
    object AiAsk : Command()                   // «Иван спроси …» — вопрос к ИИ (вопрос захватывается свободно)
    object AiCompose : Command()               // «Иван сформулируй …» — ИИ собирает текст
    object Paste : Command()                  // вставить из буфера
    object TapCenter : Command()              // тап по центру экрана (пауза/плей в плеерах)
    data class TapText(val label: String) : Command()        // нажать кнопку по надписи
    data class AutoScroll(val direction: Direction) : Command() // непрерывное листание
    data class CallContact(val name: String, val number: String) : Command()
    data class CallQuery(val query: String) : Command()   // «позвони X» — служба разрулит (поиск/выбор)
    data class OpenApp(val name: String, val pkg: String) : Command()
    object Help : Command()
    object Pause : Command()
    object Resume : Command()
    object Unknown : Command()

    fun label(): String = when (this) {
        Back -> "Назад"
        Home -> "Домой"
        Recents -> "Недавние"
        Notifications -> "Уведомления"
        QuickSettings -> "Быстрые настройки"
        Lock -> "Блокировка"
        Screenshot -> "Скриншот"
        Unlock -> "Разблокировка"
        Sos -> "SOS! Вызов помощи"
        AnswerCall -> "Ответ на звонок"
        RejectCall -> "Сброс звонка"
        SilenceRinger -> "Звонок беззвучно"
        VolumeUp -> "Громче"
        VolumeDown -> "Тише"
        VolumeMute -> "Без звука"
        is SetVolume -> "Громкость $level из 10"
        is RecordVoice -> "Запись голосового" + (if (number > 0) " №$number" else "")
        RecordSend -> "Отправить голосовое"
        RecordCancel -> "Отменить голосовое"
        CalibrateRecord -> "Калибровка кнопки записи"
        CalibrateRecordGesture -> "Калибровка жеста записи"
        is CustomGesture -> "Жест: $name"
        MediaPause -> "Пауза видео"
        MediaPlay -> "Воспроизвести"
        MediaNext -> "Следующий трек"
        MediaPrev -> "Предыдущий трек"
        is Swipe -> (if (fine) "Короткий свайп " else "Свайп ") + direction.name.lowercase()
        ShowNumbers -> "Показываю номера"
        Grid -> "Сетка 12"
        HideOverlay -> "Скрыто"
        is Tap -> "Выбор $number"
        is LongPress -> "Долгое нажатие $number"
        is DoubleTap -> "Двойное нажатие $number"
        is TypeText -> "Ввод: $text"
        DeleteText -> "Удаление"
        SelectAll -> "Выделить всё"
        CopyText -> "Копировать"
        ClearText -> "Очистка поля"
        EnterKey -> "Ввод (Enter)"
        is SwipeItem -> "Свайп $number ${direction.name.lowercase()}"
        Dictation -> "Диктовка"
        DictationDigits -> "Диктовка цифрами"
        is Drag -> "Перетащить $from → $to"
        is ScrollEdge -> "До конца ${direction.name.lowercase()}"
        Paste -> "Вставить"
        TapCenter -> "Тап по центру"
        is TapText -> "Нажать «$label»"
        is AutoScroll -> "Листать ${direction.name.lowercase()}"
        is CallContact -> "Звоню: $name"
        is CallQuery -> "Звонок: $query"
        is OpenApp -> "Открываю: $name"
        Help -> "Помощь"
        Pause -> "Пауза"
        Resume -> "Слушаю"
        AiAsk -> "🧠 Вопрос ИИ"
        AiCompose -> "🧠 Сформулировать"
        Unknown -> "Не понял команду"
    }
}
