package ru.alexandr.golosruki

import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock

/**
 * v8.11 — ДИАГНОСТИКА аудио-маршрутизации. ТОЛЬКО логи, поведение приложения не меняется.
 *
 * Замеряет на реальных устройствах:
 *  • задержку реального подключения BT-микрофона (от запроса до активного устройства связи);
 *  • поведение музыки (A2DP) в момент захвата микрофона — глушится / встаёт / резюмируется;
 *  • имя и тип подключённого устройства (наушники vs автомагнитола).
 *
 * По этим данным делается нормальная реализация внешнего микрофона БЕЗ догадок.
 * Выключается одним флагом ENABLED (тогда ни одной строки в лог не пишет).
 */
object AudioDiag {
    @Volatile var ENABLED = true

    private var lastState = ""
    private var lastChangeAt = 0L
    private var scoRequestedAt = 0L
    private var lastHeartbeatAt = 0L

    /** Отметить момент запроса BT-микрофона — для замера задержки до реального подключения. */
    fun markScoRequested() {
        if (!ENABLED) return
        scoRequestedAt = SystemClock.elapsedRealtime()
        Logger.log("DIAG", "запрошен BT-микрофон (t0)")
    }

    /** Периодический опрос. Пишем в лог только при ИЗМЕНЕНИИ состояния + редкий «пульс». */
    fun poll(am: AudioManager) {
        if (!ENABLED) return
        val now = SystemClock.elapsedRealtime()
        val mode = when (am.mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMM"
            else -> "mode${am.mode}"
        }
        val music = am.isMusicActive
        val scoOn = runCatching { am.isBluetoothScoOn }.getOrDefault(false)
        var comm = "нет"
        var avail = "n/a"
        if (Build.VERSION.SDK_INT >= 31) {
            comm = runCatching {
                am.communicationDevice?.let { devLabel(it.type, it.productName?.toString()) } ?: "нет"
            }.getOrDefault("?")
            avail = runCatching {
                am.availableCommunicationDevices.joinToString { devLabel(it.type, it.productName?.toString()) }
            }.getOrDefault("?")
        }
        val st = "mode=$mode music=$music scoOn=$scoOn comm=$comm"
        if (st != lastState) {
            val dt = if (lastChangeAt > 0) now - lastChangeAt else 0
            val sinceReq = if (scoRequestedAt > 0) " | от запроса=${now - scoRequestedAt}мс" else ""
            Logger.log("DIAG", "$st | устройства=[$avail]$sinceReq (изм. через ${dt}мс)")
            lastState = st
            lastChangeAt = now
            lastHeartbeatAt = now
        } else if (now - lastHeartbeatAt >= 10000L) {
            Logger.log("DIAG", "пульс: $st")
            lastHeartbeatAt = now
        }
    }

    private fun devLabel(type: Int, name: String?): String {
        val t = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "ВСТР_МИК"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "ДИНАМИК"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "ПРОВОД_ГАРН"
            else -> "тип$type"
        }
        return if (name.isNullOrBlank()) t else "$t:$name"
    }
}
