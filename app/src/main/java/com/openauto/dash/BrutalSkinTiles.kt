package com.openauto.dash

import com.openauto.dash.vehicle.rememberGauge
import com.openauto.dash.vehicle.rememberGaugeInt
import com.openauto.dash.vehicle.K
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.math.min

/*
 * Brutal skin, the tiles (the look itself is in BrutalSkin.kt): each main
 * widget is one loud block, as in the design. The next turn in yellow, the
 * speed in white, the weather in blue with its stripes and the car's sticker,
 * the music in pink, the car's readings in white columns, the clock in black.
 */

private const val DOT = " · "

/** Room inside a block: the outline and a margin following the block's smaller side. */
private fun padFor(w: Dp, h: Dp): Dp = BR_OUTLINE + (min(w.value, h.value) * 0.07f).coerceIn(10f, 24f).dp

/** Digits replaced by '8', so a readout keeps one size while its value changes. */
private fun template(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** A tile's shadow: the design's 8 dp, shorter on a small tile. */
private fun shadowFor(w: Dp, h: Dp): Dp = if (min(w.value, h.value) < 150f) 6.dp else BR_SHADOW

/**
 * Brutal renderer for app shortcuts, launch bars and the main widgets.
 * Anything else keeps its standard renderer.
 */
@Composable
internal fun BrutalTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> BrApp(item, env)
        is DashboardItem.LaunchBar -> BrLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> BrReadings(item, env, telemetry = true)
            BuiltinKind.RANGE -> BrReadings(item, env, telemetry = false)
            BuiltinKind.SPEED_HUD -> BrSpeed(env)
            BuiltinKind.MEDIA -> BrMedia(env)
            BuiltinKind.NAVIGATION -> BrDirections(env)
            BuiltinKind.CLOCK -> BrClock(env)
            BuiltinKind.WEATHER -> BrWeather(env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

// --- Small pieces -------------------------------------------------------------------

/**
 * Largest size (dp, for [brHeavy]) within [minSize]..[maxSize] at which
 * [sample] fits [maxW] x [maxH] on one line.
 */
@Composable
private fun brFit(sample: String, figure: Boolean, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = brHeavy(100f, Color.Black, figure)
    return remember(sample, figure, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** A block's label: tracked monospace capitals. */
@Composable
private fun BrLabel(text: String, color: Color, modifier: Modifier = Modifier, size: Float = 15f, align: TextAlign? = null) {
    BrLine(text, brMono(size, color), modifier, align = align)
}

/**
 * A quiet state on its block: the block's label, a title in heavy capitals
 * and one hint; the whole block is the target when a tap does something.
 */
@Composable
private fun BrEmpty(tone: BrTone, label: String, title: String, hint: String, editing: Boolean, onTap: (() -> Unit)?) {
    val c = brColors(tone)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val shadow = shadowFor(maxWidth, maxHeight)
        BrBlock(tone, Modifier.fillMaxSize(), onClick = if (editing) null else onTap, clickLabel = hint, editing = editing, shadow = shadow) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pad = padFor(maxWidth, maxHeight)
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val titleSize = min(innerH.value * 0.2f, innerW.value / (title.length * 0.42f + 1f)).coerceIn(18f, 40f)
                Column(Modifier.fillMaxSize().padding(pad)) {
                    if (innerH >= 120.dp) BrLabel(label, c.label)
                    Spacer(Modifier.weight(1f))
                    BrLine(title, brHeavy(titleSize, c.ink), maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    BrLine(hint, brMono(15f, c.label), maxLines = 2, caps = false)
                }
            }
        }
    }
}

/**
 * Whether a parked car's motion runs now: on a page in sight, the car still
 * and effects on. The arrow's nudge and the song's marquee follow it.
 */
@Composable
private fun brMotionAllowed(): Boolean =
    LocalPageActive.current && !LocalCarMoving.current && DashColors.Effects != DashEffects.NONE

/**
 * The turn arrow's nudge: two steps, out and back, every 1.2 s, only while
 * [brMotionAllowed]; a still arrow otherwise. Two frames per period, nothing
 * in between.
 */
@Composable
private fun rememberNudge(): State<Boolean> {
    val out = remember { mutableStateOf(false) }
    val run = brMotionAllowed()
    LaunchedEffect(run) {
        if (!run) {
            out.value = false
            return@LaunchedEffect
        }
        while (true) {
            delay(600)
            withFrameMillis { out.value = !out.value }
        }
    }
    return out
}

// --- Directions ---------------------------------------------------------------------

/**
 * The next turn on the yellow block, as in the design: NEXT TURN with the
 * arrival in mono, the turn's arrow in a white outlined circle, the distance
 * gigantic and the street in heavy capitals. A tall tile stacks them. Tap
 * opens the navigation app. Without a route (or without notification access)
 * the block says so and how to start.
 */
@Composable
private fun BrDirections(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val label = stringResource(R.string.brutal_next_turn)
    when {
        !env.hasMediaAccess -> BrEmpty(
            BrTone.YELLOW, label,
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing
        ) { CarMediaController.openNotificationAccessSettings(context) }
        !nav.active -> BrEmpty(
            BrTone.YELLOW, label,
            stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation), env.editing
        ) { openNavigationApp(context, nav) }
        else -> BrTurn(nav, label, env)
    }
}

@Composable
private fun BrTurn(nav: NavState, label: String, env: SkinTileEnv) {
    val context = env.context
    val c = brColors(BrTone.YELLOW)
    val head = remember(nav.eta, label) { (listOf(label) + nav.etaParts).joinToString(DOT) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BrBlock(
            BrTone.YELLOW, Modifier.fillMaxSize(),
            onClick = if (env.editing) null else ({ openNavigationApp(context, nav) }),
            clickLabel = stringResource(R.string.horizon_open_navigation), editing = env.editing, shadow = shadowFor(maxWidth, maxHeight)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pad = padFor(maxWidth, maxHeight) + 4.dp
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val (value, unit) = nav.distanceParts
                val street = nav.instruction.uppercase(Locale.getDefault())
                val labelShown = innerH >= 110.dp
                val labelH = if (labelShown) 30.dp else 0.dp
                val wide = innerW >= innerH * 1.25f
                Column(Modifier.fillMaxSize().padding(pad)) {
                    if (labelShown) {
                        BrLabel(head, c.label)
                        Spacer(Modifier.height(labelH - 20.dp))
                    }
                    if (value.isEmpty()) {
                        // No distance: the instruction itself is the headline.
                        val size = (min(innerH.value * 0.2f, innerW.value / 9f)).coerceIn(22f, 56f)
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            BrArrowDisc(nav, (innerH * 0.4f).coerceIn(44.dp, 140.dp).coerceAtMost(innerW * 0.3f))
                            Spacer(Modifier.width(20.dp))
                            BrLine(street, brHeavy(size, c.ink), maxLines = 3, caps = false)
                        }
                    } else {
                        val streetSize = (min(innerH.value * if (wide) 0.13f else 0.09f, innerW.value / 12f)).coerceIn(18f, 42f)
                        val streetLines = if (!wide && innerH >= 260.dp) 2 else 1
                        val streetH = (streetSize * 1.04f * streetLines + 14f).dp
                        val rowH = innerH - labelH - streetH
                        val disc = (if (wide) min(rowH.value, innerW.value * 0.26f) else min(rowH.value * 0.9f, innerW.value * 0.34f)).coerceIn(40f, 170f).dp
                        val distW = innerW - disc - 22.dp
                        val distSize = brFit(template(value), true, distW * 0.8f, rowH, 26f, 180f)
                        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            BrArrowDisc(nav, disc)
                            Spacer(Modifier.width(22.dp))
                            Row {
                                BrLine(value, brHeavy(distSize, c.ink, figure = true), Modifier.alignByBaseline(), caps = false, overflow = TextOverflow.Visible)
                                if (unit.isNotEmpty()) {
                                    BrLine(unit, brHeavy((distSize * 0.32f).coerceAtLeast(16f), c.ink), Modifier.alignByBaseline())
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        BrLine(street, brHeavy(streetSize, c.ink), maxLines = streetLines, caps = false)
                    }
                }
            }
        }
    }
}

/**
 * The turn's arrow in its white outlined circle (yellow at night): drawn
 * for the side the navigation says, or the app's own glyph in black. While
 * parked it nudges toward the turn in two steps ([rememberNudge]).
 */
@Composable
private fun BrArrowDisc(nav: NavState, d: Dp) {
    val light = DashColors.Light
    val face = if (light) Color.White else BrYellow
    val rim = if (light) BrInk else BrPaper
    val arrowInk = BrInk
    val side = nav.turnSide()
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    val nudge = rememberNudge()
    Box(
        Modifier
            .size(d)
            .semantics { contentDescription = nav.instruction }
            .cachedDraw(face, rim) {
                val o = BR_OUTLINE.toPx()
                onDrawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(face, r)
                    drawCircle(rim, r - o / 2f, style = Stroke(o))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val move = Modifier.graphicsLayer {
            val k = if (nudge.value) size.minDimension * 0.07f else 0f
            when (side) {
                TurnSide.LEFT -> translationX = -k
                TurnSide.RIGHT -> translationX = k
                TurnSide.AHEAD -> translationY = -k
            }
        }
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(arrowInk), modifier = move.fillMaxSize(0.58f))
        } else {
            Spacer(
                move
                    .fillMaxSize(0.62f)
                    .cachedDraw(side, arrowInk) {
                        val s = size.minDimension / 24f
                        // The design's arrow in a 24 grid: up the stem, round the corner, out to the side, its head.
                        val path = Path().apply {
                            when (side) {
                                TurnSide.AHEAD -> {
                                    moveTo(12f * s, 21f * s); lineTo(12f * s, 4f * s)
                                    moveTo(6f * s, 10f * s); lineTo(12f * s, 4f * s); lineTo(18f * s, 10f * s)
                                }
                                else -> {
                                    val m = side == TurnSide.LEFT
                                    fun x(v: Float) = (if (m) 24f - v else v) * s
                                    moveTo(x(8f), 21f * s); lineTo(x(8f), 11f * s)
                                    cubicTo(x(8f), 8.8f * s, x(9.8f), 7f * s, x(12f), 7f * s)
                                    lineTo(x(19f), 7f * s)
                                    moveTo(x(14.5f), 2.5f * s); lineTo(x(19f), 7f * s); lineTo(x(14.5f), 11.5f * s)
                                }
                            }
                        }
                        val stroke = Stroke(3.2f * s, cap = StrokeCap.Square, join = StrokeJoin.Miter)
                        onDrawBehind { drawPath(path, arrowInk, style = stroke) }
                    }
            )
        }
    }
}

