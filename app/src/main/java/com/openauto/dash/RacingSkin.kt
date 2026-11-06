package com.openauto.dash

import com.openauto.dash.vehicle.any
import com.openauto.dash.vehicle.rememberPanelGauges
import com.openauto.dash.vehicle.K
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.layoutId
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * Racing skin: the dashboard as the HUD of an arcade racing game, over a
 * synthwave night drive. The page is the game's backdrop: a deep violet sky
 * with a few stars, a big striped sun whose glow breathes, neon-edged
 * mountains, a magenta grid floor rushing toward the viewer and a dark road
 * with cyan edges and a scrolling yellow centre line. Every tile is a HUD
 * element in hard angles: skewed glass panels with neon rims, a segmented
 * tachometer with the gear in a hexagon, a yellow turn banner with a hard
 * magenta shadow beside a round minimap, a skewed ticker for the music, fuel
 * as skewed segments, a stage card for the trip, and the driver's own car on
 * a strip of road over its underglow. Type is heavy italic condensed caps.
 *
 * Tape Deck wears the same colours as a piece of hardware (a cassette deck);
 * nothing here is hardware: it is a game screen, flat neon and angles.
 *
 * The day version is the same scene at dawn: a pink-peach sky, a pale grid,
 * white glass panels and dark ink. Everything is drawn on canvases with
 * system fonts: no images, no resources.
 */

internal val RacingPalette = DashPalette(
    Background = Color(0xFF0A0618), Bar = Color.Transparent, Card = Color(0xFF14102E), CardHi = Color(0xFF2C2560),
    Accent = Color(0xFF00E5FF), Secondary = Color(0xFFFF2BD6), Critical = Color(0xFFFF3860), Good = Color(0xFF3CFF8F),
    Muted = Color(0xFF9A8CC8), TextPrimary = Color(0xFFF4F4FF), TextSecondary = Color(0xFFC7A6E6), Accent2 = Color(0xFFFF2BD6),
    Line = Color(0x66FF2BD6), Glow = 1f,
    BackgroundStops = listOf(Color(0xFF07021A), Color(0xFF1C0838), Color(0xFF5B1466)),
    Bare = true, Skin = DashSkin.RACING, Font = DashFont.CONDENSED, HeroWeight = FontWeight.Black
)

internal val RacingLightPalette = DashPalette(
    Background = Color(0xFFFFE6EF), Bar = Color.Transparent, Card = Color.White, CardHi = Color(0xFFF3DDF0),
    Accent = Color(0xFF007E9E), Secondary = Color(0xFFC8108F), Critical = Color(0xFFD7193F), Good = Color(0xFF0E8F4E),
    Muted = Color(0xFF6F5A8C), TextPrimary = Color(0xFF1C0B33), TextSecondary = Color(0xFF4A3466), Accent2 = Color(0xFFC8108F),
    Line = Color(0x40C8108F), Glow = 0.35f,
    BackgroundStops = listOf(Color(0xFFF3D4F4), Color(0xFFFFD6E2), Color(0xFFFFC6A6)),
    Light = true, Bare = true, Skin = DashSkin.RACING, Font = DashFont.CONDENSED, HeroWeight = FontWeight.Black
)

/** The skin's corners (dialogs, menus, tiles being arranged): hard, nearly square, like the HUD. */
internal val RacingShapes = skinShapes(4, 2, 2)

// ---------------------------------------------------------------- look

// The core inks come from DashColors (Accent cyan, Accent2 magenta, the alert
// colours); these are the game's extra colours as night / day pairs.
// DashColors.Light is snapshot state, so every read redraws on a switch.
private fun tone(night: Color, day: Color): Color = if (DashColors.Light) day else night

private val Cyan: Color get() = DashColors.Accent
private val Magenta: Color get() = DashColors.Accent2

/** The banner and centre-line yellow (the mockup's #FFE600); a warmer gold by day, so it holds on a pale page. */
private val RaYellow: Color get() = tone(Color(0xFFFFE600), Color(0xFFFFD21F))
private val RaGold: Color get() = tone(Color(0xFFFFB800), Color(0xFFFFA51F))

/** Yellow lettering on glass: the banner yellow at night, a deep amber that reads on white panels by day. */
private val RaYellowInk: Color get() = tone(Color(0xFFFFE600), Color(0xFF8F5B00))

/** Ink on the yellow banner, the same in both modes. */
private val BannerInk = Color(0xFF0A0618)

/** The far end of the "pause menu" gradient (the hot buttons). */
private val RaPurple: Color get() = tone(Color(0xFFB21CFF), Color(0xFF8A2BE2))

/** Unlit segments of the tachometer, fuel and progress bars, and the minimap's streets. */
private val RaUnlit: Color get() = tone(Color(0xFF2C2560), Color(0xFFE0D3EC))
private val RaRedUnlit: Color get() = tone(Color(0xFF6A1660), Color(0xFFF4C3D6))

/**
 * Fake frosted glass behind every panel: translucent ink at night, milky
 * white by day. Opaque with effects off, for full sun.
 */
private val RaGlass: Color
    get() = if (DashColors.Effects == DashEffects.NONE) tone(Color(0xF50E0A26), Color(0xFCFFFFFF))
    else tone(Color(0xC80A061E), Color(0xD6FFFFFF))

/** How strong the neon glows are: the effects setting, and softer by day (a bloom washes out on pastel). */
private val glowK: Float get() = DashColors.Effects.scale * (if (DashColors.Light) 0.45f else 1f)

/** tan 12°: the lean of every panel, banner and button (the mockup's skewX(-12deg)). */
private const val SLANT = 0.2126f

/** The most a panel leans, so a tall tile keeps a slanted card and not a rhombus. */
private val MAX_SLANT = 26.dp

private const val DOT = " · "

private fun String.caps(): String = uppercase(Locale.getDefault())

private val Trimmed = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** A neon glow of [color] blurred [blur] px: tight and faint by day, and only with effects at full (see [softTextShadow]). */
private fun neon(color: Color, blur: Float): Shadow? =
    if (DashColors.Light) softTextShadow(color.copy(alpha = 0.28f), blur * 0.45f)
    else softTextShadow(color.copy(alpha = 0.8f), blur)

/** Heavy italic condensed caps: every word on the HUD. */
private fun hud(
    size: TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Bold,
    spacing: Float = 0.06f,
    glow: Color? = null
): TextStyle = (if (spacing >= 0.1f) readableCaps(size) else readable(size)).let { s -> TextStyle(
    color = color,
    fontSize = s,
    lineHeight = s * 1.12f,
    fontFamily = CondensedFamily,
    fontWeight = weight,
    fontStyle = FontStyle.Italic,
    letterSpacing = spacing.em,
    shadow = glow?.let { neon(it, size.value * 0.7f) }
) }

/** Huge black italic numerals (speed, clock, distance), trimmed to their digits. */
private fun hero(size: TextUnit, color: Color, glow: Color? = null): TextStyle = TextStyle(
    color = color,
    fontSize = size,
    lineHeight = size,
    lineHeightStyle = Trimmed,
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    letterSpacing = (-0.01).em,
    shadow = glow?.let { neon(it, size.value * 0.3f) }
)

/** One run of HUD text: single line unless [maxLines] says otherwise, ending in an ellipsis when it runs out of room. */
@Composable
private fun HudText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null
) {
    Text(
        text = text,
        modifier = modifier,
        style = if (align != null) style.copy(textAlign = align) else style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * A run of figures (a speed, a distance, a time): never ellipsized, as a cut
 * number reads as a wrong one. The caller sizes it to fit (see [fitSp]).
 */
@Composable
private fun NumText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
}

/**
 * The first of [candidates] (longest first) that fits [maxW] x [maxH] at
 * [minSp] or more, with its size up to [maxSp]; else the last one at whatever
 * size fits it. Measured once per change, at 100 sp, with digits as '8'.
 */
@Composable
private fun firstFitting(candidates: List<String>, style: TextStyle, maxW: Dp, maxH: Dp, minSp: Float, maxSp: Float): Pair<String, Float> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(candidates, style, maxW, maxH, minSp, maxSp, density) {
        fun fit(text: String): Float {
            val probe = measurer.measure(template(text), style.copy(fontSize = 100.sp, shadow = null), softWrap = false, maxLines = 1)
            val byW = with(density) { maxW.toPx() } * 100f / probe.size.width.coerceAtLeast(1)
            val byH = with(density) { maxH.toPx() } * 100f / probe.size.height.coerceAtLeast(1)
            return min(byW, byH)
        }
        candidates.firstNotNullOfOrNull { c -> fit(c).takeIf { it >= minSp }?.let { c to it.coerceAtMost(maxSp) } }
            ?: candidates.last().let { it to fit(it).coerceIn(6f, maxSp) }
    }
}

/**
 * Largest font size in sp (within [minSp]..[maxSp]) at which [sample] set in
 * [style] fits [maxW] on one line with its line box no taller than [maxH].
 * Measured once at 100 sp and scaled, since type grows linearly with size.
 */
@Composable
private fun fitSp(sample: String, style: TextStyle, maxW: Dp, maxH: Dp, minSp: Float, maxSp: Float): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sample, style, maxW, maxH, minSp, maxSp, density) {
        val probe = measurer.measure(sample, style.copy(fontSize = 100.sp, shadow = null), softWrap = false, maxLines = 1)
        val w = probe.size.width.coerceAtLeast(1)
        val h = probe.size.height.coerceAtLeast(1)
        val byW = with(density) { maxW.toPx() } * 100f / w
        val byH = with(density) { maxH.toPx() } * 100f / h
        min(byW, byH).coerceIn(minSp, maxSp)
    }
}

/** Digits replaced by '8' so a readout keeps one size while its value changes. */
private fun template(text: String): String = text.map { if (it.isDigit()) '8' else it }.joinToString("")

/** How far a panel of [size] leans, capped at [max] px and at a share of its width. */
private fun slantOf(size: Size, max: Float): Float = min(size.height * SLANT, max).coerceAtMost(size.width * 0.18f)

/** The same lean in dp, for padding the content clear of the slanted edges. */
private fun slantDp(w: Dp, h: Dp, max: Dp = MAX_SLANT): Dp = minOf(h * SLANT, max, w * 0.18f)

/**
 * A parallelogram leaning forward over [w] x [h]: the top edge pushed
 * [slant] right of the bottom one, shrunk by [inset] on every side (for a
 * stroke that stays inside).
 */
private fun skewPath(w: Float, h: Float, slant: Float, inset: Float = 0f): Path = Path().apply {
    val top = inset
    val bottom = h - inset
    fun left(y: Float) = slant * (1f - y / h) + inset
    fun right(y: Float) = w - slant * (y / h) - inset
    moveTo(left(top), top)
    lineTo(right(top), top)
    lineTo(right(bottom), bottom)
    lineTo(left(bottom), bottom)
    close()
}

/** The lean as a shape, for a clip or a ripple ([ObdMark]). */
private val SkewShape = GenericShape { s, _ ->
    val k = slantOf(s, s.height * SLANT)
    moveTo(k, 0f)
    lineTo(s.width, 0f)
    lineTo(s.width - k, s.height)
    lineTo(0f, s.height)
    close()
}

/**
 * The HUD's glass panel behind the content: a parallelogram of fake frosted
 * glass with a neon [rim] that glows outward in two falling-alpha strokes, a
 * tint of the rim fading down from the top and a specular hairline under the
 * top edge. [hot] fills it with the magenta-to-purple "pause menu" gradient
 * instead. Brighter while [pressed]; a dashed rim while the dashboard is
 * being arranged ([dashed]), to show the tile is editable. Effects off keeps
 * the opaque glass and the rim only.
 */
/** The HUD glass around a widget Racing does not redraw, its content kept clear of the slanted edges ([SkinFallbackPanel]). */
@Composable
internal fun RacingFallbackPanel(content: @Composable () -> Unit) {
    HudPanel(Modifier.fillMaxSize(), Cyan, editing = false, contentAlignment = Alignment.TopStart) { content() }
}

@Composable
private fun Modifier.hudPanel(
    rim: Color,
    pressed: State<Boolean>? = null,
    dashed: Boolean = false,
    hot: Boolean = false,
    slantMax: Dp = MAX_SLANT
): Modifier {
    val glass = RaGlass
    val g = glowK
    val light = DashColors.Light
    val purple = RaPurple
    return cachedDraw(rim, pressed, dashed, hot, slantMax, glass, g, light, purple) {
        val line = 2.dp.toPx()
        val k = slantOf(size, slantMax.toPx())
        val body = skewPath(size.width, size.height, k)
        val edge = skewPath(size.width, size.height, k, line / 2f)
        val wideHalo = Stroke(line + 10.dp.toPx(), join = StrokeJoin.Miter)
        val nearHalo = Stroke(line + 4.dp.toPx(), join = StrokeJoin.Miter)
        val hotFill = if (hot) Brush.horizontalGradient(listOf(rim, purple)) else null
        val sheen = if (g > 0f && !hot) {
            Brush.verticalGradient(0f to rim.copy(alpha = if (light) 0.10f else 0.18f), 0.5f to rim.copy(alpha = 0f))
        } else null
        val spec = Color.White.copy(alpha = if (light && !hot) 0.95f else 0.28f)
        val specY = line * 1.8f
        val specFrom = Offset(slantOf(size, slantMax.toPx()) * (1f - specY / size.height) + line * 2.5f, specY)
        val specTo = Offset(size.width - k * (specY / size.height) - line * 2.5f, specY)
        val rimStroke = Stroke(
            line,
            join = StrokeJoin.Miter,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 6.dp.toPx())) else null
        )
        onDrawBehind {
            if (g > 0f) {
                drawPath(edge, rim.copy(alpha = 0.07f * g), style = wideHalo)
                drawPath(edge, rim.copy(alpha = 0.2f * g), style = nearHalo)
            }
            if (hotFill != null) drawPath(body, hotFill) else drawPath(body, glass)
            if (sheen != null) drawPath(body, sheen)
            if (pressed?.value == true) drawPath(body, if (hot) Color.White.copy(alpha = 0.25f) else rim.copy(alpha = 0.25f))
            drawLine(spec, specFrom, specTo, 1.dp.toPx())
            drawPath(edge, rim, style = rimStroke)
        }
    }
}

