package com.openauto.dash

import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.min

/*
 * Kinetic skin (2026-10-13 trends): kinetic type and the "zero layer". No app
 * grid: the screen says the one thing that matters now in huge type, an
 * assistant offers the next step, and everything else rolls by in one line
 * at the foot. Mockup: D:/dashwheel-design/v16-trends/index.html, section 3.
 *
 * Typography is the interface: the distance to the turn in a very heavy face
 * squeezed narrow that widens as the turn comes closer, painted in the skin's
 * one gradient (blue, violet, pink, amber) which drifts slowly while parked;
 * the speed as a wide outlined numeral; small labels in spaced capitals. The
 * page is near black (warm off-white by day, black type), with no boxes but
 * the assistant's card. The bar is the ticker (this file), the tiles are in
 * KineticSkinTiles.kt and the car strip in KineticSkinCar.kt.
 *
 * The K706 has no variable font and Android 10 none of its axes, so the
 * width is Roboto Black drawn narrower (a horizontal scale), the outlined
 * speed Roboto Light stroked, and the labels Roboto Condensed.
 */

internal val KineticPalette = DashPalette(
    Background = Color(0xFF06070B), Bar = Color.Transparent, Card = Color(0xFF111218), CardHi = Color(0x14FFFFFF),
    Accent = Color(0xFFB48CFF), Secondary = Color(0xFF7AB8FF), Critical = Color(0xFFFF5A5F), Good = Color(0xFF52E3A4),
    Muted = Color(0xFF8D93A3), TextPrimary = Color(0xFFF4F2EE), TextSecondary = Color(0xFFC9CCD6),
    Accent2 = Color(0xFFFFC46B), Line = Color(0x1AFFFFFF), Glow = 0.4f, Bare = true,
    BackgroundStops = listOf(Color(0xFF0A0B11), Color(0xFF06070B), Color(0xFF040408)),
    Font = DashFont.CONDENSED,
    HeroWeight = FontWeight.ExtraBold,
    Skin = DashSkin.KINETIC
)

internal val KineticLightPalette = DashPalette(
    Background = Color(0xFFF4F2EE), Bar = Color.Transparent, Card = Color(0xFFFFFFFF), CardHi = Color(0x10000000),
    Accent = Color(0xFF6A3FD0), Secondary = Color(0xFF2F6FC0), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF5F6474), TextPrimary = Color(0xFF06070B), TextSecondary = Color(0xFF34384A),
    Accent2 = Color(0xFFB86E0E), Line = Color(0x1F000000), Glow = 0f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFF8F6F2), Color(0xFFF4F2EE), Color(0xFFECE9E2)),
    Font = DashFont.CONDENSED,
    HeroWeight = FontWeight.ExtraBold,
    Skin = DashSkin.KINETIC
)

/** The skin's corners (dialogs, menus, tiles being arranged): the assistant card's big radius. */
internal val KineticShapes = skinShapes(26, 24, 23)

/**
 * The Home Kinetic was drawn around (SkinLayouts.kt): the next turn as the
 * hero over most of the page, the speed in the top right corner, the
 * assistant's suggestion under it and the car strip along the foot.
 *
 * ```
 *   0 1 2 3 4 5 6 7 8 9 10 11
 * 0 N N N N N N N N S S S  S
 * 1 N N N N N N N N S S S  S
 * 2 N N N N N N N N S S S  S
 * 3 N N N N N N N N G G G  G
 * 4 N N N N N N N N G G G  G
 * 5 R R R R R R R R R R R  R
 * 6 R R R R R R R R R R R  R
 * ```
 */
internal val KineticLayout = SkinLayout(
    home = TemplatePage(listOf(BuiltinKind.NAVIGATION, BuiltinKind.SPEED_HUD, BuiltinKind.NOW, BuiltinKind.RANGE, BuiltinKind.MEDIA)),
    wide = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 0, y = 0, w = 8, h = 5),
        DashboardItem.BuiltinWidget(BuiltinKind.SPEED_HUD, x = 8, y = 0, w = 4, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.NOW, x = 8, y = 3, w = 4, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.RANGE, x = 0, y = 5, w = 12, h = 2)
    )
)

