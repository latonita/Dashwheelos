package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/*
 * Spatial skin, the windows (the look itself is in SpatialSkin.kt): each
 * main widget is a glass window turned toward the middle of the screen
 * ([spatialTilt]). The turn's window has an ornament under it, a glass
 * capsule of tabs that switch it between the next turn, the arrival and the
 * fuel; the music's window has the cover, the song and round glass keys with
 * a white play; the speed floats with no box. Clock, weather, engine and
 * range are smaller windows.
 */

private const val DOT = " · "

/** How long the turn's window stays on another tab before it comes back to the turn by itself. */
private const val TAB_BACK_MS = 20_000L

/** Room around a window's content, following its smaller side. */
private fun padFor(w: Dp, h: Dp): Dp = (min(w.value, h.value) * 0.075f).coerceIn(14f, 28f).dp

/**
 * Spatial renderer for app shortcuts, launch bars and the main widgets, each
 * turned toward the middle by its place on the grid. Anything else keeps its
 * standard renderer.
 */
@Composable
internal fun SpatialTile(item: DashboardItem, env: SkinTileEnv) {
    Box(Modifier.fillMaxSize().spatialTilt(item, env.editing)) {
        when (item) {
            is DashboardItem.AppShortcut -> SpApp(item, env)
            is DashboardItem.LaunchBar -> SpLaunchBar(item, env)
            is DashboardItem.BuiltinWidget -> when (item.kind) {
                BuiltinKind.TELEMETRY -> SpTelemetry(env)
                BuiltinKind.SPEED_HUD -> SpSpeed(env)
                BuiltinKind.MEDIA -> SpMedia(env)
                BuiltinKind.NAVIGATION -> SpNavigation(item, env)
                BuiltinKind.CLOCK -> SpClock(env)
                BuiltinKind.WEATHER -> SpWeather(env)
                BuiltinKind.RANGE -> SpRange(item, env)
                else -> StandardSkinnedTile(item, env)
            }
            else -> StandardSkinnedTile(item, env)
        }
    }
}

// --- Small pieces -----------------------------------------------------------------

/** A spaced caps label ("FUEL", "R/MIN"). */
@Composable
private fun SpLabel(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    SpLine(text, spText(14f, DashColors.TextSecondary, FontWeight.SemiBold, caps = true), modifier, align = align, caps = true)
}

/**
 * A rounded square of tinted glass with a glyph in it: the turn's arrow chip
 * of the mockup (cyan glass, a light catch along its top), and the quiet
 * states' icon.
 */
@Composable
private fun SpChip(size: Dp, content: @Composable () -> Unit) {
    val light = DashColors.Light
    val accent = DashColors.Accent
    val plain = DashColors.Effects == DashEffects.NONE
    Box(
        Modifier
            .size(size)
            .cachedDraw(light, accent, plain) {
                val r = CornerRadius(this.size.minDimension * 0.31f)
                val fill = accent.copy(alpha = if (light) 0.16f else if (plain) 0.45f else 0.28f)
                val catch = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = if (light) 0.9f else 0.55f), Color.Transparent), r.x * 0.5f, this.size.width - r.x * 0.5f)
                onDrawBehind {
                    drawRoundRect(fill, cornerRadius = r)
                    if (!plain) drawLine(catch, Offset(r.x * 0.5f, 1.5f * density), Offset(this.size.width - r.x * 0.5f, 1.5f * density), 1.2f * density)
                }
            },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** The arrow and glyph ink on the cyan chip: white at night, the deep accent by day. */
private val chipInk: Color get() = if (DashColors.Light) DashColors.AccentInk else Color.White

/**
 * A quiet state on its window: an icon in its chip, a title and one hint; the
 * hint is in the accent's ink when a tap does something, and the whole window
 * is the target.
 */
@Composable
private fun SpEmpty(icon: ImageVector, title: String, hint: String, editing: Boolean, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .spatialGlass(editing = editing)
            .spTap(onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val wide = maxWidth >= maxHeight * 1.3f
        val roomy = maxHeight >= 200.dp
        val iconD = min(maxHeight.value * 0.3f, maxWidth.value * 0.22f).coerceIn(44f, 84f).dp
        val textW = if (wide) maxWidth - pad * 2 - iconD - 18.dp else maxWidth - pad * 2
        val titleSize = min(maxHeight.value * 0.12f, textW.value / (title.length * 0.5f + 1f)).coerceIn(17f, 28f)
        val hintSize = (titleSize * 0.72f).coerceIn(14f, 19f)
        val words: @Composable () -> Unit = {
            Column {
                SpLine(title, spText(titleSize, DashColors.TextPrimary, FontWeight.SemiBold), maxLines = 2)
                Spacer(Modifier.height(4.dp))
                SpLine(hint, spText(hintSize, if (onTap != null) DashColors.AccentInk else DashColors.TextSecondary), maxLines = 2)
            }
        }
        val glyph: @Composable () -> Unit = {
            SpChip(iconD) { Icon(icon, contentDescription = null, tint = chipInk, modifier = Modifier.size(iconD * 0.5f)) }
        }
        if (wide) {
            Row(Modifier.padding(pad), verticalAlignment = Alignment.CenterVertically) {
                glyph()
                Spacer(Modifier.width(18.dp))
                words()
            }
        } else {
            Column(Modifier.padding(pad)) {
                if (roomy) {
                    glyph()
                    Spacer(Modifier.height(14.dp))
                }
                words()
            }
        }
    }
}

