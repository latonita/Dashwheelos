package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberDoors
import com.openauto.dash.vehicle.rememberGauge
import com.openauto.dash.vehicle.rememberGaugeInt
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.rememberTyres
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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Panorama skin, the tiles (the look itself is in PanoramaSkin.kt): the
 * speed is the car's curved glass band of lit segments, revs on its left and
 * fuel on its right (the next turn takes the revs' place while guiding); the
 * other readings stand on panes of blue glass, the fuel and range tile
 * becomes the stage of the car on its plinth when it has the room
 * (PanoramaSkinCar.kt), and the clock and weather stand bare as the corners'
 * readouts.
 */

private const val DOT = " · "

/** Segments on the band's rev and fuel scales, as on the car's display. */
private const val REV_SEGMENTS = 30
private const val FUEL_SEGMENTS = 20

/** Room around a card's content, following the tile's smaller side. */
private fun padFor(w: Dp, h: Dp): Dp = (min(w.value, h.value) * 0.08f).coerceIn(12f, 22f).dp

/**
 * Panorama renderer for app shortcuts, launch bars and the main widgets.
 * Anything else keeps its standard renderer.
 */
@Composable
internal fun PanoramaTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> PanoApp(item, env)
        is DashboardItem.LaunchBar -> PanoLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> PanoTelemetry(env)
            BuiltinKind.SPEED_HUD -> PanoSpeed(env)
            BuiltinKind.MEDIA -> PanoMedia(env)
            BuiltinKind.NAVIGATION -> PanoDirections(env)
            BuiltinKind.CLOCK -> PanoClock(env)
            BuiltinKind.WEATHER -> PanoWeather(env)
            BuiltinKind.RANGE -> PanoRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

/** The glass behind a widget Panorama does not redraw ([SkinFallbackPanel]). */
@Composable
internal fun Modifier.panoFallbackPanel(): Modifier = panoGlass()

// --- Small pieces -----------------------------------------------------------------

/**
 * A scale of [count] lit segments, as the car's display draws them: [lit] of
 * them in the lit ink (from the end when [fromEnd]), those from [warmFrom]
 * on in amber (the revs past the redline), or all of them when [warm] (the
 * reserve). Lit segments glow at night; the rest stay faint.
 */
@Composable
private fun PanoSegments(count: Int, lit: Int, modifier: Modifier, fromEnd: Boolean = false, warmFrom: Int = Int.MAX_VALUE, warm: Boolean = false) {
    val on = DashColors.Accent
    val amber = DashColors.Tacho
    val off = panoSegOff
    val glowK = panoGlow
    Spacer(
        modifier.cachedDraw(count, lit, fromEnd, warmFrom, warm, on, amber, off, glowK) {
            val w = size.width
            val h = size.height
            // The design's 8 wide, 4 apart: a segment and half a gap each.
            val seg = w / (count * 1.5f - 0.5f)
            val gap = seg * 0.5f
            val r = CornerRadius(min(seg * 0.3f, 2.dp.toPx()))
            val halo = 3.dp.toPx()
            onDrawBehind {
                for (i in 0 until count) {
                    val x = i * (seg + gap)
                    val isLit = if (fromEnd) i >= count - lit else i < lit
                    val color = when {
                        !isLit -> off
                        warm || i >= warmFrom -> amber
                        else -> on
                    }
                    if (isLit && glowK > 0f) {
                        drawRoundRect(color.copy(alpha = 0.22f * glowK), Offset(x - halo, -halo * 0.6f), Size(seg + 2f * halo, h + 1.2f * halo), CornerRadius(r.x + halo))
                    }
                    drawRoundRect(color, Offset(x, 0f), Size(seg, h), r)
                }
            }
        }
    )
}

/** The engraved caps label of the band and the cards ("REVS", "NEXT TURN"). */
@Composable
private fun PanoLabel(text: String, modifier: Modifier = Modifier, size: Float = 14f, align: TextAlign? = null) {
    PanoLine(text, panoText(size, DashColors.TextSecondary, FontWeight.Medium, caps = true), modifier, align = align, caps = true)
}

/** A rounded square of glass with an icon, a soft round under it in the cards' quiet states. */
@Composable
private fun PanoGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp) {
    Box(
        Modifier.size(size).panoGlass(size * 0.3f),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * A quiet state on its pane: an icon, a title and one hint; the hint is in
 * the accent's ink when a tap does something, and the whole pane is the target.
 */
@Composable
private fun PanoEmpty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String, editing: Boolean, onTap: (() -> Unit)?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer()
            .panoGlass(editing = editing)
            .panoTap(onTap != null && !editing, hint) { onTap?.invoke() },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val wide = maxWidth >= maxHeight * 1.3f
        val roomy = maxHeight >= 200.dp
        val iconD = min(maxHeight.value * 0.34f, maxWidth.value * 0.22f).coerceIn(40f, 84f).dp
        val textW = if (wide) maxWidth - pad * 2 - iconD - 16.dp else maxWidth - pad * 2
        val titleSize = min(maxHeight.value * 0.13f, textW.value / (title.length * 0.42f + 1f)).coerceIn(17f, 30f)
        val hintSize = (titleSize * 0.72f).coerceIn(14f, 20f)
        val words: @Composable () -> Unit = {
            Column {
                PanoLine(title, panoText(titleSize, DashColors.TextPrimary, FontWeight.Medium), maxLines = 2)
                Spacer(Modifier.height(4.dp))
                PanoLine(hint, panoText(hintSize, if (onTap != null) DashColors.AccentInk else DashColors.TextSecondary), maxLines = 2)
            }
        }
        if (wide) {
            Row(Modifier.padding(pad), verticalAlignment = Alignment.CenterVertically) {
                PanoGlyph(icon, iconD)
                Spacer(Modifier.width(16.dp))
                words()
            }
        } else {
            Column(Modifier.padding(pad)) {
                if (roomy) {
                    PanoGlyph(icon, iconD)
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

/** Largest size (dp, for [panoText]) within [minSize]..[maxSize] at which [sample] fits [maxW] x [maxH] on one line. */
@Composable
private fun panoFit(sample: String, thin: Boolean, weight: FontWeight, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = panoText(100f, weight = weight, thin = thin)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** The first of [candidates] (longest first) that fits [maxW] on one line, else the last. */
@Composable
private fun firstFitting(candidates: List<String>, size: Float, weight: FontWeight, maxW: Dp): String {
    val measurer = rememberTextMeasurer()
    val style = panoText(size, weight = weight)
    val px = with(LocalDensity.current) { maxW.toPx() }
    return remember(candidates, style, px, measurer) {
        candidates.firstOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= px } ?: candidates.last()
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
private fun template(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** The gear's mark: R, 1 to 6, N while free-wheeling, a dash when it can't be told. */
private fun gearMark(gear: Gear): String = when (gear) {
    Gear.Reverse -> "R"
    is Gear.Forward -> gear.n.toString()
    Gear.Free -> "N"
    Gear.Park -> "P"
    Gear.Drive -> "D"
    Gear.Stopped, Gear.Unknown -> "–"
}

/** The revs where the scale turns amber: the car's redline when known, else a diesel's. */
private fun warmSegment(count: Int): Int {
    val redline = CarProfileStore.current.redlineRpm ?: 4_500
    return (redline / SKIN_RPM_MAX * count).roundToInt().coerceIn(1, count)
}

private fun revSegments(rpm: Int?, count: Int): Int = ((rpm ?: 0) / SKIN_RPM_MAX * count).roundToInt().coerceIn(0, count)

// --- The band (speed) ---------------------------------------------------------------

/**
 * Speed. A wide tile is the car's curved glass band: the revs as lit
 * segments on the left with the r/min and the gear (the next turn in their
 * place while a route is guided), the speed large and thin in the middle
 * with the road's limit sign and a gear box beside it, the fuel's segments
 * with the range and the coolant on the right. A light sweeps across the
 * glass now and then while parked. A smaller tile keeps the speed, the limit
 * and the gear on a glass card with two short scales under it.
 */
@Composable
private fun PanoSpeed(env: SkinTileEnv) {
    val context = env.context
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val band = maxWidth >= 560.dp && maxWidth >= maxHeight * 2.6f && maxHeight >= 110.dp
        if (band) PanoBand(env, maxWidth, maxHeight) else PanoSpeedCard(env)
    }
}

/** The band's outline in a [w] x [h] box: the domed top of the curved glass, its sides leaning in a little, round lower corners. */
private fun bandPath(w: Float, h: Float, arch: Float, lean: Float, corner: Float): Path = Path().apply {
    val k = 0.5523f
    val rx = w * 0.48f - lean
    moveTo(lean, arch)
    cubicTo(lean, arch * (1f - k), lean + rx * (1f - k), 0f, lean + rx, 0f)
    lineTo(w - lean - rx, 0f)
    cubicTo(w - lean - rx * (1f - k), 0f, w - lean, arch * (1f - k), w - lean, arch)
    lineTo(w, h - corner)
    quadraticTo(w, h, w - corner, h)
    lineTo(corner, h)
    quadraticTo(0f, h, 0f, h - corner)
    close()
}

/** The domed top alone, for its catch of light. */
private fun bandTopPath(w: Float, arch: Float, lean: Float, inset: Float): Path = Path().apply {
    val k = 0.5523f
    val rx = w * 0.48f - lean
    moveTo(lean + inset, arch)
    cubicTo(lean + inset, arch * (1f - k) + inset, lean + rx * (1f - k), inset, lean + rx, inset)
    lineTo(w - lean - rx, inset)
    cubicTo(w - lean - rx * (1f - k), inset, w - lean - inset, arch * (1f - k) + inset, w - lean - inset, arch)
}

/** How high the band's dome rises over its sides, for a band [h] tall. */
private fun bandArch(h: Dp): Dp = (h * 0.36f).coerceAtMost(60.dp)

@Composable
private fun PanoBand(env: SkinTileEnv, w: Dp, h: Dp) {
    val nav by NavDirections.state.collectAsState()
    val guiding = nav.active && (nav.distance.isNotEmpty() || nav.instruction.isNotEmpty())
    val arch = bandArch(h)
    Box(Modifier.fillMaxSize()) {
        BandGlass(arch, env.editing, Modifier.fillMaxSize())
        BandSweep(arch, Modifier.fillMaxSize())
        val sideW = w * 0.31f
        val padX = (w.value * 0.028f).coerceIn(18f, 40f).dp
        val readSize = (h.value * 0.13f).coerceIn(15f, 24f)
        Row(
            Modifier.fillMaxSize().padding(start = padX, end = padX, top = arch * 0.42f, bottom = (h * 0.08f).coerceAtLeast(8.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(sideW - padX).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (guiding) BandTurn(nav, h, readSize, env) else BandRevs(h, readSize)
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { BandSpeed(env, h) }
            Box(Modifier.width(sideW - padX).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) { BandFuel(env, h, readSize) }
        }
    }
}

/**
 * The band's glass: a deep shadow under it, the glass brighter at its top,
 * a blue glow along its foot, its rim and the catch of light along the dome,
 * in its own layer, drawn once per size. By day pearl glass on a soft shadow.
 */
@Composable
private fun BandGlass(arch: Dp, editing: Boolean, modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val card = DashColors.Card
    val rimInk = panoRim
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(light, effects, card, rimInk, arch, editing) {
                val w = size.width
                val h = size.height
                val a = arch.toPx()
                val lean = w * 0.012f
                val corner = min(26.dp.toPx(), h * 0.2f)
                val outline = bandPath(w, h, a, lean, corner)
                val top = bandTopPath(w, a, lean, 1.5f * density)
                val plain = effects == DashEffects.NONE
                val fill: Brush = when {
                    plain -> Brush.verticalGradient(listOf(card, card))
                    light -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.96f), Color.White.copy(alpha = 0.82f), Color.White.copy(alpha = 0.7f)))
                    else -> Brush.verticalGradient(listOf(Color(0x2EA0D2FF), Color(0x1478B4FF), Color(0x0D000000)), 0f, h)
                }
                val foot = if (!plain && !light) Brush.verticalGradient(listOf(Color.Transparent, Color(0x1A3C8CFF)), h * 0.55f, h) else null
                val rimStroke = Stroke((if (editing) 1.5f else 1f) * density, pathEffect = if (editing) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)) else null)
                val catch = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = if (light) 1f else 0.4f), Color.White.copy(alpha = if (light) 1f else 0.4f), Color.Transparent),
                    w * 0.08f, w * 0.92f
                )
                val shadowInk = if (light) Color(0xFF14284A) else Color.Black
                val shadow = if (plain) null else if (light) floatArrayOf(4f, 0.05f, 9f, 0.035f, 15f, 0.022f, 22f, 0.012f)
                else floatArrayOf(5f, 0.16f, 11f, 0.11f, 18f, 0.07f, 26f, 0.04f)
                onDrawBehind {
                    if (shadow != null) {
                        var i = 0
                        while (i < shadow.size) {
                            translate(0f, shadow[i] * density) { drawPath(outline, shadowInk.copy(alpha = shadow[i + 1])) }
                            i += 2
                        }
                    }
                    drawPath(outline, fill)
                    if (foot != null) drawPath(outline, foot)
                    drawPath(outline, rimInk, style = rimStroke)
                    if (!plain) drawPath(top, catch, style = Stroke(1.2f * density, cap = StrokeCap.Round))
                }
            }
    )
}