// --- Speed --------------------------------------------------------------------------

/**
 * Speed on the white block: SPEED and the unit in mono, the number gigantic,
 * the road's limit sign with LIMIT at the foot (where the speed comes from
 * when no limit is known). Red over the limit. Without a speed, a tap
 * connects the OBD adapter. A wide low tile puts the number beside the rest.
 */
@Composable
private fun BrSpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    val speed = rememberSpeedKmh()
    val limit by SpeedLimit.limit.collectAsState()
    val units = LocalUnits.current
    val over = speedOver(speed)
    val c = brColors(BrTone.WHITE)
    val numColor = when {
        speed == null -> DashColors.Muted
        over -> DashColors.Critical
        else -> c.ink
    }
    val canConnect = speed == null && env.obdConnection.isIdle && !env.editing
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(R.string.info_speed_no_signal)
    val label = stringResource(R.string.brutal_speed, units.speedUnit)
    val source = speedSource(stringResource(R.string.info_speed_no_signal))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BrBlock(
            BrTone.WHITE, Modifier.fillMaxSize().semantics(mergeDescendants = true) { contentDescription = said },
            onClick = if (canConnect) env.onConnectObd else null, clickLabel = stringResource(R.string.horizon_connect_obd),
            editing = env.editing, shadow = shadowFor(maxWidth, maxHeight)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pad = padFor(maxWidth, maxHeight) + 2.dp
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val text = speed?.let { units.speed(it).toString() } ?: "--"
                val sample = if (text.length >= 3) "188" else "88"
                val sign = (min(innerH.value * 0.2f, innerW.value * 0.3f)).coerceIn(40f, 68f).dp
                val wideLow = innerW >= innerH * 1.6f
                if (wideLow) {
                    val numSize = brFit(sample, true, innerW * 0.6f, innerH, 30f, 220f)
                    Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                        BrLine(text, brHeavy(numSize, numColor, figure = true), Modifier.weight(1f), caps = false, overflow = TextOverflow.Visible)
                        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
                            BrLabel(label, c.label)
                            SpeedFoot(limit, units, sign, source, c.label)
                        }
                    }
                } else {
                    val numH = innerH - 26.dp - sign - 16.dp
                    val numSize = brFit(sample, true, innerW, numH, 30f, 220f)
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        BrLabel(label, c.label)
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            BrLine(text, brHeavy(numSize, numColor, figure = true), caps = false, overflow = TextOverflow.Visible)
                        }
                        SpeedFoot(limit, units, sign, source, c.label)
                    }
                }
            }
        }
    }
}

