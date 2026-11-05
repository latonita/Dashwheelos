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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The four cabins: one design per interior of a modern car's theme
 * variations, each a material with a layout drawn from that cabin's own
 * cluster. Lane view (midnight navy and champagne gold: the driver-assist
 * speedometer over the lane ahead), Trend (sage green: a trip computer's
 * chart of the last minutes), Pulse (carbon and crimson: a waveform lit
 * with the reading) and Contour (graphite and ice: one thin number over a
 * soft hill). Same [WidgetFace] as every generic design, so a widget with a
 * reading can wear them; the last three draw no rows, so the list widgets
 * are not offered them (WidgetDesign.appliesTo). Two widget-specific
 * pictures come with them: the tyre map
 * (the car from above, a pressure by each wheel) and the car outline (the
 * car in profile, its readings beside it).
 */

private fun String.upper() = uppercase(Locale.getDefault())

/** The cabins' header: the title in quiet capitals, a chevron at the end like a card that opens. */
@Composable
private fun CabinHeader(f: WidgetFace, look: FaceLook, m: FaceMetrics, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FaceText(f.title, look, m.label, Modifier.weight(1f), color = look.ink.copy(alpha = 0.86f), weight = look.labelWeight, letterSpacing = 0.02.em)
        if (trailing != null) trailing() else if (f.onClick != null) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = look.dim, modifier = Modifier.size(m.dp(8f).coerceIn(14.dp, 20.dp)))
        }
    }
}

/**
 * The headline as a number, for the charts: [WidgetFace.number], else the
 * leading number of the value, else the fraction in percent. Null while the
 * widget waits for its reading: its "--" comes with a fraction of 0, which is
 * not a 0 to chart.
 */
internal fun faceNumber(f: WidgetFace): Float? {
    if (f.idle) return null
    f.number?.takeIf { it.isFinite() }?.let { return it }
    if (!f.textValue) {
        val lead = Regex("-?\\d+(?:[.,]\\d+)?").find(f.value.replace(" ", "").replace(" ", ""))?.value
        lead?.replace(',', '.')?.toFloatOrNull()?.let { return it }
    }
    return f.fraction?.takeIf { it.isFinite() }?.let { it * 100f }
}

// --- Lane view (Luxury classic) ---------------------------------------------------------------

@Composable
internal fun LaneLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    // The details go beside the picture on a wide tile, when there are any.
    val side = m.wide && m.w >= 300f && (f.stats.isNotEmpty() || f.rows.isNotEmpty() || f.actions.isNotEmpty())
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(4f))
    ) {
        BoxWithConstraints(
            modifier = if (side) Modifier.fillMaxHeight().aspectRatio(0.9f, matchHeightConstraintsFirst = true) else Modifier.fillMaxSize()
        ) {
            // A 90 x 100 drawing scaled to fit, like the other pictures; overlays find their spot in the same units.
            val s = min(maxWidth.value / 90f, maxHeight.value / 100f)
            val ox = (maxWidth.value - 90f * s) / 2f
            val oy = (maxHeight.value - 100f * s) / 2f
            fun at(x: Float, y: Float) = Modifier.offset((ox + x * s).dp, (oy + y * s).dp)
            val fraction = rememberUpdatedState(f.fraction)
            val alert = f.alert
            Spacer(Modifier.fillMaxSize().drawWithCache { laneScene(look, alert, fraction) })
            // The number in the arc, the caption under the car between hash marks.
            Box(modifier = at(0f, 19f).width((90f * s).dp), contentAlignment = Alignment.TopCenter) {
                FaceValue(f, look, m, 19f * s, stacked = true)
            }
            if (f.caption.isNotEmpty()) {
                Box(modifier = at(22f, 93.5f).width((46f * s).dp), contentAlignment = Alignment.Center) {
                    FaceText(f.caption.upper(), look, m.sp(max(4.2f * s, 9f)), color = if (f.alert) look.warn else look.accent2,
                        weight = FontWeight.Medium, letterSpacing = 0.14.em, align = TextAlign.Center)
                }
            }
            // Left of the lane, the first detail under its name and with its unit. Not a bare
            // number in a ring: beside the speed, the coolant's 90 read as the speed limit.
            // On a wide tile the details are all listed beside the picture already.
            val detail = f.stats.firstOrNull() ?: f.gauges.getOrNull(1)?.let { FaceStat(it.label, "${it.value} ${it.unit}".trim()) }
            val r = 8f * s
            if (detail != null && !side && s >= 1.6f) {
                FaceStatBlock(detail, look, m, at(1f, 60f).width((24f * s).dp))
            }
            // Right of it, the main control, or the widget's own mark.
            val action = f.actions.firstOrNull { it.primary } ?: f.actions.firstOrNull()
            if (!side && s >= 1.6f) {
                val btn = (r * 2f).coerceAtLeast(DashSize.Touch.value)
                val mod = at(89f - btn / s, 60f).size(btn.dp)
                if (action != null) {
                    Box(
                        modifier = mod.clip(CircleShape).background(if (action.primary) look.accent else look.fill)
                            .border(1.dp, look.accent.copy(alpha = 0.6f), CircleShape)
                            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(action.icon, contentDescription = action.label, tint = if (action.primary) look.onAccent else look.ink,
                            modifier = Modifier.size((r * 1.1f).coerceAtLeast(18f).dp))
                    }
                } else {
                    Box(modifier = mod.clip(CircleShape).border(1.dp, look.dim.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(f.icon, contentDescription = null, tint = look.dim, modifier = Modifier.size((r * 1.0f).coerceAtLeast(14f).dp))
                    }
                }
            }
            if (!side) {
                FaceText(f.title.upper(), look, m.sp(max(3.8f * s, 9f)), at(0f, 1f).width((90f * s).dp), color = look.dim,
                    weight = look.labelWeight, letterSpacing = 0.2.em, align = TextAlign.Center)
            }
        }
        if (side) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(2.4f), Alignment.CenterVertically)) {
                CabinHeader(f, look, m)
                GoldRule(look, m)
                when {
                    f.stats.isNotEmpty() -> f.stats.take(if (f.actions.isEmpty() && m.h >= 200f) 3 else 2).forEach { FaceStatBlock(it, look, m) }
                    f.rows.isNotEmpty() -> FaceRows(f, look, m, if (m.h >= 200f) 3 else 2)
                }
                FaceActions(f, look, m, small = true)
            }
        }
    }
}

