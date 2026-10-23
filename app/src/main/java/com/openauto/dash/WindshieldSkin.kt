package com.openauto.dash

import android.graphics.Typeface
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import java.util.Locale

/*
 * Windshield skin (2026-10-13 trends): BMW Neue Klasse's Panoramic Vision
 * band and AR guidance painted on the road. Whole-design skin (see Skins.kt):
 * the page is a drawn road at night with the driver's car seen from behind
 * (WindshieldSkinScene.kt), the bar is the black glass band along the foot of
 * the windscreen holding what the driver glances at, and the widgets float
 * over the road as AR tags (WindshieldSkinTiles.kt).
 * Mockup: D:/dashwheel-design/v16-trends/index.html, section 5.
 *
 * The band sits at the foot of the screen, where the launcher's bar lives
 * (AutomotiveDashboard puts every bar at the bottom: the unit's status bar
 * owns the top edge whenever a window floats). That is where BMW projects its
 * band too, along the base of the windscreen.
 */

internal val WindshieldPalette = DashPalette(
    Background = Color(0xFF07070C), Bar = Color.Transparent, Card = Color(0xFF0A1022), CardHi = Color(0x2246C8FF),
    Accent = Color(0xFF7FE3FF), Secondary = Color(0xFF46C8FF), Critical = Color(0xFFFF5A5F), Good = Color(0xFF52E3A4),
    Muted = Color(0xFF8AA0BC), TextPrimary = Color(0xFFEEF6FF), TextSecondary = Color(0xFFA9BDD6),
    Accent2 = Color(0xFFFFD27A), Line = Color(0x478CDCFF), Glow = 0.6f, Bare = true,
    BackgroundStops = listOf(Color(0xFF04050D), Color(0xFF0B0E2A), Color(0xFF07070C)),
    Tacho = Color(0xFFFFB547),
    Font = DashFont.CONDENSED,
    HeroWeight = FontWeight.SemiBold,
    Skin = DashSkin.WINDSHIELD
)

internal val WindshieldLightPalette = DashPalette(
    Background = Color(0xFFDFE7EF), Bar = Color.Transparent, Card = Color(0xFFF7FAFD), CardHi = Color(0x14006A99),
    Accent = Color(0xFF00739E), Secondary = Color(0xFF0A8FC4), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF566B82), TextPrimary = Color(0xFF0C1726), TextSecondary = Color(0xFF384B62),
    Accent2 = Color(0xFFB8730A), Line = Color(0x33006A99), Glow = 0.1f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFCFE2F3), Color(0xFFE6EEF5), Color(0xFFDDE3E9)),
    Tacho = Color(0xFFB0600A),
    Font = DashFont.CONDENSED,
    HeroWeight = FontWeight.SemiBold,
    Skin = DashSkin.WINDSHIELD
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val WindshieldShapes = skinShapes(24, 22, 12)

/**
 * The Home Windshield was drawn around (SkinLayouts.kt): mostly road. The
 * QuickSelect column of apps down the left, the next turn as a cyan tag over
 * the left roadside, the weather's tag over the right one, the music card in
 * the bottom-right corner; the middle stays clear for the road, the arrows
 * on it and the car (the page's own scene). The speed and the turn are on
 * the band.
 */
internal val WindshieldLayout = SkinLayout(
    home = TemplatePage(listOf(BuiltinKind.SPEED_HUD, BuiltinKind.NAVIGATION, BuiltinKind.MEDIA, BuiltinKind.WEATHER), dock = true),
    wide = listOf(
        DashboardItem.LaunchBar(x = 0, y = 1, w = 1, h = 5),
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 2, y = 1, w = 3, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.WEATHER, x = 8, y = 1, w = 3, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 9, y = 5, w = 3, h = 2)
    )
)

// --- Shared look --------------------------------------------------------------------

/** The band's height: one line of large readouts, the keys at its ends. */
internal val WS_BAR = 80.dp

/** Corners of the AR tags and the cards. */
internal val WS_TAG = 14.dp
internal val WS_CARD = 24.dp