/** The foot of the speed block: the limit sign and LIMIT, or where the speed comes from. */
@Composable
private fun SpeedFoot(limit: Int?, units: UnitSystem, sign: Dp, source: String, ink: Color) {
    Row(Modifier.height(sign), verticalAlignment = Alignment.CenterVertically) {
        if (limit != null) {
            BrLimitSign(units.speed(limit), sign)
            Spacer(Modifier.width(12.dp))
            BrLabel(stringResource(R.string.brutal_limit), ink)
        } else {
            BrLabel(source, ink)
        }
    }
}

/** The road's limit as its sign: a white disc in a thick red ring, the number heavy and black (a road sign keeps its colours). */
@Composable
private fun BrLimitSign(limit: Int, d: Dp) {
    val said = stringResource(R.string.skin_panorama_speed_limit_cd, limit)
    Box(
        Modifier
            .size(d)
            .semantics { contentDescription = said }
            .cachedDraw(d) {
                val ring = size.minDimension * 0.13f
                onDrawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(Color.White, r)
                    drawCircle(Color(0xFFFF3B2F), r - ring / 2f, style = Stroke(ring))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        BrLine(limit.toString(), brHeavy((d.value * if (limit >= 100) 0.3f else 0.36f).coerceAtLeast(14f), BrInk, figure = true), caps = false, overflow = TextOverflow.Visible)
    }
}

// --- Weather ------------------------------------------------------------------------

/**
 * Weather on the blue striped block: WEATHER in mono, the temperature huge,
 * then two lines in heavy capitals, RAIN and when it starts (or ends), else
 * the sky and how it feels. The car's sticker, ALL GOOD or CHECK CAR, is
 * slapped on its lower corner once the car has said either.
 */
@Composable
private fun BrWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    val c = brColors(BrTone.BLUE)
    val verdict = rememberBrVerdict()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BrBlock(BrTone.BLUE, Modifier.fillMaxSize(), editing = env.editing, stripes = true, shadow = shadowFor(maxWidth, maxHeight)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pad = padFor(maxWidth, maxHeight) + 2.dp
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val sticker = verdict != null && innerH >= 190.dp && innerW >= 150.dp
                val labelShown = innerH >= 120.dp
                val lines: List<String>
                val temp: String
                if (w == null) {
                    temp = "--°"
                    lines = listOf(
                        stringResource(
                            when (wait) {
                                WeatherWait.LOADING -> R.string.horizon_loading
                                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
                            }
                        )
                    )
                } else {
                    temp = "${units.temp(w.tempC)}°"
                    lines = when {
                        w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs ->
                            listOf(stringResource(R.string.brutal_rain), stringResource(R.string.brutal_rain_until, timeFmt.format(Date(w.rainUntilMs))))
                        w.rainFromMs != null ->
                            listOf(stringResource(R.string.brutal_rain), stringResource(R.string.brutal_rain_from, timeFmt.format(Date(w.rainFromMs))))
                        else -> listOf(w.condition, stringResource(R.string.brutal_feels, "${units.temp(w.feelsC)}°"))
                    }
                }
                val lineSize = min(innerW.value / 8.5f, innerH.value * 0.1f).coerceIn(16f, 32f)
                val shownLines = if (innerH >= 150.dp) lines else lines.take(1)
                val below = (lineSize * 1.04f * shownLines.size + 14f).dp + (if (sticker) 40.dp else 0.dp) + (if (labelShown) 30.dp else 0.dp)
                val tempSize = brFit(template(temp), true, innerW, innerH - below, 28f, 130f)
                Column(Modifier.fillMaxSize().padding(pad)) {
                    if (labelShown) {
                        BrLabel(stringResource(R.string.brutal_weather), c.label)
                        Spacer(Modifier.height(10.dp))
                    }
                    BrLine(temp, brHeavy(tempSize, if (w == null) c.label else c.ink, figure = true), caps = false, overflow = TextOverflow.Visible)
                    Spacer(Modifier.height(14.dp))
                    shownLines.forEach { BrLine(it, brHeavy(lineSize, c.ink)) }
                }
                if (sticker && verdict != null) {
                    BrSticker(verdict, Modifier.align(Alignment.BottomEnd).padding(end = pad * 0.4f, bottom = pad * 0.6f))
                }
            }
        }
    }
}

