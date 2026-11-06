package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/*
 * Kinetic skin, the tiles (the look itself is in KineticSkin.kt): no boxes,
 * only type. The next turn is the hero, its distance huge in the skin's
 * gradient and growing wider as the turn comes closer; the speed a wide
 * outlined numeral; the clock, the weather, the engine and the music big
 * words over small spaced labels. The fuel and range tile is the car strip
 * (KineticSkinCar.kt) where it has the room.
 */

private const val DOT = " · "

/**
 * Kinetic renderer for app shortcuts, launch bars and the main widgets.
 * Anything else keeps its standard renderer.
 */
@Composable
internal fun KineticTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> KnApp(item, env)
        is DashboardItem.LaunchBar -> KnLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> KnTelemetry(env)
            BuiltinKind.SPEED_HUD -> KnSpeed(env)
            BuiltinKind.MEDIA -> KnMedia(env)
            BuiltinKind.NAVIGATION -> KnDirections(env)
            BuiltinKind.CLOCK -> KnClock(env)
            BuiltinKind.WEATHER -> KnWeather(env)
            BuiltinKind.RANGE -> KnRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Small pieces -------------------------------------------------------------------

/** Whole tile tappable with the tap feedback, or nothing when [enabled] is false (arranging, nothing to do). */
@Composable
internal fun Modifier.knTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

/** While arranging, a bare tile shows its edge as a faint dashed hairline, so it reads as something to move. */
@Composable
private fun Modifier.knEditing(editing: Boolean): Modifier {
    if (!editing) return this
    val ink = DashColors.Line
    return cachedDraw(ink) {
        val stroke = Stroke(1.5f * density, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)))
        val r = CornerRadius(24.dp.toPx())
        onDrawBehind { drawRoundRect(ink.copy(alpha = (ink.alpha * 3f).coerceAtMost(0.6f)), cornerRadius = r, style = stroke) }
    }
}

/**
 * A quiet state as words: a big line and a hint under it, the hint in the
 * accent's ink when a tap does something; the whole tile is the target.
 */
@Composable
private fun KnQuiet(title: String, hint: String, editing: Boolean, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .knEditing(editing)
            .knTap(onTap != null && !editing, hint) { onTap?.invoke() }
            .padding(12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val titleSize = min(maxHeight.value * 0.16f, maxWidth.value / (title.length * 0.42f + 1f)).coerceIn(20f, 52f)
        Column {
            KnLine(title, knText(titleSize, DashColors.TextPrimary, KnFace.COND_BOLD), maxLines = 2)
            Spacer(Modifier.height(6.dp))
            KnLine(hint, knText((titleSize * 0.5f).coerceIn(15f, 22f), if (onTap != null) DashColors.AccentInk else DashColors.TextSecondary, KnFace.WIDE_LIGHT), maxLines = 2)
        }
    }
}

/** Children in a row, in order, each only if it fits whole in the room left (dropped, never cut), [gap] apart. */
@Composable
private fun KnFitRow(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val placed = arrayOfNulls<Placeable>(measurables.size)
        val xs = IntArray(measurables.size)
        var x = 0
        var height = 0
        measurables.forEachIndexed { i, m ->
            val room = constraints.maxWidth - x
            if (room <= 0 || m.maxIntrinsicWidth(constraints.maxHeight) > room) return@forEachIndexed
            val p = m.measure(Constraints(maxWidth = room, maxHeight = constraints.maxHeight))
            placed[i] = p
            xs[i] = x
            x += p.width + gapPx
            height = max(height, p.height)
        }
        val width = (x - gapPx).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placed.forEachIndexed { i, p -> p?.placeRelative(xs[i], height - p.height) }
        }
    }
}

/** A reading as the skin says it: its name in spaced capitals over its value in the condensed bold, amber when out of range. */
@Composable
private fun KnReadout(label: String, value: String, warn: Boolean, size: Float) {
    Column {
        KnLabel(label, size = (size * 0.62f).coerceAtLeast(MIN_CAPS_DP))
        KnLine(value, knText(size, if (warn) DashColors.Warning else DashColors.TextPrimary, KnFace.COND_BOLD))
    }
}

/**
 * A button as a pill of words, as the assistant card's: [filled] in the
 * text's own ink with the page's colour on it for the one thing to do, else
 * a hairline round it. At least [DashSize.TouchPrimary] tall.
 */
