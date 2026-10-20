package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.FillExtrusionLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.sources.VectorSource
import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.RouteOptions
import java.util.Locale

// Free, no-key services by default: OpenFreeMap's dark / positron styles, Nominatim
// geocoding ([PlaceSearch]) and the public Valhalla router; the map style and the
// router can be pointed at the driver's own servers ([MapServers]).
private const val USER_AGENT = "OpenAutoDash/1.0 (car launcher)"

private val Accent = Color(0xFF8AB4F8)
private val OverlayBg = Color(0xE6141518)

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
        mapView.setMaximumFps(if (!pageActive) HIDDEN_FPS else if (wallpaper) WALLPAPER_FPS else Int.MAX_VALUE)
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
        }
    }

    /** A saved place or a last destination, tapped: its route, ready to start. */
    fun routeToPlace(place: Place) {
        clearRoute()
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
            mapRef?.let { followVehicle(it.locationComponent, null, userZooms[it]) }
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Whether the destination box holds the cursor, so a touch of the map can
    // put it down again — and only then, so every other touch is left alone.
    val searchFocused = remember { mutableStateOf(false) }
    MapServers.load(context)
    val servers by MapServers.current.collectAsState()
    val styleUrl = if (lightMap) servers.styleLight else servers.styleDark
    LaunchedEffect(mapRef, styleUrl) {
        val map = mapRef ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
            add3dBuildings(style, lightMap)
            // The route's source and layers belong to the style, and switching
            // between the day and night styles builds a new one — so the line
            // has to be made again each time or it disappears with the old
            // style, in silence, leaving a guided route with nothing drawn.
            navRoute = RouteLine(style)
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
                fun dismissSearch() {
                    if (!searchFocused.value) return
                    keyboard?.hide()
                    focusManager.clearFocus(force = true)
                    mapView.requestFocus()
                }
                map.addOnMapClickListener { dismissSearch(); false }
                map.addOnCameraMoveStartedListener { dismissSearch() }
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

    // Location puck: enabled once the style is up, and again if the permission
    // is granted later from the runtime prompt.
    LaunchedEffect(hasLocation, styleReady) {
        if (!hasLocation || !styleReady) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        map.getStyle { style -> enableLocation(map, style, context, scope, if (wallpaper) mapView else null) }
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
            if (guidance == null) Column(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
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
                        placeholder = { Text(stringResource(if (moving) R.string.dash_drive_lock_notice else R.string.info_map_where_to), color = Color(0xFF9AA0A6)) },
                        enabled = !moving,
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { searchFocused.value = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyUp &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    submitSearch(); true
                                } else false
                            },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Color(0xFF2A2D33),
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
                        IconButton(onClick = { submitSearch() }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.info_map_search), tint = Accent)
                        }
                    }
                    if (route != null) {
                        IconButton(onClick = { clearRoute() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.info_map_clear_route), tint = Color(0xFF9AA0A6))
                        }
                    }
                }
            }
            // Out of the way once a destination is set: the route is what matters then.
            if (destination == null) PlaceChips(places, onPick = { routeToPlace(it) })
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
                            color = if (currentError != null) Color(0xFFF28B82) else Color.White
                        )
                        if (hasDest) {
                            Button(
                                onClick = { destination?.let { startGuidance(it) } },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF0B0C0F)),
                                modifier = Modifier.heightIn(min = DashSize.TouchPrimary)
                            ) {
                                Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.info_map_start))
                            }
                            // The same trip in Google Maps or Waze instead.
                            if (route != null) {
                                IconButton(onClick = { destination?.let { startGuidance(it, inOtherApp = true) } }) {
                                    Icon(Icons.Filled.OpenInNew, contentDescription = stringResource(R.string.info_map_other_app), tint = Color(0xFF9AA0A6))
                                }
                            }
                        }
                    }
                }
            }
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
                color = Color.White
            )
            IconButton(
                onClick = { InAppNav.setVoice(context, !voiceOn) },
                modifier = Modifier.size(DashSize.TouchPrimary)
            ) {
                Icon(
                    if (voiceOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = stringResource(if (voiceOn) R.string.info_map_voice_off else R.string.info_map_voice_on),
                    tint = if (voiceOn) Accent else Color(0xFF9AA0A6)
                )
            }
            Button(
                onClick = { InAppNav.stop() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2D33), contentColor = Color.White),
                modifier = Modifier.heightIn(min = DashSize.TouchPrimary)
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.info_map_stop))
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun enableLocation(
    map: MapboxMap,
    style: Style,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    /** Set when the map is a wallpaper: the camera sits lower and the car low on it. */
    wallpaper: MapView? = null
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
                        LocationComponentOptions.builder(context)
                            .trackingGesturesManagement(true)
                            .build()
                    )
                    .build()
            )
            // The zoom the driver picks by hand is the one tracking comes back
            // to; route previews and search hits don't change it.
            var byGesture = false
            map.addOnCameraMoveStartedListener { reason ->
                byGesture = reason == MapboxMap.OnCameraMoveStartedListener.REASON_API_GESTURE
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
                        followVehicle(lc, wallpaper, userZooms[map])
                    }
                }

                override fun onCameraTrackingChanged(currentMode: Int) {
                    if (currentMode != CameraMode.NONE) resume?.cancel()
                }
            })
        }
        lc.isLocationComponentEnabled = true
        // Directional puck; the camera follows position AND heading (map rotates
        // with the car) at a close zoom with pitch for the driving-nav look.
        lc.renderMode = RenderMode.COMPASS
        followVehicle(lc, wallpaper, userZooms[map])
    }
    // TRACKING_GPS only re-centres on a FRESH fix; with only a last-known
    // location the camera stays at the default world view. Once any fix exists,
    // fly to it *through* the component: a plain animateCamera here would
    // count as a developer move and cancel tracking straight away.
    scope.launch {
        repeat(15) {
            val loc = runCatching { lc.lastKnownLocation }.getOrNull()
            if (loc != null) {
                followVehicle(lc, wallpaper, userZooms[map])
                return@launch
            }
            delay(800)
        }
    }
}