/** Children in a row, in order, each only if it fits whole in the room left (dropped, never cut), [gap] apart. */
@Composable
private fun FitRow(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
            placed.forEachIndexed { i, p -> p?.placeRelative(xs[i], (height - p.height) / 2) }
        }
    }
}

/** A round glass key, [size] across: [main] is the white play of the mockup (deep ink by day), the rest clear glass. */
@Composable
private fun SpRoundKey(icon: ImageVector, label: String, size: Dp, main: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val light = DashColors.Light
    val fill = if (light) DashColors.TextPrimary else Color.White
    val ink = if (main) (if (light) Color.White else Color(0xFF0A0D16)) else DashColors.TextPrimary
    val base = Modifier.size(size)
    Box(
        modifier = (if (main) base.cachedDraw(fill, light) {
            val shadow = if (light) Color(0x221A2440) else Color(0x55000000)
            onDrawBehind {
                drawCircle(shadow, this.size.minDimension / 2f + 2f * density, center.copy(y = center.y + 3f * density))
                drawCircle(fill)
            }
        }.clip(CircleShape) else base.spatialGlass(round = true, lift = 0f))
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.44f))
    }
}

/** A pill button, at least 56 dp tall: [filled] in the skin's cyan to violet for the one thing to do, else glass. */
@Composable
private fun SpPillButton(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val from = DashColors.Accent
    val to = DashColors.Secondary
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .then(
                if (filled) Modifier
                    .cachedDraw(from, to) {
                        val brush = Brush.linearGradient(listOf(from, to), Offset.Zero, Offset(size.width, size.height))
                        onDrawBehind { drawRoundRect(brush, cornerRadius = CornerRadius(size.height / 2f)) }
                    }
                    .clip(RoundedCornerShape(50))
                else Modifier.spatialGlass(round = true)
            )
            .clickable(enabled = enabled, role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        SpLine(text, spText(18f, if (filled) Color.White else DashColors.TextPrimary, FontWeight.SemiBold))
    }
}

/** The gear's mark: R, 1 to 6, N while free-wheeling, a dash when it can't be told. */
private fun gearMark(gear: Gear): String = when (gear) {
    Gear.Reverse -> "R"
    is Gear.Forward -> gear.n.toString()
    Gear.Free -> "N"
    Gear.Park -> "P"
    Gear.Drive -> "D"
    Gear.Stopped, Gear.Unknown -> "–"
}

// --- Navigation: the turn's window and its ornament --------------------------------

/** What the turn's window shows; its ornament switches between them. */
private enum class SpNavTab { TURN, ARRIVAL, FUEL }

private val CLOCK_PART = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")

/**
 * The trip left in km, from the arrival line's distance ("11 km", "6.4 mi",
 * "800 m"); null when the line says none.
 */
internal fun spatialTripKm(etaParts: List<String>): Double? {
    for (part in etaParts) {
        val m = NavState.DISTANCE.find(part) ?: continue
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
        return when (m.groupValues[2]) {
            "km" -> v
            "m" -> v / 1000.0
            "mi" -> v * 1.609344
            "ft" -> v * 0.0003048
            "yd" -> v * 0.0009144
            else -> null
        }
    }
    return null
}

/**
 * Whether the fuel's range covers the trip left with room to spare (a fifth
 * more, and 15 km at least, since a range is an estimate); null without either.
 */
internal fun spatialFuelReaches(rangeKm: Int?, tripKm: Double?): Boolean? {
    if (rangeKm == null || tripKm == null) return null
    return rangeKm >= max(tripKm * 1.2, tripKm + 15.0)
}

/**
 * The destination's name when guidance was started from here or read off the
 * navigation app (and not long ago): the part before the first comma, so an
 * address reads as its street. Null when it is not known.
 */
@Composable
private fun rememberDestinationName(): String? {
    val target by NavHandoff.handedOff.collectAsState()
    val now = rememberWallClock(60_000L).longValue
    val t = target ?: return null
    if (now - t.at > OnMyWayText.DESTINATION_MS) return null
    return t.name.substringBefore(',').trim().takeIf { it.isNotEmpty() && it.length <= 28 }
}

/** "rain from 21:40" / "rain until 22:10" when the weather says rain in the coming hours; null when dry or unknown. */
@Composable
private fun rememberRainLine(): String? {
    val w = rememberWeather() ?: return null
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    val from = w.rainFromMs ?: return null
    val until = w.rainUntilMs
    return if (until != null && from <= nowMs) stringResource(R.string.spatial_rain_until, timeFmt.format(Date(until)))
    else stringResource(R.string.spatial_rain_from, timeFmt.format(Date(from)))
}

/**
 * Navigation: the turn's glass window, and under it (when the tile has the
 * room) its ornament, a small glass capsule of tabs: Turn shows the arrow
 * chip, the distance large, the street and a line with the arrival, the
 * distance left and rain to come; Arrival shows the arrival time large with
 * the destination; Fuel shows the range and whether it covers the trip (only
 * when the fuel is known). Another tab comes back to Turn by itself after a
 * while. A tap on the window opens the navigation app. Without a route (or
 * without notification access) a quiet window says so and how to start.
 */
