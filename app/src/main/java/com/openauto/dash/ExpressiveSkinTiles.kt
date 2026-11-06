package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Expressive skin, the tiles (the look itself is in ExpressiveSkin.kt, the
 * car's badge in ExpressiveSkinCar.kt). Each widget has its own shape and
 * tone: the coral turn tile with the arrow in a dark flower, the tall speed
 * pill, the music card in the album art's colour with the cover in a
 * morphing blob and the wavy progress line, the sky-blue weather card, the
 * lilac engine card; the clock stands bare and bold on the page.
 */

private const val DOT = " · "

/** Room around a card's content, following the tile's smaller side. */
private fun padFor(w: Dp, h: Dp): Dp = (min(w.value, h.value) * 0.1f).coerceIn(14f, 28f).dp

/**
 * Expressive renderer for app shortcuts, launch bars and the main widgets,
 * each springing into place when its dashboard comes into sight. Anything
 * else keeps its standard renderer.
 */
@Composable
internal fun ExpressiveTile(item: DashboardItem, env: SkinTileEnv) {
    Box(Modifier.fillMaxSize().exAppear()) {
        when (item) {
            is DashboardItem.AppShortcut -> ExApp(item, env)
            is DashboardItem.LaunchBar -> ExLaunchBar(item, env)
            is DashboardItem.BuiltinWidget -> when (item.kind) {
                BuiltinKind.TELEMETRY -> ExTelemetry(env)
                BuiltinKind.SPEED_HUD -> ExSpeed(env)
                BuiltinKind.MEDIA -> ExMedia(env)
                BuiltinKind.NAVIGATION -> ExDirections(env)
                BuiltinKind.CLOCK -> ExClock(env)
                BuiltinKind.WEATHER -> ExWeather(env)
                BuiltinKind.RANGE -> ExRange(item, env)
                else -> StandardSkinnedTile(item, env)
            }
            else -> StandardSkinnedTile(item, env)
        }
    }
}

// --- Small pieces -----------------------------------------------------------------

/** [shape] filled with [fill] and [icon] in [ink] in its middle: the skin's icon holders. */
@Composable
internal fun ExShapeIcon(icon: ImageVector, shape: androidx.compose.ui.graphics.Shape, fill: Color, ink: Color, size: Dp) {
    Box(Modifier.size(size).exCard(fill, shape, shadow = false), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * A quiet state on a tonal card of [fill]: the icon in a flower of [ink],
 * a title and one hint; the whole card is the target when a tap helps.
 */
@Composable
private fun ExEmpty(icon: ImageVector, title: String, hint: String, fill: Color, ink: Color, editing: Boolean, onTap: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .exSquish(interaction)
            .exCard(fill, editing = editing)
            .exClick(interaction, onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val wide = maxWidth >= maxHeight * 1.3f
        val iconD = min(maxHeight.value * 0.5f, maxWidth.value * 0.26f).coerceIn(44f, 110f).dp
        val textW = if (wide) maxWidth - pad * 2 - iconD - 18.dp else maxWidth - pad * 2
        val titleSize = min(maxHeight.value * 0.15f, textW.value / (title.length * 0.45f + 1f)).coerceIn(18f, 32f)
        val hintSize = (titleSize * 0.66f).coerceIn(15f, 20f)
        val words: @Composable () -> Unit = {
            Column {
                ExLine(title, exText(titleSize, ink, FontWeight.Bold), maxLines = 2)
                Spacer(Modifier.height(4.dp))
                ExLine(hint, exText(hintSize, ink.copy(alpha = 0.78f), FontWeight.Medium), maxLines = 2)
            }
        }
        if (wide) {
            Row(Modifier.padding(pad), verticalAlignment = Alignment.CenterVertically) {
                ExShapeIcon(icon, ExFlower, ink, fill, iconD)
                Spacer(Modifier.width(18.dp))
                words()
            }
        } else {
            val roomy = maxHeight >= 200.dp
            Column(Modifier.padding(pad)) {
                if (roomy) {
                    ExShapeIcon(icon, ExFlower, ink, fill, iconD)
                    Spacer(Modifier.height(14.dp))
                }
                words()
            }
        }
    }
}

/** Children in a row, in order, each only if it fits whole in the room left (dropped, never cut), [gap] apart. */
@Composable
internal fun ExFitRow(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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

/** A white pill with a small rounded square of [dot] colour and its words, as the mockup's chips. */
@Composable
internal fun ExChip(text: String, dot: Color, size: Float = 19f, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height((size * 2.5f).dp)
            .exCard(ExTone.chip, ExPill)
            .padding(horizontal = (size * 0.95f).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.size((size * 0.7f).dp).background(dot, RoundedCornerShape((size * 0.25f).dp)))
        Spacer(Modifier.width((size * 0.5f).dp))
        ExLine(text, exText(size, DashColors.TextPrimary, FontWeight.SemiBold))
    }
}

/** A pill button, at least 56 dp tall: filled with [fill] and [ink] words. */
@Composable
private fun ExPillButton(text: String, fill: Color, ink: Color, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .exSquish(interaction)
            .exCard(fill, ExPill, shadow = false)
            .exClick(interaction, enabled, text, onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        ExLine(text, exText(18f, ink, FontWeight.Bold))
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

// --- Directions -----------------------------------------------------------------------

/**
 * The next turn on the coral tile, as in the design: the arrow in a dark
 * flower turning slowly while parked, the distance giant, heavy and narrow
 * with its unit, the street under it, the arrival when there is room. A
 * wide tile puts the flower beside the words, a tall one above. Tap opens
 * the navigation app. Without a route (or without notification access) the
 * coral tile says so and how to start.
 */
@Composable
private fun ExDirections(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    val coral = ExTone.coral
    val ink = ExTone.onCoral
    when {
        !env.hasMediaAccess -> ExEmpty(
            Icons.Filled.NotificationsActive,
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access),
            coral, ink, env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> ExEmpty(
            Icons.Filled.Navigation,
            stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation),
            coral, ink, env.editing,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> {
            val interaction = remember { MutableInteractionSource() }
            val arrival = arrivalClock(nav)?.let { stringResource(R.string.expressive_arrive, it) }
            val eta = remember(nav.eta, arrival) {
                val rest = nav.etaParts.filter { arrivalClock(nav) != it }
                (listOfNotNull(arrival) + rest).joinToString(DOT)
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .exSquish(interaction)
                    .exCard(coral, editing = env.editing)
                    .exClick(interaction, canTap, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
            ) {
                val pad = padFor(maxWidth, maxHeight) + 4.dp
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val wide = maxWidth > maxHeight * 1.5f
                val (value, unit) = nav.distanceParts
                val streetSize = min(innerW.value / 18f, innerH.value * 0.2f).coerceIn(17f, 28f)
                val etaSize = (streetSize * 0.8f).coerceIn(15f, 20f)
                if (wide) {
                    val flower = (innerH * 0.92f).coerceIn(56.dp, 150.dp)
                    val textW = innerW - flower - 24.dp
                    val showEta = eta.isNotEmpty() && innerH >= 170.dp
                    val distH = innerH - (streetSize * 1.3f + 6f).dp - if (showEta) (etaSize * 1.4f + 4f).dp else 0.dp
                    val distSize = exFit(exTemplate(value.ifEmpty { "888" }), true, textW * 0.7f, distH, 28f, 132f)
                    Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                        ExTurnFlower(nav, flower, coral, ink)
                        Spacer(Modifier.width(24.dp))
                        TurnWords(nav, value, unit, distSize, streetSize, etaSize, if (showEta) eta else "", ink, maxLines = 1)
                    }
                } else {
                    val flower = min(innerW.value * 0.5f, innerH.value * 0.36f).coerceIn(52f, 140f).dp
                    val wordsH = innerH - flower - 12.dp - (streetSize * 2.5f + etaSize * 1.4f + 10f).dp
                    val distSize = exFit(exTemplate(value.ifEmpty { "888" }), true, innerW * 0.72f, wordsH, 28f, 120f)
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        ExTurnFlower(nav, flower, coral, ink)
                        Spacer(Modifier.weight(1f))
                        TurnWords(nav, value, unit, distSize, streetSize, etaSize, if (innerH >= 260.dp) eta else "", ink, maxLines = if (innerH > 320.dp) 2 else 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun TurnWords(nav: NavState, value: String, unit: String, distSize: Float, streetSize: Float, etaSize: Float, eta: String, ink: Color, maxLines: Int) {
    Column {
        if (value.isNotEmpty()) {
            Row {
                ExLine(value, exText(distSize, ink, hero = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width((distSize * 0.06f).dp))
                    ExLine(unit, exText((distSize * 0.36f).coerceAtLeast(16f), ink, FontWeight.Bold), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(6.dp))
            ExLine(nav.instruction, exText(streetSize, ink, FontWeight.SemiBold), maxLines = maxLines)
        } else {
            ExLine(nav.instruction, exText((streetSize * 1.4f).coerceAtMost(36f), ink, FontWeight.Bold), maxLines = maxLines + 1)
        }
        if (eta.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            ExLine(eta, exText(etaSize, ink.copy(alpha = 0.75f), FontWeight.Medium))
        }
    }
}

/**
 * The turn's arrow in a dark flower: the flower turns slowly while parked
 * (its own layer, still while driving and with effects off), the arrow in
 * the tile's coral stays put: the navigation's own glyph tinted, else drawn
 * for the side the navigation says.
 */
@Composable
private fun ExTurnFlower(nav: NavState, size: Dp, coral: Color, ink: Color) {
    val spin = rememberSpin(14_000)
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    val side = nav.turnSide()
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .size(size)
                .graphicsLayer { rotationZ = spin.value }
                .cachedDraw(ink) {
                    val path = polarPath(Path(), this.size, 8, 0.15f)
                    onDrawBehind { drawPath(path, ink) }
                }
        )
        if (bitmap != null) {
            Image(bitmap, contentDescription = nav.instruction, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(coral), modifier = Modifier.fillMaxSize(0.5f))
        } else {
            Spacer(
                Modifier
                    .fillMaxSize(0.56f)
                    .semantics { contentDescription = nav.instruction }
                    .cachedDraw(side, coral) {
                        val s = this.size.minDimension / 24f
                        val path = Path().apply {
                            when (side) {
                                TurnSide.AHEAD -> {
                                    moveTo(12f * s, 21f * s); lineTo(12f * s, 4f * s)
                                    moveTo(6.5f * s, 9.5f * s); lineTo(12f * s, 4f * s); lineTo(17.5f * s, 9.5f * s)
                                }
                                else -> {
                                    val m = side == TurnSide.LEFT
                                    fun x(v: Float) = (if (m) 24f - v else v) * s
                                    moveTo(x(7f), 21f * s); lineTo(x(7f), 12f * s)
                                    cubicTo(x(7f), 9.2f * s, x(9.2f), 7f * s, x(12f), 7f * s)
                                    lineTo(x(19f), 7f * s)
                                    moveTo(x(14.5f), 2.5f * s); lineTo(x(19f), 7f * s); lineTo(x(14.5f), 11.5f * s)
                                }
                            }
                        }
                        val stroke = Stroke(3.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        onDrawBehind { drawPath(path, coral, style = stroke) }
                    }
            )
        }
    }
}

// --- Speed --------------------------------------------------------------------------

/** Speed over the limit: coral on the dark pill by day, red on the pale one by night. */
private val overInk: Color get() = if (DashColors.Light) Color(0xFFFF8A6B) else Color(0xFFC62828)

/**
 * Speed: the tall dark pill (pale at night), the speed huge, heavy and
 * narrow, the unit under it and the road's limit sign below. A low wide
 * tile lays the pill on its side with the sign beside the figure. Without a
 * speed the pill offers to connect the adapter.
 */
@Composable
private fun ExSpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    val speed = rememberSpeedKmh()
    val limit by SpeedLimit.limit.collectAsState()
    val units = LocalUnits.current
    val over = speedOver(speed)
    val fill = ExTone.pill
    val ink = ExTone.onPill
    val color = when {
        speed == null -> ink.copy(alpha = 0.45f)
        over -> overInk
        else -> ink
    }
    val canConnect = speed == null && env.obdConnection.isIdle && !env.editing
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(R.string.info_speed_no_signal)
    val text = speed?.let { units.speed(it).toString() } ?: "–"
    val interaction = remember { MutableInteractionSource() }
    val connectLabel = stringResource(R.string.horizon_connect_obd)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val tall = maxHeight >= maxWidth * 0.9f && maxHeight >= 200.dp
        val pill = Modifier
            .exSquish(interaction)
            .exCard(fill, ExPill, editing = env.editing)
            .exClick(interaction, canConnect, connectLabel, env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said }
        val boxH = maxHeight
        val boxW = maxWidth
        if (tall) {
            val pillW = min(maxWidth.value, maxHeight.value * 0.62f).dp
            val sign = (pillW * 0.36f).coerceIn(44.dp, 80.dp)
            val numH = maxHeight * 0.4f
            val numSize = exFit("188", true, pillW * 0.84f, numH, 40f, 200f)
            Column(
                Modifier.width(pillW).fillMaxHeight().then(pill),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                ExLine(text, exText(if (speed != null) numSize else numSize * 0.6f, color, hero = true), overflow = TextOverflow.Clip)
                Spacer(Modifier.height(8.dp))
                ExLine(units.speedUnit, exText(18f, ink.copy(alpha = 0.72f), FontWeight.Medium))
                Spacer(Modifier.height((boxH.value * 0.05f).coerceIn(10f, 28f).dp))
                Box(Modifier.size(sign), contentAlignment = Alignment.Center) {
                    limit?.let { ExLimitSign(units.speed(it), sign) }
                }
            }
        } else {
            val h = maxHeight
            val sign = (h * 0.56f).coerceIn(40.dp, 76.dp)
            val numSize = exFit("188", true, maxWidth * 0.4f, h * 0.7f, 30f, 160f)
            Row(
                Modifier.fillMaxSize().then(pill).padding(horizontal = (h * 0.3f).coerceAtMost(40.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                ExLine(text, exText(if (speed != null) numSize else numSize * 0.6f, color, hero = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                Spacer(Modifier.width(8.dp))
                ExLine(units.speedUnit, exText(17f, ink.copy(alpha = 0.72f), FontWeight.Medium), Modifier.alignByBaseline())
                if (limit != null && boxW >= 260.dp) {
                    Spacer(Modifier.width(16.dp))
                    ExLimitSign(units.speed(limit!!), sign)
                }
            }
        }
    }
}

/** The road's limit as its sign: a white disc in a thick red ring, the number in black (a road sign keeps its colours). */
@Composable
private fun ExLimitSign(limit: Int, d: Dp) {
    val said = stringResource(R.string.expressive_speed_limit_cd, limit)
    Box(
        modifier = Modifier
            .size(d)
            .semantics { contentDescription = said }
            .cachedDraw(d) {
                val ring = size.minDimension * 0.11f
                onDrawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(Color.White, r)
                    drawCircle(Color(0xFFFF5B47), r - ring / 2f, style = Stroke(ring))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        ExLine(limit.toString(), exText((d.value * if (limit >= 100) 0.32f else 0.38f).coerceAtLeast(14f), Color(0xFF111111), FontWeight.ExtraBold), overflow = TextOverflow.Clip)
    }
}

// --- Media --------------------------------------------------------------------------

/** The music card's tones from the cover: [card] the fill, [ink] the words and keys on it. */
@Immutable
internal class ExArtTone(val card: Color, val ink: Color)

/**
 * The cover's main colour as (hue in degrees, saturation), from [pixels]
 * (ARGB): the most present hue among the coloured pixels, weighted toward
 * the vivid and the mid-light ones; null for a grey or black and white
 * cover, where the card keeps its lime.
 */
internal fun dominantHue(pixels: IntArray): FloatArray? {
    val weight = FloatArray(12)
    val sx = FloatArray(12)
    val sy = FloatArray(12)
    val sat = FloatArray(12)
    val hsl = FloatArray(3)
    var counted = 0
    for (p in pixels) {
        if ((p ushr 24) < 128) continue
        counted++
        rgbToHsl(p, hsl)
        if (hsl[1] < 0.22f || hsl[2] < 0.12f || hsl[2] > 0.9f) continue
        val w = hsl[1] * (1f - abs(hsl[2] - 0.5f) * 1.4f)
        val b = ((hsl[0] / 30f).toInt()).coerceIn(0, 11)
        val a = hsl[0] * (PI / 180.0).toFloat()
        weight[b] += w
        sx[b] += cos(a) * w
        sy[b] += sin(a) * w
        sat[b] += hsl[1] * w
    }
    if (counted == 0) return null
    var best = 0
    for (i in 1 until 12) if (weight[i] > weight[best]) best = i
    // Too little colour: a cover in greys keeps the card's own tone.
    if (weight[best] < counted * 0.04f) return null
    var hue = (atan2(sy[best], sx[best]) * 180.0 / PI).toFloat()
    if (hue < 0f) hue += 360f
    return floatArrayOf(hue, sat[best] / weight[best])
}

/** [argb]'s hue (0..360), saturation and lightness (0..1) into [out]. */
private fun rgbToHsl(argb: Int, out: FloatArray) {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d < 1e-4f) {
        out[0] = 0f; out[1] = 0f; out[2] = l
        return
    }
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (mx) {
        r -> ((g - b) / d).let { if (it < 0f) it + 6f else it }
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    out[0] = h
    out[1] = s.coerceIn(0f, 1f)
    out[2] = l
}

/** The card's tones for a cover of [hue] and [sat]: a bright tonal fill and a near-black ink of the same hue. */
internal fun artTone(hue: Float, sat: Float, light: Boolean): ExArtTone = ExArtTone(
    card = Color.hsl(hue, sat.coerceIn(0.5f, 0.85f), if (light) 0.76f else 0.68f),
    ink = Color.hsl(hue, 0.85f, 0.09f)
)

/** The cover's 24 x 24 pixels, made once per track off the main thread. */
private fun coverPixels(art: Bitmap): IntArray? = runCatching {
    val soft = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
    val small = Bitmap.createScaledBitmap(soft, 24, 24, true)
    IntArray(24 * 24).also { small.getPixels(it, 0, 24, 0, 0, 24, 24) }
}.getOrNull()

/** The music card's tones from [art] (worked out once per cover, off the main thread), or null to keep the lime. */
@Composable
private fun rememberArtTone(art: Bitmap?): ExArtTone? {
    val light = DashColors.Light
    val hue by produceState<FloatArray?>(null, art) {
        value = if (art == null) null else withContext(Dispatchers.Default) { coverPixels(art)?.let(::dominantHue) }
    }
    return remember(hue, light) { hue?.let { artTone(it[0], it[1], light) } }
}

/**
 * Music on its card, lime or the colour of the album art: the cover in a
 * morphing blob, the song heavy and narrow and who sings it, the wavy
 * progress line (a wave over the part played while the music plays, a
 * straight track after it), and the keys: two small rounds and the pill
 * play key that morphs, a round pill paused, a squircle playing. A low tile
 * becomes one row.
 */
@Composable
private fun ExMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        ExEmpty(
            Icons.Filled.MusicNote,
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access),
            ExTone.lime, ExTone.onLime, env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val tone = rememberArtTone(ms.artwork)
    val fill by animateColorAsState(tone?.card ?: ExTone.lime, tween(700), label = "exMediaFill")
    val ink by animateColorAsState(tone?.ink ?: ExTone.onLime, tween(700), label = "exMediaInk")
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    val cover = remember(ms.artwork) { ms.artwork?.asImageBitmap() }
    BoxWithConstraints(Modifier.fillMaxSize().exCard(fill, editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight) + 2.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val compact = maxHeight < 210.dp
        if (compact) {
            val key = (innerH * 0.7f).coerceIn(52.dp, 72.dp)
            Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                if (innerW >= 360.dp) {
                    ExArtBlob(cover, fill, ink, Modifier.size(innerH.coerceIn(48.dp, 110.dp)))
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    ExLine(title, exText((innerH.value * 0.24f).coerceIn(19f, 30f), ink, hero = true))
                    ExLine(sub, exText(16f, ink.copy(alpha = 0.75f), FontWeight.Medium))
                    if (ms.durationMs > 0L) {
                        Spacer(Modifier.height(4.dp))
                        ExWave(ms, env.mediaController, ink, Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.width(12.dp))
                ExKeys(env, key, ink, fill, prev = innerW >= 440.dp)
            }
        } else {
            val key = min((innerH.value * 0.22f), (innerW.value - 24f) / 3.9f).coerceIn(56f, 76f).dp
            val blob = min(innerH.value * 0.42f, innerW.value * 0.38f).coerceIn(64f, 150f).dp
            val titleSize = min(innerW.value / 9f, blob.value * 0.34f).coerceIn(22f, 42f)
            Column(Modifier.fillMaxSize().padding(pad)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExArtBlob(cover, fill, ink, Modifier.size(blob))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        ExLine(title, exText(titleSize, ink, hero = true), maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        ExLine(sub, exText((titleSize * 0.56f).coerceIn(16f, 22f), ink.copy(alpha = 0.75f), FontWeight.Medium))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (ms.durationMs > 0L) {
                    ExWave(ms, env.mediaController, ink, Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(0.6f))
                }
                ExKeys(env, key, ink, fill, prev = innerW >= key * 3.9f + 24.dp)
            }
        }
    }
}

/**
 * The cover in the morphing blob (a slow change of shape while parked, in its
 * own drawing, still with the effects off), or while the player gives none, a
 * sweep of the card's own tones.
 */
@Composable
private fun ExArtBlob(cover: ImageBitmap?, fill: Color, ink: Color, modifier: Modifier) {
    val morph = rememberLoop(7_000, reverse = true, rest = 0.3f)
    Spacer(
        modifier.cachedDraw(cover, fill, ink) {
            val path = Path()
            val sweep = Brush.sweepGradient(
                listOf(lerp(ink, fill, 0.15f), lerp(ink, fill, 0.55f), fill, lerp(fill, Color.White, 0.25f), lerp(ink, fill, 0.15f)),
                Offset(size.width / 2f, size.height / 2f)
            )
            // The cover as a shader, centre-cropped to the box: a filled path keeps its edge smooth, a clip would not.
            val coverBrush = cover?.let { c ->
                val k = max(size.width / c.width, size.height / c.height)
                val shader = ImageShader(c, TileMode.Clamp, TileMode.Clamp)
                shader.setLocalMatrix(android.graphics.Matrix().apply {
                    setScale(k, k)
                    postTranslate((size.width - c.width * k) / 2f, (size.height - c.height * k) / 2f)
                })
                ShaderBrush(shader)
            }
            onDrawBehind {
                blobPath(path, size, morph.value)
                drawPath(path, coverBrush ?: sweep)
            }
        }
    )
}

/**
 * The M3 Expressive progress line: a wave over the part played while the
 * music plays (it settles flat on pause, on a spring, and runs along only
 * while parked), a gap, then the straight track; in its own scope, so only
 * it follows the position.
 */
@Composable
private fun ExWave(ms: MediaState, controller: CarMediaController, ink: Color, modifier: Modifier) {
    val positionMs = rememberMediaPosition(ms, controller)
    val fraction = if (ms.durationMs > 0L) (positionMs.toFloat() / ms.durationMs).coerceIn(0f, 1f) else 0f
    val amp = animateFloatAsState(if (ms.isPlaying) 1f else 0f, spring(dampingRatio = 0.4f, stiffness = 200f), label = "exWave")
    val run = if (ms.isPlaying) rememberLoop(1_200) else null
    Spacer(
        modifier.height(26.dp).cachedDraw(fraction, ink) {
            val path = Path()
            val stroke = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val track = 4.dp.toPx()
            val waveLen = 24.dp.toPx()
            val height = 4.5.dp.toPx()
            val gap = 7.dp.toPx()
            val step = 3f * density
            val rest = ink.copy(alpha = 0.28f)
            onDrawBehind {
                val y = size.height / 2f
                val x0 = stroke.width / 2f
                val end = size.width - track / 2f
                val fw = (size.width * fraction).coerceIn(x0, end)
                if (fw + gap < end) drawLine(rest, Offset(fw + gap, y), Offset(end, y), track, StrokeCap.Round)
                val a = height * amp.value
                val shift = (run?.value ?: 0f) * 2f * PI.toFloat()
                path.reset()
                var x = x0
                path.moveTo(x, y + a * sin(x / waveLen * 2f * PI.toFloat() - shift))
                while (x < fw) {
                    x = min(x + step, fw)
                    path.lineTo(x, y + a * sin(x / waveLen * 2f * PI.toFloat() - shift))
                }
                drawPath(path, ink, style = stroke)
            }
        }
    )
}

/** Previous (when [prev]), the morphing play key and next, [h] tall; the small ones round and tonal. */
@Composable
private fun ExKeys(env: SkinTileEnv, h: Dp, ink: Color, fill: Color, prev: Boolean) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val c = env.mediaController
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (prev) ExRoundKey(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), h, ink, enabled) { c.previous() }
        ExPlayKey(playing, h, ink, fill, enabled) { c.playPause() }
        ExRoundKey(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), h, ink, enabled) { c.next() }
    }
}

@Composable
private fun ExRoundKey(icon: ImageVector, label: String, d: Dp, ink: Color, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(d)
            .exSquish(interaction, 0.88f)
            .exCard(ink.copy(alpha = 0.12f), ExPill, shadow = false)
            .exClick(interaction, enabled, label, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(d * 0.44f))
    }
}

/** Play / pause: a dark pill that morphs on a spring, round and shorter paused, a wider squircle playing. */
@Composable
private fun ExPlayKey(playing: Boolean, h: Dp, ink: Color, fill: Color, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play)
    val springy = spring<Dp>(dampingRatio = 0.5f, stiffness = 400f)
    val corner by animateDpAsState(if (playing) h * 0.3f else h / 2f, springy, label = "exPlayCorner")
    val width by animateDpAsState(if (playing) h * 2f else h * 1.75f, springy, label = "exPlayWidth")
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .size(width = width, height = h)
            .exSquish(interaction, 0.92f)
            .clip(shape)
            .background(ink, shape)
            .exClick(interaction, enabled, label, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = fill, modifier = Modifier.size(h * 0.46f))
    }
}

// --- Clock ----------------------------------------------------------------------------

/**
 * Clock: bare and bold on the page, as in the design: the time black and
 * tight, the date and the outside temperature beside it on a wide tile,
 * under it on a tall one. Tap opens the alarms.
 */
@Composable
private fun ExClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEdMMM", best = true)
    val temp = rememberOutsideTempC()
    val context = env.context
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .exSquish(interaction)
            .clip(ExCardShape)
            .exClick(interaction, !env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = (min(maxWidth.value, maxHeight.value) * 0.1f).coerceIn(10f, 24f).dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val locale = Locale.getDefault()
        val amPm = units.amPm(now, locale)
        val time = timeFmt.format(now)
        val date = capFirst(dateFmt.format(now))
        val second = listOfNotNull(date, temp?.let { "${units.temp(it)}°" }).joinToString(DOT)
        val row = maxWidth >= maxHeight * 2.2f
        if (row) {
            val timeSize = exFit(exTemplate(time), false, innerW * 0.56f, innerH * 0.86f, 28f, 160f, wide = true)
            val small = (timeSize * 0.27f).coerceIn(16f, 28f)
            Row(Modifier.padding(pad), verticalAlignment = Alignment.CenterVertically) {
                ExLine(time, exText(timeSize, DashColors.TextPrimary, wide = true), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width(6.dp))
                    ExLine(amPm, exText(small, DashColors.TextSecondary, FontWeight.Bold))
                }
                Spacer(Modifier.width((timeSize * 0.16f).dp))
                Column {
                    ExLine(date, exText(small, DashColors.TextPrimary, FontWeight.Bold))
                    if (temp != null) ExLine("${units.temp(temp)}°", exText(small, DashColors.TextSecondary, FontWeight.Medium))
                }
            }
        } else {
            val dateSize = min(innerW.value / 13f, innerH.value * 0.14f).coerceIn(15f, 28f)
            val timeSize = exFit(exTemplate(time), false, if (amPm != null) innerW * 0.8f else innerW, innerH - (dateSize * 1.4f + 6f).dp, 28f, 260f, wide = true)
            Column(Modifier.padding(pad)) {
                Row {
                    ExLine(time, exText(timeSize, DashColors.TextPrimary, wide = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                    if (amPm != null) {
                        Spacer(Modifier.width(6.dp))
                        ExLine(amPm, exText((timeSize * 0.24f).coerceAtLeast(15f), DashColors.TextSecondary, FontWeight.Bold), Modifier.alignByBaseline())
                    }
                }
                Spacer(Modifier.height(6.dp))
                ExLine(second, exText(dateSize, DashColors.TextSecondary, FontWeight.Medium))
            }
        }
    }
}

// --- Weather ----------------------------------------------------------------------------

/**
 * Weather on the sky-blue card: the sky's icon in a pale flower, the
 * temperature heavy and narrow, the sky in words, the feel and the wind, and
 * rain to come as a white chip. While there is nothing yet, it says why.
 */
@Composable
private fun ExWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    val fill = ExTone.sky
    val ink = ExTone.onSky
    BoxWithConstraints(Modifier.fillMaxSize().exCard(fill, editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight) + 2.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        if (w == null) {
            val title = when (wait) {
                WeatherWait.LOADING -> R.string.horizon_loading
                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
            }
            Column(Modifier.padding(pad)) {
                ExLine("--°", exText(exFit("88°", true, innerW * 0.5f, innerH * 0.5f, 28f, 120f), ink.copy(alpha = 0.5f), hero = true))
                ExLine(stringResource(title), exText(17f, ink, FontWeight.Medium), maxLines = 2)
            }
            return@BoxWithConstraints
        }
        val rain = when {
            w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs -> stringResource(R.string.expressive_rain_until, timeFmt.format(Date(w.rainUntilMs)))
            w.rainFromMs != null -> stringResource(R.string.expressive_rain_from, timeFmt.format(Date(w.rainFromMs)))
            else -> null
        }
        val temp = "${units.temp(w.tempC)}°"
        val roomy = innerH >= 180.dp
        val icon = min(innerH.value * (if (roomy) 0.4f else 0.7f), innerW.value * 0.3f).coerceIn(44f, 120f).dp
        val condSize = min(innerW.value / 14f, innerH.value * 0.13f).coerceIn(16f, 26f)
        val tempSize = exFit(exTemplate(temp), true, innerW - icon - 20.dp, if (roomy) icon else innerH * 0.6f, 28f, 150f)
        val feels = stringResource(R.string.horizon_feels, units.temp(w.feelsC))
        val wind = stringResource(if (units.imperial) R.string.units_horizon_wind_mph else R.string.horizon_wind, units.speed(w.windKmh))
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExShapeIcon(weatherIcon(w.code), ExFlower, lerp(fill, Color.White, 0.6f), ink, icon)
                Spacer(Modifier.width(20.dp))
                Column {
                    ExLine(temp, exText(tempSize, ink, hero = true), overflow = TextOverflow.Clip)
                    if (!roomy) ExLine(w.condition, exText(condSize, ink.copy(alpha = 0.8f), FontWeight.SemiBold))
                }
            }
            if (roomy) {
                Spacer(Modifier.weight(1f))
                ExLine(w.condition, exText(condSize, ink, FontWeight.Bold))
                ExLine("$feels$DOT$wind", exText((condSize * 0.78f).coerceAtLeast(15f), ink.copy(alpha = 0.75f), FontWeight.Medium))
                if (rain != null && innerH >= 230.dp) {
                    Spacer(Modifier.height(10.dp))
                    ExChip(rain, ExTone.dotRain, 17f)
                }
            }
        }
    }
}

// --- Telemetry ----------------------------------------------------------------------------

/**
 * The engine on the lilac card: the gear in a dark flower, the revs heavy
 * and narrow, then the readings as white chips (coolant, battery, load) where
 * they fit, a reading out of range in amber. Without the link, the card says
 * so and offers to connect.
 */
@Composable
private fun ExTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val fill = ExTone.lilac
    val ink = ExTone.onLilac
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .exSquish(interaction)
            .exCard(fill, editing = env.editing)
            .exClick(interaction, state.isIdle && !env.editing, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
    ) {
        val pad = padFor(maxWidth, maxHeight) + 2.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val flower = min(innerH.value * 0.46f, innerW.value * 0.3f).coerceIn(48f, 130f).dp
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TelemetryGear(flower, ink, fill)
                Spacer(Modifier.width(18.dp))
                TelemetryRevs(innerW - flower - 18.dp, flower, ink)
            }
            if (innerH >= 150.dp) {
                Spacer(Modifier.height(10.dp))
                TelemetryChips(env, ink)
            }
        }
    }
}

@Composable
private fun TelemetryGear(size: Dp, ink: Color, fill: Color) {
    val gear by rememberGear()
    Box(Modifier.size(size).exCard(ink, ExFlower, shadow = false), contentAlignment = Alignment.Center) {
        ExLine(gearMark(gear.gear), exText(size.value * 0.42f, fill, hero = true), overflow = TextOverflow.Clip)
    }
}

@Composable
private fun TelemetryRevs(w: Dp, h: Dp, ink: Color) {
    val gear by rememberGear()
    val rpm = gear.rpm
    val size = exFit("8 888", true, w * 0.8f, h * 0.7f, 26f, 110f)
    Row {
        ExLine(rpm?.let { groupThousands(it) } ?: "--", exText(size, ink, hero = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
        Spacer(Modifier.width(6.dp))
        ExLine(stringResource(R.string.expressive_rpm), exText((size * 0.3f).coerceAtLeast(15f), ink.copy(alpha = 0.75f), FontWeight.Bold), Modifier.alignByBaseline())
    }
}

/** The engine's readings as chips, or the link's state and its buttons while it is down. */
@Composable
private fun TelemetryChips(env: SkinTileEnv, ink: Color) {
    val state = env.obdConnection
    val units = LocalUnits.current
    // Whatever the car answers is shown, adapter or not.
    val g = rememberPanelGauges(K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    val gear by rememberGear()
    when {
        state == ObdConnectionState.CONNECTED || g.any || gear.rpm != null -> {
            ExFitRow(10.dp) {
                g.coolantTempC?.let {
                    ExChip("${stringResource(R.string.expressive_coolant)} ${units.temp(it)}°", if (it >= COOLANT_WARNING_C) DashColors.Warning else DashColors.Good, 17f)
                }
                g.voltage?.let {
                    ExChip(String.format(Locale.getDefault(), "%s %.1f V", stringResource(R.string.expressive_battery), it), if (it !in BATTERY_OK_V) DashColors.Warning else DashColors.Good, 17f)
                }
                g.engineLoadPct?.let { ExChip("${stringResource(R.string.expressive_load)} $it %", ExTone.dotRain, 17f) }
            }
        }
        state == ObdConnectionState.CONNECTING -> ExLine(stringResource(R.string.horizon_obd_connecting), exText(18f, ink, FontWeight.Bold))
        else -> ExFitRow(10.dp) {
            ExPillButton(stringResource(R.string.horizon_connect_obd), ink, ExTone.lilac, !env.editing, env.onConnectObd)
            if (state == ObdConnectionState.ERROR) {
                ExPillButton(stringResource(R.string.tape_cd_choose_adapter), ExTone.chip, DashColors.TextPrimary, !env.editing, env.onPickDevice)
            }
        }
    }
}

// --- Apps --------------------------------------------------------------------------

/** App shortcut: a white squircle card with the app's icon and its name, side by side on a wide tile, stacked on a square one. */
@Composable
private fun ExApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .exSquish(interaction)
            .exCard(ExTone.chip, RoundedCornerShape(30.dp), editing = env.editing)
            .exClick(interaction, !env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
        if (row) {
            val icon = (maxHeight.value * 0.5f).coerceIn(30f, 72f).dp
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                ExAppGlyph(app, icon)
                Spacer(Modifier.width(14.dp))
                ExLine(label, exText((icon.value * 0.4f).coerceIn(16f, 26f), DashColors.TextPrimary, FontWeight.Bold), Modifier.weight(1f, fill = false))
            }
        } else {
            val icon = (min(maxWidth.value, maxHeight.value) * 0.42f).coerceIn(30f, 96f).dp
            val named = maxHeight >= 96.dp
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ExAppGlyph(app, icon)
                if (named) {
                    Spacer(Modifier.height(8.dp))
                    ExLine(label, exText((icon.value * 0.3f).coerceIn(14f, 22f), DashColors.TextPrimary, FontWeight.Bold), align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun ExAppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.8f))
}

/**
 * Launch bar: a row of white squircle keys, one per app (the icon, and its
 * name where there is room), and a round tonal pencil at the end. Apps that
 * would get less than a 64 dp key are left out.
 */
@Composable
private fun ExLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 12.dp
        val edit = min(maxHeight.value, 64f).coerceAtLeast(56f).dp
        val room = maxWidth - edit - gap
        val fit = ((room + gap).value / (64f + gap.value)).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else (room.value - gap.value * (pkgs.size - 1)) / pkgs.size
        val labels = per >= 150f
        val icon = (maxHeight.value * 0.5f).coerceIn(24f, 48f).dp
        val labelSize = (maxHeight.value * 0.22f).coerceIn(14f, 20f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight().exCard(ExTone.chip, ExPill, editing = env.editing).padding(horizontal = 22.dp), contentAlignment = Alignment.CenterStart) {
                    ExLine(stringResource(R.string.expressive_launch_bar_empty), exText(labelSize, DashColors.TextSecondary), maxLines = 2)
                }
            } else {
                pkgs.forEach { pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    val interaction = remember(pkg) { MutableInteractionSource() }
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .widthIn(min = 64.dp)
                            .exSquish(interaction, 0.9f)
                            .exCard(ExTone.chip, RoundedCornerShape(26.dp), editing = env.editing)
                            .exClick(interaction, !env.editing, stringResource(R.string.horizon_open_app, name)) { env.onLaunchApp(pkg) }
                            .semantics(mergeDescendants = true) { contentDescription = name }
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExAppGlyph(app, icon)
                        if (labels) {
                            Spacer(Modifier.width(12.dp))
                            ExLine(name, exText(labelSize, DashColors.TextPrimary, FontWeight.Bold), Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
            val interaction = remember { MutableInteractionSource() }
            val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
            Box(
                Modifier
                    .size(edit)
                    .exSquish(interaction, 0.88f)
                    .exCard(ExTone.badge, ExPill, shadow = false)
                    .exClick(interaction, true, editLabel) { env.onEditLaunchBar() }
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The fuel's level on a small tile: a periwinkle card with the range heavy and a pill gauge. */
@Composable
internal fun ExRangeCard(fuel: FuelInfo, editing: Boolean) {
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    val ink = DashColors.TextPrimary
    BoxWithConstraints(Modifier.fillMaxSize().exCard(ExTone.badge, editing = editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val gaugeH = (innerH * 0.12f).coerceIn(12.dp, 24.dp)
        val numSize = exFit("888", true, innerW * 0.6f, innerH - gaugeH - 44.dp, 26f, 140f)
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExShapeIcon(Icons.Filled.Speed, ExFlower, ExTone.pill, ExTone.onPill, 34.dp)
                Spacer(Modifier.width(10.dp))
                ExLine(stringResource(R.string.expressive_fuel), exText(18f, ink, FontWeight.Bold), Modifier.weight(1f))
                ExLine("${fuel.percent} %", exText(18f, if (low) DashColors.Warning else ink, FontWeight.Bold))
            }
            Row {
                ExLine(units.distance(fuel.rangeKm).toString(), exText(numSize, if (low) DashColors.Warning else ink, hero = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                Spacer(Modifier.width(6.dp))
                ExLine(units.distanceUnit, exText((numSize * 0.3f).coerceAtLeast(16f), ink, FontWeight.Bold), Modifier.alignByBaseline())
            }
            val share = (fuel.percent / 100f).coerceIn(0f, 1f)
            val bar = if (low) DashColors.Warning else ExTone.pill
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(gaugeH)
                    .cachedDraw(share, bar, ink) {
                        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                        onDrawBehind {
                            drawRoundRect(ink.copy(alpha = 0.14f), cornerRadius = r)
                            if (share > 0f) drawRoundRect(bar, size = Size(max(size.height, size.width * share), size.height), cornerRadius = r)
                        }
                    }
            )
        }
    }
}