@Composable
private fun KnPill(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val ink = DashColors.TextPrimary
    val page = DashColors.Background
    val rim = if (DashColors.Light) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.25f)
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .cachedDraw(filled, ink, rim) {
                val r = CornerRadius(size.height / 2f)
                val stroke = Stroke(1f * density)
                onDrawBehind {
                    if (filled) drawRoundRect(ink, cornerRadius = r)
                    else drawRoundRect(rim, androidx.compose.ui.geometry.Offset(stroke.width / 2f, stroke.width / 2f),
                        androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width), r, style = stroke)
                }
            }
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        KnLine(text, knText(18f, if (filled) page else ink, KnFace.COND_BOLD))
    }
}

// --- Navigation: the hero -------------------------------------------------------------

/**
 * How wide the hero's distance is drawn [metres] before the turn: narrow far
 * off, wider and wider over the last kilometre, widest from 50 m on.
 */
internal fun knSqueezeFor(metres: Int?): Float {
    if (metres == null) return (KN_SQUEEZE_FAR + KN_SQUEEZE_NEAR) / 2f
    val near = ((1000f - metres.coerceIn(50, 1000)) / 950f)
    return KN_SQUEEZE_FAR + (KN_SQUEEZE_NEAR - KN_SQUEEZE_FAR) * near
}

/** Words that lead into the street's name, in the app's languages; any of them may start the street. */
private val STRONG_LINKS = listOf(
    "onto", "into", "towards", "toward", "sur", "vers", "dans", "auf", "Richtung", "hacia", "por", "verso", "su",
    "para", "pela", "pelo", "na", "op", "naar", "richting", "на", "в направлении"
)

/** Short ones that also mean other things: they lead into a name only when one follows (a capital or a figure). */
private val WEAK_LINKS = listOf("on", "in", "en", "em", "no", "nella", "nel", "in die", "w", "в")

/**
 * The navigation's sentence split where the street's name starts, for the
 * hero's two lines: "Turn left onto Rue Alsace-Lorraine" gives ("Turn left
 * onto", "Rue Alsace-Lorraine"). Null when no such word is found (the
 * sentence then stands whole).
 */
internal fun knSplitInstruction(instruction: String): Pair<String, String>? {
    val text = instruction.trim()
    var best: Pair<Int, Int>? = null
    fun consider(word: String, needsName: Boolean) {
        var from = 0
        while (true) {
            val i = text.indexOf(" $word ", from, ignoreCase = true)
            if (i < 0) return
            val start = i + word.length + 2
            val next = text.getOrNull(start)
            val ok = next != null && i > 0 && (!needsName || next.isUpperCase() || next.isDigit())
            if (ok) {
                if (best == null || i < best!!.first) best = i to start
                return
            }
            from = i + 1
        }
    }
    STRONG_LINKS.forEach { consider(it, needsName = false) }
    WEAK_LINKS.forEach { consider(it, needsName = true) }
    val (cut, start) = best ?: return null
    val lead = text.substring(0, start).trim()
    val street = text.substring(start).trim()
    if (street.length < 2 || lead.isEmpty() || cut <= 0) return null
    return lead to street
}

/** The unit of a turn's distance as a word ("METRES"), or the unit as given. */
@Composable
private fun unitWord(unit: String): String = when (unit) {
    "m" -> stringResource(R.string.kinetic_metres)
    "km" -> stringResource(R.string.kinetic_kilometres)
    "mi" -> stringResource(R.string.kinetic_miles)
    "ft" -> stringResource(R.string.kinetic_feet)
    "yd" -> stringResource(R.string.kinetic_yards)
    else -> unit
}

/**
 * The next turn, as the mockup: a line of small facts (the time, the
 * outside temperature, the arrival and what is left), the distance huge in
 * the gradient and growing wider as the turn comes closer, its unit as a
 * spaced word, then how the turn goes in light type and the street big.
 * Without a route: the time huge, the next appointment or the usual drive
 * home under it. Tap opens the navigation app.
 */
