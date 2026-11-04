package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A human-readable meaning + fix hint for an OBD-II trouble code.
 *
 * [title] / [fix] are the English text (logs, tests, non-UI callers). The UI
 * shows the localized text via [titleRes] / [fixRes]: use [localizedTitle] /
 * [localizedFix]. For codes not in the table, [titleRes] is a template filled
 * with the string resources in [titleParts] (system, scope, area) and [fixRes]
 * takes the code as its argument.
 */
data class DtcInfo(
    val code: String,
    val title: String,
    val fix: String,
    @StringRes val titleRes: Int,
    @StringRes val fixRes: Int,
    val titleParts: List<Int> = emptyList()
) {
    fun localizedTitle(context: Context): String =
        if (titleParts.isEmpty()) context.getString(titleRes)
        else context.getString(titleRes, *titleParts.map { context.getString(it) }.toTypedArray())

    fun localizedFix(context: Context): String =
        if (fixRes == R.string.vehicle_dtc_fallback_fix) context.getString(fallbackFixRes(), code) else context.getString(fixRes)
}

/** The fallback hint names the C4 Picasso, the car the table was written for: only for a PSA car. */
@StringRes
internal fun fallbackFixRes(): Int =
    if (PsaTripComputer.forThisCar()) R.string.vehicle_dtc_fallback_fix else R.string.vehicle_dtc_fallback_fix_any

/** The code's localized meaning, for Compose. */
@Composable
fun DtcInfo.localizedTitle(): String =
    if (titleParts.isEmpty()) stringResource(titleRes)
    else stringResource(titleRes, *titleParts.map { stringResource(it) }.toTypedArray())

/** The code's localized fix hint, for Compose. */
@Composable
fun DtcInfo.localizedFix(): String =
    if (fixRes == R.string.vehicle_dtc_fallback_fix) stringResource(fallbackFixRes(), code) else stringResource(fixRes)

/**
 * Explains OBD-II Diagnostic Trouble Codes. Contains the common generic codes
 * plus ones frequent on the user's **Citroën C4 Picasso (2011)** — 1.6 VTi/THP
 * petrol (timing chain, cam/crank, turbo) and 1.6/2.0 HDi diesel (fuel rail,
 * DPF, EGR). Unknown codes fall back to a description derived from the code's
 * structure so there's always something useful.
 */
object ObdCodes {

    private class Entry(@StringRes val titleRes: Int, @StringRes val fixRes: Int, val title: String, val fix: String)

    /** One piece of a fallback title: its string resource and English text. */
    private class Part(@StringRes val res: Int, val english: String)