// --- Type ---------------------------------------------------------------------------

/** The skin's faces: the heavy hero, the condensed cuts of words and labels, the wide light and the outlined thin. */
internal enum class KnFace { HERO, COND, COND_MEDIUM, COND_BOLD, WIDE_LIGHT, OUTLINE }

private fun knCondensed(weight: Int): FontFamily =
    FontFamily(robotoWeight(weight, condensed = true))

// Lazy: made on first use, never while the palettes above load (the JVM tests have no typefaces).
private val KnCond by lazy { knCondensed(400) }
private val KnCondMedium by lazy { knCondensed(500) }
internal val KnCondBold by lazy { knCondensed(700) }

/** Big figures centred in a line box trimmed to the glyphs, so huge numbers sit where they are put. */
private val FigureLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** How narrow the hero face is drawn when the turn is far, and how wide when it is here. */
internal const val KN_SQUEEZE_FAR = 0.56f
internal const val KN_SQUEEZE_NEAR = 0.9f

/** How wide the outlined speed is drawn: the mockup's wide light cut. */
private const val OUTLINE_WIDTH = 1.22f

/**
 * The skin's type at [size] dp (whatever the system font scale). [caps]
 * spaces the letters out as the small labels; [squeeze] draws the face
 * narrower (below 1) or wider. The hero is Roboto Black, tight; the outline
 * face is Roboto Light, wide, for a stroked numeral.
 */
@Composable
internal fun knText(
    size: Float,
    color: Color = DashColors.TextPrimary,
    face: KnFace = KnFace.COND,
    caps: Boolean = false,
    squeeze: Float = 1f
): TextStyle {
    val figure = face == KnFace.HERO || face == KnFace.OUTLINE
    val scaleX = when (face) {
        KnFace.OUTLINE -> OUTLINE_WIDTH * squeeze
        KnFace.WIDE_LIGHT -> 1.06f * squeeze
        else -> squeeze
    }
    return TextStyle(
        color = color,
        fontFamily = when (face) {
            KnFace.HERO, KnFace.WIDE_LIGHT, KnFace.OUTLINE -> FontFamily.SansSerif
            KnFace.COND -> KnCond
            KnFace.COND_MEDIUM -> KnCondMedium
            KnFace.COND_BOLD -> KnCondBold
        },
        fontWeight = when (face) {
            KnFace.HERO -> FontWeight.Black
            KnFace.WIDE_LIGHT, KnFace.OUTLINE -> FontWeight.Light
            else -> null
        },
        fontSize = fixedSp(size),
        fontFeatureSettings = "tnum",
        lineHeight = if (figure) 1.0.em else 1.18.em,
        lineHeightStyle = if (figure) FigureLines else null,
        letterSpacing = when {
            caps -> 0.2.em
            face == KnFace.HERO -> (-0.02).em
            face == KnFace.WIDE_LIGHT -> 0.01.em
            else -> 0.em
        },
        textGeometricTransform = if (scaleX != 1f) TextGeometricTransform(scaleX = scaleX) else null
    )
}