/**
 * Whether the car is all good or wants a look, from its own messages, the
 * engine's codes and the engine lamp ([carVerdict]): true for a problem,
 * false for nothing to report, null while nothing has been read (no sticker
 * then, never an ALL GOOD it can't back).
 */
@Composable
private fun rememberBrVerdict(): Boolean? {
    val ai by AiMechanic.state.collectAsState()
    val messages = rememberCarMessages()
    val lamp by ObdBluetoothManager.lamp.collectAsState()
    return when (carVerdict(messages.map { it.level }, ai.warningCodes, ai.diagnosis?.severity, lamp?.on == true)) {
        CarVerdict.STOP, CarVerdict.DRIVE -> true
        CarVerdict.FINE -> false
        CarVerdict.UNREAD -> null
    }
}

/** The sticker slapped on a corner: ALL GOOD in yellow on black, or CHECK CAR in white on red, turned a little. */
@Composable
private fun BrSticker(problem: Boolean, modifier: Modifier) {
    val light = DashColors.Light
    val fill = when {
        problem -> DashColors.Critical
        light -> BrInk
        else -> BrYellow
    }
    val ink = when {
        problem -> Color.White
        light -> BrYellow
        else -> BrInk
    }
    Box(
        modifier
            .graphicsLayer { rotationZ = -8f }
            .cachedDraw(fill) {
                val r = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                onDrawBehind { drawRoundRect(fill, cornerRadius = r) }
            }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        BrLine(stringResource(if (problem) R.string.brutal_check_car else R.string.brutal_all_good), brHeavy(20f, ink))
    }
}