/**
 * A HUD panel filling [modifier], its content padded clear of the slanted
 * edges. [onClick] makes the whole panel a button with tap feedback (never
 * while [editing], when the rim turns dashed instead).
 */
@Composable
private fun HudPanel(
    modifier: Modifier,
    rim: Color,
    editing: Boolean,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable BoxWithConstraintsScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    BoxWithConstraints(
        modifier
            .hudPanel(rim, pressed, dashed = editing)
            .then(
                if (onClick != null && !editing) {
                    Modifier.clickable(interaction, indication = null, onClickLabel = clickLabel, role = Role.Button) {
                        tap()
                        onClick()
                    }
                } else Modifier
            )
    ) {
        val slant = slantDp(maxWidth, maxHeight)
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(start = slant * 0.6f + 12.dp, end = slant * 0.6f + 12.dp, top = 8.dp, bottom = 8.dp),
            contentAlignment = contentAlignment,
            content = content
        )
    }
}

/**
 * A skewed neon button with [content] centred in it: dark glass with a [rim]
 * at night, white glass by day, or the hot magenta gradient ("pause menu").
 * Lights up while pressed; a dashed rim while arranging.
 */
@Composable
private fun SkewButton(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    hot: Boolean = false,
    rim: Color = Cyan,
    dashed: Boolean = false,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    BoxWithConstraints(
        modifier
            .heightIn(min = DashSize.Touch)
            .widthIn(min = DashSize.Touch)
            .hudPanel(if (hot) Magenta else rim, pressed, dashed = dashed, hot = hot)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                tap()
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        val slant = slantDp(maxWidth, maxHeight)
        Box(Modifier.padding(horizontal = slant * 0.5f + 8.dp), contentAlignment = Alignment.Center) { content() }
    }
}

/** A small slanted tick before a label, in [color]: the HUD's bullet. */
@Composable
private fun SkewTick(color: Color, height: Dp) {
    Spacer(
        Modifier
            .size(width = height * 0.55f, height = height)
            .cachedDraw(color) {
                val path = skewPath(size.width, size.height, size.width * 0.45f)
                onDrawBehind { drawPath(path, color) }
            }
    )
}

/**
 * A bar of skewed blocks lit up to [fraction] in [lit], unlit ones dim, with
 * a glow behind the lit ones; about one block per [segment] of width. The
 * fraction is read while drawing, so a moving value only redraws the bar.
 */
@Composable
private fun SegBar(fraction: Float, lit: Color, modifier: Modifier, segment: Dp = 16.dp) {
    val f = rememberUpdatedState(fraction)
    val unlit = RaUnlit
    val g = glowK
    Spacer(
        modifier.graphicsLayer().cachedDraw(lit, unlit, g, segment) {
            val h = size.height
            val k = h * 0.36f
            val gap = 4.dp.toPx()
            val count = ((size.width - k + gap) / (segment.toPx() + gap)).toInt().coerceIn(4, 40)
            val segW = ((size.width - k - gap * (count - 1)) / count).coerceAtLeast(1f)
            val block = Path().apply {
                moveTo(k, 0f)
                lineTo(k + segW, 0f)
                lineTo(segW, h)
                lineTo(0f, h)
                close()
            }
            val halo = Stroke(4.dp.toPx(), join = StrokeJoin.Miter)
            onDrawBehind {
                val on = (f.value.coerceIn(0f, 1f) * count).roundToInt()
                for (i in 0 until count) {
                    translate(left = i * (segW + gap)) {
                        if (i < on && g > 0f) drawPath(block, lit.copy(alpha = 0.3f * g), style = halo)
                        drawPath(block, if (i < on) lit else unlit)
                    }
                }
            }
        }
    )
}

// ---------------------------------------------------------------- public contract

/**
 * The whole page as the game's backdrop: violet sky with a few stars, the
 * striped sun and its breathing magenta glow, neon-edged mountains, the grid
 * floor and the road with its cyan edges. Sky and scenery are recorded once
 * per size and appearance into two offscreen layers; each step of the ambient
 * ticker (about 20 a second at full effects, 10 and at half speed when
 * reduced, none with effects off) only composites them and redraws the sun's
 * glow, two twinkling stars, the grid's cross lines and the centre dashes.
 */
@Composable
internal fun racingBackground(): Modifier {
    val loop = rememberLoop(BG_LOOP_MS)
    val light = DashColors.Light
    val cyan = DashColors.Accent
    val magenta = DashColors.Accent2
    val effects = DashColors.Effects
    return remember(light, cyan, magenta, effects, loop) { sceneModifier(light, cyan, magenta, effects, loop) }
}

/**
 * Transparent bar in the same order as every other look: the hot magenta
 * APPS button (the game's pause menu) and a skewed LAYOUT button on the left,
 * with the stage card (trip and moving time) after them on a wide screen; the
 * clock in black italic with a magenta glow and the dashboard's position in
 * the middle; on the right the setup pill, alerts, the phone, the FINISH card
 * (arrival time) while a route runs, then the OBD mark and ⋮.
 */
@Composable
internal fun RacingTopBar(m: TopBarModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            // Own layer, so the animated page background redrawing each step does not re-record the bar.
            .graphicsLayer()
            .drawWithCache {
                val shade = Brush.verticalGradient(
                    listOf(tone(Color.Black.copy(alpha = 0.35f), Color.White.copy(alpha = 0.5f)), Color.Transparent)
                )
                onDrawBehind { drawRect(shade) }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        val narrow = maxWidth < NARROW_BAR
        val labels = maxWidth >= LABELLED_BAR
        val stage = maxWidth >= 1180.dp
        val ink = Cyan
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = if (narrow) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BarButton(
                    description = stringResource(R.string.dash_all_apps),
                    onClick = m.onApps,
                    label = if (labels) stringResource(R.string.dash_apps).caps() else null,
                    hot = true
                ) {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(6.dp))
                LayoutPicker(m) { open ->
                    BarButton(
                        description = stringResource(R.string.dash_screen_layout, m.layout.title),
                        onClick = open,
                        label = if (labels) stringResource(R.string.dash_layout_button).caps() else null
                    ) {
                        LayoutIcon(m.layout, null, ink, Modifier.size(20.dp))
                    }
                }
                if (stage) {
                    Spacer(Modifier.width(12.dp))
                    BarStage()
                }
            }
            // The head unit's status bar shows the time while it is up; the dashboard's position beside it.
            Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.merged) BarClock(m.clock)
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
            // ⋮ and the OBD mark get their room first; the FINISH card goes first when the bar is crowded.
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) {
                        SetupPill(onClick = { m.onSetup(false) }, compact = narrow || barIsShared())
                    }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts()
                }
                Box(Modifier.layoutId(BarRank.PHONE)) { PhonePill() }
                if (!narrow) Box(Modifier.layoutId(BarRank.TEMP)) { BarFinish() }
                Box(Modifier.layoutId(BarRank.OBD)) { BarObd(m.obdConnection, m.onConnectObd) }
                Box(Modifier.layoutId(BarRank.MORE)) {
                    MorePicker(m) { open ->
                        BarButton(description = stringResource(R.string.dash_more), onClick = open) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Draws the skinned widgets, app shortcuts and launch bars; everything else keeps its standard renderer. */
@Composable
internal fun RacingTile(item: DashboardItem, env: SkinTileEnv) {
    when (item) {
        is DashboardItem.AppShortcut -> RaceApp(item, env)
        is DashboardItem.LaunchBar -> RaceLaunchBar(item, env)
        is DashboardItem.BuiltinWidget -> when (item.kind) {
            BuiltinKind.TELEMETRY -> RaceTelemetry(env)
            BuiltinKind.SPEED_HUD -> RaceSpeed(env)
            BuiltinKind.MEDIA -> RaceMedia(env)
            BuiltinKind.NAVIGATION -> RaceNavigation(env)
            BuiltinKind.CLOCK -> RaceClock(env)
            BuiltinKind.WEATHER -> RaceWeather(env)
            BuiltinKind.RANGE -> RaceRange(item, env)
            else -> StandardSkinnedTile(item, env)
        }
        else -> StandardSkinnedTile(item, env)
    }
}

/**
 * The HUD's frame around a docked Maps window: the top-left and bottom-right
 * corners cut off at the skin's angle (masked in the colour of the scene
 * behind them, sky or floor, by the window's place on screen), a cyan neon
 * rim along the cut outline, magenta corner brackets on the other two corners
 * and a slight darkening along the edges so the map sits in the scene. The
 * middle stays clear; nothing animates.
 */
@Composable
internal fun RacingWindowFrame(modifier: Modifier) {
    val cyan = Cyan
    val magenta = Magenta
    val g = glowK
    val light = DashColors.Light
    val view = LocalView.current
    val screenH = view.resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
    val topOnScreen = remember { mutableFloatStateOf(Float.NaN) }
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { c ->
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                topOnScreen.floatValue = loc[1] + c.positionInWindow().y
            }
            .cachedDraw(cyan, magenta, g, light, screenH) {
                val w = size.width
                val h = size.height
                val ink = if (light) DawnInk else NightInk
                val top = topOnScreen.floatValue.takeUnless { it.isNaN() } ?: BAR_HEIGHT.toPx()
                val maskTop = sceneColorAt(ink, top / screenH)
                val maskBottom = sceneColorAt(ink, (top + h) / screenH)
                val cut = min(28.dp.toPx(), min(w, h) * 0.08f)
                if (w <= cut * 4f || h <= cut * 4f) return@cachedDraw onDrawBehind { }
                val line = 2.dp.toPx()
                val i = line / 2f
                val outline = Path().apply {
                    moveTo(cut + i, i)
                    lineTo(w - i, i)
                    lineTo(w - i, h - cut - i)
                    lineTo(w - cut - i, h - i)
                    lineTo(i, h - i)
                    lineTo(i, cut + i)
                    close()
                }
                val cornerTop = Path().apply {
                    moveTo(0f, 0f); lineTo(cut + line, 0f); lineTo(0f, cut + line); close()
                }
                val cornerBottom = Path().apply {
                    moveTo(w, h); lineTo(w, h - cut - line); lineTo(w - cut - line, h); close()
                }
                val edgeShade = tone(Color.Black.copy(alpha = 0.35f), Color(0xFF3B2A5C).copy(alpha = 0.12f))
                val band = 14.dp.toPx()
                val topFade = Brush.verticalGradient(listOf(edgeShade, Color.Transparent), startY = 0f, endY = band)
                val bottomFade = Brush.verticalGradient(listOf(Color.Transparent, edgeShade), startY = h - band, endY = h)
                val halo = Stroke(line + 8.dp.toPx(), join = StrokeJoin.Miter)
                val rim = Stroke(line, join = StrokeJoin.Miter)
                val arm = min(40.dp.toPx(), min(w, h) * 0.2f)
                val bracketW = 3.dp.toPx()
                val brackets = Path().apply {
                    val o = bracketW / 2f + line
                    moveTo(w - o - arm, o); lineTo(w - o, o); lineTo(w - o, o + arm)
                    moveTo(o, h - o - arm); lineTo(o, h - o); lineTo(o + arm, h - o)
                }
                val bracketStroke = Stroke(bracketW, cap = StrokeCap.Square, join = StrokeJoin.Miter)
                onDrawBehind {
                    drawRect(topFade, size = Size(w, band))
                    drawRect(bottomFade, topLeft = Offset(0f, h - band), size = Size(w, band))
                    drawPath(cornerTop, maskTop)
                    drawPath(cornerBottom, maskBottom)
                    if (g > 0f) drawPath(outline, cyan.copy(alpha = 0.22f * g), style = halo)
                    drawPath(outline, cyan, style = rim)
                    if (g > 0f) drawPath(brackets, magenta.copy(alpha = 0.3f * g), style = Stroke(bracketW + 6.dp.toPx()))
                    drawPath(brackets, magenta, style = bracketStroke)
                }
            }
    )
}

/** The skin as a stamp in the theme picker (ThemePane): the sun over the grid and road, the turn banner and the tacho. */
internal fun DrawScope.racingStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val hy = h * 0.52f
    val ink = if (p.Light) DawnInk else NightInk
    drawRect(Brush.verticalGradient(listOf(ink.skyTop, ink.skyMid, ink.skyLow), endY = hy), size = Size(w, hy))
    // Sun with two stripes cut by sky-coloured bands.
    val r = h * 0.26f
    val sc = Offset(w * 0.42f, hy - r * 0.35f)
    drawCircle(Brush.verticalGradient(listOf(ink.sun1, ink.sun2, ink.sun3), startY = sc.y - r, endY = sc.y + r), r, sc)
    drawRect(ink.skyLow, Offset(sc.x - r, sc.y + r * 0.1f), Size(r * 2f, r * 0.08f))
    drawRect(ink.skyLow, Offset(sc.x - r, sc.y + r * 0.35f), Size(r * 2f, r * 0.12f))
    // Floor, grid, road.
    drawRect(Brush.verticalGradient(listOf(ink.floorTop, ink.floorBottom), startY = hy, endY = h), Offset(0f, hy), Size(w, h - hy))
    val grid = p.Accent2.copy(alpha = if (p.Light) 0.45f else 0.7f)
    for (k in -6..6) drawLine(grid, Offset(w / 2f + k * w * 0.03f, hy), Offset(w / 2f + k * w * 0.2f, h), 1f)
    for (i in 1..4) drawLine(grid, Offset(0f, hy + (h - hy) / (5f - i)), Offset(w, hy + (h - hy) / (5f - i)), 1f)
    drawLine(p.Accent2, Offset(0f, hy), Offset(w, hy), 1.5f)
    val road = Path().apply {
        moveTo(w * 0.48f, hy); lineTo(w * 0.52f, hy); lineTo(w * 0.8f, h); lineTo(w * 0.2f, h); close()
    }
    drawPath(road, ink.road)
    drawLine(p.Accent, Offset(w * 0.48f, hy), Offset(w * 0.2f, h), 1.5f)
    drawLine(p.Accent, Offset(w * 0.52f, hy), Offset(w * 0.8f, h), 1.5f)
    drawLine(ink.line, Offset(w * 0.5f, hy + (h - hy) * 0.45f), Offset(w * 0.5f, h), 2f)
    // Turn banner with its hard shadow, top left.
    val bw = w * 0.34f
    val bh = h * 0.16f
    fun banner(o: Float) = Path().apply {
        moveTo(w * 0.05f + bh * 0.25f + o, h * 0.08f + o); lineTo(w * 0.05f + bw + o, h * 0.08f + o)
        lineTo(w * 0.05f + bw - bh * 0.25f + o, h * 0.08f + bh + o); lineTo(w * 0.05f + o, h * 0.08f + bh + o); close()
    }
    drawPath(banner(3f), p.Accent2)
    drawPath(banner(0f), if (p.Light) Color(0xFFFFD21F) else Color(0xFFFFE600))
    // The tacho, right.
    val tr = h * 0.27f
    val tc = Offset(w * 0.8f, h * 0.36f)
    drawCircle(p.Background.copy(alpha = 0.85f), tr, tc)
    drawArc(p.CardHi, 135f, 270f, false, Offset(tc.x - tr * 0.85f, tc.y - tr * 0.85f), Size(tr * 1.7f, tr * 1.7f), style = Stroke(tr * 0.2f))
    drawArc(p.Accent, 135f, 120f, false, Offset(tc.x - tr * 0.85f, tc.y - tr * 0.85f), Size(tr * 1.7f, tr * 1.7f), style = Stroke(tr * 0.2f))
    number("87", tc.x, tc.y, 11f, p.TextPrimary)
}