@Composable
private fun KnDirections(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    when {
        !env.hasMediaAccess -> KnQuiet(
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> KnIdleHero(env, nav)
        else -> KnTurnHero(env, nav)
    }
}

@Composable
private fun KnTurnHero(env: SkinTileEnv, nav: NavState) {
    val context = env.context
    val metres = NowRules.metres(nav.distance)
    val target = knSqueezeFor(metres)
    val squeeze = animateFloatAsState(target, if (DashColors.Effects == DashEffects.NONE) snap() else tween(900), label = "squeeze")
    val split = remember(nav.instruction) { knSplitInstruction(nav.instruction) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .knEditing(env.editing)
            .knTap(!env.editing, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
            .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp)
    ) {
        val innerW = maxWidth
        val innerH = maxHeight
        val (value, unit) = nav.distanceParts
        val metaSize = (innerH.value * 0.045f).coerceIn(15f, 21f)
        val labelSize = (innerH.value * 0.062f).coerceIn(14f, 30f)
        val leadSize = (innerH.value * 0.08f).coerceIn(16f, 40f)
        val streetSize = min(innerH.value * 0.15f, 76f).coerceAtLeast(22f)
        val meta = innerH >= 260.dp
        val lead = split != null && innerH >= 200.dp
        val street = split?.second ?: nav.instruction
        Column {
            if (meta) {
                KnMetaLine(nav, metaSize)
                Spacer(Modifier.height(4.dp))
            }
            if (value.isNotEmpty()) {
                val used = (if (meta) metaSize * 1.25f + 4f else 0f) + labelSize * 1.3f + (if (lead) leadSize * 1.2f else 0f) + streetSize * 1.2f + 14f
                val heroSize = knFit(knTemplate(value), KnFace.HERO, innerW / KN_SQUEEZE_NEAR, (innerH.value - used).coerceAtLeast(40f).dp, 40f, 560f)
                KnHeroNumber(value, heroSize, squeeze, KN_SQUEEZE_NEAR, Modifier.padding(top = 2.dp))
                if (unit.isNotEmpty()) KnLabel(unitWord(unit), Modifier.padding(start = 4.dp), size = labelSize)
                Spacer(Modifier.height(6.dp))
                if (lead) KnLine(split!!.first, knText(leadSize, DashColors.TextSecondary, KnFace.WIDE_LIGHT))
                KnLine(street, knText(streetSize, DashColors.TextPrimary, KnFace.COND_BOLD))
            } else {
                // No distance given: the sentence itself is the hero.
                Spacer(Modifier.weight(1f))
                if (lead) KnLine(split!!.first, knText(leadSize * 1.2f, DashColors.TextSecondary, KnFace.WIDE_LIGHT))
                KnLine(street, knText(min(streetSize * 1.5f, 96f), DashColors.TextPrimary, KnFace.COND_BOLD), maxLines = 2)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The line of small facts over the hero: the time and the outside
 * temperature, then the arrival and what is left of the route, the figures
 * in the text's own ink and their words muted.
 */
@Composable
private fun KnMetaLine(nav: NavState?, size: Float) {
    val units = LocalUnits.current
    val now = rememberNow(60_000L)
    val timeFmt = rememberDateFormat(units.timePattern())
    val temp = rememberOutsideTempC()
    val arrivalWord = stringResource(R.string.kinetic_arrival, "%s")
    val strong = DashColors.TextPrimary
    val text = remember(now, temp, nav?.eta, arrivalWord, strong, units) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = strong, fontFamily = KnCondBold)) { append(timeFmt.format(now)) }
            if (temp != null) { append(GAP); append("${units.temp(temp)}°") }
            nav?.etaParts?.forEach { part ->
                append(GAP)
                if (KN_CLOCK.matches(part)) {
                    val (before, after) = arrivalWord.split("%s").let { it.first() to it.getOrElse(1) { "" } }
                    append(before)
                    withStyle(SpanStyle(color = strong, fontFamily = KnCondBold)) { append(part) }
                    append(after)
                } else append(part)
            }
        }
    }
    Text(text, style = knText(size, DashColors.Muted, KnFace.COND_MEDIUM), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

private const val GAP = "     "

/**
 * No route: the time is the hero, in the gradient, over the date; under it
 * the next thing worth knowing, the next appointment of the coming hours,
 * else the usual time home if the car is away from home, else an invitation
 * to start one.
 */
@Composable
private fun KnIdleHero(env: SkinTileEnv, nav: NavState) {
    val context = env.context
    val units = LocalUnits.current
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat(KN_LONG_DATE, best = true)
    val agenda = rememberAgendaSource()
    val homeAt = rememberHomeArrival()
    val event = agenda.events.firstOrNull { !it.allDay && it.begin > now.time && it.begin - now.time < 12 * 3_600_000L }
    val squeeze = remember { mutableFloatStateOf(0.64f) }
    val (leadText, mainText) = when {
        event != null -> stringResource(R.string.kinetic_next) to "${event.title.ifBlank { stringResource(R.string.info_agenda_no_title) }}  ${units.time(Date(event.begin))}"
        homeAt != null -> stringResource(R.string.kinetic_usual_drive) to stringResource(R.string.kinetic_home_at, homeAt)
        else -> stringResource(R.string.info_directions_no_route) to stringResource(R.string.kinetic_where_to)
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .knEditing(env.editing)
            .knTap(!env.editing, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
            .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp)
    ) {
        val innerW = maxWidth
        val innerH = maxHeight
        val metaSize = (innerH.value * 0.045f).coerceIn(15f, 21f)
        val labelSize = (innerH.value * 0.062f).coerceIn(14f, 30f)
        val leadSize = (innerH.value * 0.08f).coerceIn(16f, 40f)
        val mainSize = min(innerH.value * 0.14f, 72f).coerceAtLeast(22f)
        val meta = innerH >= 260.dp
        val lead = innerH >= 200.dp
        val timeText = timeFmt.format(now)
        val amPm = units.amPm(now, locale)
        val used = (if (meta) metaSize * 1.25f + 4f else 0f) + labelSize * 1.3f + (if (lead) leadSize * 1.2f else 0f) + mainSize * 1.2f + 14f
        val heroSize = knFit(knTemplate(timeText), KnFace.HERO, innerW / squeeze.floatValue, (innerH.value - used).coerceAtLeast(40f).dp, 40f, 520f)
        Column {
            if (meta) {
                KnMetaLine(null, metaSize)
                Spacer(Modifier.height(4.dp))
            }
            KnHeroNumber(timeText, heroSize, squeeze, squeeze.floatValue, Modifier.padding(top = 2.dp))
            KnLabel(
                listOfNotNull(amPm, dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }).joinToString(DOT),
                Modifier.padding(start = 4.dp), size = labelSize
            )
            Spacer(Modifier.height(6.dp))
            if (lead) KnLine(leadText, knText(leadSize, DashColors.TextSecondary, KnFace.WIDE_LIGHT))
            KnLine(mainText, knText(mainSize, DashColors.TextPrimary, KnFace.COND_BOLD))
        }
    }
}

/** Skeleton of weekday, day and month ("Tuesday 13 October"), in the locale's order. */
private const val KN_LONG_DATE = "EEEEdMMMM"

/**
 * When the car would be home if it left now, by the usual length of the
 * drives that ended there (DriveLog); null without a home, without such
 * drives, or with the car within a kilometre of it.
 */
@Composable
private fun rememberHomeArrival(): String? {
    val units = LocalUnits.current
    val places by PlacesStore.places.collectAsState()
    val drives by DriveLog.drives.collectAsState()
    val here by rememberLocationCell(100)
    val now = rememberWallClock(60_000L).longValue
    val home = places.home ?: return null
    val avg = remember(drives, home) { Commutes.to(drives, home.lat, home.lng).average } ?: return null
    val at = here ?: return null
    if (home.distanceM(at.latitude, at.longitude) < 1_000.0) return null
    return remember(now / 60_000L, avg, units) { units.time(Date(now + avg * 60_000L)) }
}

// --- Speed --------------------------------------------------------------------------

/**
 * Speed: the figure huge, wide and outlined (its letters drawn as their
 * edges only), in the top right corner, with "KM/H · LIMIT 50" in spaced
 * capitals under it. Over the limit the outline turns amber and fills
 * faintly. Without a speed: a muted dash, and a tap connects the adapter.
 */
@Composable
private fun KnSpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    val speed = rememberSpeedKmh()
    val limit by SpeedLimit.limit.collectAsState()
    val units = LocalUnits.current
    val over = speedOver(speed)
    val ink = when {
        speed == null -> DashColors.Muted
        over -> DashColors.Warning
        else -> DashColors.TextPrimary
    }
    val canConnect = speed == null && env.obdConnection.isIdle && !env.editing
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(R.string.info_speed_no_signal)
    val limitWord = limit?.let { stringResource(R.string.kinetic_limit, units.speed(it)) }
    val label = listOfNotNull(units.speedUnit, if (speed == null) stringResource(R.string.info_speed_no_signal) else limitWord).joinToString(DOT)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .knEditing(env.editing)
            .knTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        val labelSize = (maxHeight.value * 0.075f).coerceIn(14f, 22f)
        val numSize = knFit("188", KnFace.OUTLINE, maxWidth, maxHeight - (labelSize * 1.5f + 8f).dp, 36f, 300f)
        val stroke = with(LocalDensity.current) { (numSize * 0.011f).coerceIn(1.5f, 3f).dp.toPx() }
        val text = speed?.let { units.speed(it).toString() } ?: "--"
        val style = knText(numSize, ink, KnFace.OUTLINE)
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            Box {
                if (over) Text(text, style = style.copy(color = ink.copy(alpha = 0.22f)), maxLines = 1, softWrap = false)
                Text(text, style = style.copy(drawStyle = Stroke(stroke)), maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(6.dp))
            KnLabel(label, size = labelSize, color = if (over) DashColors.Warning else DashColors.Muted, align = TextAlign.End)
        }
    }
}

