package com.openauto.dash.vehicle

import com.openauto.dash.CarLight
import com.openauto.dash.Climate
import com.openauto.dash.McuReader
import com.openauto.dash.Radar
import com.openauto.dash.Tyre
import com.openauto.dash.TyrePos
import com.openauto.dash.vehicle.Criticality.DISPLAY
import com.openauto.dash.vehicle.Criticality.SAFETY
import com.openauto.dash.vehicle.Criticality.WARNING

/*
 * Every element the app knows, once. The times are how these values arrive
 * today (the outside temperature is kept for 2 h, as before). Different
 * meaning, different key: GPS speed is ground speed and the car's speed comes
 * from the wheels, so they differ and may disagree. Structured values reuse
 * the types the app already has.
 */
object K {
    private const val S = 1_000L
    private const val HOUR = 3_600 * S

    // --- Motion
    val VEHICLE_SPEED = Key.Measurement<Double>("vehicle_speed", Domain.MOTION, SAFETY, 3 * S, maxHz = 10, unit = "km/h")
    val GPS_SPEED = Key.Measurement<Double>("gps_speed", Domain.MOTION, DISPLAY, 5 * S, maxHz = 10, unit = "km/h")
    val SPEED = Key.BestOf("speed", VEHICLE_SPEED, GPS_SPEED)
    val STEERING_ANGLE = Key.Measurement<Double>("steering_angle", Domain.MOTION, DISPLAY, 3 * S, maxHz = 10, unit = "°")
    /** Degrees (0-359, magnetic) from the car's own compass; not [GPS_SPEED]'s sibling: the GPS heading is a different thing and stays in the location feed. */
    val CAR_HEADING = Key.Measurement<Double>("car_heading", Domain.MOTION, DISPLAY, 10 * S, maxHz = 2, unit = "°")

    // --- Powertrain
    val RPM = Key.Measurement<Double>("rpm", Domain.POWERTRAIN, DISPLAY, 3 * S, maxHz = 10, unit = "rpm")
    val THROTTLE = Key.Measurement<Double>("throttle", Domain.POWERTRAIN, DISPLAY, 3 * S, maxHz = 10, unit = "%")
    val ENGINE_LOAD = Key.Measurement<Double>("engine_load", Domain.POWERTRAIN, DISPLAY, 3 * S, maxHz = 10, unit = "%")
    val COOLANT_TEMP = Key.Measurement<Double>("coolant_temp", Domain.POWERTRAIN, WARNING, 60 * S, unit = "°C")
    val INTAKE_TEMP = Key.Measurement<Double>("intake_temp", Domain.POWERTRAIN, DISPLAY, 60 * S, unit = "°C")
    val ENGINE_RUNNING = Key.State<Boolean>("engine_running", Domain.POWERTRAIN, DISPLAY)
    val GEAR = Key.State<Gear>("gear", Domain.POWERTRAIN, DISPLAY)
    val IGNITION = Key.State<Boolean>("ignition", Domain.POWERTRAIN, WARNING)
    /** The key position the car reports (no key, key off, ACC, on, crank); the canbox's, where [IGNITION] is only ACC on/off. */
    val IGNITION_STATE = Key.State<IgnitionState>("ignition_state", Domain.POWERTRAIN, DISPLAY)
    /** Accessory power (RAP) is up: the box stays awake after the key is out. */
    val ACCESSORY_POWER = Key.State<Boolean>("accessory_power", Domain.POWERTRAIN, DISPLAY)
    /** A valid key is in the lock. */
    val KEY_IN = Key.State<Boolean>("key_in", Domain.POWERTRAIN, DISPLAY)
    /** The warning lamps that are lit (an empty set = none). */
    val TELLTALES = Key.State<Set<Telltale>>("telltales", Domain.POWERTRAIN, WARNING)
    /** 4WD range: high, low or shifting; unknown when the car does not say. */
    val TRANSFER_CASE = Key.State<TransferCase>("transfer_case", Domain.POWERTRAIN, DISPLAY)
    /** Engine oil pressure, kPa gauge (the car's own scale; 0 with the engine off). */
    val OIL_PRESSURE = Key.Measurement<Double>("oil_pressure", Domain.POWERTRAIN, WARNING, 5 * S, maxHz = 2, unit = "kPa")
    /** Manifold absolute pressure; boost above ambient is MAP minus the ambient pressure, a consumer's calculation. */
    val MANIFOLD_PRESSURE = Key.Measurement<Double>("manifold_pressure", Domain.POWERTRAIN, DISPLAY, 3 * S, maxHz = 10, unit = "hPa")
    /** Minutes since the engine last ran. */
    val ENGINE_OFF_MINUTES = Key.Measurement<Double>("engine_off_minutes", Domain.POWERTRAIN, DISPLAY, 10 * S, unit = "min")

    // --- Energy
    val FUEL_FLOW = Key.Measurement<Double>("fuel_flow", Domain.ENERGY, DISPLAY, 5 * S, unit = "L/h")
    val BATTERY_VOLTS = Key.Measurement<Double>("battery_volts", Domain.ENERGY, WARNING, 60 * S, unit = "V")
    val FUEL_LEVEL = Key.Measurement<Double>("fuel_level", Domain.ENERGY, WARNING, 120 * S, unit = "%")
    /** The tank in litres, for a box that measures litres: never converted to a percentage (the tank size is the app's guess, not the car's). */
    val FUEL_LITRES = Key.Measurement<Double>("fuel_litres", Domain.ENERGY, DISPLAY, 120 * S, unit = "L")
    val RANGE = Key.Measurement<Double>("range", Domain.ENERGY, DISPLAY, 120 * S, unit = "km")
    /** What the car's trip computer says it is using now, in L/100 km (0 off the throttle). */
    val FUEL_CONSUMPTION = Key.Measurement<Double>("fuel_consumption", Domain.ENERGY, DISPLAY, 10 * S, unit = "L/100 km")