/** A gold hairline that fades out to the right. */
@Composable
private fun GoldRule(look: FaceLook, m: FaceMetrics) {
    Spacer(
        Modifier.fillMaxWidth(0.7f).height(1.dp).background(Brush.horizontalGradient(listOf(look.accent, look.accent.copy(alpha = 0f))))
    )
}

/**
 * The assistance cluster in a 90 x 100 box: a 240° arc with fine ticks lit
 * gold up to the reading, and under it the lane seen from the driver's seat,
 * edges converging on the horizon, three chevrons ahead of the car lighting
 * one by one as the reading climbs, and the car itself from behind.
 */
private fun CacheDrawScope.laneScene(look: FaceLook, alert: Boolean, fraction: State<Float?>): DrawResult {
    val s = min(size.width / 90f, size.height / 100f)
    val ox = (size.width - 90f * s) / 2f
    val oy = (size.height - 100f * s) / 2f
    fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
    val c = p(45f, 36f)
    val r = 29f * s
    val arcTopLeft = Offset(c.x - r, c.y - r)
    val arcSize = Size(r * 2f, r * 2f)
    val ticks = List(25) { i ->
        val a = -120f + i * 10f
        val major = i % 4 == 0
        Triple(polarPoint(c, r - (if (major) 4.5f else 2.5f) * s, a), polarPoint(c, r - 1f * s, a), major)
    }
    val lit = if (alert) look.warn else look.accent
    val arcBrush = Brush.sweepGradient(listOf(look.accent2, lit, lit, look.accent2), c)
    val thin = Stroke(1.dp.toPx())
    val bold = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round)
    val halo = Stroke(7.dp.toPx(), cap = StrokeCap.Round)
    // The lane: two edges converging on a vanishing point, drawn from the horizon down.
    val vp = p(45f, 48f)
    fun edge(xBottom: Float, y: Float): Offset {
        val b = p(xBottom, 100f)
        val t = (p(0f, y).y - vp.y) / (b.y - vp.y)
        return Offset(vp.x + (b.x - vp.x) * t, p(0f, y).y)
    }
    val top = 66f
    val lane = Path().apply {
        moveTo(edge(8f, top)); lineTo(edge(82f, top)); lineTo(edge(82f, 100f)); lineTo(edge(8f, 100f)); close()
    }
    val laneFill = Brush.verticalGradient(listOf(Color.Transparent, look.accent.copy(alpha = 0.16f)), p(0f, top).y, p(0f, 100f).y)
    val edgeBrush = Brush.verticalGradient(listOf(look.accent.copy(alpha = 0f), look.accent), p(0f, top).y, p(0f, 100f).y)
    val shoulderBrush = Brush.verticalGradient(listOf(look.dim.copy(alpha = 0f), look.dim.copy(alpha = 0.5f)), p(0f, top).y, p(0f, 100f).y)
    val chevrons = listOf(68f, 72.5f, 77f).map { y ->
        val l = edge(22f, y)
        val rr = edge(68f, y)
        val tip = Offset(c.x, l.y - 2.2f * s)
        Path().apply { moveTo(l); lineTo(tip); lineTo(rr) }
    }
    val chevronStroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    // The car from behind.
    val body = Rect(p(35f, 85f), p(55f, 94.5f))
    val cabin = Path().apply { moveTo(p(38.5f, 80f)); lineTo(p(51.5f, 80f)); lineTo(p(54f, 86f)); lineTo(p(36f, 86f)); close() }
    val glass = Path().apply { moveTo(p(39.6f, 81.2f)); lineTo(p(50.4f, 81.2f)); lineTo(p(52.2f, 85f)); lineTo(p(37.8f, 85f)); close() }
    val bodyBrush = Brush.verticalGradient(listOf(lerp(look.dim, look.ink, 0.35f), look.dim.copy(alpha = 0.55f)), body.top, body.bottom)
    val hash = List(5) { i -> i }
    return onDrawBehind {
        val v = fraction.value
        val fr = fraction01(v)
        // Arc and ticks.
        drawArc(look.track, 150f, 240f, false, arcTopLeft, arcSize, style = thin)
        ticks.forEach { (a, b, major) -> drawLine(if (major) look.dim else look.dim.copy(alpha = 0.45f), a, b, if (major) 1.4f * s / 2f + 1f else 1f) }
        if (v != null && fr > 0f) {
            drawArc(lit.copy(alpha = 0.18f), 150f, 240f * fr, false, arcTopLeft, arcSize, style = halo)
            drawArc(arcBrush, 150f, 240f * fr, false, arcTopLeft, arcSize, style = bold)
            drawCircle(look.ink, 2.2.dp.toPx(), polarPoint(c, r, -120f + 240f * fr))
        }
        // Lane, shoulders and edges.
        drawPath(lane, laneFill)
        drawLine(shoulderBrush, edge(-6f, top + 4f), edge(-6f, 100f), 1f)
        drawLine(shoulderBrush, edge(96f, top + 4f), edge(96f, 100f), 1f)
        drawLine(edgeBrush, edge(8f, top), edge(8f, 100f), 1.8.dp.toPx(), StrokeCap.Round)
        drawLine(edgeBrush, edge(82f, top), edge(82f, 100f), 1.8.dp.toPx(), StrokeCap.Round)
        // Chevrons ahead: the far one lights last.
        val on = if (v == null) 0 else (fr * 3f + 0.34f).toInt().coerceIn(0, 3)
        chevrons.forEachIndexed { i, path ->
            val litHere = (2 - i) < on
            drawPath(path, if (litHere) lit else look.track, style = chevronStroke)
        }
        // Car.
        drawOval(Color.Black.copy(alpha = 0.35f), p(33f, 92.5f), Size(24f * s, 4f * s))
        drawPath(cabin, bodyBrush)
        drawPath(glass, Color.Black.copy(alpha = 0.45f))
        drawRoundRect(bodyBrush, body.topLeft, body.size, CornerRadius(2.4f * s))
        drawRoundRect(look.accent2, p(36f, 87f), Size(4.2f * s, 1.5f * s), CornerRadius(0.8f * s))
        drawRoundRect(look.accent2, p(49.8f, 87f), Size(4.2f * s, 1.5f * s), CornerRadius(0.8f * s))
        drawRect(Color.Black.copy(alpha = 0.3f), p(41f, 90.5f), Size(8f * s, 2.2f * s))
        // Hash marks either side of the caption.
        hash.forEach { i ->
            val x = 8f + i * 2.6f
            drawLine(look.dim.copy(alpha = 0.3f + i * 0.12f), p(x, 98.5f), p(x + 1.6f, 95.5f), 1.2f * s / 2f + 0.8f)
            val xr = 82f - i * 2.6f
            drawLine(look.dim.copy(alpha = 0.3f + i * 0.12f), p(xr, 98.5f), p(xr - 1.6f, 95.5f), 1.2f * s / 2f + 0.8f)
        }
    }
}

