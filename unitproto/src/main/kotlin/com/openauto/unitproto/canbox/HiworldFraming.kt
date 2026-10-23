package com.openauto.unitproto.canbox

/**
 * The Hiworld framing, as every Hiworld box (and the rebuilt JPF2 firmware)
 * puts it on the UART: `AA 55 <len> <cmd> <payload...> <sum>`, where
 * **`len` counts the payload only** - not the cmd byte, not the header and
 * not the checksum, so the wire length is always `len + 5` - and `sum` is
 * the low byte of the sum of **every** byte before it, the `AA 55` header
 * included.
 *
 * Equivalently, and this is the form the firmware computes:
 * `sum = (len + cmd + payload.sum() - 1) and 0xFF`, because
 * `0xAA + 0x55 = 0xFF = -1 (mod 256)`.
 *
 * This is not ours to choose. It is the stock Hiworld framing, verified
 * three ways in `hiworld-frame-format.md`: 13 candidate checksum
 * algorithms tested against 100% of captured frames, the decompiled box
 * firmware, and the vendor's own Android plugin, which computes
 * `CHK = (LEN + CMD + sum(DATA) - 1) & 0xFF`. The host app's framer gates
 * the `rxpduses` mirror on it, so a frame that does not satisfy it never
 * reaches us in the first place.
 *
 * **Test this against literal captured bytes, never against [encode].**
 * Until 2026-10-21 both halves of this object implemented a different
 * convention - `len` counting cmd+payload, the header left out of the sum.
 * [encode] and [parse] were inverses of each other, so the round-trip
 * tests passed while the parser rejected about 99.6% of real frames. The
 * frame `AA 55 02 E0 00 00 E1`, which a real head unit sends to put the
 * box into its bootloader, is the cheapest regression test there is.
 */
object HiworldFraming : CanboxFraming {
    /** The two header bytes every frame starts with. */
    const val HEADER_0 = 0xAA
    const val HEADER_1 = 0x55

    /** Header (2) + length (1) + checksum (1): what a frame costs beyond cmd + payload. */
    const val OVERHEAD = 4

    /**
     * The largest `len` the box will emit or accept. The firmware's parser
     * rejects `len >= 0x38` outright, so anything bigger is a corrupt buffer,
     * not a frame we have not implemented yet.
     */
    const val MAX_PAYLOAD = 0x37

    /**
     * The low byte of the sum of `bytes[from]` up to but not including
     * `bytes[to]`. A plain range sum, with no knowledge of the frame: the
     * header term is added by [frameChecksum], which is what callers want.
     * A range that isn't inside the array sums to 0, which never matches a
     * real frame because [parse] bounds-checks before it calls this.
     */
    fun checksum(bytes: ByteArray, from: Int, to: Int): Int {
        if (from < 0 || to > bytes.size || from >= to) return 0
        var sum = 0
        for (i in from until to) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFF
    }

    /**
     * The checksum byte for a frame whose `len` sits at `bytes[2]` and whose
     * last payload byte is at `bytes[to - 1]`: the sum over that range **plus
     * the `AA 55` header**.
     */
    fun frameChecksum(bytes: ByteArray, to: Int): Int =
        (HEADER_0 + HEADER_1 + checksum(bytes, 2, to)) and 0xFF

    /**
     * The frame in [bytes], or null when the header, the length or the
     * checksum does not hold. Trailing bytes after the checksum are tolerated
     * (the broadcast sometimes carries a padded buffer); a buffer too short
     * for the length it declares is not.
     *
     * `len` is the PAYLOAD length, so `len == 0` - a command with nothing
     * after it - is a legal frame of five bytes.
     */
    override fun parse(bytes: ByteArray?): CanFrame? {
        if (bytes == null || bytes.size < OVERHEAD + 1) return null
        if ((bytes[0].toInt() and 0xFF) != HEADER_0 || (bytes[1].toInt() and 0xFF) != HEADER_1) return null
        val len = bytes[2].toInt() and 0xFF
        if (len > MAX_PAYLOAD) return null        // the box's own parser rejects these too
        val end = 4 + len                         // index of the checksum byte
        if (end >= bytes.size) return null
        if (frameChecksum(bytes, end) != (bytes[end].toInt() and 0xFF)) return null
        return CanFrame(bytes[3].toInt() and 0xFF, bytes.copyOfRange(4, end))
    }

    override fun encode(cmd: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(OVERHEAD + 1 + payload.size)
        out[0] = HEADER_0.toByte()
        out[1] = HEADER_1.toByte()
        out[2] = payload.size.toByte()
        out[3] = cmd.toByte()
        payload.copyInto(out, 4)
        out[out.size - 1] = frameChecksum(out, out.size - 1).toByte()
        return out
    }
}