    private val TABLE: Map<String, Entry> = mapOf(
        // --- Misfires (common on 1.6 THP) ---
        "P0300" to Entry(R.string.vehicle_dtc_p0300_title, R.string.vehicle_dtc_p0300_fix, "Random/multiple cylinder misfire", "Check spark plugs & coils (THP: coils are a known weak point), fuel injectors, and for vacuum leaks. Do plugs/coils as a set."),
        "P0301" to Entry(R.string.vehicle_dtc_p0301_title, R.string.vehicle_dtc_p0301_fix, "Cylinder 1 misfire", "Swap coil/plug from cyl 1 to another cyl; if the misfire follows, replace that coil/plug. Also check injector 1."),
        "P0302" to Entry(R.string.vehicle_dtc_p0302_title, R.string.vehicle_dtc_p0302_fix, "Cylinder 2 misfire", "Swap coil/plug to isolate; check injector 2 and compression."),
        "P0303" to Entry(R.string.vehicle_dtc_p0303_title, R.string.vehicle_dtc_p0303_fix, "Cylinder 3 misfire", "Swap coil/plug to isolate; check injector 3 and compression."),
        "P0304" to Entry(R.string.vehicle_dtc_p0304_title, R.string.vehicle_dtc_p0304_fix, "Cylinder 4 misfire", "Swap coil/plug to isolate; check injector 4 and compression."),

        // --- Timing / cam-crank (THP timing chain stretch is very common) ---
        "P0016" to Entry(R.string.vehicle_dtc_p0016_title, R.string.vehicle_dtc_p0016_fix, "Crankshaft/camshaft correlation (bank1 sensor A)", "Classic 1.6 THP stretched timing chain. Check chain, tensioner and cam/crank sensors. Often needs a timing chain kit."),
        "P0017" to Entry(R.string.vehicle_dtc_p0017_title, R.string.vehicle_dtc_p0017_fix, "Crank/cam correlation (bank1 sensor B)", "Likely stretched timing chain / tensioner (1.6 THP). Inspect chain & sprockets."),
        "P0011" to Entry(R.string.vehicle_dtc_p0011_title, R.string.vehicle_dtc_p0011_fix, "Intake camshaft timing over-advanced (bank1)", "VVT/cam phaser or oil control valve. Check oil level/quality, VVT solenoid, and timing chain wear."),
        "P0341" to Entry(R.string.vehicle_dtc_p0341_title, R.string.vehicle_dtc_p0341_fix, "Camshaft position sensor range", "Check the cam sensor and its wiring; verify timing (chain) is correct."),
        "P0340" to Entry(R.string.vehicle_dtc_p0340_title, R.string.vehicle_dtc_p0340_fix, "Camshaft position sensor circuit", "Test/replace cam sensor; inspect connector and wiring."),

        // --- Turbo / boost (THP & HDi) ---
        "P0299" to Entry(R.string.vehicle_dtc_p0299_title, R.string.vehicle_dtc_p0299_fix, "Turbocharger underboost", "Common on THP/HDi. Check for boost leaks (hoses/intercooler), wastegate/turbo actuator, and the diverter valve."),
        "P0234" to Entry(R.string.vehicle_dtc_p0234_title, R.string.vehicle_dtc_p0234_fix, "Turbo overboost", "Check wastegate actuator sticking, boost solenoid, and for a stuck VNT (HDi)."),

        // --- Fuel system (HDi diesel) ---
        "P0087" to Entry(R.string.vehicle_dtc_p0087_title, R.string.vehicle_dtc_p0087_fix, "Fuel rail pressure too low", "HDi: check fuel filter, low-pressure supply, high-pressure pump and rail pressure regulator; look for air/leaks."),
        "P0089" to Entry(R.string.vehicle_dtc_p0089_title, R.string.vehicle_dtc_p0089_fix, "Fuel pressure regulator performance", "Check the rail pressure regulator/metering valve and pump; inspect fuel filter."),
        "P0093" to Entry(R.string.vehicle_dtc_p0093_title, R.string.vehicle_dtc_p0093_fix, "Large fuel leak detected", "HDi: inspect injector leak-off, high-pressure pipes and pump seals for diesel leaks."),

        // --- Emissions / after-treatment ---
        "P0420" to Entry(R.string.vehicle_dtc_p0420_title, R.string.vehicle_dtc_p0420_fix, "Catalyst efficiency below threshold (bank1)", "Often a worn catalytic converter or a lazy rear O2 sensor. Rule out exhaust leaks and misfires first."),
        "P0401" to Entry(R.string.vehicle_dtc_p0401_title, R.string.vehicle_dtc_p0401_fix, "EGR flow insufficient", "Clean or replace the EGR valve; check EGR cooler and passages for carbon (common on HDi)."),
        "P0402" to Entry(R.string.vehicle_dtc_p0402_title, R.string.vehicle_dtc_p0402_fix, "EGR flow excessive", "Check EGR valve stuck open and its control; clean carbon build-up."),
        "P2463" to Entry(R.string.vehicle_dtc_p2463_title, R.string.vehicle_dtc_p2463_fix, "DPF soot accumulation too high", "HDi diesel: do a long highway run for regeneration; check DPF pressure sensor and additive (Eolys) level. May need forced regen."),
        "P242F" to Entry(R.string.vehicle_dtc_p242f_title, R.string.vehicle_dtc_p242f_fix, "DPF restriction (ash accumulation)", "DPF near end of life or blocked; may need forced regen or DPF cleaning/replacement."),

        // --- Sensors ---
        "P0113" to Entry(R.string.vehicle_dtc_p0113_title, R.string.vehicle_dtc_p0113_fix, "Intake air temperature sensor high", "Check IAT sensor and wiring/connector; clean or replace the sensor (often in the MAF/intake)."),
        "P0128" to Entry(R.string.vehicle_dtc_p0128_title, R.string.vehicle_dtc_p0128_fix, "Coolant below thermostat regulating temp", "Usually a stuck-open thermostat. Replace thermostat; verify coolant level and sensor."),
        "P0135" to Entry(R.string.vehicle_dtc_p0135_title, R.string.vehicle_dtc_p0135_fix, "O2 sensor heater (bank1 sensor1)", "Check the upstream O2 sensor heater circuit and fuse; replace the sensor if the heater is open."),
        "P0130" to Entry(R.string.vehicle_dtc_p0130_title, R.string.vehicle_dtc_p0130_fix, "O2 sensor circuit (bank1 sensor1)", "Inspect upstream O2 sensor and wiring; replace if lazy/faulty."),
        "P0171" to Entry(R.string.vehicle_dtc_p0171_title, R.string.vehicle_dtc_p0171_fix, "System too lean (bank1)", "Check for intake/vacuum leaks (common), dirty MAF, weak fuel pump/injectors."),
        "P0172" to Entry(R.string.vehicle_dtc_p0172_title, R.string.vehicle_dtc_p0172_fix, "System too rich (bank1)", "Check for leaking injectors, high fuel pressure, dirty MAF or faulty O2 sensor."),

        // --- Idle / throttle ---
        "P0505" to Entry(R.string.vehicle_dtc_p0505_title, R.string.vehicle_dtc_p0505_fix, "Idle air control system", "Clean throttle body; check for intake leaks and throttle motor operation."),
        "P2100" to Entry(R.string.vehicle_dtc_p2100_title, R.string.vehicle_dtc_p2100_fix, "Throttle actuator motor circuit", "Check throttle body motor and wiring; may need throttle body replacement.")
    )