// --- Media --------------------------------------------------------------------------

/**
 * Music on the pink block: NOW PLAYING in mono, the song and who sings it in
 * huge capitals on one line (scrolling while parked, cut with an ellipsis
 * while driving), and three outlined round keys, at the top right as in the
 * design or under the song on a narrow tile.
 */
@Composable
private fun BrMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    val label = stringResource(R.string.brutal_now_playing)
    if (!env.hasMediaAccess) {
        BrEmpty(
            BrTone.PINK, label,
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing
        ) { CarMediaController.openNotificationAccessSettings(context) }
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val line = if (hasTrack) listOf(ms.title, ms.artist).filter { it.isNotBlank() }.joinToString(DOT) else stringResource(R.string.info_nothing_playing)
    val c = brColors(BrTone.PINK)
    val scroll = brMotionAllowed()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BrBlock(BrTone.PINK, Modifier.fillMaxSize(), editing = env.editing, shadow = shadowFor(maxWidth, maxHeight)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pad = padFor(maxWidth, maxHeight)
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val key = if (innerH >= 150.dp) 56.dp else 48.dp
                val keysW = key * 3 + 20.dp
                val keysTop = innerW >= keysW + 150.dp
                val topH = if (keysTop) key else 24.dp
                val titleH = innerH - topH - (if (keysTop) 0.dp else key + 10.dp) - 8.dp
                val titleSize = min(titleH.value / 1.08f, innerW.value / 6f).coerceIn(22f, 96f)
                Column(Modifier.fillMaxSize().padding(pad)) {
                    Row(Modifier.fillMaxWidth().height(topH), verticalAlignment = Alignment.Top) {
                        BrLabel(label, c.label, Modifier.weight(1f).padding(top = 2.dp))
                        if (keysTop) BrKeys(env, key)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        key(line, scroll) {
                            BrLine(
                                line, brHeavy(titleSize, c.ink),
                                if (scroll) Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2_000, velocity = 60.dp) else Modifier,
                                overflow = if (scroll) TextOverflow.Clip else TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (!keysTop) {
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { BrKeys(env, key) }
                    }
                }
            }
        }
    }
}

