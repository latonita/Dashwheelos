package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.LocationComponentOptions
import com.mapbox.mapboxsdk.location.OnCameraTrackingChangedListener
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.MapboxMapOptions
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.Style
import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.RouteOptions
import java.util.Locale

// Free, no-key services by default: OpenFreeMap's dark / positron styles, Nominatim
// geocoding ([PlaceSearch]) and the public Valhalla router; the map style and the
// router can be pointed at the driver's own servers ([MapServers]).
private const val USER_AGENT = "OpenAutoDash/1.0 (car launcher)"

// The dashboard's accent, not a fixed blue: the map's controls change with the look.
private val Accent: Color get() = DashColors.Accent
// Panels over the map: the theme's card as a scrim (nearly opaque, so the map shows only faintly), with its own text colours.
private val OverlayBg: Color get() = DashColors.Card.copy(alpha = 0.92f)
// Accent for marks on those panels (icons): the ink that reads against the theme's page.
private val AccentMark: Color get() = DashColors.AccentInk

/**
 * A free, open-source **GPS navigator** built on MapLibre GL:
 *  - map + your location (OpenFreeMap styles by default, no token/account/card),
 *  - type a destination (Nominatim geocoding) or tap the map,
 *  - home, work and the last destinations one tap away ([PlacesStore]),
 *  - route computed by a Valhalla server (the free public one by default), drawn on the map with ETA,
 *  - **Start** guides there on this map ([InAppNav]): next turn, voice, a
 *    new route when the car leaves it; or hands the destination to Google
 *    Maps / Waze ([NavHandoff]) when there is no route of our own.
 *
 * As a [wallpaper] (the Canvas theme's page) it is only the map and the
 * guided route: no search, no route panel, no gestures, a lower and steeper camera with the car in
 * the lower part of the screen, and at most [WALLPAPER_FPS] frames a second.
 */
