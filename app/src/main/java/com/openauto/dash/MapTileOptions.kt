package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The map tile's own options, shown in its panel while arranging. Kept in the
 * "map_tile" preferences file, so the setup backup carries it like the rest.
 * Only the tile reads it: the wallpaper and the cluster map stay flat.
 */
internal object MapTileOptions {
    const val PREFS = "map_tile"
    const val KEY_BUILDINGS_3D = "buildings_3d"

    /** Extruded buildings cost a weak GPU dearly (the Mali-450 of these units), so they are opt-in. */
    const val BUILDINGS_3D_DEFAULT = false

    private val _buildings3d = MutableStateFlow(BUILDINGS_3D_DEFAULT)
    val buildings3d: StateFlow<Boolean> = _buildings3d.asStateFlow()
    private var loaded = false

    /** The saved choice; nothing saved means [BUILDINGS_3D_DEFAULT]. */
    fun parse(saved: Boolean?): Boolean = saved ?: BUILDINGS_3D_DEFAULT

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _buildings3d.value = parse(if (prefs.contains(KEY_BUILDINGS_3D)) prefs.getBoolean(KEY_BUILDINGS_3D, BUILDINGS_3D_DEFAULT) else null)
    }

    fun setBuildings3d(context: Context, on: Boolean) {
        load(context)
        _buildings3d.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BUILDINGS_3D, on).apply()
    }
}
