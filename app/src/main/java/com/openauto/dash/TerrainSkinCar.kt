package com.openauto.dash

import android.location.Location
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Terrain skin, the car (the look is in TerrainSkin.kt): the RANGE tile,
 * given the room, is the hero of the design. The driver's car stands on its
 * pad in the contour landscape with its contact shadow, live in 3D when the
 * car pack brought a model (Car3D.kt), else the pack's picture, else a drawn
 * MPV. A problem the car reports (its own warnings, the engine's fault codes,
 * gathered as the Car problems tile does) is pinned on the part: in 3D the
 * part glows and carries its label; in 2D a smoked label with a red glow
 * hangs over the car. Over the stage, the GPS line in mono (altitude,
 * position, heading, only with a fix) and the range with its amber bar.
 * Nothing here moves by itself: the car holds still, driving or not.
 */

/** The stage needs this much room; under it the range is a card. */
private val HERO_MIN_W: Dp = 400.dp
private val HERO_MIN_H: Dp = 250.dp

/** Height of the GPS line over the stage. */
private val HEADER_H = 40.dp

/**
 * Fuel and range. With the room, the stage: the car on its pad with the GPS
 * line and the range over it. Otherwise a smoked card: RANGE · DIESEL, the
 * range large and light, the amber bar. Fuel not learned yet, on a small
 * tile, falls back to the standard tile (on the smoke), which explains how.
 */
@Composable
internal fun TerrainRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stage = maxWidth >= HERO_MIN_W && maxHeight >= HERO_MIN_H
        when {
            stage -> TerrainHero(env, fuel, maxWidth, maxHeight)
            fuel == null -> Box(Modifier.fillMaxSize().trCard(editing = env.editing)) { StandardSkinnedTile(item, env) }
            else -> Box(Modifier.fillMaxSize().graphicsLayer().trCard(editing = env.editing)) {
                val pad = trPad(this@BoxWithConstraints.maxWidth, this@BoxWithConstraints.maxHeight)
                TrRangeBlock(fuel, Modifier.fillMaxSize().padding(pad), alignEnd = false, numMax = 80f, fill = true)
            }
        }
    }
}

/** RANGE · DIESEL (the car's fuel), the range large and light with its unit, the amber bar under it. */
@Composable
private fun TrRangeBlock(fuel: FuelInfo?, modifier: Modifier, alignEnd: Boolean, numMax: Float, fill: Boolean) {
    val units = LocalUnits.current
    val car by CarProfileStore.profile.collectAsState()
    val low = fuel != null && fuel.percent <= SKIN_LOW_FUEL_PCT
    val label = trJoin(stringResource(R.string.terrain_range), stringResource(car.fuel.labelRes))
    BoxWithConstraints(modifier) {
        val numSize = trFit("888", maxWidth * 0.6f, (maxHeight - 50.dp).coerceAtLeast(30.dp), 30f, numMax)
        Column(
            Modifier.then(if (fill) Modifier.fillMaxSize() else Modifier),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
            verticalArrangement = if (fill) Arrangement.SpaceBetween else Arrangement.Top
        ) {
            TrLabel(label)
            Row {
                TrLine(
                    fuel?.let { units.distance(it.rangeKm).toString() } ?: "--",
                    trText(numSize, if (fuel == null) DashColors.Muted else if (low) DashColors.Warning else DashColors.TextPrimary, FontWeight.Light, figure = true),
                    Modifier.alignByBaseline()
                )
                Spacer(Modifier.width(6.dp))
                TrLine(units.distanceUnit, trText((numSize * 0.4f).coerceAtLeast(16f), DashColors.TextSecondary, FontWeight.Light), Modifier.alignByBaseline())
            }
            if (!fill) Spacer(Modifier.height(10.dp))
            TrFuelBar(fuel?.percent ?: 0, low, Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier.width(this@BoxWithConstraints.maxWidth.coerceAtMost(260.dp))).height(10.dp))
        }
    }
}