    // --- Environment
    /** The car's own sensor. */
    val OUTSIDE_TEMP = Key.Measurement<Double>("outside_temp", Domain.ENVIRONMENT, DISPLAY, 2 * HOUR, unit = "°C")
    val FORECAST_TEMP = Key.Measurement<Double>("forecast_temp", Domain.ENVIRONMENT, DISPLAY, 3 * HOUR, unit = "°C")
    val AMBIENT_TEMP = Key.BestOf("ambient_temp", OUTSIDE_TEMP, FORECAST_TEMP)

    // --- Body
    val DOORS = Key.State<McuReader.DoorState>("doors", Domain.BODY, WARNING)
    val REVERSE = Key.State<Boolean>("reverse", Domain.BODY, SAFETY)
    val HEADLIGHTS = Key.State<Boolean>("headlights", Domain.BODY, DISPLAY)
    /** Which lights are on, as the driver would say it ([CarLight]); [HEADLIGHTS] is the one the appearance follows. */
    val LIGHTS = Key.State<List<CarLight>>("lights", Domain.BODY, DISPLAY)
    /** The turn indicators and the hazards: which of them are lit right now. */
    val INDICATORS = Key.State<Indicators>("indicators", Domain.BODY, DISPLAY)
    val HANDBRAKE = Key.State<Boolean>("handbrake", Domain.BODY, DISPLAY)
    /** The brake pedal switch. */
    val BRAKE_PEDAL = Key.State<Boolean>("brake_pedal", Domain.BODY, DISPLAY)
    /**
     * The instrument-panel dimmer, 0..100 %. A level the driver turns through many steps, not a state: as a State
     * every step would be a [VehicleEvent.Changed]. A slow measurement (a box repeats it within seconds).
     */
    val INSTRUMENT_DIMMER = Key.Measurement<Double>("instrument_dimmer", Domain.BODY, DISPLAY, 60 * S, unit = "%")
    /** True = unfastened. */
    val DRIVER_BELT = Key.State<Boolean>("driver_belt", Domain.BODY, WARNING)

    // --- Climate, driver aids, tyres
    val CLIMATE = Key.State<Climate>("climate", Domain.CLIMATE, DISPLAY)
    /** The A/C compressor is running (the car's own signal; [CLIMATE] models a vendor HVAC panel instead). */
    val AC_COMPRESSOR = Key.State<Boolean>("ac_compressor", Domain.CLIMATE, DISPLAY)
    /** Cruise control: off, armed (on) or engaged (a speed is set). */
    val CRUISE = Key.State<Cruise>("cruise", Domain.DRIVER_AIDS, DISPLAY)
    val RADAR = Key.State<Radar>("radar", Domain.DRIVER_AIDS, SAFETY)
    val TYRES = Key.State<Map<TyrePos, Tyre>>("tyres", Domain.TYRES, WARNING)

    // --- Identity
    /** What the canbox names itself with (its 0xF0 reply). */
    val CANBOX_VERSION = Key.Info<String>("canbox_version", Domain.IDENTITY, DISPLAY)

    // --- Trip
    val ODOMETER = Key.Counter("odometer", Domain.TRIP, DISPLAY, unit = "km")
    /** The car's own trip counter: the driver resets it, so it is a reading, not a counter. */
    val TRIP_DISTANCE = Key.Measurement<Double>("trip_distance", Domain.TRIP, DISPLAY, 6 * HOUR, unit = "km")

    /** The whole catalogue (the BestOf ones are read-only). */
    val all: List<Key<*>> = listOf(
        VEHICLE_SPEED, GPS_SPEED, SPEED, STEERING_ANGLE, RPM, THROTTLE, ENGINE_LOAD, COOLANT_TEMP, INTAKE_TEMP,
        ENGINE_RUNNING, GEAR, IGNITION, FUEL_FLOW, BATTERY_VOLTS, FUEL_LEVEL, FUEL_LITRES, RANGE, FUEL_CONSUMPTION, OUTSIDE_TEMP, FORECAST_TEMP,
        AMBIENT_TEMP, DOORS, REVERSE, HEADLIGHTS, LIGHTS, INDICATORS, HANDBRAKE, INSTRUMENT_DIMMER, DRIVER_BELT, CLIMATE, RADAR, TYRES, ODOMETER, TRIP_DISTANCE, CAR_HEADING, CANBOX_VERSION,
        IGNITION_STATE, ACCESSORY_POWER, KEY_IN, TELLTALES, TRANSFER_CASE, OIL_PRESSURE, MANIFOLD_PRESSURE, ENGINE_OFF_MINUTES,
        BRAKE_PEDAL, AC_COMPRESSOR, CRUISE
    )
}

/** Which turn signals are lit. [hazard] = the hazard switch, which flashes both sides. */
data class Indicators(val left: Boolean, val right: Boolean, val hazard: Boolean = false)