/**
 * The light sweeping across the band's glass every six seconds, as in the
 * design: ambient motion (still while the car moves, with effects off, on a
 * hidden page), in its own layer, read only while drawing.
 */
@Composable
private fun BandSweep(arch: Dp, modifier: Modifier) {
    if (DashColors.Effects == DashEffects.NONE) return
    val sweep = rememberLoop(6_000)
    val light = DashColors.Light
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(arch, light, sweep) {
                val w = size.width
                val h = size.height
                val outline = bandPath(w, h, arch.toPx(), w * 0.012f, min(26.dp.toPx(), h * 0.2f))
                val bandW = w * 0.22f
                val shine = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = if (light) 0.5f else 0.11f), Color.Transparent), 0f, bandW
                )
                val slant = h * 0.14f
                val streak = Path().apply {
                    moveTo(slant, 0f); lineTo(bandW + slant, 0f); lineTo(bandW - slant, h); lineTo(-slant, h); close()
                }
                onDrawBehind {
                    // The design's 0..60 % of the period crosses the glass; the rest is a pause.
                    val p = (sweep.value / 0.6f).coerceAtMost(1f)
                    if (p >= 1f) return@onDrawBehind
                    val x = -bandW + p * (w + bandW * 1.3f)
                    clipPath(outline) { translate(x, 0f) { drawPath(streak, shine) } }
                }
            }
    )
}