/** The amber bar of the fuel left: amber to sand on a faint track. */
@Composable
private fun TrFuelBar(percent: Int, low: Boolean, modifier: Modifier) {
    val a = DashColors.Accent
    val b = if (low) DashColors.Accent else DashColors.Accent2
    val track = DashColors.TextPrimary.copy(alpha = if (DashColors.Light) 0.1f else 0.14f)
    Spacer(
        modifier.cachedDraw(percent, a, b, track) {
            val r = CornerRadius(size.height / 2f)
            val fw = size.width * (percent / 100f).coerceIn(0f, 1f)
            val brush = Brush.horizontalGradient(listOf(a, b), 0f, fw.coerceAtLeast(1f))
            onDrawBehind {
                drawRoundRect(track, cornerRadius = r)
                if (fw > 0f) drawRoundRect(brush, size = Size(fw.coerceAtLeast(size.height), size.height), cornerRadius = r)
            }
        }
    )
}

// --- The stage -----------------------------------------------------------------------

/**
 * The hero: the GPS line at the top left, the range at the top right, the
 * car on its pad filling the rest (a little left of the middle, so the range
 * keeps its corner). While arranging, a dashed outline says the tile moves.
 */
@Composable
private fun TerrainHero(env: SkinTileEnv, fuel: FuelInfo?, w: Dp, h: Dp) {
    val problems = rememberTerrainProblems()
    val model = rememberCar3DModel()
    val said = stringResource(R.string.terrain_car_cd)
    val rangeW = (w * 0.34f).coerceIn(200.dp, 300.dp)
    Box(Modifier.fillMaxSize().semantics { contentDescription = said }) {
        if (env.editing) EditOutline(Modifier.fillMaxSize())
        val carModifier = Modifier
            .fillMaxSize()
            .padding(top = HEADER_H, end = w * 0.2f)
        if (model != null) Hero3D(model, problems, carModifier)
        else Hero2D(problems, carModifier)
        TerrainHeader(Modifier.padding(start = 6.dp, top = 6.dp).fillMaxWidth().padding(end = rangeW))
        TrRangeBlock(fuel, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 6.dp).width(rangeW), alignEnd = true, numMax = (h.value * 0.13f).coerceIn(36f, 60f), fill = false)
    }
}

/** The tile's dashed outline while the dashboard is being arranged (the stage has no card of its own). */
@Composable
private fun EditOutline(modifier: Modifier) {
    val rim = trRim
    Spacer(
        modifier.cachedDraw(rim) {
            val stroke = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)))
            val ink = rim.copy(alpha = (rim.alpha * 2.4f).coerceAtMost(0.9f))
            val r = CornerRadius(TR_CARD.toPx())
            onDrawBehind { drawRoundRect(ink, Offset(1f, 1f), Size(size.width - 2f, size.height - 2f), r, style = stroke) }
        }
    )
}

/**
 * The car in 3D (Car3D.kt) over its contact shadow: turned to the most
 * serious problem, whose part glows and carries its label, or the whole car
 * when all is well. A tap on a problem's chip turns it there.
 */
@Composable
private fun Hero3D(model: java.io.File, problems: List<MapProblem>, modifier: Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    val in3d = remember(problems, car.driverOnRight) {
        problems.filter { it.number > 0 }.map { Problem3D(it.number, Car3DSpots.of(it.spot, car.driverOnRight), it.label, it.serious) }
    }
    // The chip picked (0: the whole car); at first the most serious problem.
    var picked by remember { mutableStateOf<Int?>(null) }
    val selected = when (val p = picked) {
        0 -> null
        null -> in3d.maxByOrNull { it.serious }
        else -> in3d.firstOrNull { it.number == p } ?: in3d.maxByOrNull { it.serious }
    }
    Box(modifier) {
        ContactShadow(Modifier.fillMaxSize(), centreY = 0.5f, wide = true)
        Car3DProblems(model, in3d, selected, interactive = false, onPick = { picked = it ?: 0 }, modifier = Modifier.fillMaxSize(), chips = in3d.isNotEmpty())
    }
}

/**
 * The soft shadow the car stands on and the pad's rim round it: a dark
 * ellipse under the car (light brown by day), the pad's sand hairline wider
 * still. [centreY]: where the car's middle is, as a share of the height;
 * [wide]: the 3D car seen from three quarters, whose footprint is deeper.
 */
