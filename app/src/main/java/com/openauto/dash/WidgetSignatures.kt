package com.openauto.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.openauto.dash.carphoto.CarPart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The widget-specific designs: a picture of what the widget measures rather
 * than a layout of numbers. A temperature is a thermometer, fuel a tank, the
 * doors a car seen from above, a song a turning record. Each is drawn from
 * the same live WidgetFace as the generic designs, in the theme's colours.
 *
 * Drawings are written in a small fixed coordinate box (Vb), scaled to fit
 * the tile the way an SVG viewBox is, so the shapes keep their proportions
 * on every tile size.
 */

private val LampRed = Color(0xFFFF453A)
private val LampAmber = Color(0xFFFFB020)
private val LampGreen = Color(0xFF34C759)
private val SignBlue = Color(0xFF1565C0)
private val SignRed = Color(0xFFD32F2F)
private val SignGreen = Color(0xFF0B7A3E)
private val DialFace = Color(0xFF0D0F12)
private val Sand = Color(0xFFF4B942)
private val Condensed = CondensedFamily
/** The radar face's range rings, in view-box units, and their hairline. */
private val RADAR_RINGS = floatArrayOf(15f, 30f, 45f)
private val HairlineStroke = Stroke(1f)

private fun levelColor(level: Int) = when {
    level >= 2 -> LampRed
    level == 1 -> LampAmber
    else -> LampGreen
}

/** 0..1, with a missing or NaN reading as 0 (see [fraction01]). */
private fun frac(v: Float?) = fraction01(v)

/**
 * The fixed outlines the drawings reuse, in their Vb units. Built once rather
 * than on every draw: a turning record or a sweeping radar redraws each frame.
 */
private object Shapes {
    val fuelTank = Path().apply {
        val top = 16f
        val bot = 92f
        moveTo(8f, top + 8f); quadraticBezierTo(8f, top, 16f, top); lineTo(46f, top); quadraticBezierTo(54f, top, 54f, top + 8f)
        lineTo(54f, bot - 6f); quadraticBezierTo(54f, bot, 48f, bot); lineTo(14f, bot); quadraticBezierTo(8f, bot, 8f, bot - 6f); close()
    }
    val speedBox = Path().apply { moveTo(1f, 39f); lineTo(45f, 39f); lineTo(55f, 50f); lineTo(45f, 61f); lineTo(1f, 61f); close() }
    val signArrow = Path().apply {
        moveTo(26f, 78f); lineTo(26f, 50f); quadraticBezierTo(26f, 40f, 36f, 40f); lineTo(52f, 40f); lineTo(52f, 28f)
        lineTo(72f, 46f); lineTo(52f, 64f); lineTo(52f, 52f); lineTo(40f, 52f); lineTo(40f, 78f); close()
    }
    val restCup = Path().apply {
        moveTo(27f, 38f); lineTo(71f, 38f); lineTo(71f, 58f); quadraticBezierTo(71f, 76f, 53f, 76f)
        lineTo(45f, 76f); quadraticBezierTo(27f, 76f, 27f, 58f); close()
    }
    val restSteam = listOf(40f, 52f).map { x -> Path().apply { moveTo(x, 20f); quadraticBezierTo(x - 5f, 26f, x, 32f) } }
    val headingMark = Path().apply { moveTo(100f, 38f); lineTo(95f, 45f); lineTo(105f, 45f); close() }
    val roseNeedle = Path().apply { moveTo(50f, 30f); lineTo(54f, 50f); lineTo(50f, 70f); lineTo(46f, 50f); close() }
    val roseToCar = Path().apply {
        moveTo(50f, 12f); lineTo(60f, 32f); lineTo(54f, 32f); lineTo(54f, 62f); lineTo(46f, 62f); lineTo(46f, 32f); lineTo(40f, 32f); close()
    }
    val roseTop = Path().apply { moveTo(50f, 0.5f); lineTo(45f, 8.5f); lineTo(55f, 8.5f); close() }
    val pointerArrow = Path().apply { moveTo(50f, 13f); lineTo(76f, 58f); lineTo(59f, 58f); lineTo(59f, 84f); lineTo(41f, 84f); lineTo(41f, 58f); lineTo(24f, 58f); close() }
    /** G bars' direction marks: the triangle at each bar end and the angle it turns by. */
    val gBarMarks = listOf(Offset(3f, 50f) to 270f, Offset(97f, 50f) to 90f, Offset(50f, 3f) to 0f, Offset(50f, 97f) to 180f).map { (p, a) ->
        Triple(Path().apply { moveTo(p.x, p.y - 3f); lineTo(p.x - 3f, p.y + 2f); lineTo(p.x + 3f, p.y + 2f); close() }, p, a)
    }
    val turnArrow = Path().apply {
        moveTo(20f, 80f); lineTo(20f, 52f); quadraticBezierTo(20f, 42f, 30f, 42f); lineTo(38f, 42f); lineTo(38f, 29f)
        lineTo(56f, 47f); lineTo(38f, 65f); lineTo(38f, 54f); lineTo(32f, 54f); lineTo(32f, 80f); close()
    }
    /** Half the road's width at height [y] (it narrows towards the horizon). */
    fun roadHalf(y: Float) = 3f + (y - 12f) / (96f - 12f) * 72f
    val road = Path().apply { moveTo(100f - roadHalf(12f), 12f); lineTo(100f + roadHalf(12f), 12f); lineTo(100f + roadHalf(96f), 96f); lineTo(100f - roadHalf(96f), 96f); close() }
    /** The destination flag, its pole's foot at the origin. */
    val flag = Path().apply { moveTo(0f, -16f); lineTo(12f, -16f); lineTo(9f, -12f); lineTo(12f, -8f); lineTo(0f, -8f); close() }
    val carWindscreen = Path().apply { moveTo(35f, 40f); quadraticBezierTo(50f, 32f, 65f, 40f); lineTo(62f, 50f); lineTo(38f, 50f); close() }
    val carRearWindow = Path().apply { moveTo(37f, 94f); lineTo(63f, 94f); lineTo(65f, 104f); quadraticBezierTo(50f, 110f, 35f, 104f); close() }
    val hillBack = Path().apply { moveTo(-60f, 88f); quadraticBezierTo(40f, 70f, 80f, 84f); quadraticBezierTo(120f, 98f, 160f, 80f); quadraticBezierTo(200f, 62f, 260f, 78f); lineTo(260f, 170f); lineTo(-60f, 170f); close() }
    val hillFront = Path().apply { moveTo(-60f, 98f); quadraticBezierTo(60f, 86f, 120f, 96f); quadraticBezierTo(180f, 106f, 260f, 90f); lineTo(260f, 170f); lineTo(-60f, 170f); close() }
    val cassetteFoot = Path().apply { moveTo(36f, 96f); lineTo(44f, 76f); lineTo(116f, 76f); lineTo(124f, 96f); close() }
    val vinylLabel = Path().apply { addOval(androidx.compose.ui.geometry.Rect(33f, 33f, 67f, 67f)) }
    /** The filter's 20 cells, bottom row first (the order they fill with soot): centre and outline. */
    val filterCells = buildList {
        for (r in 3 downTo 0) for (c in 0 until 5) {
            val center = Offset(16f + c * 17f + (if (r % 2 == 1) 8.5f else 0f), 16f + r * 15f)
            add(center to Path().apply {
                for (i in 0 until 6) {
                    val a = PI.toFloat() / 3f * i + PI.toFloat() / 6f
                    val p = Offset(center.x + 9.2f * cos(a), center.y + 9.2f * sin(a))
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            })
        }
    }
    val hourglass = Path().apply {
        moveTo(13f, 10f); lineTo(47f, 10f); quadraticBezierTo(47f, 32f, 32f, 50f); quadraticBezierTo(47f, 68f, 47f, 90f)
        lineTo(13f, 90f); quadraticBezierTo(13f, 68f, 28f, 50f); quadraticBezierTo(13f, 32f, 13f, 10f); close()
    }
    val leaf = Path().apply { moveTo(50f, 94f); cubicTo(12f, 72f, 8f, 32f, 50f, 6f); cubicTo(92f, 32f, 88f, 72f, 50f, 94f); close() }
}

/** [face] drawn in the widget-specific [design] (one whose [WidgetDesign.kinds] is set). */
@Composable
internal fun SignatureFace(face: WidgetFace, design: WidgetDesign, modifier: Modifier = Modifier) {
    val look = LocalThemeFaceLook.current ?: faceLook(FaceLookKind.THEME)
    FaceSurface(look, modifier.then(face.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The same one for as long as the tile keeps its size: made anew at
            // every reading, each part of the face took it for a change and was
            // drawn again, and the pictures "built once per size" were rebuilt.
            val density = LocalDensity.current
            val m = remember(maxWidth, maxHeight, density) { FaceMetrics(maxWidth.value, maxHeight.value, density) }
            val f = face
            when (design) {
                WidgetDesign.THERMOMETER -> Thermometer(f, look, m)
                WidgetDesign.FUEL_TANK -> FuelTank(f, look, m)
                WidgetDesign.BATTERY_CELL -> BatteryCell(f, look, m)
                WidgetDesign.FADER -> Fader(f, look, m)
                WidgetDesign.SPEED_TAPE -> SpeedTape(f, look, m)
                WidgetDesign.ROAD_SIGN -> RoadSign(f, look, m)
                WidgetDesign.TWIN_DIALS -> TwinDials(f, look, m)
                WidgetDesign.SHIFT_LIGHTS -> ShiftLights(f, look, m)
                WidgetDesign.HEADING_TAPE -> HeadingTape(f, look, m)
                WidgetDesign.COMPASS_ROSE -> CompassRose(f, look, m)
                WidgetDesign.POINTER -> Pointer(f, look, m)
                WidgetDesign.FRICTION_CIRCLE -> FrictionCircle(f, look, m)
                WidgetDesign.G_BARS -> GBars(f, look, m)
                WidgetDesign.SPIRIT_LEVEL -> SpiritLevel(f, look, m)
                WidgetDesign.TURN_CARD -> TurnCard(f, look, m)
                WidgetDesign.ROAD_AHEAD -> RoadAhead(f, look, m)
                WidgetDesign.RADAR -> Radar(f, look, m)
                WidgetDesign.ODOMETER -> Odometer(f, look, m)
                WidgetDesign.PRINTOUT -> Printout(f, look, m)
                WidgetDesign.WARNING_LAMP -> WarningLamp(f, look, m)
                WidgetDesign.TRAFFIC_LIGHT -> TrafficLight(f, look, m)
                WidgetDesign.GAUGE_BANK -> GaugeBank(f, look, m)
                WidgetDesign.CAR_TOP -> CarTop(f, look, m)
                WidgetDesign.DATA_RAIN -> DataRain(f, look, m)
                WidgetDesign.SKY -> SkyScene(f, look, m)
                WidgetDesign.SUN_PATH -> SunPath(f, look, m)
                WidgetDesign.BINARY_CLOCK -> BinaryClock(f, look, m)
                WidgetDesign.TIMELINE -> Timeline(f, look, m)
                WidgetDesign.DESK_CALENDAR -> DeskCalendar(f, look, m)
                WidgetDesign.CARD_STACK -> CardStack(f, look, m)
                WidgetDesign.FACES -> Faces(f, look, m)
                WidgetDesign.BADGE -> Badge(f, look, m)
                WidgetDesign.VINYL -> Vinyl(f, look, m)
                WidgetDesign.CASSETTE -> Cassette(f, look, m)
                WidgetDesign.COVER_ART -> CoverArt(f, look, m)
                WidgetDesign.VOLUME_KNOB -> VolumeKnob(f, look, m)
                WidgetDesign.LEVEL_METER -> LevelMeter(f, look, m)
                WidgetDesign.ELEVATION -> Elevation(f, look, m)
                WidgetDesign.FILTER_CELLS -> FilterCells(f, look, m)
                WidgetDesign.HOURGLASS -> Hourglass(f, look, m)
                WidgetDesign.LEAF -> Leaf(f, look, m)
                WidgetDesign.TYRE_MAP -> TyreMap(f, look, m)
                WidgetDesign.CAR_OUTLINE -> CarOutline(f, look, m)
                WidgetDesign.GEAR_GATE -> GearGate(f, look, m)
                else -> Unit
            }
        }
    }
}

// --- Building blocks ------------------------------------------------------------------

/**
 * A drawing in a [w] x [h] coordinate box starting at ([x0], [y0]), scaled to
 * fit (or, with [slice], to fill) the canvas and centred, like an SVG viewBox.
 */
@Composable
private fun Vb(
    w: Float,
    h: Float,
    modifier: Modifier = Modifier.fillMaxSize(),
    x0: Float = 0f,
    y0: Float = 0f,
    slice: Boolean = false,
    content: DrawScope.(TextMeasurer) -> Unit
) {
    // Tapes and dials draw a dozen or more labels a frame; the default cache of 8
    // layouts would measure most of them again on every draw.
    val tm = rememberTextMeasurer(cacheSize = 48)
    Canvas(modifier = modifier) {
        val s = if (slice) max(size.width / w, size.height / h) else min(size.width / w, size.height / h)
        val dx = (size.width - w * s) / 2f - x0 * s
        val dy = (size.height - h * s) / 2f - y0 * s
        drawingScale = s
        withTransform({
            translate(dx, dy)
            scale(s, s, Offset.Zero)
        }) { content(tm) }
    }
}

/** Pixels per drawing unit of the drawing being drawn (set by the canvas before its content; drawing runs on one thread). */
private var drawingScale = 1f

/**
 * Text in drawing units: [size] tall, baseline at [y], anchored at [x] by [align] (0 start, 0.5 centre, 1 end).
 * Never smaller on screen than [MIN_TEXT_DP]: a small tile scales its drawing down, its words stay readable.
 */
private fun DrawScope.label(
    tm: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    size: Float,
    color: Color,
    align: Float = 0.5f,
    weight: FontWeight = FontWeight.Bold,
    family: FontFamily = FontFamily.SansSerif,
    italic: Boolean = false
) {
    if (text.isEmpty()) return
    val layout = tm.measure(
        text,
        TextStyle(
            color = color, fontSize = (max(size, MIN_TEXT_DP * density / drawingScale.coerceAtLeast(0.01f)) / (density * fontScale)).sp, fontWeight = weight,
            fontFamily = family, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
        ),
        softWrap = false,
        maxLines = 1
    )
    drawText(layout, topLeft = Offset(x - layout.size.width * align, y - layout.firstBaseline))
}

/** Arc along a circle, angles clockwise from 12 o'clock as in [polarPoint]. */
private fun DrawScope.arcDeg(color: Color, c: Offset, r: Float, fromDeg: Float, toDeg: Float, width: Float, cap: StrokeCap = StrokeCap.Round) {
    drawArc(color, fromDeg - 90f, toDeg - fromDeg, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width, cap = cap))
}