/** Previous, play / pause and next as outlined round keys, [d] across, filling yellow while pressed. */
@Composable
private fun BrKeys(env: SkinTileEnv, d: Dp) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val mc = env.mediaController
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BrRoundKey(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), d, enabled) { mc.previous() }
        BrRoundKey(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play), d, enabled
        ) { mc.playPause() }
        BrRoundKey(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), d, enabled) { mc.next() }
    }
}

@Composable
private fun BrRoundKey(icon: ImageVector, label: String, d: Dp, enabled: Boolean, onClick: () -> Unit) {
    val c = brColors(BrTone.WHITE)
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    Box(
        Modifier
            .size(d)
            .cachedDraw(c.fill, c.outline, pressed) {
                val o = BR_OUTLINE.toPx()
                onDrawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(if (pressed.value) BrYellow else c.fill, r)
                    drawCircle(c.outline, r - o / 2f, style = Stroke(o))
                }
            }
            .clickable(interaction, indication = null, enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (pressed.value) BrInk else c.ink, modifier = Modifier.size(d * 0.5f))
    }
}

// --- The car's readings -------------------------------------------------------------

/** One column of the readings block: its mono label and its heavy value, red when out of range. */
private class BrReading(val label: String, val value: String, val warn: Boolean = false)

/**
 * The car's readings on the white block, in columns parted by thick rules:
 * the range, then the coolant (or, on a range tile, the fuel left), then a
 * third reading Dashwheel has: the cheapest fuel nearby, else the outside
 * temperature, the battery or the fuel. As many columns as the width holds;
 * a tall tile stacks them. Telemetry: a tap connects the adapter while the
 * link is down. A range tile whose fuel has not been learned yet shows the
 * standard tile, which explains how to learn it.
 */
@Composable
private fun BrReadings(item: DashboardItem, env: SkinTileEnv, telemetry: Boolean) {
    val fuel = rememberFuel()
    if (!telemetry && fuel == null) {
        Box(Modifier.fillMaxSize().brutalFallbackPanel()) { StandardSkinnedTile(item, env) }
        return
    }
    val units = LocalUnits.current
    val coolant = rememberGaugeInt(K.COOLANT_TEMP) ?: 0
    val voltage = rememberGauge(K.BATTERY_VOLTS) ?: 0.0
    val c = brColors(BrTone.WHITE)
    val idle = env.obdConnection.isIdle
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2 - BR_SHADOW
        val innerH = maxHeight - pad * 2 - BR_SHADOW
        val stacked = innerH > innerW * 1.1f && innerH >= 200.dp
        val count = when {
            stacked -> if (innerH >= 300.dp) 3 else 2
            innerW >= 400.dp -> 3
            innerW >= 240.dp -> 2
            else -> 1
        }
        val third = if (count >= 3) rememberThirdReading(telemetry, fuel, voltage) else null
        val readings = buildList {
            add(
                BrReading(
                    stringResource(R.string.brutal_range) + " " + units.distanceUnit,
                    fuel?.let { units.distance(it.rangeKm).toString() } ?: "--",
                    fuel != null && fuel.percent <= SKIN_LOW_FUEL_PCT
                )
            )
            if (telemetry) {
                add(
                    BrReading(
                        stringResource(if (idle && telemetry) R.string.horizon_connect_obd else R.string.brutal_coolant),
                        if (coolant > 0) "${units.temp(coolant)}°" else "--",
                        coolant >= COOLANT_WARNING_C
                    )
                )
            } else {
                add(BrReading(stringResource(R.string.brutal_fuel), fuel?.let { "${it.percent}%" } ?: "--", fuel != null && fuel.percent <= SKIN_LOW_FUEL_PCT))
            }
            if (third != null) add(third)
        }.take(count)
        BrBlock(
            BrTone.WHITE, Modifier.fillMaxSize(),
            onClick = if (telemetry && idle && !env.editing) env.onConnectObd else null,
            clickLabel = stringResource(R.string.horizon_connect_obd), editing = env.editing, shadow = shadowFor(maxWidth, maxHeight)
        ) {
            val labelH = 26.dp
            if (stacked) {
                val rowH = (innerH - 16.dp * (readings.size - 1)) / readings.size
                val size = readings.minOf { brFit(template(it.value), true, innerW, rowH - labelH, 22f, 90f) }
                Column(Modifier.fillMaxSize().padding(pad)) {
                    readings.forEachIndexed { i, r ->
                        if (i > 0) BrRule(horizontal = true, color = c.outline)
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) { ReadingCell(r, size, c) }
                    }
                }
            } else {
                val colW = (innerW - 28.dp * (readings.size - 1)) / readings.size
                val size = readings.minOf { brFit(template(it.value), true, colW * 0.95f, innerH - labelH - 10.dp, 22f, 96f) }
                Row(Modifier.fillMaxSize().padding(pad)) {
                    readings.forEachIndexed { i, r ->
                        if (i > 0) BrRule(horizontal = false, color = c.outline)
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Top) { ReadingCell(r, size, c) }
                    }
                }
            }
        }
    }
}