/** The band's left: REVS, the rev segments and the r/min with the gear. */
@Composable
private fun BandRevs(h: Dp, readSize: Float) {
    val gear by rememberGear()
    val rpm = gear.rpm
    val count = REV_SEGMENTS
    val lit = revSegments(rpm, count)
    val warmFrom = remember { warmSegment(count) }
    val rpmUnit = stringResource(R.string.skin_panorama_rpm)
    val gearWord = stringResource(R.string.skin_panorama_gear, gearMark(gear.gear))
    BoxWithConstraints {
        val tailW = maxWidth * 0.6f
        Column {
            PanoLabel(stringResource(R.string.skin_panorama_revs))
            Spacer(Modifier.height(7.dp))
            PanoSegments(count, lit, Modifier.fillMaxWidth().height((h * 0.16f).coerceIn(14.dp, 30.dp)), warmFrom = warmFrom)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                PanoLine(rpm?.let { groupThousands(it) } ?: "--", panoText(readSize * 1.3f, DashColors.TextPrimary, FontWeight.Bold), Modifier.alignByBaseline())
                Spacer(Modifier.width(5.dp))
                val tail = firstFitting(listOf("$rpmUnit$DOT$gearWord", rpmUnit), readSize, FontWeight.Normal, tailW)
                PanoLine(tail, panoText(readSize, DashColors.TextSecondary), Modifier.alignByBaseline())
            }
        }
    }
}

/** The band's left while guiding: the turn's arrow glowing, the distance large and thin, the street under it. */
@Composable
private fun BandTurn(nav: NavState, h: Dp, readSize: Float, env: SkinTileEnv) {
    val context = env.context
    val arrow = (h * 0.4f).coerceIn(40.dp, 72.dp)
    Row(
        Modifier.panoTap(!env.editing, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        PanoTurnArrow(nav, arrow)
        Spacer(Modifier.width(14.dp))
        Column {
            val dist = nav.distance.ifEmpty { nav.instruction }
            PanoLine(dist, panoText(readSize * 1.85f, DashColors.TextPrimary, FontWeight.Light, thin = true))
            if (nav.distance.isNotEmpty() && nav.instruction.isNotEmpty()) {
                PanoLine(nav.instruction, panoText((readSize * 0.82f).coerceAtLeast(14f), DashColors.TextSecondary), maxLines = 1)
            }
        }
    }
}

/** The band's middle: the limit sign, the speed large, thin and glowing, KM/H under it, the gear in its box. */
@Composable
private fun BandSpeed(env: SkinTileEnv, h: Dp) {
    val speed = rememberSpeedKmh()
    val limit by SpeedLimit.limit.collectAsState()
    val gear by rememberGear()
    val units = LocalUnits.current
    val over = speedOver(speed)
    val color = when {
        speed == null -> DashColors.Muted
        over -> DashColors.Warning
        else -> DashColors.TextPrimary
    }
    val canConnect = speed == null && env.obdConnection.isIdle && !env.editing
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(R.string.info_speed_no_signal)
    val numSize = (h.value * 0.58f).coerceIn(40f, 116f)
    val glow = softTextShadow(if (over) DashColors.Warning.copy(alpha = 0.6f) else DashColors.Accent.copy(alpha = 0.55f), 28f)
    Row(
        Modifier
            .panoTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val sign = (numSize * 0.5f).coerceIn(40f, 64f).dp
        Box(Modifier.size(sign), contentAlignment = Alignment.Center) {
            limit?.let { PanoLimitSign(units.speed(it), sign) }
        }
        Spacer(Modifier.width(18.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val text = speed?.let { units.speed(it).toString() } ?: "–"
            PanoLine(
                text,
                panoText(if (speed != null) numSize else numSize * 0.6f, color, FontWeight.Light, thin = true).copy(shadow = if (DashColors.Light) null else glow),
                overflow = TextOverflow.Clip
            )
            PanoLine(units.speedUnit, panoText(15f, DashColors.TextSecondary, caps = true), caps = true)
        }
        Spacer(Modifier.width(18.dp))
        // No speed, no gear: a gear held from the last reading would read as live.
        Box(Modifier.size(sign), contentAlignment = Alignment.Center) { PanoGearBox(if (speed != null) gearMark(gear.gear) else "–", (sign * 0.8f)) }
    }
}

/** The gear in its small glass box, glowing in the lit ink. */
@Composable
private fun PanoGearBox(mark: String, size: Dp) {
    val glow = softTextShadow(DashColors.Accent.copy(alpha = 0.6f), 14f)
    Box(Modifier.size(size).panoGlass(size * 0.26f), contentAlignment = Alignment.Center) {
        PanoLine(mark, panoText(size.value * 0.6f, DashColors.Accent, FontWeight.Normal, thin = true).copy(shadow = if (DashColors.Light) null else glow), overflow = TextOverflow.Clip)
    }
}

/** The road's limit as its sign: a white disc in a red ring, the number in black (a road sign keeps its colours day and night). */
@Composable
private fun PanoLimitSign(limit: Int, d: Dp) {
    val said = stringResource(R.string.skin_panorama_speed_limit_cd, limit)
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
        PanoLine(limit.toString(), panoText((d.value * if (limit >= 100) 0.32f else 0.4f).coerceAtLeast(14f), Color(0xFF111111), FontWeight.Bold), overflow = TextOverflow.Clip)
    }
}

/** The band's right: FUEL with the range, the fuel's segments lit from the end, the coolant under them. */
@Composable
private fun BandFuel(env: SkinTileEnv, h: Dp, readSize: Float) {
    val fuel = rememberFuel()
    val coolant = rememberGaugeInt(K.COOLANT_TEMP) ?: 0
    val units = LocalUnits.current
    val low = fuel != null && fuel.percent <= SKIN_LOW_FUEL_PCT
    val label = fuel?.let { stringResource(R.string.skin_panorama_fuel_range, units.distanceText(it.rangeKm)) } ?: stringResource(R.string.skin_panorama_fuel)
    val lit = fuel?.let { (it.percent / 100f * FUEL_SEGMENTS).roundToInt().coerceIn(if (it.percent > 0) 1 else 0, FUEL_SEGMENTS) } ?: 0
    Column(horizontalAlignment = Alignment.End) {
        PanoLabel(label, align = TextAlign.End)
        Spacer(Modifier.height(7.dp))
        PanoSegments(FUEL_SEGMENTS, lit, Modifier.fillMaxWidth(0.72f).height((h * 0.16f).coerceIn(14.dp, 30.dp)), fromEnd = true, warm = low)
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            PanoLine(stringResource(R.string.skin_panorama_coolant), panoText(readSize, DashColors.TextSecondary), Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            PanoLine(
                if (coolant > 0) "${units.temp(coolant)}°" else "--",
                panoText(readSize * 1.3f, if (coolant >= COOLANT_WARNING_C) DashColors.Warning else DashColors.TextPrimary, FontWeight.Bold),
                Modifier.alignByBaseline()
            )
        }
    }
}

/**
 * The speed on a glass card, for a tile too small for the band: the limit
 * sign and the gear box in the top corners, the speed large in the middle,
 * and with the room two short scales under it, the revs and the fuel.
 */
@Composable
private fun PanoSpeedCard(env: SkinTileEnv) {
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
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .panoGlass(editing = env.editing)
            .panoTap(canConnect, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said }
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val scales = innerH >= 170.dp && innerW >= 170.dp
        val corner = (min(innerW.value, innerH.value) * 0.24f).coerceIn(36f, 60f).dp
        val corners = innerW >= corner * 3.2f && innerH >= 110.dp
        val below = (if (scales) 40f else 0f) + 22f
        val numH = innerH - below.dp - if (corners && innerW < corner * 4.6f) corner * 0.6f else 0.dp
        val numSize = panoFit("188", thin = true, weight = FontWeight.Light, maxW = innerW * 0.78f, maxH = numH, minSize = 30f, maxSize = 200f)
        val glow = softTextShadow(if (over) DashColors.Warning.copy(alpha = 0.6f) else DashColors.Accent.copy(alpha = 0.5f), 26f)
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (corners) {
                limit?.let { Box(Modifier.align(Alignment.TopStart)) { PanoLimitSign(units.speed(it), corner) } }
                Box(Modifier.align(Alignment.TopEnd)) { CardGear(corner * 0.86f) }
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                PanoLine(
                    speed?.let { units.speed(it).toString() } ?: "–",
                    panoText(if (speed != null) numSize else numSize * 0.6f, color, FontWeight.Light, thin = true).copy(shadow = if (DashColors.Light) null else glow),
                    overflow = TextOverflow.Clip
                )
                PanoLine(units.speedUnit, panoText(15f, DashColors.TextSecondary, caps = true), caps = true)
            }
            if (scales) {
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CardRevScale(Modifier.weight(1f))
                    CardFuelScale(env, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CardGear(size: Dp) {
    val gear by rememberGear()
    PanoGearBox(gearMark(gear.gear), size)
}

@Composable
private fun CardRevScale(modifier: Modifier) {
    val gear by rememberGear()
    val lit = revSegments(gear.rpm, 12)
    val warmFrom = remember { warmSegment(12) }
    Column(modifier) {
        PanoLabel(stringResource(R.string.skin_panorama_revs))
        Spacer(Modifier.height(5.dp))
        PanoSegments(12, lit, Modifier.fillMaxWidth().height(14.dp), warmFrom = warmFrom)
    }
}

@Composable
private fun CardFuelScale(env: SkinTileEnv, modifier: Modifier) {
    val fuel = rememberFuel()
    val lit = fuel?.let { (it.percent / 100f * 8).roundToInt().coerceIn(if (it.percent > 0) 1 else 0, 8) } ?: 0
    Column(modifier, horizontalAlignment = Alignment.End) {
        PanoLabel(stringResource(R.string.skin_panorama_fuel), align = TextAlign.End)
        Spacer(Modifier.height(5.dp))
        PanoSegments(8, lit, Modifier.fillMaxWidth().height(14.dp), fromEnd = true, warm = fuel != null && fuel.percent <= SKIN_LOW_FUEL_PCT)
    }
}

// --- Telemetry ----------------------------------------------------------------------

/**
 * Telemetry on a glass gauge card: the band's rev side made large (REVS,
 * the segments, the r/min big), then the engine's readings as small lit
 * readouts (coolant, battery, load) where they fit. Without the link, the
 * card says so and offers to connect.
 */
@Composable
private fun PanoTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .panoGlass(editing = env.editing)
            .panoTap(state.isIdle && !env.editing, stringResource(R.string.horizon_connect_obd), env.onConnectObd)
    ) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val wide = maxWidth >= maxHeight * 2.2f && innerW >= 360.dp
        val readSize = (min(innerW.value / 18f, innerH.value * 0.13f)).coerceIn(15f, 22f)
        Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                TelemetryRevs(innerH, readSize)
                if (!wide) {
                    Spacer(Modifier.height(12.dp))
                    TelemetryReadouts(env, readSize)
                }
            }
            if (wide) {
                Spacer(Modifier.width(20.dp))
                Box(Modifier.weight(0.8f)) { TelemetryReadouts(env, readSize) }
            }
        }
    }
}