@Composable
private fun ContactShadow(modifier: Modifier, centreY: Float, wide: Boolean) {
    val light = DashColors.Light
    val rimInk = trRim
    val effects = DashColors.Effects
    Spacer(
        modifier.cachedDraw(light, rimInk, effects, centreY, wide) {
            val w = size.width
            val h = size.height
            // The 3D car fills its view: its wheels sit about a quarter of its width under its middle.
            val c = if (wide) Offset(w / 2f, min(h * 0.9f, h * centreY + w / 4.4f)) else Offset(w / 2f, h * centreY)
            val rx = w * (if (wide) 0.4f else 0.46f)
            val ry = rx * (if (wide) 0.2f else 0.09f)
            val shadow = Brush.radialGradient(
                0f to Color.Black.copy(alpha = if (light) 0.28f else 0.75f), 0.55f to Color.Black.copy(alpha = if (light) 0.1f else 0.3f), 1f to Color.Transparent,
                center = c, radius = rx
            )
            val padRx = rx * 1.35f
            val padRy = padRx * (if (wide) 0.24f else 0.14f)
            val glow = if (effects != DashEffects.NONE && !light) Brush.radialGradient(
                listOf(Color(0x1FFFAD33), Color.Transparent), c, padRx
            ) else null
            val padStroke = Stroke(1f * density)
            onDrawBehind {
                if (glow != null) scale(1f, padRy / padRx, c) { drawCircle(glow, padRx, c) }
                drawOval(rimInk, Offset(c.x - padRx, c.y - padRy), Size(padRx * 2f, padRy * 2f), style = padStroke)
                scale(1f, ry / rx, c) { drawCircle(shadow, rx, c) }
            }
        }
    )
}

/**
 * The car in 2D: the pack's three-quarter or side picture, else the drawn
 * MPV, on its shadow, in one offscreen layer drawn when the car changes; the
 * most serious problem as a smoked label with a red glow hanging over the
 * part (the back, the front or the middle of the car).
 */
@Composable
private fun Hero2D(problems: List<MapProblem>, modifier: Modifier) {
    val mine by MyCarLook.shown.collectAsState()
    val style by MyCarLook.style.collectAsState()
    val view = mine?.portrait
    val aspect = view?.aspect ?: PANO_CAR_ASPECT
    val light = DashColors.Light
    val ink = DashColors.TextPrimary
    val warn = DashColors.Warning
    val top = problems.maxByOrNull { it.serious }
    BoxWithConstraints(modifier) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val car = remember(wPx, hPx, aspect) { heroCarRect(Size(wPx, hPx), aspect) }
        ContactShadow(Modifier.fillMaxSize(), centreY = car.bottom / hPx.coerceAtLeast(1f) - 0.015f, wide = false)
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .cachedDraw(view, style, light, ink, warn, car) {
                    onDrawBehind {
                        if (view != null) drawCarView(view, style, car, ink, warn)
                        else drawPanoCar(car, light)
                    }
                }
        )
        if (top != null) {
            // Where on the car: the drawn MPV has its nose to the right; a picture's way round is not known, so its middle.
            val along = when {
                view != null -> 0.5f
                top.spot.isRear() -> 0.06f
                top.spot.isFront() -> 0.94f
                else -> 0.5f
            }
            val at = Offset(car.left + car.width * along, car.top + car.height * 0.42f)
            ProblemPin(top, problems.size - 1, at, Modifier.fillMaxSize())
        }
    }
}

/** Where the car stands in a box of [size]: as large as fits 88 % of the width and 78 % of the height, its wheels low in the box. */
private fun heroCarRect(size: Size, aspect: Float): Rect {
    val cw = min(size.width * 0.88f, size.height * 0.78f * aspect)
    val ch = cw / aspect
    val bottom = size.height * 0.9f
    return Rect(Offset((size.width - cw) / 2f, bottom - ch), Size(cw, ch))
}

private fun CarSpot.isRear(): Boolean = this in setOf(
    CarSpot.REAR_LIGHT_LEFT, CarSpot.REAR_LIGHT_RIGHT, CarSpot.REAR_LIGHTS, CarSpot.THIRD_BRAKE, CarSpot.REAR_FOGS,
    CarSpot.REVERSING, CarSpot.EXHAUST, CarSpot.BOOT, CarSpot.REAR_SENSORS, CarSpot.TYRE_RL, CarSpot.TYRE_RR, CarSpot.PARKING_BRAKE
)

