package com.openauto.dash

import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Panorama skin: a look after the cabin of the driver's own car, a big MPV
 * with a panoramic windscreen. Whole-design skin (see Skins.kt): its page
 * background, its bar, the frame over a docked app window and the main
 * widgets (PanoramaSkinTiles.kt), the car on its plinth (PanoramaSkinCar.kt).
 *
 * The car's wide central display becomes a curved glass band of lit
 * segments (the speed tile); the cards are panes of blue glass over a deep
 * navy windscreen sky, and the bar is the fixed-hub steering wheel: a ring
 * rising from the bottom edge, its buttons on the rim and Talk in the hub,
 * the time and the outside temperature in the corners beside it. By day the
 * same cabin in pearl white with deep blue ink. Android 10 cannot blur, so
 * glass is a translucent fill, a hairline rim, a catch of light on its top
 * edge and a shadow of stacked faint shapes; glows are soft gradients.
 */

internal val PanoramaPalette = DashPalette(
    Background = Color(0xFF061230), Bar = Color.Transparent, Card = Color(0xFF0E1E42), CardHi = Color(0x29A0D2FF),
    Accent = Color(0xFF8FD8FF), Secondary = Color(0xFF5AB0FF), Critical = Color(0xFFFF5A5F), Good = Color(0xFF52E3A4),
    Muted = Color(0xFF8DA4C6), TextPrimary = Color(0xFFEAF6FF), TextSecondary = Color(0xFF9FB7D6),
    Accent2 = Color(0xFF5AB0FF), Line = Color(0x3396CDFF), Glow = 0.6f, Bare = true,
    BackgroundStops = listOf(Color(0xFF0D2457), Color(0xFF071433), Color(0xFF030714)),
    Tacho = Color(0xFFFFB547),
    Font = DashFont.CONDENSED,
    HeroWeight = FontWeight.Light,
    Skin = DashSkin.PANORAMA
)

internal val PanoramaLightPalette = DashPalette(
    Background = Color(0xFFEEF3F8), Bar = Color.Transparent, Card = Color.White, CardHi = Color(0x140F4F8A),
    Accent = Color(0xFF0F4F8A), Secondary = Color(0xFF2F78C4), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF56698A), TextPrimary = Color(0xFF0D1B2E), TextSecondary = Color(0xFF3C5372),
    Accent2 = Color(0xFF2F78C4), Line = Color(0x2228466E), Glow = 0.15f, Light = true, Bare = true,
    BackgroundStops = listOf(Color(0xFFFFFFFF), Color(0xFFEEF3F8), Color(0xFFDFE6EF)),
    Tacho = Color(0xFFB0600A),
    Font = DashFont.CONDENSED,
    HeroWeight = FontWeight.Light,
    Skin = DashSkin.PANORAMA
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val PanoramaShapes = skinShapes(28, 26, 16)

// --- Shared look ------------------------------------------------------------------

/** Corners of the glass cards. */
internal val PANO_CARD = 26.dp

/** The lit ink of the segments, the arrows and the Talk button: ice blue at night, deep blue by day. */
internal val panoLit: Color get() = DashColors.Accent

/** The glow around lit things; none by day, where a glow only smears in the sun. */
internal val panoGlow: Float get() = if (DashColors.Light) 0f else DashColors.Effects.scale

/** An unlit segment: the lit ink, faint. */
internal val panoSegOff: Color get() = DashColors.Accent.copy(alpha = 0.10f)

/** Ink on the lit Talk button and the play key: the night's deepest navy, white by day. */
internal val panoOnLit: Color get() = if (DashColors.Light) Color.White else Color(0xFF030714)

/** The glass rim: ice blue at night, a blue-grey hairline by day. */
internal val panoRim: Color get() = if (DashColors.Light) Color(0x2E28466E) else Color(0x4796CDFF)

/** The DIN-like face: Roboto Condensed at the weights Android ships it in (one family per weight, since a wrapped typeface keeps one). */
private fun condensed(weight: Int): FontFamily =
    FontFamily(robotoWeight(weight, condensed = true))

// Lazy: made on first use, never while the palettes above load (the JVM tests have no typefaces).
private val DinLight by lazy { condensed(300) }
private val DinRegular by lazy { condensed(400) }
private val DinMedium by lazy { condensed(500) }
private val DinBold by lazy { condensed(700) }

private fun din(weight: FontWeight): FontFamily = when {
    weight.weight < 350 -> DinLight
    weight.weight < 450 -> DinRegular
    weight.weight < 600 -> DinMedium
    else -> DinBold
}

