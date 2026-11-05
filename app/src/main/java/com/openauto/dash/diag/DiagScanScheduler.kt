package com.openauto.dash.diag

import com.openauto.dash.LiveWatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Why a scan runs; the runner may show it or log it. */
enum class ScanReason { CONNECT, ACC_START, ENGINE_START, PERIODIC, MANUAL }

/**
 * The one owner of every diagnostic scan trigger (docs/jeep-diag-spec.md 4.9).
 *
 * It is a tick-driven state machine: the inputs ([onAcc], [onLink], [onRpm], [periodicMinutes]) only
 * record state, and [tick] decides, with the injected [clock], whether an automatic scan is due. Nothing
 * here sleeps, so a test drives it with a fake clock and no coroutines-test. [launchTicker] gives
 * production a 1 s tick. The scan itself is [runner], started in [scope].
 *
 * Rules: one scan at a time (a trigger during a scan joins it); one start-up scan per ACC cycle; a
 * minimum [MIN_GAP_MS] between automatic scans (dropped, not queued; the tap is exempt); the engine-start
 * rescan; the periodic timer counts from the last finished scan of any kind; ACC off cancels the pending
 * triggers; no automatic scan while the link is down or [canScan] is false.
 */
class DiagScanScheduler(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val canScan: () -> Boolean = { true },
    private val runner: suspend (ScanReason) -> Unit,
) {
    private val lock = Any()

    private var acc = false
    private var link = false
    private var rpm = 0
    private var accAt = 0L
    private var linkAt = 0L

    /** A scan finished since this ACC cycle began. */
    private var startupDone = false
    private var lastFinished: Long? = null
    private var rescanPending = false
    private var runningSince: Long? = null
    private var current: CompletableDeferred<Unit>? = null

    /** Background check interval in minutes; 0 = Off. The setting offers 5/10/15/30. */
    @Volatile
    var periodicMinutes: Int = 0

    /** ACC on/off (canbox bit, or K.IGNITION on units without it). A rising edge starts a new cycle. */
    fun onAcc(on: Boolean) = synchronized(lock) {
        if (on == acc) return@synchronized
        acc = on
        startupDone = false
        runningSince = null
        if (on) accAt = clock()
    }

    /** OBD link up/down. */
    fun onLink(up: Boolean) = synchronized(lock) {
        if (up == link) return@synchronized
        link = up
        if (up) linkAt = clock()
    }

    /** Latest engine rpm, fed on every reading. */
    fun onRpm(value: Int) = synchronized(lock) { rpm = value }

    /** True while a scan runs. */
    val scanning: Boolean get() = synchronized(lock) { current != null }

    /**
     * The Scan button. Starts a scan, or joins the running one; the deferred completes when that scan does.
     * Exempt from the gap and from [canScan]/link (the caller decides whether a tap is allowed).
     */
    fun requestScan(): Deferred<Unit> = start(ScanReason.MANUAL)

    suspend fun scanNow() = requestScan().await()

    /** Call about once a second (or from the gauge poll). Starts at most one scan. */
    fun tick() {
        val reason = synchronized(lock) { due(clock()) } ?: return
        start(reason)
    }

    /** A 1 s ticker in [scope]; cancel the returned job to stop. */
    fun launchTicker(intervalMs: Long = 1_000L): Job = scope.launch {
        while (isActive) {
            tick()
            delay(intervalMs)
        }
    }

    // Called under [lock].
    private fun due(now: Long): ScanReason? {
        val running = acc && rpm > LiveWatch.RUNNING_RPM
        if (!running) runningSince = null else if (runningSince == null) runningSince = now
        if (!acc || !link || current != null || !canScan()) return null
        val last = lastFinished
        if (last != null && now - last < MIN_GAP_MS) return null // dropped; startup waits it out below
        val periodic = periodicMinutes
        if (!startupDone) {
            val at = if (periodic > 0) maxOf(accAt + ACC_DELAY_MS, linkAt) else maxOf(accAt, linkAt) + CONNECT_DELAY_MS
            if (now < at) return null
            return if (periodic > 0) ScanReason.ACC_START else ScanReason.CONNECT
        }
        val since = runningSince
        if (rescanPending && since != null && now - since >= ENGINE_RUN_MS) return ScanReason.ENGINE_START
        if (periodic > 0 && last != null && now - last >= periodic * 60_000L) return ScanReason.PERIODIC
        return null
    }

    private fun start(reason: ScanReason): Deferred<Unit> {
        val mine = CompletableDeferred<Unit>()
        synchronized(lock) {
            current?.let { return it }
            current = mine
        }
        scope.launch {
            try {
                runner(reason)
            } finally {
                synchronized(lock) {
                    lastFinished = clock()
                    startupDone = true
                    // The engine-start rescan is owed when the scan was made with the engine stopped.
                    rescanPending = rpm == 0
                    runningSince = if (acc && rpm > LiveWatch.RUNNING_RPM) clock() else null
                    current = null
                }
                mine.complete(Unit)
            }
        }
        return mine
    }

    companion object {
        const val MIN_GAP_MS = 60_000L
        const val ACC_DELAY_MS = 15_000L
        const val CONNECT_DELAY_MS = 3_000L
        const val ENGINE_RUN_MS = 20_000L
    }
}