private fun CarSpot.isFront(): Boolean = this in setOf(
    CarSpot.HEADLIGHT_LEFT, CarSpot.HEADLIGHT_RIGHT, CarSpot.HEADLIGHTS, CarSpot.FRONT_FOGS, CarSpot.ENGINE, CarSpot.BATTERY,
    CarSpot.WASHER, CarSpot.GEARBOX, CarSpot.FRONT_SENSORS, CarSpot.TYRE_FL, CarSpot.TYRE_FR
)

/**
 * A problem pinned on the car in 2D: a glow on the part (red to stop, amber
 * for the garage, blue to know), a stem rising from it and the smoked label
 * at its top, with how many more problems there are.
 */
@Composable
private fun ProblemPin(p: MapProblem, more: Int, at: Offset, modifier: Modifier) {
    val tone = when (p.serious) {
        2 -> DashColors.Critical
        1 -> DashColors.Warning
        else -> DashColors.Secondary
    }
    val glowK = DashColors.Effects.scale.coerceAtLeast(0.35f)
    val stem = 70.dp
    Box(modifier) {
        Spacer(
            Modifier
                .fillMaxSize()
                .cachedDraw(tone, glowK, at) {
                    val stemPx = stem.toPx()
                    val r = 7.dp.toPx()
                    val glow = Brush.radialGradient(listOf(tone.copy(alpha = 0.55f * glowK), tone.copy(alpha = 0.18f * glowK), Color.Transparent), at, r * 6f)
                    val line = Brush.verticalGradient(listOf(tone.copy(alpha = 0.9f), tone.copy(alpha = 0f)), at.y - stemPx, at.y)
                    onDrawBehind {
                        drawCircle(glow, r * 6f, at)
                        drawLine(line, Offset(at.x, at.y - stemPx), at, 2.dp.toPx())
                        drawCircle(tone, r * 0.6f, at)
                    }
                }
        )
        val moreText = if (more > 0) androidx.compose.ui.platform.LocalContext.current.resources.getQuantityString(R.plurals.terrain_more_problems, more, more) else null
        Column(
            Modifier
                .layout { m, c ->
                    val pl = m.measure(c.copy(minWidth = 0, minHeight = 0))
                    val stemPx = stem.roundToPx()
                    val x = (at.x.roundToInt() - 16.dp.roundToPx()).coerceIn(0, (c.maxWidth - pl.width).coerceAtLeast(0))
                    val y = (at.y.roundToInt() - stemPx - pl.height).coerceAtLeast(0)
                    layout(c.maxWidth, c.maxHeight) { pl.place(x, y) }
                }
                .widthIn(max = 300.dp)
                .pinCard(tone)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            TrLine(p.label, trText(19f, DashColors.TextPrimary, FontWeight.SemiBold), maxLines = 2)
            if (moreText != null) TrLine(moreText, trText(15f, DashColors.TextSecondary))
        }
    }
}

/** The pin's label card: the smoke, a rim in the problem's colour and its glow at night. */
@Composable
private fun Modifier.pinCard(tone: Color): Modifier {
    val light = DashColors.Light
    val glowK = trGlow
    return cachedDraw(tone, light, glowK) {
        val r = CornerRadius(16.dp.toPx())
        val rim = Stroke(1.dp.toPx())
        onDrawBehind {
            if (glowK > 0f) {
                for (k in 1..3) {
                    val g = k * 6f * density
                    drawRoundRect(tone.copy(alpha = 0.06f * glowK), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(r.x + g))
                }
            }
            drawRoundRect(if (light) Color(0xF2FBF8F1) else Color(0xE6141610), cornerRadius = r)
            drawRoundRect(tone.copy(alpha = 0.6f), Offset(0.5f, 0.5f), Size(size.width - 1f, size.height - 1f), r, style = rim)
        }
    }
}

// --- The problems ----------------------------------------------------------------------

/**
 * The car's problems as the Car problems tile numbers them (FaultCodesTile):
 * the car's own messages first (the engine's warning left out once its fault
 * codes are read), then the engine's fault codes; each on its part of the car.
 */
