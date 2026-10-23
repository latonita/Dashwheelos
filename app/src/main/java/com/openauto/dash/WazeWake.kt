package com.openauto.dash

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Waze opened afresh a while after the unit wakes. Waking from sleep, Waze
 * comes back before the unit's location is on ("turn on your location" over
 * the map) and before the LTE link is up (stuck on offline maps), and stays so
 * until it is closed; after a full start it is fine. With the switch on
 * (Settings, Driving), [delayS] seconds after the wake-up, and once location
 * and the internet are there (at most [READY_WAIT_MS] more), Waze is closed
 * and opened again: on its tile when it runs in one, full screen when it was
 * in front, and only closed when it ran behind, so the next opening is a fresh one.
 *
 * The wake-up is the ignition on the QF firmware ([CarPower]); on other units,
 * a deep sleep seen in the clocks (the time asleep, which uptime leaves out).
 * Closing another app takes the privileged shell (root or the unit's ADB).
 */
object WazeWake {
    private const val TAG = "WazeWake"
    private const val PREFS = "waze_wake"
    private const val KEY_ON = "on"
    private const val KEY_DELAY = "delay_s"

    const val DELAY_DEFAULT_S = 20
    const val DELAY_MIN_S = 5
    const val DELAY_MAX_S = 120
    const val DELAY_STEP_S = 5

    /** How much longer than the delay it waits for location and the internet before restarting anyway. */
    private const val READY_WAIT_MS = 60_000L
    private const val READY_EVERY_MS = 2_000L

    /** Two signs of the same wake-up (the ignition and the clocks) restart it once. */
    private const val ONCE_MS = 5 * 60_000L

    /** How often the clocks are looked at for a sleep, on units with no ignition broadcast. */
    private const val TICK_MS = 5_000L

    /** Time asleep between two looks that counts as the unit having slept. */
    private const val SLEPT_MS = 60_000L

    /** How long a closed Waze gets to go before it is opened again. */
    private const val CLOSE_WAIT_MS = 800L

    data class Setting(val on: Boolean = false, val delayS: Int = DELAY_DEFAULT_S)

    private val _setting = MutableStateFlow(Setting())
    val setting: StateFlow<Setting> = _setting.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var loaded = false
    private var started = false
    private var pending: Job? = null
    @Volatile private var wokeAt = 0L

    /** Whether Waze was installed when the setting was last loaded: for the settings search. */
    @Volatile var present = false
        private set

    fun load(context: Context) {
        present = installed(context)
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _setting.value = Setting(
            prefs.getBoolean(KEY_ON, false),
            prefs.getInt(KEY_DELAY, DELAY_DEFAULT_S).coerceIn(DELAY_MIN_S, DELAY_MAX_S)
        )
    }

    fun save(context: Context, value: Setting) {
        val fixed = value.copy(delayS = value.delayS.coerceIn(DELAY_MIN_S, DELAY_MAX_S))
        _setting.value = fixed
        loaded = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON, fixed.on).putInt(KEY_DELAY, fixed.delayS).apply()
    }

    /** Whether Waze is on the unit at all: the setting is only offered then. */
    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(WazeScreen.PACKAGE, 0) }.isSuccess

    /** Watches the clocks for a sleep (once per process), for units whose ignition says nothing. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        load(app)
        scope.launch {
            var asleep = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            while (true) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
                val slept = now - asleep
                asleep = now
                if (slept >= SLEPT_MS && !CarPower.available) {
                    DebugLog.note(app, "Waze restart: the unit slept ${slept / 1000} s")
                    carWoke(app)
                }
            }
        }
    }

    /** The unit woke up: Waze is restarted [Setting.delayS] seconds from now, when the switch is on. */
    fun carWoke(context: Context) {
        val app = context.applicationContext
        load(app)
        val s = _setting.value
        if (!s.on || Edition.play || DemoMode.isOn) return
        val now = SystemClock.elapsedRealtime()
        if (wokeAt != 0L && now - wokeAt < ONCE_MS) return
        wokeAt = now
        pending?.cancel()
        pending = scope.launch { restartLater(app, s.delayS) }
    }

    private suspend fun restartLater(context: Context, delayS: Int) {
        delay(delayS * 1_000L)
        if (CarPower.ignition.value == false) return
        val until = SystemClock.elapsedRealtime() + READY_WAIT_MS
        while (!ready(context) && SystemClock.elapsedRealtime() < until) delay(READY_EVERY_MS)
        // Switched off again meanwhile, or turned off in Settings.
        if (CarPower.ignition.value == false || !_setting.value.on) return
        restart(context)
    }

    /** Location on and an internet link Android checked: what Waze lacked when it woke first. */
    private fun ready(context: Context): Boolean {
        val location = runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            // isLocationEnabled is API 28; the older units answer the same question
            // through the provider, which has been there since the beginning.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(true)
        val online = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.getOrDefault(true)
        return location && online
    }

    private suspend fun restart(context: Context) {
        val pkg = WazeScreen.PACKAGE
        if (!PrivilegedShell.access.value.shell) {
            Log.i(TAG, "no privileged shell: Waze can't be closed")
            DebugLog.note(context, "Waze restart skipped: no root or ADB shell")
            return
        }
        val pid = runCatching { DockShell.shell(context, "pidof $pkg") }.getOrDefault("").trim()
        if (pid.isEmpty() || !pid.first().isDigit()) {
            Log.i(TAG, "Waze not running: it will start fresh when opened")
            return
        }
        if (EmbeddedApp.restartInTile(pkg)) {
            Log.i(TAG, "Waze reopened on its tile")
            DebugLog.note(context, "Waze restarted on its tile after the wake-up")
            return
        }
        val stacks = runCatching { WindowListing.appStacks(DockShell.listStacks(context), pkg) }.getOrDefault(emptyList())
        val inFront = stacks.any { it.visible && it.displayId == android.view.Display.DEFAULT_DISPLAY }
        runCatching { DockShell.shell(context, "am force-stop $pkg") }
            .onFailure { Log.w(TAG, "Waze not closed", it); return }
        if (inFront) {
            delay(CLOSE_WAIT_MS)
            val component = AppLauncher.launchIntent(context, pkg)?.component?.flattenToShortString()
            if (component != null) runCatching { DockShell.shell(context, "am start -n $component") }
                .onFailure { Log.w(TAG, "Waze not reopened", it) }
        }
        Log.i(TAG, "Waze ${if (inFront) "closed and reopened" else "closed, it ran behind"}")
        DebugLog.note(context, "Waze restarted after the wake-up (${if (inFront) "in front" else "behind, closed only"})")
    }
}