/** Big figures centred in their line box, so large numbers sit where they are put. */
private val FigureLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * The skin's type at [size] dp (whatever the system font scale, so text sized
 * from a tile never outgrows it): the condensed DIN-like face for words and
 * readouts, or with [thin] the wide light sans of the band's big figures.
 * [caps] spaces the letters out as the display's engraved labels.
 */
@Composable
internal fun panoText(
    size: Float,
    color: Color = DashColors.TextPrimary,
    weight: FontWeight = FontWeight.Normal,
    thin: Boolean = false,
    caps: Boolean = false
): TextStyle = TextStyle(
    color = color,
    fontFamily = if (thin) FontFamily.SansSerif else din(weight),
    fontWeight = if (thin) weight else null,
    fontSize = fixedSp(size),
    fontFeatureSettings = "tnum",
    lineHeight = if (thin) 1.05.em else 1.22.em,
    lineHeightStyle = if (thin) FigureLineHeight else null,
    letterSpacing = when {
        caps -> 0.2.em
        thin -> (-0.02).em
        else -> 0.em
    }
)

/** One run of the skin's text: a single line with an ellipsis unless [maxLines] says otherwise; [caps] upper-cases it. */
@Composable
internal fun PanoLine(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
    caps: Boolean = false,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    Text(
        text = if (caps) text.uppercase(Locale.getDefault()) else text,
        modifier = modifier,
        style = if (align != null) style.copy(textAlign = align) else style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = overflow
    )
}

/**
 * A pane of the skin's glass behind the content, clipped to its corners. At
 * night a blue glass brighter at its top left, an ice-blue hairline rim, a
 * catch of light along its top edge and a deep shadow under it; by day a
 * pearl pane on a soft blue-grey shadow. Effects off: the solid card and its
 * rim. While the tile is being arranged its rim is dashed, so it reads as movable.
 */
@Composable
internal fun Modifier.panoGlass(radius: Dp = PANO_CARD, editing: Boolean = false): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val card = DashColors.Card
    val rimInk = panoRim
    val shape = remember(radius) { RoundedCornerShape(radius) }
    return this
        .cachedDraw(light, effects, card, rimInk, editing, radius) {
            val w = size.width
            val h = size.height
            val r = radius.toPx().coerceAtMost(min(w, h) / 2f)
            val corner = CornerRadius(r)
            val plain = effects == DashEffects.NONE
            val fill: Brush = when {
                plain -> SolidColor(card)
                light -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.94f), Color.White.copy(alpha = 0.66f)), Offset.Zero, Offset(w * 0.36f, h))
                else -> Brush.linearGradient(listOf(Color(0x2EA0D2FF), Color(0x1478B4FF)), Offset.Zero, Offset(w * 0.36f, h))
            }
            val rim = rimInk.let { if (plain) it.copy(alpha = (it.alpha * 1.6f).coerceAtMost(1f)) else it }
                .let { if (editing) it.copy(alpha = (it.alpha * 2f).coerceAtMost(0.85f)) else it }
            val rimW = (if (editing) 1.5f else 1f) * density
            val rimStroke = Stroke(rimW, pathEffect = if (editing) PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)) else null)
            val half = rimW / 2f
            val catchLight = if (!plain) {
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = if (light) 0.95f else 0.3f), Color.Transparent),
                    r * 0.5f, w - r * 0.5f
                )
            } else null
            // The deep cast shadow: four faint rounded rects, each a little wider and lower than the last.
            val shadowInk = if (light) Color(0xFF14284A) else Color.Black
            val shadow = if (!plain) {
                if (light) floatArrayOf(2f, 3f, 0.035f, 5f, 7f, 0.03f, 9f, 11f, 0.022f, 14f, 15f, 0.014f)
                else floatArrayOf(2f, 4f, 0.12f, 6f, 9f, 0.08f, 11f, 14f, 0.05f, 17f, 19f, 0.03f)
            } else null
            onDrawBehind {
                if (shadow != null) {
                    var i = 0
                    while (i < shadow.size) {
                        val k = shadow[i] * density
                        val dy = shadow[i + 1] * density
                        drawRoundRect(shadowInk.copy(alpha = shadow[i + 2]), Offset(-k, -k + dy), Size(w + 2f * k, h + 2f * k), CornerRadius(r + k))
                        i += 3
                    }
                }
                drawRoundRect(fill, cornerRadius = corner)
                drawRoundRect(rim, Offset(half, half), Size(w - rimW, h - rimW), CornerRadius(r - half), style = rimStroke)
                if (catchLight != null) drawLine(catchLight, Offset(r * 0.5f, half + density), Offset(w - r * 0.5f, half + density), density)
            }
        }
        .clip(shape)
}