/** Picture on the left, title / value / caption on the right. [ratio] is the picture's width over height. */
@Composable
private fun Split(f: WidgetFace, look: FaceLook, m: FaceMetrics, ratio: Float, visual: @Composable () -> Unit) {
    val avail = (m.h - m.pad * 2f).coerceAtLeast(12f)
    val vw = min(avail * ratio, m.w * 0.46f)
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(5f))
    ) {
        Box(modifier = Modifier.size(vw.dp, (vw / ratio).dp)) { visual() }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(2.2f), Alignment.CenterVertically)) {
            FaceHeader(f, look, m)
            FaceValue(f, look, m, min(m.h * 0.20f, m.w * 0.105f))
            FaceCaption(f, look, m)
            // From a two-row tile up: the knob, the fader and the parking sign
            // are pictures, and these are the tile's only keys.
            if (m.h >= 140f) FaceActions(f, look, m, small = true)
        }
    }
}

/** Title on top, picture in the middle, [foot] (value and caption by default) underneath. */
@Composable
private fun Stack(
    f: WidgetFace,
    look: FaceLook,
    m: FaceMetrics,
    foot: (@Composable () -> Unit)? = null,
    header: Boolean = true,
    visual: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.2f))) {
        if (header) FaceHeader(f, look, m)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { visual() }
        if (foot != null) foot()
        else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(m.dp(3f))) {
            FaceValue(f, look, m, min(m.h * 0.18f, m.w * 0.12f), Modifier.weight(1f, fill = false))
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomEnd) { FaceCaption(f, look, m, align = TextAlign.End) }
            FootActions(f, look, m, beside = 2)
        }
    }
}

/**
 * What a picture keeps under it whatever it draws: the caption (why there is
 * no reading) and the tile's buttons (the one that fixes it, Scan, Clear),
 * as WidgetFaceData.kt promises. Nothing when the face has neither.
 */
@Composable
private fun FaceFoot(f: WidgetFace, look: FaceLook, m: FaceMetrics, caption: Boolean = true) {
    val text = caption && f.caption.isNotEmpty()
    if (!text && f.actions.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(3f))) {
        Box(Modifier.weight(1f)) {
            // Beside a button the line has the height for two: the reason is read whole.
            if (text) FaceText(f.caption, look, m.caption, color = if (f.alert) look.warn else look.dim, maxLines = if (f.actions.isEmpty()) 1 else 2)
        }
        FootActions(f, look, m, beside = if (text) 1 else 0)
    }
}

/** The footer's buttons at the driving size, as many as fit once [beside] button widths are left to the text, the main one kept. */
@Composable
private fun FootActions(f: WidgetFace, look: FaceLook, m: FaceMetrics, beside: Int) {
    val each = DashSize.TouchPrimary.value
    val gap = (m.u * 2.6f).coerceAtLeast(4f)
    val n = actionsThatFit(m.w - m.pad * 2f - beside * each, each, gap)
    FaceActions(f.copy(actions = keyActions(f.actions, n)), look, m, size = DashSize.TouchPrimary)
}

/** Hour of the day as a fraction (14.5 = 14:30), from the face's clock or now. */
@Composable
private fun hourOfDay(f: WidgetFace): Float {
    val c = f.clock
    if (c != null) return c.first + c.second / 60f
    val now = rememberNow(60_000L)
    val cal = remember(now) { Calendar.getInstance().apply { time = now } }
    return cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
}

// --- Temperature, fuel, battery, levels -------------------------------------------------------

@Composable
private fun Thermometer(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 0.6f) {
    val (lo, hi) = f.scale ?: ("" to "")
    Vb(60f, 100f) { tm ->
        val top = 8f
        val bot = 76f
        val y = bot - (bot - top) * frac(f.fraction)
        drawRoundRect(look.track, Offset(13f, 3f), Size(14f, 80f), CornerRadius(7f))
        drawRoundRect(look.dim, Offset(13f, 3f), Size(14f, 80f), CornerRadius(7f), style = Stroke(0.7f))
        drawCircle(look.track, 11.5f, Offset(20f, 86f))
        drawCircle(look.dim, 11.5f, Offset(20f, 86f), style = Stroke(0.7f))
        drawRoundRect(
            Brush.verticalGradient(0f to Color(0xFFFF5252), 0.45f to Color(0xFFFFD54F), 1f to Color(0xFF4FC3F7), startY = top, endY = bot),
            Offset(16f, y), Size(8f, 88f - y), CornerRadius(4f)
        )
        drawCircle(Color(0xFF4FC3F7), 8.5f, Offset(20f, 86f))
        drawCircle(Color.White.copy(alpha = 0.45f), 2.2f, Offset(17f, 83f))
        for (i in 0..10) {
            val yy = bot - (bot - top) * i / 10f
            drawLine(look.dim, Offset(29f, yy), Offset(if (i % 5 != 0) 32f else 35f, yy), strokeWidth = if (i % 5 != 0) 0.6f else 1f)
        }
        label(tm, hi, 38f, top + 3f, 7f, look.dim, align = 0f, weight = FontWeight.Normal)
        label(tm, lo, 38f, bot + 3f, 7f, look.dim, align = 0f, weight = FontWeight.Normal)
    }
}