    /** The table's English meaning for [code]; null for a code the table doesn't hold (no guess from its structure). */
    fun tableTitle(code: String): String? = TABLE[code.uppercase().trim()]?.title

    fun describe(code: String): DtcInfo {
        val c = code.uppercase().trim()
        TABLE[c]?.let { return DtcInfo(c, it.title, it.fix, it.titleRes, it.fixRes) }
        return fallback(c)
    }

    private fun fallback(code: String): DtcInfo {
        val fix = "No stored description. Scan for related codes, check the affected " +
            "circuit's sensor/wiring, and search \"$code Citroën C4 Picasso\" for " +
            "model-specific guidance."
        if (code.length < 2) {
            return DtcInfo(code, "Unknown code", fix, R.string.vehicle_dtc_unknown_code, R.string.vehicle_dtc_fallback_fix)
        }
        val system = when (code[0]) {
            'P' -> Part(R.string.vehicle_dtc_system_powertrain, "Powertrain")
            'C' -> Part(R.string.vehicle_dtc_system_chassis, "Chassis")
            'B' -> Part(R.string.vehicle_dtc_system_body, "Body")
            'U' -> Part(R.string.vehicle_dtc_system_network, "Network")
            else -> Part(R.string.vehicle_dtc_system_unknown, "Unknown")
        }
        val scope = if (code.getOrNull(1) == '1' || code.getOrNull(1) == '3') {
            Part(R.string.vehicle_dtc_scope_manufacturer, "manufacturer-specific")
        } else {
            Part(R.string.vehicle_dtc_scope_generic, "generic")
        }
        val area = when (code.getOrNull(2)) {
            '1', '2' -> Part(R.string.vehicle_dtc_area_fuel_air, "fuel & air metering")
            '3' -> Part(R.string.vehicle_dtc_area_ignition, "ignition / misfire")
            '4' -> Part(R.string.vehicle_dtc_area_emissions, "emissions / auxiliary")
            '5' -> Part(R.string.vehicle_dtc_area_idle, "speed / idle control")
            '6' -> Part(R.string.vehicle_dtc_area_computer, "computer / outputs")
            '7', '8', '9' -> Part(R.string.vehicle_dtc_area_transmission, "transmission")
            else -> Part(R.string.vehicle_dtc_area_system, "system")
        }
        return DtcInfo(
            code,
            "${system.english} ${scope.english} fault — ${area.english}",
            fix,
            R.string.vehicle_dtc_fallback_title,
            R.string.vehicle_dtc_fallback_fix,
            listOf(system.res, scope.res, area.res)
        )
    }
}
