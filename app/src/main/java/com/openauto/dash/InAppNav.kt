package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.geojson.Point
import org.maplibre.navigation.android.navigation.ui.v5.instruction.maneuver.ManeuverView
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.milestone.Milestone
import org.maplibre.navigation.core.milestone.MilestoneEventListener
import org.maplibre.navigation.core.milestone.VoiceInstructionMilestone
import org.maplibre.navigation.core.models.BannerText
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.StepManeuver
import org.maplibre.navigation.core.navigation.AndroidMapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions
import org.maplibre.navigation.core.offroute.OffRouteListener
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener
import org.maplibre.navigation.core.routeprogress.RouteProgress
import java.util.Date
import java.util.Locale

/**
 * Turn-by-turn guidance on the dashboard's own map, without Google Maps.
 *
 * The route comes from the same free Valhalla server as the preview, with its
 * written and spoken instructions in the app's language. MapLibre Navigation
 * follows the car along it; the next turn is published through [NavDirections],
 * so the map banner, the Directions tile, the skins and the cluster show it as
 * they show Google Maps' turns. The instructions are spoken by [CarVoice], and
 * leaving the route asks Valhalla for a new one from where the car is.
 *
 * Lives in the process, not in a map: the guidance goes on while another page
 * or app is in front, and every map drawn meanwhile picks up its route.
 */
object InAppNav {

    /** A trip being guided. */
    data class Guidance(
        val route: DirectionsRoute,
        val destination: Point,
        val name: String?,
        /** Distance and time left, as shown under the map; empty until the first fix on the route. */
        val remaining: String = "",
        /** A new route is being fetched after the car left this one. */
        val rerouting: Boolean = false,
        val arrived: Boolean = false
    )

    private val _guidance = MutableStateFlow<Guidance?>(null)
    val guidance: StateFlow<Guidance?> = _guidance

    /** The running navigation, for the maps' route line (the travelled part and the turn arrow). */
    private val _navigation = MutableStateFlow<MapLibreNavigation?>(null)
    val navigation: StateFlow<MapLibreNavigation?> = _navigation

    private val _voiceOn = MutableStateFlow(true)
    val voiceOn: StateFlow<Boolean> = _voiceOn

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reroute: Job? = null
    private var lastReroute = 0L
    private var arrival: Job? = null
    private var appContext: Context? = null

    val active: Boolean get() = _guidance.value != null

    private val _askStop = MutableStateFlow(false)
    /** A tile or a banner showing this guidance was tapped: the dashboard asks whether to stop it ([StopGuidancePrompt]). */
    val askStop: StateFlow<Boolean> = _askStop

    fun askToStop() {
        if (active) _askStop.value = true
    }

    fun answerStop(stop: Boolean) {
        _askStop.value = false
        if (stop) stop()
    }

    /** Stops the guidance if it runs; whether it did. Main thread. */
    fun endNow(): Boolean {
        val was = active
        if (was) stop()
        return was
    }

    /**
     * A navigation app is handed a destination: this guidance ends, or two
     * voices would give two routes. From any thread.
     */
    fun handOver() {
        if (active) scope.launch { stop() }
    }