@Composable
private fun TelemetryRevs(innerH: Dp, readSize: Float) {
    val gear by rememberGear()
    val rpm = gear.rpm
    val lit = revSegments(rpm, REV_SEGMENTS)
    val warmFrom = remember { warmSegment(REV_SEGMENTS) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanoLabel(stringResource(R.string.skin_panorama_revs), Modifier.weight(1f))
            PanoLine(stringResource(R.string.skin_panorama_gear, gearMark(gear.gear)), panoText((readSize * 0.9f).coerceAtLeast(14f), DashColors.AccentInk, FontWeight.Medium))
        }
        Spacer(Modifier.height(8.dp))
        PanoSegments(REV_SEGMENTS, lit, Modifier.fillMaxWidth().height((innerH * 0.14f).coerceIn(14.dp, 34.dp)), warmFrom = warmFrom)
        Spacer(Modifier.height(8.dp))
        Row {
            PanoLine(rpm?.let { groupThousands(it) } ?: "--", panoText((readSize * 2.2f).coerceAtMost((innerH.value * 0.3f).coerceAtLeast(20f)), DashColors.TextPrimary, FontWeight.Light, thin = true), Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            PanoLine(stringResource(R.string.skin_panorama_rpm), panoText(readSize, DashColors.TextSecondary), Modifier.alignByBaseline())
        }
    }
}

/** The engine's readings, or the link's state and its buttons while it is down. */
@Composable
private fun TelemetryReadouts(env: SkinTileEnv, size: Float) {
    val state = env.obdConnection
    val units = LocalUnits.current
    // Whatever the car answers is shown, adapter or not.
    val g = rememberPanelGauges(K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    val gear by rememberGear()
    when {
        state == ObdConnectionState.CONNECTED || g.any || gear.rpm != null -> {
            FitRow(18.dp) {
                g.coolantTempC?.let {
                    PanoReadout(stringResource(R.string.skin_panorama_coolant), "${units.temp(it)}°", it >= COOLANT_WARNING_C, size)
                }
                g.voltage?.let {
                    PanoReadout(stringResource(R.string.skin_panorama_battery), String.format(Locale.getDefault(), "%.1f V", it), it !in BATTERY_OK_V, size)
                }
                g.engineLoadPct?.let { PanoReadout(stringResource(R.string.skin_panorama_load), "$it %", false, size) }
            }
        }
        state == ObdConnectionState.CONNECTING -> PanoLine(stringResource(R.string.horizon_obd_connecting), panoText(size, DashColors.AccentInk, FontWeight.Medium))
        else -> FitRow(10.dp) {
            PanoPillButton(stringResource(R.string.horizon_connect_obd), filled = true, enabled = !env.editing, onClick = env.onConnectObd)
            if (state == ObdConnectionState.ERROR) {
                PanoPillButton(stringResource(R.string.tape_cd_choose_adapter), filled = false, enabled = !env.editing, onClick = env.onPickDevice)
            }
        }
    }
}

/** A small readout: its name over its value, the value in amber when it is out of range. */
@Composable
private fun PanoReadout(label: String, value: String, warn: Boolean, size: Float) {
    Column {
        PanoLine(label, panoText((size * 0.8f).coerceAtLeast(14f), DashColors.TextSecondary))
        PanoLine(value, panoText(size * 1.15f, if (warn) DashColors.Warning else DashColors.TextPrimary, FontWeight.Bold))
    }
}

/** A pill button, at least 56 dp tall: [filled] in the lit ink for the one thing to do, else glass. */
@Composable
private fun PanoPillButton(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val fill = DashColors.Accent
    val ink = if (filled) panoOnLit else DashColors.TextPrimary
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .then(
                if (filled) Modifier
                    .cachedDraw(fill) { onDrawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2f)) } }
                    .clip(RoundedCornerShape(50))
                else Modifier.panoGlass(100.dp)
            )
            .clickable(enabled = enabled, role = Role.Button) { tap(); onClick() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        PanoLine(text, panoText(18f, ink, FontWeight.Medium))
    }
}