/** Whole pane tappable with the tap feedback, or nothing when [enabled] is false (arranging, nothing to do). */
@Composable
internal fun Modifier.panoTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

// --- The steering wheel's geometry ----------------------------------------------------

/**
 * The fixed-hub wheel at the foot of the screen, in dp from the bottom
 * centre: the ring's radius (its band's middle) and band, how far its centre
 * sits under the bottom edge, the hub's radius and centre height, the rim
 * buttons' size and where their centres stand. A narrow screen (upright, or
 * half of a split one) gets a smaller wheel so the corners keep their room.
 * The page background draws the ring and the bar its hub and buttons, both
 * from these figures, so the two meet.
 */
internal class PanoWheel(
    val ring: Float, val band: Float, val sink: Float,
    val hub: Float, val hubY: Float, val talk: Float, val talkY: Float,
    val button: Float, val innerX: Float, val innerY: Float, val outerX: Float, val outerY: Float
) {
    /** Half the width the wheel takes on the bar, buttons included. */
    val span: Float get() = outerX + button / 2f + 6f
}

private val WideWheel = PanoWheel(
    ring = 236f, band = 20f, sink = 26f,
    hub = 72f, hubY = 62f, talk = 76f, talkY = 48f,
    button = 64f, innerX = 118f, innerY = 70f, outerX = 190f, outerY = 40f
)
private val NarrowWheel = PanoWheel(
    ring = 196f, band = 16f, sink = 22f,
    hub = 60f, hubY = 58f, talk = 76f, talkY = 48f,
    button = 60f, innerX = 100f, innerY = 66f, outerX = 164f, outerY = 38f
)

/** The wheel for a screen [widthDp] wide. */
internal fun panoWheel(widthDp: Float): PanoWheel = if (widthDp < NARROW_BAR.value) NarrowWheel else WideWheel

/** The bar's height: the hub's Talk and its word, the rim's inner buttons. */
internal val PANO_BAR = 108.dp

/** The corners' readouts: a large figure and a line under it, over a margin from the bottom edge. */
private val CORNER_H = 92.dp

/** The room the Talk button's halo takes around it, in dp. */
private const val TALK_HALO = 28f

// --- Background -------------------------------------------------------------------

/** The cabin's three blues at night (dome, sky, floor) and their pearl counterparts by day. */
private val NightDome = Color(0xFF0D2457)
private val NightSky = Color(0xFF071433)
private val NightFloor = Color(0xFF030714)
private val DayDome = Color(0xFFFFFFFF)
private val DaySky = Color(0xFFEEF3F8)
private val DayFloor = Color(0xFFDFE6EF)

/** Where the windscreen's faint horizon runs, as a share of the page's height. */
private const val HORIZON = 0.53f

/**
 * The page: the deep windscreen sky, lighter in a wide dome at the top, a
 * blue glow rising from the foot, a faint horizon and the windscreen's
 * pillars as hairlines, and the steering wheel's ring rising from the bottom
 * edge behind everything (the bar draws its hub and buttons). By day the
 * same in pearl. Nothing moves: the band's light sweep and the plinth are
 * the skin's motion. Effects off: the plain sky and the ring alone.
 */
@Composable
internal fun panoramaBackground(): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val lit = DashColors.Accent
    return remember(light, effects, lit) { panoBackdrop(light, effects, lit) }
}