private fun Path.moveTo(o: Offset) = moveTo(o.x, o.y)
private fun Path.lineTo(o: Offset) = lineTo(o.x, o.y)

// --- Trend (Eco leaf) -------------------------------------------------------------------------

/**
 * Recent readings per widget, sampled every [STEP_MS], so the Trend design
 * has a line to draw. Kept for the process only: a restart starts a fresh
 * chart. Keyed by the widget's title, so two tiles of one widget share it.
 */
internal object FaceTrends {
    const val STEP_MS = 10_000L
    const val MAX_POINTS = 48
    private val series = HashMap<String, SnapshotStateList<Float>>()
    private val lastAt = HashMap<String, Long>()

    fun of(key: String): SnapshotStateList<Float> = series.getOrPut(key) { mutableStateListOf() }

    /** Adds [value] unless one came in under half a step ago (another tile of the same widget). */
    fun record(key: String, value: Float, now: Long) {
        val last = lastAt[key]
        if (last != null && now - last < STEP_MS / 2) return
        lastAt[key] = now
        val list = of(key)
        list.add(value)
        while (list.size > MAX_POINTS) list.removeAt(0)
    }
}

@Composable
internal fun TrendLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val key = f.title
    val latest = rememberUpdatedState(faceNumber(f))
    LaunchedEffect(key) {
        while (true) {
            latest.value?.let { FaceTrends.record(key, it, System.currentTimeMillis()) }
            delay(FaceTrends.STEP_MS)
        }
    }
    val points = remember(key) { FaceTrends.of(key) }
    val chart = m.h >= 120f
    // Without a reading nothing is added to the chart, and the last point is no longer "now".
    val live = latest.value != null
    val captionOnTop = f.caption.isNotEmpty() && m.w >= 240f
    val keysOnTop = ((m.w - 200f) / 56f).toInt().coerceIn(0, 2)
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2f))) {
        CabinHeader(f, look, m) {
            if (captionOnTop) {
                FaceText(f.caption, look, m.label, Modifier.padding(start = 6.dp), color = if (f.alert) look.warn else look.dim)
            }
            if (keysOnTop > 0 && f.actions.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                FaceActions(f.copy(actions = keyActions(f.actions, keysOnTop)), look, m, small = true)
            }
        }
        // The reading, then its details side by side, all on one baseline row.
        val stats = f.stats.take(when {
            m.w >= 460f -> 3
            m.w >= 300f -> 2
            m.w >= 220f -> 1
            else -> 0
        })
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(m.dp(5f))) {
            FaceValue(f, look, m, if (chart) min(m.h * 0.16f, m.w * 0.11f) else min(m.h * 0.34f, m.w * 0.18f), Modifier.weight(1.2f, fill = false))
            stats.forEach { stat ->
                Column(modifier = Modifier.weight(1f)) {
                    FaceText(stat.label, look, m.sp(max(m.u * 4.6f, 10f)), color = look.dim, weight = look.labelWeight)
                    FaceText(stat.value, look, m.sp(max(m.u * 6.8f, 12f)), weight = FontWeight.Medium, family = look.numFont)
                }
            }
        }
        if (chart) {
            val alert = f.alert
            Spacer(Modifier.weight(1f).fillMaxWidth().drawAreaChart(points, look, alert, m, live))
            // A narrow tile has no room on top for why the reading is missing, nor for the button that gets it.
            if (f.idle && (!captionOnTop || keysOnTop == 0)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(3f))) {
                    Box(Modifier.weight(1f)) { if (!captionOnTop) FaceCaption(f, look, m) }
                    if (keysOnTop == 0) FaceActions(f.copy(actions = keyActions(f.actions, 1)), look, m, size = DashSize.TouchPrimary)
                }
            }
        } else {
            FaceCaption(f, look, m)
        }
    }
}

/**
 * The area chart: a few gridlines with their values, the smoothed line and a
 * soft fill under it, the last point ringed while it is the [live] reading.
 */
@Composable
private fun Modifier.drawAreaChart(points: List<Float>, look: FaceLook, alert: Boolean, m: FaceMetrics, live: Boolean): Modifier {
    val tm = rememberTextMeasurer(cacheSize = 8)
    val labelSize = max(m.u * 4.4f, MIN_TEXT_DP)
    return this.drawWithCache {
        val axisW = 30.dp.toPx()
        val labelStyle = TextStyle(color = look.dim, fontSize = labelSize.sp)
        val line = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val color = if (alert) look.warn else look.accent
        onDrawBehind {
            val w = size.width
            val h = size.height
            if (h < 8f) return@onDrawBehind
            val left = axisW
            val bottom = h - 2.dp.toPx()
            val topY = 4.dp.toPx()
            val pts = points.toList()
            val lo0 = pts.minOrNull() ?: 0f
            val hi0 = pts.maxOrNull() ?: 1f
            // Round the scale out to whole steps so the gridlines carry plain numbers.
            val span = max(hi0 - lo0, max(abs(hi0) * 0.1f, 1f))
            val step = niceStep(span / 3f)
            val lo = kotlin.math.floor(lo0 / step) * step
            val hi = max(lo + step * 3f, kotlin.math.ceil(hi0 / step) * step)
            fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - topY)
            var g = lo
            while (g <= hi + step * 0.01f) {
                val gy = y(g)
                drawLine(look.track, Offset(left, gy), Offset(w, gy), 1f)
                // An empty chart keeps its lines but has no scale to put on them.
                if (pts.isNotEmpty()) {
                    val text = if (step >= 1f) g.roundToInt().toString() else String.format(Locale.getDefault(), "%.1f", g)
                    val layout = tm.measure(text, labelStyle)
                    if (gy - layout.size.height / 2f >= 0f) drawText(layout, topLeft = Offset(left - layout.size.width - 4.dp.toPx(), gy - layout.size.height / 2f))
                }
                g += step
            }
            // Ticks along the bottom, one a minute.
            val perMinute = (60_000L / FaceTrends.STEP_MS).toInt()
            var t = 0
            while (t < FaceTrends.MAX_POINTS) {
                val tx = left + (w - left) * t / (FaceTrends.MAX_POINTS - 1f)
                drawLine(look.dim.copy(alpha = 0.5f), Offset(tx, bottom), Offset(tx, bottom - 3.dp.toPx()), 1f)
                t += perMinute
            }
            if (pts.isEmpty()) return@onDrawBehind
            // Newest point on the right edge; older ones to its left, one step each.
            val stepX = (w - left) / (FaceTrends.MAX_POINTS - 1f)
            val xs = List(pts.size) { i -> w - (pts.size - 1 - i) * stepX }
            val curve = Path()
            if (pts.size == 1) {
                curve.moveTo(left, y(pts[0])); curve.lineTo(w, y(pts[0]))
            } else {
                curve.moveTo(xs[0], y(pts[0]))
                for (i in 1 until pts.size) {
                    val x0 = xs[i - 1]; val y0 = y(pts[i - 1])
                    val x1 = xs[i]; val y1 = y(pts[i])
                    val mid = (x0 + x1) / 2f
                    curve.cubicTo(mid, y0, mid, y1, x1, y1)
                }
            }
            val area = Path().apply {
                addPath(curve)
                lineTo(w, bottom)
                lineTo(if (pts.size == 1) left else xs[0], bottom)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.04f)), topY, bottom))
            drawPath(curve, Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), look.accent2), left, w), style = line)
            if (!live) return@onDrawBehind
            val end = Offset(w - 4.dp.toPx(), y(pts.last()))
            drawCircle(color.copy(alpha = 0.3f), 6.dp.toPx(), end)
            drawCircle(look.ink, 3.dp.toPx(), end)
        }
    }
}