@Composable
fun MapLibrePanel(modifier: Modifier = Modifier, wallpaper: Boolean = false) {
    // Typing a destination waits until the car has stopped (the drive lock).
    val moving = LocalDriveLock.current.moving
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasLocation by remember { mutableStateOf(hasLocationPerm(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasLocation = result.values.any { it } }

    LaunchedEffect(Unit) {
        if (!hasLocation) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val mapView = remember {
        Mapbox.getInstance(context)
        val options = MapboxMapOptions.createFromAttributes(context, null).textureMode(true)
        MapView(context, options).also { if (wallpaper) it.setMaximumFps(WALLPAPER_FPS) }
    }

    var mapRef by remember { mutableStateOf<MapboxMap?>(null) }
    // On a page the pagers keep composed beside the one on screen nobody sees
    // the map, yet a camera following the car redrew it every frame: it runs
    // at [HIDDEN_FPS] there, and back at its own pace (uncapped, or the
    // wallpaper's cap) as soon as the page starts sliding in ([LocalPageActive]).
    // Paused or stopped it is not: a tile map put to sleep came back black.
    val pageActive = LocalPageActive.current
    LaunchedEffect(pageActive) {
        mapView.setMaximumFps(if (!pageActive) HIDDEN_FPS else if (wallpaper) WALLPAPER_FPS else TILE_FPS)
        if (pageActive) mapRef?.triggerRepaint()
    }

    var navRoute by remember { mutableStateOf<RouteLine?>(null) }
    // Map listeners are set once; the route line is rebuilt per style (below).
    var listenersSet by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf<DirectionsRoute?>(null) }
    var destination by remember { mutableStateOf<Point?>(null) }
    // What the destination is called: the address typed, a saved place's name; null for a spot picked on the map.
    var destinationName by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // The destination panel: a magnifier on the map until it is tapped.
    var searchOpen by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val hostView = LocalView.current
    val focusManager = LocalFocusManager.current

    // When the panel last opened (uptime ms): touches on the map in the first
    // moments after are the tail of the magnifier tap or the layout settling,
    // not the driver reaching for the map.
    val searchOpenedAt = remember { longArrayOf(0L) }
    val searchFocusRequester = remember { FocusRequester() }

    /** The one way the panel opens. */
    fun openSearch(reason: String) {
        Log.d(SEARCH_TAG, "open ($reason) was=$searchOpen")
        searchOpenedAt[0] = android.os.SystemClock.uptimeMillis()
        searchOpen = true
    }

    /** The one way the panel closes: panel away, keyboard with it, the Android focus back on the map. */
    fun closeSearch(reason: String) {
        Log.d(SEARCH_TAG, "close ($reason) was=$searchOpen")
        searchOpen = false
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        mapView.isFocusable = true
        mapView.isFocusableInTouchMode = true
        mapView.requestFocus()
    }

    fun clearRoute() {
        route = null; destination = null; destinationName = null; info = null; error = null
        navRoute?.clear()
        mapRef?.markers?.forEach { mapRef?.removeMarker(it) }
    }

    fun routeTo(dest: Point) {
        // Destination is set regardless of GPS so "Start" (Google Maps handoff)
        // always works; the in-app route preview below just needs our own fix.
        destination = dest
        info = null
        val map = mapRef
        // The component throws until it's switched on (style loaded, location
        // allowed): a destination picked before that is "GPS not ready", not a crash.
        @SuppressLint("MissingPermission")
        val loc = runCatching { map?.locationComponent?.lastKnownLocation }.getOrNull()
        if (map == null || loc == null) {
            loading = false
            error = context.getString(R.string.info_map_gps_not_ready)
            return
        }
        val origin = Point.fromLngLat(loc.longitude, loc.latitude)
        error = null; loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { valhallaRoute(origin, dest, InAppNav.locale(context).language) }
            }
            loading = false
            result.onSuccess { resp ->
                val first = resp.routes.firstOrNull()
                if (first == null) { error = context.getString(R.string.info_map_no_route); return@onSuccess }
                route = first
                navRoute?.showAll(resp.routes)
                info = formatEta(context, first.distance, first.duration)
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(dest.latitude(), dest.longitude()), 13.0)
                )
            }.onFailure {
                error = it.message?.takeIf { m -> m.isNotBlank() }
                    ?.let { m -> context.getString(R.string.info_map_routing_failed_detail, m) }
                    ?: context.getString(R.string.info_map_routing_failed)
            }
        }
    }

    fun searchAndRoute() {
        val q = query.trim()
        if (q.isEmpty() || loading) return
        error = null; info = null; loading = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { PlaceSearch.find(q) } }
            val place = found.getOrNull()
            if (place == null) {
                loading = false
                // No answer at all is the connection, not the address.
                error = context.getString(if (found.isFailure) R.string.places_offline else R.string.info_map_address_not_found)
                return@launch
            }
            val ll = LatLng(place.lat, place.lng)
            mapRef?.addMarker(MarkerOptions().position(ll))
            mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 14.0))
            loading = false
            routeTo(Point.fromLngLat(place.lng, place.lat))
            destinationName = place.name
            closeSearch("search result routed")
        }
    }

    /** A saved place or a last destination, tapped: its route, ready to start. */
    fun routeToPlace(place: Place) {
        clearRoute()
        closeSearch("saved place picked")
        val ll = LatLng(place.lat, place.lng)
        mapRef?.addMarker(MarkerOptions().position(ll))
        routeTo(Point.fromLngLat(place.lng, place.lat))
        destinationName = place.name
    }

    /**
     * Start: guidance on this map along the route shown, or in the navigation
     * app ([inOtherApp], or no route of our own yet), and the destination
     * kept among the last ones.
     */
    fun startGuidance(dest: Point, inOtherApp: Boolean = false) {
        val lat = dest.latitude()
        val lng = dest.longitude()
        val name = destinationName
        val shown = route
        // A route the guidance refuses goes to the navigation app instead.
        if (!inOtherApp && shown != null && hasLocationPerm(context) && InAppNav.start(context, shown, dest, name)) {
            // Back to the car straight away, not after the preview's pause.
            mapRef?.let { followVehicle(it.locationComponent, mapView, wallpaper, userZooms[it]) }
        } else if (!NavHandoff.start(context, lat, lng, name.orEmpty())) {
            error = context.getString(R.string.places_no_nav_app)
            return
        }
        scope.launch {
            // A spot picked on the map is kept under its address.
            val known = name ?: withContext(Dispatchers.IO) { PlaceSearch.nameOf(lat, lng) } ?: PlaceSearch.coordinates(lat, lng)
            PlacesStore.visited(context, Place(known, lat, lng))
        }
    }

    DisposableEffect(lifecycleOwner) {
        mapView.onCreate(null)
        // The observer is replayed up to the lifecycle's current state when
        // added, so it alone forwards start / resume: calling them here as well
        // started the map twice. What it forwarded is tracked, so disposing
        // only undoes what was done.
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { started = true; mapView.onStart() }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { resumed = true; mapView.onResume() }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { resumed = false; mapView.onPause() }
                Lifecycle.Event.ON_STOP -> if (started) { started = false; mapView.onStop() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Low memory: the map drops its tile caches.
        val memory = object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) mapView.onLowMemory()
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = mapView.onLowMemory()
        }
        context.applicationContext.registerComponentCallbacks(memory)
        onDispose {
            context.applicationContext.unregisterComponentCallbacks(memory)
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (resumed) runCatching { mapView.onPause() }
            if (started) runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }

    // Map setup runs exactly once. It used to live in AndroidView's update
    // block, which re-runs on every recomposition (each search keystroke), so
    // the style reloaded and a new click listener stacked up every time.
    var styleReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        mapView.getMapAsync { map ->
            // No MapLibre wordmark on the dashboard. The attribution (i)
            // stays: OpenStreetMap's licence and the tile providers ask for it.
            map.uiSettings.isLogoEnabled = false
            // The compass keeps to the top right corner: the search is a magnifier
            // at the top left now. Margins are pixels.
            map.uiSettings.isCompassEnabled = !wallpaper
            map.uiSettings.compassGravity = android.view.Gravity.TOP or android.view.Gravity.END
            val d = context.resources.displayMetrics.density
            map.uiSettings.setCompassMargins(0, (12 * d).toInt(), (12 * d).toInt(), 0)
            // Under the dashboard nothing reaches the map by touch anyway.
            if (wallpaper) map.uiSettings.setAllGesturesEnabled(false)
            mapRef = map
        }
    }
    // The basemap follows the dashboard's dark or light version. Only the
    // style swaps on a change; the route line and click listener are set up
    // on the first load, and the location puck carries over by itself.
    val lightMap = DashColors.Light
    // The listener below is registered once; it reads the lock's latest state.
    val movingNow = rememberUpdatedState(moving)
    // Whether the destination box holds the cursor, so a touch of the map can
    // put it down again — and only then, so every other touch is left alone.
    val searchFocused = remember { mutableStateOf(false) }
    MapServers.load(context)
    val servers by MapServers.current.collectAsState()
    val styleUrl = if (lightMap) servers.styleLight else servers.styleDark
    LaunchedEffect(mapRef, styleUrl) {
        val map = mapRef ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
            // The stock dark style is too low-contrast for a car screen; lift
            // it, but only for the built-in default (a custom URL is the
            // user's own look).
            if (!lightMap && styleUrl == MapServers.DEFAULT_STYLE_DARK) MapDarkContrast.apply(style)
            // The route's source and layers belong to the style, and switching
            // between the day and night styles builds a new one — so the line
            // has to be made again each time or it disappears with the old
            // style, in silence, leaving a guided route with nothing drawn.
            navRoute = RouteLine(style, DashColors.Accent)
            // Redrawn too: the new line starts empty, and the route being
            // followed is not interrupted by the driver changing the map's look.
            InAppNav.guidance.value?.route?.let { navRoute?.show(it) }
            if (!listenersSet) {
                listenersSet = true
                // Touching the map puts the keyboard away.
                //
                // It has to come from the map's own callbacks. The map is a real
                // Android View, so a touch that lands on it is dispatched by the
                // Android view hierarchy and never passes through Compose's
                // pointer input — no modifier on the AndroidView can see it.
                // Until this, the keyboard covered the bottom half of the screen,
                // the Start button with it, and nothing would put it down: the
                // box kept the cursor, the map swallowed the touch, and these
                // units' keyboards carry no hide key of their own.
                //
                // A tap and a drag are both a dismissal, so both are taken; false
                // leaves the tap to the map, which has its own use for it.
                //
                // Clearing the focus is not enough on its own: with the
                // destination box the only thing in the panel that can hold
                // focus, Compose hands it straight back — measured at 30ms —
                // and the keyboard returns. The focus has to go *somewhere*,
                // and the map is the right somewhere: it is a real View and
                // focusable in touch mode, so the driver's touch lands where
                // they aimed it. The same hand-off at startup stops the box
                // from grabbing the cursor before anyone has asked to type.
                // Only a real touch of the map counts, and only once the panel
                // has been open a moment. A camera move the app makes itself
                // (tracking each fix, the look-ahead padding re-applied when the
                // keyboard resizes the window) is not the driver.
                fun dismissSearch(why: String) {
                    if (!searchOpen) return
                    val age = android.os.SystemClock.uptimeMillis() - searchOpenedAt[0]
                    if (age < SEARCH_GRACE_MS) {
                        Log.d(SEARCH_TAG, "dismiss ignored ($why) age=${age}ms")
                        return
                    }
                    closeSearch("map touch: $why")
                }
                map.addOnMapClickListener { dismissSearch("click"); false }
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapboxMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        dismissSearch("gesture")
                    } else if (searchOpen) {
                        Log.d(SEARCH_TAG, "camera move ignored reason=$reason")
                    }
                }
                // The map takes the focus first, so the keyboard does not open
                // over the map the moment the dashboard appears.
                mapView.requestFocus()
                // A destination is set by a long press, not a tap: a tap while
                // panning, or a knock on the screen in a bumpy lane, used to
                // drop the route being followed and start a new one. Never
                // while the car moves (the drive lock).
                map.addOnMapLongClickListener { latLng ->
                    if (movingNow.value) return@addOnMapLongClickListener true
                    clearRoute()
                    mapRef?.addMarker(MarkerOptions().position(latLng))
                    routeTo(Point.fromLngLat(latLng.longitude, latLng.latitude))
                    true
                }
            }
            styleReady = true
        }
    }
    // The guided route on every map, the tile's and the wallpaper's: drawn
    // when it starts or changes (a new route after leaving it), with its
    // travelled part and the next turn's arrow; gone when it ends.
    val guidance by InAppNav.guidance.collectAsState()
    val guidedRoute = guidance?.route
    val navigation by InAppNav.navigation.collectAsState()
    var wasGuiding by remember { mutableStateOf(false) }
    LaunchedEffect(navRoute, guidedRoute) {
        val line = navRoute ?: return@LaunchedEffect
        if (guidedRoute != null) {
            line.show(guidedRoute)
            wasGuiding = true
        } else if (wasGuiding) {
            wasGuiding = false
            clearRoute()
        }
    }

    // The route line and the puck are the map's own layers, not Compose: they
    // take the accent again when the look changes (and while it fades).
    LaunchedEffect(navRoute) {
        val line = navRoute ?: return@LaunchedEffect
        snapshotFlow { DashColors.Accent }.collect { line.tint(it) }
    }
    LaunchedEffect(mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        snapshotFlow { DashColors.Accent }.collect { accent ->
            map.uiSettings.setAttributionTintColor(accent.toArgb())
            runCatching {
                val lc = map.locationComponent
                if (lc.isLocationComponentActivated) lc.applyStyle(puckOptions(context, accent))
            }
        }
    }

    // Location puck: enabled once the style is up, and again if the permission
    // is granted later from the runtime prompt.
    LaunchedEffect(hasLocation, styleReady) {
        if (!hasLocation || !styleReady) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        map.getStyle { style -> enableLocation(map, style, context, scope, mapView, wallpaper) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // The search and route panels belong to the map tile, not to a wallpaper.
        if (!wallpaper) {
            // Destination search bar. Searching from the IME key, the search button
            // or a hardware Enter all dismiss the keyboard first so the map is
            // visible while the route loads.
            fun submitSearch() {
                keyboard?.hide()
                focusManager.clearFocus()
                searchAndRoute()
            }
            val places by PlacesStore.places.collectAsState()
            LaunchedEffect(Unit) { PlacesStore.load(context) }
            // While guiding, the turn banner has the top of the map.
            // Opened: the map stops being focusable, the box takes the cursor and
            // the keyboard comes up (parked only). Keyed on the state, not on the
            // animated content, so it runs once per opening.
            LaunchedEffect(searchOpen) {
                if (!searchOpen) return@LaunchedEffect
                Log.d(SEARCH_TAG, "open effect moving=$moving")
                mapView.isFocusable = false
                mapView.isFocusableInTouchMode = false
                if (mapView.hasFocus()) mapView.clearFocus()
                if (moving) return@LaunchedEffect
                delay(150) // the field is composed and laid out by now
                val ok = runCatching { searchFocusRequester.requestFocus() }
                Log.d(SEARCH_TAG, "requestFocus -> $ok")
                delay(50)
                Log.d(SEARCH_TAG, "keyboard.show()")
                keyboard?.show()
                delay(350)
                // Fallback for a keyboard that was slow to bind.
                if (searchOpen) {
                    Log.d(SEARCH_TAG, "IMM showSoftInput fallback focused=${searchFocused.value}")
                    runCatching {
                        (hostView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                            ?.showSoftInput(hostView, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
            LaunchedEffect(guidance != null) { if (guidance != null && searchOpen) closeSearch("guidance started") }
            if (guidance == null) Box(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
            ) {
                if (!searchOpen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MapButton(Icons.Filled.Search, stringResource(R.string.info_map_search)) { openSearch("magnifier") }
                        // The route can be dropped without opening the search.
                        if (route != null) {
                            MapButton(Icons.Filled.Close, stringResource(R.string.info_map_clear_route), tint = DashColors.Muted) { clearRoute() }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = searchOpen,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 560.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
            Surface(
                color = OverlayBg,
                shape = DashShape.Medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(if (moving) R.string.dash_drive_lock_notice else R.string.info_map_where_to), color = DashColors.Muted) },
                        enabled = !moving,
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocusRequester)
                            .onFocusChanged {
                                Log.d(SEARCH_TAG, "field focus=${it.isFocused} open=$searchOpen")
                                searchFocused.value = it.isFocused
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyUp &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    submitSearch(); true
                                } else false
                            },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = DashColors.TextPrimary,
                            unfocusedTextColor = DashColors.TextPrimary,
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = DashColors.Muted.copy(alpha = 0.4f),
                            cursorColor = Accent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        // Head-unit keyboards label the action key differently; accept them all.
                        keyboardActions = KeyboardActions(
                            onSearch = { submitSearch() },
                            onDone = { submitSearch() },
                            onGo = { submitSearch() },
                            onSend = { submitSearch() }
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Accent, strokeWidth = 3.dp)
                    } else {
                        IconButton(onClick = { submitSearch() }, modifier = Modifier.size(MapButtonSize)) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.info_map_search), tint = AccentMark)
                        }
                    }
                    IconButton(onClick = { closeSearch("close button") }, modifier = Modifier.size(MapButtonSize)) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = DashColors.Muted)
                    }
                }
            }
            // Out of the way once a destination is set: the route is what matters then.
            if (destination == null) PlaceChips(places, onPick = { routeToPlace(it) })
                    }
                }
            }

            // Zoom and back to the car: always, moving or not (the drive lock is for typing).
            val zoomState = remember { doubleArrayOf(0.0, 0.0) }
            fun zoomBy(delta: Int) {
                val map = mapRef ?: return
                val now = System.currentTimeMillis()
                // Quick taps add up from the zoom being animated to, not the one the camera has reached.
                val base = if (now - zoomState[1].toLong() < 600) zoomState[0] else map.cameraPosition.zoom
                val z = (base + delta).coerceIn(map.minZoomLevel, map.maxZoomLevel)
                zoomState[0] = z; zoomState[1] = now.toDouble()
                userZooms[map] = z
                val lc = runCatching { map.locationComponent }.getOrNull()
                if (lc != null && lc.isLocationComponentActivated && lc.cameraMode != CameraMode.NONE) {
                    // Through the component: a plain zoom would drop tracking, and the
                    // resume ten seconds on would snap back to the old zoom.
                    runCatching { lc.zoomWhileTracking(z, 300L) }
                } else {
                    map.animateCamera(CameraUpdateFactory.zoomTo(z), 300)
                }
            }
            fun recenter() {
                val map = mapRef ?: return
                val lc = runCatching { map.locationComponent }.getOrNull() ?: return
                if (!lc.isLocationComponentActivated) return
                val fix = LocationFeed.location.value ?: runCatching { lc.lastKnownLocation }.getOrNull()
                if (fix == null) return
                userZooms.remove(map); userMoved.remove(map)
                followVehicle(lc, mapView, wallpaper = false, zoom = null)
            }
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MapButton(Icons.Filled.Add, stringResource(R.string.info_map_zoom_in)) { zoomBy(1) }
                MapButton(Icons.Filled.Remove, stringResource(R.string.info_map_zoom_out)) { zoomBy(-1) }
                MapButton(Icons.Filled.MyLocation, stringResource(R.string.info_map_recenter)) { recenter() }
            }

            val guiding = guidance
            if (guiding != null) {
                GuidancePanel(guiding, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                return@Box
            }
            // Route info / error + Start (on this map; or in Google Maps / Waze).
            val currentInfo = info
            val currentError = error
            val hasDest = destination != null
            if (currentInfo != null || currentError != null || hasDest) {
                Surface(
                    color = OverlayBg,
                    shape = DashShape.Medium,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = currentInfo ?: currentError ?: stringResource(R.string.info_map_ready),
                            color = if (currentError != null) DashColors.Critical else DashColors.TextPrimary
                        )
                        if (hasDest) {
                            Button(
                                onClick = { destination?.let { startGuidance(it) } },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = DashColors.OnAccent),
                                modifier = Modifier.heightIn(min = DashSize.TouchPrimary)
                            ) {
                                Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.info_map_start))
                            }
                            // The same trip in Google Maps or Waze instead.
                            if (route != null) {
                                IconButton(onClick = { destination?.let { startGuidance(it, inOtherApp = true) } }) {
                                    Icon(Icons.Filled.OpenInNew, contentDescription = stringResource(R.string.info_map_other_app), tint = DashColors.Muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val MapButtonSize = 64.dp

/** A square touch target over the map, in the overlay's colours. */
@Composable
private fun MapButton(icon: ImageVector, description: String, tint: Color = AccentMark, onClick: () -> Unit) {
    Surface(onClick = onClick, color = OverlayBg, shape = DashShape.Medium, modifier = Modifier.size(MapButtonSize)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(32.dp))
        }
    }
}

/** Under the map while guiding: time, distance and arrival left, the voice switch and Stop. */
@Composable
private fun GuidancePanel(guidance: InAppNav.Guidance, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val voiceOn by InAppNav.voiceOn.collectAsState()
    Surface(color = OverlayBg, shape = DashShape.Medium, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = when {
                    guidance.arrived -> stringResource(R.string.info_map_arrived)
                    guidance.rerouting -> stringResource(R.string.info_map_rerouting)
                    else -> guidance.remaining.ifEmpty { stringResource(R.string.info_map_ready) }
                },
                color = DashColors.TextPrimary
            )
            IconButton(
                onClick = { InAppNav.setVoice(context, !voiceOn) },
                modifier = Modifier.size(DashSize.TouchPrimary)
            ) {
                Icon(
                    if (voiceOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = stringResource(if (voiceOn) R.string.info_map_voice_off else R.string.info_map_voice_on),
                    tint = if (voiceOn) AccentMark else DashColors.Muted
                )
            }
            Button(
                onClick = { InAppNav.stop() },
                colors = ButtonDefaults.buttonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary),
                modifier = Modifier.heightIn(min = DashSize.TouchPrimary)
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.info_map_stop))
            }
        }
    }
}