// ---------------------------------------------------------------- background

private const val BG_LOOP_MS = 12_000

/** Horizon line, as a share of the page height (the mockup's 338 of 720). */
private const val HORIZON = 0.47f

/** Grid cross lines passing per loop (one every half second), and half that with reduced effects. */
private const val GRID_ROLLS = 24
private const val GRID_DEPTHS = 14

/** Centre dashes in world units (the grid's square is 1), and how many pass per loop to match the grid's speed. */
private const val DASH_GAP = 0.5f
private const val DASH_ROLLS = 48
private const val DASH_COUNT = 16

/** The sun's glow breathes this many times per loop (every 4 s). */
private const val SUN_PULSES = 3

/** Sun stripes cut out of the disc: top and bottom of each gap, as shares of its height from the top. */
private val SUN_GAPS = floatArrayOf(0.46f, 0.49f, 0.54f, 0.58f, 0.62f, 0.67f, 0.70f, 0.76f)

/** The mountain range from the mockup: x as a share of the width, y in px above the horizon of a 720 px page. */
private val RANGE_LEFT = floatArrayOf(0f, 31f, 0.08f, 64f, 0.14f, 40f, 0.22f, 86f, 0.31f, 44f, 0.38f, 58f, 0.44f, 0f)
private val RANGE_RIGHT = floatArrayOf(0.56f, 0f, 0.62f, 53f, 0.70f, 92f, 0.78f, 48f, 0.86f, 75f, 0.93f, 42f, 1f, 58f)

/** A few stars as shares of the width and the sky's height; the first two twinkle. */
private val STARS: List<Offset> = listOf(Offset(0.094f, 0.21f), Offset(0.766f, 0.18f)) +
    Random(87).let { rnd -> List(16) { Offset(rnd.nextFloat(), 0.08f + rnd.nextFloat() * 0.62f) } } +
    listOf(Offset(0.266f, 0.44f), Offset(0.906f, 0.53f), Offset(0.64f, 0.59f))

/** Every colour of the scene, night or dawn. */
private class SceneInk(
    val skyTop: Color, val skyMid: Color, val skyLow: Color,
    val sun1: Color, val sun2: Color, val sun3: Color, val halo: Color,
    val hillTop: Color, val hillLow: Color,
    val floorTop: Color, val floorBottom: Color,
    val road: Color, val roadNear: Color,
    val line: Color,
    /** Grid line strength: cross lines, verticals. */
    val crossInk: Float, val railInk: Float
)

private val NightInk = SceneInk(
    skyTop = Color(0xFF07021A), skyMid = Color(0xFF1C0838), skyLow = Color(0xFF5B1466),
    sun1 = Color(0xFFFFE14D), sun2 = Color(0xFFFF8A3D), sun3 = Color(0xFFFF2BA8), halo = Color(0xB3FF4FA3),
    hillTop = Color(0xFF2A0B47), hillLow = Color(0xFF16062A),
    floorTop = Color(0xFF2A0A40), floorBottom = Color(0xFF0B0316),
    road = Color(0xFF15102E), roadNear = Color(0xFF0D0A24),
    line = Color(0xFFFFE600),
    crossInk = 0.85f, railInk = 0.6f
)

/** Dawn: a pink-peach sky, a pale sun and lilac hills, a pearl floor with a soft grid, made for dark ink. */
private val DawnInk = SceneInk(
    skyTop = Color(0xFFF3D4F4), skyMid = Color(0xFFFFD6E2), skyLow = Color(0xFFFFC6A6),
    sun1 = Color(0xFFFFF07A), sun2 = Color(0xFFFFAA5E), sun3 = Color(0xFFFF6FAE), halo = Color(0xCCFFE2C4),
    hillTop = Color(0xFFD5BCEB), hillLow = Color(0xFFE9D8F3),
    floorTop = Color(0xFFFFE3EE), floorBottom = Color(0xFFF7EEF8),
    road = Color(0xFFE9DDF0), roadNear = Color(0xFFD6C6E4),
    line = Color(0xFFF5A300),
    crossInk = 0.42f, railInk = 0.3f
)

/** The scene's colour at [yFrac] of the page height: the sky's gradient above the horizon, the floor's below. */
private fun sceneColorAt(ink: SceneInk, yFrac: Float): Color {
    if (yFrac >= HORIZON) return lerp(ink.floorTop, ink.floorBottom, ((yFrac - HORIZON) / (1f - HORIZON)).coerceIn(0f, 1f))
    val f = (yFrac / HORIZON).coerceIn(0f, 1f)
    return if (f < 0.55f) lerp(ink.skyTop, ink.skyMid, f / 0.55f) else lerp(ink.skyMid, ink.skyLow, (f - 0.55f) / 0.45f)
}

private fun sceneModifier(light: Boolean, cyan: Color, magenta: Color, effects: DashEffects, loop: State<Float>): Modifier =
    Modifier.drawWithCache {
        val w = size.width
        val h = size.height
        val hy = h * HORIZON
        val ground = h - hy
        val s = min(w, h) / 720f
        val g = effects.scale * (if (light) 0.5f else 1f)
        val ink = if (light) DawnInk else NightInk
        val speed = if (effects == DashEffects.REDUCED) 2 else 1

        // Sky and the still stars.
        val sky = Brush.verticalGradient(0f to ink.skyTop, 0.55f to ink.skyMid, 1f to ink.skyLow, startY = 0f, endY = hy)
        val still = STARS.drop(2).map { Offset(it.x * w, it.y * hy) }
        val twinkle = STARS.take(2).map { Offset(it.x * w, it.y * hy) }
        val skyLayer = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
        skyLayer.record {
            drawRect(sky, size = Size(w, hy))
            if (!light) drawPoints(still, PointMode.Points, Color.White.copy(alpha = 0.7f), 2.2f * s, StrokeCap.Round)
        }

        // The striped sun, high enough above the horizon for four stripes to show.
        val sunR = min(0.215f * h, 0.3f * w)
        val sunC = Offset(w / 2f, hy - 0.45f * sunR)
        val sunTop = sunC.y - sunR
        val disc = Path().apply { addOval(Rect(sunC, sunR)) }
        val cuts = Path().apply {
            for (i in SUN_GAPS.indices step 2) {
                addRect(Rect(0f, sunTop + SUN_GAPS[i] * 2f * sunR, w, sunTop + SUN_GAPS[i + 1] * 2f * sunR))
            }
        }
        val sun = Path().apply { if (!op(disc, cuts, PathOperation.Difference)) addPath(disc) }
        val sunFill = Brush.verticalGradient(
            0.1f to ink.sun1, 0.45f to ink.sun2, 0.8f to ink.sun3, startY = sunTop, endY = sunTop + 2f * sunR
        )
        val haloR = sunR * 1.75f
        val halo = Brush.radialGradient(0.5f to ink.halo, 1f to ink.halo.copy(alpha = 0f), center = sunC, radius = haloR)

        // Mountains: a dark fill and a magenta ridge with a soft glow.
        fun ridge(pts: FloatArray): Path = Path().apply {
            moveTo(pts[0] * w, hy - pts[1] * s)
            for (i in 2 until pts.size step 2) lineTo(pts[i] * w, hy - pts[i + 1] * s)
        }
        fun massif(pts: FloatArray): Path = ridge(pts).apply {
            lineTo(pts[pts.size - 2] * w, hy)
            lineTo(pts[0] * w, hy)
            close()
        }
        val hills = Path().apply {
            addPath(massif(RANGE_LEFT))
            addPath(massif(RANGE_RIGHT))
        }
        val ridges = Path().apply {
            addPath(ridge(RANGE_LEFT))
            addPath(ridge(RANGE_RIGHT))
        }
        val hillFill = Brush.verticalGradient(listOf(ink.hillTop, ink.hillLow), startY = hy - 92f * s, endY = hy)
        val ridgeGlow = Stroke(7f * s, join = StrokeJoin.Round)
        val ridgeLine = Stroke(2.5f * s, join = StrokeJoin.Round)

        // Floor and the grid's lines running to the vanishing point.
        val floor = Brush.verticalGradient(listOf(ink.floorTop, ink.floorBottom), startY = hy, endY = h)
        val rails = Path().apply {
            for (k in -24..24) {
                moveTo(w / 2f + k * 14f * s, hy)
                lineTo(w / 2f + k * 128f * s, h)
            }
        }
        val railInk = Brush.verticalGradient(
            0f to magenta.copy(alpha = 0.1f * ink.railInk), 1f to magenta.copy(alpha = ink.railInk), startY = hy, endY = h
        )
        val railStroke = Stroke(1.5f * s)

        // The road: 4% wide at the horizon, 72% at the bottom (the mockup's 48..52% and 14..86%).
        val l0 = w * 0.48f
        val r0 = w * 0.52f
        val l1 = w * 0.14f
        val r1 = w * 0.86f
        val road = Path().apply {
            moveTo(l0, hy); lineTo(r0, hy); lineTo(r1, h); lineTo(l1, h); close()
        }
        val roadFill = Brush.verticalGradient(listOf(ink.road, ink.roadNear), startY = hy, endY = h)
        val edges = Path().apply {
            moveTo(l0, hy); lineTo(l1, h)
            moveTo(r0, hy); lineTo(r1, h)
        }
        val edgeInk = Brush.verticalGradient(
            0f to cyan.copy(alpha = 0.35f), 1f to cyan.copy(alpha = if (light) 0.85f else 0.95f), startY = hy, endY = h
        )
        val edgeGlow = Stroke(8f * s)
        val edgeLine = Stroke(2.5f * s)

        val scenery = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
        scenery.record {
            drawPath(sun, sunFill)
            drawPath(hills, hillFill)
            if (g > 0f) drawPath(ridges, magenta.copy(alpha = 0.3f * g), style = ridgeGlow)
            drawPath(ridges, magenta.copy(alpha = if (light) 0.7f else 0.85f), style = ridgeLine)
            drawRect(floor, topLeft = Offset(0f, hy), size = Size(w, ground))
            drawPath(rails, railInk, style = railStroke)
            drawPath(road, roadFill)
            if (g > 0f) drawPath(edges, cyan.copy(alpha = 0.25f * g), style = edgeGlow)
            drawPath(edges, edgeInk, style = edgeLine)
        }

        val thin = 1.6f * s
        val wide = 6f * s
        val bloomH = 16f * s
        val bloom = Brush.verticalGradient(
            0f to magenta.copy(alpha = 0f), 0.5f to magenta.copy(alpha = (if (light) 0.45f else 0.8f) * (0.4f + 0.6f * g)),
            1f to magenta.copy(alpha = 0f), startY = hy - bloomH, endY = hy + bloomH
        )
        val dashBottom = w * 0.016f
        val dashTop = w * 0.003f
        val dashInk = ink.line

        onDrawBehind {
            val t = loop.value
            drawLayer(skyLayer)
            if (!light) {
                twinkle.forEachIndexed { i, p ->
                    val a = 0.3f + 0.7f * (0.5f + 0.5f * sin(2f * PI.toFloat() * (t * 8f + i * 0.37f)))
                    drawCircle(Color.White, radius = 1.8f * s, center = p, alpha = a)
                }
            }
            if (g > 0f) {
                val pulse = 0.72f + 0.28f * sin(2f * PI.toFloat() * SUN_PULSES * t)
                drawCircle(halo, radius = haloR, center = sunC, alpha = (pulse * g).coerceIn(0f, 1f))
            }
            drawLayer(scenery)

            // Cross lines at depths 1, 2, 3... sliding toward the viewer (y = horizon + ground / depth),
            // drawn either side of the road so the asphalt stays clean.
            val roll = (t * GRID_ROLLS / speed) % 1f
            for (i in 0 until GRID_DEPTHS) {
                val y = hy + ground / (i + 1f - roll)
                if (y > h) continue
                val f = (y - hy) / ground
                val fade = (f * 2.4f).coerceIn(0.06f, 1f)
                val xl = l0 + (l1 - l0) * f
                val xr = r0 + (r1 - r0) * f
                if (g > 0f) {
                    val c = magenta.copy(alpha = 0.22f * g * fade)
                    drawLine(c, Offset(0f, y), Offset(xl, y), wide)
                    drawLine(c, Offset(xr, y), Offset(w, y), wide)
                }
                val c = magenta.copy(alpha = ink.crossInk * fade)
                drawLine(c, Offset(0f, y), Offset(xl, y), thin)
                drawLine(c, Offset(xr, y), Offset(w, y), thin)
            }

            // Centre dashes in the same perspective, at the grid's speed.
            val phase = (t * DASH_ROLLS / speed) % 1f
            for (i in 0 until DASH_COUNT) {
                val z0 = 1f + (i - phase) * DASH_GAP
                val z1 = z0 + DASH_GAP * 0.45f
                if (z1 <= 1f) continue
                val f0 = 1f - 1f / z0.coerceAtLeast(1f)
                if (f0 > 0.97f) break
                val f1 = 1f - 1f / z1
                val y0 = h - ground * f0
                val y1 = h - ground * f1
                val dw = dashBottom + (dashTop - dashBottom) * (f0 + f1) / 2f
                if (g > 0f) drawLine(dashInk.copy(alpha = 0.3f * g), Offset(w / 2f, y0), Offset(w / 2f, y1), dw * 2.6f)
                drawLine(dashInk, Offset(w / 2f, y0), Offset(w / 2f, y1), dw)
            }

            drawRect(bloom, topLeft = Offset(0f, hy - bloomH), size = Size(w, bloomH * 2f))
            drawRect(magenta, topLeft = Offset(0f, hy - thin / 2f), size = Size(w, thin))
        }
    }

