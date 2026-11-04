package com.openauto.dash.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Today's OBD-II mode 03/07 read: the stored codes and, apart, the pending ones (mode 07 only). */
data class Obd2Read(val stored: List<String>, val pending: List<String>)

/**
 * The generic OBD-II path as it was before the module sweep (broadcast mode 03 + 07 with the 7E0 retry, mode 04
 * clear). Production wraps `ObdBluetoothManager.readTroubleCodes` / `clearTroubleCodes`; tests fake it.
 */
interface GenericObd {
    suspend fun read(): Result<Obd2Read>
    suspend fun clear(): Result<Unit>
}

/** No module of the profile answered at all: nothing was read. */
class NoModuleAnsweredException : Exception("no module answered")

/** A clear step got something other than a positive reply; the steps after it were not sent. */
class ClearRefusedException(val module: DiagModule, val step: String, val reply: RawReply) :
    Exception("${module.id} refused $step: $reply")

/**
 * Reads and clears fault codes module by module (docs/jeep-diag-spec.md 4.1–4.3). One sweep is one
 * [DiagTransport.withModules] block, so the generic 7DF state is put back once, at the end; the transport releases
 * the adapter between requests so the gauge poll goes on. OBD2 modules use [generic], unchanged.
 *
 * It decides nothing about when to scan (that is [DiagScanScheduler]) and keeps no results (that is the mechanic).
 */
class DiagScanner(private val transport: DiagTransport, private val generic: GenericObd) {

    private val _progress = MutableStateFlow<DiagModule?>(null)

    /** The module being read or cleared right now; null when idle. */
    val progress: StateFlow<DiagModule?> = _progress.asStateFlow()

    /**
     * Reads every module of [profile] in order ([DiagModule.probeOnly] ones only with [probeAll]). Fails only when
     * nothing could be read: the generic module's own failure for a generic-only profile, else
     * [NoModuleAnsweredException].
     */
    suspend fun sweep(profile: DiagProfile, descriptions: DtcDescriptions, probeAll: Boolean = false): Result<List<ModuleScan>> {
        val modules = profile.modules.filter { probeAll || !it.probeOnly }
        if (modules.isEmpty()) return Result.success(emptyList())
        // A profile that is only the generic module keeps today's result, failure message included.
        if (modules.all { it.protocol == DiagProtocol.OBD2 } && modules.size == 1) {
            val m = modules.single()
            _progress.value = m
            try {
                return generic.read().map { listOf(obd2Scan(m, it, descriptions)) }
            } finally {
                _progress.value = null
            }
        }
        val scans = try {
            transport.withModules { modules.map { readModule(it, descriptions) } }
        } finally {
            _progress.value = null
        }
        val answered = scans.any { it.outcome != ScanOutcome.NO_RESPONSE && it.outcome != ScanOutcome.ERROR }
        return if (answered) Result.success(scans) else Result.failure(NoModuleAnsweredException())
    }

    /**
     * Clears [module]'s codes and reads it again. Generic: mode 04, unchanged. Otherwise the [DiagModule.clearSteps]
     * in order, stopping at the first reply that is not positive ([ClearRefusedException]). The result is the
     * module as read after the clear.
     */
    suspend fun clear(module: DiagModule, descriptions: DtcDescriptions): Result<ModuleScan> {
        _progress.value = module
        try {
            if (module.protocol == DiagProtocol.OBD2) {
                generic.clear().onFailure { return Result.failure(it) }
                // Read again; when that fails the codes are taken as gone, as the tile did before the re-read.
                val after = generic.read().getOrNull() ?: Obd2Read(emptyList(), emptyList())
                return Result.success(obd2Scan(module, after, descriptions))
            }
            if (module.clearSteps.isEmpty()) return Result.failure(IllegalStateException("${module.id} offers no clear"))
            return transport.withModules {
                for (step in module.clearSteps) {
                    val reply = transport.request(module.tx, module.rx, step, CLEAR_TIMEOUT_MS)
                    if (reply !is RawReply.Positive) return@withModules Result.failure(ClearRefusedException(module, step, reply))
                }
                Result.success(readModule(module, descriptions))
            }
        } finally {
            _progress.value = null
        }
    }

    private suspend fun readModule(module: DiagModule, descriptions: DtcDescriptions): ModuleScan {
        _progress.value = module
        if (module.protocol == DiagProtocol.OBD2) {
            return generic.read().fold(
                onSuccess = { obd2Scan(module, it, descriptions) },
                onFailure = { ModuleScan(module, ScanOutcome.ERROR, emptyList(), null, "") }
            )
        }
        return decode(module, transport.request(module.tx, module.rx, module.readRequest, READ_TIMEOUT_MS), descriptions)
    }