@Composable
private fun SpNavigation(item: DashboardItem, env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    when {
        !env.hasMediaAccess -> SpEmpty(
            Icons.Filled.NotificationsActive,
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> SpEmpty(
            Icons.Filled.Navigation,
            stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation), env.editing,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> SpRoute(nav, item, env)
    }
}

@Composable
private fun SpRoute(nav: NavState, item: DashboardItem, env: SkinTileEnv) {
    val context = env.context
    val fuel = rememberFuel()
    var picked by remember { mutableStateOf(SpNavTab.TURN) }
    LaunchedEffect(picked) {
        if (picked != SpNavTab.TURN) {
            delay(TAB_BACK_MS)
            picked = SpNavTab.TURN
        }
    }
    val tabs = if (fuel != null) SpNavTab.entries.toList() else listOf(SpNavTab.TURN, SpNavTab.ARRIVAL)
    val tab = if (picked in tabs) picked else SpNavTab.TURN
    val dest = rememberDestinationName()
    val rain = rememberRainLine()
    val arrivalWord = stringResource(R.string.spatial_arrival, "%s")
    val parts = nav.etaParts
    val arrival = parts.firstOrNull { CLOCK_PART.matches(it) }
    val distanceLeft = parts.firstOrNull { NavState.DISTANCE.containsMatchIn(it) && !CLOCK_PART.matches(it) }
    val duration = parts.firstOrNull { it != arrival && it != distanceLeft }
    val arrivalText = arrival?.let { if (dest != null) "$dest $it" else arrivalWord.replace("%s", it) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ornament = maxHeight >= 300.dp && maxWidth >= 300.dp && !env.editing
        val rightSide = spatialTiltSide(item.x, item.w, GRID_COLS) > 0
        Column(Modifier.fillMaxSize(), horizontalAlignment = if (rightSide) Alignment.End else Alignment.Start) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .spatialGlass(editing = env.editing)
                    .spTap(!env.editing, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
            ) {
                val pad = padFor(maxWidth, maxHeight)
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                Box(Modifier.fillMaxSize().padding(pad)) {
                    when (tab) {
                        SpNavTab.TURN -> TurnFace(nav, innerW, innerH, listOfNotNull(arrivalText, distanceLeft, rain, duration))
                        SpNavTab.ARRIVAL -> ArrivalFace(arrival ?: nav.eta, dest, listOfNotNull(distanceLeft, duration), rain, innerW, innerH)
                        SpNavTab.FUEL -> fuel?.let { FuelFace(it, spatialTripKm(parts), innerW, innerH) }
                    }
                }
            }
            if (ornament) {
                Spacer(Modifier.height(14.dp))
                SpOrnament(tabs, tab, Modifier.padding(horizontal = 30.dp)) { picked = it }
            }
        }
    }
}

/**
 * The ornament: a small glass capsule under the window with a tab per face,
 * the one shown lit. Tabs are 52 dp tall (wider touch around them).
 */
@Composable
private fun SpOrnament(tabs: List<SpNavTab>, current: SpNavTab, modifier: Modifier, onPick: (SpNavTab) -> Unit) {
    val tap = rememberTapFeedback()
    val light = DashColors.Light
    val on = if (light) DashColors.Accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.25f)
    Row(
        modifier = modifier
            .height(66.dp)
            .spatialGlass(round = true, lift = 0.5f)
            .padding(horizontal = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { t ->
            val label = stringResource(
                when (t) {
                    SpNavTab.TURN -> R.string.spatial_tab_turn
                    SpNavTab.ARRIVAL -> R.string.spatial_tab_arrival
                    SpNavTab.FUEL -> R.string.spatial_tab_fuel
                }
            )
            val isOn = t == current
            Box(
                Modifier
                    .height(52.dp)
                    .clip(RoundedCornerShape(50))
                    .then(if (isOn) Modifier.cachedDraw(on) { onDrawBehind { drawRoundRect(on, cornerRadius = CornerRadius(size.height / 2f)) } } else Modifier)
                    .clickable(role = Role.Tab) { if (!isOn) { tap(); onPick(t) } }
                    .semantics { selected = isOn }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                SpLine(label, spText(17f, if (isOn) DashColors.TextPrimary else DashColors.TextSecondary, FontWeight.SemiBold))
            }
        }
    }
}

/**
 * The turn as the mockup draws it: the arrow chip, "300 m" large and bold,
 * the street, then one small line with the arrival, the distance left, rain
 * to come and the time left, as much of it as fits. A low wide window puts
 * the chip beside the words.
 */
@Composable
private fun TurnFace(nav: NavState, innerW: Dp, innerH: Dp, line: List<String>) {
    val (value, unit) = nav.distanceParts
    val streetSize = min(innerW.value / 15f, innerH.value * 0.085f).coerceIn(17f, 25f)
    val lineSize = (streetSize * 0.74f).coerceIn(14f, 19f)
    val candidates = remember(line) { (line.size downTo 1).map { line.take(it).joinToString(DOT) } }
    val eta = if (line.isEmpty()) "" else spFirstFitting(candidates, lineSize, FontWeight.Normal, innerW)
    val sample = spTemplate(if (unit.isEmpty()) value.ifEmpty { "888" } else "$value $unit")
    val low = innerH < 210.dp
    if (low) {
        val chip = (innerH * 0.62f).coerceIn(44.dp, 84.dp)
        val textW = innerW - chip - 18.dp
        val distSize = spFit(sample, FontWeight.Bold, textW * 0.9f, innerH * 0.46f, 22f, 84f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            SpTurnChip(nav, chip)
            Spacer(Modifier.width(18.dp))
            TurnWords(nav, value, unit, distSize, streetSize, lineSize, eta, 1)
        }
    } else {
        val chip = (innerH * 0.24f).coerceIn(52.dp, 84.dp)
        val wordsH = innerH - chip - 14.dp - (streetSize * 1.3f + lineSize * 1.35f + 22f).dp
        val distSize = spFit(sample, FontWeight.Bold, innerW * 0.92f, wordsH, 24f, 96f)
        Column(Modifier.fillMaxSize()) {
            SpTurnChip(nav, chip)
            Spacer(Modifier.weight(1f))
            TurnWords(nav, value, unit, distSize, streetSize, lineSize, eta, if (innerH > 320.dp) 2 else 1)
        }
    }
}

