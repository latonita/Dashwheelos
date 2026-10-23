package com.openauto.unitproto.canbox

/**
 * What one canbox firmware's frames mean: a frame in, the car's values out.
 * Pure, with no Android and no app in it, and no memory between frames - the
 * values it returns are what that one frame said.
 *
 * Adding a canbox is one of these and one line in [CanboxRegistry]; a new
 * [CanboxFraming] too only when its byte format is new.
 */
interface CanboxDecoder {
    /** A stable name, for the override and the logs ("hiworld-jpf2", "hiworld-jpf20l"). */
    val id: String

    /** How this box's frames are put on the wire. */
    val framing: CanboxFraming

    /** Whether this decoder is the one for a box that names its firmware [version]. */
    fun matches(version: String): Boolean

    /** The values in [frame]; empty for a command this decoder has nothing to say about. */
    fun decode(frame: CanFrame): List<CanboxValue>

    /** A short name for [cmd], for logs and the Signal Finder. */
    fun commandName(cmd: Int): String = "cmd_%02X".format(cmd)
}

/** The byte readers every decoder uses: bounds-checked, so a short payload is null and never an exception. */
object CanBytes {
    /** Temperatures travel as a byte biased by this, so -40 degC is 0 on the wire. */
    const val TEMP_BIAS = 40

    /**
     * The raw byte that means "I do not have this temperature".
     *
     * Every +40-biased byte from 0x00 to 0xFF otherwise decodes to a value
     * inside -40..215, so without a reserved code a temperature field has no
     * way to say "unknown" - and 0xFF, the filler a car puts on the bus when a
     * sensor is absent, would read as a perfectly acceptable 215 C. Reserving
     * it costs the ability to report exactly 215 C, which no coolant, intake
     * or tyre ever reaches.
     */
    const val TEMP_NOT_AVAILABLE = 0xFF

    /** The unsigned byte at [at], or null when it isn't there. */
    fun u8(payload: ByteArray?, at: Int): Int? =
        if (payload == null || at < 0 || at >= payload.size) null else payload[at].toInt() and 0xFF

    /** The big-endian unsigned word at [at], or null when it isn't there. */
    fun u16(payload: ByteArray?, at: Int): Int? {
        if (payload == null || at < 0 || at + 1 >= payload.size) return null
        return ((payload[at].toInt() and 0xFF) shl 8) or (payload[at + 1].toInt() and 0xFF)
    }

    /** The big-endian unsigned 24-bit value at [at], or null when it isn't there. */
    fun u24(payload: ByteArray?, at: Int): Int? {
        if (payload == null || at < 0 || at + 2 >= payload.size) return null
        return ((payload[at].toInt() and 0xFF) shl 16) or
            ((payload[at + 1].toInt() and 0xFF) shl 8) or
            (payload[at + 2].toInt() and 0xFF)
    }

    /** The big-endian signed word at [at], or null when it isn't there. */
    fun s16(payload: ByteArray?, at: Int): Int? = u16(payload, at)?.let { if (it > 0x7FFF) it - 0x10000 else it }

    /** A temperature byte biased by [TEMP_BIAS], or null when it isn't there or the box sent [TEMP_NOT_AVAILABLE]. */
    fun biasedTemp(payload: ByteArray?, at: Int): Int? =
        u8(payload, at)?.takeIf { it != TEMP_NOT_AVAILABLE }?.minus(TEMP_BIAS)
}
