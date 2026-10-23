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
    val HANDBRAKE = Key.State<Boolean>("handbrake", Domain.BODY, DISPLAY)
    /**
     * The instrument-panel dimmer, 0..100 %. A level the driver turns through many steps, not a state: as a State
     * every step would be a [VehicleEvent.Changed]. A slow measurement (a box repeats it within seconds).
     */
    val INSTRUMENT_DIMMER = Key.Measurement<Double>("instrument_dimmer", Domain.BODY, DISPLAY, 60 * S, unit = "%")
    /** True = unfastened. */
    val DRIVER_BELT = Key.State<Boolean>("driver_belt", Domain.BODY, WARNING)

    // --- Climate, driver aids, tyres
    val CLIMATE = Key.State<Climate>("climate", Domain.CLIMATE, DISPLAY)
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
        AMBIENT_TEMP, DOORS, REVERSE, HEADLIGHTS, LIGHTS, HANDBRAKE, INSTRUMENT_DIMMER, DRIVER_BELT, CLIMATE, RADAR, TYRES, ODOMETER, TRIP_DISTANCE, CAR_HEADING, CANBOX_VERSION
    )
}