@Composable
private fun TurnWords(nav: NavState, value: String, unit: String, distSize: Float, streetSize: Float, lineSize: Float, eta: String, maxLines: Int) {
    Column {
        if (value.isNotEmpty()) {
            Row {
                SpLine(value, spText(distSize, DashColors.TextPrimary, FontWeight.Bold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width((distSize * 0.1f).dp))
                    SpLine(unit, spText((distSize * 0.36f).coerceAtLeast(16f), DashColors.TextPrimary.copy(alpha = 0.8f), FontWeight.SemiBold), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(8.dp))
            SpLine(nav.instruction, spText(streetSize, DashColors.TextPrimary.copy(alpha = 0.92f)), maxLines = maxLines)
        } else {
            SpLine(nav.instruction, spText((streetSize * 1.3f).coerceAtMost(32f), DashColors.TextPrimary, FontWeight.SemiBold), maxLines = maxLines + 1)
        }
        if (eta.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SpLine(eta, spText(lineSize, DashColors.TextSecondary))
        }
    }
}

/** The arrow chip: the navigation's own glyph tinted when it sends one, else the mockup's arrow for the side the turn goes. */
@Composable
private fun SpTurnChip(nav: NavState, size: Dp) {
    val ink = chipInk
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    val side = nav.turnSide()
    SpChip(size) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = nav.instruction, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(ink), modifier = Modifier.fillMaxSize(0.56f))
        } else {
            Spacer(
                Modifier
                    .fillMaxSize(0.56f)
                    .semantics { contentDescription = nav.instruction }
                    .cachedDraw(side, ink) {
                        val s = this.size.minDimension / 24f
                        // The mockup's glyph in a 24 grid: up the stem, round the corner, out to the side, its head.
                        val path = Path().apply {
                            when (side) {
                                TurnSide.AHEAD -> {
                                    moveTo(12f * s, 21f * s); lineTo(12f * s, 4f * s)
                                    moveTo(7f * s, 9f * s); lineTo(12f * s, 4f * s); lineTo(17f * s, 9f * s)
                                }
                                else -> {
                                    val left = side == TurnSide.LEFT
                                    fun x(v: Float) = (if (left) v else 24f - v) * s
                                    moveTo(x(17f), 21f * s); lineTo(x(17f), 11f * s)
                                    cubicTo(x(17f), 9.3f * s, x(15.7f), 8f * s, x(14f), 8f * s)
                                    lineTo(x(5f), 8f * s)
                                    moveTo(x(9f), 4f * s); lineTo(x(5f), 8f * s); lineTo(x(9f), 12f * s)
                                }
                            }
                        }
                        val stroke = Stroke(2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        onDrawBehind { drawPath(path, ink, style = stroke) }
                    }
            )
        }
    }
}

/** Arrival: the time large and bold, the destination under it, then the distance and time left and rain to come. */
@Composable
private fun ArrivalFace(time: String, dest: String?, rest: List<String>, rain: String?, innerW: Dp, innerH: Dp) {
    val sub = (innerW.value / 15f).coerceIn(17f, 25f)
    val line = (sub * 0.76f).coerceIn(14f, 19f)
    val below = (sub * 1.3f + line * 1.35f * 2 + 40f).dp
    val size = spFit(spTemplate(time.ifEmpty { "88:88" }), FontWeight.Bold, innerW * 0.92f, (innerH - below).coerceAtLeast(30.dp), 26f, 104f)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        SpLabel(stringResource(R.string.spatial_tab_arrival))
        Spacer(Modifier.weight(1f))
        SpLine(time.ifEmpty { "--:--" }, spText(size, DashColors.TextPrimary, FontWeight.Bold, figure = true), overflow = TextOverflow.Clip)
        Spacer(Modifier.height(10.dp))
        if (dest != null) SpLine(dest, spText(sub, DashColors.TextPrimary.copy(alpha = 0.92f), FontWeight.Medium))
        if (rest.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SpLine(rest.joinToString(DOT), spText(line, DashColors.TextSecondary))
        }
        if (rain != null) SpLine(rain, spText(line, DashColors.AccentInk, FontWeight.Medium))
    }
}

