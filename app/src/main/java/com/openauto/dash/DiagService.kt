package com.openauto.dash

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.openauto.dash.diag.ClearRefusedException
import com.openauto.dash.diag.DiagChoice
import com.openauto.dash.diag.DiagModule
import com.openauto.dash.diag.DiagProfile
import com.openauto.dash.diag.DiagProfiles
import com.openauto.dash.diag.DiagScanScheduler
import com.openauto.dash.diag.DiagScanner
import com.openauto.dash.diag.DiagSettings
import com.openauto.dash.diag.DtcDescriptions
import com.openauto.dash.diag.GenericObd
import com.openauto.dash.diag.NoModuleAnsweredException
import com.openauto.dash.diag.Obd2Read
import com.openauto.dash.diag.RawReply
import com.openauto.dash.diag.ScanReason
import com.openauto.dash.vehicle.ignitionFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The fault-code scans of the whole app: the one [DiagScanScheduler] that owns every trigger (connect, ACC on,
 * engine start, the background check, the Scan button) and the per-module clear. Nothing else reads or clears
 * fault codes. Results go to [AiMechanic.report]; the profile follows the Diagnostics setting ([DiagSettings]).
 */
object DiagService {
    private const val TAG = "DiagService"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null
    private var started = false

    /** A sweep or a clear: one at a time. The scheduler doesn't start a sweep while it is held. */
    private val op = Mutex()

    private val _busy = MutableStateFlow(false)

    /** A sweep or a clear is running. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val generic = object : GenericObd {
        override suspend fun read(): Result<Obd2Read> = ObdBluetoothManager.readTroubleCodes().map { all ->
            // The manager keeps the mode-07-only codes apart; it sets them before it returns.
            val pending = ObdBluetoothManager.pending.value
            Obd2Read(stored = all.filter { it !in pending }, pending = all.filter { it in pending })
        }

        override suspend fun clear(): Result<Unit> = ObdBluetoothManager.clearTroubleCodes()
    }

    private val scanner = DiagScanner(ObdBluetoothManager.diag, generic)

    /** The module being read or cleared; null when idle. */
    val progress: StateFlow<DiagModule?> = scanner.progress

    private val scheduler = DiagScanScheduler(
        scope = scope,
        clock = { SystemClock.elapsedRealtime() },
        canScan = { !DeepObdSource.owns() && !DemoMode.isOn && !op.isLocked },
        runner = { reason -> lastResult = sweep(announce = reason != ScanReason.MANUAL) }
    )

    /** The outcome of the last sweep, for whoever tapped Scan (a tap may join a sweep that was already running). */
    @Volatile private var lastResult: Result<Unit> = Result.success(Unit)

    private var jeepCache: Pair<DiagProfile, DtcDescriptions>? = null
    private val genericProfile = DiagProfiles.GenericObd2 to DtcDescriptions.EMPTY

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        scheduler.periodicMinutes = DiagSettings.periodicMinutes(app)
        scope.launch {
            val linked = combine(ObdBluetoothManager.connectionState, DemoMode.active) { c, demo ->
                c == ObdConnectionState.CONNECTED && !demo
            }
            combine(linked, ignitionFlow()) { up, ignition -> up to ignition }
                .distinctUntilChanged()
                .collect { (up, ignition) ->
                    // A unit that doesn't say the ignition: each connection is a new drive, as before the scheduler.
                    scheduler.onAcc(ignition ?: up)
                    scheduler.onLink(up)
                }
        }
        scheduler.launchTicker()
    }

    /** The engine speed of each OBD poll (the engine-start rescan). */
    fun onRpm(rpm: Int) = scheduler.onRpm(rpm)

    /** "Background check" changed in the settings. */
    fun setPeriodicMinutes(context: Context, minutes: Int) {
        DiagSettings.setPeriodicMinutes(context, minutes)
        scheduler.periodicMinutes = DiagSettings.periodicMinutes(context)
    }

    /** The Scan button: a sweep now, or the one already running. The result is that sweep's. */
    suspend fun scanNow(): Result<Unit> {
        scheduler.requestScan().await()
        return lastResult
    }

    /** The profile in use, with its descriptions: the setting, or the car's VIN and make in Automatic. */
    fun profile(): Pair<DiagProfile, DtcDescriptions> {
        val context = appContext ?: return genericProfile
        // The demo's codes are generic ones.
        if (DemoMode.isOn) return genericProfile
        val car = CarProfileStore.current
        return when (DiagSettings.resolve(DiagSettings.choice(context), car.vin, car.make)) {
            DiagChoice.JEEP_WK -> jeepWk(context) ?: genericProfile
            else -> genericProfile
        }
    }

    @Synchronized
    private fun jeepWk(context: Context): Pair<DiagProfile, DtcDescriptions>? =
        jeepCache ?: runCatching { DiagProfiles.loadJeepWk { context.assets.open(it) } }
            .onFailure { Log.w(TAG, "Jeep WK profile: ${it.message}") }
            .getOrNull()?.also { jeepCache = it }

    /** Clears [module]'s codes (the caller asked and the car is parked), then reads it again. */
    suspend fun clear(module: DiagModule): Result<Unit> = op.withLock {
        _busy.value = true
        try {
            val context = appContext ?: return@withLock Result.failure(IllegalStateException("OBD error"))
            precheck(context)?.let { return@withLock it }
            scanner.clear(module, profile().second)
                .map { AiMechanic.cleared(it) }
                .recoverCatching { throw IllegalStateException(message(context, it), it) }
        } finally {
            _busy.value = false
        }
    }

    private suspend fun sweep(announce: Boolean): Result<Unit> = op.withLock {
        _busy.value = true
        try {
            val context = appContext ?: return@withLock Result.failure(IllegalStateException("OBD error"))
            precheck(context)?.let { return@withLock it }
            val (profile, descriptions) = profile()
            scanner.sweep(profile, descriptions, DiagSettings.probeAll(context))
                .map { AiMechanic.report(it, announce) }
                .recoverCatching { throw IllegalStateException(message(context, it), it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "sweep: ${e.message}")
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /** Why nothing can be read now, as the generic read says it; null when the adapter is there. */
    private fun precheck(context: Context): Result<Unit>? {
        if (DemoMode.isOn) return null
        val res = when {
            DeepObdSource.owns() -> R.string.settings_deepobd_needs_adapter
            ObdBluetoothManager.connectionState.value != ObdConnectionState.CONNECTED -> R.string.vehicle_obd_not_connected
            else -> return null
        }
        return Result.failure(IllegalStateException(context.getString(res)))
    }

    /** A failure as the tile shows it. The generic read's own failures are already worded. */
    private fun message(context: Context, error: Throwable): String = when (error) {
        is NoModuleAnsweredException -> context.getString(R.string.vehicle_no_dtc_answer)
        is ClearRefusedException -> when (val r = error.reply) {
            is RawReply.Negative -> context.getString(R.string.diag_clear_refused, error.module.id, "7F %02X %02X".format(r.service, r.nrc))
            RawReply.NoResponse -> context.getString(R.string.diag_clear_no_answer, error.module.id)
            is RawReply.Error -> context.getString(R.string.diag_clear_refused, error.module.id, r.message)
            is RawReply.Positive -> context.getString(R.string.diag_clear_refused, error.module.id, "?")
        }
        else -> error.message ?: context.getString(R.string.ai_scan_failed)
    }
}