@Composable
private fun FuelTank(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val pump = rememberVectorPainter(Icons.Filled.LocalGasStation)
    Split(f, look, m, 0.7f) {
        Vb(70f, 100f) { tm ->
            val top = 16f
            val bot = 92f
            val fr = frac(f.fraction)
            val lv = bot - (bot - top) * fr
            // An unknown level is not a low one: no red E while the tank waits for its reading.
            val low = !f.idle && fr < 0.15f
            val body = Shapes.fuelTank
            fun wave(y: Float) = Path().apply {
                moveTo(-4f, y); quadraticBezierTo(8f, y - 3f, 20f, y); quadraticBezierTo(32f, y + 3f, 44f, y); quadraticBezierTo(56f, y - 3f, 68f, y)
                lineTo(68f, 100f); lineTo(-4f, 100f); close()
            }
            drawRoundRect(look.dim, Offset(34f, 7f), Size(12f, 9f), CornerRadius(2f))
            drawPath(body, look.track)
            clipPath(body) {
                drawPath(wave(lv), if (low) look.warn else look.accent)
                drawPath(wave(lv + 5f), Color.Black.copy(alpha = 0.18f))
            }
            drawPath(body, look.dim, style = Stroke(1f))
            translate(19f, top + 10f) { with(pump) { draw(Size(24f, 24f), alpha = 0.55f, colorFilter = ColorFilter.tint(look.ink)) } }
            listOf(1f to "F", 0.5f to "½", 0f to "E").forEach { (p, l) ->
                val yy = bot - (bot - top) * p
                drawLine(look.dim, Offset(56f, yy), Offset(60f, yy), strokeWidth = 1f)
                label(tm, l, 62f, yy + 2.5f, 7f, if (l == "E" && low) look.warn else look.dim, align = 0f)
            }
        }
    }
}

@Composable
private fun BatteryCell(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Stack(f, look, m) {
    Vb(126f, 60f) {
        val on = kotlin.math.ceil(frac(f.fraction) * 5).toInt()
        val c = levelColor(f.level)
        drawRoundRect(look.ink, Offset(4f, 8f), Size(110f, 44f), CornerRadius(8f), style = Stroke(2.4f))
        drawRoundRect(look.ink, Offset(115f, 22f), Size(7f, 16f), CornerRadius(2f))
        for (i in 0 until 5) drawRoundRect(if (i < on) c else look.track, Offset(11f + i * 19.6f, 15f), Size(16f, 30f), CornerRadius(2.5f))
    }
}

@Composable
private fun Fader(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 0.5f) {
    Vb(50f, 100f) {
        val top = 8f
        val bot = 92f
        val y = bot - (bot - top) * frac(f.fraction)
        for (i in 0..10) {
            val yy = bot - (bot - top) * i / 10f
            drawLine(look.dim, Offset(if (i % 5 != 0) 9f else 5f, yy), Offset(14f, yy), strokeWidth = 0.7f)
            drawLine(look.dim, Offset(36f, yy), Offset(if (i % 5 != 0) 41f else 45f, yy), strokeWidth = 0.7f)
        }
        drawRoundRect(look.track, Offset(19f, top), Size(12f, bot - top), CornerRadius(6f))
        drawRoundRect(
            if (f.alert) Brush.verticalGradient(listOf(look.warn, look.warn)) else Brush.verticalGradient(listOf(look.accent2, look.accent), startY = y, endY = bot),
            Offset(19f, y), Size(12f, bot - y), CornerRadius(6f)
        )
        // No reading, no knob: at the bottom of its travel it would read as zero.
        if (f.idle) return@Vb
        drawRoundRect(look.ink, Offset(10f, y - 5f), Size(30f, 10f), CornerRadius(3f))
        drawLine(look.accent, Offset(14f, y), Offset(36f, y), strokeWidth = 1.6f)
    }
}

// --- Speed and engine ------------------------------------------------------------------------------

@Composable
private fun SpeedTape(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 0.6f) {
    Vb(60f, 100f) { tm ->
        // No speed yet (adapter off, no GPS fix): a blank tape, not one stopped at 0.
        val n = (f.number ?: f.value.toFloatOrNull())?.roundToInt()
        drawRoundRect(look.fill, Offset(2f, 2f), Size(54f, 96f), CornerRadius(6f))
        var v = if (n == null) 0 else ((n - 40) / 5) * 5
        while (n != null && v <= n + 40) {
            if (v >= 0) {
                val y = 50f - (v - n) * 1.5f
                val a = (1f - (abs(y - 50f) / 50f).pow(2)).coerceIn(0f, 1f)
                if (v % 10 == 0) {
                    drawLine(look.dim.copy(alpha = a), Offset(42f, y), Offset(52f, y), strokeWidth = 1f)
                    label(tm, v.toString(), 38f, y + 3.2f, 9f, look.dim.copy(alpha = a), align = 1f)
                } else drawLine(look.dim.copy(alpha = a), Offset(46f, y), Offset(52f, y), strokeWidth = 0.6f)
            }
            v += 5
        }
        val box = Shapes.speedBox
        drawPath(box, Color(0xFF111418))
        drawPath(box, look.accent, style = Stroke(1.4f))
        label(tm, n?.toString() ?: NO_READING, 24f, 55.5f, 15f, if (f.alert) look.warn else Color.White)
    }
}

@Composable
private fun RoadSign(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val pump = rememberVectorPainter(Icons.Filled.LocalGasStation)
    when (f.sign) {
        SignKind.SPEED -> Split(f, look, m, 1f) {
            Vb(100f, 100f) { tm ->
                val s = (f.number?.roundToInt()?.toString() ?: f.value)
                drawCircle(Color.White, 47f, Offset(50f, 50f))
                drawCircle(SignRed, 40.5f, Offset(50f, 50f), style = Stroke(11f))
                label(tm, s, 50f, if (s.length > 2) 62f else 65f, if (s.length > 2) 32f else 42f, Color(0xFF111111), weight = FontWeight.ExtraBold, family = Condensed)
            }
        }
        SignKind.DIRECTIONS -> Stack(f, look, m, foot = { FaceFoot(f, look, m, caption = false) }, header = false) {
            Vb(200f, 96f) { tm ->
                drawRoundRect(SignGreen, Offset(2f, 2f), Size(196f, 92f), CornerRadius(10f))
                drawRoundRect(Color.White, Offset(7f, 7f), Size(186f, 82f), CornerRadius(7f), style = Stroke(2.5f))
                val art = f.art
                if (art != null) {
                    drawImage(art, dstOffset = IntOffset(18, 26), dstSize = IntSize(56, 56), colorFilter = ColorFilter.tint(Color.White))
                } else {
                    drawPath(Shapes.signArrow, Color.White)
                }
                label(tm, "${f.value} ${f.unit}".trim(), 86f, 52f, 30f, Color.White, align = 0f, weight = FontWeight.ExtraBold, family = Condensed)
                label(tm, if (f.caption.length > 27) f.caption.take(26) + "…" else f.caption, 87f, 72f, 11f, Color.White, align = 0f, weight = FontWeight.Normal)
            }
        }
        else -> Split(f, look, m, 100f / 130f) {
            Vb(100f, 130f) { tm ->
                drawRoundRect(SignBlue, Offset(4f, 4f), Size(92f, 92f), CornerRadius(10f))
                drawRoundRect(Color.White, Offset(4f, 4f), Size(92f, 92f), CornerRadius(10f), style = Stroke(3f))
                when (f.sign) {
                    SignKind.PARKING -> label(tm, "P", 50f, 73f, 64f, Color.White, weight = FontWeight.ExtraBold)
                    SignKind.REST -> {
                        drawPath(Shapes.restCup, Color.White)
                        drawArc(Color.White, -90f, 180f, false, Offset(68f, 44f), Size(16f, 16f), style = Stroke(5f))
                        drawRoundRect(Color.White, Offset(24f, 80f), Size(52f, 5f), CornerRadius(2.5f))
                        Shapes.restSteam.forEach { drawPath(it, Color.White, style = Stroke(3f, cap = StrokeCap.Round)) }
                    }
                    else -> translate(21f, 20f) { with(pump) { draw(Size(57.6f, 57.6f), colorFilter = ColorFilter.tint(Color.White)) } }
                }
                drawRoundRect(Color.White, Offset(4f, 102f), Size(92f, 24f), CornerRadius(5f))
                drawRoundRect(SignBlue, Offset(4f, 102f), Size(92f, 24f), CornerRadius(5f), style = Stroke(2f))
                val plate = if (f.sign == SignKind.REST) f.value else "${f.value} ${f.unit.substringBefore(' ')}".trim()
                label(tm, plate, 50f, 119f, 14f, Color(0xFF111111), weight = FontWeight.ExtraBold, family = Condensed)
            }
        }
    }
}

@Composable
private fun TwinDials(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Stack(f, look, m, foot = { FaceFoot(f, look, m) }) {
    Vb(200f, 100f) { tm ->
        // Adapter off: both dials stay, with "--" and no needle.
        List(2) { f.gauges.getOrNull(it) }.forEachIndexed { i, g ->
            val c = Offset(50f + i * 100f, 50f)
            drawCircle(DialFace, 46f, c)
            drawCircle(look.dim, 46f, c, style = Stroke(1f))
            if (i == 1) arcDeg(look.warn, c, 38.5f, -135f + 270f * 0.78f, 135f, 3f, StrokeCap.Butt)
            for (t in 0..20) {
                val a = -135f + 270f * t / 20f
                drawLine(Color(0xFFE8EAED), polarPoint(c, 40f, a), polarPoint(c, if (t % 5 != 0) 36.5f else 33f, a), strokeWidth = if (t % 5 != 0) 0.6f else 1.4f)
            }
            if (g != null) {
                val a = -135f + 270f * frac(g.fraction)
                drawLine(look.accent, polarPoint(c, 7f, a + 180f), polarPoint(c, 34f, a), strokeWidth = 2.4f, cap = StrokeCap.Round)
            }
            drawCircle(Color(0xFFE8EAED), 3.8f, c)
            label(tm, g?.value ?: NO_READING, c.x, 79f, 13f, Color.White)
            label(tm, g?.unit.orEmpty().uppercase(Locale.getDefault()), c.x, 88f, 6f, Color(0xFF9AA0A6), weight = FontWeight.Normal)
        }
    }
}

@Composable
private fun ShiftLights(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val rpm = f.gauges.firstOrNull { it.unit == "rpm" } ?: FaceGauge("", f.value, f.unit, frac(f.fraction))
    val others = f.gauges.filter { it !== rpm }.take(3)
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.2f))) {
        FaceHeader(f, look, m)
        Vb(200f, 16f, Modifier.fillMaxWidth().height(m.dp(9f).coerceAtLeast(10.dp))) {
            val on = (frac(rpm.fraction * 1.6f) * 15).roundToInt()
            for (i in 0 until 15) {
                val c = if (i < 6) LampGreen else if (i < 11) Color(0xFFFFC107) else Color(0xFFFF3B30)
                val center = Offset(7f + i * 13.3f, 8f)
                if (i < on) drawCircle(c.copy(alpha = 0.35f), 7.5f, center)
                drawCircle(if (i < on) c else look.track, 5.2f, center)
            }
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FaceText(rpm.value, look, m.sp(min(m.h * 0.34f, m.w * 0.2f)), weight = FontWeight.ExtraBold, family = look.numFont, italic = true, glow = true)
            Spacer(Modifier.width(6.dp))
            FaceText(rpm.unit.uppercase(Locale.getDefault()), look, m.label, color = look.dim, weight = FontWeight.Bold, letterSpacing = 0.14.em)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.dp(5f))) {
            others.forEach { FaceStatBlock(FaceStat(it.label, "${it.value} ${it.unit}".trim()), look, m, Modifier.weight(1f, fill = false)) }
        }
        // While it drives the caption only repeats the revs; without a reading it says why, beside Connect.
        if (f.idle || f.actions.isNotEmpty()) FaceFoot(f, look, m)
    }
}