/** The location puck in the dashboard's accent: the dot, the heading arrow and the accuracy disc. */
private fun puckOptions(context: Context, accent: Color): LocationComponentOptions {
    val argb = accent.toArgb()
    return LocationComponentOptions.builder(context)
        .trackingGesturesManagement(true)
        .foregroundTintColor(argb)
        .bearingTintColor(argb)
        .accuracyColor(argb)
        .build()
}

@SuppressLint("MissingPermission")
private fun enableLocation(
    map: MapboxMap,
    style: Style,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    mapView: MapView,
    /** Set when the map is a wallpaper: the camera sits lower and the car lower on it. */
    wallpaper: Boolean
) {
    if (!hasLocationPerm(context)) return
    val lc = map.locationComponent
    runCatching {
        if (!lc.isLocationComponentActivated) {
            // Tracking gestures management: a two-finger pinch zooms around the
            // car and keeps following it, instead of counting as a pan.
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .locationComponentOptions(
                        puckOptions(context, DashColors.Accent)
                    )
                    .build()
            )
            // The zoom the driver picks by hand is the one tracking comes back
            // to; route previews and search hits don't change it.
            var byGesture = false
            map.addOnCameraMoveStartedListener { reason ->
                byGesture = reason == MapboxMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                if (byGesture) userMoved[map] = true
            }
            map.addOnCameraIdleListener {
                if (byGesture) userZooms[map] = map.cameraPosition.zoom
                byGesture = false
            }
            // Any gesture or programmatic camera move (route preview, search hit)
            // drops the component out of tracking mode. In a car the map has to
            // come back to the vehicle by itself, so tracking resumes a few
            // seconds after the last interruption.
            var resume: Job? = null
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() {
                    resume?.cancel()
                    resume = scope.launch {
                        delay(TRACKING_RESUME_MS)
                        followVehicle(lc, mapView, wallpaper, userZooms[map])
                    }
                }

                override fun onCameraTrackingChanged(currentMode: Int) {
                    if (currentMode != CameraMode.NONE) resume?.cancel()
                }
            })
        }
        // Opens where the car was last seen, not at the whole world, while
        // tracking waits for a fix. A plain move: tracking is not on yet.
        if (positioned.add(map) && userMoved[map] != true) {
            val last = LocationFeed.location.value
                ?: runCatching { lc.lastKnownLocation }.getOrNull()
            if (last != null) {
                runCatching {
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(last.latitude, last.longitude))
                                .zoom(userZooms[map] ?: if (wallpaper) 17.0 else 16.5)
                                .tilt(if (wallpaper) 60.0 else 45.0)
                                .build()
                        )
                    )
                }
            }
        }
        // The map has no height until it is laid out, and changes with its size.
        if (lookAheadHooked.add(mapView)) {
            mapView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) applyLookAhead(lc, mapView, wallpaper)
            }
        }
        lc.isLocationComponentEnabled = true
        // Puck arrow from the GPS course (no magnetometer); the camera follows position AND heading (map rotates
        // with the car) at a close zoom with pitch for the driving-nav look.
        followVehicle(lc, mapView, wallpaper, userZooms[map])
    }
    // Stationary detection from the fixes: stopped, the map holds its bearing.
    if (motionHooked.add(map)) {
        scope.launch {
            val det = StationaryDetector()
            var prev: android.location.Location? = null
            LocationFeed.location.filterNotNull().collect { fix ->
                val kmh = StationaryDetector.speedKmh(fix, prev)
                prev = fix
                if (kmh != null) applyMotion(map, lc, mapView, wallpaper, det.update(System.currentTimeMillis(), kmh))
            }
        }
    }
    // TRACKING_GPS only re-centres on a FRESH fix; with only a last-known
    // location the camera stays where it was put above. Wait, as long as it
    // takes (a cold GPS in a car can take minutes), for the first fix, then
    // engage tracking *through* the component: a plain animateCamera here
    // would count as a developer move and cancel tracking straight away.
    // A driver who has already moved the map is left alone; the resume timer
    // brings the camera back to the car.
    scope.launch {
        val held = LocationFeed.acquire(context.applicationContext)
        try {
            LocationFeed.location.filterNotNull().first()
        } finally {
            if (held) LocationFeed.release()
        }
        if (userMoved[map] != true) followVehicle(lc, mapView, wallpaper, userZooms[map])
    }
}