/** Fuel: the range large and bold, the level, and whether it covers the trip left when both are known. */
@Composable
private fun FuelFace(fuel: FuelInfo, tripKm: Double?, innerW: Dp, innerH: Dp) {
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    val reaches = spatialFuelReaches(fuel.rangeKm, tripKm)
    val sub = (innerW.value / 16f).coerceIn(16f, 23f)
    val value = units.distance(fuel.rangeKm).toString()
    val below = (sub * 1.35f * 2 + 50f).dp
    val size = spFit(spTemplate("$value ${units.distanceUnit}"), FontWeight.Bold, innerW * 0.92f, (innerH - below).coerceAtLeast(30.dp), 26f, 104f)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpLabel(stringResource(R.string.spatial_range), Modifier.weight(1f))
            SpLine("${fuel.percent} %", spText(17f, if (low) DashColors.Warning else DashColors.TextSecondary, FontWeight.SemiBold))
        }
        Spacer(Modifier.weight(1f))
        Row {
            SpLine(value, spText(size, if (low) DashColors.Warning else DashColors.TextPrimary, FontWeight.Bold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
            Spacer(Modifier.width((size * 0.1f).dp))
            SpLine(units.distanceUnit, spText((size * 0.36f).coerceAtLeast(16f), DashColors.TextPrimary.copy(alpha = 0.8f), FontWeight.SemiBold), Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(12.dp))
        SpFuelBar(fuel.percent, low, Modifier.fillMaxWidth().height(10.dp))
        if (reaches != null) {
            Spacer(Modifier.height(10.dp))
            SpLine(
                stringResource(if (reaches) R.string.spatial_fuel_enough else R.string.spatial_fuel_short),
                spText(sub, if (reaches) DashColors.Good else DashColors.Warning, FontWeight.SemiBold), maxLines = 2
            )
        }
    }
}

/** The fuel level as a glowing cyan to violet bar in a glass groove, amber in the reserve. */
@Composable
private fun SpFuelBar(percent: Int, low: Boolean, modifier: Modifier) {
    val from = if (low) DashColors.Warning else DashColors.Accent
    val to = if (low) DashColors.Warning else DashColors.Secondary
    val light = DashColors.Light
    val glowK = if (light) 0f else DashColors.Effects.scale
    Spacer(
        modifier.cachedDraw(percent, from, to, light, glowK) {
            val h = size.height
            val groove = if (light) Color(0x1A0D1220) else Color.White.copy(alpha = 0.14f)
            val fw = size.width * (percent / 100f).coerceIn(0f, 1f)
            val brush = Brush.horizontalGradient(listOf(from, to), 0f, size.width)
            onDrawBehind {
                drawRoundRect(groove, cornerRadius = CornerRadius(h / 2f))
                if (fw > 0f) {
                    if (glowK > 0f) drawRoundRect(brush, Offset(-h * 0.4f, -h * 0.4f), Size(fw + h * 0.8f, h * 1.8f), CornerRadius(h), alpha = 0.25f * glowK)
                    drawRoundRect(brush, size = Size(fw.coerceAtLeast(h), h), cornerRadius = CornerRadius(h / 2f))
                }
            }
        }
    )
}

// --- Media ---------------------------------------------------------------------------

/**
 * Music in its glass window, as the mockup has it: the cover across the top
 * (or the mockup's glow of pink and blue while the player gives none), the
 * song in bold, who sings it, and round glass keys with the white play. A
 * low window becomes one row with a small cover.
 */
@Composable
private fun SpMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        SpEmpty(
            Icons.Filled.MusicNote,
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    BoxWithConstraints(Modifier.fillMaxSize().spatialGlass(editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val compact = maxHeight < 210.dp
        if (compact) {
            val key = innerH.coerceIn(48.dp, 60.dp)
            Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                if (innerW >= 400.dp) {
                    SpCover(ms, Modifier.size(innerH.coerceIn(48.dp, 110.dp)))
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    SpLine(title, spText((innerH.value * 0.22f).coerceIn(17f, 26f), DashColors.TextPrimary, FontWeight.Bold))
                    SpLine(sub, spText(16f, DashColors.TextSecondary))
                }
                Spacer(Modifier.width(12.dp))
                SpKeys(env, key, prev = innerW >= 440.dp, spread = false)
            }
        } else {
            val titleSize = (innerW.value / 12.5f).coerceIn(19f, 28f)
            val subSize = (titleSize * 0.68f).coerceIn(15f, 19f)
            val key = (innerW.value / 5.8f).coerceIn(52f, 64f).dp
            val textBlock = titleSize * 1.25f + subSize * 1.25f + 16f + 16f + key.value
            val coverH = (innerH.value - textBlock).coerceAtLeast(0f)
            Column(Modifier.fillMaxSize().padding(pad)) {
                if (coverH >= 56f) {
                    SpCover(ms, Modifier.fillMaxWidth().height(coverH.coerceAtMost(innerW.value * 0.72f).dp))
                }
                Spacer(Modifier.weight(1f))
                SpLine(title, spText(titleSize, DashColors.TextPrimary, FontWeight.Bold))
                SpLine(sub, spText(subSize, DashColors.TextSecondary))
                Spacer(Modifier.height(16.dp))
                SpKeys(env, key, prev = true, spread = true)
            }
        }
    }
}

/** The song's cover with rounded corners or, while the player publishes none, the mockup's pink and blue glow, drawn once. */
@Composable
private fun SpCover(ms: MediaState, modifier: Modifier) {
    val bitmap = remember(ms.artwork) { ms.artwork?.asImageBitmap() }
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .cachedDraw(bitmap == null) {
                val w = size.width
                val h = size.height
                val pink = Brush.radialGradient(listOf(Color(0xFFFF5EA0), Color.Transparent), Offset(w * 0.3f, h * 0.7f), max(w, h) * 0.6f)
                val blue = Brush.radialGradient(listOf(Color(0xFF5B7BFF), Color.Transparent), Offset(w * 0.8f, h * 0.2f), max(w, h) * 0.6f)
                onDrawBehind {
                    drawRect(Color(0xFF1B1240))
                    drawRect(pink)
                    drawRect(blue)
                }
            }
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Previous (when [prev]), play / pause in white and next, [size] round each, spread over the row when [spread]. */
@Composable
private fun SpKeys(env: SkinTileEnv, size: Dp, prev: Boolean, spread: Boolean) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val c = env.mediaController
    Row(
        modifier = if (spread) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prev) SpRoundKey(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), size, false, enabled) { c.previous() }
        SpRoundKey(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play),
            size, true, enabled
        ) { c.playPause() }
        SpRoundKey(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), size, false, enabled) { c.next() }
    }
}