// --- Fuel and range: the car on its plinth ------------------------------------------

/**
 * Fuel and range. With the room, the stage of the design: the car on its lit
 * plinth with its callouts (doors, the fuel and range, then the tyres, the
 * battery and the next service, as many as are known). Otherwise a glass
 * card: FUEL and the level, the range large and thin, the fuel's segments.
 * Fuel not learned yet, on a small tile, falls back to the standard tile
 * (on the glass), which explains how to learn it.
 */
@Composable
private fun PanoRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val stage = maxWidth >= PANO_HERO_MIN_W && maxHeight >= PANO_HERO_MIN_H
        when {
            stage -> PanoHero(rememberCallouts(env, fuel), Modifier.fillMaxSize())
            fuel == null -> Box(Modifier.fillMaxSize().panoGlass(editing = env.editing)) { StandardSkinnedTile(item, env) }
            else -> RangeCard(fuel, env.editing)
        }
    }
}

/** What the stage points out, in the corners' order: doors, fuel, then tyres, battery, service where known. */
@Composable
private fun rememberCallouts(env: SkinTileEnv, fuel: FuelInfo?): List<PanoCallout> {
    val units = LocalUnits.current
    val doors = rememberDoors()
    val tyres = rememberTyres()
    val problems by Tyres.problems.collectAsState()
    val pressureUnit by Tyres.unit.collectAsState()
    val upkeep by Maintenance.state.collectAsState()
    val voltage = rememberGauge(K.BATTERY_VOLTS) ?: 0.0
    val doorsLabel = stringResource(R.string.skin_panorama_doors)
    val closed = stringResource(R.string.skin_panorama_doors_closed)
    val openWord = stringResource(R.string.skin_panorama_doors_open)
    val fuelLabel = stringResource(R.string.skin_panorama_fuel)
    val tyresLabel = stringResource(R.string.skin_panorama_tyres)
    val batteryLabel = stringResource(R.string.skin_panorama_battery)
    val serviceLabel = stringResource(R.string.skin_panorama_service)
    val dueWord = stringResource(R.string.skin_panorama_service_due)
    val context = env.context
    val now = rememberWallClock(3_600_000L).longValue
    val service = remember(upkeep, now) {
        upkeep.statuses(now)
            .filter { it.stage != UpkeepStage.UNKNOWN }
            .minByOrNull { d -> d.kmLeft?.toDouble() ?: ((d.daysLeft ?: Int.MAX_VALUE) * 40.0) }
    }
    val list = ArrayList<PanoCallout>(4)
    doors?.let { list += PanoCallout(doorsLabel, if (it.anyOpen) openWord else closed, it.anyOpen) }
    fuel?.let { list += PanoCallout(fuelLabel, units.distanceText(it.rangeKm), it.percent <= SKIN_LOW_FUEL_PCT) }
    val tyre = problems.keys.firstNotNullOfOrNull { tyres[it] } ?: tyres.filterKeys { it != TyrePos.SPARE }.values.minByOrNull { it.kPa }
    if (tyre != null) list += PanoCallout(tyresLabel, formatPressure(tyre.kPa, pressureUnit), problems.isNotEmpty())
    if (voltage > 0.0) list += PanoCallout(batteryLabel, String.format(Locale.getDefault(), "%.1f V", voltage), voltage !in BATTERY_OK_V)
    if (service != null) {
        val value = when {
            service.stage == UpkeepStage.DUE -> dueWord
            service.kmLeft != null -> units.distanceText(service.kmLeft).let { groupedDistance(it) }
            service.daysLeft != null -> context.resources.getQuantityString(R.plurals.skin_panorama_service_days, service.daysLeft, service.daysLeft)
            else -> dueWord
        }
        list += PanoCallout(serviceLabel, value, service.stage == UpkeepStage.SOON || service.stage == UpkeepStage.DUE)
    }
    return list
}

/** "3200 km" with its thousands split ("3 200 km"). */
private fun groupedDistance(text: String): String {
    val n = text.takeWhile { it.isDigit() }
    val v = n.toIntOrNull() ?: return text
    return groupThousands(v) + text.removePrefix(n)
}

/** The range on a glass card: FUEL and the level, the range large and thin, the segments, the litres. */
@Composable
private fun RangeCard(fuel: FuelInfo, editing: Boolean) {
    val units = LocalUnits.current
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    BoxWithConstraints(Modifier.fillMaxSize().panoGlass(editing = editing)) {
        val pad = padFor(maxWidth, maxHeight)
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val segH = (innerH * 0.1f).coerceIn(12.dp, 26.dp)
        val lineSize = (innerH.value * 0.09f).coerceIn(14f, 19f)
        val showLine = innerH >= 150.dp
        val numH = innerH - 20.dp - segH - 24.dp - if (showLine) (lineSize * 1.3f).dp else 0.dp
        val numSize = panoFit("888", thin = true, weight = FontWeight.Light, maxW = innerW * 0.62f, maxH = numH, minSize = 26f, maxSize = 150f)
        val lit = (fuel.percent / 100f * FUEL_SEGMENTS).roundToInt().coerceIn(if (fuel.percent > 0) 1 else 0, FUEL_SEGMENTS)
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PanoLabel(stringResource(R.string.skin_panorama_fuel), Modifier.weight(1f))
                PanoLine("${fuel.percent} %", panoText(16f, if (low) DashColors.Warning else DashColors.TextSecondary, FontWeight.Medium))
            }
            Row {
                PanoLine(
                    units.distance(fuel.rangeKm).toString(),
                    panoText(numSize, if (low) DashColors.Warning else DashColors.TextPrimary, FontWeight.Light, thin = true),
                    Modifier.alignByBaseline(), overflow = TextOverflow.Clip
                )
                Spacer(Modifier.width((numSize * 0.08f).dp))
                PanoLine(units.distanceUnit, panoText((numSize * 0.28f).coerceAtLeast(15f), DashColors.TextSecondary), Modifier.alignByBaseline())
            }
            PanoSegments(FUEL_SEGMENTS, lit, Modifier.fillMaxWidth().height(segH), warm = low)
            if (showLine) {
                PanoLine(
                    String.format(Locale.getDefault(), "%s%.0f L", if (fuel.percentEstimated) "≈ " else "", fuel.liters),
                    panoText(lineSize, DashColors.TextSecondary)
                )
            }
        }
    }
}

// --- Directions ---------------------------------------------------------------------