private const val SEARCH_TAG = "DashMapSearch"

/** A map touch this soon after the search panel opened does not close it. */
private const val SEARCH_GRACE_MS = 1_000L

/** Seconds of free panning before the camera snaps back to the vehicle. */
private const val TRACKING_RESUME_MS = 10_000L

/** Zoom last set by a pinch on each map; tracking resumes at it instead of the default. */
private val userZooms = java.util.WeakHashMap<MapboxMap, Double>()

/** Maps whose driver has panned or zoomed them by hand: the first-fix jump leaves those alone. */
private val userMoved = java.util.WeakHashMap<MapboxMap, Boolean>()

/** Maps already put at the last known position, once. */
private val positioned = java.util.Collections.newSetFromMap(java.util.WeakHashMap<MapboxMap, Boolean>())

/** Maps already watching the fixes for stationary / moving. */
private val motionHooked = java.util.Collections.newSetFromMap(java.util.WeakHashMap<MapboxMap, Boolean>())

/** Map views that already watch their layout for [applyLookAhead]. */
private val lookAheadHooked = java.util.Collections.newSetFromMap(java.util.WeakHashMap<MapView, Boolean>())

/** Location components whose car stands still: the camera and puck then ignore GPS bearing. */
private val stationaryLcs = java.util.Collections.newSetFromMap(java.util.WeakHashMap<LocationComponent, Boolean>())