    companion object {
        const val READ_TIMEOUT_MS = 2_000L
        const val CLEAR_TIMEOUT_MS = 3_000L

        fun codec(protocol: DiagProtocol, descriptions: DtcDescriptions): DtcCodec = when (protocol) {
            DiagProtocol.KWP_CAN -> KwpCodec(descriptions)
            DiagProtocol.UDS -> UdsCodec(descriptions)
            DiagProtocol.OBD2 -> Obd2Codec(descriptions)
        }

        /** One module's raw answer as a [ModuleScan]. */
        fun decode(module: DiagModule, reply: RawReply, descriptions: DtcDescriptions): ModuleScan = when (reply) {
            is RawReply.Positive -> codec(module.protocol, descriptions).parse(module, reply.bytes, reply.complete)
            is RawReply.Negative -> ModuleScan(
                module, ScanOutcome.REFUSED, emptyList(), null, "7F%02X%02X".format(reply.service, reply.nrc)
            )
            RawReply.NoResponse -> ModuleScan(module, ScanOutcome.NO_RESPONSE, emptyList(), null, "")
            is RawReply.Error -> ModuleScan(module, ScanOutcome.ERROR, emptyList(), null, "")
        }

        /**
         * Today's mode 03/07 result as a module scan: stored codes STORED, the pending-only ones PENDING. The
         * fingerprint is the sorted codes with their status, so the same codes in another order are no change.
         */
        fun obd2Scan(module: DiagModule, read: Obd2Read, descriptions: DtcDescriptions = DtcDescriptions.EMPTY): ModuleScan {
            val stored = read.stored.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
            val pending = read.pending.map { it.trim().uppercase() }.filter { it.isNotEmpty() && it !in stored }.distinct()
            val codes = stored.map { it to DtcStatus.STORED } + pending.map { it to DtcStatus.PENDING }
            val dtcs = codes.map { (code, status) ->
                val raw = rawOf(code)
                Dtc(code, raw, status, if (raw >= 0) descriptions.lookup(module.id, raw) else null)
            }
            val fingerprint = codes.map { (code, status) -> "$code:${status.name[0]}" }.sorted().joinToString(",")
            return ModuleScan(module, ScanOutcome.OK, dtcs, null, fingerprint)
        }

        /** "P0676" back to its two bytes (0x0676); -1 when it is not a code. The inverse of ObdParser.decodeDtc. */
        fun rawOf(code: String): Int {
            if (code.length != 5) return -1
            val letter = "PCBU".indexOf(code[0].uppercaseChar())
            val d1 = code[1].digitToIntOrNull() ?: return -1
            val rest = code.substring(2).toIntOrNull(16) ?: return -1
            if (letter < 0 || d1 > 3) return -1
            return (letter shl 14) or (d1 shl 12) or rest
        }

        /**
         * A new sweep over the last one: a module whose read failed in the adapter ([ScanOutcome.ERROR]) keeps its
         * last answer, so a glitch on a Bluetooth clone doesn't make its codes vanish and come back. Modules that
         * didn't answer at all stay as read (they may really be gone).
         */
        fun merge(previous: List<ModuleScan>?, fresh: List<ModuleScan>): List<ModuleScan> {
            if (previous == null) return fresh
            val before = previous.associateBy { it.module.id }
            return fresh.map { s ->
                val old = before[s.module.id]
                if (s.outcome == ScanOutcome.ERROR && old != null &&
                    (old.outcome == ScanOutcome.OK || old.outcome == ScanOutcome.PARTIAL)
                ) old else s
            }
        }

        /** [scan] in place of the same module's entry in [scans] (appended when it wasn't there). */
        fun replace(scans: List<ModuleScan>?, scan: ModuleScan): List<ModuleScan> {
            val list = scans.orEmpty()
            return if (list.any { it.module.id == scan.module.id }) list.map { if (it.module.id == scan.module.id) scan else it }
            else list + scan
        }

        /** Whether two sweeps say the same: per module the same outcome and the same reply. */
        fun sameAs(a: List<ModuleScan>?, b: List<ModuleScan>?): Boolean {
            if (a == null || b == null) return a == b
            if (a.size != b.size) return false
            return a.zip(b).all { (x, y) -> x.module.id == y.module.id && x.outcome == y.outcome && x.fingerprint == y.fingerprint }
        }
    }
}
