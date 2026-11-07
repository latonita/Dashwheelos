package com.openauto.dash.vehicle

/*
 * The vocabulary of the bus: who a value came from ([Source], [SourceKind]),
 * how far to trust it ([Quality]), the value with both ([Sample]), and what
 * the bus announces besides values ([VehicleEvent]).
 */

/** What kind of provider a source is. Lower ranks win; equal ranks are the same standing. */
enum class SourceKind(val rank: Int) {
    /** Demo mode, while it is on. */
    DEMO(-2),
    /** Signals the driver taught (Signal Finder); they beat the built-in decoding, as before. */
    LEARNED(-1),
    /** The car, through the head unit. */
    CANBOX(0), MCU(0), VEHICLE_APP(0),
    /** An adapter, Deep OBD. */
    OBD(1),
    /** Estimates: gear from rpm and speed, range from fuel. */
    COMPUTED(2),
    GPS(3),
    /** Weather. */
    NETWORK(4)
}

/** A provider's identity. [id] is unique and stable (it is what the driver's choice is saved by). */
data class Source(val id: String, val kind: SourceKind, val label: String)

/** DEGRADED loses to GOOD and ESTIMATED from any rank; GOOD beats ESTIMATED at the same rank. */
enum class Quality { GOOD, ESTIMATED, DEGRADED }

/** A value, where it came from, when ([atMs], the bus clock) and how far to trust it. */
data class Sample<T : Any>(val value: T, val source: Source, val atMs: Long, val quality: Quality = Quality.GOOD)

/** The gear, the same on every car; a computed provider publishes it as [Quality.ESTIMATED]. */
sealed interface Gear {
    data object Park : Gear
    data object Reverse : Gear
    data object Neutral : Gear
    data object Drive : Gear
    /** A numbered forward gear (manual, or a held automatic). */
    data class Forward(val n: Int) : Gear
}

/** The key position the car reports. */
enum class IgnitionState { NO_KEY, KEY_OFF, ACC, ON, CRANK }

/** Cruise control: [ARMED] = switched on, [ENGAGED] = a speed is set. */
enum class Cruise { OFF, ARMED, ENGAGED }

/** The 4WD range; [SHIFTING] is the transfer case moving between the two. */
enum class TransferCase { HIGH, LOW, SHIFTING }

/** The warning lamps of the instrument cluster the car reports. */
enum class Telltale { ESP_OFF, MIL, OIL, CHARGE, ABS, ESP, GLOW, WATER_IN_FUEL }

sealed interface VehicleEvent {
    /** The resolved value of a [Key.State] changed; [value] and [source] are null when it went away. */
    data class Changed(val key: Key<*>, val value: Any?, val source: Source?) : VehicleEvent
}
