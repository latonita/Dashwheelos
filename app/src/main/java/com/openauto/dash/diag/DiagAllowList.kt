package com.openauto.dash.diag

/**
 * The hard safety rule of the diagnostic path: only these requests may be written to the adapter. Everything else
 * (security access, writes, actuators, routines, ECU reset, memory reads, programming sessions) is refused before
 * anything is sent. Pure, so a unit test can enforce it.
 */
object DiagAllowList {
    /** KWP-style services that may be sent whatever their data: read DTCs, freeze frame, ident, local-id read, tester present, clear DTCs. */
    private val SERVICES = setOf(0x18, 0x17, 0x1A, 0x21, 0x3E, 0x14)

    /** OBD-II modes: current data, stored codes, clear codes, pending codes, permanent codes, vehicle info. */
    private val OBD_MODES = setOf(0x01, 0x03, 0x04, 0x07, 0x0A, 0x09)

    /** The only diagnostic session that may be opened (before a clear): the extended one, `10 92`. */
    private const val SESSION = 0x10
    private const val SESSION_EXTENDED = 0x92

    /** Null when [payload] may be sent, else why not. */
    fun refusal(payload: ByteArray): String? {
        if (payload.isEmpty()) return "empty request"
        val service = payload[0].toInt() and 0xFF
        return when {
            service == SESSION ->
                if (payload.size == 2 && (payload[1].toInt() and 0xFF) == SESSION_EXTENDED) null
                else "session request other than 10 92 refused"
            service in SERVICES || service in OBD_MODES -> null
            else -> "service %02X is not on the allow-list".format(service)
        }
    }

    fun allowed(payload: ByteArray): Boolean = refusal(payload) == null

    /** Hex text ("18 00 FF 00", "1800ff00") to bytes; null when it is not whole bytes of hex. */
    fun parseHex(text: String): ByteArray? {
        val clean = text.filter { !it.isWhitespace() }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        if (!clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
