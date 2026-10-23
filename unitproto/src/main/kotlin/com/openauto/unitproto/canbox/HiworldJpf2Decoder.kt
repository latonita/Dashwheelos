package com.openauto.unitproto.canbox

import com.openauto.unitproto.canbox.CanBytes.u16
import com.openauto.unitproto.canbox.CanBytes.u8

/**
 * The stock Hiworld JPF2 box (firmware H1N4JPF20A and its kin): the commands
 * it has always sent, which the `rxpduses` mirror carries like any other
 * frame. They are the vendor's and their layout is not negotiable.
 *
 * ```
 * 0x72 STATE   [0]    bits 0 ACC, 1 illumination, 2 reverse
 *              [2]    steering-wheel key        (not decoded)
 *              [3]    dimmer, 0..100 %
 *              [4..5] steering angle            (not decoded: the WK never fills it)
 * 0x12 BODY    [2]    doors, high bits: 80 FL, 40 FR, 20 RL, 10 RR, 08 tailgate
 *              [4]    volume, [5..9] tone       (not decoded)
 * 0x32 DRIVE   [1]    selector, 1 P 2 N 3 R 4 D 5 S, 0 blank
 *              [2..3] rpm, FFFF = unavailable
 * 0xF0 VERSION        firmware version, ASCII
 * 0xA6 / 0x73         amplifier level and tone / eight zero bytes: nothing about the car
 * ```
 */
open class HiworldJpf2Decoder : CanboxDecoder {
    override val id = "hiworld-jpf2"
    override val framing: CanboxFraming = HiworldFraming

    // JPF2 boxes name themselves H1N4JPF2<revision><letter>; a custom build
    // that is a decoder of its own is listed ahead of this one in the registry.
    override fun matches(version: String) = version.startsWith("H1N4JPF2")

    override fun decode(frame: CanFrame): List<CanboxValue> {
        val p = frame.payload
        return when (frame.cmd) {
            HiworldJpf2Cmd.STATE -> listOfNotNull(
                HiworldJpf2Signals.reverse(p)?.let { CanboxValue.Reverse(it) },
                HiworldJpf2Signals.lightsOn(p)?.let { CanboxValue.Lights(it) },
                HiworldJpf2Signals.dimmerPct(p)?.let { CanboxValue.Dimmer(it) }
            )
            HiworldJpf2Cmd.BODY -> listOfNotNull(HiworldJpf2Signals.doors(p)?.let { CanboxValue.Doors(it) })
            // A blank (0) withdraws the lever, so a consumer can fall back on another gear source.
            HiworldJpf2Cmd.DRIVE -> listOfNotNull(
                CanboxValue.Gear(HiworldJpf2Signals.gear(p)?.let(::gearPosition)),
                HiworldJpf2Signals.rpm(p)?.let { CanboxValue.Rpm(it) }
            )
            HiworldJpf2Cmd.VERSION -> listOfNotNull(HiworldJpf2Signals.version(p)?.let { CanboxValue.Version(it) })
            else -> emptyList()
        }
    }

    override fun commandName(cmd: Int): String = when (cmd) {
        HiworldJpf2Cmd.STATE -> "stock_state"; HiworldJpf2Cmd.BODY -> "stock_body"
        HiworldJpf2Cmd.DRIVE -> "stock_drive"; HiworldJpf2Cmd.VERSION -> "stock_version"
        else -> super.commandName(cmd)
    }

    private fun gearPosition(lever: Char): GearPosition? = when (lever) {
        'P' -> GearPosition.PARK
        'R' -> GearPosition.REVERSE
        'N' -> GearPosition.NEUTRAL
        'D' -> GearPosition.DRIVE
        'S' -> GearPosition.MANUAL
        else -> null
    }
}

/** The stock commands this decoder reads. */
object HiworldJpf2Cmd {
    const val STATE = 0x72
    const val BODY = 0x12
    const val DRIVE = 0x32
    const val VERSION = 0xF0

    val DECODED = setOf(STATE, BODY, DRIVE, VERSION)
}

/** The byte-level rules of the stock commands: pure, null rather than a wrong number. */
object HiworldJpf2Signals {
    /** `0x12`: the door bitfield and its high-bit layout. See [doors]. */
    const val OFF_DOORS = 2
    const val DOOR_TAILGATE = 0x08
    const val DOOR_REAR_RIGHT = 0x10
    const val DOOR_REAR_LEFT = 0x20
    const val DOOR_FRONT_RIGHT = 0x40
    const val DOOR_FRONT_LEFT = 0x80

