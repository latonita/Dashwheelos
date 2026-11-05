package com.openauto.dash

import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource

/**
 * The ride trail on the map tile: the path of the current ride as a ribbon coloured by speed (blue crawling,
 * green, orange, red fast), thinner and fainter the farther behind the car it is. The data is the GeoJSON of
 * [RideRuns]: LineStrings with `s` (speed bucket) and `a` (age band). It lives in its own source and two
 * layers (a dark casing and the ribbon), under the first label and under the navigation route line.
 */
internal object MapRideTrail {
    const val SOURCE = "dash-ride-trail-source"
    const val CASING = "dash-ride-trail-casing"
    const val LAYER = "dash-ride-trail"
    private const val ROUTE_CASING = "dash-route-casing"

    private val SPEED_COLORS = intArrayOf(0xFF3B82F6.toInt(), 0xFF22C55E.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt())
    private val AGE_OPACITY = floatArrayOf(1f, 0.8f, 0.6f, 0.4f)
    private val AGE_WIDTH = floatArrayOf(5f, 4.5f, 4f, 3.5f)

    /** Adds the layers (or removes them when [on] is false) on the current style. Safe to call again. */
    fun apply(style: Style, on: Boolean) {
        runCatching {
            if (!on) {
                style.removeLayer(LAYER)
                style.removeLayer(CASING)
                style.removeSource(SOURCE)
                return
            }
            if (style.getLayer(LAYER) != null) return
            style.addSource(GeoJsonSource(SOURCE))
            // Under the route line when there is one, else under the first label.
            val anchor = style.getLayer(ROUTE_CASING)?.id
                ?: style.layers.firstOrNull { it is SymbolLayer }?.id
            val casing = LineLayer(CASING, SOURCE).withProperties(
                PropertyFactory.lineColor(0xFF10131A.toInt()),
                PropertyFactory.lineOpacity(byAge(AGE_OPACITY.map { it * 0.55f })),
                PropertyFactory.lineWidth(byAge(AGE_WIDTH.map { it + 2f })),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            val ribbon = LineLayer(LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor(bySpeed()),
                PropertyFactory.lineOpacity(byAge(AGE_OPACITY.toList())),
                PropertyFactory.lineWidth(byAge(AGE_WIDTH.toList())),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            if (anchor != null) {
                style.addLayerBelow(casing, anchor)
                style.addLayerBelow(ribbon, anchor)
            } else {
                style.addLayer(casing)
                style.addLayer(ribbon)
            }
        }
    }

    /** Hands the source the trail's GeoJSON (see [RideRuns.geoJson]); a no-op while the layers are not on the style. */
    fun update(style: Style, geoJson: String) {
        runCatching { style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(geoJson) }
    }

    private fun bySpeed(): Expression = Expression.match(
        Expression.get("s"), Expression.color(SPEED_COLORS[0]),
        *SPEED_COLORS.drop(1).mapIndexed { i, c -> Expression.stop(i + 1, Expression.color(c)) }.toTypedArray()
    )

    private fun byAge(values: List<Float>): Expression = Expression.match(
        Expression.get("a"), Expression.literal(values[0]),
        *values.drop(1).mapIndexed { i, v -> Expression.stop(i + 1, Expression.literal(v)) }.toTypedArray()
    )
}