/** One run of the skin's text: a single line with an ellipsis unless [maxLines] says otherwise; [caps] upper-cases it. */
@Composable
internal fun KnLine(
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

/** A small label in spaced capitals ("METRES", "KM/H · LIMIT 50"), in the muted ink unless [color] says otherwise. */
@Composable
internal fun KnLabel(text: String, modifier: Modifier = Modifier, size: Float = 15f, color: Color = DashColors.Muted, align: TextAlign? = null) {
    KnLine(text, knText(size.coerceAtLeast(MIN_CAPS_DP), color, KnFace.COND_MEDIUM, caps = true), modifier, align = align, caps = true)
}

/**
 * Largest size (dp, for [knText]) within [minSize]..[maxSize] at which
 * [sample] in [face] fits [maxW] x [maxH] on one line. Measured once at 100
 * and scaled, since type grows linearly with its size.
 */
@Composable
internal fun knFit(sample: String, face: KnFace, maxW: Dp, maxH: Dp, minSize: Float, maxSize: Float, squeeze: Float = 1f): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = knText(100f, face = face, squeeze = squeeze)
    return remember(sample, style, maxW, maxH, minSize, maxSize, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = (100f / density.fontScale).sp), softWrap = false, maxLines = 1)
        val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
        val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
        min(byW, byH).coerceIn(minSize, maxSize.coerceAtLeast(minSize))
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
internal fun knTemplate(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

// --- The gradient ---------------------------------------------------------------------

/** The skin's one gradient, used on the hero number alone: blue, violet, pink, amber and back to blue, so it repeats seamlessly. */
private val NightGradient = listOf(Color(0xFF7AB8FF), Color(0xFFB48CFF), Color(0xFFFF8FB1), Color(0xFFFFC46B), Color(0xFF7AB8FF))
private val NightStops = floatArrayOf(0f, 0.35f, 0.6f, 0.85f, 1f)

/** By day the same hues, deep enough to read on the off-white page in the sun. */
private val DayGradient = listOf(Color(0xFF1F5FBF), Color(0xFF6A3FD0), Color(0xFFC2306E), Color(0xFFB5650A), Color(0xFF1F5FBF))

internal fun knGradientColors(light: Boolean): List<Color> = if (light) DayGradient else NightGradient

/** One full turn of the hero's gradient while parked, in ms. */
private const val GRADIENT_TURN_MS = 12_000

/**
 * The hero number: [text] in the heavy face at [size] dp, drawn [squeeze]
 * wide (read only while drawing, so a change of width redraws alone), in the
 * skin's gradient laid twice its width as in the mockup. The gradient drifts
 * one turn in [GRADIENT_TURN_MS] while parked (ambient motion: still while
 * the car moves, with effects off and on a hidden page). The box is as wide
 * as the number at [maxSqueeze], so the words around it never move.
 */
@Composable
internal fun KnHeroNumber(text: String, size: Float, squeeze: State<Float>, maxSqueeze: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = knText(size, face = KnFace.HERO)
    val light = DashColors.Light
    val layout = remember(text, style, measurer) { measurer.measure(text, style, softWrap = false, maxLines = 1) }
    val density = LocalDensity.current
    val drift = if (DashColors.Effects != DashEffects.NONE) rememberLoop(GRADIENT_TURN_MS) else null
    val w = with(density) { (layout.size.width * maxSqueeze).toDp() }
    val h = with(density) { layout.size.height.toDp() }
    Spacer(
        modifier
            .size(w, h)
            .semantics { contentDescription = text }
            .graphicsLayer()
            .drawWithCache {
                val span = layout.size.width.toFloat().coerceAtLeast(1f) * 2f
                val colors = knGradientColors(light)
                val brush = Brush.horizontalGradient(
                    *NightStops.mapIndexed { i, s -> s to colors[i] }.toTypedArray(),
                    startX = 0f, endX = span, tileMode = TileMode.Repeated
                )
                onDrawBehind {
                    val dx = (drift?.value ?: 0f) * span
                    scale(squeeze.value, 1f, Offset.Zero) {
                        // The canvas moves one way and the text the other: the gradient slides under still letters.
                        translate(-dx, 0f) { drawText(layout, brush = brush, topLeft = Offset(dx, 0f)) }
                    }
                }
            }
    )
}

// --- Background -----------------------------------------------------------------------

/**
 * The page: near black, a little lighter at the top, with a faint violet
 * haze behind where the hero stands and a warm one at the far foot, for
 * depth (none with effects off). By day warm off-white and the same hazes,
 * fainter. Nothing moves.
 */
@Composable
internal fun kineticBackground(): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val stops = DashColors.BackgroundStops
    return remember(light, effects, stops) { knBackdrop(light, effects, stops) }
}

