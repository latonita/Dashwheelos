package com.openauto.dash.diag

import com.openauto.dash.ObdParser

private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

/** Shared handling of an empty reply and of a negative reply (7F svc nrc). Null means "carry on parsing". */
private fun early(module: DiagModule, payload: ByteArray): ModuleScan? = when {
    payload.isEmpty() -> ModuleScan(module, ScanOutcome.NO_RESPONSE, emptyList(), null, "")
    payload.u(0) == 0x7F -> ModuleScan(module, ScanOutcome.REFUSED, emptyList(), null, payload.hex())
    else -> null
}

private fun dtc(raw: Int, st: DtcStatus, desc: DtcDescriptions?, module: DiagModule): Dtc =
    Dtc(ObdParser.decodeDtc(raw shr 8, raw and 0xFF), raw, st, desc?.lookup(module.id, raw))

/**
 * KWP2000 reply 58 n [hi lo st] x n. n is authoritative, not the reply length. Status is (st >> 5) & 3:
 * 3 active, 2 pending, otherwise stored. If fewer than n whole records arrived (a first frame only, or a
 * truncated reply) the outcome is PARTIAL with the records that fit; a first frame that already holds all
 * n records is complete and reported OK.
 */
class KwpCodec(private val descriptions: DtcDescriptions? = null) : DtcCodec {
    override fun parse(module: DiagModule, payload: ByteArray, complete: Boolean): ModuleScan {
        early(module, payload)?.let { return it }
        if (payload.u(0) != 0x58 || payload.size < 2) {
            return ModuleScan(module, ScanOutcome.ERROR, emptyList(), null, payload.hex())
        }
        val n = payload.u(1)
        val codes = ArrayList<Dtc>()
        var i = 2
        while (codes.size < n && i + 3 <= payload.size) {
            val raw = (payload.u(i) shl 8) or payload.u(i + 1)
            val s = when ((payload.u(i + 2) shr 5) and 3) {
                3 -> DtcStatus.ACTIVE
                2 -> DtcStatus.PENDING
                else -> DtcStatus.STORED
            }
            codes.add(dtc(raw, s, descriptions, module))
            i += 3
        }
        val outcome = if (codes.size < n) ScanOutcome.PARTIAL else ScanOutcome.OK
        return ModuleScan(module, outcome, codes, n, payload.hex())
    }
}

/**
 * UDS reply 59 02 availabilityMask [3-byte DTC + status] x k. The code uses the first two DTC bytes (same
 * letter layout as OBD-II); the failure-type byte is dropped. Status choice: bit0 testFailed -> ACTIVE,
 * else bit2 pendingDTC -> PENDING, else STORED (confirmed/history only). The reply carries no count, so
 * countReported is null; a truncated trailing record, or complete=false, gives PARTIAL.
 */
class UdsCodec(private val descriptions: DtcDescriptions? = null) : DtcCodec {
    override fun parse(module: DiagModule, payload: ByteArray, complete: Boolean): ModuleScan {
        early(module, payload)?.let { return it }
        if (payload.u(0) != 0x59 || payload.size < 3) {
            return ModuleScan(module, ScanOutcome.ERROR, emptyList(), null, payload.hex())
        }
        val codes = ArrayList<Dtc>()
        var i = 3
        while (i + 4 <= payload.size) {
            val raw = (payload.u(i) shl 8) or payload.u(i + 1)
            val st = payload.u(i + 3)
            val s = when {
                st and 0x01 != 0 -> DtcStatus.ACTIVE
                st and 0x04 != 0 -> DtcStatus.PENDING
                else -> DtcStatus.STORED
            }
            codes.add(dtc(raw, s, descriptions, module))
            i += 4
        }
        val truncated = i < payload.size
        val outcome = if (truncated || !complete) ScanOutcome.PARTIAL else ScanOutcome.OK
        return ModuleScan(module, outcome, codes, null, payload.hex())
    }
}

/**
 * OBD-II replies 43 / 47: 43 [count] hi lo ... (CAN, even length) or the older zero-padded 43 hi lo x3
 * (odd length), the two layouts ObdParser.parseDtcReply handles. Mode 03 gives STORED, mode 07 PENDING;
 * 00 00 pairs are padding. Letters come from ObdParser.decodeDtc.
 */
class Obd2Codec(private val descriptions: DtcDescriptions? = null) : DtcCodec {
    override fun parse(module: DiagModule, payload: ByteArray, complete: Boolean): ModuleScan {
        early(module, payload)?.let { return it }
        val mode = payload.u(0)
        if (mode != 0x43 && mode != 0x47) {
            return ModuleScan(module, ScanOutcome.ERROR, emptyList(), null, payload.hex())
        }
        val st = if (mode == 0x47) DtcStatus.PENDING else DtcStatus.STORED
        val can = payload.size % 2 == 0
        val count = if (can) payload.u(1) else null
        var i = if (can) 2 else 1
        val end = if (count != null) minOf(payload.size, 2 + count * 2) else payload.size
        val codes = ArrayList<Dtc>()
        while (i + 2 <= end) {
            val raw = (payload.u(i) shl 8) or payload.u(i + 1)
            if (raw != 0) codes.add(dtc(raw, st, descriptions, module))
            i += 2
        }
        val short = count != null && payload.size < 2 + count * 2
        val partial = !complete || short
        return ModuleScan(module, if (partial) ScanOutcome.PARTIAL else ScanOutcome.OK, codes, count, payload.hex())
    }
}
