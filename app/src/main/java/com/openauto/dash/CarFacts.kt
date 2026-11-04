package com.openauto.dash

import android.content.Context
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.fuelInfoNow
import com.openauto.dash.vehicle.gaugesNow
import com.openauto.dash.vehicle.headlightsNow
import com.openauto.dash.vehicle.ignitionNow
import com.openauto.dash.vehicle.reversingNow
import com.openauto.dash.vehicle.speedKmhNow
import com.openauto.dash.vehicle.tyresNow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Everything Dashwheel knows right now, for Dashwheel's Gemini (DashAssistant):
 * the car (OBD, the CAN box, fuel and range, doors, tyres, climate, battery,
 * faults, servicing, the particle filter), the trip and the drives, the
 * guidance, the weather and the fuel prices around, the music, the phone,
 * the dashboards. The driver asked for Gemini to be told all of it.
 *
 * Most of it is data classes, written as they print (Gemini reads that
 * fine); never what can't or mustn't go: pictures (the turn arrow, covers,
 * contact photos) and secrets (the phone link's keys, the Gemini key).
 * Each part on its own, so one that fails leaves the others.
 */

/** What only the dashboard on screen knows, kept here for [CarFacts]: the music and its dashboards. */
internal object LiveFacts {
    /** The music playing, from the dashboard's media controller. */
    @Volatile
    var media: MediaState? = null

    /** The dashboards the bar offers, as (page, name), Home first. */
    @Volatile
    var dashboards: List<Pair<Int, String>> = emptyList()

    /** The first of the bar's dashboards holding each kind of built-in tile: where the Now card sends a tap. */
    @Volatile
    var widgetPages: Map<BuiltinKind, Int> = emptyMap()
}

internal object CarFacts {
    /** Longest any one part is let be: a list that grew out of hand stays readable. */
    private const val PART_MAX = 1_500
    private const val NOTIFICATION_CHARS = 200

    fun snapshot(context: Context): JSONObject {
        val o = JSONObject()
        fun put(name: String, value: () -> Any?) {
            val v = runCatching { value() }.getOrNull() ?: return
            o.put(
                name,
                when (v) {
                    is JSONObject, is JSONArray, is Number, is Boolean -> v
                    is Collection<*> -> if (v.isEmpty()) return else v.joinToString("; ").take(PART_MAX)
                    is Map<*, *> -> if (v.isEmpty()) return else v.toString().take(PART_MAX)
                    else -> v.toString().take(PART_MAX)
                }
            )
        }
        val now = System.currentTimeMillis()
        val obdOn = ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED

        put("time") { SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(now)) }
        put("units") { Units.current.value }
        put("car") { CarProfileStore.current }
        put("ignition_on") { ignitionNow() }
        put("speed_kmh") { speedKmhNow() }
        put("obd_adapter") { ObdBluetoothManager.connectionState.value.name }
        // The gauges the poll always reads ([VehicleMonitor.POLL_FLOOR]): the snapshot holds nothing of its own.
        put("obd_readings") { gaugesNow(VehicleMonitor.POLL_FLOOR).toObdData().takeIf { obdOn } }
        put("engine_warning_lamp") { ObdBluetoothManager.lamp.value }
        put("fuel") {
            fuelInfoNow()?.let {
                "percent=${it.percent}, range_km=${it.rangeKm}, liters_left=%.1f, from=${it.source}".format(Locale.ROOT, it.liters)
            }
        }
        put("car_trip_computer") {
            listOfNotNull(
                Vehicle.bus.now(K.FUEL_CONSUMPTION)?.let { "consumption_l_per_100km=%.1f".format(Locale.ROOT, it) },
                Vehicle.bus.now(K.TRIP_DISTANCE)?.let { "trip_km=%.1f".format(Locale.ROOT, it) },
                Vehicle.bus.now(K.ODOMETER)?.takeIf { it > 0.0 }?.let { "odometer_km=%.0f".format(Locale.ROOT, it) },
                Vehicle.bus.now(K.LIGHTS)?.let { "lights=${it.joinToString().ifEmpty { "off" }}" },
                Vehicle.bus.now(K.HANDBRAKE)?.let { "handbrake_on=$it" },
                Vehicle.bus.now(K.DRIVER_BELT)?.let { "driver_belt_unfastened=$it" }
            ).joinToString(", ").ifEmpty { null }
        }
        put("psa_trip_computer") { PsaTripComputer.fresh()?.let { t -> t.copy(at = 0L).toString() + t.warnings.joinToString(prefix = ", messages=") { c -> PsaWarnings.of(c)?.let { w -> context.getString(w.textRes) } ?: "code $c" } } }
        put("climate") { Vehicle.bus.now(K.CLIMATE) }
        put("parking_sensors") { Vehicle.bus.now(K.RADAR) }
        put("reverse_gear") { reversingNow() }
        put("doors") { Vehicle.bus.now(K.DOORS) }
        put("tyres") { tyresNow() }
        put("tyre_problems") { Tyres.problems.value }
        put("battery") { BatteryWatch.state.value }
        put("battery_volts") { Vehicle.bus.now(K.BATTERY_VOLTS) }
        put("headlights_on") { headlightsNow() }
        put("fault_codes") {
            AiMechanic.state.value.let { s -> s.warningCodes?.let { "codes=$it, diagnosis=${s.diagnosis}" } }
        }
        put("pending_fault_codes") { ObdBluetoothManager.pending.value }
        put("servicing") { Maintenance.state.value.statuses(now) }
        put("car_news") { CarNews.notices.value.takeLast(5) }
        put("eco_and_rest") { CarCare.state.value }
        put("particle_filter") { CarCare.filterWatch.value }
        put("vehicle_alerts") {
            (AlertCenter.critical.values + AlertCenter.warnings.toList()).map { "${it.level}: ${it.text}" }
        }

        put("position") {
            LocationFeed.location.value?.let { "lat=%.5f, lng=%.5f".format(Locale.ROOT, it.latitude, it.longitude) }
        }
        put("heading_deg") { LocationFeed.headingDeg.value }
        put("trip") {
            LocationFeed.trip.value.let {
                "distance_km=%.1f, moving_min=%d, average_kmh=%.0f, max_kmh=%.0f, started=%s".format(
                    Locale.ROOT, it.distanceM / 1000, it.movingMs / 60_000, it.avgSpeedKmh, it.maxSpeedKmh,
                    SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(it.startedAt))
                )
            }
        }
        put("current_drive") { DriveLog.current.value }
        put("recent_drives") { DriveLog.drives.value.take(3) }
        put("parked_car") { ParkingStore.spot.value }
        put("navigation") {
            NavDirections.state.value.let { n ->
                if (!n.active) "no guidance running"
                else JSONObject()
                    .put("app", n.packageName)
                    .put("next_instruction", n.instruction)
                    .put("distance_to_next_turn", n.distance)
                    .put("remaining_time_distance_arrival", n.eta)
            }
        }
        put("last_destination") { NavHandoff.destination?.first }
        put("saved_places") { PlacesStore.places.value }
        put("weather") { WeatherRepo.weather.value?.let { "$it, condition=${context.getString(it.conditionRes)}" } }
        put("nearby_fuel_prices") {
            val here = LocationFeed.location.value ?: return@put null
            val stations = FuelPriceRepo.stations.value ?: return@put null
            FuelPrices.rank(stations, FuelPrices.gradesFor(CarProfileStore.current).first(), here.latitude, here.longitude).take(5)
        }
        put("fuel_log") {
            "last_fills=${FuelLog.fills.value.takeLast(3)}, measured_l_per_100km=${FuelLog.litersPer100()}, last_price=${FuelLog.lastPrice()}"
        }

        put("music") { LiveFacts.media?.let { "title=${it.title}, artist=${it.artist}, playing=${it.isPlaying}" } }
        put("music_source") { HeadUnitMedia.source() }
        put("radio") { HeadUnitMedia.radio }
        put("volume") {
            val am = MediaVolume.audio(context)
            "level=${MediaVolume.level(am)} of ${MediaVolume.max(am)}, muted=${MediaVolume.isMuted(am)}"
        }

        put("phone_link") { PhoneLink.state.value }
        put("phone_battery") { PhoneLink.battery.value }
        put("phone_on_unit_bluetooth") { UnitSignals.phone.value }
        put("call") { PhoneCallOverlay.call.value?.let { "phase=${it.phase}, name=${it.name}, number=${it.number}" } }
        put("recent_notifications") {
            // Other people's words, handed to the model: the start of each is enough to answer "what did it say".
            NotificationFeed.items.value.take(5).map { "${it.appLabel}: ${it.title}, ${it.text}".take(NOTIFICATION_CHARS) }
        }
        put("phone_agenda") { PhoneLink.lists.value.agenda }

        put("dashboards") { LiveFacts.dashboards.map { it.second } }
        put("head_unit") { HeadUnitMonitor.stats.value?.toString()?.take(400) }
        put("demo_mode") { DemoMode.isOn }
        return o
    }
}