// --- Heading and forces -------------------------------------------------------------------------------

@Composable
private fun HeadingTape(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val points = listOf(
        R.string.info_dir_n, R.string.info_dir_ne, R.string.info_dir_e, R.string.info_dir_se,
        R.string.info_dir_s, R.string.info_dir_sw, R.string.info_dir_w, R.string.info_dir_nw
    ).map { stringResource(it) }
    Stack(f, look, m) {
        Vb(200f, 46f) { tm ->
            drawRoundRect(look.fill, Offset(0f, 2f), Size(200f, 40f), CornerRadius(6f))
            // No heading yet: a blank tape, not one stopped on north.
            val a = f.angle ?: run {
                label(tm, NO_READING, 100f, 27f, 12f, look.dim)
                return@Vb
            }
            var d = (floor((a - 75f) / 5f) * 5f).toInt()
            while (d <= a + 75f) {
                val x = 100f + (d - a) * 1.35f
                val alpha = (1f - (abs(x - 100f) / 100f).pow(2)).coerceIn(0f, 1f)
                val dd = ((d % 360) + 360) % 360
                if (dd % 45 == 0) {
                    label(tm, points[dd / 45], x, 20f, if (dd % 90 != 0) 9f else 12f, (if (dd == 0) look.warn else look.ink).copy(alpha = alpha), weight = FontWeight.ExtraBold)
                } else if (dd % 15 == 0) {
                    label(tm, dd.toString(), x, 19f, 7f, look.dim.copy(alpha = alpha), weight = FontWeight.Normal)
                }
                drawLine(look.dim.copy(alpha = alpha), Offset(x, if (dd % 15 != 0) 32f else 27f), Offset(x, 38f), strokeWidth = if (dd % 15 != 0) 0.6f else 1.1f)
                d += 5
            }
            drawLine(look.accent, Offset(100f, 4f), Offset(100f, 38f), strokeWidth = 1.6f)
            drawPath(Shapes.headingMark, look.accent)
        }
    }
}

@Composable
private fun CompassRose(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val letters = listOf(R.string.info_dir_n, R.string.info_dir_e, R.string.info_dir_s, R.string.info_dir_w).map { stringResource(it) }
    val toCar = f.sign == SignKind.PARKING
    Split(f, look, m, 1f) {
        Vb(100f, 100f) { tm ->
            val c = Offset(50f, 50f)
            // No heading (or no way to the car) yet: the bare ring, with no north and no arrow to read.
            val a = f.angle
            drawCircle(look.fill, 47f, c)
            drawCircle(look.dim, 47f, c, style = Stroke(0.8f))
            rotate(if (toCar || a == null) 0f else -a, c) {
                for (d in 0 until 360 step 10) drawLine(look.ink, polarPoint(c, 44f, d.toFloat()), polarPoint(c, if (d % 30 != 0) 41f else 37.5f, d.toFloat()), strokeWidth = if (d % 30 != 0) 0.6f else 1.3f)
                if (a != null) {
                    letters.forEachIndexed { i, l ->
                        val p = polarPoint(c, 29f, i * 90f)
                        rotate(i * 90f, p) { label(tm, l, p.x, p.y + 4f, 11f, if (i == 0) look.warn else look.ink, weight = FontWeight.ExtraBold) }
                    }
                    drawPath(Shapes.roseNeedle, look.dim.copy(alpha = 0.45f))
                }
            }
            if (a != null) {
                if (toCar) {
                    rotate(a, c) { drawPath(Shapes.roseToCar, look.accent) }
                } else {
                    drawPath(Shapes.roseTop, look.accent)
                }
            }
            drawCircle(look.ink, 3f, c)
        }
    }
}

@Composable
private fun Pointer(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 1f) {
    Vb(100f, 100f) {
        val c = Offset(50f, 50f)
        drawCircle(look.track, 46f, c, style = Stroke(6f))
        // No direction yet: the empty ring, not an arrow straight ahead.
        val a = f.angle ?: return@Vb
        arcDeg(look.accent, c, 46f, a - 14f, a + 14f, 6f)
        rotate(a, c) {
            val arrow = Shapes.pointerArrow
            drawPath(arrow, look.accent.copy(alpha = 0.25f), style = Stroke(6f))
            drawPath(arrow, look.accent)
        }
    }
}

@Composable
private fun FrictionCircle(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 1f) {
    Vb(100f, 100f) { tm ->
        val c = Offset(50f, 50f)
        val r = 44f
        val sc = r / 1.2f
        val (lat, lon) = f.point ?: (0f to 0f)
        val p = Offset(50f + lat.coerceIn(-1.2f, 1.2f) * sc, 50f - lon.coerceIn(-1.2f, 1.2f) * sc)
        drawCircle(look.fill, r, c)
        drawCircle(look.warn.copy(alpha = 0.5f), r, c, style = Stroke(1f))
        drawCircle(look.dim.copy(alpha = 0.4f), r * 2 / 3, c, style = Stroke(1f))
        drawCircle(look.dim.copy(alpha = 0.4f), r / 3, c, style = Stroke(1f))
        drawLine(look.dim.copy(alpha = 0.35f), Offset(6f, 50f), Offset(94f, 50f))
        drawLine(look.dim.copy(alpha = 0.35f), Offset(50f, 6f), Offset(50f, 94f))
        val pk = (f.peak ?: 0f) * sc
        if (pk > 0f) drawCircle(look.accent2, pk.coerceAtMost(r), c, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2f))))
        drawLine(look.accent, c, p, strokeWidth = 2f, cap = StrokeCap.Round)
        drawCircle(look.accent.copy(alpha = 0.3f), 9f, p)
        drawCircle(look.accent, 6f, p)
        drawCircle(Color.White, 2.4f, p)
        label(tm, "1.2 g", 50f, 12f, 5.5f, look.dim, weight = FontWeight.Normal)
    }
}

@Composable
private fun GBars(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 1f) {
    Vb(100f, 100f) {
        val (lat, lon) = f.point ?: (0f to 0f)
        val sc = 42f / 1.2f
        val lx = lat.coerceIn(-1.2f, 1.2f) * sc
        val ly = -lon.coerceIn(-1.2f, 1.2f) * sc
        drawRoundRect(look.track, Offset(8f, 45f), Size(84f, 10f), CornerRadius(5f))
        drawRoundRect(look.track, Offset(45f, 8f), Size(10f, 84f), CornerRadius(5f))
        drawRoundRect(look.accent, Offset(min(50f, 50f + lx), 45f), Size(abs(lx), 10f), CornerRadius(3f))
        drawRoundRect(look.accent2, Offset(45f, min(50f, 50f + ly)), Size(10f, abs(ly)), CornerRadius(3f))
        drawCircle(look.ink, 6f, Offset(50f, 50f))
        // Direction marks at the ends of each bar.
        Shapes.gBarMarks.forEach { (mark, p, a) -> rotate(a, p) { drawPath(mark, look.dim) } }
    }
}

@Composable
private fun SpiritLevel(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 1f) {
    Vb(100f, 100f) {
        val (lat, lon) = f.point ?: (0f to 0f)
        val c = Offset(50f, 50f)
        val line = Color(0xFF1B3D12)
        drawCircle(Color(0xFF2A2D33), 46f, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFE6F7B0), Color(0xFF7CB342)), Offset(42f, 38f), 50f), 41f, c)
        drawCircle(line, 13f, c, style = Stroke(1.2f))
        drawCircle(line, 27f, c, style = Stroke(0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2f))))
        drawLine(line, Offset(9f, 50f), Offset(91f, 50f), 0.5f)
        drawLine(line, Offset(50f, 9f), Offset(50f, 91f), 0.5f)
        val b = Offset(50f - lat.coerceIn(-1f, 1f) * 30f, 50f - lon.coerceIn(-1f, 1f) * 30f)
        drawOval(Color.White.copy(alpha = 0.85f), Offset(b.x - 9.5f, b.y - 9f), Size(19f, 18f))
        drawOval(Color.White, Offset(b.x - 6f, b.y - 5f), Size(6f, 4f))
    }
}

// --- Navigation ---------------------------------------------------------------------------------------

@Composable
private fun TurnCard(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 0.84f) {
    Vb(84f, 100f) {
        val fr = frac(f.fraction)
        drawRoundRect(look.accent, Offset(2f, 8f), Size(62f, 84f), CornerRadius(14f))
        val art = f.art
        if (art != null) {
            drawImage(art, dstOffset = IntOffset(9, 26), dstSize = IntSize(48, 48), colorFilter = ColorFilter.tint(look.onAccent))
        } else if (!f.idle) {
            // Without guidance the card stays blank: an arrow would be a turn to take.
            drawPath(Shapes.turnArrow, look.onAccent)
        }
        // Distance left to the turn: the bar empties as the car gets there.
        drawRoundRect(look.track, Offset(72f, 8f), Size(8f, 84f), CornerRadius(4f))
        drawRoundRect(look.accent, Offset(72f, 8f + 84f * fr), Size(8f, 84f * (1f - fr)), CornerRadius(4f))
    }
}

