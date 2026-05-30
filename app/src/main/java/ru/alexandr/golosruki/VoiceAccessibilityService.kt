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
        Log.d(TAG, "Служба подключена")
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
            is Command.Swipe -> doSwipe(command.direction)
            Command.ShowNumbers -> showNumbers()
            Command.Grid -> showGrid()
            Command.HideOverlay -> { mode = Mode.NONE; targets.clear(); overlay.clearTargets() }
            is Command.Tap -> onTap(command.number, TapKind.SINGLE)
            is Command.LongPress -> onTap(command.number, TapKind.LONG)
            is Command.DoubleTap -> onTap(command.number, TapKind.DOUBLE)
            is Command.TypeText -> typeText(command.text)
            Command.DeleteText -> deleteText()
            Command.EnterKey -> pressEnter()
            Command.Dictation -> VoiceRecognitionService.instance?.enterDictation()
            is Command.CallContact -> doCall(command.number)
            is Command.OpenApp -> openApp(command.pkg)
            Command.Help -> overlay.showHelp()
            Command.Pause -> VoiceRecognitionService.setPaused(true)
            Command.Resume -> VoiceRecognitionService.setPaused(false)
            Command.Unknown -> showStatus("Не понял команду")
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    // --- Свайпы ---
    private fun doSwipe(direction: Direction) {
        val (w, h) = screenSize()
        val cx = w / 2f; val cy = h / 2f; val dx = w * 0.35f; val dy = h * 0.35f
        val path = Path()
        when (direction) {
            Direction.UP    -> { path.moveTo(cx, cy + dy); path.lineTo(cx, cy - dy) }
            Direction.DOWN  -> { path.moveTo(cx, cy - dy); path.lineTo(cx, cy + dy) }
            Direction.LEFT  -> { path.moveTo(cx + dx, cy); path.lineTo(cx - dx, cy) }
            Direction.RIGHT -> { path.moveTo(cx - dx, cy); path.lineTo(cx + dx, cy) }
        }
        gesture(path, 300)
    }

    // --- Номера на кликабельных элементах ---
    private fun showNumbers() {
        mode = Mode.NUMBERS
        targets.clear()
        val root = rootInActiveWindow ?: run { showStatus("Нет активного окна"); return }
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
        showStatus("Скажите: нажми + номер")
    }

    private fun collectClickable(node: AccessibilityNodeInfo?, out: MutableList<Rect>) {
        if (node == null) return
        if (node.isVisibleToUser && (node.isClickable || node.isLongClickable || node.isCheckable)) {
            val r = Rect(); node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) out.add(r)
        }
        for (i in 0 until node.childCount) collectClickable(node.getChild(i), out)
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
                doTap(cell.exactCenterX(), cell.exactCenterY(), kind)
                resetOverlay()
            }
            else -> {  // NUMBERS или NONE
                val r = targets[number] ?: run { showStatus("Нет цели $number"); return }
                doTap(r.exactCenterX(), r.exactCenterY(), kind)
                resetOverlay()
            }
        }
    }

    private fun resetOverlay() { mode = Mode.NONE; targets.clear(); overlay.clearTargets() }

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

    fun typeText(text: String) {
        val node = focusedEditable() ?: run { showStatus("Нет поля ввода"); return }
        val existing = node.text?.toString() ?: ""
        val newText = if (existing.isEmpty()) text else "$existing $text"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun deleteText() {
        val node = focusedEditable() ?: return
        val existing = node.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing.dropLast(1))
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
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: SecurityException) {
            // нет разрешения CALL_PHONE — откроем набор номера
            val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(dial)
        }
    }

    // --- SOS: СМС с геолокацией + звонок ---
    private fun doSos() {
        val number = personal.sosNumber
        if (number.isBlank()) { showStatus("SOS-номер не задан"); return }
        val loc = lastKnownLocation()
        val mapLink = loc?.let { " Координаты: https://maps.google.com/?q=${it.latitude},${it.longitude}" } ?: ""
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
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) { null }
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
