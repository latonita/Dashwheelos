package com.openauto.dash

import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.BackgroundLayer
import com.mapbox.mapboxsdk.style.layers.FillLayer
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.SymbolLayer

/**
 * OpenFreeMap's "dark" style is a deliberately low-contrast look: roads are
 * #181818 on a #0c0c0c ground and labels are 37-40% grey. That is fine on a
 * phone and unreadable on a car screen at 160 dpi, so after the style loads we
 * lift its road, label and water colours. The style itself is valid for
 * MapLibre 10 (no unsupported expressions); this is a pure colour problem.
 *
 * [adjustmentFor] is the pure id-to-colour mapping; [apply] pushes it onto a
 * loaded style. Unknown layer ids map to null and are left alone, so a custom
 * style is harmless, and [apply] is only called for the default dark style.
 */
object MapDarkContrast {
    sealed class Adjustment {
        data class Fill(val color: Int) : Adjustment()
        data class Line(val color: Int) : Adjustment()

        /** Light text with a dark halo; [minSize] at z10 up to [maxSize] at z16. */
        data class Label(val color: Int, val minSize: Float, val maxSize: Float) : Adjustment()
    }

    const val LABEL_HALO = 0xFF000000.toInt()
    const val LABEL_HALO_WIDTH = 1.8f

    private const val LABEL = 0xFFF2F2F5.toInt()
    private const val LABEL_SOFT = 0xFFD6D8DE.toInt()
    private const val WATER_LABEL = 0xFF9CC4EA.toInt()

    private val roadCasing = Adjustment.Line(0xFF050506.toInt())

    private val byId: Map<String, Adjustment> = mapOf(
        "background" to Adjustment.Fill(0xFF14161A.toInt()),
        "water" to Adjustment.Fill(0xFF1B3550.toInt()),
        "waterway" to Adjustment.Line(0xFF2A4C70.toInt()),
        "landuse_park" to Adjustment.Fill(0xFF1D2B22.toInt()),
        "landcover_wood" to Adjustment.Fill(0xFF1D2B22.toInt()),
        "building" to Adjustment.Fill(0xFF23252B.toInt()),
        // roads: minor mid-grey, major brighter, motorways brightest
        "highway_path" to Adjustment.Line(0xFF7A7D86.toInt()),
        "highway_minor" to Adjustment.Line(0xFF83868F.toInt()),
        "highway_major_casing" to roadCasing,
        "highway_major_inner" to Adjustment.Line(0xFFB4B7C0.toInt()),
        "highway_major_subtle" to Adjustment.Line(0xFF8F929B.toInt()),
        "highway_motorway_casing" to roadCasing,
        "highway_motorway_inner" to Adjustment.Line(0xFFE6E8EE.toInt()),
        "highway_motorway_subtle" to Adjustment.Line(0xFF8F929B.toInt()),
        "railway" to Adjustment.Line(0xFF55585F.toInt()),
        "railway_minor" to Adjustment.Line(0xFF55585F.toInt()),
        "railway_transit" to Adjustment.Line(0xFF55585F.toInt()),
        // labels
        "highway_name_motorway" to Adjustment.Label(LABEL, 13f, 16f),
        "highway_name_other" to Adjustment.Label(LABEL, 13f, 16f),
        "water_name" to Adjustment.Label(WATER_LABEL, 13f, 16f),
        "place_other" to Adjustment.Label(LABEL_SOFT, 14f, 17f),
        "place_suburb" to Adjustment.Label(LABEL_SOFT, 14f, 17f),
        "place_village" to Adjustment.Label(LABEL, 14f, 18f),
        "place_town" to Adjustment.Label(LABEL, 15f, 19f),
        "place_city" to Adjustment.Label(LABEL, 16f, 21f),
        "place_city_large" to Adjustment.Label(LABEL, 18f, 24f),
        "place_state" to Adjustment.Label(LABEL_SOFT, 13f, 16f),
        "place_country_other" to Adjustment.Label(LABEL_SOFT, 13f, 15f),
        "place_country_minor" to Adjustment.Label(LABEL_SOFT, 14f, 16f),
        "place_country_major" to Adjustment.Label(LABEL, 15f, 18f),
    )

    /** The adjustment for a layer id, or null when the layer is not one we know. */
    fun adjustmentFor(layerId: String): Adjustment? = byId[layerId]

    fun knownIds(): Set<String> = byId.keys

    /** Applies the pass to a loaded style. Missing layers and type mismatches are skipped. */
    fun apply(style: Style) {
        for ((id, adj) in byId) {
            val layer = style.getLayer(id) ?: continue
            try {
                when {
                    adj is Adjustment.Fill && layer is BackgroundLayer ->
                        layer.setProperties(PropertyFactory.backgroundColor(adj.color))
                    adj is Adjustment.Fill && layer is FillLayer ->
                        layer.setProperties(
                            PropertyFactory.fillColor(adj.color),
                            PropertyFactory.fillOpacity(1f),
                        )
                    adj is Adjustment.Line && layer is LineLayer ->
                        layer.setProperties(
                            PropertyFactory.lineColor(adj.color),
                            PropertyFactory.lineOpacity(1f),
                        )
                    adj is Adjustment.Label && layer is SymbolLayer ->
                        layer.setProperties(
                            PropertyFactory.textColor(adj.color),
                            PropertyFactory.textHaloColor(LABEL_HALO),
                            PropertyFactory.textHaloWidth(LABEL_HALO_WIDTH),
                            PropertyFactory.textHaloBlur(0f),
                            PropertyFactory.textSize(
                                Expression.interpolate(
                                    Expression.linear(), Expression.zoom(),
                                    Expression.stop(10, adj.minSize),
                                    Expression.stop(16, adj.maxSize),
                                )
                            ),
                        )
                }
            } catch (_: Throwable) {
                // A style that rejects one property must not lose the rest of the map.
            }
        }
    }
}
