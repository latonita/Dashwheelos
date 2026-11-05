package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map tile's 3D buildings option: off by default, how it is saved and backed up, and the layer's pure parts. */
class MapTileOptionsTest {

    @Test
    fun offByDefault() {
        assertFalse(MapTileOptions.BUILDINGS_3D_DEFAULT)
        assertFalse(MapTileOptions.buildings3d.value)
        assertFalse(MapTileOptions.parse(null))
    }

    @Test
    fun aSavedChoiceWins() {
        assertTrue(MapTileOptions.parse(true))
        assertFalse(MapTileOptions.parse(false))
    }

    @Test
    fun theChoiceSurvivesABackupRoundTrip() {
        val files = mapOf(MapTileOptions.PREFS to mapOf<String, Any?>(MapTileOptions.KEY_BUILDINGS_3D to true))
        val back = SetupBackup.decode(SetupBackup.encode(files, "1.0.0").toString())
        assertEquals(true, back[MapTileOptions.PREFS]?.get(MapTileOptions.KEY_BUILDINGS_3D))
    }

    @Test
    fun theFileIsNotLeftOutOfTheBackup() {
        // A Boolean in a preferences file is saved by type; nothing skips this file.
        val prefsFiles = SetupBackup::class.java.getDeclaredField("SKIP_FILES").apply { isAccessible = true }.get(SetupBackup)
        assertFalse((prefsFiles as Set<*>).contains(MapTileOptions.PREFS))
    }

    @Test
    fun darkWallsDifferFromLightAndFromTheFlatFill() {
        assertNotEquals(MapBuildings3d.color(true), MapBuildings3d.color(false))
        assertNotEquals(0xFF23252B.toInt(), MapBuildings3d.color(false))
    }

    @Test
    fun theLayerGoesAfterTheRoadsAndUnderTheNames() {
        fun flat(id: String, src: String? = null) = MapBuildings3d.Slot(id, false, src)
        fun label(id: String) = MapBuildings3d.Slot(id, true)
        // The OpenFreeMap order: a water name comes before the roads.
        val style = listOf(
            flat("water"), label("water_name"), flat("building", "building"), flat("highway_minor", "transportation"),
            label("road_oneway"), flat("railway", "transportation"), label("highway_name_other"),
            flat("boundary_state", "boundary"), label("place_city")
        )
        assertEquals("highway_name_other", MapBuildings3d.firstLabelId(style))
        assertNull(MapBuildings3d.firstLabelId(listOf(flat("bg"), flat("roads", "transportation"))))
    }
}