// --- Telemetry ----------------------------------------------------------------------

/**
 * The engine in words: the revs huge and narrow with "R/MIN · GEAR 3" over
 * them, then the coolant, the battery and the load as labelled figures where
 * they fit. Without the link: its state as a big word and the way to connect.
 */
@Composable
private fun KnTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val units = LocalUnits.current
    val gear by rememberGear()
    // Whatever the car answers is shown, adapter or not.
    val g = rememberPanelGauges(K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    val live = state == ObdConnectionState.CONNECTED || g.any || gear.rpm != null
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .knEditing(env.editing)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        val readSize = (min(maxWidth.value / 14f, maxHeight.value * 0.12f)).coerceIn(18f, 34f)
        if (!live) {
            Column(Modifier.align(Alignment.CenterStart)) {
                KnLabel(stringResource(R.string.dash_obd_short))
                KnLine(
                    stringResource(if (state == ObdConnectionState.CONNECTING) R.string.horizon_obd_connecting else obdStatusLabelRes(state)),
                    knText((readSize * 1.5f).coerceAtMost(52f), DashColors.TextPrimary, KnFace.COND_BOLD), maxLines = 2
                )
                if (state != ObdConnectionState.CONNECTING) {
                    Spacer(Modifier.height(12.dp))
                    KnFitRow(10.dp) {
                        KnPill(stringResource(R.string.horizon_connect_obd), filled = true, enabled = !env.editing, onClick = env.onConnectObd)
                        if (state == ObdConnectionState.ERROR) KnPill(stringResource(R.string.tape_cd_choose_adapter), filled = false, enabled = !env.editing, onClick = env.onPickDevice)
                    }
                }
            }
            return@BoxWithConstraints
        }
        val rpm = gear.rpm
        val rpmText = rpm?.let { groupThousands(it) } ?: "--"
        val labelSize = (maxHeight.value * 0.06f).coerceIn(14f, 20f)
        val numSize = knFit("8 888", KnFace.HERO, maxWidth / 0.7f, maxHeight - (labelSize * 1.4f + readSize * 2.2f + 20f).dp, 36f, 260f, squeeze = 1f)
        Column {
            KnLabel(listOf(stringResource(R.string.kinetic_rpm), stringResource(R.string.kinetic_gear, gearMarkKn(gear.gear))).joinToString(DOT), size = labelSize)
            KnLine(rpmText, knText(numSize, DashColors.TextPrimary, KnFace.HERO, squeeze = 0.7f), overflow = TextOverflow.Clip)
            Spacer(Modifier.weight(1f))
            KnFitRow(28.dp) {
                g.coolantTempC?.let { KnReadout(stringResource(R.string.kinetic_coolant), "${units.temp(it)}°", it >= COOLANT_WARNING_C, readSize) }
                g.voltage?.let { KnReadout(stringResource(R.string.kinetic_battery), String.format(Locale.getDefault(), "%.1f V", it), it !in BATTERY_OK_V, readSize) }
                g.engineLoadPct?.let { KnReadout(stringResource(R.string.kinetic_load), "$it %", false, readSize) }
            }
        }
    }
}