/** The turn's arrow, drawn as in the design for the side the navigation says, or the app's own glyph tinted. */
@Composable
private fun PanoTurnArrow(nav: NavState, size: Dp) {
    val lit = DashColors.Accent
    val glowK = panoGlow
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    val side = nav.turnSide()
    if (bitmap != null) {
        Box(
            Modifier.size(size).glowHalo(lit.copy(alpha = 0.3f * glowK), 0.62f),
            contentAlignment = Alignment.Center
        ) {
            Image(bitmap, contentDescription = nav.instruction, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(lit), modifier = Modifier.fillMaxSize(0.9f))
        }
        return
    }
    Spacer(
        Modifier
            .size(size)
            .semantics { contentDescription = nav.instruction }
            .cachedDraw(side, lit, glowK) {
                val s = this.size.minDimension / 24f
                // The design's arrow in a 24 grid: up the stem, round the corner, out to the side, its head.
                val path = Path().apply {
                    when (side) {
                        TurnSide.AHEAD -> {
                            moveTo(12f * s, 21f * s); lineTo(12f * s, 4f * s)
                            moveTo(7f * s, 9f * s); lineTo(12f * s, 4f * s); lineTo(17f * s, 9f * s)
                        }
                        else -> {
                            val m = side == TurnSide.LEFT
                            fun x(v: Float) = (if (m) 24f - v else v) * s
                            moveTo(x(7f), 20f * s); lineTo(x(7f), 11f * s)
                            cubicTo(x(7f), 8.8f * s, x(8.8f), 7f * s, x(11f), 7f * s)
                            lineTo(x(18f), 7f * s)
                            moveTo(x(14f), 3f * s); lineTo(x(18f), 7f * s); lineTo(x(14f), 11f * s)
                        }
                    }
                }
                val stroke = Stroke(2.1f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val halo1 = Stroke(6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val halo2 = Stroke(10f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    if (glowK > 0f) {
                        drawPath(path, lit.copy(alpha = 0.08f * glowK), style = halo2)
                        drawPath(path, lit.copy(alpha = 0.16f * glowK), style = halo1)
                    }
                    drawPath(path, lit, style = stroke)
                }
            }
    )
}

private val CLOCK_PART = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")

/**
 * The next turn on a glass card, as in the design: NEXT TURN, the arrow big
 * and glowing, the distance large and thin, the street and the arrival.
 * A wide low tile puts the arrow beside the words. Tap opens the navigation
 * app. Without a route (or without notification access) a quiet card says
 * so and how to start.
 */
@Composable
private fun PanoDirections(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    when {
        !env.hasMediaAccess -> PanoEmpty(
            Icons.Filled.NotificationsActive,
            stringResource(R.string.info_directions_access_title), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> PanoEmpty(
            Icons.Filled.Navigation,
            stringResource(R.string.info_directions_no_route), stringResource(R.string.horizon_start_navigation), env.editing,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> {
            val arrivalWord = stringResource(R.string.skin_panorama_arrival, "%s")
            val eta = remember(nav.eta, arrivalWord) {
                nav.etaParts.map { if (CLOCK_PART.matches(it)) arrivalWord.replace("%s", it) else it }
                    .sortedBy { if (it.startsWith(arrivalWord.substringBefore("%s"))) 0 else 1 }
                    .joinToString(DOT)
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .panoGlass(editing = env.editing)
                    .panoTap(canTap, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
            ) {
                val pad = padFor(maxWidth, maxHeight) + 4.dp
                val innerW = maxWidth - pad * 2
                val innerH = maxHeight - pad * 2
                val wide = maxWidth > maxHeight * 1.35f
                val (value, unit) = nav.distanceParts
                val streetSize = (min(innerW.value / 14f, innerH.value * 0.075f)).coerceIn(16f, 24f)
                val etaSize = (streetSize * 0.8f).coerceIn(14f, 19f)
                val label = innerH >= 150.dp
                if (wide) {
                    val arrow = (innerH * 0.72f).coerceIn(44.dp, 140.dp)
                    val textW = innerW - arrow - 20.dp
                    val distSize = panoFit(template(if (unit.isEmpty()) value.ifEmpty { "888" } else "$value $unit"), true, FontWeight.Light, textW * 0.8f, innerH * 0.42f, 24f, 90f)
                    Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                        PanoTurnArrow(nav, arrow)
                        Spacer(Modifier.width(20.dp))
                        TurnWords(nav, value, unit, distSize, streetSize, etaSize, eta, maxLines = 1, label = false)
                    }
                } else {
                    val arrow = min(innerW.value * 0.42f, innerH.value * 0.36f).coerceIn(40f, 130f).dp
                    val wordsH = innerH - arrow - (if (label) 28.dp else 0.dp) - (streetSize * 1.3f + etaSize * 1.4f + 14f).dp
                    val distSize = panoFit(template(if (unit.isEmpty()) value.ifEmpty { "888" } else "$value $unit"), true, FontWeight.Light, innerW * 0.85f, wordsH, 24f, 72f)
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        if (label) {
                            PanoLabel(stringResource(R.string.skin_panorama_next_turn))
                            Spacer(Modifier.height(8.dp))
                        }
                        PanoTurnArrow(nav, arrow)
                        Spacer(Modifier.weight(1f))
                        TurnWords(nav, value, unit, distSize, streetSize, etaSize, eta, maxLines = if (innerH > 300.dp) 2 else 1, label = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun TurnWords(nav: NavState, value: String, unit: String, distSize: Float, streetSize: Float, etaSize: Float, eta: String, maxLines: Int, label: Boolean) {
    val glow = softTextShadow(DashColors.Accent.copy(alpha = 0.5f), 20f)
    Column {
        if (label) PanoLabel(stringResource(R.string.skin_panorama_next_turn))
        if (value.isNotEmpty()) {
            Row {
                PanoLine(value, panoText(distSize, DashColors.TextPrimary, FontWeight.Light, thin = true).copy(shadow = if (DashColors.Light) null else glow), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width((distSize * 0.1f).dp))
                    PanoLine(unit, panoText((distSize * 0.36f).coerceAtLeast(15f), DashColors.TextSecondary), Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.height(6.dp))
            PanoLine(nav.instruction, panoText(streetSize, DashColors.TextPrimary, FontWeight.Medium), maxLines = maxLines)
        } else {
            PanoLine(nav.instruction, panoText((streetSize * 1.4f).coerceAtMost(34f), DashColors.TextPrimary, FontWeight.Medium), maxLines = maxLines + 1)
        }
        if (eta.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            PanoLine(eta, panoText(etaSize, DashColors.TextSecondary))
        }
    }
}

// --- Media --------------------------------------------------------------------------

/**
 * Music on a glass card, as in the design: the cover across the top (or the
 * skin's own glow of colour while the player gives none), the song and who
 * sings it, a lit progress line and the three keys, play / pause filled in
 * the lit ink. A low tile becomes one row; a wide one puts the cover beside.
 */
@Composable
private fun PanoMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val context = env.context
    if (!env.hasMediaAccess) {
        PanoEmpty(
            Icons.Filled.MusicNote,
            stringResource(R.string.info_media_access_needed), stringResource(R.string.horizon_tap_allow_notification_access), env.editing,
            if (!env.editing) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        return
    }
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val title = if (hasTrack) ms.title else stringResource(R.string.info_nothing_playing)
    val sub = if (hasTrack) ms.artist.ifBlank { stringResource(R.string.horizon_unknown_artist) } else stringResource(R.string.horizon_play_something)
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().panoGlass(editing = env.editing)) {
        val pad = padFor(maxWidth, maxHeight) + 4.dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val compact = maxHeight < 170.dp
        val wide = !compact && maxWidth > maxHeight * 1.7f
        val keyW = (innerW.value / 3.6f).coerceIn(56f, 84f).dp
        val keyH = 56.dp
        when {
            compact -> Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                if (innerW >= 420.dp) {
                    PanoCover(ms, Modifier.size(innerH.coerceIn(40.dp, 100.dp)))
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    PanoLine(title, panoText((innerH.value * 0.22f).coerceIn(17f, 24f), DashColors.TextPrimary, FontWeight.Medium))
                    PanoLine(sub, panoText(15f, DashColors.TextSecondary))
                    if (ms.durationMs > 0L) {
                        Spacer(Modifier.height(6.dp))
                        PanoProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.width(12.dp))
                PanoKeys(env, (keyW * 0.85f).coerceAtLeast(56.dp), keyH, prev = innerW >= 480.dp)
            }
            wide -> Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                val cover = min(innerH.value, innerW.value * 0.34f).dp
                PanoCover(ms, Modifier.size(width = cover * 1.2f, height = cover))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    PanoLine(title, panoText((innerH.value * 0.13f).coerceIn(18f, 30f), DashColors.TextPrimary, FontWeight.Medium), maxLines = 2)
                    PanoLine(sub, panoText((innerH.value * 0.09f).coerceIn(15f, 20f), DashColors.TextSecondary))
                    Spacer(Modifier.height(12.dp))
                    if (ms.durationMs > 0L) PanoProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    PanoKeys(env, keyW.coerceAtMost(84.dp), keyH, prev = true)
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(pad)) {
                val textBlock = 26f * 1.25f + 17f * 1.25f + 16f + 8f + keyH.value + 8f
                val coverH = (innerH.value - textBlock).coerceAtLeast(0f)
                if (coverH >= 56f) {
                    PanoCover(ms, Modifier.fillMaxWidth().height(coverH.coerceAtMost(innerW.value * 0.62f).dp))
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.weight(1f))
                PanoLine(title, panoText((innerW.value / 13f).coerceIn(18f, 26f), DashColors.TextPrimary, FontWeight.Medium))
                PanoLine(sub, panoText((innerW.value / 18f).coerceIn(15f, 18f), DashColors.TextSecondary))
                Spacer(Modifier.height(10.dp))
                PanoProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PanoKeys(env, keyW, keyH, prev = true, spread = true)
                }
            }
        }
    }
}

/**
 * The song's cover with rounded corners or, while the player publishes none,
 * the design's glow of pink and blue through fine vertical lines, drawn once.
 */
@Composable
private fun PanoCover(ms: MediaState, modifier: Modifier) {
    val bitmap = remember(ms.artwork) { ms.artwork?.asImageBitmap() }
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .cachedDraw(bitmap == null) {
                val w = size.width
                val h = size.height
                val pink = Brush.radialGradient(listOf(Color(0xFFFF6AA8), Color.Transparent), Offset(w * 0.3f, h * 0.4f), min(w, h) * 0.75f)
                val blue = Brush.radialGradient(listOf(Color(0xFF5AB0FF), Color.Transparent), Offset(w * 0.75f, h * 0.6f), min(w, h) * 0.85f)
                val line = 2.dp.toPx()
                val step = 8.dp.toPx()
                onDrawBehind {
                    drawRect(Color(0xFF20194A))
                    drawRect(pink)
                    drawRect(blue)
                    var x = 0f
                    while (x < w) {
                        drawRect(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Size(line, h))
                        x += step
                    }
                }
            }
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Previous (when [prev]), play / pause filled in the lit ink, and next: [w] x [h] each, spread over the row when [spread]. */
@Composable
private fun PanoKeys(env: SkinTileEnv, w: Dp, h: Dp, prev: Boolean, spread: Boolean = false) {
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val c = env.mediaController
    Row(
        modifier = if (spread) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prev) PanoKey(Icons.Filled.SkipPrevious, stringResource(R.string.horizon_cd_previous_track), w, h, false, enabled) { c.previous() }
        PanoKey(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (playing) R.string.horizon_cd_pause else R.string.horizon_cd_play),
            w, h, true, enabled
        ) { c.playPause() }
        PanoKey(Icons.Filled.SkipNext, stringResource(R.string.horizon_cd_next_track), w, h, false, enabled) { c.next() }
    }
}

/** A transport key, at least [DashSize.TouchDriving] wide: [main] filled in the lit ink with its glow, else bare. */
@Composable
private fun PanoKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, w: Dp, h: Dp, main: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val fill = DashColors.Accent
    val glowK = panoGlow
    Box(
        modifier = Modifier
            .size(width = w.coerceAtLeast(DashSize.TouchDriving), height = h)
            .then(
                if (main) Modifier.cachedDraw(fill, glowK) {
                    val r = CornerRadius(18.dp.toPx())
                    onDrawBehind {
                        if (glowK > 0f) {
                            for (k in 1..3) {
                                val g = k * 4f * density
                                drawRoundRect(fill.copy(alpha = 0.09f * glowK), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(r.x + g))
                            }
                        }
                        drawRoundRect(fill, cornerRadius = r)
                    }
                } else Modifier
            )
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (main) panoOnLit else DashColors.TextPrimary, modifier = Modifier.size(32.dp))
    }
}

/** The thin lit progress line; in its own scope, so only it follows the position. */
@Composable
private fun PanoProgress(ms: MediaState, controller: CarMediaController, modifier: Modifier) {
    val positionMs = rememberMediaPosition(ms, controller)
    val fraction = if (ms.durationMs > 0L) (positionMs.toFloat() / ms.durationMs).coerceIn(0f, 1f) else 0f
    val on = DashColors.Accent
    val off = panoSegOff
    val glowK = panoGlow
    Spacer(
        modifier.height(6.dp).cachedDraw(fraction, on, off, glowK) {
            val y = size.height / 2f
            val bar = 4.dp.toPx()
            val fw = size.width * fraction
            onDrawBehind {
                drawLine(off, Offset(bar / 2f, y), Offset(size.width - bar / 2f, y), bar, StrokeCap.Round)
                if (fw > 0f) {
                    if (glowK > 0f) drawLine(on.copy(alpha = 0.25f * glowK), Offset(bar / 2f, y), Offset(fw, y), bar * 2.6f, StrokeCap.Round)
                    drawLine(on, Offset(bar / 2f, y), Offset(fw.coerceAtLeast(bar / 2f), y), bar, StrokeCap.Round)
                }
            }
        }
    )
}

// --- Clock and weather: the corners' readouts --------------------------------------

/**
 * Clock: as the bar's corner, bare on the page, the time large and thin and
 * the date under it. Tap opens the alarms.
 */
@Composable
private fun PanoClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat(PANO_LONG_DATE, best = true)
    val context = env.context
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .clip(RoundedCornerShape(PANO_CARD))
            .panoTap(!env.editing, stringResource(R.string.horizon_open_clock)) { openClockApp(context) },
        contentAlignment = Alignment.CenterStart
    ) {
        val pad = (min(maxWidth.value, maxHeight.value) * 0.06f).coerceIn(8f, 20f).dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        val dateSize = min(innerW.value / 14f, innerH.value * 0.13f).coerceIn(14f, 26f)
        val date = dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }
        val amPm = units.amPm(now, locale)
        val timeText = timeFmt.format(now)
        val timeSize = panoFit(template(timeText), true, FontWeight.Light, if (amPm != null) innerW * 0.8f else innerW, innerH - (dateSize * 1.3f + 4f).dp, 28f, 300f)
        Column(Modifier.padding(pad)) {
            Row {
                PanoLine(timeText, panoText(timeSize, DashColors.TextPrimary, FontWeight.Light, thin = true), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
                if (amPm != null) {
                    Spacer(Modifier.width((timeSize * 0.06f).dp))
                    PanoLine(amPm, panoText((timeSize * 0.22f).coerceAtLeast(14f), DashColors.TextSecondary), Modifier.alignByBaseline())
                }
            }
            PanoLine(date, panoText(dateSize, DashColors.TextSecondary))
        }
    }
}

/**
 * Weather: as the bar's other corner, bare and aligned to the right, the
 * temperature large and thin with the sky in words under it, then the feel
 * and the wind, and rain to come in the lit ink. While there is nothing yet,
 * it says why (no GPS, offline).
 */
@Composable
private fun PanoWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    val units = LocalUnits.current
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer(), contentAlignment = Alignment.CenterEnd) {
        val pad = (min(maxWidth.value, maxHeight.value) * 0.06f).coerceIn(8f, 20f).dp
        val innerW = maxWidth - pad * 2
        val innerH = maxHeight - pad * 2
        if (w == null) {
            val title = when (wait) {
                WeatherWait.LOADING -> R.string.horizon_loading
                WeatherWait.NO_GPS -> R.string.info_waiting_gps
                WeatherWait.UNAVAILABLE -> R.string.info_weather_unavailable
            }
            Column(Modifier.padding(pad), horizontalAlignment = Alignment.End) {
                PanoLine("--°", panoText(panoFit("88°", true, FontWeight.Light, innerW * 0.6f, innerH * 0.5f, 28f, 120f), DashColors.Muted, FontWeight.Light, thin = true))
                PanoLine(stringResource(title), panoText(16f, DashColors.TextSecondary), maxLines = 2, align = TextAlign.End)
            }
            return@BoxWithConstraints
        }
        val rain = when {
            w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= nowMs -> stringResource(R.string.canvas_rain_until, timeFmt.format(Date(w.rainUntilMs)))
            w.rainFromMs != null -> stringResource(R.string.canvas_rain_from, timeFmt.format(Date(w.rainFromMs)))
            else -> null
        }
        val condSize = min(innerW.value / 12f, innerH.value * 0.13f).coerceIn(15f, 26f)
        val lineSize = (condSize * 0.8f).coerceIn(14f, 20f)
        val extra = innerH >= 150.dp
        val temp = "${units.temp(w.tempC)}°"
        val below = (condSize * 1.3f + if (extra) lineSize * 1.3f * (if (rain != null) 2 else 1) else 0f + 4f).dp
        val tempSize = panoFit(template(temp), true, FontWeight.Light, innerW * 0.7f, innerH - below, 28f, 220f)
        val feels = stringResource(R.string.horizon_feels, units.temp(w.feelsC))
        val wind = stringResource(if (units.imperial) R.string.units_horizon_wind_mph else R.string.horizon_wind, units.speed(w.windKmh))
        val line = firstFitting(listOf("$feels$DOT$wind", feels), lineSize, FontWeight.Normal, innerW)
        Column(Modifier.padding(pad), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(w.code), contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size((tempSize * 0.42f).coerceIn(22f, 64f).dp))
                Spacer(Modifier.width(10.dp))
                PanoLine(temp, panoText(tempSize, DashColors.TextPrimary, FontWeight.Light, thin = true), overflow = TextOverflow.Clip)
            }
            PanoLine(w.condition, panoText(condSize, DashColors.TextSecondary), align = TextAlign.End)
            if (extra) {
                PanoLine(line, panoText(lineSize, DashColors.TextSecondary), align = TextAlign.End)
                if (rain != null) PanoLine(rain, panoText(lineSize, DashColors.AccentInk, FontWeight.Medium), align = TextAlign.End)
            }
        }
    }
}

