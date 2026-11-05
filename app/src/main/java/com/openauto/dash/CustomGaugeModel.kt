package com.openauto.dash

import com.openauto.dash.vehicle.Domain
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import java.util.Locale

/*
 * The pure parts of the Custom gauge tile: one vehicle-bus signal, shown in a
 * chosen style. The tile's own settings (signal, style, min, max) live on the
 * tile ([DashboardItem.BuiltinWidget.opts]) as one short string, so every copy
 * keeps its own and they travel with the layout (templates, backups, sharing).
 */

/** How the signal is drawn. Names are saved: never rename an entry. */
enum class GaugeStyle { DIAL, BAR_H, BAR_V, NUMBER }

/**
 * One tile's settings. [keyId] is a [Key.id] on the vehicle bus (null until
 * the driver has picked one); [min] / [max] are the dial's and bars' ends in
 * the key's own (metric) unit, null for the default of [defaultGaugeRange].
 */
data class GaugeConfig(
    val keyId: String? = null,
    val style: GaugeStyle = GaugeStyle.DIAL,
    val min: Double? = null,
    val max: Double? = null
) {
    /** `g1|key|STYLE|min|max`; an empty field is the default. Key ids never hold a `|`. */
    fun encode(): String = listOf(
        "g1", keyId.orEmpty(), style.name, min?.let(::number).orEmpty(), max?.let(::number).orEmpty()
    ).joinToString("|")

    companion object {
        /** What [encode] wrote; anything unreadable is the blank config (nothing picked, a dial). */
        fun decode(text: String?): GaugeConfig {
            val p = text?.split('|') ?: return GaugeConfig()
            if (p.size < 3 || p[0] != "g1") return GaugeConfig()
            val style = GaugeStyle.entries.firstOrNull { it.name == p[2] } ?: GaugeStyle.DIAL
            return GaugeConfig(
                keyId = p[1].ifBlank { null },
                style = style,
                min = p.getOrNull(3)?.toDoubleOrNull()?.takeIf { it.isFinite() },
                max = p.getOrNull(4)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            )
        }

        private fun number(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else v.toString()
    }
}

/**
 * Whether a key's value is a plain number the tile can draw on a scale: a reading or a counter, or the first
 * available of such readings. States (gear, doors, lights, the turn signals, tyres) are typed values with no
 * display string on the bus, so they are not offered.
 */
internal fun Key<*>.isGaugeNumeric(): Boolean = when (this) {
    is Key.Measurement<*>, is Key.Counter -> true
    is Key.BestOf<*> -> members.all { it.isGaugeNumeric() }
    else -> false
}

/** The keys the picker offers: every numeric one of [all] (the catalogue, so a key a branch adds shows up by itself). */
internal fun gaugeKeys(all: List<Key<*>> = K.all): List<Key<*>> = all.filter { it.isGaugeNumeric() }

/** [id]'s key among [all], null when this build has no such key (a layout from a newer build). */
internal fun gaugeKeyById(id: String?, all: List<Key<*>> = K.all): Key<*>? = id?.let { wanted -> all.firstOrNull { it.id == wanted } }

private val DOMAIN_ORDER = listOf(
    Domain.MOTION, Domain.POWERTRAIN, Domain.ENERGY, Domain.TRIP, Domain.ENVIRONMENT, Domain.BODY,
    Domain.TYRES, Domain.CLIMATE, Domain.DRIVER_AIDS, Domain.IDENTITY
)

/** The picker's groups, in reading order: a domain and its keys sorted by [name]; empty domains are left out. */
internal fun <T> groupGaugeKeys(keys: List<Key<*>>, name: (Key<*>) -> String, tag: (Domain) -> T): List<Pair<T, List<Key<*>>>> =
    DOMAIN_ORDER.mapNotNull { d ->
        val inDomain = keys.filter { it.domain == d }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, name))
        if (inDomain.isEmpty()) null else tag(d) to inDomain
    }

/** "fuel_litres" as words, for a key nobody has written a name for yet. */
internal fun humanizeKeyId(id: String): String =
    id.replace('_', ' ').trim().replaceFirstChar { it.titlecase(Locale.ROOT) }

/**
 * The scale a key's dial and bars get when the driver sets none, in the key's own unit. The catalogue carries no
 * limits, so these are what the quantity usually spans: by key where it is known, else by the unit.
 */