/** The gear's mark: R, 1 to 6, N while free-wheeling, a dash when it can't be told. */
private fun gearMarkKn(gear: Gear): String = when (gear) {
    Gear.Reverse -> "R"
    is Gear.Forward -> gear.n.toString()
    Gear.Free -> "N"
    Gear.Park -> "P"
    Gear.Drive -> "D"
    Gear.Stopped, Gear.Unknown -> "–"
}

// --- Media --------------------------------------------------------------------------

/**
 * Music in words: NOW PLAYING over the song in big condensed type, who sings
 * it in light type, a hairline of progress and the keys bare, play / pause a
 * round filled in the text's ink. A low tile keeps one row.
 */
@Composable
private fun KnMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        KnQuiet(
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    val label = listOfNotNull(
        stringResource(if (ms.isPlaying) R.string.kinetic_now_playing else R.string.kinetic_paused),
        ms.station.takeIf { it.isNotBlank() }
    ).joinToString(DOT)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .knEditing(env.editing)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        val compact = maxHeight < 170.dp
        val boxW = maxWidth
        val boxH = maxHeight
        if (compact) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    KnLine(title, knText((boxH.value * 0.24f).coerceIn(18f, 30f), DashColors.TextPrimary, KnFace.COND_BOLD))
                    KnLine(sub, knText(16f, DashColors.TextSecondary, KnFace.WIDE_LIGHT))
                    if (ms.durationMs > 0L) {
                        Spacer(Modifier.height(8.dp))
                        KnProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.width(12.dp))
                KnKeys(env, prev = boxW >= 420.dp)
            }
            return@BoxWithConstraints
        }
        val titleSize = min(maxHeight.value * 0.17f, maxWidth.value / 9f).coerceIn(22f, 64f)
        Column(Modifier.fillMaxSize()) {
            KnLabel(label, size = (boxH.value * 0.055f).coerceIn(14f, 18f))
            Spacer(Modifier.height(6.dp))
            KnLine(title, knText(titleSize, DashColors.TextPrimary, KnFace.COND_BOLD), maxLines = 2)
            KnLine(sub, knText((titleSize * 0.46f).coerceIn(16f, 30f), DashColors.TextSecondary, KnFace.WIDE_LIGHT))
            Spacer(Modifier.weight(1f))
            if (ms.durationMs > 0L) {
                KnProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }
            KnKeys(env, prev = true)
        }
    }
}

