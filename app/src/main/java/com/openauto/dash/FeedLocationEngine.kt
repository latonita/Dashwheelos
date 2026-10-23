package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import org.maplibre.navigation.core.location.Location as NavLocation
import org.maplibre.navigation.core.location.engine.LocationEngine

/**
 * Where the navigation gets the car's position: the dashboard's own GPS feed.
 *
 * The navigation SDK ships two engines of its own and this app can use neither.
 * `GoogleLocationEngine` wants Play services, which these units do not have;
 * `MapLibreLocationEngine` reaches into the **map** SDK
 * (`org.maplibre.android.location.engine`), and the map SDK here is pinned back
 * to 10.x for OpenGL ES 2.0, where those classes are named
 * `com.mapbox.mapboxsdk.*` instead. Loading it would throw
 * `NoClassDefFoundError`.
 *
 * That turns out not to cost anything, because the engine is a small interface
 * and the dashboard already has the better answer to it: [LocationFeed], which
 * is the same feed the map tile and every speed readout use. One source of
 * position for the whole app, and the one class that binds the navigation SDK
 * to the map SDK is never loaded — which is what lets the two run at versions
 * four years apart (see the force() in app/build.gradle.kts).
 */
internal class FeedLocationEngine(context: Context) : LocationEngine {

    private val app = context.applicationContext

    /**
     * [request] is ignored on purpose. [LocationFeed] already runs the GPS at
     * one fix a second, which is both what the navigation asks for and the most
     * these units' receivers give; a second subscription at a different rate
     * would only be a second claim on the same provider.
     *
     * The feed is reference-counted, so the GPS is held for exactly as long as
     * something collects this flow and let go after — the navigation stopping
     * is enough to release it, with no teardown call of its own.
     */
    override fun listenToLocation(request: LocationEngine.Request): Flow<NavLocation> {
        var held = false
        return LocationFeed.location
            .filterNotNull()
            .map { it.toNavLocation() }
            .onStart { held = LocationFeed.acquire(app) }
            .onCompletion { if (held) LocationFeed.release() }
    }

    /** Whatever the dashboard last saw; null before the first fix. */
    override suspend fun getLastLocation(): NavLocation? =
        LocationFeed.location.value?.toNavLocation()
}

/**
 * An Android fix as the navigation's own model.
 *
 * Every optional field is sent only when the fix actually carries it: Android
 * returns 0.0 from `getSpeed()` and `getBearing()` on a fix that has neither,
 * and a standing car reporting a real bearing of north would have the route
 * snapping to the wrong heading.
 */
private fun android.location.Location.toNavLocation(): NavLocation = NavLocation(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    altitude = if (hasAltitude()) altitude else null,
    speedMetersPerSeconds = if (hasSpeed()) speed else null,
    bearing = if (hasBearing()) bearing else null,
    timeMilliseconds = time,
    provider = provider
)