/** A column's label over its value. */
@Composable
private fun ReadingCell(r: BrReading, size: Float, c: BrColors) {
    BrLabel(r.label, c.label)
    Spacer(Modifier.height(10.dp))
    BrLine(r.value, brHeavy(size, if (r.warn) DashColors.Critical else c.ink, figure = true), caps = false, overflow = TextOverflow.Visible)
}

/** A thick rule between columns (or rows), 12 dp clear of each. */
@Composable
private fun BrRule(horizontal: Boolean, color: Color) {
    val m = if (horizontal) Modifier.padding(vertical = 6.dp).fillMaxWidth().height(BR_OUTLINE) else Modifier.padding(horizontal = 12.dp).fillMaxHeight().width(BR_OUTLINE)
    Spacer(m.cachedDraw(color) { onDrawBehind { drawRect(color) } })
}

/**
 * The third reading: the cheapest fuel of the car's grade nearby (French open
 * data, asked only with the location allowed), else the outside temperature,
 * else the battery or, on a telemetry tile, the fuel left.
 */
@Composable
private fun rememberThirdReading(telemetry: Boolean, fuel: FuelInfo?, voltage: Double): BrReading? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val units = LocalUnits.current
    val located = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val nearby = if (located) rememberFuelNearby() else null
    val best = nearby?.ranked?.firstOrNull()
    if (best != null) return BrReading("${nearby.grade.label} ${FuelPrices.CURRENCY}", FuelPrices.formatPrice(best.price))
    val outside = rememberOutsideTempC()
    if (outside != null) return BrReading(stringResource(R.string.brutal_outside), "${units.temp(outside)}°")
    if (voltage > 0.0) {
        return BrReading(stringResource(R.string.brutal_battery), String.format(Locale.getDefault(), "%.1f V", voltage), voltage !in BATTERY_OK_V)
    }
    if (telemetry && fuel != null) return BrReading(stringResource(R.string.brutal_fuel), "${fuel.percent}%", fuel.percent <= SKIN_LOW_FUEL_PCT)
    return null
}

// --- Clock --------------------------------------------------------------------------

/** The clock: the black block (yellow at night) with the time huge and the date in mono. Tap opens the alarms. */
@Composable
private fun BrClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val units = LocalUnits.current
    val locale = Locale.getDefault()
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEEdMMMM", best = true)
    val context = env.context
    val c = brColors(BrTone.INK)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BrBlock(
            BrTone.INK, Modifier.fillMaxSize(),
            onClick = if (env.editing) null else ({ openClockApp(context) }), clickLabel = stringResource(R.string.horizon_open_clock),
            editing = env.editing, shadow = shadowFor(maxWidth, maxHeight)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pad = padFor(maxWidth, maxHeight)
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val dated = innerH >= 110.dp
                val time = timeFmt.format(now)
                val amPm = units.amPm(now, locale)
                val timeSize = brFit(template(time), true, if (amPm != null) innerW * 0.8f else innerW, innerH - if (dated) 34.dp else 0.dp, 28f, 260f)
                Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center) {
                    Row {
                        BrLine(time, brHeavy(timeSize, c.ink, figure = true), Modifier.alignByBaseline(), caps = false, overflow = TextOverflow.Visible)
                        if (amPm != null) BrLine(amPm, brHeavy((timeSize * 0.24f).coerceAtLeast(14f), c.ink), Modifier.alignByBaseline())
                    }
                    if (dated) {
                        Spacer(Modifier.height(10.dp))
                        BrLabel(dateFmt.format(now), c.label, size = (innerH.value * 0.08f).coerceIn(15f, 20f))
                    }
                }
            }
        }
    }
}