internal fun defaultGaugeRange(keyId: String?, unit: String?): Pair<Double, Double> = when (keyId) {
    "coolant_temp" -> 40.0 to 130.0
    "intake_temp" -> -20.0 to 80.0
    "outside_temp", "forecast_temp", "ambient_temp" -> -30.0 to 50.0
    "battery_volts" -> 9.0 to 16.0
    "steering_angle" -> -540.0 to 540.0
    "car_heading" -> 0.0 to 360.0
    "rpm" -> 0.0 to 7000.0
    "range" -> 0.0 to 800.0
    "odometer" -> 0.0 to 300_000.0
    "trip_distance" -> 0.0 to 1000.0
    "fuel_litres" -> 0.0 to 100.0
    else -> when (unit) {
        "km/h" -> 0.0 to 240.0
        "rpm" -> 0.0 to 7000.0
        "%" -> 0.0 to 100.0
        "°C" -> 0.0 to 120.0
        "V" -> 0.0 to 16.0
        "L/h" -> 0.0 to 30.0
        "L/100 km" -> 0.0 to 30.0
        "km" -> 0.0 to 1000.0
        "L" -> 0.0 to 100.0
        "kPa" -> 0.0 to 400.0
        "°" -> 0.0 to 360.0
        else -> 0.0 to 100.0
    }
}

/** The scale in force: the driver's ends where they make one (max above min), else the defaults for the key. */
internal fun gaugeRange(config: GaugeConfig, unit: String?): Pair<Double, Double> {
    val default = defaultGaugeRange(config.keyId, unit)
    val lo = config.min ?: default.first
    val hi = config.max ?: default.second
    return if (hi > lo) lo to hi else default
}

/** How far [value] sits between [min] and [max], 0..1; null for no value. */
internal fun gaugeFraction(value: Double?, min: Double, max: Double): Float? {
    if (value == null || !value.isFinite() || max <= min) return null
    return ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

/**
 * A key's unit as the tile shows it: the label, the decimals, and the way from the stored (metric) value to the
 * shown one ([toShown], null where there is no figure, e.g. mpg at a standstill) and back ([fromShown], null
 * where that is not a straight line: mpg). The scale's ends stay in the key's unit; this is only the way out.
 */
internal class GaugeUnit(
    val label: String,
    /** -1: one decimal under 100, none above. */
    val decimals: Int,
    val toShown: (Double) -> Double?,
    val fromShown: ((Double) -> Double)?
)

/** [unit] (a [Key.unit]) in the app's units: speed and distance, temperature, economy and pressure follow the driver's choices. */
internal fun gaugeUnit(unit: String?, units: UnitSystem, pressure: PressureUnit = PressureUnit.BAR): GaugeUnit {
    fun linear(label: String, decimals: Int, factor: Double, offset: Double = 0.0) =
        GaugeUnit(label, decimals, { it * factor + offset }, { (it - offset) / factor })
    val per = UnitSystem.KM_PER_MILE
    return when (unit) {
        "km/h" -> if (units.imperial) linear(units.speedUnit, 0, 1 / per) else linear(units.speedUnit, 0, 1.0)
        "km" -> if (units.imperial) linear(units.distanceUnit, 0, 1 / per) else linear(units.distanceUnit, 0, 1.0)
        "°C" -> if (units.fahrenheit) linear(units.tempUnit, 0, 1.8, 32.0) else linear(units.tempUnit, 0, 1.0)
        "L/100 km" -> GaugeUnit(
            units.economyUnit, 1, { units.economy(it) },
            if (units.economy == Economy.L_PER_100KM) { v -> v } else null
        )
        "kPa" -> when (pressure) {
            PressureUnit.BAR -> linear("bar", 1, 0.01)
            PressureUnit.PSI -> linear("psi", 0, 1 / 6.895)
            PressureUnit.KPA -> linear("kPa", 0, 1.0)
        }
        "V", "L/h", "L" -> linear(unit, 1, 1.0)
        "%", "rpm", "°" -> linear(unit, 0, 1.0)
        null -> linear("", -1, 1.0)
        else -> linear(unit, -1, 1.0)
    }
}

/** [value] for the screen, in [unit]'s shown form; "--" when there is none. */
internal fun formatGaugeValue(value: Double?, unit: GaugeUnit, locale: Locale = Locale.getDefault()): String {
    val shown = value?.takeIf { it.isFinite() }?.let(unit.toShown) ?: return "--"
    val decimals = if (unit.decimals >= 0) unit.decimals else if (kotlin.math.abs(shown) < 100) 1 else 0
    val text = String.format(locale, "%.${decimals}f", shown)
    // A small negative that rounds to nothing reads "0", not "-0".
    return if (text.startsWith("-") && text.drop(1).all { it == '0' || it == '.' || it == ',' }) text.drop(1) else text
}

/** A scale end for the editor's field: the shown figure without trailing zeros. */
internal fun formatGaugeEnd(stored: Double, unit: GaugeUnit, locale: Locale = Locale.US): String {
    val shown = if (unit.fromShown != null) unit.toShown(stored) ?: stored else stored
    return String.format(locale, "%.2f", shown).trimEnd('0').trimEnd('.')
}

/** A figure typed in the editor, in the shown unit, as the stored one; null for blank or not a number. */
internal fun parseGaugeEnd(text: String, unit: GaugeUnit): Double? {
    val typed = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return unit.fromShown?.invoke(typed) ?: typed
}
