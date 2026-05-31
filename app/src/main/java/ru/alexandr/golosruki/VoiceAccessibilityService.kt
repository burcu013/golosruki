package ru.alexandr.golosruki

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Выполняет команды: навигация, нажатия, свайпы, ввод, SOS, разблокировка, запуск приложений. */
class VoiceAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private val targets = mutableMapOf<Int, Rect>()
    private val handler = Handler(Looper.getMainLooper())

    private enum class Mode { NONE, NUMBERS, GRID1, GRID2 }
    private var mode = Mode.NONE

    private val personal by lazy { PersonalConfig.load(this) }

    companion object {
        @Volatile var instance: VoiceAccessibilityService? = null
        const val TAG = "GolosRuki"
        const val GRID_COLS = 3
        const val GRID_ROWS = 4   // 3×4 = 12 ячеек
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this)
        overlay.showStatus("Сон. Скажите: Иван")
        Logger.log("ACC", "Служба спец. возможностей ПОДКЛЮЧЕНА")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (this::overlay.isInitialized) overlay.clearAll()
        instance = null
    }

    fun showStatus(text: String) {
        if (this::overlay.isInitialized) overlay.showStatus(text)
    }

    fun keepScreenOn(on: Boolean) {
        if (this::overlay.isInitialized) overlay.setKeepScreenOn(on)
    }

    fun execute(command: Command) {
        showStatus(command.label())
        when (command) {
            Command.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            Command.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            Command.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            Command.Notifications -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            Command.QuickSettings -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            Command.Lock -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            Command.Screenshot -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            Command.Unlock -> doUnlock()
            Command.Sos -> doSos()
            Command.AnswerCall -> answerCall()
            Command.RejectCall -> endCall()
            Command.SilenceRinger -> silenceRinger()
            Command.VolumeUp -> volume(android.media.AudioManager.ADJUST_RAISE)
            Command.VolumeDown -> volume(android.media.AudioManager.ADJUST_LOWER)
            Command.VolumeMute -> volume(android.media.AudioManager.ADJUST_MUTE)
            Command.MediaPause -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            Command.MediaPlay -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            is Command.Swipe -> doScroll(command.direction)
            Command.ShowNumbers -> showNumbers()
            Command.Grid -> showGrid()
            Command.HideOverlay -> { mode = Mode.NONE; targets.clear(); overlay.clearTargets() }
            is Command.Tap -> onTap(command.number, TapKind.SINGLE)
            is Command.LongPress -> onTap(command.number, TapKind.LONG)
            is Command.DoubleTap -> onTap(command.number, TapKind.DOUBLE)
            is Command.TypeText -> typeText(command.text)
            Command.DeleteText -> deleteText()
            Command.SelectAll -> selectAll()
            Command.ClearText -> clearText()
            Command.EnterKey -> pressEnter()
            is Command.SwipeItem -> swipeItem(command.number, command.direction)
            Command.Dictation -> VoiceRecognitionService.instance?.enterDictation()
            Command.DictationDigits -> VoiceRecognitionService.instance?.enterDictation(true)
            is Command.Drag -> drag(command.from, command.to)
            is Command.ScrollEdge -> scrollEdge(command.direction)
            Command.Paste -> paste()
            Command.TapCenter -> { val (w, h) = screenSize(); doTap(w / 2f, h / 2f, TapKind.SINGLE) }
            is Command.CallContact -> doCall(command.number)
            is Command.OpenApp -> openApp(command.pkg)
            Command.Help -> overlay.showHelp()
            Command.Pause -> VoiceRecognitionService.setPaused(true)
            Command.Resume -> VoiceRecognitionService.setPaused(false)
            Command.Unknown -> showStatus("Не понял команду")
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    // --- Скролл: сначала пробуем прокрутить нужный контейнер, иначе жест по центру ---
    private fun doScroll(direction: Direction) {
        gestureScroll(direction)
    }

    private fun gestureScroll(direction: Direction) {
        val (w, h) = screenSize()
        val cx = w / 2f; val cy = h / 2f

        // калибровка
        val invV = SettingsStore.getSwipeInvertV(this)
        val invH = SettingsStore.getSwipeInvertH(this)
        val frac = when (SettingsStore.getSwipeStrength(this)) {
            1 -> 0.10f; 2 -> 0.16f; 3 -> 0.24f; 4 -> 0.34f; 5 -> 0.45f; else -> 0.16f
        }
        val dy = h * frac; val dx = w * frac

        var dir = direction
        if ((dir == Direction.UP || dir == Direction.DOWN) && invV)
            dir = if (dir == Direction.UP) Direction.DOWN else Direction.UP
        if ((dir == Direction.LEFT || dir == Direction.RIGHT) && invH)
            dir = if (dir == Direction.LEFT) Direction.RIGHT else Direction.LEFT

        val path = Path()
        when (dir) {
            // палец движется в ту же сторону, что и команда
            Direction.DOWN  -> { path.moveTo(cx, cy - dy); path.lineTo(cx, cy + dy) }
            Direction.UP    -> { path.moveTo(cx, cy + dy); path.lineTo(cx, cy - dy) }
            Direction.LEFT  -> { path.moveTo(cx + dx, cy); path.lineTo(cx - dx, cy) }
            Direction.RIGHT -> { path.moveTo(cx - dx, cy); path.lineTo(cx + dx, cy) }
        }
        gesture(path, 200)
    }

    // --- Номера на кликабельных элементах ---
    private var maxNodeArea = Long.MAX_VALUE

    private fun showNumbers() {
        mode = Mode.NUMBERS
        targets.clear()
        val root = rootInActiveWindow ?: run { showStatus("Нет активного окна"); return }
        val (w, h) = screenSize()
        maxNodeArea = (0.85 * w * h).toLong()
        val rects = mutableListOf<Rect>()
        collectClickable(root, rects)
        val labeled = mutableListOf<Pair<Int, Rect>>()
        var n = 1
        for (r in rects) {
            if (labeled.any { isClose(it.second, r) }) continue
            targets[n] = r; labeled.add(n to r); n++
            if (n > 200) break
        }
        overlay.showNumbers(labeled)
        showStatus("Найдено: ${labeled.size}. Скажите: нажми + номер")
    }

    private fun collectClickable(node: AccessibilityNodeInfo?, out: MutableList<Rect>) {
        if (node == null) return
        if (node.isVisibleToUser && isCandidate(node)) {
            val r = Rect(); node.getBoundsInScreen(r)
            val area = r.width().toLong() * r.height()
            if (r.width() > 0 && r.height() > 0 && area in 1 until maxNodeArea) out.add(r)
        }
        for (i in 0 until node.childCount) collectClickable(node.getChild(i), out)
    }

    private fun isCandidate(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable || n.isLongClickable || n.isCheckable || n.isEditable) return true
        val hasText = !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
        return n.isFocusable && hasText
    }

    private fun isClose(a: Rect, b: Rect) =
        Math.abs(a.centerX() - b.centerX()) < 24 && Math.abs(a.centerY() - b.centerY()) < 24

    // --- Сетка 3×4 (12), двухуровневая ---
    private fun showGrid() {
        val (w, h) = screenSize()
        mode = Mode.GRID1
        buildGridCells(Rect(0, 0, w, h))
        showStatus("Сетка: номер ячейки (1–12)")
    }

    private fun buildGridCells(area: Rect) {
        targets.clear()
        val cw = area.width() / GRID_COLS; val ch = area.height() / GRID_ROWS
        val labeled = mutableListOf<Pair<Int, Rect>>()
        var n = 1
        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val cell = Rect(
                    area.left + col * cw, area.top + row * ch,
                    area.left + (col + 1) * cw, area.top + (row + 1) * ch
                )
                targets[n] = cell; labeled.add(n to cell); n++
            }
        }
        overlay.showGrid(labeled)
    }

    // --- Нажатия ---
    private enum class TapKind { SINGLE, LONG, DOUBLE }

    private fun onTap(number: Int, kind: TapKind) {
        when (mode) {
            Mode.GRID1 -> {
                val cell = targets[number] ?: run { showStatus("Нет ячейки $number"); return }
                mode = Mode.GRID2
                buildGridCells(cell)
                showStatus("Уточните: номер (1–12)")
            }
            Mode.GRID2 -> {
                val cell = targets[number] ?: run { showStatus("Нет ячейки $number"); return }
                tapAt(cell.exactCenterX(), cell.exactCenterY(), kind)
                afterTap(kind)
            }
            else -> {  // NUMBERS или NONE
                val r = targets[number] ?: run { showStatus("Нет цели $number"); return }
                tapAt(r.exactCenterX(), r.exactCenterY(), kind)
                afterTap(kind)
            }
        }
    }

    /** После обычного тапа — убрать наложение; после долгого — перенумеровать появившееся меню. */
    private fun afterTap(kind: TapKind) {
        if (kind == TapKind.LONG) {
            resetOverlay()
            handler.postDelayed({ showNumbers() }, 650)  // успеть пронумеровать контекстное меню
        } else {
            resetOverlay()
        }
    }

    private fun resetOverlay() { mode = Mode.NONE; targets.clear(); overlay.clearTargets() }

    /** Нажатие в точке: сначала прямой клик по элементу (надёжно, без «долгого нажатия»),
     *  иначе — жест по координатам. */
    private fun tapAt(x: Float, y: Float, kind: TapKind) {
        if (kind != TapKind.DOUBLE) {
            val node = clickableNodeAt(x.toInt(), y.toInt())
            if (node != null) {
                val action = if (kind == TapKind.LONG)
                    AccessibilityNodeInfo.ACTION_LONG_CLICK
                else AccessibilityNodeInfo.ACTION_CLICK
                if (node.performAction(action)) { Logger.log("ACC", "Клик по элементу"); return }
            }
        }
        doTap(x, y, kind)   // запасной — жест по координатам
    }

    /** Самый маленький (самый точный) кликабельный элемент, содержащий точку. */
    private fun clickableNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var result: AccessibilityNodeInfo? = null
        var smallest = Long.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isVisibleToUser) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.contains(x, y) && (n.isClickable || n.isLongClickable)) {
                    val area = r.width().toLong() * r.height()
                    if (area < smallest) { smallest = area; result = n }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return result
    }

    // --- Свайп/закрытие конкретного элемента по номеру (например, уведомления) ---
    private fun swipeItem(number: Int, direction: Direction) {
        val r = targets[number] ?: run { showStatus("Нет цели $number"); return }
        // Для горизонтального свайва пробуем штатно закрыть (уведомление и т.п.)
        if (direction == Direction.LEFT || direction == Direction.RIGHT) {
            val d = findDismissable(r.centerX(), r.centerY())
            if (d != null && d.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
                Logger.log("ACC", "Элемент закрыт (dismiss)")
                resetOverlay(); return
            }
        }
        swipeAcross(r, direction)
        resetOverlay()
    }

    private fun swipeAcross(rect: Rect, direction: Direction) {
        val (w, h) = screenSize()
        val cx = rect.exactCenterX(); val cy = rect.exactCenterY()
        val path = Path()
        when (direction) {
            Direction.LEFT  -> { path.moveTo(rect.right - 12f, cy); path.lineTo(8f, cy) }
            Direction.RIGHT -> { path.moveTo(rect.left + 12f, cy); path.lineTo(w - 8f, cy) }
            Direction.UP    -> { path.moveTo(cx, rect.bottom - 12f); path.lineTo(cx, maxOf(8f, cy - h * 0.3f)) }
            Direction.DOWN  -> { path.moveTo(cx, rect.top + 12f); path.lineTo(cx, minOf(h - 8f, cy + h * 0.3f)) }
        }
        gesture(path, 280)
    }

    /** Узел под точкой, поддерживающий «закрыть» (ACTION_DISMISS) — для уведомлений. */
    private fun findDismissable(x: Int, y: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var result: AccessibilityNodeInfo? = null
        var smallest = Long.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isVisibleToUser) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.contains(x, y) && n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }) {
                    val area = r.width().toLong() * r.height()
                    if (area < smallest) { smallest = area; result = n }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return result
    }

    private fun doTap(x: Float, y: Float, kind: TapKind) {
        val p = Path().apply { moveTo(x, y); lineTo(x, y) }
        when (kind) {
            TapKind.SINGLE -> gesture(p, 50)
            TapKind.LONG -> gesture(p, 700)
            TapKind.DOUBLE -> { gesture(p, 50); handler.postDelayed({ gesture(Path().apply { moveTo(x, y); lineTo(x, y) }, 50) }, 180) }
        }
    }

    private fun gesture(path: Path, duration: Long) {
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build(), null, null
        )
    }

    // --- Перетаскивание: зажать элемент N и перенести на M ---
    private fun drag(fromN: Int, toN: Int) {
        val a = targets[fromN] ?: run { showStatus("Нет цели $fromN"); return }
        val b = targets[toN] ?: run { showStatus("Нет цели $toN"); return }
        val sx = a.exactCenterX(); val sy = a.exactCenterY()
        val ex = b.exactCenterX(); val ey = b.exactCenterY()
        if (Build.VERSION.SDK_INT >= 26) {
            // 1) удержание (имитация долгого нажатия — «зажать/зафиксировать»)
            val hold = Path().apply { moveTo(sx, sy) }
            val s1 = GestureDescription.StrokeDescription(hold, 0, 500, true)
            dispatchGesture(
                GestureDescription.Builder().addStroke(s1).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) {
                        // 2) перенос (продолжение того же касания)
                        val move = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
                        val s2 = s1.continueStroke(move, 0, 600, false)
                        dispatchGesture(GestureDescription.Builder().addStroke(s2).build(), null, null)
                    }
                }, null
            )
        } else {
            // запасной: один медленный штрих
            val p = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
            gesture(p, 1200)
        }
        Logger.log("ACC", "Перетаскивание $fromN→$toN")
        resetOverlay()
    }

    // --- Листать до конца (несколько свайпов подряд) ---
    private fun scrollEdge(direction: Direction) {
        var i = 0
        fun step() {
            if (i++ >= 8) return
            gestureScroll(direction)
            handler.postDelayed({ step() }, 260)
        }
        step()
    }

    private fun paste() {
        val node = focusedEditable() ?: run { showStatus("Нет поля ввода"); return }
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    // --- Ввод текста ---
    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val f = findEditable(node.getChild(i)); if (f != null) return f
        }
        return null
    }

    /** Заменяет содержимое поля целиком (диктовка) — без подмешивания подсказки поля. */
    fun setFieldText(text: String) {
        val node = focusedEditable() ?: run { showStatus("Нет поля ввода"); return }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel) }
    }

    fun typeText(text: String) {
        val node = focusedEditable() ?: run { showStatus("Нет поля ввода"); return }
        // Пустое поле может отдавать свой хинт («Поле поиска») как текст — игнорируем его
        var existing = node.text?.toString() ?: ""
        if (Build.VERSION.SDK_INT >= 26) {
            if (node.isShowingHintText) existing = ""
            val hint = node.hintText?.toString()
            if (hint != null && existing == hint) existing = ""
        }
        val newText = if (existing.isBlank()) text else "$existing $text"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun deleteText() {
        val node = focusedEditable() ?: return
        val full = node.text?.toString() ?: ""
        val s = node.textSelectionStart; val e = node.textSelectionEnd
        val newText = if (s in 0..full.length && e in 0..full.length && s != e) {
            full.removeRange(minOf(s, e), maxOf(s, e))   // удалить выделенное
        } else full.dropLast(1)                           // иначе один символ
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun selectAll() {
        val node = focusedEditable() ?: run { showStatus("Нет поля ввода"); return }
        val len = node.text?.length ?: 0
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun clearText() {
        val node = focusedEditable() ?: run { showStatus("Нет поля ввода"); return }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pressEnter() {
        val node = focusedEditable() ?: return
        if (Build.VERSION.SDK_INT >= 30)
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        else showStatus("Enter недоступен на этой версии Android")
    }

    // --- Запуск приложения ---
    private fun openApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) { showStatus("Приложение не найдено"); return }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // --- Звонок ---
    private fun doCall(number: String) {
        if (number.isBlank()) { showStatus("Номер не задан"); return }
        Logger.log("ACC", "Звоню на $number")
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: SecurityException) {
            Logger.log("ACC", "Нет CALL_PHONE — открываю набор")
            val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(dial)
        } catch (e: Exception) {
            Logger.log("ACC", "Ошибка звонка: ${e.message}")
            showStatus("Ошибка звонка")
        }
    }

    // --- SOS: СМС с геолокацией + звонок ---
    private fun doSos() {
        val number = personal.sosNumber
        if (number.isBlank()) { showStatus("SOS-номер не задан"); return }
        val loc = lastKnownLocation()
        val mapLink = loc?.let {
            // Яндекс.Карты используют порядок «долгота,широта»
            " Координаты: https://yandex.ru/maps/?pt=${it.longitude},${it.latitude}&z=18&l=map"
        } ?: ""
        val text = personal.sosText + mapLink
        try {
            val sms: SmsManager? = if (Build.VERSION.SDK_INT >= 31)
                getSystemService(SmsManager::class.java)
            else
                SmsManager.getDefault()
            if (sms != null) {
                val parts = sms.divideMessage(text)   // длинный текст — на части
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SOS СМС: ${e.message}")
        }
        // через секунду — звонок
        handler.postDelayed({ doCall(number) }, 1200)
        showStatus("SOS отправлен")
    }

    private fun lastKnownLocation(): Location? {
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
    }

    // --- Управление звонком и звуком ---
    private fun answerCall() {
        try {
            val tm = getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager
            if (Build.VERSION.SDK_INT >= 26) { tm.acceptRingingCall(); Logger.log("ACC", "Ответ на звонок") }
        } catch (e: Exception) {
            Logger.log("ACC", "Ответ не удался: ${e.message}")
            showStatus("Нужно разрешение «Звонки» (ANSWER_PHONE_CALLS)")
        }
    }

    private fun endCall() {
        try {
            val tm = getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager
            if (Build.VERSION.SDK_INT >= 28) { tm.endCall(); Logger.log("ACC", "Сброс звонка") }
            else showStatus("Сброс доступен на Android 9+")
        } catch (e: Exception) {
            Logger.log("ACC", "Сброс не удался: ${e.message}")
            showStatus("Нужно разрешение «Звонки»")
        }
    }

    private fun silenceRinger() {
        // Сначала пробуем TelecomManager, иначе глушим поток звонка
        try {
            val tm = getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager
            tm.silenceRinger(); Logger.log("ACC", "Звонок заглушён"); return
        } catch (e: Exception) {
            Logger.log("ACC", "silenceRinger недоступен: ${e.message}")
        }
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(android.media.AudioManager.STREAM_RING, android.media.AudioManager.ADJUST_MUTE, 0)
        } catch (e: Exception) { showStatus("Не удалось заглушить (нужен доступ к режиму «Не беспокоить»)") }
    }

    private fun volume(adjust: Int) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, adjust, android.media.AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) { Logger.log("ACC", "Громкость: ${e.message}") }
    }

    private fun mediaKey(code: Int) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            Logger.log("ACC", "Медиа-клавиша $code")
        } catch (e: Exception) { Logger.log("ACC", "Медиа: ${e.message}") }
    }

    // --- Пробуждение / разблокировка ---
    private fun doUnlock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "GolosRuki:wake"
            )
            wl.acquire(3000)
        } catch (_: Exception) {}
        startActivity(
            Intent(this, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