// --- Apps ---------------------------------------------------------------------------

/** App shortcut: a glass key like the wheel's, the app's icon and its name, side by side on a wide tile, stacked on a square one. */
@Composable
private fun PanoApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val label = appLabel(app, item.packageName)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .panoGlass(editing = env.editing)
            .panoTap(!env.editing, stringResource(R.string.horizon_open_app, label)) { env.onLaunchApp(item.packageName) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val row = maxWidth >= maxHeight * 1.5f && maxWidth >= 180.dp
        if (row) {
            val icon = (maxHeight.value * 0.5f).coerceIn(30f, 72f).dp
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                PanoAppGlyph(app, icon)
                Spacer(Modifier.width(14.dp))
                PanoLine(label, panoText((icon.value * 0.4f).coerceIn(16f, 26f), DashColors.TextPrimary, FontWeight.Medium), Modifier.weight(1f, fill = false))
            }
        } else {
            val icon = (min(maxWidth.value, maxHeight.value) * 0.42f).coerceIn(30f, 96f).dp
            val named = maxHeight >= 96.dp
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PanoAppGlyph(app, icon)
                if (named) {
                    Spacer(Modifier.height(8.dp))
                    PanoLine(label, panoText((icon.value * 0.3f).coerceIn(14f, 22f), DashColors.TextPrimary, FontWeight.Medium), align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun PanoAppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(size * 0.8f))
}

/**
 * Launch bar: a row of glass keys, one per app (icon and name where there is
 * room, the name under the icon on a tall bar), and a glass pencil at the
 * end. Apps that would get less than a 64 dp key are left out.
 */
@Composable
private fun PanoLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer()) {
        val gap = 12.dp
        val edit = min(maxHeight.value, 64f).coerceAtLeast(56f).dp
        val room = maxWidth - edit - gap
        val tall = maxHeight >= 150.dp
        val fit = ((room + gap).value / (64f + gap.value)).toInt().coerceAtLeast(1)
        val pkgs = item.packages.take(fit)
        val per = if (pkgs.isEmpty()) 0f else (room.value - gap.value * (pkgs.size - 1)) / pkgs.size
        val labels = if (tall) per >= 90f else per >= 150f
        val icon = if (tall) (maxHeight.value * 0.34f).coerceIn(28f, 64f).dp else (maxHeight.value * 0.46f).coerceIn(24f, 48f).dp
        val labelSize = if (tall) (icon.value * 0.32f).coerceIn(14f, 20f) else (maxHeight.value * 0.2f).coerceIn(14f, 20f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (pkgs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight().panoGlass(editing = env.editing).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    PanoLine(stringResource(R.string.skin_panorama_launch_bar_empty), panoText(labelSize, DashColors.TextSecondary), maxLines = 2)
                }
            } else {
                pkgs.forEach { pkg ->
                    val app = env.appsByPackage[pkg]
                    val name = appLabel(app, pkg)
                    val base = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .widthIn(min = 64.dp)
                        .panoGlass(22.dp, editing = env.editing)
                        .panoTap(!env.editing, stringResource(R.string.horizon_open_app, name)) { env.onLaunchApp(pkg) }
                        .semantics(mergeDescendants = true) { contentDescription = name }
                        .padding(horizontal = 10.dp)
                    if (tall) {
                        Column(base, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            PanoAppGlyph(app, icon)
                            if (labels) {
                                Spacer(Modifier.height(8.dp))
                                PanoLine(name, panoText(labelSize, DashColors.TextPrimary, FontWeight.Medium), align = TextAlign.Center)
                            }
                        }
                    } else {
                        Row(base, horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            PanoAppGlyph(app, icon)
                            if (labels) {
                                Spacer(Modifier.width(12.dp))
                                PanoLine(name, panoText(labelSize, DashColors.TextPrimary, FontWeight.Medium), Modifier.weight(1f, fill = false))
                            }
                        }
                    }
                }
            }
            val tap = rememberTapFeedback()
            val editLabel = stringResource(R.string.horizon_cd_edit_launch_bar)
            Box(
                Modifier
                    .size(edit)
                    .panoGlass(20.dp)
                    .clickable(onClickLabel = editLabel, role = Role.Button) { tap(); env.onEditLaunchBar() }
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}
