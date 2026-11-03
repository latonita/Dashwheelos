package com.openauto.dash

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapDarkContrastTest {
    private fun lum(c: Int): Double {
        val r = (c shr 16 and 255) / 255.0
        val g = (c shr 8 and 255) / 255.0
        val b = (c and 255) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun line(id: String) = (MapDarkContrast.adjustmentFor(id) as MapDarkContrast.Adjustment.Line).color
    private fun fill(id: String) = (MapDarkContrast.adjustmentFor(id) as MapDarkContrast.Adjustment.Fill).color

    @Test fun unknownLayerIsUntouched() {
        assertNull(MapDarkContrast.adjustmentFor("my_custom_layer"))
        assertNull(MapDarkContrast.adjustmentFor(""))
    }

    @Test fun roadsStandOutFromBackgroundAndMajorIsBrighter() {
        val b = lum(fill("background"))
        assertTrue(lum(line("highway_minor")) > b + 0.2)
        assertTrue(lum(line("highway_major_inner")) > lum(line("highway_minor")))
        assertTrue(lum(line("highway_motorway_inner")) > lum(line("highway_major_inner")))
    }

    @Test fun nightMapStaysDark() {
        assertTrue(lum(fill("background")) < 0.12)
        assertTrue(lum(fill("water")) < 0.2)
    }

    @Test fun labelsAreLightAndLargeEnough() {
        val labels = MapDarkContrast.knownIds()
            .mapNotNull { MapDarkContrast.adjustmentFor(it) as? MapDarkContrast.Adjustment.Label }
        assertTrue(labels.size >= 10)
        for (l in labels) {
            assertTrue(lum(l.color) > 0.5)
            assertTrue(l.minSize >= 13f && l.maxSize >= l.minSize)
        }
        assertTrue(lum(MapDarkContrast.LABEL_HALO) < 0.01)
    }

    @Test fun casingIsDarkerThanItsRoad() {
        assertTrue(lum(line("highway_motorway_casing")) < lum(line("highway_motorway_inner")))
    }
}
