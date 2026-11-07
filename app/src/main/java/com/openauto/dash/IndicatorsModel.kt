package com.openauto.dash

import com.openauto.dash.vehicle.Indicators

/*
 * The Indicators tile's state mapping: which tell-tales the vehicle bus can light, and how the bus's values
 * ([com.openauto.dash.vehicle.K.INDICATORS], [com.openauto.dash.vehicle.K.LIGHTS], handbrake, driver belt) become
 * one lamp each. No drawing here (IndicatorsTile.kt), so it can be tested.
 */

/** A tell-tale of the instrument panel. Declared in the order the tile lays them out (left signal first, right last). */
enum class Tell(val colour: TellColour, val blinks: Boolean = false) {
    LEFT(TellColour.GREEN, blinks = true),
    PARKING(TellColour.GREEN),
    LOW_BEAM(TellColour.GREEN),
    HIGH_BEAM(TellColour.BLUE),
    FRONT_FOG(TellColour.GREEN),
    REAR_FOG(TellColour.AMBER),
    HAZARD(TellColour.RED, blinks = true),
    HANDBRAKE(TellColour.RED),
    SEAT_BELT(TellColour.RED),
    RIGHT(TellColour.GREEN, blinks = true)
}

/** The conventional colour of a lit tell-tale. */
enum class TellColour { GREEN, BLUE, AMBER, RED }

/** [NO_DATA]: the bus never gave it (or went silent): drawn dim like Off, but not claiming the lamp is off. */
enum class TellState { NO_DATA, OFF, ON }

/**
 * What the bus holds right now; null = nothing received. [lights] is the canbox's "strongest of main beam / dipped /
 * sidelights, then the fogs" list ([lightsOn]); [beltUnfastened] is the driver's belt NOT buckled.
 */
data class TellInputs(
    val indicators: Indicators? = null,
    val lights: List<CarLight>? = null,
    val handbrake: Boolean? = null,
    val beltUnfastened: Boolean? = null
)

private fun state(known: Boolean, on: Boolean) = if (!known) TellState.NO_DATA else if (on) TellState.ON else TellState.OFF

/**
 * Every tell-tale's state. The hazard switch lights both turn signals as well as its own triangle. The lights list
 * keeps only the strongest beam, and each includes the ones below it, so the parking lamp counts as on with dipped or
 * main beam; low beam is lit for dipped only (the box reports main beam instead of it, as the cluster does).
 */
fun tellStates(i: TellInputs): Map<Tell, TellState> {
    val ind = i.indicators
    val lights = i.lights
    fun has(l: CarLight) = lights?.contains(l) == true
    val hazard = ind?.hazard == true || has(CarLight.HAZARD)
    val hazardKnown = ind != null || lights != null
    return linkedMapOf(
        Tell.LEFT to state(ind != null, ind?.left == true || ind?.hazard == true),
        Tell.PARKING to state(lights != null, has(CarLight.SIDELIGHTS) || has(CarLight.DIPPED) || has(CarLight.MAIN_BEAM)),
        Tell.LOW_BEAM to state(lights != null, has(CarLight.DIPPED)),
        Tell.HIGH_BEAM to state(lights != null, has(CarLight.MAIN_BEAM)),
        Tell.FRONT_FOG to state(lights != null, has(CarLight.FRONT_FOG)),
        Tell.REAR_FOG to state(lights != null, has(CarLight.REAR_FOG)),
        Tell.HAZARD to state(hazardKnown, hazard),
        Tell.HANDBRAKE to state(i.handbrake != null, i.handbrake == true),
        Tell.SEAT_BELT to state(i.beltUnfastened != null, i.beltUnfastened == true),
        Tell.RIGHT to state(ind != null, ind?.right == true || ind?.hazard == true)
    )
}

/** Turn signals flash at 1.5 Hz, half on, half off: the lamp is lit in the first half of each period. */
const val BLINK_PERIOD_MS = 667L

fun blinkPhaseOn(timeMs: Long): Boolean = Math.floorMod(timeMs, BLINK_PERIOD_MS) < BLINK_PERIOD_MS / 2

/**
 * Whether a flashing tell-tale is "engaged" (the lever is set), whichever way the bus reports it: a steady on while
 * the lever is held, or the car's own flashing as on/off pulses. It stays engaged for [HOLD_MS] after the last "on",
 * longer than the gap between a flashing signal's pulses and shorter than a lever flick, so the tile flashes at its
 * own steady rate in both cases instead of following the pulses' jitter.
 */
class BlinkLatch(private val holdMs: Long = HOLD_MS) {
    private var lastOn = Long.MIN_VALUE

    fun observe(on: Boolean, nowMs: Long) {
        if (on) lastOn = nowMs
    }

    fun engaged(on: Boolean, nowMs: Long): Boolean = on || (lastOn != Long.MIN_VALUE && nowMs - lastOn < holdMs)

    companion object {
        const val HOLD_MS = 900L
    }
}

/** Whether to draw this lamp lit now. [engaged] is only used by flashing tell-tales. */
fun lampLit(tell: Tell, state: TellState, engaged: Boolean, nowMs: Long): Boolean =
    if (tell.blinks) engaged && blinkPhaseOn(nowMs) else state == TellState.ON