/**
 * Follow position, and heading while moving, with the driving-nav zoom and
 * pitch, keeping tracking on. The moving puck arrow is the GPS course, never
 * the head unit's compass. Standing still the camera keeps its bearing
 * (TRACKING) and the puck does not turn with GPS noise.
 */
private fun followVehicle(lc: LocationComponent, view: MapView, wallpaper: Boolean, zoom: Double? = null) {
    val still = lc in stationaryLcs
    // A wallpaper looks further ahead: steeper, a little closer.
    runCatching {
        lc.renderMode = if (still) RenderMode.NORMAL else RenderMode.GPS
        val mode = if (still) CameraMode.TRACKING else CameraMode.TRACKING_GPS
        if (wallpaper) lc.setCameraMode(mode, 1000L, zoom ?: 17.0, null, 60.0, null)
        else lc.setCameraMode(mode, 1000L, zoom ?: 16.5, null, 45.0, null)
    }
    applyLookAhead(lc, view, wallpaper)
}

/** The car stopped or set off: switch bearing-following and the puck, keeping zoom, tilt and padding. */
private fun applyMotion(map: MapboxMap, lc: LocationComponent, view: MapView, wallpaper: Boolean, still: Boolean) {
    if ((lc in stationaryLcs) == still) return
    if (still) stationaryLcs.add(lc) else stationaryLcs.remove(lc)
    Log.d("DashMapMotion", "stationary=$still wallpaper=$wallpaper cameraMode=${runCatching { lc.cameraMode }.getOrNull()}")
    runCatching {
        lc.renderMode = if (still) RenderMode.NORMAL else RenderMode.GPS
        // Not tracking (the driver is panning): the 10 s resume picks the right mode up.
        if (lc.cameraMode != CameraMode.NONE) {
            val cam = map.cameraPosition
            lc.setCameraMode(if (still) CameraMode.TRACKING else CameraMode.TRACKING_GPS, 600L, cam.zoom, null, cam.tilt, null)
            applyLookAhead(lc, view, wallpaper)
        }
    }
}

