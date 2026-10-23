package com.openauto.dash

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import org.maplibre.navigation.core.models.DirectionsRoute

/**
 * The route, drawn on the map.
 *
 * This replaces the navigation SDK's own `NavigationMapRoute`. That class is
 * the one thing in the navigation UI module that reaches deep into the map SDK
 * — it builds layers, sources, expressions and camera positions of its own, 21
 * map classes in all — and the map SDK here is pinned back to 10.x for OpenGL
 * ES 2.0, where every one of those is under a different package name. It could
 * not load. Everything else the app uses from that module is `ManeuverView`,
 * which draws a turn arrow on a plain Canvas and touches no map class at all,
 * so this one file is the whole price of running the two SDKs apart.
 *
 * What it draws is a casing and a line, under the first symbol layer so that
 * street names and the route do not fight each other.
 *
 * What it does not draw, and `NavigationMapRoute` did: the travelled part of
 * the route in a dimmer colour as the car eats into it. The line is the whole
 * route from start to destination for as long as the guidance runs. The car's
 * own position is on the map anyway, as the puck, so nothing is lost but the
 * shading.
 */
internal class RouteLine(private val style: Style, accent: Color) {

    private val source = GeoJsonSource(SOURCE)

    init {
        runCatching {
            style.addSource(source)
            // Under the first symbol layer: labels stay readable over the route.
            // If the style has no symbol layer at all, the route goes on top,
            // which is the right answer for a style that has nothing to cover.
            val firstSymbol = style.layers.firstOrNull { it is com.mapbox.mapboxsdk.style.layers.SymbolLayer }?.id
            val casing = LineLayer(CASING, SOURCE).withProperties(
                PropertyFactory.lineColor(casingColor(accent)),
                PropertyFactory.lineWidth(CASING_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            val line = LineLayer(LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor(lineColor(accent)),
                PropertyFactory.lineWidth(LINE_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            if (firstSymbol != null) {
                style.addLayerBelow(casing, firstSymbol)
                style.addLayerBelow(line, firstSymbol)
            } else {
                style.addLayer(casing)
                style.addLayer(line)
            }
        }
    }

    /** Draw one route, replacing whatever was drawn before. */
    fun show(route: DirectionsRoute) {
        val points = route.points()
        if (points.size < 2) { clear(); return }
        runCatching { source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points))) }
    }

    /**
     * Draw the first of [routes]. The alternatives a router offers are not
     * drawn: this map proposes one route and the driver either starts it or
     * does not, so a second line would only be something to mistake for the
     * first at a glance from the driver's seat.
     */
    fun showAll(routes: List<DirectionsRoute>) {
        routes.firstOrNull()?.let(::show) ?: clear()
    }

    /** Recolour both layers for a new [accent] (the look changed); a no-op once the style is gone. */
    fun tint(accent: Color) {
        runCatching {
            style.getLayer(CASING)?.setProperties(PropertyFactory.lineColor(casingColor(accent)))
            style.getLayer(LAYER)?.setProperties(PropertyFactory.lineColor(lineColor(accent)))
        }
    }

    /** Take the route off the map, leaving the layers in place for the next one. */
    fun clear() {
        runCatching { source.setGeoJson(FeatureCollectionEmpty) }
    }

    private companion object {
        const val SOURCE = "dash-route-source"
        const val LAYER = "dash-route"
        const val CASING = "dash-route-casing"

        /**
         * The dashboard's accent is "your route", with a much darker edge of the
         * same hue to lift it off the map: the accent is bright on the night map
         * and deep on the day one, and the dark edge separates it from both.
         */
        fun lineColor(accent: Color): Int = accent.copy(alpha = 1f).toArgb()
        fun casingColor(accent: Color): Int = lerp(accent.copy(alpha = 1f), Color.Black, 0.55f).toArgb()
        const val LINE_WIDTH = 6f
        const val CASING_WIDTH = 10f

        /** An empty line: what "no route" looks like to the source. */
        val FeatureCollectionEmpty: Feature =
            Feature.fromGeometry(LineString.fromLngLats(emptyList<Point>()))
    }
}

/**
 * The route's shape as points.
 *
 * The geometry comes back encoded, at the precision asked for in
 * [RouteOptions.geometries] where the route was requested — "polyline6", so six
 * decimal places. The app decodes it with its own [Polyline], the same one the
 * second screen's route uses, rather than pulling in a geometry library for it.
 */
private fun DirectionsRoute.points(): List<Point> {
    val encoded = geometry ?: return emptyList()
    return Polyline.decode(encoded, precision = 6).map { (lat, lon) -> Point.fromLngLat(lon, lat) }
}
