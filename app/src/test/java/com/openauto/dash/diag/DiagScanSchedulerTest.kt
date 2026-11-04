package com.openauto.dash.diag

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagScanSchedulerTest {
    private var now = 0L
    private val scans = mutableListOf<Pair<Long, ScanReason>>()
    private var gate: CompletableDeferred<Unit>? = null
    private var canScan = true
    private val s = DiagScanScheduler(
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
        canScan = { canScan },
        runner = { r -> scans += (now / 1000) to r; gate?.await() },
    )

    /** Runs the clock to [toSec], ticking each second, with events applied at their second (one per second). */
    private fun run(toSec: Long, vararg at: Pair<Long, () -> Unit>) {
        val ev = at.toMap()
        require(ev.size == at.size) { "two events in one second" }
        var t = now / 1000
        while (t <= toSec) {
            now = t * 1000
            ev[t]?.invoke()
            s.tick()
            t++
        }
    }

    private fun scansAt(vararg e: Pair<Long, ScanReason>) = assertEquals(e.toList(), scans)

    @Test fun accThenLink_periodicOn_oneScanAt15() {
        s.periodicMinutes = 15
        run(60, 0L to { s.onAcc(true) }, 2L to { s.onLink(true) })
        scansAt(15L to ScanReason.ACC_START)
    }

    @Test fun accThenLink_periodicOff_oneScanAt5() {
        run(60, 0L to { s.onAcc(true) }, 2L to { s.onLink(true) })
        scansAt(5L to ScanReason.CONNECT)
    }

    @Test fun linkAt30_periodicOn_scansAtLinkUp() {
        s.periodicMinutes = 10
        run(60, 0L to { s.onAcc(true) }, 30L to { s.onLink(true) })
        scansAt(30L to ScanReason.ACC_START)
    }

    @Test fun engineStartInsideGap_noExtraScan_thenRescanAfterGap() {
        // Scan at 5 s with the engine stopped; engine runs from 10 s, 20 s later is 30 s: inside the gap.
        run(100, 0L to { s.onAcc(true) }, 2L to { s.onLink(true) }, 10L to { s.onRpm(2000) })
        // Rule 4 still holds (stopped scan, 20 s running) once the gap is over: one rescan at 65 s.
        scansAt(5L to ScanReason.CONNECT, 65L to ScanReason.ENGINE_START)
    }

    @Test fun engineAlreadyRunningAtScan_noRescan() {
        run(120, 0L to { s.onAcc(true); s.onRpm(1500) }, 2L to { s.onLink(true) })
        scansAt(5L to ScanReason.CONNECT)
    }

    @Test fun tapDuringScan_joinsIt() {
        val g = CompletableDeferred<Unit>()
        gate = g
        run(10, 0L to { s.onAcc(true) }, 2L to { s.onLink(true) })
        assertTrue(s.scanning)
        val tap = s.requestScan()
        s.tick()
        assertFalse(tap.isCompleted)
        g.complete(Unit)
        assertTrue(tap.isCompleted)
        assertEquals(1, scans.size)
        assertFalse(s.scanning)
    }

    @Test fun tapIsExemptFromGap() {
        run(10, 0L to { s.onAcc(true) }, 2L to { s.onLink(true) })
        now = 11_000
        s.requestScan()
        scansAt(5L to ScanReason.CONNECT, 11L to ScanReason.MANUAL)
    }

    @Test fun periodicAndEngineRescanSameMinute_oneScan() {
        s.periodicMinutes = 5
        // ACC scan at 15 s with the engine stopped; engine runs from 280 s, rescan due at 300 s, and the
        // periodic tick would be due at 315 s: the rescan restarts the timer, so only one scan results.
        run(400, 0L to { s.onAcc(true) }, 1L to { s.onLink(true) }, 280L to { s.onRpm(1500) })
        scansAt(15L to ScanReason.ACC_START, 300L to ScanReason.ENGINE_START)
    }

    @Test fun accOffThenOn_newStartupScan() {
        run(
            60,
            0L to { s.onAcc(true) }, 1L to { s.onLink(true) },
            20L to { s.onAcc(false) }, 30L to { s.onAcc(true) },
        )
        run(100)
        // New cycle at 30 s: 33 s is inside the 60 s gap from the 4 s scan, so it waits to 64 s.
        scansAt(4L to ScanReason.CONNECT, 64L to ScanReason.CONNECT)
    }

    @Test fun accOff_cancelsPendingStartup() {
        run(10, 0L to { s.onAcc(true) }, 1L to { s.onLink(true) }, 3L to { s.onAcc(false) })
        scansAt()
    }

    @Test fun periodic5min_ticksAndTapPushesBack() {
        s.periodicMinutes = 5
        run(1000, 0L to { s.onAcc(true); s.onLink(true) }, 200L to { s.requestScan() })
        scansAt(
            15L to ScanReason.ACC_START, 200L to ScanReason.MANUAL,
            500L to ScanReason.PERIODIC, 800L to ScanReason.PERIODIC,
        )
    }

    @Test fun linkDown_nothing() {
        s.periodicMinutes = 5
        run(1000, 0L to { s.onAcc(true) })
        scansAt()
    }

    @Test fun cannotScan_skipsAutomatic() {
        canScan = false
        run(60, 0L to { s.onAcc(true) }, 1L to { s.onLink(true) })
        scansAt()
    }
}
