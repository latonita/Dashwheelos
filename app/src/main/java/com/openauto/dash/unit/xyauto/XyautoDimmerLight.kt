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
import kotlin.math.pow
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
    /** The WK dimmer's positions in percent, darkest first; 0 (lights off) reads as the darkest. */
    private val STEPS = doubleArrayOf(17.0, 34.0, 51.0, 82.0, 100.0)
    /** The darkest backlight level: the firmware's own floor. */
    private const val MIN_LEVEL = 10.0
    private const val MAX_LEVEL = 255.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /**
     * The backlight level (10..255) for the car's dimmer in percent. The eye sees ratios, not differences, so the
     * wheel's positions are spaced evenly on a log scale (each one about 2.25 times the last); a value between
     * two positions falls between their levels.
     */
    internal fun level(pct: Double): Int {
        val p = pct.coerceIn(STEPS.first(), STEPS.last())
        val i = (1 until STEPS.size).first { p <= STEPS[it] }
        val position = i - 1 + (p - STEPS[i - 1]) / (STEPS[i] - STEPS[i - 1])
        return (MIN_LEVEL * (MAX_LEVEL / MIN_LEVEL).pow(position / (STEPS.size - 1))).roundToInt()
    }

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