// ---------------------------------------------------------------- top bar parts

private val BAR_HEIGHT = 64.dp

/** A bar button: a skewed 40 dp panel (icon, optional caps word) in a 56 dp target; [hot] is the magenta pause-menu one. */
@Composable
private fun BarButton(
    description: String,
    onClick: () -> Unit,
    label: String? = null,
    hot: Boolean = false,
    icon: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .heightIn(min = DashSize.TouchPrimary)
            .widthIn(min = DashSize.TouchPrimary)
            .clickable(interaction, indication = null, role = Role.Button) {
                tap()
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .hudPanel(if (hot) Magenta else Cyan, pressed, hot = hot, slantMax = 9.dp)
                .padding(horizontal = if (label != null) 16.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            if (label != null) {
                Spacer(Modifier.width(8.dp))
                HudText(label, hud(fixedSp(15f), if (hot) Color.White else DashColors.TextPrimary, FontWeight.Black, 0.12f))
            }
        }
    }
}

/** The clock in black italic with a magenta glow; AM / PM small beside it. */
@Composable
private fun BarClock(clock: String) {
    val (digits, amPm) = splitClock(clock)
    Row {
        NumText(digits, hero(fixedSp(30f), DashColors.TextPrimary, glow = Magenta), Modifier.alignByBaseline())
        if (amPm != null) {
            Spacer(Modifier.width(4.dp))
            HudText(amPm.caps(), hud(fixedSp(14f), Magenta, FontWeight.Black), Modifier.alignByBaseline())
        }
    }
}

/** The stage card of the bar: the trip's distance and moving time, as the game shows its stage. */
@Composable
private fun BarStage() {
    val trip by LocationFeed.trip.collectAsState()
    val units = LocalUnits.current
    Row(
        modifier = Modifier
            .height(46.dp)
            .hudPanel(Cyan, slantMax = 10.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BarStat(stringResource(R.string.info_trip_title).caps(), "%.1f".format(units.distance(trip.distanceM / 1000.0)), units.distanceUnit.caps())
        Spacer(Modifier.width(16.dp))
        BarStat(stringResource(R.string.info_trip_time).caps(), stageTime(trip.movingMs), null)
    }
}

@Composable
private fun BarStat(label: String, value: String, unit: String?) {
    Column {
        HudText(label, hud(fixedSp(14f), Cyan, FontWeight.Black, 0.2f))
        Row {
            NumText(value, hero(fixedSp(19f), DashColors.TextPrimary), Modifier.alignByBaseline())
            if (unit != null) HudText(" $unit", hud(fixedSp(14f), DashColors.TextSecondary), Modifier.alignByBaseline())
        }
    }
}

/** FINISH and the arrival time while a route runs; nothing otherwise. */
@Composable
private fun BarFinish() {
    val nav by NavDirections.state.collectAsState()
    val arrival = if (nav.active) arrivalOf(nav) else null
    if (arrival == null) return
    Row(
        modifier = Modifier
            .padding(end = 4.dp)
            .height(46.dp)
            .hudPanel(Magenta, slantMax = 10.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudText(stringResource(R.string.skin_racing_finish), hud(fixedSp(14f), Magenta, FontWeight.Black, 0.2f))
        Spacer(Modifier.width(8.dp))
        NumText(arrival, hero(fixedSp(22f), DashColors.TextPrimary, glow = Magenta))
    }
}

/**
 * The OBD link on a skewed panel: a dot (hollow when off, lit with a glow when
 * live, blinking while connecting) and the letters OBD, with "!" on error so
 * it reads without colour. What a tap does is the shared [ObdMark]'s.
 */
@Composable
private fun BarObd(state: ObdConnectionState, onConnect: () -> Unit) {
    val color = obdStatusColor(state)
    val off = state == ObdConnectionState.DISCONNECTED
    val blink = if (state == ObdConnectionState.CONNECTING) rememberBlink(350L) else null
    val g = glowK
    ObdMark(state, onConnect, shape = SkewShape) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .hudPanel(if (off) DashColors.Muted else color, slantMax = 9.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(
                Modifier
                    .size(12.dp)
                    .cachedDraw(color, off, blink, g) {
                        val r = size.minDimension * 0.4f
                        val ring = Stroke(1.5.dp.toPx())
                        onDrawBehind {
                            val on = blink?.value ?: true
                            when {
                                off -> drawCircle(color, r, style = ring)
                                on -> {
                                    if (g > 0f) drawCircle(color.copy(alpha = 0.35f * g), r * 1.6f)
                                    drawCircle(color, r)
                                }
                                else -> drawCircle(color.copy(alpha = 0.3f), r)
                            }
                        }
                    }
            )
            Spacer(Modifier.width(8.dp))
            HudText(stringResource(R.string.dash_obd_short), hud(fixedSp(15f), DashColors.TextPrimary, FontWeight.Black, 0.12f))
            if (state == ObdConnectionState.ERROR) HudText("!", hud(fixedSp(15f), color, FontWeight.Black))
        }
    }
}

// ---------------------------------------------------------------- shared tile bits

/** Every tile: its own layer (the background redraws under it each step) and room for the neon halos. */
private val TileFrame = Modifier.fillMaxSize().graphicsLayer().padding(6.dp)

/** A moving time as a stage clock: "24:13", or "1:05:12" past the hour. */
private fun stageTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A clock time that ends an ETA part ("18:42", "6:42 PM"). */
private val CLOCK_ONLY = Regex("""\d{1,2}[:h.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?""")

/** The arrival time of the route, from its ETA line; null when the app gives none. */
private fun arrivalOf(nav: NavState): String? = nav.etaParts.firstOrNull { CLOCK_ONLY.matches(it) }?.caps()

/**
 * A quiet state on a HUD panel: [title] in black italic caps and [hint]
 * underneath, cyan when tapping does something.
 */
@Composable
private fun HudMessage(title: String, hint: String?, rim: Color, editing: Boolean, onTap: (() -> Unit)?) {
    HudPanel(TileFrame, rim, editing, onClick = onTap, clickLabel = hint, contentAlignment = Alignment.Center) {
        val titleSp = min(maxHeight.value * 0.26f, maxWidth.value / (title.length.coerceAtLeast(4) * 0.5f)).coerceIn(15f, 48f)
        val hintSp = (titleSp * 0.45f).coerceIn(11f, 18f)
        val roomy = maxHeight >= 64.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HudText(title.caps(), hud(fixedSp(titleSp), DashColors.TextPrimary, FontWeight.Black, 0.04f), maxLines = 2, align = TextAlign.Center)
            if (hint != null && roomy) {
                Spacer(Modifier.height(4.dp))
                HudText(
                    hint.caps(),
                    hud(fixedSp(hintSp), if (onTap != null) DashColors.AccentInk else DashColors.TextSecondary, FontWeight.Bold, 0.14f),
                    maxLines = 2,
                    align = TextAlign.Center
                )
            }
        }
    }
}

// ---------------------------------------------------------------- speed: the tachometer

private const val SEGMENTS = 27
private const val RED_FROM = 20
private const val ARC_START = 135f
private const val SEG_STEP = 10f
private const val SEG_SWEEP = 8f

/** Without the revs, the tacho shows the speed against this top speed. */
private const val SPEED_SCALE_KMH = 200f

/** Segments lit for [rpm] (against the 1.6 HDi's scale), else for [speedKmh]. */
private fun litSegments(rpm: Int?, speedKmh: Int?): Int {
    val f = when {
        rpm != null && rpm > 0 -> rpm / SKIN_RPM_MAX
        speedKmh != null -> speedKmh / SPEED_SCALE_KMH
        else -> 0f
    }
    return (f * SEGMENTS).roundToInt().coerceIn(0, SEGMENTS)
}

/** The gear's letter: R, 1 to 6, N while free-wheeling, a dash when it can't be told. */
private fun gearText(gear: Gear): String = when (gear) {
    Gear.Reverse -> "R"
    is Gear.Forward -> gear.n.toString()
    Gear.Free -> "N"
    Gear.Park -> "P"
    Gear.Drive -> "D"
    Gear.Stopped, Gear.Unknown -> "–"
}

/**
 * Speed: the game's tachometer when the tile is small; with room, the
 * driver's car on its strip of road beside (or under) it.
 */