private fun panoBackdrop(light: Boolean, effects: DashEffects, lit: Color): Modifier = Modifier.drawWithCache {
    val w = size.width
    val h = size.height
    val dp = density
    val sky = if (light) DaySky else NightSky
    val floor = if (light) DayFloor else NightFloor
    val plain = effects == DashEffects.NONE
    val base = Brush.verticalGradient(listOf(sky, floor))
    // The dome of light at the top: an ellipse 1400 x 520 above the page, as in the design.
    val domeC = Offset(w / 2f, -140f * dp)
    val domeRx = 700f * dp
    val domeRy = 260f * dp
    val dome = Brush.radialGradient(0f to (if (light) DayDome else NightDome), 0.7f to Color.Transparent, center = domeC, radius = domeRx)
    val footC = Offset(w / 2f, h * 1.12f)
    val footRx = 450f * dp
    val footRy = 210f * dp
    val footInk = if (light) Color(0x1A0F4F8A) else Color(0x2E3C8CFF)
    val foot = Brush.radialGradient(0f to footInk, 0.7f to Color.Transparent, center = footC, radius = footRx)
    val skyLine = lit.copy(alpha = if (light) 0.06f else 0.05f)
    val pillar = lit.copy(alpha = if (light) 0.014f else 0.025f)
    val wheel = panoWheel(w / dp)
    val c = Offset(w / 2f, h + wheel.sink * dp)
    val r = wheel.ring * dp
    val bandW = wheel.band * dp
    val rimInk = if (light) Color(0x2E28466E) else Color(0x4796CDFF)
    val rimLit = lit.copy(alpha = if (light) 0.25f else 0.35f)
    // The wheel's rim: the design's conic from 200 degrees, rim and lit in turn (turned so 0 is at the top).
    val rim = Brush.sweepGradient(listOf(rimInk, rimLit, rimInk, rimLit, rimInk), c)
    val inside = if (light) DaySky else NightFloor
    val glowK = effects.scale * if (light) 0.5f else 1f
    val outerGlow = Brush.radialGradient(
        0f to Color.Transparent, (r - bandW) / (r + 70f * dp) to Color.Transparent,
        r / (r + 70f * dp) to Color(0xFF3C8CFF).copy(alpha = 0.2f * glowK), 1f to Color.Transparent,
        center = c, radius = r + 70f * dp
    )
    val innerGlow = Brush.radialGradient(
        0f to Color.Transparent, 0.72f to Color.Transparent, 1f to Color(0xFF3C8CFF).copy(alpha = 0.14f * glowK),
        center = c, radius = r - bandW / 2f
    )
    val ringStroke = Stroke(bandW)
    val edge = Stroke(1f * dp)
    onDrawBehind {
        drawRect(base)
        if (!plain) {
            scale(1f, domeRy / domeRx, domeC) { drawCircle(dome, domeRx, domeC) }
            scale(1f, footRy / footRx, footC) { drawCircle(foot, footRx, footC) }
            drawRect(skyLine, Offset(0f, h * HORIZON), Size(w, h * 0.01f))
            var x = 79f * dp
            while (x < w) {
                drawLine(pillar, Offset(x, 0f), Offset(x, h), dp)
                x += 80f * dp
            }
            if (glowK > 0f) drawCircle(outerGlow, r + 70f * dp, c)
        }
        drawCircle(inside, r - bandW / 2f, c)
        if (!plain && glowK > 0f) drawCircle(innerGlow, r - bandW / 2f, c)
        rotate(110f, c) { drawCircle(rim, r, c, style = ringStroke) }
        drawCircle(rimInk, r - bandW / 2f, c, style = edge)
        drawCircle(rimInk, r + bandW / 2f, c, style = edge)
    }
}

/** The page's still colour [fy] of the way down it: what the window frame's corners are painted with. */
private fun pageColorAt(fy: Float, light: Boolean): Color =
    lerp(if (light) DaySky else NightSky, if (light) DayFloor else NightFloor, fy.coerceIn(0f, 1f))

// --- Bar --------------------------------------------------------------------------

/**
 * The bar: the car's fixed-hub steering wheel. Its ring (drawn by the page
 * background) rises from the bottom edge; on its rim Apps and Layout on the
 * left, the OBD link and ⋮ on the right, the inner ones higher as they follow
 * the ring; in the fixed hub the big Talk button. Beside the wheel, the time
 * and date in the bottom-left corner (not while a clock tile or the head
 * unit's own bar shows the time) with the dashboard's position, and in the
 * bottom-right corner the outside temperature, after the setup pill, the
 * vehicle alerts, the phone and the demo badge where they fit. The wheel
 * keeps to the window's middle even when the bar stops short of a parked
 * window's cover.
 */
