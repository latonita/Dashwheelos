package com.openauto.dash.diag

import android.content.Context

/** The "Diagnostics" setting. Saved by name. */
enum class DiagChoice { AUTO, GENERIC, JEEP_WK }

/** The diagnostics settings, in their own prefs file ("diag"). */
object DiagSettings {
    private const val PREFS = "diag"
    private const val KEY_PROFILE = "profile"
    private const val KEY_PERIODIC = "periodic_min"
    private const val KEY_PROBE_ALL = "probe_all"

    /** What "Background check" offers, in minutes; 0 is Off (the default). */
    val PERIODIC_OPTIONS = listOf(0, 5, 10, 15, 30)

    /** VIN world-manufacturer ids of the Jeep line the WK profile covers (Chrysler-built Jeeps). */
    private val JEEP_WMI = listOf("1J4", "1J8", "1C4")

    /**
     * The profile [choice] comes to for this car: never AUTO. Auto picks Jeep WK when the [vin] starts with a Jeep
     * WMI or the [make] starts with "jeep" (any case), else generic OBD-II.
     */
    fun resolve(choice: DiagChoice, vin: String, make: String): DiagChoice = when (choice) {
        DiagChoice.GENERIC, DiagChoice.JEEP_WK -> choice
        DiagChoice.AUTO -> {
            val v = vin.trim().uppercase()
            if (JEEP_WMI.any { v.startsWith(it) } || make.trim().lowercase().startsWith("jeep")) DiagChoice.JEEP_WK
            else DiagChoice.GENERIC
        }
    }

    fun choice(context: Context): DiagChoice =
        prefs(context).getString(KEY_PROFILE, null)?.let { n -> DiagChoice.entries.firstOrNull { it.name == n } } ?: DiagChoice.AUTO

    fun setChoice(context: Context, choice: DiagChoice) {
        prefs(context).edit().putString(KEY_PROFILE, choice.name).apply()
    }

    fun periodicMinutes(context: Context): Int =
        prefs(context).getInt(KEY_PERIODIC, 0).takeIf { it in PERIODIC_OPTIONS } ?: 0

    fun setPeriodicMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_PERIODIC, minutes.takeIf { it in PERIODIC_OPTIONS } ?: 0).apply()
    }

    /** Also ask the modules that never answered on the reference car (spec P5). Off by default. */
    fun probeAll(context: Context): Boolean = prefs(context).getBoolean(KEY_PROBE_ALL, false)

    fun setProbeAll(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROBE_ALL, on).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