@Composable
private fun RaceSpeed(env: SkinTileEnv) {
    val speed = rememberSpeedKmh()
    val gear = rememberGear()
    val obd = env.obdConnection == ObdConnectionState.CONNECTED
    val idle = env.obdConnection.isIdle
    val canConnect = speed == null && idle && !env.editing
    val noSignal = stringResource(R.string.info_speed_no_signal).caps()
    val source = speedSource(noSignal)
    val connectLabel = stringResource(R.string.skin_racing_connect_obd)
    val tap = rememberTapFeedback()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .then(
                if (canConnect) Modifier.clickable(onClickLabel = connectLabel, role = Role.Button) {
                    tap()
                    env.onConnectObd()
                } else Modifier
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        val boxW = maxWidth
        val boxH = maxHeight
        val wide = boxW >= boxH * 1.25f
        val stage = if (wide) boxW >= 440.dp && boxH >= 220.dp else boxH >= 430.dp && boxW >= 260.dp
        val sourceInk = if (speed != null) DashColors.Good else DashColors.Muted
        when {
            !stage -> Tacho(speed, gear, source, sourceInk, minOf(boxW, boxH))
            wide -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                CarStage(Modifier.weight(1f).fillMaxHeight())
                Tacho(speed, gear, source, sourceInk, minOf(boxH, boxW * 0.5f))
            }
            else -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Tacho(speed, gear, source, sourceInk, minOf(boxW, boxH * 0.55f))
                CarStage(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * The tachometer, [d] across: a disc of dark glass with a cyan halo, 27
 * segments over 270° (cyan when lit, dim purple unlit, the last seven a red
 * zone), the speed in huge black italic with a cyan glow, the unit in spaced
 * caps and the gear in a magenta hexagon; the source (OBD / GPS) under it on
 * a large dial. The segments follow the revs (or the speed without them) and
 * are read while drawing, on the dial's own layer.
 */
@Composable
private fun Tacho(speed: Int?, gear: State<GearReading>, source: String, sourceInk: Color, d: Dp) {
    val speedState = rememberUpdatedState(speed)
    val cyan = Cyan
    val red = DashColors.Critical
    val unlit = RaUnlit
    val redUnlit = RaRedUnlit
    val glass = RaGlass
    val g = glowK
    val units = LocalUnits.current
    val over = speedOver(speed)
    Box(Modifier.size(d), contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .cachedDraw(cyan, red, unlit, redUnlit, glass, g) {
                    val r = size.minDimension / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val margin = r * 0.06f
                    val ring = r * 0.17f
                    val arcR = r - margin - ring / 2f
                    val arcTopLeft = Offset(c.x - arcR, c.y - arcR)
                    val arcSize = Size(arcR * 2f, arcR * 2f)
                    val segStroke = Stroke(ring)
                    val glowStroke = Stroke(ring * 1.6f)
                    val halo = Brush.radialGradient(
                        0.8f to cyan.copy(alpha = 0.4f * g), 1f to cyan.copy(alpha = 0f), center = c, radius = r
                    )
                    val innerR = arcR - ring / 2f - 4.dp.toPx()
                    val innerStroke = Stroke(1.dp.toPx())
                    onDrawBehind {
                        if (g > 0f) drawCircle(halo, r, c)
                        drawCircle(glass, r - margin, c)
                        val lit = litSegments(gear.value.rpm, speedState.value)
                        if (lit > 0 && g > 0f) {
                            drawArc(cyan.copy(alpha = 0.22f * g), ARC_START, lit * SEG_STEP - 2f, false, arcTopLeft, arcSize, style = glowStroke)
                        }
                        for (i in 0 until SEGMENTS) {
                            val zone = i >= RED_FROM
                            val color = if (i < lit) (if (zone) red else cyan) else (if (zone) redUnlit else unlit)
                            drawArc(color, ARC_START + i * SEG_STEP + 1f, SEG_SWEEP, false, arcTopLeft, arcSize, style = segStroke)
                        }
                        drawCircle(cyan.copy(alpha = 0.25f), innerR, c, style = innerStroke)
                    }
                }
        )
        val numSp = d.value * 0.27f
        val unitSp = (d.value * 0.055f).coerceAtLeast(11f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val color = when {
                speed == null -> DashColors.Muted
                over -> DashColors.Warning
                else -> DashColors.TextPrimary
            }
            if (speed != null) {
                NumText(units.speed(speed).toString(), hero(fixedSp(numSp), color, glow = if (over) DashColors.Warning else cyan))
            } else {
                // A lone dash at display size reads as a bar, so the placeholder is smaller.
                NumText("–", hero(fixedSp(numSp * 0.6f), color))
            }
            Spacer(Modifier.height(d * 0.015f))
            HudText(units.speedUnit.caps(), hud(fixedSp(unitSp), cyan, FontWeight.Black, 0.25f))
            Spacer(Modifier.height(d * 0.03f))
            GearBadge(gear, d * 0.2f)
            if (d >= 200.dp) {
                Spacer(Modifier.height(d * 0.02f))
                HudText(source, hud(fixedSp((d.value * 0.042f).coerceAtLeast(11f)), sourceInk, FontWeight.Bold, 0.18f))
            }
        }
    }
}

/**
 * The gear in a magenta hexagon with a glow, [w] wide. It follows the gear's
 * letter only (a derived state), not every reading of the revs.
 */
@Composable
private fun GearBadge(gear: State<GearReading>, w: Dp) {
    val text by remember(gear) { derivedStateOf { gearText(gear.value.gear) } }
    val magenta = Magenta
    val g = glowK
    val description = stringResource(R.string.skin_racing_gear, text)
    Box(
        Modifier
            .size(width = w, height = w * 0.86f)
            .semantics { contentDescription = description }
            .cachedDraw(magenta, g) {
                val sw = size.width
                val sh = size.height
                val hex = Path().apply {
                    moveTo(sw * 0.25f, 0f); lineTo(sw * 0.75f, 0f); lineTo(sw, sh / 2f)
                    lineTo(sw * 0.75f, sh); lineTo(sw * 0.25f, sh); lineTo(0f, sh / 2f); close()
                }
                val halo = Stroke(6.dp.toPx(), join = StrokeJoin.Round)
                onDrawBehind {
                    if (g > 0f) drawPath(hex, magenta.copy(alpha = 0.35f * g), style = halo)
                    drawPath(hex, magenta)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        HudText(text, hero(fixedSp(w.value * 0.5f), Color.White))
    }
}

// ---------------------------------------------------------------- the car on its road

private const val STAGE_LOOP_MS = 6_000

/** Per stage loop: the car's bobs (every half second), underglow breaths, cross lines and dashes passing. */
private const val STAGE_BOBS = 12
private const val STAGE_PULSES = 5
private const val STAGE_ROLLS = 10
private const val STAGE_DASH_ROLLS = 20

/** The fallback car's drawing box (a rear view): 100 x 56 units. */
private const val ARCADE_W = 100f
private const val ARCADE_H = 56f

/** Where the car stands in a stage of [size]: as big as fits, its wheels near the bottom, centred on the road. */
private fun stageCarRect(size: Size, aspect: Float): Rect {
    val area = Rect(size.width * 0.06f, size.height * 0.2f, size.width * 0.94f, size.height * 0.93f)
    val cw = min(area.width, area.height * aspect)
    val ch = cw / aspect
    return Rect(Offset(area.center.x - cw / 2f, area.bottom - ch), Size(cw, ch))
}

/**
 * The driver's car (its 3/4 picture from MyCarLook, else its side, else a
 * drawn arcade car seen from behind) on a strip of road: a perspective road
 * with cyan edges and a rolling yellow centre line over a few grid lines, all
 * fading out toward the top so the strip melts into the page. Under the car
 * a cyan-magenta underglow breathes and the car bobs gently. Three layers:
 * the road redraws each ambient step, the glow and the car only move (their
 * layers' alpha and offset change, nothing is re-recorded).
 */
@Composable
private fun CarStage(modifier: Modifier) {
    val look by MyCarLook.shown.collectAsState()
    val style by MyCarLook.style.collectAsState()
    val view = look?.hero ?: look?.side
    val loop = rememberLoop(STAGE_LOOP_MS)
    val effects = DashColors.Effects
    val speed = if (effects == DashEffects.REDUCED) 2 else 1
    val g = glowK
    val light = DashColors.Light
    val cyan = Cyan
    val magenta = Magenta
    val yellow = tone(Color(0xFFFFE600), Color(0xFFF5A300))
    val warn = DashColors.Warning
    val road = tone(Color(0xF00D0A24), Color(0xF2E4D8EE))
    val aspect = view?.aspect ?: (ARCADE_W / ARCADE_H)
    Box(modifier) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .cachedDraw(cyan, magenta, yellow, road, g, speed, light) {
                    val w = size.width
                    val h = size.height
                    val top = h * 0.3f
                    val ground = h - top
                    val cx = w / 2f
                    val topHalf = w * 0.035f
                    val bottomHalf = w * 0.62f
                    val strip = Path().apply {
                        moveTo(cx - topHalf, top); lineTo(cx + topHalf, top)
                        lineTo(cx + bottomHalf, h); lineTo(cx - bottomHalf, h); close()
                    }
                    val stripFill = Brush.verticalGradient(
                        0f to road.copy(alpha = 0f), 0.4f to road, 1f to road, startY = top, endY = h
                    )
                    val edges = Path().apply {
                        moveTo(cx - topHalf, top); lineTo(cx - bottomHalf, h)
                        moveTo(cx + topHalf, top); lineTo(cx + bottomHalf, h)
                    }
                    val edgeInk = Brush.verticalGradient(
                        0f to cyan.copy(alpha = 0f), 0.5f to cyan.copy(alpha = 0.8f), 1f to cyan, startY = top, endY = h
                    )
                    val edgeGlow = Stroke(7.dp.toPx())
                    val edgeLine = Stroke(2.dp.toPx())
                    val grid = Brush.horizontalGradient(
                        0f to magenta.copy(alpha = 0f), 0.25f to magenta, 0.75f to magenta, 1f to magenta.copy(alpha = 0f)
                    )
                    val thin = 1.5.dp.toPx()
                    val dashBottom = w * 0.022f
                    val dashTop = w * 0.004f
                    onDrawBehind {
                        val t = loop.value
                        val roll = (t * STAGE_ROLLS / speed) % 1f
                        for (i in 0 until 9) {
                            val y = top + ground / (i + 1f - roll)
                            if (y > h) continue
                            val f = (y - top) / ground
                            drawLine(grid, Offset(0f, y), Offset(w, y), thin, alpha = (if (light) 0.45f else 0.75f) * (f * 1.6f).coerceIn(0f, 1f))
                        }
                        drawPath(strip, stripFill)
                        if (g > 0f) drawPath(edges, cyan.copy(alpha = 0.22f * g), style = edgeGlow)
                        drawPath(edges, edgeInk, style = edgeLine)
                        val phase = (t * STAGE_DASH_ROLLS / speed) % 1f
                        for (i in 0 until 12) {
                            val z0 = 1f + (i - phase) * DASH_GAP
                            val z1 = z0 + DASH_GAP * 0.45f
                            if (z1 <= 1f) continue
                            val f0 = 1f - 1f / z0.coerceAtLeast(1f)
                            if (f0 > 0.9f) break
                            val f1 = 1f - 1f / z1
                            val dw = dashBottom + (dashTop - dashBottom) * (f0 + f1) / 2f
                            drawLine(
                                yellow, Offset(cx, h - ground * f0), Offset(cx, h - ground * f1), dw,
                                alpha = (1.6f - 1.6f * f0).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
        )
        if (g > 0f) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.7f + 0.3f * (0.5f + 0.5f * sin(2f * PI.toFloat() * STAGE_PULSES * loop.value))
                    }
                    .cachedDraw(aspect, cyan, magenta, g) {
                        val car = stageCarRect(size, aspect)
                        val gc = Offset(car.center.x, car.bottom - car.height * 0.04f)
                        val rx = car.width * 0.56f
                        val ry = car.height * 0.15f
                        val glow = Brush.radialGradient(
                            0f to cyan.copy(alpha = 0.95f * g), 0.45f to magenta.copy(alpha = 0.5f * g), 1f to magenta.copy(alpha = 0f),
                            center = gc, radius = rx
                        )
                        onDrawBehind { scale(1f, ry / rx, gc) { drawCircle(glow, rx, gc) } }
                    }
            )
        }
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val bob = 0.5f + 0.5f * sin(2f * PI.toFloat() * (STAGE_BOBS / speed) * loop.value)
                    translationY = -bob * (if (speed == 2) 1.5f else 3f) * density
                }
                .cachedDraw(view, style, cyan, magenta, warn, g, light) {
                    val car = stageCarRect(size, aspect)
                    val shadowInk = if (light) Color(0xFF3B2A5C).copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.55f)
                    val shadowTopLeft = Offset(car.left + car.width * 0.06f, car.bottom - car.height * 0.07f)
                    val shadowSize = Size(car.width * 0.88f, car.height * 0.12f)
                    val rim = ColorFilter.tint(magenta, BlendMode.SrcIn)
                    val nudge = 2.dp.toPx().roundToInt()
                    val at = IntOffset(car.left.roundToInt(), car.top.roundToInt())
                    val carSize = IntSize(car.width.roundToInt().coerceAtLeast(1), car.height.roundToInt().coerceAtLeast(1))
                    onDrawBehind {
                        drawOval(shadowInk, shadowTopLeft, shadowSize)
                        if (view != null) {
                            // A magenta neon rim: the car's outline drawn four times a hair off each side, behind it.
                            if (g > 0f) {
                                for ((dx, dy) in RIM_NUDGES) {
                                    drawImage(
                                        view.outline,
                                        dstOffset = IntOffset(at.x + dx * nudge, at.y + dy * nudge),
                                        dstSize = carSize,
                                        alpha = 0.6f * g,
                                        colorFilter = rim
                                    )
                                }
                            }
                            drawCarView(view, style, car, cyan, warn)
                        } else {
                            drawArcadeCar(car, light, magenta, g)
                        }
                    }
                }
        )
    }
}

private val RIM_NUDGES = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

private val ArcadeCabin = Path().apply {
    moveTo(22f, 5f); lineTo(78f, 5f); lineTo(91f, 27f); lineTo(9f, 27f); close()
}
private val ArcadeWindow = Path().apply {
    moveTo(26f, 8.5f); lineTo(74f, 8.5f); lineTo(84f, 24f); lineTo(16f, 24f); close()
}
private val ArcadeGlint = Path().apply {
    moveTo(30f, 8.5f); lineTo(38f, 8.5f); lineTo(30f, 24f); lineTo(22f, 24f); close()
}

/**
 * The fallback car: an arcade racer seen from behind, as racing games show
 * the player's car. Wheels, a purple body, the cabin with a cyan-tinted rear
 * window, a spoiler, a full-width tail-light bar in magenta and the plate.
 */
private fun DrawScope.drawArcadeCar(dst: Rect, light: Boolean, lights: Color, g: Float) {
    val body = if (light) Color(0xFF6C58B8) else Color(0xFF3A2A7A)
    val dark = if (light) Color(0xFF3E2F80) else Color(0xFF1A1236)
    val tyre = Color(0xFF0B0816)
    withTransform({
        translate(dst.left, dst.top)
        scale(dst.width / ARCADE_W, dst.height / ARCADE_H, Offset.Zero)
    }) {
        drawRoundRect(tyre, Offset(7f, 38f), Size(17f, 18f), CornerRadius(3f))
        drawRoundRect(tyre, Offset(76f, 38f), Size(17f, 18f), CornerRadius(3f))
        drawPath(ArcadeCabin, dark)
        drawPath(ArcadeWindow, Brush.verticalGradient(listOf(Color(0xFF00B8D4), Color(0xFF0A0A24)), startY = 8.5f, endY = 24f))
        drawPath(ArcadeGlint, Color.White.copy(alpha = 0.18f))
        drawRoundRect(
            Brush.verticalGradient(listOf(body, dark), startY = 24f, endY = 50f),
            Offset(2f, 25f), Size(96f, 25f), CornerRadius(7f)
        )
        drawRect(dark, Offset(3f, 19f), Size(94f, 4f))
        drawRect(dark, Offset(18f, 22f), Size(3f, 4f))
        drawRect(dark, Offset(79f, 22f), Size(3f, 4f))
        if (g > 0f) drawRoundRect(lights.copy(alpha = 0.35f * g), Offset(4f, 28f), Size(92f, 11f), CornerRadius(4f))
        drawRoundRect(lights, Offset(7f, 30.5f), Size(86f, 5f), CornerRadius(2f))
        drawRect(Color(0xFFE8E4F4), Offset(41f, 40f), Size(18f, 6f))
        drawRect(tyre, Offset(14f, 47f), Size(72f, 3f))
    }
}

// ---------------------------------------------------------------- telemetry: the stage card

private class StageCell(val label: String, val value: String, val unit: String, val labelInk: Color, val valueInk: Color)

/**
 * Telemetry as the game's stage card: STAGE with the OBD state, then the
 * trip, its moving time and the best 0-100 (when the performance timer has
 * one), and with OBD live the revs, coolant, battery and load, laid out in as
 * many columns and rows as the tile holds. Roomy tiles add the car on its
 * road beside or under the card. Without OBD the card connects on a tap.
 */