@Composable
private fun RoadAhead(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Stack(f, look, m) {
    Vb(200f, 100f) { tm ->
        fun hw(y: Float) = Shapes.roadHalf(y)
        drawPath(Shapes.road, Color(0xFF2B2F36))
        drawLine(Color.White.copy(alpha = 0.8f), Offset(100f - hw(12f), 12f), Offset(100f - hw(96f), 96f), 1.2f)
        drawLine(Color.White.copy(alpha = 0.8f), Offset(100f + hw(12f), 12f), Offset(100f + hw(96f), 96f), 1.2f)
        for (i in 0 until 9) {
            val y0 = 96f - (i / 9f).pow(0.8f) * 84f
            val y1 = 96f - ((i + 0.45f) / 9f).pow(0.8f) * 84f
            drawLine(Color.White, Offset(100f, y0), Offset(100f, y1), strokeWidth = 1.8f - i * 0.15f)
        }
        drawLine(look.dim.copy(alpha = 0.4f), Offset(0f, 12f), Offset(200f, 12f), 1f)
        f.reach?.let { label(tm, it, 100f, 8f, 7f, look.dim) }
        f.fraction?.let { fr ->
            val y = 94f - frac(fr).pow(0.7f) * 78f
            val x = 100f + hw(y) + 6f
            drawLine(look.ink, Offset(x, y), Offset(x, y - 16f), 1.2f)
            translate(x, y) { drawPath(Shapes.flag, look.accent) }
            f.marker?.let { label(tm, it, x + 15f, y - 9f, 7f, look.dim, align = 0f, weight = FontWeight.Normal) }
        }
        drawRoundRect(look.accent, Offset(93f, 80f), Size(14f, 18f), CornerRadius(4f))
        drawRoundRect(Color(0x990B1220), Offset(95f, 84f), Size(10f, 5f), CornerRadius(1.5f))
    }
}

@Composable
private fun Radar(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    // The beam turns only while there's a spot to point at, as ambient motion:
    // at the effects setting's rate (20 fps at full), still with effects off.
    val turn = if (f.angle != null) rememberLoop(4000) else null
    val beamCenter = Offset(50f, 50f)
    // The beam's gradient is fixed in the view box; turned, not rebuilt, each frame.
    val beam = remember(look.accent) {
        Brush.sweepGradient(0.625f to Color.Transparent, 0.75f to look.accent.copy(alpha = 0.45f), center = beamCenter)
    }
    Split(f, look, m, 1f) {
        Vb(100f, 100f) {
            val c = beamCenter
            drawCircle(look.fill, 46f, c)
            for (ring in RADAR_RINGS) drawCircle(look.accent.copy(alpha = 0.3f), ring, c, style = HairlineStroke)
            drawLine(look.accent.copy(alpha = 0.2f), Offset(4f, 50f), Offset(96f, 50f))
            drawLine(look.accent.copy(alpha = 0.2f), Offset(50f, 4f), Offset(50f, 96f))
            // The beam: a 45° wedge fading in towards its leading edge, turned as one piece.
            val sweep = turn?.let { it.value * 360f } ?: 0f
            rotate(sweep, c) {
                drawArc(beam, 225f, 45f, true, Offset(4f, 4f), Size(92f, 92f))
            }
            // The blip only once there is a spot and a way to it: none parked, nothing on the screen.
            f.angle?.let { a ->
                val p = polarPoint(c, 8f + 36f * (1f - frac(f.fraction)), a)
                drawCircle(look.accent.copy(alpha = 0.25f), 7f, p)
                drawCircle(look.accent, 3.6f, p)
            }
            drawRoundRect(look.ink, Offset(47f, 45f), Size(6f, 10f), CornerRadius(2f))
        }
    }
}

// --- Numbers as objects ---------------------------------------------------------------------------------

@Composable
private fun Odometer(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val (ip, dec) = odometerDigits(f.value, java.text.DecimalFormatSymbols.getInstance(java.util.Locale.getDefault()).decimalSeparator)
    val digits = ip.padStart(5, '0') + dec
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.4f))) {
        FaceHeader(f, look, m) { FaceActions(f, look, m, small = true) }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            val gap = 3f
            val unitW = if (f.unit.isEmpty()) 0f else 44f
            val dh = min(maxHeight.value * 0.9f, (maxWidth.value - unitW - gap * digits.length) / digits.length / 0.62f).coerceAtLeast(12f)
            Row(horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) {
                digits.forEachIndexed { i, ch -> Drum(ch.digitToIntOrNull() ?: 0, dec.isNotEmpty() && i == digits.lastIndex, dh.dp, m) }
                if (f.unit.isNotEmpty()) FaceText(f.unit, look, m.label, Modifier.padding(start = 4.dp), color = look.dim, weight = FontWeight.Bold)
            }
        }
        FaceCaption(f, look, m)
        if (f.stats.isNotEmpty() && m.h >= 140f) Row(horizontalArrangement = Arrangement.spacedBy(m.dp(5f))) {
            f.stats.take(3).forEach { FaceStatBlock(it, look, m, Modifier.weight(1f, fill = false)) }
        }
    }
}

/** One odometer drum: the digit, with its neighbours rolling out of view above and below. */
@Composable
private fun Drum(d: Int, inverted: Boolean, h: Dp, m: FaceMetrics) {
    val bg = if (inverted) Brush.verticalGradient(listOf(Color(0xFF777777), Color(0xFFEEEEEE), Color.White, Color(0xFFEEEEEE), Color(0xFF777777)))
    else Brush.verticalGradient(listOf(Color.Black, Color(0xFF2B2D31), Color(0xFF3A3D42), Color(0xFF2B2D31), Color.Black))
    val ink = if (inverted) Color(0xFF111111) else Color(0xFFF1F1F1)
    val size = m.sp(h.value * 0.58f)
    Box(modifier = Modifier.height(h).aspectRatio(0.62f).clip(RoundedCornerShape(h * 0.08f)).background(bg).clipToBounds(), contentAlignment = Alignment.Center) {
        val style = TextStyle(color = ink, fontSize = size, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        androidx.compose.material3.Text(((d + 9) % 10).toString(), style = style, modifier = Modifier.offset(y = -h * 0.62f).alpha(0.35f))
        androidx.compose.material3.Text(d.toString(), style = style)
        androidx.compose.material3.Text(((d + 1) % 10).toString(), style = style, modifier = Modifier.offset(y = h * 0.62f).alpha(0.35f))
    }
}

@Composable
private fun Printout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val paperInk = Color(0xFF1D1D1D)
    val size = m.sp(max(m.u * 5.6f, 9f))
    val now = rememberNow(60_000L)
    val timePattern = LocalUnits.current.timePattern()
    val stamp = remember(now, timePattern) { SimpleDateFormat("dd/MM/yyyy $timePattern", Locale.getDefault()).format(now) }
    val lines = (f.stats.map { it.label to it.value } + f.rows.map { it.title to it.detail }).take(4)
    val torn = remember {
        GenericShape { s, _ ->
            val teeth = 16
            moveTo(0f, 0f); lineTo(s.width, 0f)
            val tooth = s.width / (teeth * 2)
            val depth = 5f
            for (i in 0..teeth * 2) lineTo(s.width - i * tooth, if (i % 2 == 1) s.height - depth else s.height)
            close()
        }
    }
    val paperLook = look.copy(ink = paperInk, dim = Color(0xFF555555), glow = null)
    Column(modifier = Modifier.fillMaxSize().padding(start = m.dp(12f), end = m.dp(12f), top = m.dp(3f))) {
        Box(Modifier.fillMaxWidth().height(m.dp(3.5f).coerceAtLeast(4.dp)).clip(CircleShape).background(Color(0xFF050608)))
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().clip(torn).background(Color(0xFFF6F6F3))
                .padding(horizontal = m.dp(5f), vertical = m.dp(3f)).clipToBounds(),
            verticalArrangement = Arrangement.spacedBy(m.dp(0.6f))
        ) {
            FaceText(f.title.uppercase(Locale.getDefault()), paperLook, size, Modifier.fillMaxWidth(), weight = FontWeight.Bold, family = FontFamily.Monospace, align = TextAlign.Center, letterSpacing = 0.12.em)
            FaceText(stamp, paperLook, size, Modifier.fillMaxWidth(), family = FontFamily.Monospace, align = TextAlign.Center)
            DashedRule(paperInk)
            lines.forEach { (l, v) ->
                Row {
                    FaceText(l, paperLook, size, Modifier.weight(1f), family = FontFamily.Monospace)
                    FaceText(v, paperLook, size, family = FontFamily.Monospace)
                }
            }
            DashedRule(paperInk)
            FaceText("= ${f.value} ${f.unit}".trim(), paperLook, size * 1.2f, Modifier.fillMaxWidth(), weight = FontWeight.Bold, family = FontFamily.Monospace, align = TextAlign.End)
            FaceText(f.caption, paperLook, size, Modifier.fillMaxWidth(), family = FontFamily.Monospace, align = TextAlign.Center)
        }
        // The caption is on the paper; the buttons (Scan, Clear) go under the tear.
        if (f.actions.isNotEmpty()) Box(Modifier.padding(vertical = m.dp(2f).coerceAtLeast(4.dp))) { FaceFoot(f, look, m, caption = false) }
    }
}

@Composable
private fun DashedRule(color: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(5.dp)) {
        drawLine(color.copy(alpha = 0.45f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    }
}

// --- Status at a glance -------------------------------------------------------------------------------

@Composable
private fun WarningLamp(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val level = f.level
    val lamp = if (level >= 2) LampRed else if (level == 1) LampAmber else Color(0xFF3A3F47)
    val glyph = min(m.h * (if (f.actions.isEmpty()) 0.46f else 0.30f), m.w * 0.40f)
    Column(
        modifier = Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1B1E24), Color(0xFF060708))))
            .padding(m.pad.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.dp(2f), Alignment.CenterVertically)
    ) {
        Box(
            modifier = Modifier.size(glyph.dp).drawBehind {
                if (level > 0) drawCircle(Brush.radialGradient(listOf(lamp.copy(alpha = 0.45f), Color.Transparent)), size.minDimension * 0.75f)
            },
            contentAlignment = Alignment.Center
        ) { Icon(f.icon, contentDescription = null, tint = lamp, modifier = Modifier.fillMaxSize()) }
        FaceText(f.title.uppercase(Locale.getDefault()), look, m.label, color = Color(0xFF8E97A8), weight = FontWeight.Bold, letterSpacing = 0.16.em)
        FaceText(f.caption, look, m.caption, color = if (level > 0) lamp else Color(0xFF8E97A8), align = TextAlign.Center)
        // The lamp's plate is dark in every theme: the buttons are drawn for it.
        FootActions(f, look.copy(ink = Color.White, fill = Color.White.copy(alpha = 0.14f)), m, beside = 0)
    }
}

