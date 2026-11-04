package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Some cars and TPMS sources report a tyre under the wrong wheel. The driver says which wheel a reading really
 * belongs to (long-tap a wheel on the Tyres tile) and the two swap. The fix is made on the read side
 * (vehicle/Body.kt), per bus source, so the bus keeps what the source sent.
 */

/** The four wheels a map can move, in the order they are stored. SPARE is never remapped. */
private val WHEELS = listOf(TyrePos.FRONT_LEFT, TyrePos.FRONT_RIGHT, TyrePos.REAR_LEFT, TyrePos.REAR_RIGHT)
private val CODES = listOf("FL", "FR", "RL", "RR")

/** Short name of a wheel ("FL"), for the menu and the stored text; the spare has none. */
internal val TyrePos.code: String? get() = WHEELS.indexOf(this).takeIf { it >= 0 }?.let { CODES[it] }

/** Shown position -> the position its data really came from. Always a permutation of the four wheels. */
data class WheelMap(val from: Map<TyrePos, TyrePos>) {
    val isIdentity: Boolean get() = WHEELS.all { from[it] == it }

    /** Moves each tyre to the position it is shown at; a wheel with no data stays empty, the spare passes through. */
    fun apply(raw: Map<TyrePos, Tyre>): Map<TyrePos, Tyre> {
        if (isIdentity) return raw
        val out = LinkedHashMap<TyrePos, Tyre>()
        for ((pos, tyre) in raw) if (pos !in WHEELS) out[pos] = tyre
        for (shown in WHEELS) raw[from[shown] ?: shown]?.let { out[shown] = it }
        return out
    }

    /** Exchanges what [a] and [b] are shown from; a spare, or a wheel with itself, changes nothing. */
    fun swap(a: TyrePos, b: TyrePos): WheelMap {
        if (a !in WHEELS || b !in WHEELS || a == b) return this
        return WheelMap(from + (a to (from[b] ?: b)) + (b to (from[a] ?: a)))
    }

    /** "RR,FR,RL,FL": the raw wheel each of FL, FR, RL, RR is shown from. */
    fun format(): String = WHEELS.joinToString(",") { (from[it] ?: it).code!! }

    companion object {
        val IDENTITY = WheelMap(WHEELS.associateWith { it })

        /** The stored text; anything that is not a permutation of the four wheels is the identity. */
        fun parse(text: String?): WheelMap {
            val parts = text?.split(',')?.map { it.trim().uppercase() } ?: return IDENTITY
            if (parts.size != 4) return IDENTITY
            val raw = parts.map { c -> WHEELS.getOrNull(CODES.indexOf(c)) ?: return IDENTITY }
            if (raw.toSet().size != 4) return IDENTITY
            return WheelMap(WHEELS.zip(raw).toMap())
        }
    }
}

/**
 * The driver's wheel maps, one per bus source id ("tpms", "tpms.usb", the canbox's...): a car swapped from one TPMS
 * to another must not keep the first one's fix. The bus can say which source a value came from
 * (`Vehicle.bus.sample(K.TYRES).source`), so there is no global map. Prefs `tyre_wheel_map`, a key per source id.
 */
internal object WheelMaps {
    private const val PREFS = "tyre_wheel_map"

    private val _maps = MutableStateFlow<Map<String, WheelMap>>(emptyMap())
    /** Only the maps that are not the identity. */
    val maps: StateFlow<Map<String, WheelMap>> = _maps.asStateFlow()
    private var loaded = false

    fun of(maps: Map<String, WheelMap>, sourceId: String?): WheelMap = sourceId?.let { maps[it] } ?: WheelMap.IDENTITY

    /** [raw] as the driver's map for [sourceId] says. */
    fun apply(maps: Map<String, WheelMap>, sourceId: String?, raw: Map<TyrePos, Tyre>): Map<TyrePos, Tyre> =
        of(maps, sourceId).apply(raw)

    /** What the prefs hold: a map per key, the identity ones dropped. */
    fun parseAll(saved: Map<String, *>): Map<String, WheelMap> =
        saved.mapNotNull { (k, v) -> (v as? String)?.let { WheelMap.parse(it) }?.takeIf { !it.isIdentity }?.let { k to it } }.toMap()

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _maps.value = parseAll(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all)
    }

    fun set(context: Context, sourceId: String, map: WheelMap) {
        load(context)
        _maps.value = if (map.isIdentity) _maps.value - sourceId else _maps.value + (sourceId to map)
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (map.isIdentity) edit.remove(sourceId) else edit.putString(sourceId, map.format())
        edit.apply()
    }

    fun reset(context: Context, sourceId: String) = set(context, sourceId, WheelMap.IDENTITY)
}