/** Seconds of free panning before the camera snaps back to the vehicle. */
private const val TRACKING_RESUME_MS = 10_000L

/** Zoom last set by a pinch on each map; tracking resumes at it instead of the default. */
private val userZooms = java.util.WeakHashMap<MapboxMap, Double>()

/** Follow position and heading with the driving-nav zoom and pitch, keeping tracking on. */
private fun followVehicle(lc: LocationComponent, wallpaper: MapView? = null, zoom: Double? = null) {
    if (wallpaper == null) {
        runCatching { lc.setCameraMode(CameraMode.TRACKING_GPS, 1000L, zoom ?: 16.5, null, 45.0, null) }
        return
    }
    // A wallpaper looks further ahead: steeper, a little closer, and the car
    // pushed down to the lower part of the screen, between the panels.
    runCatching {
        lc.setCameraMode(CameraMode.TRACKING_GPS, 1000L, zoom ?: 17.0, null, 60.0, null)
        lc.paddingWhileTracking(doubleArrayOf(0.0, wallpaper.height * 0.42, 0.0, 0.0))
    }
}

/** The wallpaper map's frame cap: smooth enough for a car moving under it, half the work of 60. */
private const val WALLPAPER_FPS = 30

/**
 * A map's frame cap on a page off screen. The cap is a sleep after each frame
 * on the map's render thread, which lifting it does not cut short, and the
 * main thread waits it out when the map's view goes: a tenth of a second at
 * most, over by the time a sliding page shows much of the map.
 */
private const val HIDDEN_FPS = 10

/**
 * Adds extruded 3D buildings to the vector basemap, tinted for its [light] or dark
 * version. The building geometry lives in the style's vector source under the
 * OpenMapTiles `building` source-layer with `render_height` / `render_min_height`.
 * We detect the source id at runtime so this works regardless of what the style
 * names it, and skip silently if unavailable.
 */
private fun add3dBuildings(style: Style, light: Boolean) {
    runCatching {
        val sourceId = style.sources.firstOrNull { it is VectorSource }?.id ?: return
        if (style.getLayer("3d-buildings") != null) return
        val layer = FillExtrusionLayer("3d-buildings", sourceId).apply {
            sourceLayer = "building"
            setProperties(
                PropertyFactory.fillExtrusionColor(android.graphics.Color.parseColor(if (light) "#D5D8DE" else "#2B2F36")),
                PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                PropertyFactory.fillExtrusionOpacity(0.85f)
            )
            setMinZoom(14f)
        }
        style.addLayer(layer)
    }
}

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
                    Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
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