// --- Speed: floating -------------------------------------------------------------------

/**
 * Speed, floating in the air with no box as in the mockup: the figure large
 * and light with its soft shadow, KM/H spaced under it, amber over the road's
 * limit, and the limit's sign beside it when known. With effects off it
 * stands on opaque glass, so it reads in full sun over the city.
 */
@Composable
private fun SpSpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    val speed = rememberSpeedKmh()
    val limit by SpeedLimit.limit.collectAsState()
    val units = LocalUnits.current
    val over = speedOver(speed)
    val color = when {
        speed == null -> DashColors.Muted
        over -> DashColors.Warning
        else -> DashColors.TextPrimary
    }
    val canConnect = speed == null && env.obdConnection.isIdle && !env.editing
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(R.string.info_speed_no_signal)
    val plain = DashColors.Effects == DashEffects.NONE
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(if (plain || env.editing) Modifier.spatialGlass(editing = env.editing) else Modifier)
            .spTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said },
        contentAlignment = Alignment.Center
    ) {
        val pad = (min(maxWidth.value, maxHeight.value) * 0.06f).coerceIn(6f, 18f).dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val sign = (innerH.value * 0.34f).coerceIn(40f, 64f).dp
        val withSign = limit != null && innerW >= sign * 4f
        val numW = if (withSign) innerW - (sign + 16.dp) * 2 else innerW
        val numSize = spFit("188", FontWeight.Light, numW * 0.9f, innerH - 30.dp, 30f, 150f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (withSign) Spacer(Modifier.width(sign + 16.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SpLine(
                    speed?.let { units.speed(it).toString() } ?: "–",
                    spText(if (speed != null) numSize else numSize * 0.6f, color, FontWeight.Light, figure = true).copy(shadow = spFloatShadow()),
                    overflow = TextOverflow.Clip
                )
                Spacer(Modifier.height(6.dp))
                SpLine(units.speedUnit, spText(15f, DashColors.TextSecondary, FontWeight.Medium, caps = true).copy(shadow = spFloatShadow()), caps = true)
            }
            if (withSign) {
                Spacer(Modifier.width(16.dp))
                limit?.let { SpLimitSign(units.speed(it), sign) }
            }
        }
    }
}

/** The road's limit as its sign: a white disc in a red ring, the number in black (a road sign keeps its colours day and night). */
@Composable
private fun SpLimitSign(limit: Int, d: Dp) {
    val said = stringResource(R.string.spatial_speed_limit_cd, limit)
    Box(
        modifier = Modifier
            .size(d)
            .semantics { contentDescription = said }
            .cachedDraw(d) {
                val ring = size.minDimension * 0.115f
                onDrawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(Color.White, r)
                    drawCircle(Color(0xFFD8232A), r - ring / 2f, style = Stroke(ring))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        SpLine(limit.toString(), spText((d.value * if (limit >= 100) 0.32f else 0.4f).coerceAtLeast(14f), Color(0xFF111111), FontWeight.Bold), overflow = TextOverflow.Clip)
    }
}

// --- Telemetry ---------------------------------------------------------------------

/**
 * Engine in a glass window: the revs large and light with r/min and the gear
 * in a cyan chip, then coolant, battery and load where they fit. Without the
 * link, it says so and offers to connect.
 */
@Composable
private fun SpTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val units = LocalUnits.current
    // Whatever the car answers is shown, adapter or not.
    val g = rememberPanelGauges(K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    val gear by rememberGear()
    val live = state == ObdConnectionState.CONNECTED || g.any || gear.rpm != null
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .spatialGlass(editing = env.editing)
            .spTap(state.isIdle && !env.editing, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val readSize = min(innerW.value / 16f, innerH.value * 0.12f).coerceIn(15f, 22f)
        Column(Modifier.fillMaxSize().padding(pad)) {
            val rpm = gear.rpm
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpLabel(stringResource(R.string.spatial_rpm), Modifier.weight(1f))
                if (live) {
                    val chip = (readSize * 2.3f).coerceIn(36f, 52f).dp
                    SpChip(chip) { SpLine(gearMark(gear.gear), spText(chip.value * 0.5f, chipInk, FontWeight.SemiBold), overflow = TextOverflow.Clip) }
                }
            }
            Spacer(Modifier.weight(1f))
            val bigH = (innerH - (readSize * 2.8f + 60f).dp).coerceAtLeast(28.dp)
            val big = spFit("8 888", FontWeight.Light, innerW * 0.9f, bigH, 26f, 110f)
            SpLine(rpm?.let { groupThousands(it) } ?: "--", spText(big, DashColors.TextPrimary, FontWeight.Light, figure = true), overflow = TextOverflow.Clip)
            Spacer(Modifier.height(12.dp))
            when {
                live -> {
                    FitRow(20.dp) {
                        g.coolantTempC?.let {
                            SpReadout(stringResource(R.string.spatial_coolant), "${units.temp(it)}°", it >= COOLANT_WARNING_C, readSize)
                        }
                        g.voltage?.let {
                            SpReadout(stringResource(R.string.spatial_battery), String.format(Locale.getDefault(), "%.1f V", it), it !in BATTERY_OK_V, readSize)
                        }
                        g.engineLoadPct?.let { SpReadout(stringResource(R.string.spatial_load), "$it %", false, readSize) }
                    }
                }
                state == ObdConnectionState.CONNECTING -> SpLine(stringResource(R.string.horizon_obd_connecting), spText(readSize, DashColors.AccentInk, FontWeight.Medium))
                else -> FitRow(10.dp) {
                    SpPillButton(stringResource(R.string.horizon_connect_obd), filled = true, enabled = !env.editing, onClick = env.onConnectObd)
                    if (state == ObdConnectionState.ERROR) {
                        SpPillButton(stringResource(R.string.tape_cd_choose_adapter), filled = false, enabled = !env.editing, onClick = env.onPickDevice)
                    }
                }
            }
        }
    }
}