/**
 * The car pushed down from the middle of the map, so more of the road ahead
 * shows: top padding p moves it down by p/2. Skipped until the map has a height;
 * the layout listener comes back here once it does.
 */
private fun applyLookAhead(lc: LocationComponent, view: MapView, wallpaper: Boolean) {
    val h = view.height
    if (h <= 0) return
    val top = h * if (wallpaper) WALLPAPER_LOOK_AHEAD else TILE_LOOK_AHEAD
    runCatching { lc.paddingWhileTracking(doubleArrayOf(0.0, top, 0.0, 0.0)) }
}

/** The wallpaper map's frame cap: smooth enough for a car moving under it, half the work of 60. */
private const val WALLPAPER_FPS = 30

/** The interactive map's frame cap: 30 is plenty for the head unit's Mali-450 and leaves the CPU/GPU to the rest of the dashboard. */
private const val TILE_FPS = 30

/** Top padding while tracking, as a share of the map's height: it moves the car down by half of it, to about a quarter from the bottom. */
private const val TILE_LOOK_AHEAD = 0.5

/** The same for the wallpaper map. */
private const val WALLPAPER_LOOK_AHEAD = 0.42

/**
 * A map's frame cap on a page off screen. The cap is a sleep after each frame
 * on the map's render thread, which lifting it does not cut short, and the
 * main thread waits it out when the map's view goes: a tenth of a second at
 * most, over by the time a sliding page shows much of the map.
 */