@Composable
internal fun PanoramaTopBar(m: TopBarModel) {
    val view = LocalView.current
    val density = LocalDensity.current
    val barLeft = remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(PANO_BAR)
            .onGloballyPositioned { barLeft.floatValue = it.positionInWindow().x }
            .graphicsLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val wheel = panoWheel(with(density) { view.width.toDp().value })
        val windowMid = with(density) { (view.width / 2f - barLeft.floatValue).toDp().value }
        val cx = windowMid.coerceIn(wheel.span, (maxWidth.value - wheel.span).coerceAtLeast(wheel.span))
        val barH = PANO_BAR.value
        val leftW = (cx - wheel.span - 10f).coerceAtLeast(0f).dp
        val rightW = (maxWidth.value - cx - wheel.span - 10f).coerceAtLeast(0f).dp

        // The hub, drawn under its button; it takes no touches.
        PanoHub(wheel, Modifier.offset { IntOffset(((cx - wheel.hub) * density.density).roundToInt(), ((wheel.hubY - wheel.hub) * density.density).roundToInt()) }
            .size((wheel.hub * 2f).dp))

        fun place(x: Float, y: Float, d: Float) = Modifier.offset { IntOffset(((x - d / 2f) * density.density).roundToInt(), ((y - d / 2f) * density.density).roundToInt()) }
        val b = wheel.button
        // The rim: outer buttons low, inner ones higher, as the ring rises toward the hub.
        PanoRimButton(
            onClick = m.onApps, description = stringResource(R.string.dash_all_apps), size = b,
            modifier = place(cx - wheel.outerX, barH - wheel.outerY, b)
        ) { Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(30.dp)) }
        Box(place(cx - wheel.innerX, barH - wheel.innerY, b)) {
            LayoutPicker(m) { open ->
                PanoRimButton(onClick = open, description = stringResource(R.string.dash_screen_layout, m.layout.title), size = b) {
                    LayoutIcon(m.layout, null, DashColors.TextPrimary, Modifier.size(30.dp))
                }
            }
        }
        Box(place(cx + wheel.innerX, barH - wheel.innerY, b)) { PanoRimObd(m.obdConnection, m.onConnectObd, b) }
        Box(place(cx + wheel.outerX, barH - wheel.outerY, b)) {
            MorePicker(m, geminiInBar = true) { open ->
                PanoRimButton(onClick = open, description = stringResource(R.string.dash_more), size = b) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(30.dp))
                }
            }
        }
        Box(place(cx, wheel.talkY, wheel.talk + TALK_HALO)) { PanoTalkButton(wheel.talk) }

        // The corners beside the wheel.
        Row(
            modifier = Modifier.align(Alignment.BottomStart).width(leftW).height(CORNER_H).padding(start = 22.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val showClock = !m.merged && !ClockInSight.on.value
            if (showClock) PanoBarClock(m.clock, withDate = !narrow && leftW >= 300.dp, Modifier.weight(1f, fill = false))
            // A narrow corner keeps the time; the dashboard's position goes to the other side.
            if (!narrow || !showClock) PagePositionChip(Modifier.padding(start = if (showClock) 8.dp else 0.dp, bottom = 2.dp))
        }
        BarEnd(modifier = Modifier.align(Alignment.BottomEnd).width(rightW).height(CORNER_H).padding(end = 22.dp, bottom = 10.dp)) {
            if (m.demo) {
                Box(Modifier.layoutId(BarRank.DEMO).padding(end = 8.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
            }
            Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                VehicleAlerts(m.obdConnection, m.obd)
            }
            Box(Modifier.layoutId(BarRank.PHONE).padding(end = 8.dp)) { PhonePill() }
            if (m.setupPending) {
                Box(Modifier.layoutId(BarRank.SETUP).padding(end = 8.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = narrow) }
            }
            if (narrow && !m.merged && !ClockInSight.on.value) {
                Box(Modifier.layoutId(BarRank.CLOCK).padding(end = 6.dp)) { PagePositionChip() }
            }
            Box(Modifier.layoutId(BarRank.TEMP)) { PanoBarTemp(withCondition = !narrow) }
        }
    }
}

/**
 * The wheel's fixed hub: a disc of glass with an ice-blue rim, lit from
 * inside around the Talk button (a soft glow at night, none by day or with
 * effects off). Its lower part runs off the bottom edge, as in the car.
 */