@Composable
private fun RaceTelemetry(env: SkinTileEnv) {
    val state = env.obdConnection
    val tapConnect = state.isIdle && !env.editing
    val connectLabel = stringResource(R.string.skin_racing_connect_obd)
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().padding(6.dp)) {
        val wideCar = maxWidth >= 640.dp && maxHeight >= 240.dp && maxWidth >= maxHeight * 1.6f
        val tallCar = !wideCar && maxHeight >= 400.dp && maxWidth >= 280.dp
        val card: @Composable (Modifier) -> Unit = { m ->
            HudPanel(m, Cyan, env.editing, onClick = if (tapConnect) env.onConnectObd else null, clickLabel = connectLabel) {
                StageCard(env, maxWidth, maxHeight)
            }
        }
        when {
            wideCar -> Row(Modifier.fillMaxSize()) {
                card(Modifier.weight(1.15f).fillMaxHeight())
                Spacer(Modifier.width(10.dp))
                CarStage(Modifier.weight(1f).fillMaxHeight())
            }
            tallCar -> Column(Modifier.fillMaxSize()) {
                card(Modifier.fillMaxWidth().weight(1f))
                CarStage(Modifier.fillMaxWidth().weight(0.9f))
            }
            else -> card(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun StageCard(env: SkinTileEnv, w: Dp, h: Dp) {
    val headerSp = (h.value * 0.075f).coerceIn(12f, 18f)
    val state = env.obdConnection
    val chooseAdapter = stringResource(R.string.skin_racing_choose_adapter)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SkewTick(Cyan, (headerSp * 0.9f).dp)
                Spacer(Modifier.width(6.dp))
                HudText(stringResource(R.string.skin_racing_stage), hud(fixedSp(headerSp), Cyan, FontWeight.Black, 0.2f))
                Spacer(Modifier.weight(1f))
                ObdWord(state, headerSp)
            }
            Spacer(Modifier.height(6.dp))
            StageCells(env, Modifier.weight(1f).fillMaxWidth())
        }
        if (state == ObdConnectionState.CONNECTED && w >= 160.dp) {
            // Invisible 48 dp target over "LIVE": pick another adapter, as the standard tile does.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-14).dp)
                    .size(width = 96.dp, height = 48.dp)
                    .clickable(enabled = !env.editing, role = Role.Button, onClick = env.onPickDevice)
                    .semantics { contentDescription = chooseAdapter }
            )
        }
    }
}

/** LIVE in green, LINKING blinking, or NO OBD · TAP / OBD ERROR · TAP in magenta. */
@Composable
private fun ObdWord(state: ObdConnectionState, sizeSp: Float) {
    val style = hud(fixedSp(sizeSp), Magenta, FontWeight.Black, 0.14f)
    when (state) {
        ObdConnectionState.CONNECTED -> HudText(stringResource(R.string.skin_racing_live), style.copy(color = DashColors.Good))
        ObdConnectionState.CONNECTING -> {
            val blink = rememberBlink(500L)
            HudText(
                stringResource(R.string.skin_racing_linking),
                style.copy(color = Cyan),
                Modifier.graphicsLayer { alpha = if (blink.value) 1f else 0.35f }
            )
        }
        ObdConnectionState.ERROR -> HudText(stringResource(R.string.skin_racing_obd_error_tap), style)
        ObdConnectionState.DISCONNECTED -> HudText(stringResource(R.string.skin_racing_no_obd_tap), style)
    }
}

/** The best 0-100 km/h in ms from the performance timer (PerfTimer.kt), live or as last saved; null without one. */
@Composable
private fun rememberBest0to100(): Long? {
    val context = LocalContext.current
    val best by PerfTimer.best.collectAsState()
    // The timer loads its bests only while its own tile shows: read what it saved otherwise.
    val saved = remember(best) {
        context.getSharedPreferences("perf_timer", android.content.Context.MODE_PRIVATE).getLong("to100", 0L).takeIf { it > 0L }
    }
    return best.to100 ?: saved
}

/** The card's cells, in their own scope: a GPS fix or an OBD sample recomposes only them. */
@Composable
private fun StageCells(env: SkinTileEnv, modifier: Modifier) {
    val trip by LocationFeed.trip.collectAsState()
    val units = LocalUnits.current
    val best = rememberBest0to100()
    // Whatever the car answers is shown, adapter or not.
    val g = rememberPanelGauges(K.RPM, K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
    val cyan = Cyan
    val ink = DashColors.TextPrimary
    val tripLabel = stringResource(R.string.info_trip_title).caps()
    val timeLabel = stringResource(R.string.info_trip_time).caps()
    val bestLabel = stringResource(R.string.widgets_perf_best, "0–100").caps()
    val rpmLabel = stringResource(R.string.skin_racing_rpm)
    val tempLabel = stringResource(R.string.skin_racing_temp)
    val battLabel = stringResource(R.string.skin_racing_batt)
    val loadLabel = stringResource(R.string.skin_racing_load)
    val cells = buildList {
        add(StageCell(tripLabel, "%.1f".format(units.distance(trip.distanceM / 1000.0)), units.distanceUnit.caps(), cyan, ink))
        add(StageCell(timeLabel, stageTime(trip.movingMs), "", cyan, ink))
        if (best != null) add(StageCell(bestLabel, "%.1f".format(best / 1000.0), "S", RaYellowInk, ink))
        g.rpm?.let { add(StageCell(rpmLabel, groupThousands(it), "", cyan, ink)) }
        g.coolantTempC?.let { c ->
            val hot = c >= 105
            add(StageCell(tempLabel, "${units.temp(c)}", units.tempUnit, if (hot) DashColors.Warning else cyan, if (hot) DashColors.Warning else ink))
        }
        g.voltage?.let { v ->
            val bad = v !in 12.0..15.0
            add(StageCell(battLabel, "%.1f".format(v), "V", if (bad) DashColors.Warning else cyan, if (bad) DashColors.Warning else ink))
        }
        g.engineLoadPct?.let { add(StageCell(loadLabel, "$it", "%", cyan, ink)) }
    }
    BoxWithConstraints(modifier) {
        val n = cells.size
        val cols = (maxWidth / 104.dp).toInt().coerceIn(1, n)
        val maxRows = (maxHeight / 44.dp).toInt().coerceAtLeast(1)
        val rows = ceil(n / cols.toFloat()).toInt().coerceAtMost(maxRows)
        val shown = cells.take(rows * cols)
        val cellW = maxWidth / cols
        val cellH = maxHeight / rows
        val valueSp = min(cellH.value * 0.46f, cellW.value * 0.22f).coerceIn(15f, 60f)
        val labelSp = (valueSp * 0.34f).coerceIn(11f, 16f)
        Column(Modifier.fillMaxSize()) {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    for (c in 0 until cols) {
                        val cell = shown.getOrNull(r * cols + c)
                        Box(Modifier.weight(1f)) {
                            if (cell != null) StageCellView(cell, valueSp, labelSp, cellW)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageCellView(cell: StageCell, valueSp: Float, labelSp: Float, cellW: Dp) {
    // The value and its unit measured together in the value's face (wider than the unit's), so the figures never ellipsize.
    val sp = fitSp(template(cell.value) + " " + cell.unit, hero(100.sp, Color.White), cellW - 10.dp, 1000.dp, 6f, valueSp)
    Column(Modifier.padding(end = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkewTick(cell.labelInk, (labelSp * 0.85f).dp)
            Spacer(Modifier.width(5.dp))
            HudText(cell.label, hud(fixedSp(labelSp), cell.labelInk, FontWeight.Black, 0.16f))
        }
        Row {
            NumText(cell.value, hero(sp.sp, cell.valueInk), Modifier.alignByBaseline())
            if (cell.unit.isNotEmpty()) {
                NumText(" ${cell.unit}", hud((sp * 0.42f).coerceAtLeast(MIN_TEXT_DP).sp, DashColors.TextSecondary, FontWeight.Bold), Modifier.alignByBaseline())
            }
        }
    }
}

// ---------------------------------------------------------------- media: the ticker

/**
 * Music as the game's radio ticker: a skewed magenta-rimmed panel with a note
 * glyph, the title in black italic caps and the artist in lavender; under it a
 * segmented progress bar with the times and three skewed buttons (play in the
 * hot magenta gradient). Short tiles put the ticker on one line with the
 * buttons beside it. Without media access the panel opens the settings.
 */
@Composable
private fun RaceMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val access = env.hasMediaAccess
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val context = env.context
    val title = when {
        !access -> stringResource(R.string.info_media_access_needed)
        hasTrack -> ms.title
        else -> stringResource(R.string.info_nothing_playing)
    }.caps()
    val sub = when {
        !access -> stringResource(R.string.skin_racing_tap_to_allow)
        hasTrack -> ms.artist.caps().ifBlank { stringResource(R.string.skin_racing_unknown_artist) }
        else -> stringResource(R.string.skin_racing_start_music)
    }
    val subInk = if (!access) Cyan else DashColors.TextSecondary
    val allow = stringResource(R.string.skin_racing_allow_access)
    val openAccess = { CarMediaController.openNotificationAccessSettings(context) }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().padding(6.dp)) {
        val boxW = maxWidth
        val boxH = maxHeight
        val strip = boxH < 150.dp
        val panel: @Composable (Modifier) -> Unit = { m ->
            HudPanel(m, Magenta, env.editing, onClick = if (!access) openAccess else null, clickLabel = allow) {
                Ticker(title, sub, subInk, ms.isPlaying, maxWidth, maxHeight)
            }
        }
        if (strip) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                panel(Modifier.weight(1f).fillMaxHeight())
                if (access && boxW >= 340.dp) {
                    Spacer(Modifier.width(8.dp))
                    MediaButtons(env, Modifier.width(minOf(boxW * 0.42f, 210.dp)).height(minOf(boxH, 64.dp)))
                }
            }
        } else {
            val ctrlH = (maxHeight * 0.28f).coerceIn(48.dp, 64.dp)
            val showProgress = access && ms.durationMs > 0L && maxHeight >= 210.dp
            Column(Modifier.fillMaxSize()) {
                panel(Modifier.fillMaxWidth().weight(1f))
                if (showProgress) {
                    Spacer(Modifier.height(10.dp))
                    MediaProgress(ms, env.mediaController, Modifier.fillMaxWidth())
                }
                if (access) {
                    Spacer(Modifier.height(10.dp))
                    MediaButtons(env, Modifier.fillMaxWidth().height(ctrlH))
                }
            }
        }
    }
}

/** The ticker's line: a glowing note, the title and the artist, side by side when short, stacked when tall. */
@Composable
private fun Ticker(title: String, sub: String, subInk: Color, playing: Boolean, w: Dp, h: Dp) {
    val oneLine = h < 70.dp
    val titleSp = min(h.value * (if (oneLine) 0.5f else 0.3f), w.value / 10f).coerceIn(14f, 46f)
    val subSp = (titleSp * 0.68f).coerceIn(12f, 28f)
    val icon = (titleSp * 1.15f).coerceIn(18f, 46f).dp
    val magenta = Magenta
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(icon).glowHalo(magenta.copy(alpha = if (playing) 0.45f else 0.2f), 0.75f),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = magenta, modifier = Modifier.size(icon * 0.85f))
        }
        Spacer(Modifier.width(10.dp))
        if (oneLine) {
            Row(Modifier.weight(1f)) {
                HudText(title, hud(fixedSp(titleSp), DashColors.TextPrimary, FontWeight.Black, 0.04f), Modifier.alignByBaseline().weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                HudText(sub, hud(fixedSp(subSp), subInk), Modifier.alignByBaseline())
            }
        } else {
            Column(Modifier.weight(1f)) {
                HudText(title, hud(fixedSp(titleSp), DashColors.TextPrimary, FontWeight.Black, 0.04f), maxLines = if (h > 150.dp) 2 else 1)
                HudText(sub, hud(fixedSp(subSp), subInk))
            }
        }
    }
}

/** Elapsed time, the segmented progress bar and the length; in its own scope so only it follows the playback position. */
@Composable
private fun MediaProgress(ms: MediaState, controller: CarMediaController, modifier: Modifier) {
    val positionMs = rememberMediaPosition(ms, controller)
    val fraction = if (ms.durationMs > 0L) (positionMs.toFloat() / ms.durationMs).coerceIn(0f, 1f) else 0f
    val timeStyle = hud(fixedSp(14f), DashColors.TextSecondary, FontWeight.Bold)
    Row(modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        NumText(formatTrackTime(positionMs), timeStyle)
        Spacer(Modifier.width(8.dp))
        SegBar(fraction, Magenta, Modifier.weight(1f).height(14.dp), segment = 12.dp)
        Spacer(Modifier.width(8.dp))
        NumText(formatTrackTime(ms.durationMs), timeStyle)
    }
}