/** Previous (when [prev]), play / pause as a filled round, next. */
@Composable
private fun KnKeys(env: SkinTileEnv, prev: Boolean) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val c = env.mediaController
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (prev) KnMediaKey(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), false, enabled) { c.previous() }
        KnMediaKey(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play), true, enabled
        ) { c.playPause() }
        KnMediaKey(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), false, enabled) { c.next() }
    }
}

/** A transport key, [DashSize.TouchPrimary] tall: [main] a round filled in the text's ink, else the icon bare. */
@Composable
private fun KnMediaKey(icon: ImageVector, label: String, main: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val ink = DashColors.TextPrimary
    Box(
        modifier = Modifier
            .size(width = if (main) 84.dp else 64.dp, height = DashSize.TouchPrimary)
            .then(if (main) Modifier.cachedDraw(ink) { onDrawBehind { drawRoundRect(ink, cornerRadius = CornerRadius(size.height / 2f)) } } else Modifier)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (main) DashColors.Background else ink, modifier = Modifier.size(32.dp))
    }
}

/** The progress as a hairline in the text's ink over a fainter one; in its own scope, so only it follows the position. */
@Composable
private fun KnProgress(ms: MediaState, controller: CarMediaController, modifier: Modifier) {
    val positionMs = rememberMediaPosition(ms, controller)
    val fraction = if (ms.durationMs > 0L) (positionMs.toFloat() / ms.durationMs).coerceIn(0f, 1f) else 0f
    val on = DashColors.TextPrimary
    val off = DashColors.Line
    Spacer(
        modifier.height(6.dp).cachedDraw(fraction, on, off) {
            val y = size.height / 2f
            val bar = 2.dp.toPx()
            onDrawBehind {
                drawLine(off, Offset(0f, y), Offset(size.width, y), bar)
                if (fraction > 0f) drawLine(on, Offset(0f, y), Offset(size.width * fraction, y), bar, StrokeCap.Round)
            }
        }
    )
}

// --- Clock and weather ----------------------------------------------------------------

/** Clock: the time huge, heavy and narrow, the date in spaced capitals under it. Tap opens the alarms. */
@Composable
private fun KnClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat(KN_LONG_DATE, best = true)
    val context = env.context
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .knEditing(env.editing)
            .knTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val labelSize = (min(maxWidth.value / 18f, maxHeight.value * 0.1f)).coerceIn(14f, 24f)
        val timeText = timeFmt.format(now)
        val amPm = units.amPm(now, locale)
        val timeSize = knFit(knTemplate(timeText), KnFace.HERO, maxWidth * (if (amPm != null) 0.85f else 1f) / 0.72f, maxHeight - (labelSize * 1.4f + 4f).dp, 28f, 320f)
        Column {
            Row {
                KnLine(timeText, knText(timeSize, DashColors.TextPrimary, KnFace.HERO, squeeze = 0.72f), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width(6.dp))
                    KnLine(amPm, knText((timeSize * 0.2f).coerceAtLeast(15f), DashColors.Muted, KnFace.COND_MEDIUM, caps = true), Modifier.alignByBaseline())
                }
            }
            KnLabel(dateFmt.format(now), size = labelSize)
        }
    }
}

/**
 * Weather: the temperature huge and narrow with the sky in big words beside
 * it, the feel and the wind in spaced capitals, rain to come in the accent's
 * ink. While there is nothing yet, it says why (no GPS, offline).
 */