/** The glow around lit things: full at night, a trace by day, none with effects off. */
internal val wsGlow: Float get() = DashColors.Effects.scale * (if (DashColors.Light) 0.35f else 1f)

/** The AR ink: cyan at night, deep sea blue by day. */
internal val wsLit: Color get() = DashColors.Accent

/** The rim of the smoked glass. */
internal val wsRim: Color get() = if (DashColors.Light) Color(0x3D0A3C5C) else Color(0x598CDCFF)

/** The DIN-like condensed face (Barlow Condensed in the mockup): Roboto Condensed at the weights Android ships. */
private fun condensed(weight: Int): FontFamily =
    FontFamily(robotoWeight(weight, condensed = true))

// Lazy: made on first use, never while the palettes above load (the JVM tests have no typefaces).
private val WsRegular by lazy { condensed(400) }
private val WsMedium by lazy { condensed(500) }
private val WsSemi by lazy { condensed(600) }
private val WsBold by lazy { condensed(700) }

private fun wsFamily(weight: FontWeight): FontFamily = when {
    weight.weight < 450 -> WsRegular
    weight.weight < 550 -> WsMedium
    weight.weight < 650 -> WsSemi
    else -> WsBold
}

/** The skin's type at [size] dp whatever the system font scale; [caps] spaces the letters as the HUD's labels. */
@Composable
internal fun wsText(size: Float, color: Color = DashColors.TextPrimary, weight: FontWeight = FontWeight.Normal, caps: Boolean = false): TextStyle =
    TextStyle(
        color = color,
        fontFamily = wsFamily(weight),
        fontSize = fixedSp(size),
        fontFeatureSettings = "tnum",
        lineHeight = 1.18.em,
        letterSpacing = if (caps) 0.24.em else 0.01.em
    )

/** One run of the skin's text: one line with an ellipsis unless [maxLines] says otherwise. */
@Composable
internal fun WsLine(
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
 * Smoked glass behind an AR tag or a card, clipped to its corners: a deep
 * navy at 70 % with a cyan hairline rim and a soft shadow at night, a pale
 * frosted pane with a blue-grey rim by day. [lit] is the cyan tag of the
 * guidance: tinted with the lit ink, a brighter rim and a glow round it.
 * Effects off: the solid card and its rim. While arranging, the rim is dashed.
 */
@Composable
internal fun Modifier.wsSmoke(radius: Dp = WS_TAG, lit: Boolean = false, editing: Boolean = false): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val card = DashColors.Card
    val ink = wsLit
    val rimInk = wsRim
    val glowK = wsGlow
    val shape = remember(radius) { RoundedCornerShape(radius) }
    return this
        .cachedDraw(light, effects, card, ink, rimInk, glowK, lit, editing, radius) {
            val w = size.width
            val h = size.height
            val r = radius.toPx().coerceAtMost(minOf(w, h) / 2f)
            val plain = effects == DashEffects.NONE
            val fill: Brush = when {
                plain -> SolidColor(if (lit) androidx.compose.ui.graphics.lerp(card, ink, 0.12f) else card)
                lit && light -> SolidColor(Color(0xF0F2FAFE))
                lit -> Brush.verticalGradient(listOf(Color(0xD9081A2C), Color(0xD9061220)))
                light -> SolidColor(Color(0xE6FBFDFF))
                else -> Brush.verticalGradient(listOf(Color(0xC20A1022), Color(0xB3080C1C)))
            }
            val tint = if (lit && !plain) ink.copy(alpha = if (light) 0.08f else 0.14f) else null
            val rim = when {
                lit -> ink.copy(alpha = if (plain) 0.9f else if (light) 0.7f else 0.6f)
                plain -> rimInk.copy(alpha = (rimInk.alpha * 1.6f).coerceAtMost(1f))
                else -> rimInk
            }.let { if (editing) it.copy(alpha = (it.alpha * 1.6f).coerceAtMost(0.9f)) else it }
            val rimW = (if (lit || editing) 1.5f else 1f) * density
            val half = rimW / 2f
            val rimStroke = Stroke(rimW, pathEffect = if (editing) PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)) else null)
            // The glow of the lit tag: a few faint rounded rects, each wider than the last; no blur on this unit.
            val glowSteps = if (lit && glowK > 0f) 3 else 0
            // The shadow under the glass: stacked faint shapes, lower and wider.
            val shadowInk = if (light) Color(0xFF14284A) else Color.Black
            val shadow = if (!plain && !lit) {
                if (light) floatArrayOf(2f, 3f, 0.05f, 6f, 7f, 0.035f) else floatArrayOf(3f, 6f, 0.16f, 8f, 12f, 0.1f)
            } else null
            onDrawBehind {
                if (shadow != null) {
                    var i = 0
                    while (i < shadow.size) {
                        val k = shadow[i] * density
                        drawRoundRect(shadowInk.copy(alpha = shadow[i + 2]), Offset(-k, -k + shadow[i + 1] * density), Size(w + 2f * k, h + 2f * k), CornerRadius(r + k))
                        i += 3
                    }
                }
                for (s in 1..glowSteps) {
                    val g = s * 5f * density
                    drawRoundRect(ink.copy(alpha = 0.075f * glowK), Offset(-g, -g), Size(w + 2f * g, h + 2f * g), CornerRadius(r + g))
                }
                drawRoundRect(fill, cornerRadius = CornerRadius(r))
                if (tint != null) drawRoundRect(tint, cornerRadius = CornerRadius(r))
                drawRoundRect(rim, Offset(half, half), Size(w - rimW, h - rimW), CornerRadius(r - half), style = rimStroke)
            }
        }
        .clip(shape)
}