/** 1, 2 or 5 times a power of ten, at least [raw]. */
private fun niceStep(raw: Float): Float {
    if (raw <= 0f || !raw.isFinite()) return 1f
    val mag = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw.toDouble()))).toFloat()
    val n = raw / mag
    return mag * when {
        n <= 1f -> 1f
        n <= 2f -> 2f
        n <= 5f -> 5f
        else -> 10f
    }
}

// --- Pulse (Cyber sport) ----------------------------------------------------------------------

@Composable
internal fun PulseLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val fraction = rememberUpdatedState(f.fraction)
    val phase = rememberSpin(1600, f.active)
    val alert = f.alert
    val seed = f.title.hashCode()
    val tall = m.h >= 150f
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(2.4f))) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(4f))) {
            if (tall && m.w >= 220f) {
                val side = min(m.h * 0.46f, m.w * 0.3f)
                PulseArt(f, look, Modifier.size(side.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(1.2f))) {
                FaceText(f.title, look, m.label, color = look.accent, weight = FontWeight.SemiBold, letterSpacing = 0.04.em)
                FaceValue(f, look, m, min(m.h * 0.30f, m.w * 0.16f))
                FaceCaption(f, look, m)
            }
        }
        Spacer(Modifier.fillMaxWidth().height(m.dp(14f).coerceIn(16.dp, 44.dp)).drawWithCache { waveform(look, alert, f.active, fraction, phase, seed) })
        if (f.actions.isNotEmpty() || (f.stats.isNotEmpty() && m.w >= 260f)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val stat = f.stats.firstOrNull()
                if (stat != null && m.w >= 260f) FaceText(stat.value, look, m.body, color = look.dim, family = look.numFont)
                Spacer(Modifier.weight(1f))
                FaceActions(f, look, m, small = !tall)
                Spacer(Modifier.weight(1f))
                val last = f.stats.getOrNull(1)
                if (last != null && m.w >= 260f) FaceText(last.value, look, m.body, color = look.dim, family = look.numFont)
            }
        }
    }
}

/** The cover, or the widget's mark on a crimson-to-magenta block with a glow under it. */
@Composable
private fun PulseArt(f: WidgetFace, look: FaceLook, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val art = f.art
    Box(
        modifier = modifier
            .drawBehind {
                drawRoundRect(look.accent.copy(alpha = 0.35f), Offset(-3.dp.toPx(), 2.dp.toPx()), Size(size.width + 6.dp.toPx(), size.height + 4.dp.toPx()), CornerRadius(14.dp.toPx()))
            }
            .clip(shape)
            .background(Brush.linearGradient(listOf(look.accent, look.accent2)))
            .border(1.dp, look.ink.copy(alpha = 0.25f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Spacer(Modifier.fillMaxSize().drawWithCache {
                // A waveform stamped on the block, like the cover in the cluster.
                val n = 17
                val bw = size.width / (n * 1.6f)
                onDrawBehind {
                    for (i in 0 until n) {
                        val hgt = size.height * (0.12f + 0.55f * abs(sin(i * 1.7f + 0.4f)) * (1f - abs(i - n / 2f) / n))
                        val x = size.width * 0.12f + i * bw * 1.4f
                        drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(x, (size.height - hgt) / 2f), Size(bw, hgt), CornerRadius(bw / 2f))
                    }
                }
            })
            Icon(f.icon, contentDescription = null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.fillMaxSize(0.5f))
        }
    }
}

/**
 * A row of thin bars shaped like a sound wave, lit crimson to magenta up to
 * the reading (all of it, dimmer, with no reading). While playing, the lit
 * bars breathe with [phase].
 */