@Composable
private fun KnWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .knEditing(env.editing)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (w == null) {
            val reason = when (wait) {
                WeatherWait.LOADING -> R.string.horizon_loading
                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
            }
            val dashSize = knFit("88°", KnFace.HERO, maxWidth * 0.6f / 0.72f, maxHeight * 0.55f, 28f, 160f)
            Column {
                KnLine("--°", knText(dashSize, DashColors.Muted, KnFace.HERO, squeeze = 0.72f))
                KnLabel(stringResource(reason))
            }
            return@BoxWithConstraints
        }
        val rain = when {
            w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs -> stringResource(R.string.canvas_rain_until, timeFmt.format(Date(w.rainUntilMs)))
            w.rainFromMs != null -> stringResource(R.string.canvas_rain_from, timeFmt.format(Date(w.rainFromMs)))
            else -> null
        }
        val labelSize = (maxHeight.value * 0.07f).coerceIn(14f, 20f)
        val extra = maxHeight >= 150.dp
        val temp = "${units.temp(w.tempC)}°"
        val below = labelSize * 1.4f + (if (extra && rain != null) labelSize * 1.6f else 0f) + 6f
        val tempSize = knFit(knTemplate(temp), KnFace.HERO, maxWidth * 0.5f / 0.72f, maxHeight - below.dp, 28f, 260f)
        val condSize = (tempSize * 0.3f).coerceIn(18f, 48f)
        val feels = stringResource(R.string.horizon_feels, units.temp(w.feelsC))
        val wind = stringResource(if (units.imperial) R.string.units_horizon_wind_mph else R.string.horizon_wind, units.speed(w.windKmh))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KnLine(temp, knText(tempSize, DashColors.TextPrimary, KnFace.HERO, squeeze = 0.72f), overflow = TextOverflow.Clip)
                Spacer(Modifier.width(14.dp))
                KnLine(w.condition, knText(condSize, DashColors.TextSecondary, KnFace.COND_BOLD), Modifier.weight(1f), maxLines = 2)
            }
            KnLabel(if (extra) "$feels$DOT$wind" else feels, size = labelSize)
            if (extra && rain != null) {
                Spacer(Modifier.height(4.dp))
                KnLine(rain, knText(labelSize * 1.1f, DashColors.AccentInk, KnFace.COND_BOLD))
            }
        }
    }
}

// --- Fuel and range ---------------------------------------------------------------------

/**
 * Fuel and range. With the room, the car strip of the mockup: the driver's
 * car as a dark silhouette lit at its edges, its lamps glowing, a light
 * running along its side and streaks of speed behind it while parked, the
 * range as a big figure beside it. Otherwise the range as type alone. Fuel
 * not learned yet, on a small tile: the standard tile, which explains how
 * to learn it, on the skin's panel.
 */