private const val HIDDEN_FPS = 10

private fun hasLocationPerm(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Home, work and the last destinations as buttons under the search bar: one
 * tap shows the route, moving or not, where typing has to wait for a stop.
 */
@Composable
private fun PlaceChips(places: Places, onPick: (Place) -> Unit) {
    val home = stringResource(R.string.places_home)
    val work = stringResource(R.string.places_work)
    val chips = remember(places, home, work) {
        listOfNotNull(
            places.home?.let { Triple(Icons.Filled.Home, home, it) },
            places.work?.let { Triple(Icons.Filled.Work, work, it) }
        ) + places.recent.map { Triple(Icons.Filled.History, it.name, it) }
    }
    if (chips.isEmpty()) return
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { (icon, label, place) ->
            Surface(onClick = { onPick(place) }, color = OverlayBg, shape = DashShape.Pill) {
                Row(
                    modifier = Modifier.heightIn(min = DashSize.TouchPrimary).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, tint = AccentMark, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = DashColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                }
            }
        }
    }
}

/** Fetches a driving route from a free Valhalla server (OSRM-format response). */
internal fun valhallaRoute(origin: Point, dest: Point, language: String): DirectionsResponse {
    val body = mapOf(
        "format" to "osrm",
        "costing" to "auto",
        "banner_instructions" to true,
        "voice_instructions" to true,
        "language" to language,
        // The spoken and written instructions in the driver's unit; the route's own figures stay in metres.
        "directions_options" to mapOf("units" to if (Units.current.value.imperial) "miles" else "kilometers"),
        "locations" to listOf(
            mapOf("lon" to origin.longitude(), "lat" to origin.latitude(), "type" to "break"),
            mapOf("lon" to dest.longitude(), "lat" to dest.latitude(), "type" to "break")
        )
    )
    val json = Gson().toJson(body)
    val routingUrl = MapServers.current.value.routing
    val request = Request.Builder()
        .header("User-Agent", USER_AGENT)
        .url(routingUrl)
        .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    Http.client.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        val rb = resp.body?.string() ?: error("Empty routing response")
        // Valhalla doesn't echo the request back, and the navigation refuses a
        // route without it (its spoken turns need voice and banner instructions).
        val options = RouteOptions(
            baseUrl = routingUrl,
            user = "valhalla",
            profile = "auto",
            coordinates = listOf(origin, dest),
            language = language,
            geometries = "polyline6",
            steps = true,
            voiceInstructions = true,
            bannerInstructions = true
        )
        val parsed = DirectionsResponse.fromJson(rb)
        return parsed.copy(routes = parsed.routes.map { it.copy(routeOptions = options) })
    }
}

private fun formatEta(context: Context, distanceMeters: Double?, durationSeconds: Double?): String {
    val units = Units.current.value
    val d = units.distance((distanceMeters ?: 0.0) / 1000.0)
    val mins = ((durationSeconds ?: 0.0) / 60.0).toInt()
    val dist = (if (d >= 10) "%.0f %s" else "%.1f %s").format(d, units.distanceUnit)
    val time = if (mins >= 60) context.getString(R.string.info_map_duration_hm, mins / 60, mins % 60)
    else context.getString(R.string.info_map_duration_min, mins)
    return "$dist · $time"
}