// --- Apps ---------------------------------------------------------------------------

/** App shortcut: a white key block, the app's icon and its name in heavy capitals, side by side on a wide tile. */
@Composable
private fun BrApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    val c = brColors(BrTone.WHITE)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BrBlock(
            BrTone.WHITE, Modifier.fillMaxSize().semantics(mergeDescendants = true) { contentDescription = label },
            onClick = if (env.editing) null else ({ env.onLaunchApp(item.packageName) }),
            clickLabel = stringResource(R.string.horizon_open_app, label), editing = env.editing,
            shadow = shadowFor(maxWidth, maxHeight), contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
                if (row) {
                    val icon = (maxHeight.value * 0.48f).coerceIn(28f, 72f).dp
                    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrAppGlyph(app, icon, c.ink)
                        Spacer(Modifier.width(14.dp))
                        BrLine(label, brHeavy((icon.value * 0.42f).coerceIn(16f, 28f), c.ink), Modifier.weight(1f, fill = false))
                    }
                } else {
                    val icon = (min(maxWidth.value, maxHeight.value) * 0.42f).coerceIn(28f, 96f).dp
                    val named = maxHeight >= 96.dp
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BrAppGlyph(app, icon, c.ink)
                        if (named) {
                            Spacer(Modifier.height(8.dp))
                            BrLine(label, brHeavy((icon.value * 0.3f).coerceIn(14f, 22f), c.ink), align = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrAppGlyph(app: AppEntry?, size: Dp, ink: Color) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.8f))
}

/**
 * Launch bar: a row of white key blocks, one per app (its icon and its name
 * in heavy capitals where there is room), and a pencil block at the end.
 * Apps that would get less than a 64 dp key are left out.
 */
@Composable
private fun BrLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    val c = brColors(BrTone.WHITE)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 12.dp
        val shadow = BR_BAR_SHADOW
        val edit = (min(maxHeight.value, 72f).coerceAtLeast(56f)).dp
        val room = maxWidth - edit - gap
        val fit = ((room + gap).value / (70f + gap.value)).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else (room.value - gap.value * (pkgs.size - 1)) / pkgs.size
        val labels = per >= 150f
        val icon = ((maxHeight - shadow).value * 0.5f).coerceIn(24f, 48f).dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                BrBlock(BrTone.WHITE, Modifier.weight(1f).fillMaxHeight(), editing = env.editing, shadow = shadow, contentAlignment = Alignment.CenterStart) {
                    BrLine(stringResource(R.string.skin_panorama_launch_bar_empty), brMono(15f, c.label), Modifier.padding(horizontal = 20.dp), maxLines = 2, caps = false)
                }
            } else {
                pkgs.forEach { pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    BrBlock(
                        BrTone.WHITE,
                        Modifier.weight(1f).fillMaxHeight().widthIn(min = 64.dp).semantics(mergeDescendants = true) { contentDescription = name },
                        onClick = if (env.editing) null else ({ env.onLaunchApp(pkg) }),
                        clickLabel = stringResource(R.string.horizon_open_app, name), editing = env.editing, shadow = shadow,
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp),
                            horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BrAppGlyph(app, icon, c.ink)
                            if (labels) {
                                Spacer(Modifier.width(12.dp))
                                BrLine(name, brHeavy((icon.value * 0.46f).coerceIn(15f, 22f), c.ink), Modifier.weight(1f, fill = false))
                            }
                        }
                    }
                }
            }
            val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
            BrBlock(
                BrTone.WHITE, Modifier.size(edit + shadow).semantics { contentDescription = editLabel },
                onClick = env.onEditLaunchBar, clickLabel = editLabel, shadow = shadow, contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = c.ink, modifier = Modifier.size(24.dp))
            }
        }
    }
}