/** Whole box tappable with the tap feedback, or nothing when [enabled] is false. */
@Composable
internal fun Modifier.wsTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

/**
 * The next turn's arrow in the lit ink with its glow, drawn for the side the
 * navigation says (the mockup's arrow), or the app's own glyph tinted.
 */
@Composable
internal fun WsTurnArrow(nav: NavState, size: Dp, ink: Color = wsLit) {
    val glowK = wsGlow
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    if (bitmap != null) {
        Box(Modifier.size(size).glowHalo(ink.copy(alpha = 0.3f * glowK), 0.62f), contentAlignment = Alignment.Center) {
            Image(bitmap, contentDescription = nav.instruction, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(ink), modifier = Modifier.fillMaxSize(0.92f))
        }
        return
    }
    val side = nav.turnSide()
    Spacer(
        Modifier
            .size(size)
            .semantics { contentDescription = nav.instruction }
            .cachedDraw(side, ink, glowK) {
                val s = this.size.minDimension / 24f
                // The mockup's arrow in a 24 grid: up the stem, round the corner, out to the side, its head.
                val path = Path().apply {
                    when (side) {
                        TurnSide.AHEAD -> {
                            moveTo(12f * s, 21f * s); lineTo(12f * s, 4f * s)
                            moveTo(7f * s, 9f * s); lineTo(12f * s, 4f * s); lineTo(17f * s, 9f * s)
                        }
                        else -> {
                            val m = side == TurnSide.LEFT
                            fun x(v: Float) = (if (m) v else 24f - v) * s
                            moveTo(x(17f), 21f * s); lineTo(x(17f), 11f * s)
                            cubicTo(x(17f), 9.3f * s, x(15.7f), 8f * s, x(14f), 8f * s)
                            lineTo(x(5f), 8f * s)
                            moveTo(x(9f), 4f * s); lineTo(x(5f), 8f * s); lineTo(x(9f), 12f * s)
                        }
                    }
                }
                val stroke = Stroke(2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val halo = Stroke(7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    if (glowK > 0f) drawPath(path, ink.copy(alpha = 0.18f * glowK), style = halo)
                    drawPath(path, ink, style = stroke)
                }
            }
    )
}

/** The road's limit as its sign: a white disc in a red ring, the number in black, day and night. */
@Composable
internal fun WsLimitSign(limit: Int, d: Dp) {
    val said = stringResource(R.string.windshield_speed_limit_cd, limit)
    Box(
        modifier = Modifier
            .size(d)
            .semantics { contentDescription = said }
            .cachedDraw(d) {
                val ring = size.minDimension * 0.11f
                onDrawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(Color.White, r)
                    drawCircle(Color(0xFFFF3B4A), r - ring / 2f, style = Stroke(ring))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        WsLine(limit.toString(), wsText((d.value * if (limit >= 100) 0.36f else 0.44f).coerceAtLeast(13f), Color(0xFF111111), FontWeight.Bold), overflow = TextOverflow.Clip)
    }
}

/** A clock time standing alone in a navigation's ETA line ("21:24", "9:48 PM"). */
internal val WS_CLOCK_PART = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")

// --- The band (bar) -----------------------------------------------------------------

/**
 * The bar: BMW's Panoramic Vision band, a black glass band across the whole
 * width at the foot of the windscreen, holding everything glanced at. On the
 * left the Apps and Layout keys, small, then the speed large with the road's
 * limit sign and the dashboard's position; in the middle the next turn as a
 * cyan pill (arrow, distance, street; a tap opens the navigation app), or
 * the time while no route runs; on the right the arrival time while guiding
 * and the outside temperature, the setup pill, the alerts and the phone where
 * they fit, then Talk, the OBD dot and ⋮, small, at the end. By day a pale
 * frosted band with the same layout.
 */
@Composable
internal fun WindshieldTopBar(m: TopBarModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(WS_BAR)
            .graphicsLayer()
            .wsBand()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val middle = if (narrow) maxWidth * 0.34f else min(maxWidth * 0.42f, 600.dp)
        val side = (maxWidth - middle) / 2
        // The route is read where it is drawn: its distance changes every few seconds.
        val navState = NavDirections.state.collectAsState()
        val guiding by remember(navState) { derivedStateOf { navState.value.active } }
        val showClock = !m.merged && !ClockInSight.on.value

        // The start: keys, the speed and its limit, where the dashboards stand.
        BarEnd(Modifier.align(Alignment.CenterStart).width(side).fillMaxHeight().padding(start = 8.dp), alignEnd = false) {
            Box(Modifier.layoutId(BarRank.MORE)) {
                WsBandKey(onClick = m.onApps, description = stringResource(R.string.dash_all_apps)) {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(26.dp))
                }
            }
            Box(Modifier.layoutId(BarRank.OBD)) {
                LayoutPicker(m) { open ->
                    WsBandKey(onClick = open, description = stringResource(R.string.dash_screen_layout, m.layout.title)) {
                        LayoutIcon(m.layout, null, DashColors.TextSecondary, Modifier.size(26.dp))
                    }
                }
            }
            Box(Modifier.layoutId(BarRank.CLOCK).padding(start = 14.dp)) { WsBandSpeed(m) }
            Box(Modifier.layoutId(BarRank.SETUP).padding(start = 10.dp)) { PagePositionChip() }
        }

        // The middle: the next turn, or the time.
        Box(Modifier.align(Alignment.Center).width(middle).fillMaxHeight(), contentAlignment = Alignment.Center) {
            when {
                guiding -> WsTurnPill(navState, compact = narrow)
                showClock -> WsLine(m.clock, wsText(36f, DashColors.TextPrimary, FontWeight.SemiBold), overflow = TextOverflow.Clip)
            }
        }

        // The end: arrival and temperature, the shared pills, then Talk, OBD and ⋮.
        BarEnd(Modifier.align(Alignment.CenterEnd).width(side).fillMaxHeight().padding(end = 8.dp)) {
            if (guiding) Box(Modifier.layoutId(BarRank.CLOCK).padding(end = 18.dp)) { WsBandArrival(navState) }
            Box(Modifier.layoutId(BarRank.TEMP).padding(end = 14.dp)) { WsBandTemp() }
            if (m.demo) Box(Modifier.layoutId(BarRank.DEMO).padding(end = 8.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
            if (m.setupPending) Box(Modifier.layoutId(BarRank.SETUP).padding(end = 8.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = true) }
            Row(Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) { VehicleAlerts(m.obdConnection, m.obd) }
            Box(Modifier.layoutId(BarRank.PHONE).padding(end = 8.dp)) { PhonePill() }
            Box(Modifier.layoutId(BarRank.OBD)) { WsBandTalk() }
            Box(Modifier.layoutId(BarRank.OBD)) { WsBandObd(m.obdConnection, m.onConnectObd) }
            Box(Modifier.layoutId(BarRank.MORE)) {
                MorePicker(m, geminiInBar = true) { open ->
                    WsBandKey(onClick = open, description = stringResource(R.string.dash_more)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

/**
 * The band's glass: black, deeper toward the screen's edge, a cyan hairline
 * along its top with a faint glow under it at night; by day frosted white
 * with a blue hairline. Effects off: the plain fill and the line.
 */
@Composable
private fun Modifier.wsBand(): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val ink = wsLit
    return cachedDraw(light, effects, ink) {
        val plain = effects == DashEffects.NONE
        val fill: Brush = when {
            light && plain -> SolidColor(Color(0xFFF4F7FA))
            light -> Brush.verticalGradient(listOf(Color(0xE0FFFFFF), Color(0xF5F2F6FA)))
            plain -> SolidColor(Color(0xFF020306))
            else -> Brush.verticalGradient(listOf(Color(0x8C000000), Color(0xEB000000)))
        }
        val line = if (light) Color(0x400A3C5C) else ink.copy(alpha = 0.3f)
        val glow = if (!plain && !light) Brush.verticalGradient(listOf(ink.copy(alpha = 0.09f * effects.scale), Color.Transparent), 0f, 14f * density) else null
        onDrawBehind {
            drawRect(fill)
            if (glow != null) drawRect(glow, size = Size(size.width, 14f * density))
            drawRect(line, size = Size(size.width, density))
        }
    }
}

/** A key of the band: small to the eye, a 56 dp square to the finger. */
@Composable
private fun WsBandKey(onClick: () -> Unit, description: String, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    val fill = if (DashColors.Light) Color(0x0F0A3C5C) else Color(0x14A0D8FF)
    Box(
        modifier = Modifier
            .size(DashSize.TouchPrimary)
            .padding(4.dp)
            .cachedDraw(fill) { onDrawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(14.dp.toPx())) } }
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { icon() }
}

/**
 * The speed large, km/h beside it, then the road's limit sign; amber over the
 * limit. In its own scope, so a new reading redraws only this. Without a speed,
 * a dash, and a tap connects the adapter.
 */
@Composable
private fun WsBandSpeed(m: TopBarModel) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        SpeedLimit.start(context)
        onDispose { SpeedLimit.stop() }
    }
    val speed = rememberSpeedKmh(m.obdData, m.obdConnection)
    val limit by SpeedLimit.limit.collectAsState()
    val units = LocalUnits.current
    val over = speedOver(speed)
    val color = when {
        speed == null -> DashColors.Muted
        over -> DashColors.Warning
        else -> DashColors.TextPrimary
    }
    val said = speed?.let { "${units.speed(it)} ${units.speedUnit}" } ?: stringResource(R.string.info_speed_no_signal)
    val canConnect = speed == null && m.obdConnection.isIdle
    Row(
        Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .wsTap(canConnect, stringResource(R.string.horizon_connect_obd), m.onConnectObd)
            .semantics(mergeDescendants = true) { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically
    ) {
        WsLine(speed?.let { units.speed(it).toString() } ?: "–", wsText(38f, color, FontWeight.SemiBold), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
        Spacer(Modifier.width(8.dp))
        WsLine(units.speedUnit, wsText(18f, DashColors.TextSecondary), Modifier.alignByBaseline())
        limit?.let {
            Spacer(Modifier.width(14.dp))
            WsLimitSign(units.speed(it), 46.dp)
        }
    }
}

/**
 * The next turn as the band's cyan pill: the arrow, the distance in the lit
 * ink, the street in white; a narrow band keeps the arrow and the distance.
 * A tap opens the navigation app.
 */
@Composable
private fun WsTurnPill(navState: State<NavState>, compact: Boolean) {
    val nav = navState.value
    val context = LocalContext.current
    val (value, unit) = nav.distanceParts
    val distance = if (unit.isEmpty()) value else "$value $unit"
    Row(
        Modifier
            .height(58.dp)
            .wsSmoke(29.dp, lit = true)
            .wsTap(true, stringResource(R.string.horizon_open_navigation)) { openNavigationApp(context, nav) }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WsTurnArrow(nav, 34.dp)
        if (distance.isNotEmpty()) {
            Spacer(Modifier.width(14.dp))
            WsLine(distance, wsText(32f, wsLit, FontWeight.SemiBold), overflow = TextOverflow.Clip)
        }
        if (!compact || distance.isEmpty()) {
            Spacer(Modifier.width(14.dp))
            WsLine(nav.instruction, wsText(23f, DashColors.TextPrimary), Modifier.weight(1f, fill = false))
        }
    }
}

/** While guiding: "Arrival" and the time the navigation gives, or its first figure when it gives no time. */
@Composable
private fun WsBandArrival(navState: State<NavState>) {
    val parts = navState.value.etaParts
    val time = parts.firstOrNull { WS_CLOCK_PART.matches(it) }
    val value = time ?: parts.firstOrNull() ?: return
    Row(verticalAlignment = Alignment.Bottom) {
        if (time != null) {
            WsLine(stringResource(R.string.windshield_arrival), wsText(18f, DashColors.TextSecondary), Modifier.alignByBaseline())
            Spacer(Modifier.width(8.dp))
        }
        WsLine(value, wsText(34f, DashColors.TextPrimary, FontWeight.SemiBold), Modifier.alignByBaseline(), overflow = TextOverflow.Clip)
    }
}

/** The outside temperature, large; nothing until it is known. */
@Composable
private fun WsBandTemp() {
    val temp = rememberOutsideTempC() ?: return
    val units = LocalUnits.current
    WsLine("${units.temp(temp)}°", wsText(34f, DashColors.TextPrimary, FontWeight.SemiBold), overflow = TextOverflow.Clip)
}

/**
 * Talk, small at the band's end: the microphone in the lit ink. It opens
 * Gemini Live when there is one, else the unit's voice assistant; while a
 * conversation is open it turns red with an end mark that pulses.
 */
@Composable
private fun WsBandTalk() {
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.windshield_talk_cd)
    val pulse = if (active) rememberLoop(900, reverse = true, status = true) else null
    val red = DashColors.Critical
    WsBandKey(onClick = { if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context) }, description = description) {
        if (active) {
            Box(
                Modifier
                    .size(36.dp)
                    .cachedDraw(red, pulse) { onDrawBehind { drawCircle(red, alpha = 0.6f + 0.4f * (pulse?.value ?: 1f)) } },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        } else {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = wsLit, modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * The OBD link as a dot over the letters OBD (lit with a halo when live,
 * hollow when off, pulsing while connecting, a "!" on error), so it reads
 * without its colour. What a tap does is the shared [ObdMark]'s.
 */
@Composable
private fun WsBandObd(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = obdStatusColor(state)
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    val halo = wsGlow
    ObdMark(state, onConnect, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.size(DashSize.TouchPrimary), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(
                Modifier
                    .size(14.dp)
                    .cachedDraw(state, color, halo) {
                        val r = 4.5.dp.toPx()
                        val glow = Brush.radialGradient(listOf(color.copy(alpha = 0.55f * halo), Color.Transparent), Offset(size.width / 2f, size.height / 2f), r * 2.4f)
                        val ring = Stroke(2.dp.toPx())
                        onDrawBehind {
                            when (state) {
                                ObdConnectionState.CONNECTED -> {
                                    if (halo > 0f) drawCircle(glow, r * 2.4f)
                                    drawCircle(color, r)
                                }
                                ObdConnectionState.DISCONNECTED -> drawCircle(color, r - 1.dp.toPx(), style = ring)
                                ObdConnectionState.CONNECTING -> drawCircle(color, r, alpha = 0.3f + 0.7f * (pulse?.value ?: 1f))
                                ObdConnectionState.ERROR -> drawCircle(color, r)
                            }
                        }
                    }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                WsLine(stringResource(R.string.dash_obd_short), wsText(14f, if (state == ObdConnectionState.DISCONNECTED) DashColors.TextSecondary else DashColors.TextPrimary, FontWeight.Medium))
                if (state == ObdConnectionState.ERROR) WsLine("!", wsText(14f, color, FontWeight.Bold))
            }
        }
    }
}

// --- Window frame ---------------------------------------------------------------------

/**
 * The frame over a docked app window, as an AR viewfinder: a thin cyan
 * hairline round it and short bright brackets at its four corners with a
 * soft glow at night. The middle stays clear and it takes no touches.
 */
@Composable
internal fun WindshieldWindowFrame(modifier: Modifier) {
    val ink = wsLit
    val glowK = wsGlow
    val light = DashColors.Light
    Box(
        modifier
            .fillMaxSize()
            .cachedDraw(ink, glowK, light) {
                val w = size.width
                val h = size.height
                val r = 12.dp.toPx()
                val hair = Stroke(1.dp.toPx())
                val armLen = minOf(26.dp.toPx(), minOf(w, h) * 0.2f)
                val brackets = Path().apply {
                    val i = 1.5.dp.toPx()
                    // Each corner: down its side, round the corner, along its top or bottom.
                    moveTo(i, i + armLen); lineTo(i, i + r * 0.6f); quadraticTo(i, i, i + r * 0.6f, i); lineTo(i + armLen, i)
                    moveTo(w - i - armLen, i); lineTo(w - i - r * 0.6f, i); quadraticTo(w - i, i, w - i, i + r * 0.6f); lineTo(w - i, i + armLen)
                    moveTo(w - i, h - i - armLen); lineTo(w - i, h - i - r * 0.6f); quadraticTo(w - i, h - i, w - i - r * 0.6f, h - i); lineTo(w - i - armLen, h - i)
                    moveTo(i + armLen, h - i); lineTo(i + r * 0.6f, h - i); quadraticTo(i, h - i, i, h - i - r * 0.6f); lineTo(i, h - i - armLen)
                }
                val bracket = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val halo = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    drawRoundRect(ink.copy(alpha = if (light) 0.45f else 0.4f), Offset(0.5f, 0.5f), Size(w - 1f, h - 1f), CornerRadius(r), style = hair)
                    if (glowK > 0f) drawPath(brackets, ink.copy(alpha = 0.2f * glowK), style = halo)
                    drawPath(brackets, ink, style = bracket)
                }
            }
    )
}

// --- Theme stamp ----------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): the road at dusk (by
 * day in the light palette) bending toward a turn, the cyan arrows on its
 * lane, the car from behind with its lamps lit, and the black band along the
 * foot with the speed and the turn's pill.
 */
internal fun DrawScope.windshieldStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    val lit = p.Accent
    val hy = h * 0.36f
    // Sky and ground.
    drawRect(
        Brush.verticalGradient(
            if (light) listOf(Color(0xFF9EC3E6), Color(0xFFDCEAF5)) else listOf(Color(0xFF04050D), Color(0xFF0B0E2A), Color(0xFF2A1846), Color(0xFF8A3B45)),
            0f, hy
        ),
        size = Size(w, hy)
    )
    drawRect(
        Brush.verticalGradient(if (light) listOf(Color(0xFFC2C9CF), Color(0xFFA6AEB6)) else listOf(Color(0xFF1A1022), Color(0xFF07070C)), hy, h),
        Offset(0f, hy), Size(w, h - hy)
    )
    // The road, bending left far ahead.
    val cx = w * 0.5f
    val road = Path().apply {
        moveTo(cx - w * 0.34f, h)
        cubicTo(cx - w * 0.14f, h * 0.62f, cx - w * 0.06f, h * 0.48f, cx - w * 0.2f, hy + h * 0.02f)
        lineTo(cx - w * 0.15f, hy + h * 0.02f)
        cubicTo(cx + w * 0.01f, h * 0.48f, cx + w * 0.14f, h * 0.62f, cx + w * 0.34f, h)
        close()
    }
    drawPath(road, if (light) Color(0xFF6F7780) else Color(0xFF0D0E15))
    val edge = if (light) Color.White else Color(0xFFBFC8D8).copy(alpha = 0.6f)
    drawLine(edge, Offset(cx - w * 0.31f, h), Offset(cx - w * 0.12f, h * 0.6f), 1.2f)
    drawLine(edge, Offset(cx + w * 0.31f, h), Offset(cx + w * 0.1f, h * 0.6f), 1.2f)
    // Street lights.
    if (!light) {
        for (k in 0 until 3) {
            val f = 1f - k * 0.28f
            for (sd in intArrayOf(-1, 1)) {
                val c = Offset(cx + sd * w * 0.42f * f, h * (0.5f + 0.2f * f))
                drawCircle(Brush.radialGradient(listOf(Color(0x8CFFC882), Color.Transparent), c, h * 0.08f * f), h * 0.08f * f, c)
            }
        }
    }
    // The arrows on the lane.
    val arrow = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (k in 0 until 3) {
        val y = h * (0.56f - k * 0.06f)
        val x = cx - w * (0.02f + k * 0.03f)
        val half = w * (0.06f - k * 0.012f)
        val path = Path().apply { moveTo(x - half, y + h * 0.03f); lineTo(x, y); lineTo(x + half, y + h * 0.03f) }
        if (!light) drawPath(path, lit.copy(alpha = 0.3f), style = Stroke(6f, cap = StrokeCap.Round))
        drawPath(path, lit, style = arrow)
    }
    // The car from behind, lamps lit.
    val car = Rect(cx - w * 0.11f, h * 0.6f, cx + w * 0.11f, h * 0.6f + w * 0.22f * WS_CAR_ASPECT_INV)
    if (!light) {
        val glowC = Offset(cx, car.top + car.height * 0.55f)
        drawCircle(Brush.radialGradient(listOf(Color(0x66FF1A2A), Color.Transparent), glowC, w * 0.2f), w * 0.2f, glowC)
    }
    drawWsDrawnCar(car, light, glow = 0f)
    // The band along the foot: the speed on the left, the turn's pill in the middle.
    val bandTop = h * 0.84f
    drawRect(if (light) Color(0xF2F4F7FA) else Color(0xF0000000), Offset(0f, bandTop), Size(w, h - bandTop))
    drawLine(lit.copy(alpha = 0.5f), Offset(0f, bandTop), Offset(w, bandTop), 1f)
    number("87", w * 0.12f, (bandTop + h) / 2f, 8f, p.TextPrimary)
    val pill = Size(w * 0.3f, (h - bandTop) * 0.62f)
    val pillTl = Offset(cx - pill.width / 2f, (bandTop + h) / 2f - pill.height / 2f)
    drawRoundRect(lit.copy(alpha = 0.16f), pillTl, pill, CornerRadius(pill.height / 2f))
    drawRoundRect(lit.copy(alpha = 0.7f), pillTl, pill, CornerRadius(pill.height / 2f), style = Stroke(1f))
    drawLine(lit, Offset(pillTl.x + pill.height * 0.6f, pillTl.y + pill.height * 0.5f), Offset(pillTl.x + pill.width * 0.75f, pillTl.y + pill.height * 0.5f), 2f, StrokeCap.Round)
}

/** Space a fallback widget is given: the skin's smoked card ([SkinFallbackPanel]). */
@Composable
internal fun Modifier.windshieldFallbackPanel(): Modifier = wsSmoke(WS_CARD)