/** Previous, play / pause (the hot one) and next, as skewed buttons, at most 140 dp each. */
@Composable
private fun MediaButtons(env: SkinTileEnv, modifier: Modifier) {
    val c = env.mediaController
    val playing = env.mediaState.isPlaying
    val enabled = !env.editing
    val cyan = Cyan
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 440.dp).fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val b = Modifier.weight(1f).fillMaxHeight()
            SkewButton(stringResource(R.string.skin_racing_cd_previous), enabled, { c.previous() }, b, dashed = env.editing) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = cyan, modifier = Modifier.size(26.dp))
            }
            SkewButton(
                stringResource(if (playing) R.string.skin_racing_cd_pause else R.string.skin_racing_cd_play),
                enabled, { c.playPause() }, b, hot = true, dashed = env.editing
            ) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            SkewButton(stringResource(R.string.skin_racing_cd_next), enabled, { c.next() }, b, dashed = env.editing) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, tint = cyan, modifier = Modifier.size(26.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- navigation: banner and minimap

/**
 * Directions as the game's call-outs: a skewed yellow banner "RIGHT · 300 M"
 * with the turn glyph and the instruction, over a hard magenta offset shadow;
 * banner and shadow pulse. Wide tiles add a round neon minimap of the turn,
 * tall ones put it under the banner; the ETA line runs on a glass strip.
 * Tap opens the navigation app; without a route or access, a quiet panel.
 */
@Composable
private fun RaceNavigation(env: SkinTileEnv) {
    val nav by NavDirections.state.collectAsState()
    val context = env.context
    val canTap = !env.editing
    when {
        !env.hasMediaAccess -> HudMessage(
            stringResource(R.string.info_directions_access_title), stringResource(R.string.skin_racing_tap_to_allow), Magenta, env.editing,
            if (canTap) ({ CarMediaController.openNotificationAccessSettings(context) }) else null
        )
        !nav.active -> HudMessage(
            stringResource(R.string.info_directions_no_route), stringResource(R.string.skin_racing_tap_open_maps), Cyan, env.editing,
            if (canTap) ({ openNavigationApp(context, nav) }) else null
        )
        else -> {
            val side = nav.turnSide()
            val openLabel = stringResource(R.string.skin_racing_open_navigation)
            val tap = rememberTapFeedback()
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .clickable(enabled = canTap, onClickLabel = openLabel, role = Role.Button) {
                        tap()
                        openNavigationApp(context, nav)
                    }
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                val eta = nav.etaParts
                val boxW = maxWidth
                val boxH = maxHeight
                val etaH = 40.dp
                val wide = maxWidth >= 420.dp && maxHeight >= 150.dp && maxWidth >= maxHeight * 1.45f
                val tall = !wide && maxHeight >= 300.dp && maxWidth >= 200.dp && maxHeight >= maxWidth * 0.95f
                val showEta = eta.isNotEmpty() && maxHeight >= (if (wide || tall) 150.dp else 120.dp)
                val gap = 10.dp
                when {
                    wide -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        val map = minOf(boxH, boxW * 0.36f)
                        Minimap(side, map)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            val bannerH = (boxH - (if (showEta) etaH + gap else 0.dp)).coerceAtMost(132.dp)
                            TurnBanner(nav, side, Modifier.fillMaxWidth().height(bannerH))
                            if (showEta) {
                                Spacer(Modifier.height(gap))
                                EtaStrip(eta, env.editing, Modifier.fillMaxWidth().height(etaH))
                            }
                        }
                    }
                    tall -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val bannerH = (boxH * 0.3f).coerceIn(72.dp, 132.dp)
                        TurnBanner(nav, side, Modifier.fillMaxWidth().height(bannerH))
                        Spacer(Modifier.height(gap))
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val room = boxH - bannerH - gap * 2 - (if (showEta) etaH else 0.dp)
                            Minimap(side, minOf(boxW, room).coerceAtLeast(48.dp))
                        }
                        if (showEta) {
                            Spacer(Modifier.height(gap))
                            EtaStrip(eta, env.editing, Modifier.fillMaxWidth().height(etaH))
                        }
                    }
                    else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        val bannerH = (boxH - (if (showEta) etaH + gap else 0.dp)).coerceAtMost(132.dp)
                        TurnBanner(nav, side, Modifier.fillMaxWidth().height(bannerH))
                        if (showEta) {
                            Spacer(Modifier.height(gap))
                            EtaStrip(eta, env.editing, Modifier.fillMaxWidth().height(etaH))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The yellow call-out: a skewed yellow-to-gold banner with a glow, the turn
 * glyph and "RIGHT · 300 M" in black italic with the instruction under it,
 * in dark ink in both modes. Behind it a hard magenta copy offset down and
 * right. Both breathe (1.4 s) on their own layers: only the layers' scale and
 * alpha change, nothing is redrawn. Still with effects off.
 */
@Composable
private fun TurnBanner(nav: NavState, side: TurnSide, modifier: Modifier) {
    val pulse = rememberLoop(1_400, reverse = true, rest = 0f)
    val yellow = RaYellow
    val gold = RaGold
    val magenta = Magenta
    val g = glowK
    val (value, unit) = nav.distanceParts
    val distance = listOf(value, unit.caps()).filter { it.isNotEmpty() }.joinToString(" ")
    val word = when (side) {
        TurnSide.LEFT -> stringResource(R.string.skin_racing_left)
        TurnSide.RIGHT -> stringResource(R.string.skin_racing_right)
        TurnSide.AHEAD -> null
    }
    val headline = listOfNotNull(word, distance.ifEmpty { null }).joinToString(DOT).ifEmpty { nav.instruction.caps() }
    BoxWithConstraints(modifier) {
        val o = minOf(8.dp, maxHeight * 0.07f)
        val faceW = maxWidth - o
        val faceH = maxHeight - o
        val slant = slantDp(faceW, faceH)
        Spacer(
            Modifier
                .padding(start = o, top = o)
                .size(faceW, faceH)
                .graphicsLayer {
                    val p = pulse.value
                    alpha = 0.7f + 0.3f * p
                    scaleX = 1f + 0.03f * p
                    scaleY = 1f + 0.03f * p
                }
                .cachedDraw(magenta) {
                    val path = skewPath(size.width, size.height, slantOf(size, MAX_SLANT.toPx()))
                    onDrawBehind { drawPath(path, magenta) }
                }
        )
        Box(
            Modifier
                .size(faceW, faceH)
                .graphicsLayer {
                    val p = pulse.value
                    scaleX = 1f + 0.03f * p
                    scaleY = 1f + 0.03f * p
                }
                .cachedDraw(yellow, gold, g) {
                    val k = slantOf(size, MAX_SLANT.toPx())
                    val path = skewPath(size.width, size.height, k)
                    val fill = Brush.horizontalGradient(listOf(yellow, gold))
                    val halo = Stroke(12.dp.toPx(), join = StrokeJoin.Miter)
                    val spec = Color.White.copy(alpha = 0.55f)
                    val specY = 2.dp.toPx()
                    onDrawBehind {
                        if (g > 0f) drawPath(path, yellow.copy(alpha = 0.3f * g), style = halo)
                        drawPath(path, fill)
                        drawLine(spec, Offset(k + 4.dp.toPx(), specY), Offset(size.width - 4.dp.toPx(), specY), 1.5.dp.toPx())
                    }
                }
        ) {
            val padX = slant * 0.6f + 12.dp
            val glyph = minOf(faceH * 0.62f, 72.dp)
            val textW = faceW - padX * 2 - glyph - 12.dp
            val lines = if (faceH >= 110.dp) 2 else 1
            // Figures never ellipsize: the headline shrinks to fit, and under 22 sp the side word goes
            // (the arrow shows the side), leaving the distance alone at whatever size fits.
            val heads = if (word != null && distance.isNotEmpty()) listOf(headline, distance) else listOf(headline)
            val (head, headSp) = firstFitting(heads, hero(100.sp, BannerInk), textW, faceH * (if (lines == 2) 0.42f else 0.5f), 22f, 64f)
            val subSp = (headSp * 0.5f).coerceIn(12f, 24f)
            Row(
                Modifier.fillMaxSize().padding(horizontal = padX, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TurnGlyph(nav, side, glyph)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // Only the instruction stand-in (no distance) is words, and may end in an ellipsis.
                    if (distance.isEmpty()) HudText(head, hero(headSp.sp, BannerInk)) else NumText(head, hero(headSp.sp, BannerInk))
                    if (faceH >= 64.dp && nav.instruction.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        HudText(nav.instruction, hud(subSp.sp, BannerInk, FontWeight.Bold, 0.02f), maxLines = lines)
                    }
                }
            }
        }
    }
}

/** The navigation app's own turn arrow in banner ink; without one, a drawn arrow for [side]. */
@Composable
private fun TurnGlyph(nav: NavState, side: TurnSide, size: Dp) {
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = nav.instruction, colorFilter = ColorFilter.tint(BannerInk), modifier = Modifier.size(size))
    } else {
        Spacer(
            Modifier
                .size(size)
                .semantics { contentDescription = nav.instruction }
                .cachedDraw(side) {
                    // The mockup's 24-unit arrow: a stem that bends toward the turn, and its head.
                    val u = this.size.minDimension / 24f
                    val stroke = Stroke(2.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val arrow = Path().apply {
                        when (side) {
                            TurnSide.RIGHT -> {
                                moveTo(8f * u, 21f * u); lineTo(8f * u, 13f * u)
                                quadraticTo(8f * u, 9f * u, 12f * u, 9f * u); lineTo(20f * u, 9f * u)
                                moveTo(16f * u, 5f * u); lineTo(20f * u, 9f * u); lineTo(16f * u, 13f * u)
                            }
                            TurnSide.LEFT -> {
                                moveTo(16f * u, 21f * u); lineTo(16f * u, 13f * u)
                                quadraticTo(16f * u, 9f * u, 12f * u, 9f * u); lineTo(4f * u, 9f * u)
                                moveTo(8f * u, 5f * u); lineTo(4f * u, 9f * u); lineTo(8f * u, 13f * u)
                            }
                            TurnSide.AHEAD -> {
                                moveTo(12f * u, 21f * u); lineTo(12f * u, 4f * u)
                                moveTo(7f * u, 9f * u); lineTo(12f * u, 4f * u); lineTo(17f * u, 9f * u)
                            }
                        }
                    }
                    onDrawBehind { drawPath(arrow, BannerInk, style = stroke) }
                }
        )
    }
}

/** The minimap's frame: 220 units across, as in the mockup's SVG. */
private const val MAP_UNITS = 220f

/**
 * A round neon minimap of the turn, [d] across: dark glass with a cyan ring
 * and halo, a few dim streets, the route in magenta (glowing) turning toward
 * [side], and the car as a yellow arrow. Static: drawn once per size.
 */
@Composable
private fun Minimap(side: TurnSide, d: Dp) {
    val cyan = Cyan
    val magenta = Magenta
    val yellow = RaYellow
    val street = RaUnlit
    val glass = RaGlass
    val g = glowK
    Spacer(
        Modifier
            .size(d)
            .cachedDraw(side, cyan, magenta, yellow, street, glass, g) {
                val ringW = 3.dp.toPx()
                val r = size.minDimension / 2f - 7.dp.toPx()
                val c = Offset(size.width / 2f, size.height / 2f)
                val k = 2f * r / MAP_UNITS
                val disc = Path().apply { addOval(Rect(c, r)) }
                val mirror = side == TurnSide.LEFT
                fun x(v: Float) = if (mirror) MAP_UNITS - v else v
                val streets = Path().apply {
                    moveTo(0f, 80f); lineTo(MAP_UNITS, 80f)
                    moveTo(110f, 0f); lineTo(110f, MAP_UNITS)
                    moveTo(x(50f), 0f); lineTo(x(50f), 80f)
                    moveTo(x(0f), 165f); cubicTo(x(70f), 155f, x(150f), 160f, x(220f), 145f)
                }
                val route = Path().apply {
                    moveTo(110f, MAP_UNITS)
                    when (side) {
                        TurnSide.AHEAD -> lineTo(110f, 0f)
                        else -> {
                            lineTo(110f, 86f)
                            quadraticTo(110f, 80f, x(116f), 80f)
                            lineTo(x(MAP_UNITS), 80f)
                        }
                    }
                }
                val car = Path().apply {
                    moveTo(110f, 116f); lineTo(120f, 140f); lineTo(110f, 134f); lineTo(100f, 140f); close()
                }
                val streetStroke = Stroke(11f)
                val routeStroke = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val routeGlow = Stroke(16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val halo = Brush.radialGradient(0.85f to cyan.copy(alpha = 0.45f * g), 1f to cyan.copy(alpha = 0f), center = c, radius = size.minDimension / 2f)
                val inner = Brush.radialGradient(0.7f to Color.Transparent, 1f to cyan.copy(alpha = 0.2f * g), center = c, radius = r)
                onDrawBehind {
                    if (g > 0f) drawCircle(halo, size.minDimension / 2f, c)
                    drawCircle(glass, r, c)
                    clipPath(disc) {
                        withTransform({
                            translate(c.x - r, c.y - r)
                            scale(k, k, Offset.Zero)
                        }) {
                            drawPath(streets, street, style = streetStroke)
                            if (g > 0f) drawPath(route, magenta.copy(alpha = 0.3f * g), style = routeGlow)
                            drawPath(route, magenta, style = routeStroke)
                            drawPath(car, yellow)
                        }
                        if (g > 0f) drawCircle(inner, r, c)
                    }
                    drawCircle(cyan, r, c, style = Stroke(ringW))
                }
            }
    )
}

/** The ETA line ("FINISH 18:42 · 12 MIN · 6.4 KM") on a thin glass strip. */
@Composable
private fun EtaStrip(parts: List<String>, editing: Boolean, modifier: Modifier) {
    val finish = stringResource(R.string.skin_racing_finish)
    val words = parts.map { p -> if (CLOCK_ONLY.matches(p)) "$finish ${p.caps()}" else p.caps() }
    // Longest first: the whole line, then fewer parts from the end, so no figure is ever cut.
    val candidates = remember(words) { (words.size downTo 1).map { words.take(it).joinToString(DOT) } }
    HudPanel(modifier, Magenta, editing) {
        val style = hud(15.sp, DashColors.TextPrimary, FontWeight.Black, 0.08f)
        val (text, sp) = firstFitting(candidates, style, maxWidth - 20.dp, maxHeight, 12f, 15f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkewTick(RaYellowInk, 12.dp)
            Spacer(Modifier.width(8.dp))
            NumText(text, style.copy(fontSize = sp.sp, lineHeight = (sp * 1.12f).sp))
        }
    }
}

// ---------------------------------------------------------------- clock, weather, range

/**
 * Clock: the date in magenta caps, the time in huge black italic with a
 * magenta glow and, while a route runs, FINISH and the arrival in yellow
 * caps. Tap opens the alarms.
 */
@Composable
private fun RaceClock(env: SkinTileEnv) {
    val now = rememberNow(60_000L)
    val locale = Locale.getDefault()
    val units = LocalUnits.current
    val timeFmt = rememberDateFormat(units.digitsPattern())
    val dateFmt = rememberDateFormat("EEEdMMM", best = true)
    val nav by NavDirections.state.collectAsState()
    val arrival = if (nav.active) arrivalOf(nav) else null
    val context = env.context
    val time = remember(now, timeFmt) { timeFmt.format(now) }
    val amPm = remember(now, units) { units.amPm(now, locale)?.caps() }
    val date = remember(now, dateFmt) { dateFmt.format(now).caps() }
    val finish = stringResource(R.string.skin_racing_finish)
    HudPanel(
        TileFrame, Magenta, env.editing,
        onClick = { openClockApp(context) }, clickLabel = stringResource(R.string.skin_racing_open_clock),
        contentAlignment = Alignment.Center
    ) {
        val capsSp = (maxHeight.value * 0.11f).coerceIn(11f, 20f)
        val showDate = maxHeight >= 80.dp
        val showFinish = arrival != null && maxHeight >= 120.dp
        val panelW = maxWidth
        val lines = (if (showDate) 1 else 0) + (if (showFinish) 1 else 0)
        val below = (capsSp * 1.25f * lines + 6f).dp
        val timeSp = fitSp(template(time), hero(100.sp, Color.White), maxWidth * (if (amPm != null) 0.8f else 0.98f), maxHeight - below, 8f, 220f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showDate) HudText(date, hud(fixedSp(capsSp), Magenta, FontWeight.Black, 0.2f))
            Row {
                NumText(time, hero(timeSp.sp, DashColors.TextPrimary, glow = Magenta), Modifier.alignByBaseline())
                if (amPm != null) {
                    Spacer(Modifier.width(4.dp))
                    HudText(amPm, hud((timeSp * 0.24f).coerceAtLeast(12f).sp, Magenta, FontWeight.Black), Modifier.alignByBaseline())
                }
            }
            if (showFinish) {
                val finishStyle = hud(100.sp, RaYellowInk, FontWeight.Black, 0.16f)
                val finishSp = fitSp("$finish ${template(arrival ?: "")}", finishStyle, panelW, 1000.dp, 6f, capsSp)
                NumText("$finish $arrival", finishStyle.copy(fontSize = finishSp.sp, lineHeight = (finishSp * 1.12f).sp))
            }
        }
    }
}

/**
 * Weather: the condition's icon in yellow and the temperature in black
 * italic with a cyan glow, the condition in cyan caps and feels / wind /
 * low-high in lavender; wide tiles set the details beside the temperature.
 */
@Composable
private fun RaceWeather(env: SkinTileEnv) {
    val w = rememberWeather()
    val wait = rememberWeatherWait()
    if (w == null) {
        // Says why there is none, so "loading" never stands for a whole drive without GPS or network.
        val title = when (wait) {
            WeatherWait.LOADING -> stringResource(R.string.skin_racing_loading)
            WeatherWait.NO_GPS -> stringResource(R.string.info_no_gps)
            WeatherWait.UNAVAILABLE -> stringResource(R.string.info_weather_unavailable)
        }
        HudMessage(title, null, Cyan, env.editing, null)
        return
    }
    val units = LocalUnits.current
    val temp = "${units.temp(w.tempC)}°"
    val feels = stringResource(R.string.skin_racing_feels, units.temp(w.feelsC))
    val wind = stringResource(if (units.imperial) R.string.skin_racing_wind_mph else R.string.skin_racing_wind_kmh, units.speed(w.windKmh))
    // Either end can be missing from the forecast; rounding a NaN would throw.
    val range = if (!w.hiC.isNaN() && !w.loC.isNaN()) "${units.temp(w.loC)}° / ${units.temp(w.hiC)}°" else null
    HudPanel(TileFrame, Cyan, env.editing, contentAlignment = Alignment.Center) {
        val wide = maxWidth > maxHeight * 1.7f
        val condSp = (min(maxWidth.value, maxHeight.value * 2f) / 16f).coerceIn(13f, 26f)
        val lineSp = (condSp * 0.75f).coerceIn(11f, 18f)
        val shownRange = range?.takeIf { maxHeight >= 120.dp }
        val details: @Composable (Alignment.Horizontal) -> Unit = { align ->
            Column(horizontalAlignment = align) {
                HudText(w.condition.caps(), hud(fixedSp(condSp), Cyan, FontWeight.Black, 0.1f))
                HudText("$feels$DOT$wind", hud(fixedSp(lineSp), DashColors.TextSecondary))
                if (shownRange != null) HudText(shownRange, hud(fixedSp(lineSp), DashColors.TextSecondary))
            }
        }
        if (wide) {
            val tempSp = fitSp(template(temp), hero(100.sp, Color.White), maxWidth * 0.36f, maxHeight, 8f, 200f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherGlyph(w.code, (tempSp * 0.7f).dp)
                Spacer(Modifier.width(8.dp))
                NumText(temp, hero(tempSp.sp, DashColors.TextPrimary, glow = Cyan))
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f, fill = false)) { details(Alignment.Start) }
            }
        } else {
            val below = (condSp * 1.2f + lineSp * 1.2f * (if (shownRange != null) 2 else 1) + 8f).dp
            val tempSp = fitSp(template(temp), hero(100.sp, Color.White), maxWidth * 0.6f, maxHeight - below, 8f, 200f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeatherGlyph(w.code, (tempSp * 0.7f).dp)
                    Spacer(Modifier.width(8.dp))
                    NumText(temp, hero(tempSp.sp, DashColors.TextPrimary, glow = Cyan))
                }
                Spacer(Modifier.height(4.dp))
                details(Alignment.CenterHorizontally)
            }
        }
    }
}