@Composable
private fun TrafficLight(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 0.4f) {
    Vb(40f, 100f) {
        val level = f.level
        drawRoundRect(Color(0xFF23262B), Offset(4f, 2f), Size(32f, 96f), CornerRadius(9f))
        drawRoundRect(Color(0xFF0E0F11), Offset(4f, 2f), Size(32f, 96f), CornerRadius(9f), style = Stroke(2f))
        listOf(Triple(21f, LampRed, level >= 2), Triple(50f, LampAmber, level == 1), Triple(79f, LampGreen, level == 0)).forEach { (y, c, on) ->
            val p = Offset(20f, y)
            if (on) drawCircle(c.copy(alpha = 0.35f), 15f, p)
            drawCircle(if (on) c else Color(0xFF1A1A1A), 11f, p)
            drawCircle(Color.Black, 11f, p, style = Stroke(1.5f))
        }
    }
}

@Composable
private fun GaugeBank(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    // Adapter off: a row of unlit dials reading "--", over the reason and Connect.
    val gauges = f.gauges.take(6).ifEmpty { List(3) { FaceGauge("", NO_READING, "", 0f) } }
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2f))) {
        FaceHeader(f, look, m)
        gauges.chunked(3).forEach { row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(2f))) {
                row.forEach { g ->
                    Vb(100f, 84f, Modifier.weight(1f).fillMaxHeight()) { tm ->
                        val c = Offset(50f, 48f)
                        arcDeg(look.track, c, 36f, -120f, 120f, 8f)
                        val fr = frac(g.fraction)
                        if (fr > 0f) arcDeg(look.accent, c, 36f, -120f, -120f + 240f * fr, 8f)
                        label(tm, g.value, 50f, 53f, 17f, look.ink)
                        label(tm, g.unit, 50f, 66f, 8f, look.dim, weight = FontWeight.Normal)
                        label(tm, g.label.uppercase(Locale.getDefault()), 50f, 82f, 9f, look.dim)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (f.gauges.isEmpty() || f.actions.isNotEmpty()) FaceFoot(f, look, m)
    }
}

@Composable
private fun CarTop(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 100f / 146f) {
    // The driver's own car from above (MyCarLook.kt), its open parts laid on it.
    val mine by MyCarLook.shown.collectAsState()
    val style by MyCarLook.style.collectAsState()
    val own = mine?.top
    if (own != null) {
        val d = f.doors ?: emptyList()
        val open = DOOR_ORDER.withIndex().associate { (i, part) -> part to if (d.getOrElse(i) { false }) 1f else 0f }
        Canvas(Modifier.fillMaxSize()) { drawCarView(own, style, fitCarOpen(own, Rect(Offset.Zero, size)), look.accent, look.warn, open = open) }
        return@Split
    }
    Vb(100f, 146f, y0 = -10f) {
        val d = f.doors ?: emptyList()
        fun open(i: Int) = d.getOrElse(i) { false }
        drawRoundRect(look.fill, Offset(30f, 8f), Size(40f, 112f), CornerRadius(15f))
        drawRoundRect(look.dim, Offset(30f, 8f), Size(40f, 112f), CornerRadius(15f), style = Stroke(1f))
        drawPath(Shapes.carWindscreen, look.track)
        drawRoundRect(look.track, Offset(37f, 52f), Size(26f, 40f), CornerRadius(4f))
        drawPath(Shapes.carRearWindow, look.track)
        fun door(hx: Float, hy: Float, dir: Float, isOpen: Boolean) {
            val rad = if (isOpen) 50f * PI.toFloat() / 180f else 0f
            val end = Offset(hx + dir * 24f * sin(rad), hy + 24f * cos(rad))
            drawLine(if (isOpen) look.warn else look.ink, Offset(hx, hy), end, strokeWidth = if (isOpen) 3.5f else 2f, cap = StrokeCap.Round)
        }
        door(30f, 42f, -1f, open(0)); door(70f, 42f, 1f, open(1)); door(30f, 70f, -1f, open(2)); door(70f, 70f, 1f, open(3))
        if (open(4)) drawRoundRect(look.warn.copy(alpha = 0.85f), Offset(34f, 120f), Size(32f, 12f), CornerRadius(3f))
        else drawLine(look.ink, Offset(36f, 119f), Offset(64f, 119f), 2f)
        if (open(5)) drawRoundRect(look.warn.copy(alpha = 0.85f), Offset(34f, -6f), Size(32f, 12f), CornerRadius(3f))
        else drawLine(look.ink, Offset(38f, 10f), Offset(62f, 10f), 2f)
    }
}

/** The parts in [WidgetFace.doors]' order. */
private val DOOR_ORDER = listOf(CarPart.FRONT_LEFT, CarPart.FRONT_RIGHT, CarPart.REAR_LEFT, CarPart.REAR_RIGHT, CarPart.TAILGATE, CarPart.BONNET)

@Composable
private fun DataRain(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val green = Color(0xFF3DDC84)
    val lines = remember(f.rows) {
        List(10) { i ->
            f.rows.getOrNull(i)?.title ?: ("%02X.%02d  ".format(Locale.US, 0x20 + i * 7, i * 13 % 90 + 10) +
                List(5) { j -> "%02X".format(Locale.US, (i * 37 + j * 91) % 256) }.joinToString(" "))
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(1.6f))) {
        FaceHeader(f, look, m)
        Column(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            lines.forEachIndexed { i, l ->
                FaceText(l, look.copy(glow = green.copy(alpha = 0.4f)), m.sp(max(m.u * 6f, 10f)), Modifier.alpha((1f - i * 0.1f).coerceAtLeast(0.1f)),
                    color = if (i == 0) Color(0xFFD8FFE6) else green, family = FontFamily.Monospace, glow = true)
            }
        }
        FaceCaption(f, look, m)
    }
}

// --- Time and the sky -------------------------------------------------------------------------------------

@Composable
private fun SkyScene(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val hour = hourOfDay(f)
    val day = hour >= 7f && hour < 20f
    val code = f.weatherCode ?: 0
    val over = look.copy(ink = Color.White, dim = Color(0xFFF0F6FF), accent = Color.White, glow = Color.Black.copy(alpha = 0.45f))
    Box(modifier = Modifier.fillMaxSize()) {
        Vb(200f, 110f, slice = true) {
            drawRect(Brush.verticalGradient(if (day) listOf(Color(0xFF2F7FDB), Color(0xFFA9D8FF)) else listOf(Color(0xFF0B1433), Color(0xFF2A3F7A)), 0f, 110f), Offset(-200f, -200f), Size(600f, 510f))
            val t = ((hour - 6f) / 16f).coerceIn(0f, 1f)
            if (day) {
                val sun = Offset(20f + 160f * t, 70f - sin(PI.toFloat() * t) * 52f)
                drawCircle(Color(0x40FFD54F), 15f, sun)
                drawCircle(Color(0xFFFFE082), 9f, sun)
            } else {
                drawCircle(Color(0xFFF5F3CE), 8f, Offset(150f, 24f))
                drawCircle(Color(0xFF0B1433), 7f, Offset(154f, 21f))
            }
            fun cloud(x: Float, y: Float, s: Float, a: Float) {
                listOf(Triple(0f, 0f, 16f to 9f), Triple(12f, -6f, 12f to 10f), Triple(24f, 1f, 13f to 8f)).forEach { (cx, cy, r) ->
                    drawOval(Color.White.copy(alpha = a), Offset(x + (cx - r.first) * s, y + (cy - r.second) * s), Size(r.first * 2 * s, r.second * 2 * s))
                }
            }
            if (code >= 2) { cloud(118f, 34f, 1.3f, 0.92f); cloud(40f, 22f, 0.9f, 0.75f) }
            val rain = code in 51..67 || code in 80..82
            val snow = code in 71..77 || code == 85 || code == 86
            if (rain) repeat(16) { i -> drawLine(Color(0xFFDDEBFF), Offset(20f + i * 11f, 50f + (i % 3) * 6f), Offset(16f + i * 11f, 60f + (i % 3) * 6f), 1.2f) }
            if (snow) repeat(18) { i -> drawCircle(Color.White, 1.4f, Offset(14f + i * 10f, 52f + (i % 4) * 7f)) }
            drawPath(Shapes.hillBack, if (day) Color(0xFF3C7A4A) else Color(0xFF11203A))
            drawPath(Shapes.hillFront, if (day) Color(0xFF2E6139) else Color(0xFF0B1628))
        }
        Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(1.5f))) {
            FaceHeader(f, over, m)
            Spacer(Modifier.weight(1f))
            FaceValue(f, over, m, min(m.h * 0.34f, m.w * 0.2f))
            FaceCaption(f, over, m)
        }
    }
}

@Composable
private fun SunPath(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val hour = hourOfDay(f)
    Stack(f, look, m) {
        Vb(200f, 100f) { tm ->
            val t = ((hour - 6f) / 16f).coerceIn(0f, 1f)
            val c = Offset(100f, 86f)
            arcDeg(look.dim.copy(alpha = 0.6f), c, 76f, -90f, 90f, 1f, StrokeCap.Butt)
            val pos = polarPoint(c, 76f, -90f + 180f * t)
            if (t > 0f) drawArc(
                Brush.linearGradient(listOf(Color(0xFFFF8A65), Color(0xFFFFD54F), Color(0xFFFF8A65)), Offset(24f, 0f), Offset(176f, 0f)),
                180f, 180f * t, false, Offset(24f, 10f), Size(152f, 152f), style = Stroke(3f, cap = StrokeCap.Round)
            )
            drawLine(look.dim.copy(alpha = 0.6f), Offset(6f, 86f), Offset(194f, 86f), 1f)
            drawCircle(Color(0x40FFD54F), 11f, pos)
            drawCircle(Color(0xFFFFD54F), 6.5f, pos)
            listOf(6 to 24f, 12 to 100f, 18 to 157f, 22 to 176f).forEach { (h, x) -> label(tm, "%02d".format(Locale.US, h), x, 97f, 7f, look.dim, weight = FontWeight.Normal) }
        }
    }
}

@Composable
private fun BinaryClock(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    // The clock face already brings the time (and ticks); only a face without one needs a ticker here.
    // Hours and minutes only: the seconds columns blinked for nothing while driving.
    val (h, mi, _) = f.clock ?: run {
        val now = rememberNow(60_000L)
        val cal = remember(now) { Calendar.getInstance().apply { time = now } }
        Triple(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), 0)
    }
    Split(f, look, m, 1.32f) {
        Vb(132f, 100f) {
            val digits = listOf(h / 10, h % 10, mi / 10, mi % 10)
            val bits = listOf(2, 4, 3, 4)
            digits.forEachIndexed { c, v ->
                // Four columns centred where six used to stand.
                val x = 34f + c * 20f + (if (c > 1) 8f else 0f)
                for (b in 0 until bits[c]) {
                    val on = (v shr b) and 1 == 1
                    val p = Offset(x, 86f - b * 22f)
                    if (on) drawCircle(look.accent.copy(alpha = 0.3f), 9.5f, p)
                    drawCircle(if (on) look.accent else look.track, 7f, p)
                }
            }
        }
    }
}

