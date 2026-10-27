package com.openauto.dash.unit.xyauto

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.openauto.dash.DockShell
import com.openauto.dash.UnitLight
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The XYAuto unit's screen light following the car's dimmer (the canbox's, on the bus as
 * [K.INSTRUMENT_DIMMER]). The firmware picks the backlight from `lightOnBrightness` while the headlamp
 * setting is 0 and re-reads it only on a headlamp change, so the level is written there (through the
 * privileged shell, it is not a public setting) and, when the firmware is using it now, to the screen at once.
 * Inert where the canbox sends no dimmer; a shell that fails is logged and the next level tries again.
 */
object XyautoDimmerLight {
    private const val TAG = "XyautoDimmerLight"
    private const val MIN_PCT = 17.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /** The backlight level (0..255) for the car's dimmer in percent; never below [MIN_PCT]. */
    internal fun level(pct: Double): Int = (max(pct, MIN_PCT) * 255 / 100).roundToInt().coerceIn(0, 255)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            Vehicle.bus.value(K.INSTRUMENT_DIMMER).filterNotNull().map { level(it) }.distinctUntilChanged().collect { apply(app, it) }
        }
    }

    private suspend fun apply(context: Context, level: Int) {
        try {
            DockShell.shell(context, "settings put system lightOnBrightness $level")
            // Headlamp 0: the firmware is using lightOnBrightness now, so the screen follows at once.
            if (Settings.System.getInt(context.contentResolver, "Headlamp", 0) == 0) UnitLight.writeBrightness(context, level)
        } catch (e: Exception) {
            Log.w(TAG, "can't set the dimmer light $level: ${e.message}")
        }
    }
}