@Composable
private fun PanoHub(wheel: PanoWheel, modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val lit = DashColors.Accent
    val rimInk = panoRim
    val glowK = panoGlow
    Spacer(
        modifier.cachedDraw(light, effects, lit, rimInk, glowK, wheel) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val plain = effects == DashEffects.NONE
            val fill: Brush = when {
                plain -> SolidColor(if (light) Color.White else Color(0xFF0E1E42))
                light -> Brush.radialGradient(listOf(Color.White, Color(0xFFF4F7FB)), Offset(c.x, c.y - r * 0.3f), r)
                else -> Brush.radialGradient(listOf(Color(0xFF1A3366), Color(0xFF0A1838)), Offset(c.x, c.y - r * 0.3f), r)
            }
            val glow = if (glowK > 0f) Brush.radialGradient(
                0f to lit.copy(alpha = 0f), 0.62f to lit.copy(alpha = 0f), 0.78f to lit.copy(alpha = 0.22f * glowK), 1f to Color.Transparent,
                center = c, radius = r * 1.45f
            ) else null
            val shade = if (light && !plain) Brush.radialGradient(listOf(Color(0x2214284A), Color.Transparent), Offset(c.x, c.y + r * 0.08f), r * 1.25f) else null
            onDrawBehind {
                if (glow != null) drawCircle(glow, r * 1.45f, c)
                if (shade != null) drawCircle(shade, r * 1.25f, Offset(c.x, c.y + r * 0.08f))
                drawCircle(fill, r, c)
                drawCircle(rimInk, r - density / 2f, c, style = Stroke(density))
                if (!plain) drawArc(
                    Color.White.copy(alpha = if (light) 0.9f else 0.22f), 205f, 130f, false,
                    Offset(c.x - r + 2f * density, c.y - r + 2f * density), Size(2f * r - 4f * density, 2f * r - 4f * density),
                    style = Stroke(density, cap = StrokeCap.Round)
                )
            }
        }
    )
}

/** A glass button on the wheel's rim: [size] dp square with rounded corners, the icon alone, as on the wheel's own keys. */
@Composable
private fun PanoRimButton(onClick: () -> Unit, description: String, size: Float, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = modifier
            .size(size.dp)
            .panoGlass((size * 0.32f).dp)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { icon() }
}

/**
 * The OBD link on the rim: a dot in the link's colour (lit with a halo when
 * live, hollow when off, pulsing while connecting) over the letters OBD, a
 * "!" beside them on error, so it reads without its colour. What a tap does
 * is the shared [ObdMark]'s.
 */
@Composable
private fun PanoRimObd(state: ObdConnectionState, onConnect: () -> Unit, size: Float) {
    val color = obdStatusColor(state)
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    val halo = panoGlow
    ObdMark(state, onConnect, shape = RoundedCornerShape((size * 0.32f).dp)) {
        Column(
            modifier = Modifier.size(size.dp).panoGlass((size * 0.32f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(
                Modifier
                    .size(16.dp)
                    .cachedDraw(state, color, halo) {
                        val r = 5.dp.toPx()
                        val glow = Brush.radialGradient(listOf(color.copy(alpha = 0.55f * halo), Color.Transparent), Offset(this.size.width / 2f, this.size.height / 2f), r * 2.2f)
                        val ring = Stroke(2.dp.toPx())
                        onDrawBehind {
                            when (state) {
                                ObdConnectionState.CONNECTED -> {
                                    if (halo > 0f) drawCircle(glow, r * 2.2f)
                                    drawCircle(color, r)
                                }
                                ObdConnectionState.DISCONNECTED -> drawCircle(color, r - 1.dp.toPx(), style = ring)
                                ObdConnectionState.CONNECTING -> drawCircle(color, r, alpha = 0.3f + 0.7f * (pulse?.value ?: 1f))
                                ObdConnectionState.ERROR -> drawCircle(color, r)
                            }
                        }
                    }
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PanoLine(
                    stringResource(R.string.dash_obd_short),
                    panoText(15f, if (state == ObdConnectionState.DISCONNECTED) DashColors.TextSecondary else DashColors.TextPrimary, FontWeight.Medium, caps = true)
                )
                if (state == ObdConnectionState.ERROR) PanoLine("!", panoText(15f, color, FontWeight.Bold))
            }
        }
    }
}

/**
 * Talk, in the hub: a round lit button with the microphone, a halo around it
 * that breathes slowly (effects at full; still when reduced, none when off)
 * and the word under it. It opens Gemini Live (Dashwheel's own or Google's)
 * when there is one, else the unit's voice assistant. While a conversation
 * is open it turns red with an end mark and its ring pulses whatever the
 * effects, since it then tells the driver Gemini is listening.
 */
@Composable
private fun PanoTalkButton(size: Float) {
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    val effects = DashColors.Effects
    val light = DashColors.Light
    val tap = rememberTapFeedback()
    val word = stringResource(if (active) R.string.ai_gemini_live_end else R.string.skin_panorama_talk)
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.skin_panorama_talk_cd)
    val fill = if (active) DashColors.Critical else DashColors.Accent
    val ink = if (active) Color.White else panoOnLit
    val breath = when {
        active -> rememberLoop(1_400, status = true)
        effects == DashEffects.FULL -> rememberLoop(3_200, reverse = true)
        else -> null
    }
    val glowK = if (active) 1f else panoGlow
    Box(contentAlignment = Alignment.Center) {
        // The halo and its word sit outside the button's own box: drawn, never touched.
        Spacer(
            Modifier
                .size((size + TALK_HALO).dp)
                .cachedDraw(fill, glowK, light, breath, active) {
                    val r0 = size.dp.toPx() / 2f
                    val ringW = 9.dp.toPx()
                    val glow = Brush.radialGradient(
                        0f to fill.copy(alpha = 0.5f * glowK), 0.55f to fill.copy(alpha = 0.18f * glowK), 1f to Color.Transparent,
                        center = Offset(this.size.width / 2f, this.size.height / 2f), radius = r0 + 14.dp.toPx()
                    )
                    onDrawBehind {
                        val t = breath?.value
                        if (glowK > 0f) drawCircle(glow, r0 + 14.dp.toPx(), alpha = if (t != null && !active) 0.7f + 0.3f * t else 1f)
                        val ringA = when {
                            active && t != null -> 0.45f * (1f - t)
                            light -> 0.12f
                            else -> 0.10f + 0.06f * (t ?: 0.5f)
                        }
                        drawCircle(fill.copy(alpha = ringA), r0 + ringW / 2f + (if (active && t != null) t * 8.dp.toPx() else 0f), style = Stroke(ringW))
                    }
                }
        )
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .cachedDraw(fill, light) {
                    val top = lerp(fill, Color.White, if (light) 0.12f else 0.25f)
                    val bottom = lerp(fill, Color.Black, if (light) 0.15f else 0.12f)
                    val brush = Brush.verticalGradient(listOf(top, bottom))
                    onDrawBehind { drawCircle(brush) }
                }
                .clickable(role = Role.Button, onClickLabel = description) {
                    tap()
                    if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context)
                }
                .semantics(mergeDescendants = true) { contentDescription = description },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (active) Icons.Filled.Close else Icons.Filled.Mic, contentDescription = null, tint = ink, modifier = Modifier.size((size * 0.44f).dp))
        }
        PanoLine(
            word, panoText(14f, if (active) DashColors.Critical else DashColors.TextSecondary, FontWeight.Medium, caps = true),
            Modifier.offset(y = (size / 2f + 10f).dp), align = TextAlign.Center, caps = true
        )
    }
}