@Composable
private fun Timeline(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val nowMs = rememberNow(60_000L).time
    val cal = remember(nowMs) { Calendar.getInstance().apply { timeInMillis = nowMs } }
    val nowH = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
    Stack(f, look, m, foot = { FaceFoot(f, look, m) }) {
        Vb(200f, 78f) { tm ->
            val start = floor(nowH) - 1f
            val end = start + 9f
            fun x(hh: Float) = 8f + (hh - start) / (end - start) * 184f
            fun hourOf(ms: Long) = nowH + (ms - nowMs) / 3_600_000f
            drawLine(look.dim.copy(alpha = 0.6f), Offset(8f, 60f), Offset(192f, 60f), 1f)
            var hh = start
            while (hh <= end) {
                val xx = x(hh)
                drawLine(look.dim, Offset(xx, 60f), Offset(xx, if (hh.toInt() % 3 != 0) 63f else 66f), 1f)
                if (hh.toInt() % 2 == 0) label(tm, "%02d".format(Locale.US, ((hh.toInt() % 24) + 24) % 24), xx, 74f, 7f, look.dim, weight = FontWeight.Normal)
                hh += 1f
            }
            f.events.forEachIndexed { i, e ->
                val a = hourOf(e.startMs)
                val b = e.endMs?.let { hourOf(it) }
                if (a > end || (b ?: a) < start) return@forEachIndexed
                val y = if (i % 2 == 1) 32f else 8f
                if (b == null) {
                    drawCircle(look.accent, 4.5f, Offset(x(a), y + 10f))
                    label(tm, e.title.take(18), x(a) + 7f, y + 13f, 8f, look.ink, align = 0f, weight = FontWeight.Normal)
                } else {
                    val x0 = x(max(a, start))
                    val w = max(6f, x(min(b, end)) - x0)
                    drawRoundRect(look.accent.copy(alpha = 0.9f), Offset(x0, y), Size(w, 20f), CornerRadius(5f))
                    clipPathRect(x0, y, w, 20f) { label(tm, e.title, x0 + 4f, y + 13.5f, 8f, look.onAccent, align = 0f) }
                }
            }
            drawLine(look.warn, Offset(x(nowH), 2f), Offset(x(nowH), 60f), 1.4f)
            drawCircle(look.warn, 2.6f, Offset(x(nowH), 60f))
        }
    }
}

private inline fun DrawScope.clipPathRect(x: Float, y: Float, w: Float, h: Float, block: DrawScope.() -> Unit) {
    clipRect(x, y, x + w, y + h) { block() }
}

@Composable
private fun DeskCalendar(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val month = remember(now, locale) { SimpleDateFormat("LLLL", locale).format(now).uppercase(locale) }
    val day = remember(now, locale) { SimpleDateFormat("d", locale).format(now) }
    val weekday = remember(now, locale) { SimpleDateFormat("EEEE", locale).format(now).replaceFirstChar { it.uppercase(locale) } }
    val page = look.copy(ink = Color(0xFF111111), dim = Color(0xFF555555), glow = null)
    Split(f, look, m, 0.82f) {
        Column(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.White)) {
            FaceText(month, page.copy(ink = Color.White), m.sp(max(m.u * 5f, 9f)), Modifier.fillMaxWidth().background(SignRed).padding(vertical = 3.dp),
                weight = FontWeight.Bold, align = TextAlign.Center, letterSpacing = 0.12.em)
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FaceText(day, page, m.sp(min(m.h * 0.3f, m.w * 0.16f)), weight = FontWeight.ExtraBold, family = Condensed)
            }
            FaceText(weekday, page, m.sp(max(m.u * 5.5f, 9f)), Modifier.fillMaxWidth().padding(bottom = 3.dp), color = Color(0xFF555555), align = TextAlign.Center)
        }
    }
}

// --- People and messages -----------------------------------------------------------------------------------

@Composable
private fun CardStack(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val rows = f.rows.take(3)
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.4f))) {
        FaceHeader(f, look, m) {
            if (f.rows.isNotEmpty()) FaceText(
                f.rows.size.toString(), look.copy(ink = Color.White), m.label,
                Modifier.clip(CircleShape).background(look.warn).padding(horizontal = 8.dp, vertical = 2.dp), weight = FontWeight.Bold
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // No cards: the reason (access to grant, nothing new) where the pile would be.
            if (rows.isEmpty()) FaceCaption(f, look, m)
            val shape = RoundedCornerShape(m.dp(3.4f))
            // Back cards first so the newest ends up on top; only their edges show.
            rows.indices.reversed().forEach { i ->
                val r = rows[i]
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer {
                            translationY = i * m.dp(5f).toPx()
                            scaleX = 1f - i * 0.07f; scaleY = 1f - i * 0.07f
                            alpha = 1f - i * 0.25f
                        }
                        .clip(shape).background(look.fill)
                        .then(if (i == 0) (r.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier) else Modifier)
                        .padding(horizontal = m.dp(4f).coerceAtLeast(8.dp), vertical = m.dp(3f).coerceAtLeast(6.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (i == 0) {
                        val badge = r.badge
                        if (badge != null) Initials(badge, look, m.dp(11f).coerceAtLeast(26.dp), m)
                        else Box(Modifier.width(3.dp).height(m.dp(8f).coerceAtLeast(18.dp)).clip(CircleShape).background(look.accent))
                        Spacer(Modifier.width(m.dp(3f).coerceAtLeast(8.dp)))
                        Column(Modifier.weight(1f)) {
                            FaceText(r.title, look, m.body, weight = FontWeight.SemiBold)
                            if (r.detail.isNotEmpty()) FaceText(r.detail, look, m.sp(max(m.u * 5.4f, 10f)), color = look.dim)
                        }
                    } else Spacer(Modifier.height(m.dp(12f).coerceAtLeast(30.dp)))
                }
            }
        }
        FaceActions(f, look, m, small = true)
    }
}

@Composable
private fun Initials(text: String, look: FaceLook, size: Dp, m: FaceMetrics, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(look.accent, look.accent2))),
        contentAlignment = Alignment.Center
    ) { FaceText(text, look, m.sp(size.value * 0.36f), color = look.onAccent, weight = FontWeight.ExtraBold) }
}

@Composable
private fun Faces(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val people = f.rows.take(4)
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.4f))) {
        FaceHeader(f, look, m)
        if (people.isEmpty()) {
            FaceCaption(f, look, m)
            FaceActions(f, look, m, small = true)
            return@Column
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val size = min(m.h * 0.34f, m.w * 0.17f).dp
            people.forEachIndexed { i, r ->
                Column(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).then(r.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier).padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box {
                        Initials(r.badge ?: r.title.take(1), look, size, m,
                            if (i == 0) Modifier.border(2.dp, look.accent, CircleShape).padding(3.dp) else Modifier)
                        if (i == 0) Box(
                            modifier = Modifier.align(Alignment.BottomEnd).size(size * 0.38f).clip(CircleShape).background(LampGreen),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.22f)) }
                    }
                    FaceText(r.title.substringBefore(' '), look, m.sp(max(m.u * 6.2f, 10f)), weight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun Badge(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val bell = rememberVectorPainter(Icons.Filled.Notifications)
    Split(f, look, m, 1f) {
        Vb(100f, 100f) { tm ->
            drawCircle(look.fill, 40f, Offset(46f, 54f))
            translate(18f, 26f) { with(bell) { draw(Size(56f, 56f), colorFilter = ColorFilter.tint(look.accent)) } }
            if (f.value != "0" && f.value != "--") {
                drawCircle(look.warn, 17f, Offset(76f, 24f))
                drawCircle(Color(0xFF1E2024), 17f, Offset(76f, 24f), style = Stroke(3f))
                label(tm, f.value, 76f, 30.5f, 18f, Color.White, weight = FontWeight.ExtraBold)
            }
        }
    }
}

// --- Sound ----------------------------------------------------------------------------------------------

@Composable
private fun Vinyl(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val spin by rememberSpin(2200, f.active)
    Split(f, look, m, 1f) {
        Vb(100f, 100f) {
            val c = Offset(50f, 50f)
            rotate(spin, c) {
                drawCircle(Color(0xFF0B0B0C), 47f, c)
                var r = 22f
                while (r < 46f) { drawCircle(Color.White.copy(alpha = 0.06f), r, c, style = Stroke(1f)); r += 2.4f }
                drawArc(Color.White.copy(alpha = 0.18f), 200f, 40f, false, Offset(14f, 14f), Size(72f, 72f), style = Stroke(3f))
                val art = f.art
                if (art != null) clipPath(Shapes.vinylLabel) { drawImage(art, dstOffset = IntOffset(33, 33), dstSize = IntSize(34, 34)) }
                else drawCircle(Brush.linearGradient(listOf(Color(0xFFFF7A59), Color(0xFFC58AF9), Color(0xFF5AD0FF)), Offset(33f, 33f), Offset(67f, 67f)), 17f, c)
                drawCircle(Color.White.copy(alpha = 0.7f), 2.5f, Offset(50f, 42f))
                drawCircle(Color(0xFF111111), 2f, c)
            }
            // The tonearm moves in along the song.
            rotate(16f + frac(f.fraction) * 20f, Offset(90f, 10f)) {
                drawLine(Color(0xFFC9CED5), Offset(90f, 10f), Offset(90f, 60f), 2.4f, cap = StrokeCap.Round)
                drawRoundRect(Color(0xFF9AA0A6), Offset(86f, 58f), Size(8f, 10f), CornerRadius(1.5f))
            }
            drawCircle(Color(0xFF9AA0A6), 5f, Offset(90f, 10f))
        }
    }
}

@Composable
private fun Cassette(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val spin by rememberSpin(1800, f.active)
    Stack(f, look, m, header = false, foot = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaceCaption(f, look, m, Modifier.weight(1f))
            FaceActions(f, look, m, small = true)
        }
    }) {
        Vb(160f, 100f) { tm ->
            val fr = frac(f.fraction)
            drawRoundRect(Color(0xFF26272B), Offset(2f, 4f), Size(156f, 92f), CornerRadius(8f))
            drawRoundRect(Color(0xFF0E0E10), Offset(2f, 4f), Size(156f, 92f), CornerRadius(8f), style = Stroke(2f))
            drawRoundRect(Color(0xFFE8EAED), Offset(12f, 11f), Size(136f, 54f), CornerRadius(4f))
            drawRect(look.accent, Offset(12f, 11f), Size(136f, 7f))
            drawRect(look.accent2, Offset(12f, 18f), Size(136f, 3f))
            clipPathRect(14f, 22f, 132f, 12f) { label(tm, f.value, 18f, 31f, 9f, Color(0xFF111111), align = 0f) }
            drawRoundRect(Color(0xFF141416), Offset(42f, 34f), Size(76f, 22f), CornerRadius(11f))
            // Tape winds from the left reel onto the right one as the song plays.
            drawCircle(Color(0xFF5A3A22), 9f + 16f * (1f - fr), Offset(58f, 44f))
            drawCircle(Color(0xFF5A3A22), 9f + 16f * fr, Offset(102f, 44f))
            listOf(58f, 102f).forEach { x ->
                val c = Offset(x, 44f)
                rotate(spin, c) {
                    drawCircle(Color(0xFFE8EAED), 7.5f, c)
                    for (a in 0 until 360 step 60) drawCircle(Color(0xFF333333), 1.2f, polarPoint(c, 5f, a.toFloat()))
                }
                drawCircle(Color(0xFF333333), 2f, c)
            }
            drawPath(Shapes.cassetteFoot, Color(0xFF1C1D20))
            drawCircle(Color.Black, 3f, Offset(62f, 86f))
            drawCircle(Color.Black, 3f, Offset(98f, 86f))
        }
    }
}