private fun CacheDrawScope.waveform(look: FaceLook, alert: Boolean, active: Boolean, fraction: State<Float?>, phase: State<Float>, seed: Int): DrawResult {
    val gap = 2.dp.toPx()
    val bw = 2.4.dp.toPx()
    val n = max(8, ((size.width + gap) / (bw + gap)).toInt())
    val x0 = (size.width - (n * bw + (n - 1) * gap)) / 2f
    val h = size.height
    val cy = h / 2f
    // A fixed wave per widget: louder in the middle phrases, quiet at the ends.
    val shape = List(n) { i ->
        val t = i / (n - 1f)
        val env = 0.35f + 0.65f * sin(PI.toFloat() * t)
        val grain = 0.55f + 0.45f * abs(sin(i * 0.9f + seed * 0.001f) * sin(i * 0.37f + 1.3f))
        (env * grain).coerceIn(0.08f, 1f)
    }
    val lit = if (alert) Brush.horizontalGradient(listOf(look.warn, look.warn)) else Brush.horizontalGradient(listOf(look.accent, look.accent2), x0, size.width - x0)
    val corner = CornerRadius(bw / 2f)
    return onDrawBehind {
        val v = fraction.value
        val upTo = if (v == null) n else (fraction01(v) * n).roundToInt()
        val a = phase.value * PI.toFloat() / 180f
        for (i in 0 until n) {
            val x = x0 + i * (bw + gap)
            val on = i < upTo
            val breathe = if (on && active) 0.8f + 0.2f * sin(a * 4f + i * 0.6f) else 1f
            val bh = max(bw, shape[i] * h * breathe)
            if (on) drawRoundRect(lit, Offset(x, cy - bh / 2f), Size(bw, bh), corner, alpha = if (v == null) 0.55f else 1f)
            else drawRoundRect(look.track, Offset(x, cy - bh / 2f), Size(bw, bh), corner)
        }
        if (v != null && upTo in 1 until n) {
            val x = x0 + upTo * (bw + gap) - gap / 2f
            drawLine(look.ink, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
        }
    }
}

// --- Contour (Nordic minimal) -----------------------------------------------------------------

@Composable
internal fun ContourLayout(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    // Without a reading the hill keeps its resting shape, with no crest marked on it.
    val fraction = rememberUpdatedState(f.fraction.takeIf { !f.idle })
    val alert = f.alert
    Box(modifier = Modifier.fillMaxSize()) {
        // The hill runs under everything, from the bottom-left corner up to the reading.
        Spacer(Modifier.fillMaxSize().drawWithCache { contourHill(look, alert, fraction, m.pad.dp.toPx()) })
        Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(1.6f))) {
            CabinHeader(f, look, m)
            FaceValue(f, look, m, min(m.h * 0.34f, m.w * 0.2f))
            if (m.h >= 150f) FaceCaption(f, look, m)
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val stat = f.stats.firstOrNull()
                Row(modifier = Modifier.weight(1f)) {
                    if (stat != null) {
                        FaceText(stringResource(R.string.design_label_value, stat.label, ""), look, m.body, color = look.dim)
                        FaceText(stat.value, look, m.body, weight = FontWeight.Medium, family = look.numFont)
                    } else if (m.h < 150f) {
                        FaceCaption(f, look, m)
                    }
                }
                // Every key where there is room (up to three, icons only), else the main one with its name.
                if (f.actions.size > 1 && m.w >= 240f) {
                    Row(horizontalArrangement = Arrangement.spacedBy(m.dp(2f))) {
                        f.actions.take(3).forEach { OutlinePill(it, look, m, labelled = false) }
                    }
                } else {
                    val action = f.actions.firstOrNull { it.primary } ?: f.actions.firstOrNull()
                    if (action != null) OutlinePill(action, look, m, labelled = m.w >= 260f)
                }
            }
        }
    }
}

/** A hairline pill with the control's icon and, where it fits, its name. */
@Composable
private fun OutlinePill(a: FaceAction, look: FaceLook, m: FaceMetrics, labelled: Boolean) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.Touch)
            .clip(shape)
            .border(1.dp, look.dim.copy(alpha = 0.6f), shape)
            .background(if (a.primary) look.fill else Color.Transparent)
            .clickable(enabled = a.enabled, role = Role.Button, onClick = a.onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(a.icon, contentDescription = if (labelled) null else a.label, tint = look.ink.copy(alpha = if (a.enabled) 1f else 0.4f), modifier = Modifier.size(18.dp))
        if (labelled) FaceText(a.label, look, m.sp(max(m.u * 5f, 11f)), color = look.ink, weight = FontWeight.Medium)
    }
}

/**
 * A soft ridge across the tile: low on the left, rising to a crest whose
 * height and place follow the reading, then easing off to the right edge.
 * Filled with a mist that fades to nothing, the crest marked with a dot.
 */
private fun CacheDrawScope.contourHill(look: FaceLook, alert: Boolean, fraction: State<Float?>, inset: Float): DrawResult {
    val w = size.width
    val h = size.height
    val base = h * 0.92f
    val line = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
    val color = if (alert) look.warn else look.accent
    val ridge = Path()
    val area = Path()
    val echo = Path()
    return onDrawBehind {
        val v = fraction.value
        val fr = if (v == null) 0.5f else fraction01(v)
        val crestX = w * (0.45f + 0.35f * fr)
        val crestY = base - (h * 0.18f + h * 0.42f * fr)
        val startY = base - h * 0.08f
        val endY = base - h * (0.20f + 0.2f * fr)
        ridge.rewind()
        ridge.moveTo(0f, startY)
        ridge.cubicTo(crestX * 0.45f, startY, crestX * 0.6f, crestY, crestX, crestY)
        ridge.cubicTo(crestX + (w - crestX) * 0.4f, crestY, w - (w - crestX) * 0.35f, endY, w, endY)
        area.rewind()
        area.addPath(ridge)
        area.lineTo(w, h); area.lineTo(0f, h); area.close()
        // A fainter ridge behind, a little lower and to the left, for depth.
        echo.rewind()
        echo.moveTo(0f, startY + h * 0.03f)
        echo.cubicTo(crestX * 0.3f, startY, crestX * 0.45f, crestY + h * 0.12f, crestX * 0.78f, crestY + h * 0.12f)
        echo.cubicTo(crestX + (w - crestX) * 0.2f, crestY + h * 0.12f, w * 0.9f, base, w, base)
        drawPath(echo, look.dim.copy(alpha = 0.25f), style = Stroke(1.dp.toPx()))
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)), crestY, h))
        drawPath(ridge, Brush.horizontalGradient(listOf(look.accent2.copy(alpha = 0.3f), color, look.accent2.copy(alpha = 0.5f))), style = line)
        if (v != null) {
            drawLine(color.copy(alpha = 0.35f), Offset(crestX, crestY), Offset(crestX, h - inset * 0.3f), 1f)
            drawCircle(look.ink, 3.dp.toPx(), Offset(crestX, crestY))
        }
    }
}

// --- Material backdrops ---------------------------------------------------------------------------