/** The corner time: large and thin, the date under it on a wide bar. */
@Composable
private fun PanoBarClock(clock: String, withDate: Boolean, modifier: Modifier = Modifier) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val dateFmt = rememberDateFormat(PANO_LONG_DATE, best = true)
    Column(modifier) {
        PanoLine(clock, panoText(40f, DashColors.TextPrimary, FontWeight.Light, thin = true), overflow = TextOverflow.Clip)
        if (withDate) PanoLine(dateFmt.format(now).replaceFirstChar { it.titlecase(locale) }, panoText(18f, DashColors.TextSecondary))
    }
}

/** Skeleton of weekday, day and month ("Saturday 10 October"), in the locale's order. */
internal const val PANO_LONG_DATE = "EEEEdMMMM"

/** The corner's outside temperature, large and thin, the sky in words under it once the weather has come; nothing before either. */
@Composable
private fun PanoBarTemp(withCondition: Boolean) {
    val weather = rememberWeather()
    val temp = rememberOutsideTempC() ?: return
    val units = LocalUnits.current
    Column(horizontalAlignment = Alignment.End) {
        PanoLine("${units.temp(temp)}°", panoText(40f, DashColors.TextPrimary, FontWeight.Light, thin = true), overflow = TextOverflow.Clip)
        if (withCondition && weather != null) PanoLine(weather.condition, panoText(18f, DashColors.TextSecondary), Modifier.width(170.dp), align = TextAlign.End)
    }
}

// --- Window frame -----------------------------------------------------------------

/**
 * The frame over a docked app window: its corners rounded off in the sky's
 * colour behind them (read from where the window sits on the page), an
 * ice-blue glass rim and, at night, the catch of light along its top edge,
 * so the map reads as one more pane of the cabin. The middle stays clear.
 */