/** A small readout: its name over its value, the value in amber when it is out of range. */
@Composable
private fun SpReadout(label: String, value: String, warn: Boolean, size: Float) {
    Column {
        SpLine(label, spText((size * 0.78f).coerceAtLeast(14f), DashColors.TextSecondary))
        SpLine(value, spText(size * 1.15f, if (warn) DashColors.Warning else DashColors.TextPrimary, FontWeight.SemiBold))
    }
}

// --- Range ---------------------------------------------------------------------------

/**
 * Fuel and range in a glass window: RANGE and the level, the range large and
 * bold, the glowing fuel bar, the litres. Fuel not learned yet falls back to
 * the standard tile on the glass, which explains how to learn it.
 */
@Composable
private fun SpRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    if (fuel == null) {
        Box(Modifier.fillMaxSize().spatialGlass(editing = env.editing)) { StandardSkinnedTile(item, env) }
        return
    }
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    BoxWithConstraints(Modifier.fillMaxSize().spatialGlass(editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val lineSize = (innerH.value * 0.09f).coerceIn(14f, 19f)
        val showLine = innerH >= 150.dp
        val numH = innerH - 22.dp - 10.dp - 28.dp - if (showLine) (lineSize * 1.3f).dp else 0.dp
        val value = units.distance(fuel.rangeKm).toString()
        val numSize = spFit(spTemplate("$value ${units.distanceUnit}"), FontWeight.Bold, innerW * 0.9f, numH, 24f, 110f)
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpLabel(stringResource(R.string.spatial_range), Modifier.weight(1f))
                SpLine("${fuel.percent} %", spText(16f, if (low) DashColors.Warning else DashColors.TextSecondary, FontWeight.SemiBold))
            }
            Spacer(Modifier.weight(1f))
            Row {
                SpLine(value, spText(numSize, if (low) DashColors.Warning else DashColors.TextPrimary, FontWeight.Bold, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                Spacer(Modifier.width((numSize * 0.1f).dp))
                SpLine(units.distanceUnit, spText((numSize * 0.34f).coerceAtLeast(15f), DashColors.TextPrimary.copy(alpha = 0.8f), FontWeight.SemiBold), Modifier.alignByBaseline())
            }
            Spacer(Modifier.height(12.dp))
            SpFuelBar(fuel.percent, low, Modifier.fillMaxWidth().height(10.dp))
            if (showLine) {
                Spacer(Modifier.height(8.dp))
                SpLine(
                    String.format(Locale.getDefault(), "%s%.0f L", if (fuel.percentEstimated) "≈ " else "", fuel.liters),
                    spText(lineSize, DashColors.TextSecondary)
                )
            }
        }
    }
}

// --- Clock and weather: small windows ------------------------------------------------

/** Clock: a small glass window, the time large and light, the date under it. Tap opens the alarms. */
@Composable
private fun SpClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEEdMMMM", best = true)
    val context = env.context
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .spatialGlass(editing = env.editing)
            .spTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val dateSize = min(innerW.value / 14f, innerH.value * 0.14f).coerceIn(14f, 24f)
        val date = dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }
        val amPm = units.amPm(now, locale)
        val timeText = timeFmt.format(now)
        val timeSize = spFit(spTemplate(timeText), FontWeight.Light, if (amPm != null) innerW * 0.8f else innerW, innerH - (dateSize * 1.3f + 6f).dp, 26f, 220f)
        Column(Modifier.padding(pad)) {
            Row {
                SpLine(timeText, spText(timeSize, DashColors.TextPrimary, FontWeight.Light, figure = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width((timeSize * 0.06f).dp))
                    SpLine(amPm, spText((timeSize * 0.22f).coerceAtLeast(14f), DashColors.TextSecondary), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(6.dp))
            SpLine(date, spText(dateSize, DashColors.TextSecondary))
        }
    }
}

/**
 * Weather: a small glass window, the sky's icon in its cyan chip beside the
 * temperature, the sky in words, and rain to come in the accent's ink. While
 * there is nothing yet, it says why (no GPS, offline).
 */