/** The cabins' own decorations, drawn behind the content (see FaceSurface). */
internal fun DrawScope.drawCabinDecoration(look: FaceLook) {
    val w = size.width
    val h = size.height
    when (look.decoration) {
        LookDecoration.PINSTRIPE -> {
            // A second gold hairline inset from the rim, and a satin sheen across the top.
            val inset = 4.dp.toPx()
            val r = (look.radius.toPx() - inset).coerceAtLeast(2f)
            drawRoundRect(look.accent.copy(alpha = 0.18f), Offset(inset, inset), Size(w - inset * 2f, h - inset * 2f), CornerRadius(r), style = Stroke(1f))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent), 0f, h * 0.45f))
        }
        LookDecoration.LEAF -> {
            // A pale leaf curling out of the bottom-right corner, with its veins.
            val s = min(w, h) * 0.42f
            withTransform({ translate(w - s * 0.92f, h - s * 0.98f) }) {
                val leaf = Path().apply {
                    moveTo(s * 0.05f, s * 0.95f)
                    cubicTo(s * 0.1f, s * 0.35f, s * 0.55f, s * 0.05f, s * 0.98f, s * 0.02f)
                    cubicTo(s * 0.95f, s * 0.5f, s * 0.6f, s * 0.92f, s * 0.05f, s * 0.95f)
                    close()
                }
                drawPath(leaf, Brush.linearGradient(listOf(look.accent.copy(alpha = 0.26f), look.accent2.copy(alpha = 0.08f)), Offset(0f, s), Offset(s, 0f)))
                val spine = Path().apply { moveTo(s * 0.05f, s * 0.95f); quadraticTo(s * 0.45f, s * 0.5f, s * 0.92f, s * 0.08f) }
                drawPath(spine, look.accent2.copy(alpha = 0.3f), style = Stroke(1.2.dp.toPx()))
                for (i in 1..4) {
                    val t = i / 5f
                    val base = Offset(s * (0.05f + 0.85f * t), s * (0.95f - 0.87f * t))
                    drawLine(look.accent2.copy(alpha = 0.18f), base, Offset(base.x + s * 0.12f, base.y + s * 0.02f), 1f)
                    drawLine(look.accent2.copy(alpha = 0.18f), base, Offset(base.x - s * 0.02f, base.y - s * 0.12f), 1f)
                }
            }
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), Offset(w * 0.2f, 0f), max(w, h) * 0.7f))
        }
        LookDecoration.EDGE_LIGHT -> {
            // Carbon twill, then a crimson-to-magenta light along the bottom edge and bright corner brackets.
            val step = 5.dp.toPx()
            var x = -h
            while (x < w) {
                drawLine(Color.White.copy(alpha = 0.03f), Offset(x, 0f), Offset(x + h, h), strokeWidth = step / 2f)
                x += step
            }
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, look.accent.copy(alpha = 0.16f)), h * 0.6f, h), Offset(0f, h * 0.6f), Size(w, h * 0.4f))
            val edge = 2.dp.toPx()
            drawLine(Brush.horizontalGradient(listOf(look.accent, look.accent2)), Offset(w * 0.08f, h - edge / 2f), Offset(w * 0.92f, h - edge / 2f), edge)
            val b = min(w, h) * 0.12f
            val t = 2.5.dp.toPx()
            val ins = 5.dp.toPx()
            drawLine(look.accent, Offset(ins, ins + b), Offset(ins, ins), t, StrokeCap.Square)
            drawLine(look.accent, Offset(ins, ins), Offset(ins + b, ins), t, StrokeCap.Square)
            drawLine(look.accent2, Offset(w - ins, h - ins - b), Offset(w - ins, h - ins), t, StrokeCap.Square)
            drawLine(look.accent2, Offset(w - ins, h - ins), Offset(w - ins - b, h - ins), t, StrokeCap.Square)
        }
        else -> Unit
    }
}

// --- Tyre map (made for the tyres) ------------------------------------------------------------

/** One wheel's callout: the pressure large, and the trouble (if any) under it. */
@Composable
private fun WheelCallout(row: FaceRow?, look: FaceLook, m: FaceMetrics, end: Boolean) {
    val align = if (end) Alignment.End else Alignment.Start
    Column(horizontalAlignment = align) {
        // "Avant gauche" is wider than the side column on most tiles: two lines and a shrink rather than "AVANT G…".
        FaceText((row?.title ?: "").upper(), look, m.sp(max(m.u * 4.4f, 9f)), color = look.dim, weight = look.labelWeight, letterSpacing = 0.04.em,
            maxLines = 2, fit = true, align = if (end) TextAlign.End else TextAlign.Start)
        val detail = row?.detail.orEmpty()
        val problem = detail.substringBeforeLast(" · ", "")
        val pressure = detail.substringAfterLast(" · ").ifEmpty { "--" }
        val tint = if (row?.alert == true) look.warn else look.ink
        Row(verticalAlignment = Alignment.Bottom) {
            FaceText(pressure.substringBefore(' '), look, m.sp(max(m.u * 11f, 15f)), color = tint, weight = look.numWeight.coerceAtLeast(FontWeight.SemiBold), family = look.numFont, glow = true)
            val unit = pressure.substringAfter(' ', "")
            if (unit.isNotEmpty()) FaceText(" $unit", look, m.sp(max(m.u * 5f, 10f)), color = look.dim, modifier = Modifier.padding(bottom = 2.dp))
        }
        row?.sub?.let { FaceText(it, look, m.sp(max(m.u * 4.8f, 10f)), color = look.dim) }
        if (problem.isNotEmpty()) FaceText(problem, look, m.sp(max(m.u * 4.8f, 10f)), color = look.warn, weight = FontWeight.Medium)
    }
}

@Composable
internal fun TyreMap(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val wheels = f.wheels ?: List(4) { f.rows.getOrNull(it) }
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(1.6f))) {
        FaceHeader(f, look, m)
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = m.dp(2f)), verticalArrangement = Arrangement.SpaceBetween) {
                WheelCallout(wheels.getOrNull(0), look, m, end = false)
                WheelCallout(wheels.getOrNull(2), look, m, end = false)
            }
            val alerts = wheels.map { it?.alert == true }
            val mine by MyCarLook.shown.collectAsState()
            val style by MyCarLook.style.collectAsState()
            val top = mine?.top?.takeIf { it.wheels.size == 4 }
            if (top != null) {
                // The driver's own car (MyCarLook.kt), with room either side for the leader lines; never so wide the callouts get cut.
                Canvas(modifier = Modifier.weight(2f).fillMaxHeight().padding(horizontal = m.dp(2f))) {
                    myCarFromAbove(top, style, look, alerts)
                }
            } else {
                Canvas(modifier = Modifier.fillMaxHeight().aspectRatio(0.62f).padding(horizontal = m.dp(2f))) { carFromAbove(look, alerts) }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = m.dp(2f)),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                WheelCallout(wheels.getOrNull(1), look, m, end = true)
                WheelCallout(wheels.getOrNull(3), look, m, end = true)
            }
        }
        if (m.h >= 170f) FaceCaption(f, look, m, Modifier.fillMaxWidth(), align = TextAlign.Center)
    }
}

/** How much of the Tyre map's car column the driver's own car takes; the rest is for the leader lines. */
private const val MY_CAR_SHARE = 0.8f

/**
 * [carFromAbove] with the driver's own car ([MyCarLook]): the picture in its
 * style, and from each wheel's anchor point a leader line out to its callout.
 */