@Composable
private fun rememberTerrainProblems(): List<MapProblem> {
    val ai by AiMechanic.state.collectAsState()
    val codes = ai.warningCodes
    val diagnosis = ai.diagnosis
    val carMessages = rememberCarMessages().let { all ->
        if (codes.isNullOrEmpty()) all else all.filter { it.code !in PsaWarnings.engineLamp }
    }
    val messageTexts = carMessages.map { carMessageText(it.code) }
    val codeSerious = if (diagnosis?.severity == Severity.STOP) 2 else 1
    val codeNames = codes.orEmpty().map { c ->
        diagnosis?.codes?.firstOrNull { it.code == c }?.meaning?.ifBlank { null } ?: ai.description(c) ?: ObdCodes.describe(c).localizedTitle()
    }
    return remember(carMessages, messageTexts, codes, codeSerious, codeNames) {
        carMessages.mapIndexedNotNull { i, m ->
            CarProblemMap.spotOf(m.code)?.let { spot -> MapProblem(spot, m.serious, messageTexts[i], number = i + 1) }
        } + codes.orEmpty().mapIndexedNotNull { i, code ->
            CarProblemMap.spotOfCode(code)?.let { spot -> MapProblem(spot, codeSerious, codeNames[i], number = carMessages.size + i + 1) }
        }
    }
}

// --- The GPS line --------------------------------------------------------------------------

/** What the GPS line shows, rounded so it changes only when the words do. */
internal data class TerrainFix(val altM: Int?, val latMin: Int, val north: Boolean, val lonMin: Int, val east: Boolean) {
    companion object {
        fun of(l: Location): TerrainFix = TerrainFix(
            altM = if (l.hasAltitude()) l.altitude.roundToInt() else null,
            latMin = (abs(l.latitude) * 60.0).toInt(), north = l.latitude >= 0.0,
            lonMin = (abs(l.longitude) * 60.0).toInt(), east = l.longitude >= 0.0
        )
    }
}

/** The 8-point compass sector of a heading in degrees: 0 north, 1 north-east ... 7 north-west. */
internal fun terrainCompass(deg: Float): Int = Math.floorMod(kotlin.math.floor((deg + 22.5f) / 45f).toInt(), 8)

/**
 * The GPS line in mono capitals, as in the design: ALT 146 M, the position
 * in degrees and minutes, HEADING NW, the values bright. Parts that don't fit
 * are left out from the end; with no fix there is no line.
 */
@Composable
private fun TerrainHeader(modifier: Modifier) {
    UseLocationFeed()
    val fix by remember { LocationFeed.location.map { it?.let(TerrainFix::of) }.distinctUntilChanged() }
        .collectAsState(initial = remember { LocationFeed.location.value?.let(TerrainFix::of) })
    val sector by remember { LocationFeed.headingDeg.map { it?.let(::terrainCompass) }.distinctUntilChanged() }
        .collectAsState(initial = remember { LocationFeed.headingDeg.value?.let(::terrainCompass) })
    val f = fix ?: return
    val units = LocalUnits.current
    val points = stringResource(R.string.terrain_compass).split(',').map { it.trim() }
    fun point(i: Int) = points.getOrElse(i) { "NESW"[i / 2].toString() }
    val base = trText(15f, trLabelInk, FontWeight.Normal, mono = true)
    val bright = SpanStyle(color = DashColors.TextPrimary, fontWeight = FontWeight.Medium)
    val altWord = stringResource(R.string.terrain_altitude)
    val headingWord = stringResource(R.string.terrain_heading)
    val locale = Locale.getDefault()
    TrFitRow(26.dp, modifier) {
        f.altM?.let { m ->
            val value = if (units.imperial) "${(m / UnitSystem.METRES_PER_FOOT).roundToInt()} FT" else "$m M"
            Text(buildAnnotatedString {
                append(altWord.uppercase(locale)); append(' ')
                withStyle(bright) { append(value) }
            }, style = base, maxLines = 1, softWrap = false)
        }
        val position = "${point(if (f.north) 0 else 4)} ${f.latMin / 60}°${(f.latMin % 60).toString().padStart(2, '0')}′  " +
            "${point(if (f.east) 2 else 6)} ${f.lonMin / 60}°${(f.lonMin % 60).toString().padStart(2, '0')}′"
        Text(position, style = base, maxLines = 1, softWrap = false)
        sector?.let { s ->
            Text(buildAnnotatedString {
                append(headingWord.uppercase(locale)); append(' ')
                withStyle(bright) { append(point(s)) }
            }, style = base, maxLines = 1, softWrap = false)
        }
    }
}