private fun knBackdrop(light: Boolean, effects: DashEffects, stops: List<Color>): Modifier = Modifier.drawWithCache {
    val w = size.width
    val h = size.height
    val base = Brush.verticalGradient(stops)
    val k = effects.scale * if (light) 0.5f else 1f
    val violet = Brush.radialGradient(
        listOf(Color(0xFFB48CFF).copy(alpha = 0.10f * k), Color.Transparent),
        Offset(w * 0.18f, h * 0.22f), w * 0.42f
    )
    val warm = Brush.radialGradient(
        listOf(Color(0xFFFFC46B).copy(alpha = 0.05f * k), Color.Transparent),
        Offset(w * 0.86f, h * 1.05f), w * 0.38f
    )
    onDrawBehind {
        drawRect(base)
        if (k > 0f) {
            drawRect(violet)
            drawRect(warm)
        }
    }
}

// --- Bar: the ticker -------------------------------------------------------------------

/** The bar's height: one line of type and its text buttons, the size a finger finds without looking. */
private val KN_BAR = 64.dp

/** How fast the ticker rolls while parked, in dp a second. */
private const val TICKER_DP_S = 38f

/** How long each step of the ticker holds while the car moves. */
private const val TICKER_STEP_MS = 4_500L

/** Between two items of the ticker. */
private const val TICKER_GAP = "   /   "

/**
 * The bar: the ticker. A hairline over the foot of the screen, Apps as a
 * word on the left, then the time (unless the head unit's bar or a clock tile
 * shows it) and the dashboard's position, then one line with the rest of
 * what there is to know, separated by slashes: the song, the arrival, the
 * coolant, the range, the weather, rain to come, the next appointment, the
 * sunset. Parked, it rolls by smoothly; while the car moves it never rolls:
 * it steps to the next item every few seconds. At the end, the bar's own
 * buttons as words: the OBD link with its dot, Talk, Layout and Menu.
 */
@Composable
internal fun KineticTopBar(m: TopBarModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(KN_BAR)
            .graphicsLayer()
            .knHairlineTop()
            .padding(horizontal = 6.dp)
    ) {
        val narrow = maxWidth < NARROW_BAR
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            KnKey(stringResource(R.string.dash_apps), stringResource(R.string.dash_all_apps), m.onApps)
            val showClock = !m.merged && !ClockInSight.on.value
            if (showClock) {
                KnLine(m.clock, knText(20f, DashColors.TextPrimary, KnFace.COND_BOLD), Modifier.padding(start = 6.dp), overflow = TextOverflow.Clip)
            }
            if (!narrow) PagePositionChip(Modifier.padding(start = 10.dp))
            // The ticker and the badges share the middle: the badges take what they need, the
            // ticker the rest, and the buttons at the end keep their room whatever happens here.
            Row(Modifier.weight(1f).fillMaxHeight().clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
                KnTicker(m, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp))
                if (narrow) PagePositionChip(Modifier.padding(end = 6.dp))
                VehicleAlerts(m.obdConnection, m.obd)
                PhonePill(Modifier.padding(end = 6.dp))
                if (m.setupPending) SetupPill(onClick = { m.onSetup(false) }, modifier = Modifier.padding(end = 6.dp), compact = true)
                if (m.demo) DemoBadge(onStop = m.onDemo, modifier = Modifier.padding(end = 6.dp), compact = true)
            }
            KnObdKey(m.obdConnection, m.onConnectObd)
            KnTalkKey()
            LayoutPicker(m) { open ->
                KnKey(stringResource(R.string.dash_layout_button), stringResource(R.string.dash_screen_layout, m.layout.title), open)
            }
            MorePicker(m, geminiInBar = true) { open ->
                KnKey(stringResource(R.string.kinetic_menu), stringResource(R.string.dash_more), open)
            }
        }
    }
}

/** The hairline along the top of a box: the ticker's edge. */
private fun Modifier.knHairlineTop(): Modifier = drawWithCache {
    val ink = DashColors.Line
    onDrawBehind { drawLine(ink, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f) }
}

/**
 * A button of the bar as a word: spaced capitals, [DashSize.Bar] tall and at
 * least 56 dp wide, the tap feedback on a soft rounded patch. [ink] colours
 * the word; [lead] draws before it (the OBD dot).
 */
@Composable
private fun KnKey(text: String, description: String, onClick: () -> Unit, ink: Color = DashColors.TextPrimary, lead: (@Composable () -> Unit)? = null) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.Bar)
            .widthIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        lead?.invoke()
        KnLine(text, knText(16f, ink, KnFace.COND_BOLD, caps = true), caps = true)
    }
}

