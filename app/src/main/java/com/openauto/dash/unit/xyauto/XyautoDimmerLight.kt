package com.openauto.dash.unit.xyauto

import android.content.Context
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
 * [K.INSTRUMENT_DIMMER]). The firmware picks the backlight from `lightOnBrightness` (headlamp setting 0) or
 * `lightOffBrightness` (1) and re-reads them only on a headlamp change, so the level is written to both
 * (through the privileged shell, they are not public settings) and to the screen at once.
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
            // Both presets: with the headlights always on the firmware sits on lightOffBrightness, and a headlamp
            // change must not snap the screen back to a stale level either way.
            DockShell.shell(context, "settings put system lightOnBrightness $level; settings put system lightOffBrightness $level")
            UnitLight.writeBrightness(context, level)
        } catch (e: Exception) {
            Log.w(TAG, "can't set the dimmer light $level: ${e.message}")
        }
    }
}