/** The condition's icon in the banner yellow over a soft glow. */
@Composable
private fun WeatherGlyph(code: Int, size: Dp) {
    val yellow = RaYellowInk
    Box(
        Modifier.size(size).glowHalo(yellow.copy(alpha = 0.3f), 0.7f),
        contentAlignment = Alignment.Center
    ) {
        Icon(weatherIcon(code), contentDescription = null, tint = yellow, modifier = Modifier.size(size * 0.9f))
    }
}

/**
 * Fuel: FUEL and the percentage in caps, the range in huge black italic and
 * the tank as skewed cyan segments with a glow (amber at the reserve). A strip
 * of a tile sets it all on one line, as the mockup's "FUEL ▰▰▰ 310 KM". Falls
 * back to the standard tile until a fuel level is known (it explains how to
 * learn one).
 */
@Composable
private fun RaceRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    if (fuel == null) {
        StandardSkinnedTile(item, env)
        return
    }
    val low = fuel.percent <= SKIN_LOW_FUEL_PCT
    val ink = if (low) DashColors.Warning else Cyan
    val units = LocalUnits.current
    val range = units.distance(fuel.rangeKm).toString()
    val unit = units.distanceUnit.caps()
    val label = stringResource(R.string.skin_racing_fuel)
    HudPanel(TileFrame, ink, env.editing) {
        if (maxHeight < 76.dp) {
            val sp = (maxHeight.value * 0.36f).coerceIn(12f, 22f)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                HudText(label, hud(fixedSp(sp * 0.75f), ink, FontWeight.Black, 0.2f))
                Spacer(Modifier.width(10.dp))
                SegBar(fuel.percent / 100f, ink, Modifier.weight(1f).height((sp * 0.8f).dp))
                Spacer(Modifier.width(10.dp))
                NumText("$range $unit", hero(fixedSp(sp), DashColors.TextPrimary))
            }
        } else {
            val capsSp = (maxHeight.value * 0.1f).coerceIn(11f, 18f)
            val barH = (maxHeight * 0.16f).coerceIn(14.dp, 34.dp)
            val numH = maxHeight - (capsSp * 1.25f).dp - barH - 16.dp
            val numSp = fitSp(template(range), hero(100.sp, Color.White), maxWidth * 0.7f, numH, 8f, 170f)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SkewTick(ink, (capsSp * 0.9f).dp)
                    Spacer(Modifier.width(6.dp))
                    // The percentage is measured first; only the source (a word) gives way.
                    NumText("$label ${fuel.percent}%", hud(fixedSp(capsSp), ink, FontWeight.Black, 0.18f))
                    Spacer(Modifier.width(8.dp))
                    HudText(fuel.source.caps(), hud(fixedSp(capsSp), DashColors.Muted, FontWeight.Bold, 0.1f), Modifier.weight(1f), align = TextAlign.End)
                }
                Row {
                    NumText(range, hero(numSp.sp, DashColors.TextPrimary, glow = ink), Modifier.alignByBaseline())
                    Spacer(Modifier.width(6.dp))
                    HudText(unit, hud((numSp * 0.3f).coerceAtLeast(12f).sp, ink, FontWeight.Black, 0.1f), Modifier.alignByBaseline())
                }
                SegBar(fuel.percent / 100f, ink, Modifier.fillMaxWidth().height(barH), segment = (barH.value * 1.1f).coerceAtLeast(16f).dp)
            }
        }
    }
}

// ---------------------------------------------------------------- apps and launch bars

/** The app's own icon, or a generic grid while apps load. */
@Composable
private fun AppGlyph(app: AppEntry?, size: Dp) {
    if (app != null) AppIcon(icon = app.icon, size = size)
    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(size * 0.75f))
}

/** An app shortcut as a skewed neon button: icon over (or, on wide tiles, beside) its name in caps. */
@Composable
private fun RaceApp(item: DashboardItem.AppShortcut, env: SkinTileEnv) {
    val app = env.appsByPackage[item.packageName]
    val name = appLabel(app, item.packageName)
    val open = stringResource(R.string.skin_racing_open_app, name)
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().padding(6.dp)) {
        val wide = maxWidth > maxHeight * 1.6f
        val icon = (if (wide) maxHeight * 0.5f else minOf(maxHeight * 0.42f, maxWidth * 0.42f)).coerceIn(24.dp, 72.dp)
        val labelSp = (icon.value * 0.32f).coerceIn(12f, 18f)
        val style = hud(fixedSp(labelSp), DashColors.TextPrimary, FontWeight.Black, 0.08f)
        val showLabel = maxHeight >= 80.dp
        SkewButton(open, !env.editing, { env.onLaunchApp(item.packageName) }, Modifier.fillMaxSize(), dashed = env.editing) {
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppGlyph(app, icon)
                    Spacer(Modifier.width(10.dp))
                    HudText(name.caps(), style, Modifier.weight(1f, fill = false))
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppGlyph(app, icon)
                    if (showLabel) {
                        Spacer(Modifier.height(6.dp))
                        HudText(name.caps(), style, align = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/**
 * A launch bar as the game's menu: square-ish skewed neon keys sized from the
 * bar (the app's icon about 45% of the key, its name in caps under it where
 * there is room), centred, in as many rows as gives the biggest keys; last,
 * the pencil that edits the bar, a key of the same size in the hot magenta
 * gradient (the "pause menu" of the row). A bar too short for a label under
 * the icon keeps one row of wider keys with the name beside the icon.
 */
@Composable
private fun RaceLaunchBar(item: DashboardItem.LaunchBar, env: SkinTileEnv) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer().padding(6.dp), contentAlignment = Alignment.Center) {
        val gap = 10.dp
        val w = maxWidth
        val h = maxHeight
        val short = h < 84.dp
        // Rows and key height giving the biggest keys for [n] keys (the pencil included).
        fun layout(n: Int): Pair<Int, Dp> {
            if (short) return 1 to (w - gap * (n - 1)) / n
            var best = 1 to 0.dp
            for (r in 1..n) {
                val cols = (n + r - 1) / r
                val k = minOf((w - gap * (cols - 1)) / cols / 1.12f, (h - gap * (r - 1)) / r, 150.dp)
                if (k > best.second) best = r to k
            }
            return best
        }
        // Never squeeze a key below about 52 dp: apps that don't fit are left out.
        var count = item.packages.size
        while (count > 0 && layout(count + 1).second < 52.dp) count--
        val pkgs = item.packages.take(count)
        val (rows, size) = layout(pkgs.size + 1)
        val keyHeight = if (short) h else size.coerceAtLeast(48.dp)
        val keyW = if (short) minOf(size, maxOf(h * 2.6f, 56.dp), 220.dp).coerceAtLeast(48.dp) else keyHeight * 1.12f
        val icon = (keyHeight * (if (short) 0.5f else 0.45f)).coerceIn(18.dp, 64.dp)
        val labelSp = (if (short) keyHeight.value * 0.22f else keyHeight.value * 0.11f).coerceIn(11f, 15f)
        // A label under the icon needs about two lines of height left and some width.
        val labelUnder = !short && keyHeight - icon >= 40.dp && keyW >= 64.dp
        val labelBeside = short && keyW >= 120.dp
        val style = hud(fixedSp(labelSp), DashColors.TextPrimary, FontWeight.Black, 0.06f)
        val key = Modifier.size(keyW, keyHeight)
        val cols = (pkgs.size + 1 + rows - 1) / rows
        // null stands for the pencil, always last.
        val entries: List<String?> = pkgs + listOf(null)
        val pencil: @Composable () -> Unit = {
            SkewButton(stringResource(R.string.skin_racing_edit_launch_bar), true, env.onEditLaunchBar, key, hot = true) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(icon * 0.8f))
            }
        }
        if (pkgs.isEmpty()) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                HudText(
                    stringResource(R.string.skin_racing_launch_bar_empty),
                    hud(fixedSp(14f), DashColors.Muted, FontWeight.Bold, 0.1f),
                    Modifier.weight(1f).padding(start = 8.dp),
                    maxLines = 2
                )
                pencil()
            }
        } else {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                entries.chunked(cols).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                        line.forEach { pkg ->
                            if (pkg == null) {
                                pencil()
                            } else {
                                val app = env.appsByPackage[pkg]
                                val name = appLabel(app, pkg)
                                SkewButton(
                                    stringResource(R.string.skin_racing_open_app, name), !env.editing, { env.onLaunchApp(pkg) },
                                    key, dashed = env.editing
                                ) {
                                    if (labelBeside) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            AppGlyph(app, icon)
                                            Spacer(Modifier.width(8.dp))
                                            HudText(name.caps(), style, Modifier.weight(1f, fill = false))
                                        }
                                    } else {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            AppGlyph(app, icon)
                                            if (labelUnder) {
                                                Spacer(Modifier.height(5.dp))
                                                HudText(name.caps(), style, align = TextAlign.Center)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