@Composable
private fun KnRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val strip = maxWidth >= 460.dp && maxWidth >= maxHeight * 2f && maxHeight >= 100.dp
        val stage = !strip && maxWidth >= 300.dp && maxHeight >= 220.dp
        when {
            strip || stage -> KnCarStrip(fuel, env, tall = stage, Modifier.fillMaxSize().knEditing(env.editing))
            fuel == null -> Box(Modifier.fillMaxSize().kineticFallbackPanel()) { StandardSkinnedTile(item, env) }
            else -> KnRangeType(fuel, Modifier.fillMaxSize().knEditing(env.editing).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

/** The range as type: RANGE, the figure huge and narrow with its unit, the fuel left in spaced capitals. */
@Composable
private fun KnRangeType(fuel: FuelInfo, modifier: Modifier) {
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val labelSize = (maxHeight.value * 0.08f).coerceIn(14f, 20f)
        val numSize = knFit("888", KnFace.HERO, maxWidth * 0.7f / 0.7f, maxHeight - (labelSize * 2.8f + 8f).dp, 28f, 220f)
        Column {
            KnLabel(stringResource(R.string.kinetic_range), size = labelSize)
            Row {
                KnLine(units.distance(fuel.rangeKm).toString(), knText(numSize, if (low) DashColors.Warning else DashColors.TextPrimary, KnFace.HERO, squeeze = 0.7f), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                Spacer(Modifier.width(8.dp))
                KnLine(units.distanceUnit, knText((numSize * 0.22f).coerceAtLeast(15f), DashColors.Muted, KnFace.COND_MEDIUM, caps = true), Modifier.alignByBaseline(), caps = true)
            }
            KnLabel(stringResource(R.string.kinetic_fuel_left, fuel.percent), size = labelSize, color = if (low) DashColors.Warning else DashColors.Muted)
        }
    }
}

/**
 * The range as the strip says it: the figure huge and narrow, its unit and
 * the fuel left in spaced capitals under it, aligned to [end] or the start.
 * Fuel not known: a muted dash and the word.
 */
@Composable
internal fun KnRangeReadout(fuel: FuelInfo?, height: Dp, end: Boolean, modifier: Modifier = Modifier) {
    val units = LocalUnits.current
    val low = fuel != null && fuel.percent <= SKIN_LOW_FUEL_PCT
    val labelSize = (height.value * 0.1f).coerceIn(14f, 19f)
    val numSize = (height.value * 0.55f).coerceIn(30f, 140f)
    val ink = when {
        fuel == null -> DashColors.Muted
        low -> DashColors.Warning
        else -> DashColors.TextPrimary
    }
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Row {
            KnLine(fuel?.let { units.distance(it.rangeKm).toString() } ?: "--", knText(numSize, ink, KnFace.HERO, squeeze = 0.7f), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
            Spacer(Modifier.width(8.dp))
            KnLine(units.distanceUnit, knText((numSize * 0.24f).coerceAtLeast(15f), DashColors.Muted, KnFace.COND_MEDIUM, caps = true), Modifier.alignByBaseline(), caps = true)
        }
        KnLabel(
            listOfNotNull(stringResource(R.string.kinetic_range), fuel?.let { stringResource(R.string.kinetic_fuel_left, it.percent) }).joinToString(DOT),
            size = labelSize, color = if (low) DashColors.Warning else DashColors.Muted, align = if (end) TextAlign.End else null
        )
    }
}

// --- Apps ---------------------------------------------------------------------------

/** App shortcut: the app's icon and its name in spaced capitals, side by side on a wide tile, stacked on a square one. No box. */
@Composable
private fun KnApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .knEditing(env.editing)
            .clip(RoundedCornerShape(24.dp))
            .knTap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
        if (row) {
            val icon = (maxHeight.value * 0.5f).coerceIn(30f, 72f).dp
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                KnAppGlyph(app, icon)
                Spacer(Modifier.width(14.dp))
                KnLine(label, knText((icon.value * 0.36f).coerceIn(16f, 26f), DashColors.TextPrimary, KnFace.COND_BOLD, caps = true), Modifier.weight(1f, fill = false), caps = true)
            }
        } else {
            val icon = (min(maxWidth.value, maxHeight.value) * 0.44f).coerceIn(30f, 96f).dp
            val named = maxHeight >= 96.dp
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                KnAppGlyph(app, icon)
                if (named) {
                    Spacer(Modifier.height(8.dp))
                    KnLine(label, knText((icon.value * 0.24f).coerceIn(14f, 20f), DashColors.TextPrimary, KnFace.COND_BOLD, caps = true), align = TextAlign.Center, caps = true)
                }
            }
        }
    }
}

@Composable
private fun KnAppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.8f))
}

/**
 * Launch bar: the apps in a row with no keys around them, each its icon and,
 * where there is room, its name in spaced capitals, hairlines between them,
 * and the pencil bare at the end. Apps that would get less than 64 dp are
 * left out.
 */
@Composable
private fun KnLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().knEditing(env.editing)) {
        val edit = 64.dp
        val room = maxWidth - edit
        val fit = (room.value / 64f).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else room.value / pkgs.size
        val labels = per >= 150f
        val icon = (maxHeight.value * 0.46f).coerceIn(24f, 48f).dp
        val line = DashColors.Line
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                KnLine(stringResource(R.string.kinetic_launch_bar_empty), knText(17f, DashColors.TextSecondary, KnFace.WIDE_LIGHT), Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2)
            } else {
                pkgs.forEachIndexed { i, pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .widthIn(min = 64.dp)
                            .cachedDraw(line, i) {
                                onDrawBehind { if (i > 0) drawLine(line, Offset(0f, size.height * 0.25f), Offset(0f, size.height * 0.75f), density) }
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .knTap(!env.editing, stringResource(R.string.horizon_open_app, name)) { env.onLaunchApp(pkg) }
                            .semantics(mergeDescendants = true) { contentDescription = name }
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        KnAppGlyph(app, icon)
                        if (labels) {
                            Spacer(Modifier.width(12.dp))
                            KnLine(name, knText(16f, DashColors.TextPrimary, KnFace.COND_BOLD, caps = true), Modifier.weight(1f, fill = false), caps = true)
                        }
                    }
                }
            }
            val tap = rememberTapFeedback()
            val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
            Box(
                Modifier
                    .size(edit)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = editLabel, role = Role.Button) { tap(); env.onEditLaunchBar() }
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(22.dp))
            }
        }
    }
}
