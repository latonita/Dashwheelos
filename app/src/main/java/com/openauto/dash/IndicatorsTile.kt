package com.openauto.dash

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.rememberVehicle
import kotlinx.coroutines.delay
import kotlin.math.min

/*
 * The Indicators tile: the instrument panel's tell-tales as lamps (turn signals, parking, low and high beam, fog,
 * hazards, parking brake, seat belt), each lit in its conventional colour and dim when off or not heard yet. The
 * state mapping is IndicatorsModel.kt; the shapes are drawn here, our own outlines in the ISO 2575 style. The dim
 * colour follows the theme ([DashColors.Muted]); the lit colours are fixed, because the meaning is the colour.
 */

private val LitGreen = Color(0xFF2ECC71)
private val LitBlue = Color(0xFF3D9BFF)
private val LitAmber = Color(0xFFFFB300)
private val LitRed = Color(0xFFFF4D4D)

private fun TellColour.lit(): Color = when (this) {
    TellColour.GREEN -> LitGreen
    TellColour.BLUE -> LitBlue
    TellColour.AMBER -> LitAmber
    TellColour.RED -> LitRed
}

private val Tell.labelRes: Int
    get() = when (this) {
        Tell.LEFT -> R.string.tell_left
        Tell.RIGHT -> R.string.tell_right
        Tell.HAZARD -> R.string.tell_hazard
        Tell.PARKING -> R.string.tell_parking
        Tell.LOW_BEAM -> R.string.tell_low_beam
        Tell.HIGH_BEAM -> R.string.tell_high_beam
        Tell.FRONT_FOG -> R.string.tell_front_fog
        Tell.REAR_FOG -> R.string.tell_rear_fog
        Tell.HANDBRAKE -> R.string.tell_handbrake
        Tell.SEAT_BELT -> R.string.tell_seat_belt
    }

/** Rows and columns of equal cells that give the biggest cell for [count] lamps in a [w] x [h] box. */
internal fun tellGrid(count: Int, w: Float, h: Float): Pair<Int, Int> {
    var best = 1 to count
    var bestCell = -1f
    for (cols in 1..count) {
        val rows = (count + cols - 1) / cols
        val cell = min(w / cols, h / rows)
        if (cell > bestCell + 0.01f) { bestCell = cell; best = rows to cols }
    }
    return best
}

@Composable
internal fun IndicatorsCard(modifier: Modifier = Modifier) {
    val states = tellStates(
        TellInputs(
            indicators = rememberVehicle(K.INDICATORS),
            lights = rememberVehicle(K.LIGHTS),
            handbrake = rememberVehicle(K.HANDBRAKE),
            beltUnfastened = rememberVehicle(K.DRIVER_BELT)
        )
    )
    val tells = Tell.entries
    val latches = remember { tells.filter { it.blinks }.associateWith { BlinkLatch() } }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    for ((tell, latch) in latches) latch.observe(states[tell] == TellState.ON, now)
    val anyEngaged = latches.any { (tell, latch) -> latch.engaged(states[tell] == TellState.ON, now) }
    LaunchedEffect(anyEngaged) {
        // A clock only while a signal is flashing: an idle tile redraws nothing.
        while (anyEngaged) {
            now = SystemClock.elapsedRealtime()
            delay(BLINK_PERIOD_MS / 8)
        }
        now = SystemClock.elapsedRealtime()
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(DashSpace.Md),
            verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                // A title only where it leaves the lamps room.
                if (maxHeight >= 150.dp) {
                    Column(Modifier.fillMaxSize()) {
                        TileHeader(stringResource(R.string.indicators_title))
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Lamps(tells, states, latches, now, this@BoxWithConstraints.maxWidth, this@BoxWithConstraints.maxHeight - 28.dp)
                        }
                    }
                } else Lamps(tells, states, latches, now, maxWidth, maxHeight)
            }
        }
    }
}

