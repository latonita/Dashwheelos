package com.openauto.dash.diag

enum class DiagProtocol { OBD2, KWP_CAN, UDS }

/** SENSITIVE modules hold adaptives or security state; the UI asks for extra confirmation before a clear. */
enum class ClearRisk { NORMAL, SENSITIVE }

/**
 * One diagnosable computer. [tx]/[rx] are 11-bit CAN ids. [readRequest] and [clearSteps] are hex strings
 * sent in order; an empty [clearSteps] means clearing is not offered. [probeOnly] modules never answered
 * on the reference car and are off by default.
 */
data class DiagModule(
    val id: String,
    val name: String,
    val tx: Int,
    val rx: Int,
    val protocol: DiagProtocol,
    val readRequest: String,
    val clearSteps: List<String>,
    val clearRisk: ClearRisk,
    val probeOnly: Boolean,
)

data class DiagProfile(val id: String, val label: String, val modules: List<DiagModule>)

enum class DtcStatus { ACTIVE, PENDING, STORED }

/** [raw] is the 2-byte code as sent (high byte first), the key into [DtcDescriptions]. */
data class Dtc(val code: String, val raw: Int, val status: DtcStatus, val description: String?)

enum class ScanOutcome { OK, PARTIAL, NO_RESPONSE, REFUSED, ERROR }

/** [countReported] is the module's own fault count when the reply carries one; [fingerprint] is the payload in hex. */
data class ModuleScan(
    val module: DiagModule,
    val outcome: ScanOutcome,
    val codes: List<Dtc>,
    val countReported: Int?,
    val fingerprint: String,
)

/**
 * [payload] is the reassembled positive reply starting at the service byte (58 03 C1 26 20 ...).
 * [complete] false means only the first frame arrived: the result is PARTIAL with the whole records that fit.
 */
interface DtcCodec { fun parse(module: DiagModule, payload: ByteArray, complete: Boolean): ModuleScan }