private fun DrawScope.myCarFromAbove(view: CarView, style: CarLookStyle, look: FaceLook, alerts: List<Boolean>) {
    val w = size.width
    val car = fitCar(view, Rect(Offset(w * (1f - MY_CAR_SHARE) / 2f, 0f), Size(w * MY_CAR_SHARE, size.height)))
    drawCarView(view, style, car, look.accent, look.warn)
    view.wheels.forEachIndexed { i, fraction ->
        val bad = alerts.getOrElse(i) { false }
        val at = car.at(fraction)
        val left = i % 2 == 0
        val to = if (left) 0f else w
        drawLine((if (bad) look.warn else look.dim).copy(alpha = 0.7f), at, Offset(to, at.y), 1.dp.toPx())
        if (bad) drawCircle(look.warn.copy(alpha = 0.35f), 9.dp.toPx(), at)
        drawCircle(if (bad) look.warn else look.accent, 3.dp.toPx(), at)
        drawCircle(if (bad) look.warn else look.accent, 2.dp.toPx(), Offset(to + (if (left) 2.dp.toPx() else -2.dp.toPx()), at.y))
    }
}

/** The car from above, nose up, with leader lines from each wheel out to its callout; a wheel in trouble glows [FaceLook.warn]. */
private fun DrawScope.carFromAbove(look: FaceLook, alerts: List<Boolean>) {
    val w = size.width
    val h = size.height
    val bodyL = w * 0.18f
    val bodyR = w * 0.82f
    val bw = bodyR - bodyL
    val top = h * 0.03f
    val bottom = h * 0.97f
    val bodyColor = Brush.horizontalGradient(listOf(look.dim.copy(alpha = 0.35f), look.ink.copy(alpha = 0.55f), look.dim.copy(alpha = 0.35f)), bodyL, bodyR)
    // Wheels first, so the body sits over their inner half.
    val wheelW = w * 0.2f
    val wheelH = h * 0.17f
    val wheelY = listOf(h * 0.13f, h * 0.13f, h * 0.70f, h * 0.70f)
    val wheelX = listOf(bodyL - wheelW * 0.55f, bodyR - wheelW * 0.45f, bodyL - wheelW * 0.55f, bodyR - wheelW * 0.45f)
    for (i in 0 until 4) {
        val bad = alerts.getOrElse(i) { false }
        val o = Offset(wheelX[i], wheelY[i])
        if (bad) drawRoundRect(look.warn.copy(alpha = 0.35f), o - Offset(3.dp.toPx(), 3.dp.toPx()), Size(wheelW + 6.dp.toPx(), wheelH + 6.dp.toPx()), CornerRadius(wheelW * 0.5f))
        drawRoundRect(if (bad) look.warn else Color(0xFF15171A), o, Size(wheelW, wheelH), CornerRadius(wheelW * 0.35f))
        // Leader line out to the callout's side.
        val cy = o.y + wheelH / 2f
        val left = i % 2 == 0
        val from = if (left) o.x else o.x + wheelW
        val to = if (left) 0f else w
        drawLine((if (bad) look.warn else look.dim).copy(alpha = 0.7f), Offset(from, cy), Offset(to, cy), 1.dp.toPx())
        drawCircle(if (bad) look.warn else look.accent, 2.dp.toPx(), Offset(to + (if (left) 2.dp.toPx() else -2.dp.toPx()), cy))
    }
    val body = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(bodyL, top, bodyR, bottom, CornerRadius(bw * 0.42f, bw * 0.55f)))
    }
    drawPath(body, bodyColor)
    drawPath(body, look.ink.copy(alpha = 0.35f), style = Stroke(1.dp.toPx()))
    // Bonnet crease, windscreen, roof, rear window.
    val glass = Color.Black.copy(alpha = 0.55f)
    val ws = Path().apply {
        moveTo(bodyL + bw * 0.12f, h * 0.36f); lineTo(bodyR - bw * 0.12f, h * 0.36f)
        lineTo(bodyR - bw * 0.2f, h * 0.27f); lineTo(bodyL + bw * 0.2f, h * 0.27f); close()
    }
    drawPath(ws, glass)
    drawRoundRect(look.ink.copy(alpha = 0.12f), Offset(bodyL + bw * 0.13f, h * 0.38f), Size(bw * 0.74f, h * 0.34f), CornerRadius(bw * 0.12f))
    val rw = Path().apply {
        moveTo(bodyL + bw * 0.14f, h * 0.74f); lineTo(bodyR - bw * 0.14f, h * 0.74f)
        lineTo(bodyR - bw * 0.22f, h * 0.83f); lineTo(bodyL + bw * 0.22f, h * 0.83f); close()
    }
    drawPath(rw, glass)
    drawLine(look.ink.copy(alpha = 0.2f), Offset(w / 2f, h * 0.07f), Offset(w / 2f, h * 0.24f), 1f)
    // Mirrors, head and tail lights.
    drawOval(look.dim.copy(alpha = 0.6f), Offset(bodyL - bw * 0.1f, h * 0.30f), Size(bw * 0.13f, h * 0.035f))
    drawOval(look.dim.copy(alpha = 0.6f), Offset(bodyR - bw * 0.03f, h * 0.30f), Size(bw * 0.13f, h * 0.035f))
    drawLine(look.accent2, Offset(bodyL + bw * 0.12f, top + h * 0.025f), Offset(bodyL + bw * 0.34f, top + h * 0.012f), 2.dp.toPx(), StrokeCap.Round)
    drawLine(look.accent2, Offset(bodyR - bw * 0.12f, top + h * 0.025f), Offset(bodyR - bw * 0.34f, top + h * 0.012f), 2.dp.toPx(), StrokeCap.Round)
    val tail = Color(0xFFE5484D)
    drawLine(tail, Offset(bodyL + bw * 0.1f, bottom - h * 0.02f), Offset(bodyL + bw * 0.3f, bottom - h * 0.008f), 2.dp.toPx(), StrokeCap.Round)
    drawLine(tail, Offset(bodyR - bw * 0.1f, bottom - h * 0.02f), Offset(bodyR - bw * 0.3f, bottom - h * 0.008f), 2.dp.toPx(), StrokeCap.Round)
}

// --- Car outline (made for the car's readings) -----------------------------------------------------