@Composable
private fun Lamps(tells: List<Tell>, states: Map<Tell, TellState>, latches: Map<Tell, BlinkLatch>, now: Long, w: Dp, h: Dp) {
    val (rows, cols) = tellGrid(tells.size, w.value, h.value)
    val cell = min(w.value / cols, h.value / rows)
    val icon = min(cell * 0.86f, 72f)
    val gap = ((cell - icon)).coerceAtLeast(0f).dp
    Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (c in 0 until cols) {
                    val tell = tells.getOrNull(r * cols + c)
                    if (tell == null) Box(Modifier.size(icon.dp)) else {
                        val state = states.getValue(tell)
                        val engaged = latches[tell]?.engaged(state == TellState.ON, now) ?: false
                        TellIcon(tell, lampLit(tell, state, engaged, now), icon.dp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TellIcon(tell: Tell, lit: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val colour = if (lit) tell.colour.lit() else DashColors.Muted.copy(alpha = 0.4f)
    val label = stringResource(tell.labelRes)
    Canvas(modifier = modifier.size(size).semantics { contentDescription = label }) {
        if (lit) drawCircle(colour.copy(alpha = 0.16f), radius = this.size.minDimension / 2)
        drawTell(tell, colour)
    }
}

private fun Path.start(o: Offset) = moveTo(o.x, o.y)
private fun Path.to(o: Offset) = lineTo(o.x, o.y)

/** One tell-tale, drawn in a unit square scaled to the canvas. */
private fun DrawScope.drawTell(tell: Tell, colour: Color) {
    val s = size.minDimension
    val w = s * 0.07f
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    val line = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun ray(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(colour, p(x0, y0), p(x1, y1), strokeWidth = w, cap = StrokeCap.Round)
    when (tell) {
        Tell.LEFT, Tell.RIGHT -> {
            // A block arrow: the head on the pointing side.
            val d = if (tell == Tell.LEFT) -1f else 1f
            fun x(v: Float) = 0.5f + d * v
            val path = Path().apply {
                start(p(x(0.42f), 0.5f))
                to(p(x(0.02f), 0.2f))
                to(p(x(0.02f), 0.36f))
                to(p(x(-0.42f), 0.36f))
                to(p(x(-0.42f), 0.64f))
                to(p(x(0.02f), 0.64f))
                to(p(x(0.02f), 0.8f))
                close()
            }
            drawPath(path, colour)
        }
        Tell.HAZARD -> {
            fun tri(inset: Float) = Path().apply {
                start(p(0.5f, 0.12f + inset))
                to(p(0.92f - inset, 0.84f - inset * 0.6f))
                to(p(0.08f + inset, 0.84f - inset * 0.6f))
                close()
            }
            drawPath(tri(0f), colour, style = line)
            drawPath(tri(0.2f), colour, style = line)
        }
        Tell.LOW_BEAM, Tell.HIGH_BEAM, Tell.FRONT_FOG, Tell.REAR_FOG -> {
            // A lamp with the dome behind it and rays in front (the rear fog lamp faces the other way).
            val rear = tell == Tell.REAR_FOG
            fun mx(x: Float) = if (rear) 1f - x else x
            val body = Path().apply {
                start(p(mx(0.42f), 0.2f))
                cubicTo(p(mx(1.0f), 0.2f).x, 0.2f * s, p(mx(1.0f), 0.8f).x, 0.8f * s, p(mx(0.42f), 0.8f).x, 0.8f * s)
                close()
            }
            drawPath(body, colour)
            // Low beam's rays slope down, high beam's are level, the fogs' level ones are crossed by a wave.
            val drop = if (tell == Tell.LOW_BEAM) 0.1f else 0f
            for (y in floatArrayOf(0.26f, 0.42f, 0.58f, 0.74f)) ray(mx(0.34f), y, mx(0.04f), y + drop)
            if (tell == Tell.FRONT_FOG || tell == Tell.REAR_FOG) {
                val wave = Path().apply {
                    start(p(mx(0.2f), 0.08f))
                    quadraticBezierTo(p(mx(0.36f), 0.29f).x, 0.29f * s, p(mx(0.2f), 0.5f).x, 0.5f * s)
                    quadraticBezierTo(p(mx(0.04f), 0.71f).x, 0.71f * s, p(mx(0.2f), 0.92f).x, 0.92f * s)
                }
                drawPath(wave, colour, style = line)
            }
        }
        Tell.PARKING -> {
            // The sidelights: two small half-lamps back to back, each with short rays to the outside.
            for (side in floatArrayOf(-1f, 1f)) {
                fun x(v: Float) = 0.5f + side * v
                val body = Path().apply {
                    start(p(x(0.2f), 0.28f))
                    cubicTo(x(-0.02f) * s, 0.28f * s, x(-0.02f) * s, 0.72f * s, x(0.2f) * s, 0.72f * s)
                    close()
                }
                drawPath(body, colour)
                for (y in floatArrayOf(0.34f, 0.5f, 0.66f)) ray(x(0.27f), y, x(0.44f), y)
            }
        }
        Tell.HANDBRAKE -> {
            // The parking brake: a ring with a P inside it, between brackets.
            drawArc(colour, 135f, 90f, false, p(0.02f, 0.15f), Size(s * 0.4f, s * 0.7f), style = line)
            drawArc(colour, -45f, 90f, false, p(0.58f, 0.15f), Size(s * 0.4f, s * 0.7f), style = line)
            drawCircle(colour, radius = s * 0.24f, center = p(0.5f, 0.5f), style = line)
            val letter = Path().apply {
                start(p(0.44f, 0.64f))
                to(p(0.44f, 0.36f))
                to(p(0.52f, 0.36f))
                cubicTo(0.62f * s, 0.36f * s, 0.62f * s, 0.52f * s, 0.52f * s, 0.52f * s)
                to(p(0.44f, 0.52f))
            }
            drawPath(letter, colour, style = line)
        }
        Tell.SEAT_BELT -> {
            // A seated figure with the belt across it.
            drawCircle(colour, radius = s * 0.09f, center = p(0.42f, 0.2f))
            val body = Path().apply {
                start(p(0.42f, 0.36f))
                to(p(0.42f, 0.62f))
                to(p(0.68f, 0.62f))
                to(p(0.74f, 0.86f))
            }
            drawPath(body, colour, style = line)
            ray(0.3f, 0.34f, 0.7f, 0.58f)
        }
    }
}
