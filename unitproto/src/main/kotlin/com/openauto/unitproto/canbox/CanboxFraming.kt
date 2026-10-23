package com.openauto.unitproto.canbox

/** One frame off the wire, with the framing taken away: the command byte and what follows it. */
data class CanFrame(val cmd: Int, val payload: ByteArray) {
    // A ByteArray, so equals / hashCode are written out by hand: the generated
    // ones would compare identities and break every test and every "did this
    // frame change?" check in the app.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrame) return false
        return cmd == other.cmd && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()

    override fun toString(): String =
        "CanFrame(cmd=0x%02X, payload=%s)".format(cmd, payload.joinToString(" ") { "%02X".format(it) })
}

/**
 * How one family of canbox puts a frame on the wire: bytes in, command and
 * payload out, with the header, length and checksum checked on the way.
 * A canbox whose byte format is new gets one of these; one that only means
 * different things by the same format does not.
 */
interface CanboxFraming {
    /** The frame in [bytes], or null when the header, the length or the checksum does not hold. */
    fun parse(bytes: ByteArray?): CanFrame?

    /** The wire bytes for [cmd] and [payload]: the inverse of [parse], for tests and the fake firmware. */
    fun encode(cmd: Int, payload: ByteArray): ByteArray
}