    /** Starts guiding along [route], a route Valhalla gave from where the car is to [destination]. */
    @SuppressLint("MissingPermission")
    fun start(context: Context, route: DirectionsRoute, destination: Point, name: String?): Boolean {
        stop()
        // One guide at a time: Google Maps' own guidance, if it runs, is asked to end.
        NavDirections.exitNavigation()
        val app = context.applicationContext
        appContext = app
        _voiceOn.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VOICE, true)
        val nav = runCatching {
            // The position comes from the dashboard's own feed, not from the
            // SDK's MapLibreLocationEngine: that one is the single class in the
            // navigation SDK that reaches into the map SDK, which is pinned
            // back to 10.x here for OpenGL ES 2.0 and has those classes under
            // different names. See FeedLocationEngine.
            AndroidMapLibreNavigation(app, MapLibreNavigationOptions(), FeedLocationEngine(app))
        }.getOrElse {
            Log.w("InAppNav", "navigation failed to start: ${it.message}")
            return false
        }
        nav.addProgressChangeListener(progressListener)
        nav.addMilestoneEventListener(milestoneListener)
        nav.addOffRouteListener(offRouteListener)
        _guidance.value = Guidance(route, destination, name)
        _navigation.value = nav
        // The library throws on a route it can't follow: no guidance, not a crash.
        runCatching { nav.startNavigation(route) }.onFailure {
            Log.w("InAppNav", "route refused: ${it.message}")
            stop()
            return false
        }
        return true
    }

    /** Ends the guidance; the turn banners go back to Google Maps' or Waze's, if they guide. */
    fun stop() {
        reroute?.cancel(); reroute = null
        arrival?.cancel(); arrival = null
        _navigation.value?.let { nav ->
            runCatching {
                nav.removeProgressChangeListener(progressListener)
                nav.removeMilestoneEventListener(milestoneListener)
                nav.removeOffRouteListener(offRouteListener)
                nav.stopNavigation()
                nav.onDestroy()
            }
        }
        _navigation.value = null
        _guidance.value = null
        _askStop.value = false
        NavDirections.publishInApp(null)
    }

    fun setVoice(context: Context, on: Boolean) {
        _voiceOn.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE, on).apply()
    }

    /** The app's language: the route's instructions are written and spoken in it. */
    fun locale(context: Context): Locale = AppLanguage.wrap(context.applicationContext).resources.configuration.locales[0]

    private val progressListener = object : ProgressChangeListener {
        override fun onProgressChange(location: Location, routeProgress: RouteProgress) {
            scope.launch { onProgress(routeProgress) }
        }
    }

    private val milestoneListener = object : MilestoneEventListener {
        override fun onMilestoneEvent(routeProgress: RouteProgress, instruction: String?, milestone: Milestone) {
            if (milestone !is VoiceInstructionMilestone || !_voiceOn.value) return
            val context = appContext ?: return
            val text = milestone.announcement?.takeIf { it.isNotBlank() } ?: return
            CarVoice.guide(text, locale(context))
        }
    }

    private val offRouteListener = object : OffRouteListener {
        override fun userOffRoute(location: Location) {
            scope.launch { onOffRoute(location) }
        }
    }

    private fun onProgress(progress: RouteProgress) {
        val g = _guidance.value ?: return
        val context = appContext ?: return
        val leg = progress.currentLegProgress
        val step = leg.currentStep
        val stepLeft = leg.currentStepProgress.distanceRemaining
        // Banner instructions on a step describe the manoeuvre at its end; the one
        // in force is the closest whose announce distance the car has passed.
        val banner = step.bannerInstructions
            ?.filter { it.distanceAlongGeometry >= stepLeft }
            ?.minByOrNull { it.distanceAlongGeometry }
            ?: step.bannerInstructions?.firstOrNull()
        val primary: BannerText? = banner?.primary
        val upcoming = leg.upComingStep
        val type = primary?.type ?: upcoming?.maneuver?.type
        val modifier = primary?.modifier?.text ?: upcoming?.maneuver?.modifier?.text
        val instruction = primary?.text?.takeIf { it.isNotBlank() }
            ?: upcoming?.maneuver?.instruction
            ?: step.maneuver.instruction
            ?: ""
        val units = Units.current.value
        val res = AppLanguage.wrap(context).resources
        val mins = (progress.durationRemaining / 60.0).toInt()
        val time = if (mins >= 60) res.getString(R.string.info_map_duration_hm, mins / 60, mins % 60)
        else res.getString(R.string.info_map_duration_min, mins)
        val distance = units.shortDistance(progress.distanceRemaining)
        val arrival = units.time(Date(System.currentTimeMillis() + (progress.durationRemaining * 1000).toLong()))
        val arrived = progress.distanceRemaining < ARRIVED_M || step.maneuver.type == StepManeuver.Type.ARRIVE
        _guidance.value = g.copy(remaining = "$time · $distance · $arrival", arrived = arrived)
        NavDirections.publishInApp(
            NavState(
                active = true,
                instruction = instruction,
                distance = if (arrived) "" else units.shortDistance(stepLeft),
                eta = "$time · $distance · $arrival",
                icon = maneuverIcon(context, type?.text, modifier, primary?.degrees, primary?.drivingSide),
                packageName = context.packageName,
                maneuver = Maneuvers.fromOsrm(type?.text, modifier)
            )
        )
        // There: the last line stays up a moment, then the guidance ends by itself.
        if (arrived && this.arrival == null) {
            this.arrival = scope.launch {
                delay(ARRIVED_HOLD_MS)
                stop()
            }
        }
    }

    private suspend fun onOffRoute(location: Location) {
        val g = _guidance.value ?: return
        val context = appContext ?: return
        if (g.arrived || reroute?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastReroute < REROUTE_GAP_MS) return
        lastReroute = now
        _guidance.value = g.copy(rerouting = true)
        reroute = scope.launch {
            val from = Point.fromLngLat(location.longitude, location.latitude)
            val result = withContext(Dispatchers.IO) {
                runCatching { valhallaRoute(from, g.destination, locale(context).language) }
            }
            val fresh = result.getOrNull()?.routes?.firstOrNull()
            val current = _guidance.value ?: return@launch
            if (fresh == null) {
                Log.w("InAppNav", "reroute failed: ${result.exceptionOrNull()?.message}")
                _guidance.value = current.copy(rerouting = false)
                return@launch
            }
            _guidance.value = current.copy(route = fresh, rerouting = false)
            runCatching { _navigation.value?.startNavigation(fresh) }
                .onFailure { Log.w("InAppNav", "reroute refused: ${it.message}") }
        }
    }

    // --- Turn glyph -------------------------------------------------------------------------------

    private val icons = HashMap<String, Bitmap>()

    /** The navigation UI's own turn arrow, drawn white on transparent like Google Maps' glyph. */
    private fun maneuverIcon(context: Context, type: String?, modifier: String?, degrees: Double?, side: String?): Bitmap? {
        if (type == null) return null
        val angle = degrees?.toFloat()
        val key = "$type|$modifier|${angle?.let { (it / 15).toInt() }}|$side"
        icons[key]?.let { return it }
        return runCatching {
            val view = ManeuverView(context).apply {
                setPrimaryColor(Color.WHITE)
                setSecondaryColor(0x66FFFFFF)
                side?.let { setDrivingSide(it) }
                angle?.let { setRoundaboutAngle(it) }
                setManeuverTypeAndModifier(type, modifier.orEmpty())
            }
            val spec = View.MeasureSpec.makeMeasureSpec(ICON_PX, View.MeasureSpec.EXACTLY)
            view.measure(spec, spec)
            view.layout(0, 0, ICON_PX, ICON_PX)
            Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }.getOrNull()?.also { if (icons.size < 64) icons[key] = it }
    }

    private const val PREFS = "in_app_nav"
    private const val KEY_VOICE = "voice"
    private const val ICON_PX = 128
    /** Metres from the destination that count as there. */
    private const val ARRIVED_M = 25.0
    private const val ARRIVED_HOLD_MS = 20_000L
    /** Least time between two new routes, so a car parked off the line doesn't hammer the server. */
    private const val REROUTE_GAP_MS = 8_000L
}