/** The OBD link as a word with its dot: lit when live, hollow when off, pulsing while it dials, a "!" on error. */
@Composable
private fun KnObdKey(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = obdStatusColor(state)
    val off = state == ObdConnectionState.DISCONNECTED
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    ObdMark(state, onConnect, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.height(DashSize.Bar).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(10.dp)
                    .drawWithCache {
                        val r = size.minDimension / 2f
                        val ring = Stroke(1.5f * density)
                        onDrawBehind {
                            if (off) drawCircle(DashColors.Muted, r - 0.75f * density, style = ring)
                            else drawCircle(color, r, alpha = pulse?.let { 0.3f + 0.7f * it.value } ?: 1f)
                        }
                    }
            )
            Spacer(Modifier.width(8.dp))
            KnLine(
                stringResource(R.string.dash_obd_short) + if (state == ObdConnectionState.ERROR) " !" else "",
                knText(16f, if (off) DashColors.TextSecondary else DashColors.TextPrimary, KnFace.COND_BOLD, caps = true)
            )
        }
    }
}

/**
 * Talk as a word in the accent's ink: Gemini Live (Dashwheel's own or
 * Google's) when there is one, else the unit's voice assistant. While a
 * conversation is open it reads End in red and breathes, since it then tells
 * the driver Gemini is listening.
 */
@Composable
private fun KnTalkKey() {
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    val word = stringResource(if (active) R.string.ai_gemini_live_end else R.string.kinetic_talk)
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.kinetic_talk_cd)
    val pulse = if (active) rememberLoop(1_400, reverse = true, status = true) else null
    Box(Modifier.graphicsLayer { alpha = pulse?.let { 0.55f + 0.45f * it.value } ?: 1f }) {
        KnKey(word, description, { if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context) },
            ink = if (active) DashColors.Critical else DashColors.AccentInk)
    }
}

/** What the ticker says, item by item: only what Dashwheel knows now, nothing made up. */
@Composable
private fun rememberTickerItems(m: TopBarModel): List<String> {
    val context = LocalContext.current
    val units = LocalUnits.current
    val media by remember { CarMediaController.shared(context).mediaState }.collectAsState()
    val nav by NavDirections.state.collectAsState()
    val coolant by remember(m) { derivedStateOf { if (m.obdConnection == ObdConnectionState.CONNECTED) m.obdData.coolantTempC else 0 } }
    val fuel = rememberFuel(m.obd, m.obdConnection)
    val weather = rememberWeather()
    val tempC = rememberOutsideTempC()
    val agenda = rememberAgendaSource()
    val sun = rememberSunTimes()
    val now = rememberWallClock(60_000L).longValue
    val arrivalWord = stringResource(R.string.kinetic_arrival, "%s")
    val coolantWord = stringResource(R.string.kinetic_ticker_coolant, "%s")
    val rangeWord = stringResource(R.string.kinetic_ticker_range, "%s")
    val sunsetWord = stringResource(R.string.kinetic_sunset, "%s")
    val sunriseWord = stringResource(R.string.kinetic_sunrise, "%s")
    val rainFrom = stringResource(R.string.canvas_rain_from, "%s")
    val rainUntil = stringResource(R.string.canvas_rain_until, "%s")
    return remember(media.title, media.artist, media.hasMedia, nav.active, nav.eta, coolant, fuel?.rangeKm, weather, tempC, agenda.events, sun, now / 60_000L, units) {
        val out = ArrayList<String>(8)
        if (media.hasMedia && media.title.isNotBlank()) out += listOf(media.title, media.artist).filter { it.isNotBlank() }.joinToString(" · ")
        if (nav.active && nav.eta.isNotBlank()) out += knEtaLine(nav, arrivalWord)
        if (coolant > 0) out += coolantWord.replace("%s", "${units.temp(coolant)}°")
        fuel?.rangeKm?.takeIf { it > 0 }?.let { out += rangeWord.replace("%s", units.distanceText(it)) }
        if (tempC != null) out += listOfNotNull("${units.temp(tempC)}°", weather?.let { context.getString(it.conditionRes) }).joinToString(" ")
        weather?.let { w ->
            when {
                w.rainFromMs != null && w.rainUntilMs != null && w.rainFromMs <= now -> out += rainUntil.replace("%s", units.time(Date(w.rainUntilMs)))
                w.rainFromMs != null -> out += rainFrom.replace("%s", units.time(Date(w.rainFromMs)))
            }
        }
        agenda.events.firstOrNull { !it.allDay && it.begin > now && it.begin - now < 12 * 3_600_000L }?.let { e ->
            if (e.title.isNotBlank()) out += "${e.title} ${units.time(Date(e.begin))}"
        }
        sun?.let { (rise, set) ->
            when {
                now < rise -> out += sunriseWord.replace("%s", units.time(Date(rise)))
                now < set -> out += sunsetWord.replace("%s", units.time(Date(set)))
            }
        }
        out
    }
}