@Composable
private fun CoverArt(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val over = look.copy(ink = Color.White, dim = Color.White.copy(alpha = 0.8f), accent = Color.White, accent2 = Color.White, onAccent = Color(0xFF111111), fill = Color.White.copy(alpha = 0.18f))
    Box(modifier = Modifier.fillMaxSize()) {
        val art = f.art
        if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(Brush.sweepGradient(listOf(Color(0xFFFF7A59), Color(0xFFC58AF9), Color(0xFF5AD0FF), Color(0xFFFF7A59)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.2f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(1.5f))) {
            FaceHeader(f, over, m)
            Spacer(Modifier.weight(1f))
            FaceText(f.value, over, m.sp(min(m.h * 0.15f, m.w * 0.09f)), weight = FontWeight.ExtraBold)
            FaceCaption(f, over, m)
            f.fraction?.let { FaceProgress(it, over, m, Modifier.fillMaxWidth()) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FaceActions(f, over, m, small = true) }
        }
    }
}

@Composable
private fun VolumeKnob(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 1f) {
    Vb(100f, 100f) {
        val c = Offset(50f, 50f)
        val fr = frac(f.fraction)
        for (i in 0 until 27) {
            val on = i / 26f <= fr
            drawCircle(if (on) look.accent else look.track, if (on) 2.3f else 1.6f, polarPoint(c, 45f, -135f + 270f * i / 26f))
        }
        drawCircle(Color(0xFF0B0C0E), 35f, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF5A606A), Color(0xFF14161A)), Offset(38f, 35f), 60f), 32f, c)
        for (a in 0 until 360 step 10) drawLine(Color.Black.copy(alpha = 0.5f), polarPoint(c, 32f, a.toFloat()), polarPoint(c, 30f, a.toFloat()), 1f)
        val a = -135f + 270f * fr
        drawLine(look.accent, polarPoint(c, 12f, a), polarPoint(c, 27f, a), 3.4f, cap = StrokeCap.Round)
    }
}

@Composable
private fun LevelMeter(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    // The bounce runs only while something plays with the volume up; otherwise
    // the bars stand still instead of redrawing 160 cells every frame.
    val loop = if (f.active && frac(f.fraction) > 0f) rememberLoop(1400) else null
    Stack(f, look, m, foot = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaceValue(f, look, m, min(m.h * 0.16f, m.w * 0.11f), Modifier.weight(1f))
            FaceActions(f, look, m, small = true)
        }
    }) {
        Vb(196f, 76f) {
            val fr = frac(f.fraction)
            val t = loop?.value ?: 0f
            val pattern = listOf(0.5f, 0.8f, 0.95f, 0.7f, 0.6f, 0.85f, 0.9f, 0.65f, 0.5f, 0.72f, 0.8f, 0.55f, 0.42f, 0.6f, 0.46f, 0.3f)
            pattern.forEachIndexed { c, p ->
                // A gentle bounce so the meter looks alive; silent when the volume is off.
                val wobble = if (fr > 0f) 0.85f + 0.15f * sin((t * 2f * PI.toFloat()) + c * 0.9f) else 0f
                val on = (p * (0.3f + 0.7f * fr) * wobble * 10).roundToInt()
                for (i in 0 until 10) {
                    val col = if (i < 6) LampGreen else if (i < 8) Color(0xFFFFC107) else Color(0xFFFF3B30)
                    drawRoundRect(if (i < on) col else look.track, Offset(4f + c * 12f, 70f - i * 7f), Size(9f, 5f), CornerRadius(1f))
                }
            }
        }
    }
}

@Composable
private fun Elevation(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val e = f.elevation ?: return
    // The scrim over a tile in edit mode takes the taps first (DashboardGrid), so this only fires on a tile at rest.
    Box(Modifier.fillMaxSize().then(e.onZoom?.let { Modifier.clickable(onClick = it) } ?: Modifier)) {
    Stack(f, look, m, foot = {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(m.dp(3f))) {
            FaceText(e.now, look, m.sp(min(m.h * 0.18f, m.w * 0.12f)), Modifier.weight(1f, fill = false), weight = FontWeight.ExtraBold, fit = true)
            // The no-chart hint is drawn once, in the middle of the chart area; the span caption is for a chart.
            if (e.points != null) Box(Modifier.weight(1f), contentAlignment = Alignment.BottomEnd) { FaceText(e.caption, look, m.caption, color = look.dim, align = TextAlign.End, fit = true) }
        }
    }) {
        val pts = e.points
        if (pts == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FaceText(e.caption, look, m.caption, color = look.dim, align = TextAlign.Center, maxLines = 3) }
        } else {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) { drawProfile(pts, look) }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    FaceText(e.max, look, m.label, color = look.dim)
                    FaceText(e.min, look, m.label, color = look.dim)
                }
            }
        }
    }
    }
}

/** The line of [pts] (metres, oldest first; the newest at the right) over a faint fill, with a dot on the newest. */
private fun DrawScope.drawProfile(pts: List<Float>, look: FaceLook) {
    if (pts.size < 2) return
    val lo = pts.min()
    // At least 20 m of scale, so a flat road stays flat instead of magnifying GPS noise.
    val span = max(pts.max() - lo, 20f)
    val pad = size.height * 0.08f
    val h = size.height - 2f * pad
    val stroke = max(1.5f, size.height * 0.022f)
    fun x(i: Int) = size.width * i / (pts.size - 1)
    fun y(v: Float) = pad + h * (1f - (v - lo) / span)
    val line = Path().apply { pts.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
    val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
    for (k in 0..2) drawLine(look.track, Offset(0f, pad + h * k / 2f), Offset(size.width, pad + h * k / 2f), 1f)
    drawPath(area, Brush.verticalGradient(listOf(look.accent.copy(alpha = 0.35f), look.accent.copy(alpha = 0.02f))))
    drawPath(line, look.accent, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(look.accent, stroke * 2f, Offset(size.width, y(pts.last())))
}

// --- Car care --------------------------------------------------------------------------------------------

@Composable
private fun FilterCells(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 108f / 78f) {
    Vb(108f, 78f) {
        val n = 20
        val sooty = (frac(f.fraction) * n).roundToInt()
        Shapes.filterCells.forEachIndexed { k, (center, hex) ->
            val dark = k < sooty
            drawPath(hex, if (dark) Color(0xFF2A2A2A) else look.fill)
            drawPath(hex, if (dark) Color(0xFF555555) else look.accent, style = Stroke(if (dark) 0.6f else 0.9f))
            if (dark) {
                drawCircle(Color(0xFF111111), 1.4f, Offset(center.x - 2f, center.y + 1f))
                drawCircle(Color(0xFF111111), 1f, Offset(center.x + 2.5f, center.y - 2f))
            }
        }
    }
}

@Composable
private fun Hourglass(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 0.6f) {
    Vb(60f, 100f) {
        val fr = frac(f.fraction)
        val glass = Shapes.hourglass
        drawPath(glass, look.track)
        val topY = 12f + 36f * fr
        val botY = 90f - 30f * fr
        clipPath(glass) {
            drawRect(Sand, Offset(0f, topY), Size(60f, max(0f, 50f - topY)))
            drawPath(Path().apply { moveTo(8f, 90f); lineTo(30f, botY); lineTo(52f, 90f); close() }, Sand)
            if (fr < 1f) drawLine(Sand, Offset(30f, 50f), Offset(30f, botY), 1.2f)
        }
        drawPath(glass, look.dim, style = Stroke(1f))
        drawRoundRect(Color(0xFF8D6E63), Offset(6f, 3f), Size(48f, 7f), CornerRadius(2f))
        drawRoundRect(Color(0xFF8D6E63), Offset(6f, 90f), Size(48f, 7f), CornerRadius(2f))
    }
}

@Composable
private fun Leaf(f: WidgetFace, look: FaceLook, m: FaceMetrics) = Split(f, look, m, 100f / 104f) {
    Vb(100f, 104f) {
        val fr = frac(f.fraction)
        val leaf = Shapes.leaf
        val vein = Color(0xFF1B5E20)
        drawPath(leaf, look.track)
        clipPath(leaf) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF9CCC65), Color(0xFF2E7D32)), 6f, 94f), Offset(0f, 94f - 88f * fr), Size(100f, 100f))
        }
        drawPath(leaf, vein, style = Stroke(1.4f))
        drawLine(vein, Offset(50f, 100f), Offset(50f, 12f), 1.2f)
        listOf(70f to 30f, 56f to 70f, 42f to 34f, 80f to 68f).forEach { (y, x) ->
            drawLine(vein, Offset(50f, y), Offset(x, y - 16f), 1.2f)
        }
    }
}