@Composable
internal fun CarOutline(f: WidgetFace, look: FaceLook, m: FaceMetrics) {
    val details: List<Pair<String, String>> = when {
        f.rows.isNotEmpty() -> f.rows.map { it.title to it.detail }
        else -> f.stats.map { it.label to it.value }
    }
    val doors = f.doors
    val side = m.w >= 280f && details.isNotEmpty()
    Column(modifier = Modifier.fillMaxSize().padding(m.pad.dp), verticalArrangement = Arrangement.spacedBy(m.dp(1.6f))) {
        CabinHeader(f, look, m)
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(3f))) {
            val mine by MyCarLook.shown.collectAsState()
            val style by MyCarLook.style.collectAsState()
            val own = mine?.side
            Canvas(modifier = Modifier.weight(if (side) 1.6f else 1f).fillMaxHeight()) {
                // The driver's own car (MyCarLook.kt): anything open or wrong turns it to the warning colour.
                if (own != null) drawCarView(own, style, fitCar(own, Rect(Offset.Zero, size)), look.accent, look.warn, f.alert || doors?.any { it } == true)
                else carProfile(look, doors, f.alert)
            }
            if (side) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(1.4f), Alignment.CenterVertically)) {
                    details.take(if (m.h >= 200f) 4 else 3).forEach { (label, value) ->
                        Column {
                            FaceText(label.upper(), look, m.sp(max(m.u * 4.2f, 9f)), color = look.dim, weight = look.labelWeight, letterSpacing = 0.08.em)
                            FaceText(value, look, m.sp(max(m.u * 6.4f, 11f)), weight = FontWeight.Medium, family = look.numFont)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(m.dp(3f))) {
            FaceValue(f, look, m, min(m.h * 0.13f, m.w * 0.075f).coerceAtLeast(14f), Modifier.weight(1f, fill = false))
            if (f.caption.isNotEmpty() && m.w >= 240f) FaceCaption(f, look, m, Modifier.weight(1f).padding(bottom = 2.dp), align = TextAlign.End)
        }
    }
}

/**
 * The car in profile as a light-drawn wireframe: body line with the wheel
 * arches, glasshouse, door cuts, wheels and a reflection under it. Open
 * doors, bonnet or tailgate ([WidgetFace.doors]) are traced in the warning
 * colour; so is the whole outline when the widget is in alert.
 */
private fun DrawScope.carProfile(look: FaceLook, doors: List<Boolean>?, alert: Boolean) {
    // Drawn in a 100 x 42 box, scaled to fit and centred.
    val s = min(size.width / 100f, size.height / 42f)
    val ox = (size.width - 100f * s) / 2f
    val oy = (size.height - 42f * s) / 2f
    withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) {
        val ink = if (alert) look.warn else look.accent
        val body = Path().apply {
            moveTo(3f, 30f)
            lineTo(3f, 24.5f)
            cubicTo(3.5f, 21f, 8f, 19.5f, 16f, 18.5f)
            lineTo(27f, 17.5f)
            cubicTo(32f, 12f, 37f, 6.5f, 44f, 5.5f)
            lineTo(71f, 5f)
            cubicTo(79f, 5f, 86f, 9f, 93f, 14f)
            cubicTo(96f, 16.5f, 97f, 20f, 97f, 24f)
            lineTo(96.5f, 30f)
            lineTo(85f, 30f)
            arcTo(Rect(Offset(76f, 30f), 8.5f), 0f, -180f, false)
            lineTo(30.5f, 30f)
            arcTo(Rect(Offset(22f, 30f), 8.5f), 0f, -180f, false)
            close()
        }
        val glass = Path().apply {
            moveTo(30f, 17.2f)
            cubicTo(34f, 12.5f, 38f, 8.5f, 44.5f, 7.8f)
            lineTo(70f, 7.4f)
            cubicTo(76f, 7.6f, 81f, 10.5f, 86f, 15.8f)
            lineTo(30f, 17.2f)
            close()
        }
        val wide = Stroke(3.2f / s * 1.dp.toPx(), join = StrokeJoin.Round)
        val thin = Stroke(1.1f / s * 1.dp.toPx(), join = StrokeJoin.Round)
        // Floor reflection and shadow.
        drawOval(Brush.radialGradient(listOf(ink.copy(alpha = 0.25f), Color.Transparent), Offset(50f, 39f), 46f), Offset(4f, 36f), Size(92f, 6f))
        drawPath(body, Brush.verticalGradient(listOf(ink.copy(alpha = 0.10f), Color.Transparent), 5f, 30f))
        drawPath(body, ink.copy(alpha = 0.18f), style = wide)
        drawPath(body, ink, style = thin)
        drawPath(glass, look.ink.copy(alpha = 0.07f))
        drawPath(glass, ink.copy(alpha = 0.7f), style = thin)
        // Pillars and door cuts; an open door lights its cut.
        val open = doors ?: emptyList()
        fun isOpen(i: Int) = open.getOrElse(i) { false }
        val frontOpen = isOpen(0) || isOpen(1)
        val rearOpen = isOpen(2) || isOpen(3)
        drawLine(ink.copy(alpha = 0.7f), Offset(55f, 7.4f), Offset(55f, 17f), thin.width)
        val doorFront = Path().apply { moveTo(31f, 17.4f); lineTo(33f, 29.5f); moveTo(55f, 17f); lineTo(55f, 29.5f) }
        val doorRear = Path().apply { moveTo(55f, 17f); lineTo(55f, 29.5f); moveTo(74f, 16.5f); lineTo(69f, 26f) }
        drawPath(doorFront, if (frontOpen) look.warn else ink.copy(alpha = 0.55f), style = if (frontOpen) wide else thin)
        drawPath(doorRear, if (rearOpen) look.warn else ink.copy(alpha = 0.55f), style = if (rearOpen) wide else thin)
        drawLine(ink.copy(alpha = 0.4f), Offset(8f, 24f), Offset(94f, 23f), thin.width)
        if (isOpen(5)) drawLine(look.warn, Offset(6f, 20f), Offset(26f, 17.8f), wide.width, StrokeCap.Round)
        if (isOpen(4)) drawLine(look.warn, Offset(88f, 9.5f), Offset(96.5f, 22f), wide.width, StrokeCap.Round)
        // Wheels.
        listOf(Offset(22f, 30f), Offset(76f, 30f)).forEach { c ->
            drawCircle(Color.Black.copy(alpha = 0.35f), 7f, c)
            drawCircle(ink, 7f, c, style = thin)
            drawCircle(ink.copy(alpha = 0.5f), 4f, c, style = thin)
            for (k in 0 until 5) {
                val a = k * 72f
                drawLine(ink.copy(alpha = 0.35f), polarPoint(c, 1.4f, a), polarPoint(c, 4f, a), thin.width)
            }
        }
        // Lamps.
        drawLine(look.accent2, Offset(4.5f, 21.5f), Offset(10f, 20f), 1.6f, StrokeCap.Round)
        drawLine(Color(0xFFE5484D), Offset(95.5f, 17f), Offset(96.5f, 21f), 1.6f, StrokeCap.Round)
    }
}