@Composable
internal fun PanoramaWindowFrame(modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val rimInk = panoRim
    val view = LocalView.current
    val origin = remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> origin.value = c.positionInWindow() }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = PANO_CARD.toPx()
                val o = origin.value ?: Offset.Zero
                val vh = view.height.toFloat().coerceAtLeast(1f)
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val maskBrush = Brush.verticalGradient(listOf(pageColorAt(o.y / vh, light), pageColorAt((o.y + h) / vh, light)))
                val rimStroke = Stroke(1.dp.toPx())
                val half = rimStroke.width / 2f
                val catchLight = if (!light && effects != DashEffects.NONE) {
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.3f), Color.Transparent), r, w - r)
                } else null
                onDrawBehind {
                    drawPath(mask, maskBrush)
                    drawRoundRect(rimInk, Offset(half, half), Size(w - 2f * half, h - 2f * half), CornerRadius(r - half), style = rimStroke)
                    if (catchLight != null) drawLine(catchLight, Offset(r, half + 0.5f), Offset(w - r, half + 0.5f), 1.dp.toPx())
                }
            }
    )
}

// --- Theme stamp --------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): the curved band with
 * its lit segments and the speed, the car on its glowing plinth between two
 * glass cards, and the wheel's ring with its lit hub at the foot.
 */
internal fun DrawScope.panoramaStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    val lit = p.Accent
    val glass = if (light) Color.White.copy(alpha = 0.85f) else Color(0x24A0D2FF)
    val rim = if (light) Color(0x3328466E) else Color(0x5596CDFF)
    // The band.
    val band = Path().apply {
        moveTo(w * 0.08f, h * 0.17f)
        cubicTo(w * 0.08f, h * 0.06f, w * 0.3f, h * 0.04f, w * 0.5f, h * 0.04f)
        cubicTo(w * 0.7f, h * 0.04f, w * 0.92f, h * 0.06f, w * 0.92f, h * 0.17f)
        lineTo(w * 0.93f, h * 0.27f)
        lineTo(w * 0.07f, h * 0.27f)
        close()
    }
    drawPath(band, glass)
    drawPath(band, rim, style = Stroke(1f))
    for (i in 0 until 9) {
        val on = i < 4
        drawRect(if (on) lit else lit.copy(alpha = 0.15f), Offset(w * (0.13f + i * 0.025f), h * 0.15f), Size(w * 0.013f, h * 0.06f))
        drawRect(if (i >= 3) lit else lit.copy(alpha = 0.15f), Offset(w * (0.65f + i * 0.025f), h * 0.15f), Size(w * 0.013f, h * 0.06f))
    }
    number("87", w * 0.5f, h * 0.16f, 11f, p.TextPrimary)
    // The cards.
    val corner = CornerRadius(h * 0.05f)
    drawRoundRect(glass, Offset(w * 0.04f, h * 0.34f), Size(w * 0.2f, h * 0.36f), corner)
    drawRoundRect(rim, Offset(w * 0.04f, h * 0.34f), Size(w * 0.2f, h * 0.36f), corner, style = Stroke(1f))
    drawRoundRect(glass, Offset(w * 0.76f, h * 0.34f), Size(w * 0.2f, h * 0.36f), corner)
    drawRoundRect(rim, Offset(w * 0.76f, h * 0.34f), Size(w * 0.2f, h * 0.36f), corner, style = Stroke(1f))
    drawLine(lit, Offset(w * 0.09f, h * 0.5f), Offset(w * 0.09f, h * 0.42f), 1.5f, StrokeCap.Round)
    drawLine(lit, Offset(w * 0.09f, h * 0.42f), Offset(w * 0.14f, h * 0.42f), 1.5f, StrokeCap.Round)
    drawRoundRect(lit, Offset(w * 0.82f, h * 0.58f), Size(w * 0.08f, h * 0.07f), CornerRadius(h * 0.02f))
    // The car on its plinth.
    val plinthC = Offset(w * 0.5f, h * 0.64f)
    scale(1f, 0.16f, plinthC) {
        drawCircle(Brush.radialGradient(listOf(lit.copy(alpha = if (light) 0.3f else 0.5f), Color.Transparent), plinthC, w * 0.2f), w * 0.2f, plinthC)
        drawCircle(lit.copy(alpha = 0.6f), w * 0.17f, plinthC, style = Stroke(6f))
    }
    val car = Rect(w * 0.34f, h * 0.38f, w * 0.66f, h * 0.38f + w * 0.32f / PANO_CAR_ASPECT)
    drawPanoCar(car, light)
    // The wheel and its hub.
    val wc = Offset(w * 0.5f, h * 1.08f)
    drawCircle(rim, h * 0.3f, wc, style = Stroke(h * 0.035f))
    drawCircle(if (light) Color.White else Color(0xFF0A1838), h * 0.11f, Offset(w * 0.5f, h * 0.93f))
    drawCircle(lit, h * 0.06f, Offset(w * 0.5f, h * 0.91f))
}