    /** `0x72`: the status flags, and the illumination and reverse bits among them. */
    const val OFF_FLAGS = 0
    const val FLAG_ILLUMINATION = 0x02
    const val FLAG_REVERSE = 0x04
    /** `0x72`: the instrument-panel dimmer, 0..100 %, 0 with the lights off. */
    const val OFF_DIMMER = 3

    /** `0x32`: the selector position, 1 P, 2 N, 3 R, 4 D, 5 S; 0 the box can't tell. */
    const val OFF_GEAR = 1
    /** `0x32`: the engine speed, which the custom block carries as well. */
    const val OFF_RPM = 2

    /** `0xF0`: the firmware version, 17 ASCII bytes ("H1N4JPF20L-211026"). */
    const val VERSION_LENGTH = 17

    /**
     * Doors out of `0x12`, payload byte 2.
     *
     * The bit order is the vendor's and is NOT the same as the custom block's
     * - it sits in the HIGH bits and there is no bonnet, because the car has
     * no bonnet switch on this bus:
     *
     * `0x80` front left - `0x40` front right - `0x20` rear left -
     * `0x10` rear right - `0x08` tailgate.
     *
     * The firmware has already undone the car's own rear-door crossover (the
     * WK numbers them RR then RL), so what arrives here is in this order and
     * needs no further permutation.
     */
    fun doors(payload: ByteArray?): DoorBits? {
        val bits = u8(payload, OFF_DOORS) ?: return null
        return DoorBits(
            frontLeft = bits and DOOR_FRONT_LEFT != 0,
            frontRight = bits and DOOR_FRONT_RIGHT != 0,
            rearLeft = bits and DOOR_REAR_LEFT != 0,
            rearRight = bits and DOOR_REAR_RIGHT != 0,
            tailgate = bits and DOOR_TAILGATE != 0,
            bonnet = false
        )
    }

    /**
     * `0x72` flags bit 1: the instrument panel is lit, which on the WK
     * means the side lights or headlights are on. Null for a short payload.
     */
    fun lightsOn(payload: ByteArray?): Boolean? =
        u8(payload, OFF_FLAGS)?.let { it and FLAG_ILLUMINATION != 0 }

    /** `0x72` flags bit 2: the car is in reverse. Null for a short payload. */
    fun reverse(payload: ByteArray?): Boolean? =
        u8(payload, OFF_FLAGS)?.let { it and FLAG_REVERSE != 0 }

    /**
     * `0x72` byte 3: the instrument-panel dimmer as a percentage, the
     * firmware's `min(raw / 2, 100)` of the car's own dimmer byte. The WK's
     * five steps arrive as 17, 34, 51, 82 and 100; 0 is lights off, a reading,
     * not a blank.
     */
    fun dimmerPct(payload: ByteArray?): Int? = u8(payload, OFF_DIMMER)?.takeIf { it in 0..100 }

    /**
     * `0x32` byte 1: the selector lever as the cluster shows it, `P`,
     * `R`, `N`, `D`, or `S` for the driver's own range (AutoStick 1..4). Null
     * for 0 - the box has no reading - and for anything it never sends.
     */
    fun gear(payload: ByteArray?): Char? = when (u8(payload, OFF_GEAR)) {
        1 -> 'P'
        2 -> 'N'
        3 -> 'R'
        4 -> 'D'
        5 -> 'S'
        else -> null
    }

    /** `0x32` bytes 2..3: the engine speed, 0..12000; FFFF is the box's "unavailable". */
    fun rpm(payload: ByteArray?): Int? = u16(payload, OFF_RPM)?.takeIf { it in 0..12_000 }

    /**
     * `0xF0`: the box's firmware version, trimmed of the NUL and space
     * padding a shorter string would carry. Null unless every byte is
     * printable ASCII - a version string is never anything else, so a stray
     * byte means the frame is not one.
     */
    fun version(payload: ByteArray?): String? {
        if (payload == null || payload.isEmpty()) return null
        val text = payload.map { it.toInt() and 0xFF }.takeWhile { it != 0 }
        if (text.any { it !in 0x20..0x7E }) return null
        return text.joinToString("") { it.toChar().toString() }.trim().takeIf { it.isNotEmpty() }
    }
}