@Composable
private fun SpWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val rain = rememberRainLine()
    BoxWithConstraints(Modifier.fillMaxSize().spatialGlass(editing = env.editing), contentAlignment = Alignment.CenterStart) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        if (w == null) {
            val title = when (wait) {
                WeatherWait.LOADING -> R.string.horizon_loading
                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
            }
            Column(Modifier.padding(pad)) {
                SpLine("--°", spText(spFit("88°", FontWeight.Light, innerW * 0.6f, innerH * 0.5f, 26f, 110f), DashColors.Muted, FontWeight.Light, figure = true))
                SpLine(stringResource(title), spText(16f, DashColors.TextSecondary), maxLines = 2)
            }
            return@BoxWithConstraints
        }
        val condSize = min(innerW.value / 13f, innerH.value * 0.13f).coerceIn(15f, 24f)
        val lineSize = (condSize * 0.8f).coerceIn(14f, 19f)
        val extra = innerH >= 140.dp && rain != null
        val temp = "${units.temp(w.tempC)}°"
        val chip = (innerH.value * 0.36f).coerceIn(40f, 72f).dp
        val below = (condSize * 1.3f + (if (extra) lineSize * 1.35f else 0f) + 8f).dp
        val tempSize = spFit(spTemplate(temp), FontWeight.Light, innerW - chip - 16.dp, innerH - below, 26f, 150f)
        Column(Modifier.padding(pad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpChip(chip) { Icon(weatherIcon(w.code), contentDescription = null, tint = chipInk, modifier = Modifier.size(chip * 0.55f)) }
                Spacer(Modifier.width(16.dp))
                SpLine(temp, spText(tempSize, DashColors.TextPrimary, FontWeight.Light, figure = true), overflow = TextOverflow.Clip)
            }
            Spacer(Modifier.height(8.dp))
            SpLine(w.condition, spText(condSize, DashColors.TextSecondary))
            if (extra) SpLine(rain.orEmpty().replaceFirstChar { it.titlecase(Locale.getDefault()) }, spText(lineSize, DashColors.AccentInk, FontWeight.Medium))
        }
    }
}

// --- Apps ---------------------------------------------------------------------------

/** App shortcut: a glass window with the app's icon and its name, side by side on a wide tile, stacked on a square one. */
@Composable
private fun SpApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .spatialGlass(editing = env.editing)
            .spTap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
        if (row) {
            val icon = (maxHeight.value * 0.5f).coerceIn(30f, 72f).dp
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                SpAppGlyph(app, icon)
                Spacer(Modifier.width(14.dp))
                SpLine(label, spText((icon.value * 0.4f).coerceIn(16f, 26f), DashColors.TextPrimary, FontWeight.SemiBold), Modifier.weight(1f, fill = false))
            }
        } else {
            val icon = (min(maxWidth.value, maxHeight.value) * 0.42f).coerceIn(30f, 96f).dp
            val named = maxHeight >= 96.dp
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SpAppGlyph(app, icon)
                if (named) {
                    Spacer(Modifier.height(8.dp))
                    SpLine(label, spText((icon.value * 0.3f).coerceIn(14f, 22f), DashColors.TextPrimary, FontWeight.SemiBold), align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun SpAppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.8f))
}

/**
 * Launch bar: an ornament of round glass keys, one per app, in a glass
 * capsule centred in its tile (as the mockup's dock), and a glass pencil at
 * its end. Apps that would get less than a 56 dp key are left out; a tall
 * bar names them under their icons.
 */
@Composable
private fun SpLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val capsuleH = min(maxHeight.value, 84f).coerceAtLeast(56f)
        val key = (capsuleH - 16f).coerceIn(48f, 64f)
        val gap = 10f
        val room = maxWidth.value - 16f - key - gap
        val fit = ((room + gap) / (key + gap)).toInt().coerceAtLeast(0)
        val pkgs = item.packages.take(fit)
        val tall = maxHeight >= 150.dp
        Row(
            Modifier
                .height(if (tall) maxHeight.coerceAtMost(150.dp) else capsuleH.dp)
                .spatialGlass(editing = env.editing, round = !tall)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pkgs.isEmpty()) {
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                    SpLine(stringResource(R.string.spatial_launch_bar_empty), spText(16f, DashColors.TextSecondary), maxLines = 2)
                }
            }
            pkgs.forEach { pkg ->
                val app = env.appsByPackage[pkg]
                val name = appLabel(app, pkg)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(key.dp)
                            .spatialGlass(round = true, lift = 0f)
                            .spTap(!env.editing, stringResource(R.string.horizon_open_app, name)) { env.onLaunchApp(pkg) }
                            .semantics(mergeDescendants = true) { contentDescription = name },
                        contentAlignment = Alignment.Center
                    ) { SpAppGlyph(app, (key * 0.6f).dp) }
                    if (tall) {
                        Spacer(Modifier.height(6.dp))
                        SpLine(name, spText(14f, DashColors.TextPrimary, FontWeight.Medium), Modifier.width((key + gap).dp), align = TextAlign.Center)
                    }
                }
            }
            val tap = rememberTapFeedback()
            val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
            Box(
                Modifier
                    .size(key.dp)
                    .spatialGlass(round = true, lift = 0f)
                    .clickable(onClickLabel = editLabel, role = Role.Button) { tap(); env.onEditLaunchBar() }
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}