/** The navigation's ETA as one line, its arrival time said as such: "Arrival 21:24 · 11 km · 17 min". */
internal fun knEtaLine(nav: NavState, arrivalWord: String): String {
    val parts = nav.etaParts
    val arrival = parts.firstOrNull { KN_CLOCK.matches(it) }
    val rest = parts.filter { it != arrival }
    return (listOfNotNull(arrival?.let { arrivalWord.replace("%s", it) }) + rest).joinToString(" · ")
}

/** A part of the ETA that is a time of day ("21:24", "9:24 PM"). */
internal val KN_CLOCK = Regex("""^\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")

/**
 * The ticker's line in [modifier]'s box. All of it fits: it stands still.
 * Parked (effects on): it rolls by smoothly, round and round, its offset read
 * only while drawing. Moving, or effects off: it steps one item every few
 * seconds, a short slide upward between steps (none with effects off).
 */
@Composable
private fun KnTicker(m: TopBarModel, modifier: Modifier) {
    val items = rememberTickerItems(m)
    if (items.isEmpty()) {
        Spacer(modifier)
        return
    }
    val moving = LocalCarMoving.current || m.moving
    val effects = DashColors.Effects
    val style = knText(20f, DashColors.TextSecondary, KnFace.COND_MEDIUM)
    val slash = DashColors.Muted.copy(alpha = 0.7f)
    val line = remember(items, slash) { tickerText(items, 0, slash) }
    val measurer = rememberTextMeasurer()
    val cycle = remember(line, style, measurer) { measurer.measure(line, style, softWrap = false, maxLines = 1).size.width }
    val gap = remember(style, measurer) { measurer.measure(TICKER_GAP, style).size.width }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.CenterStart) {
        val room = constraints.maxWidth
        val fits = cycle - gap <= room
        when {
            fits -> KnLine(remember(items, slash) { tickerText(items, 0, slash, trailing = false) }.text, style)
            !moving && effects != DashEffects.NONE -> {
                val period = (cycle / (TICKER_DP_S * density.density) * 1000f).toInt().coerceAtLeast(4_000)
                val roll = rememberLoop(period)
                val copies = ceil(room.toFloat() / cycle.coerceAtLeast(1)).toInt() + 1
                Row(
                    Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .graphicsLayer { translationX = -roll.value * cycle }
                ) {
                    repeat(copies) { Text(line, style = style, maxLines = 1, softWrap = false) }
                }
            }
            else -> KnTickerSteps(items, style, slash, animate = effects != DashEffects.NONE)
        }
    }
}

/** The ticker one step at a time: from item [n] on, as many as fit, the next item every [TICKER_STEP_MS]. */
@Composable
private fun KnTickerSteps(items: List<String>, style: TextStyle, slash: Color, animate: Boolean) {
    val step = remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(items.size)
    val active = LocalPageActive.current
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(TICKER_STEP_MS)
            step.intValue = (step.intValue + 1) % latest.coerceAtLeast(1)
        }
    }
    val n = step.intValue % items.size
    if (!animate) {
        Text(tickerText(items, n, slash), style = style, maxLines = 1, softWrap = false)
        return
    }
    AnimatedContent(
        targetState = n,
        transitionSpec = {
            (slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(320))) togetherWith
                (slideOutVertically(tween(260)) { -it / 2 } + fadeOut(tween(200)))
        },
        label = "ticker"
    ) { i ->
        Text(tickerText(items, i % items.size, slash), style = style, maxLines = 1, softWrap = false)
    }
}

/** [items] from [from] on, round the end, each followed by a slash in [slash] ink (the last one too unless [trailing] is false). */
private fun tickerText(items: List<String>, from: Int, slash: Color, trailing: Boolean = true): AnnotatedString = buildAnnotatedString {
    for (k in items.indices) {
        append(items[(from + k) % items.size])
        if (trailing || k < items.lastIndex) withStyle(SpanStyle(color = slash)) { append(TICKER_GAP) }
    }
}

// --- Sun ----------------------------------------------------------------------------------

/** Sunrise and sunset where the car was last seen, today, in wall-clock ms; null without a place or on a day the sun never crosses. */
@Composable
private fun rememberSunTimes(): Pair<Long, Long>? {
    val context = LocalContext.current
    AutoLight.start(context)
    val place by AutoLight.place.collectAsState()
    val day = rememberWallClock(3_600_000L).longValue / 3_600_000L
    return remember(place?.first?.let { (it * 10).toInt() }, place?.second?.let { (it * 10).toInt() }, day) {
        place?.let { (lat, lon) -> sunTimes(day * 3_600_000L, lat, lon) }
    }
}

/**
 * The day of [timeMs]'s sunrise and sunset at ([lat], [lon]), when the sun's
 * centre crosses 0.833 degrees under the horizon (refraction and its radius),
 * found to two minutes; null on a day it stays up or down.
 */
internal fun sunTimes(timeMs: Long, lat: Double, lon: Double, zone: TimeZone = TimeZone.getDefault()): Pair<Long, Long>? {
    val midnight = Calendar.getInstance(zone).apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    var rise: Long? = null
    var set: Long? = null
    var wasUp = sunAltitudeDeg(midnight, lat, lon) > SUN_EDGE_DEG
    var m = 2
    while (m < 24 * 60) {
        val t = midnight + m * 60_000L
        val up = sunAltitudeDeg(t, lat, lon) > SUN_EDGE_DEG
        if (up && !wasUp && rise == null) rise = t
        if (!up && wasUp && set == null) set = t
        wasUp = up
        m += 2
    }
    return if (rise != null && set != null) rise to set else null
}

private const val SUN_EDGE_DEG = -0.833

// --- Window frame -------------------------------------------------------------------------

/**
 * The frame over a docked app window: its corners rounded off in the page's
 * colour, a hairline round it and the time in a small tab of the page at its
 * top left corner, heavy and narrow like the rest of the skin's figures. The
 * middle stays clear and nothing takes touches.
 */
@Composable
internal fun KineticWindowFrame(modifier: Modifier) {
    val page = DashColors.Background
    val rim = DashColors.Line
    val units = LocalUnits.current
    val now = rememberNow(60_000L)
    val fmt = rememberDateFormat(units.digitsPattern())
    Box(modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val r = 24.dp.toPx()
                    val mask = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, w, h))
                        addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                    }
                    val stroke = Stroke(1.dp.toPx())
                    val half = stroke.width / 2f
                    onDrawBehind {
                        drawPath(mask, page)
                        drawRoundRect(rim, Offset(half, half), Size(w - 2f * half, h - 2f * half), CornerRadius(r - half), style = stroke)
                    }
                }
        )
        KnLine(
            fmt.format(now),
            knText(22f, DashColors.TextPrimary, KnFace.HERO, squeeze = 0.74f),
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 12.dp)
                .background(page, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            overflow = TextOverflow.Clip
        )
    }
}

// --- Fallback panel -----------------------------------------------------------------------

/**
 * What stands behind a widget Kinetic does not redraw, as the assistant's
 * card of the mockup: a 5 % white veil (a faint ink by day), a hairline and
 * the big radius. Effects off: the palette's solid card.
 */
@Composable
internal fun Modifier.kineticFallbackPanel(): Modifier {
    val light = DashColors.Light
    val plain = DashColors.Effects == DashEffects.NONE
    val shape = skinChrome().shapes.medium
    val fill = when {
        plain -> DashColors.Card
        light -> Color.Black.copy(alpha = 0.035f)
        else -> Color.White.copy(alpha = 0.05f)
    }
    val rim = if (light) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.1f)
    return clip(shape).background(fill, shape).border(1.dp, rim, shape)
}

// --- Theme stamp --------------------------------------------------------------------------

/**
 * The theme card's small picture (ThemePane): the gradient "300" big on the
 * left with a label line under it, the outlined speed in the top right, the
 * suggestion card under it and the ticker's hairline and dashes at the foot.
 */
internal fun DrawScope.kineticStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    // A faint violet haze behind the hero, as on the page.
    if (!light) drawCircle(Brush.radialGradient(listOf(Color(0x29B48CFF), Color.Transparent), Offset(w * 0.25f, h * 0.35f), w * 0.3f), w * 0.3f, Offset(w * 0.25f, h * 0.35f))
    // The hero: drawn in white, then the gradient laid on its pixels only.
    drawIntoCanvas { canvas ->
        val area = Rect(0f, 0f, w * 0.62f, h * 0.72f)
        canvas.saveLayer(area, Paint())
        number("300", w * 0.27f, h * 0.36f, 26f, Color.White)
        val colors = knGradientColors(light)
        drawRect(Brush.horizontalGradient(colors, area.left + w * 0.04f, area.left + w * 0.9f), area.topLeft, area.size, blendMode = BlendMode.SrcIn)
        canvas.restore()
    }
    drawLine(p.Muted, Offset(w * 0.07f, h * 0.66f), Offset(w * 0.24f, h * 0.66f), h * 0.025f)
    drawLine(p.TextPrimary, Offset(w * 0.07f, h * 0.76f), Offset(w * 0.44f, h * 0.76f), h * 0.05f)
    // The speed, outlined: the figure grown by a pixel each way, its own shape cut out of it.
    drawIntoCanvas { canvas ->
        val area = Rect(w * 0.62f, 0f, w, h * 0.42f)
        canvas.saveLayer(area, Paint())
        val c = Offset(w * 0.8f, h * 0.2f)
        for (d in listOf(Offset(-1f, 0f), Offset(1f, 0f), Offset(0f, -1f), Offset(0f, 1f))) number("48", c.x + d.x, c.y + d.y, 15f, p.TextPrimary)
        canvas.saveLayer(area, Paint().apply { blendMode = BlendMode.DstOut })
        number("48", c.x, c.y, 15f, Color.Black)
        canvas.restore()
        canvas.restore()
    }
    // The suggestion card.
    val card = Rect(w * 0.6f, h * 0.44f, w * 0.95f, h * 0.7f)
    drawRoundRect(if (light) Color.Black.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.06f), card.topLeft, card.size, CornerRadius(h * 0.06f))
    drawRoundRect(if (light) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.14f), card.topLeft, card.size, CornerRadius(h * 0.06f), style = Stroke(1f))
    drawLine(p.Accent, Offset(card.left + w * 0.03f, card.top + h * 0.07f), Offset(card.left + w * 0.1f, card.top + h * 0.07f), h * 0.02f)
    drawLine(p.TextSecondary, Offset(card.left + w * 0.03f, card.top + h * 0.14f), Offset(card.right - w * 0.04f, card.top + h * 0.14f), h * 0.025f)
    drawRoundRect(p.TextPrimary, Offset(card.left + w * 0.03f, card.top + h * 0.18f), Size(w * 0.09f, h * 0.05f), CornerRadius(h * 0.025f))
    // The ticker.
    drawLine(p.Line, Offset(0f, h * 0.86f), Offset(w, h * 0.86f), 1f)
    var x = w * 0.05f
    for (len in floatArrayOf(0.16f, 0.12f, 0.18f, 0.1f)) {
        drawLine(p.TextSecondary.copy(alpha = 0.7f), Offset(x, h * 0.93f), Offset(x + w * len, h * 0.93f), h * 0.025f)
        x += w * (len + 0.06f)
    }
}
