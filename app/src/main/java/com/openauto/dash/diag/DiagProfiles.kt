package com.openauto.dash.diag

import org.json.JSONObject
import java.io.InputStream

/** Profile loading. Profiles are data: JSON under assets/diag/profiles (see tools/diag/make_jeep_wk.py). */
object DiagProfiles {
    const val JEEP_WK_ASSET = "diag/profiles/jeep-wk.json"
    const val JEEP_WK_DTC_ASSET = "diag/profiles/jeep-wk-dtc.csv"

    /** Today's behaviour: one pseudo-module read by mode 03, cleared by mode 04, on the functional ids. */
    val GenericObd2 = DiagProfile(
        id = "generic-obd2",
        label = "Generic OBD-II",
        modules = listOf(
            DiagModule(
                id = "ENGINE", name = "Engine (OBD-II)", tx = 0x7DF, rx = 0x7E8, protocol = DiagProtocol.OBD2,
                readRequest = "03", clearSteps = listOf("04"), clearRisk = ClearRisk.NORMAL, probeOnly = false,
            ),
        ),
    )

    fun parse(json: String): DiagProfile {
        val o = JSONObject(json)
        val arr = o.getJSONArray("modules")
        val modules = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val steps = m.optJSONArray("clearSteps")
            DiagModule(
                id = m.getString("id"),
                name = m.getString("name"),
                tx = parseId(m.get("tx")),
                rx = parseId(m.get("rx")),
                protocol = DiagProtocol.valueOf(m.getString("protocol")),
                readRequest = m.getString("readRequest"),
                clearSteps = if (steps == null) emptyList() else (0 until steps.length()).map { steps.getString(it) },
                clearRisk = ClearRisk.valueOf(m.optString("clearRisk", "NORMAL")),
                probeOnly = m.optBoolean("probeOnly", false),
            )
        }
        return DiagProfile(o.getString("id"), o.getString("label"), modules)
    }

    fun parse(input: InputStream): DiagProfile = parse(input.bufferedReader(Charsets.UTF_8).use { it.readText() })

    /** [open] opens an asset by its path, e.g. { context.assets.open(it) }. */
    fun loadJeepWk(open: (String) -> InputStream): Pair<DiagProfile, DtcDescriptions> =
        open(JEEP_WK_ASSET).use { parse(it) } to open(JEEP_WK_DTC_ASSET).use { DtcDescriptions.parse(it) }

    private fun parseId(v: Any): Int =
        if (v is Int) v else v.toString().removePrefix("0x").removePrefix("0X").toInt(16)
}
