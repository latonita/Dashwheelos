package com.openauto.dash

import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.FillExtrusionLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.VectorSource

/**
 * Extruded 3D buildings on the vector basemap, for the map tile's "3D
 * buildings" option. The geometry is the OpenMapTiles `building` source-layer
 * with `render_height` / `render_min_height`. The camera already looks ahead
 * at a tilt (45 degrees on the tile), so nothing else changes with it.
 */
internal object MapBuildings3d {
    const val LAYER_ID = "3d-buildings"
    private const val LIGHT = 0xFFD5D8DE.toInt()
    // A little lighter than the dark style's flat building fill (#23252B, MapDarkContrast), so the walls read against it.
    private const val DARK = 0xFF2F3239.toInt()

    /** The extrusion's colour for the light or dark style. */
    fun color(light: Boolean): Int = if (light) LIGHT else DARK

    /** One style layer as far as the order goes: a label, or a flat fill or line, with its source-layer. */
    class Slot(val id: String, val isSymbol: Boolean, val sourceLayer: String? = null)

    /**
     * Where the buildings go: under the first label that comes after the last
     * flat fill or line (the roads and rails), so nothing flat is drawn over
     * the walls, while the street names stay on top. Boundaries are ignored:
     * they come after the labels. The style's first symbol layer is a water
     * name, ahead of every road, so "below the first symbol" put the walls
     * under the roads. Null puts them on top.
     */
    fun firstLabelId(layers: List<Slot>): String? {
        val lastFlat = layers.indexOfLast { !it.isSymbol && it.sourceLayer != "boundary" }
        return layers.drop(lastFlat + 1).firstOrNull { it.isSymbol }?.id
    }

    /** Adds the layer (or removes it when [on] is false) on the current style. Safe to call again. */
    fun apply(style: Style, on: Boolean, light: Boolean) {
        runCatching {
            if (!on) {
                style.removeLayer(LAYER_ID)
                return
            }
            if (style.getLayer(LAYER_ID) != null) return
            val sourceId = style.sources.firstOrNull { it is VectorSource }?.id ?: return
            val layer = FillExtrusionLayer(LAYER_ID, sourceId).apply {
                sourceLayer = "building"
                setProperties(
                    PropertyFactory.fillExtrusionColor(color(light)),
                    PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                    PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                    PropertyFactory.fillExtrusionOpacity(0.85f)
                )
                setMinZoom(14f)
            }
            val labelId = firstLabelId(style.layers.map {
                Slot(
                    it.id, it is SymbolLayer,
                    (it as? com.mapbox.mapboxsdk.style.layers.LineLayer)?.sourceLayer
                        ?: (it as? com.mapbox.mapboxsdk.style.layers.FillLayer)?.sourceLayer
                )
            })
            if (labelId != null) style.addLayerBelow(layer, labelId) else style.addLayer(layer)
        }
    }
}
