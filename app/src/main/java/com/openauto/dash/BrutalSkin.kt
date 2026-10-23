package com.openauto.dash

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.Locale
import kotlin.math.min

/*
 * Brutal skin (neo-brutalism, one of the car-free looks of 2026-10-13): blocks
 * of loud flat colour (yellow, white, electric blue, pink) with thick black
 * outlines, hard offset shadows without blur and small corners, on a warm
 * off-white page; the heaviest face there is, huge, and labels in tracked
 * monospace capitals. Made for full sun: no glow, no glass, nothing soft. At
 * night the page goes black, the blocks near black with light outlines, and
 * the loud colours turn into the ink and the hard shadows. The tiles are in
 * BrutalSkinTiles.kt. Mockup: D:/dashwheel-design/v17-no-car (section 2).
 *
 * Pressing a block moves it onto its shadow, as a physical key would. The only
 * motion is the turn arrow's two-step nudge and the song's marquee, both only
 * while parked, on a page in sight, with effects on.
 */

internal val BrutalPalette = DashPalette(
    Background = Color(0xFF0B0B0B), Bar = Color.Transparent, Card = Color(0xFF171717), CardHi = Color(0x22FFE14D),
    Accent = Color(0xFFFFE14D), Secondary = Color(0xFFFF6B9A), Critical = Color(0xFFFF5A5F), Good = Color(0xFF52E3A4),
    Muted = Color(0xFFB5B2A8), TextPrimary = Color(0xFFF2EFE6), TextSecondary = Color(0xFFD6D2C6),
    Accent2 = Color(0xFF8C9EFF), Line = Color(0xFFF2EFE6), Glow = 0f, Bare = true,
    BackgroundStops = listOf(Color(0xFF0B0B0B), Color(0xFF0B0B0B)),
    Warning = Color(0xFFFFB547),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Black,
    Skin = DashSkin.BRUTAL
)

internal val BrutalLightPalette = DashPalette(
    Background = Color(0xFFF2EFE6), Bar = Color.Transparent, Card = Color(0xFFFFFFFF), CardHi = Color(0x140B0B0B),
    Accent = Color(0xFF3D5AFE), Secondary = Color(0xFFE0457A), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF5A574F), TextPrimary = Color(0xFF0B0B0B), TextSecondary = Color(0xFF2E2C27),
    Accent2 = Color(0xFFFFE14D), Line = Color(0xFF0B0B0B), Glow = 0f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFF2EFE6), Color(0xFFF2EFE6)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Black,
    Skin = DashSkin.BRUTAL
)

/** The skin's corners (dialogs, menus, tiles being arranged): small, as the blocks'. */
internal val BrutalShapes = skinShapes(8, 6, 4)

/**
 * The Home Brutal was drawn around: the next turn big at the top left, the
 * speed and the weather beside it, the music and the car's readings under
 * them. The bar is the row of button blocks, so no dock.
 *
 * ```
 *   0 1 2 3 4 5 6 7 8 9 10 11
 * 0 N N N N N N S S S W W  W
 * 1 N N N N N N S S S W W  W
 * 2 N N N N N N S S S W W  W
 * 3 N N N N N N S S S W W  W
 * 4 M M M M M M T T T T T  T
 * 5 M M M M M M T T T T T  T
 * 6 M M M M M M T T T T T  T
 * ```
 */
internal val BrutalLayout = SkinLayout(
    home = TemplatePage(
        listOf(BuiltinKind.NAVIGATION, BuiltinKind.SPEED_HUD, BuiltinKind.MEDIA, BuiltinKind.WEATHER, BuiltinKind.TELEMETRY)
    ),
    wide = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 0, y = 0, w = 6, h = 4),
        DashboardItem.BuiltinWidget(BuiltinKind.SPEED_HUD, x = 6, y = 0, w = 3, h = 4),
        DashboardItem.BuiltinWidget(BuiltinKind.WEATHER, x = 9, y = 0, w = 3, h = 4),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 0, y = 4, w = 6, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.TELEMETRY, x = 6, y = 4, w = 6, h = 3)
    )
)

// --- Colours ------------------------------------------------------------------------

internal val BrInk = Color(0xFF0B0B0B)
internal val BrPaper = Color(0xFFF2EFE6)
internal val BrYellow = Color(0xFFFFE14D)
internal val BrBlue = Color(0xFF3D5AFE)
internal val BrPink = Color(0xFFFF6B9A)

/** The blue as ink on the night's near-black blocks: the electric blue lifted until it reads. */
private val BrBlueNight = Color(0xFF8C9EFF)

/** A block's face at night. */
private val BrNightBlock = Color(0xFF171717)

/** A block's colour: the loud flat fills of the design, and [INK] for the black Talk and clock blocks. */
internal enum class BrTone { YELLOW, WHITE, BLUE, PINK, INK }

/**
 * What a block of [BrTone] is painted with, by day or at night: its [fill],
 * the [ink] of its big words, the [label] ink of its small capitals, its
 * [outline] and its hard [shadow].
 */
internal class BrColors(val fill: Color, val ink: Color, val label: Color, val outline: Color, val shadow: Color)

private val DayColors = mapOf(
    BrTone.YELLOW to BrColors(BrYellow, BrInk, BrInk, BrInk, BrInk),
    BrTone.WHITE to BrColors(Color.White, BrInk, BrInk, BrInk, BrInk),
    BrTone.BLUE to BrColors(BrBlue, Color.White, Color.White, BrInk, BrInk),
    BrTone.PINK to BrColors(BrPink, BrInk, BrInk, BrInk, BrInk),
    BrTone.INK to BrColors(BrInk, BrYellow, BrYellow, BrInk, BrInk)
)

// At night the loud colour is the ink and the shadow; the face stays near black, the outline light.
private val NightColors = mapOf(
    BrTone.YELLOW to BrColors(BrNightBlock, BrYellow, BrPaper, BrPaper, BrYellow),
    BrTone.WHITE to BrColors(BrNightBlock, BrPaper, BrPaper, BrPaper, BrPaper),
    BrTone.BLUE to BrColors(BrNightBlock, BrBlueNight, BrPaper, BrPaper, BrBlueNight),
    BrTone.PINK to BrColors(BrNightBlock, BrPink, BrPaper, BrPaper, BrPink),
    BrTone.INK to BrColors(BrYellow, BrInk, BrInk, BrPaper, BrPaper)
)

/** [tone]'s colours in the palette on screen now. */
internal fun brColors(tone: BrTone): BrColors = (if (DashColors.Light) DayColors else NightColors).getValue(tone)

/** The page's warm off-white by day, black at night. */
internal val brPage: Color get() = if (DashColors.Light) BrPaper else BrInk

// --- Geometry -----------------------------------------------------------------------

/** The blocks' thick outline. */
internal val BR_OUTLINE = 4.dp

/** How far a tile's hard shadow falls, right and down. */
internal val BR_SHADOW = 8.dp

/** The bar's blocks drop a shorter shadow. */
internal val BR_BAR_SHADOW = 6.dp

/** The blocks' small corners. */
internal val BR_CORNER = 6.dp

/** The bar: its blocks (72 dp and their shadow) between thin margins. */
private val BRUTAL_BAR = 96.dp
private val BAR_BLOCK = 72.dp

// --- Type ---------------------------------------------------------------------------

/** The heaviest face the unit has: Roboto at 900 (Black). Lazy: the JVM tests have no typefaces. */
private val BlackFace by lazy { FontFamily(robotoWeight(900)) }

/** Big words and figures sit in a tight line box, so huge type takes no more room than it shows. */
private val TightLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/**
 * The skin's heavy type at [size] dp (whatever the system font scale): the
 * Black face, tight. [figure] packs the figures closer still, as the design's
 * huge numbers.
 */
@Composable
internal fun brHeavy(size: Float, color: Color, figure: Boolean = false): TextStyle = TextStyle(
    color = color,
    fontFamily = BlackFace,
    fontSize = fixedSp(size),
    fontFeatureSettings = "tnum",
    lineHeight = if (figure) 0.92.em else 1.04.em,
    lineHeightStyle = TightLine,
    letterSpacing = if (figure) (-0.04).em else (-0.01).em
)

/** The labels: tracked monospace capitals, never under [MIN_CAPS_DP]. */
@Composable
internal fun brMono(size: Float, color: Color): TextStyle = TextStyle(
    color = color,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = fixedSp(size.coerceAtLeast(MIN_CAPS_DP)),
    letterSpacing = 0.08.em
)

/** One run of the skin's text: one line with an ellipsis unless told otherwise, upper-cased when [caps]. */
@Composable
internal fun BrLine(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    caps: Boolean = true,
    align: TextAlign? = null,
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

// --- The block ----------------------------------------------------------------------

/**
 * A block of [tone] filling the box: the hard shadow [shadow] down and to
 * the right (the block stands in the rest of the box), the flat fill, the
 * thick outline over the content, small corners. [stripes] lays the
 * weather's diagonal stripes on it (not with effects off: the sun wants it
 * plain). While [pressed] the block sits on its shadow. While the tile is
 * being arranged its outline is dashed, so it reads as movable.
 */
@Composable
internal fun Modifier.brBlock(
    tone: BrTone,
    shadow: Dp = BR_SHADOW,
    pressed: State<Boolean>? = null,
    editing: Boolean = false,
    stripes: Boolean = false,
    outline: Dp = BR_OUTLINE
): Modifier {
    val c = brColors(tone)
    val striped = stripes && DashColors.Effects != DashEffects.NONE
    val stripeInk = if (DashColors.Light) Color.White.copy(alpha = 0.14f) else BrBlueNight.copy(alpha = 0.16f)
    val shape = remember { RoundedCornerShape(BR_CORNER) }
    return this
        .cachedDraw(c.shadow, shadow, pressed) {
            val s = shadow.toPx()
            val r = CornerRadius(BR_CORNER.toPx())
            onDrawBehind {
                if (pressed?.value != true) drawRoundRect(c.shadow, Offset(s, s), Size(size.width - s, size.height - s), r)
            }
        }
        .padding(end = shadow, bottom = shadow)
        .then(
            if (pressed != null) Modifier.graphicsLayer {
                val d = if (pressed.value) shadow.toPx() else 0f
                translationX = d
                translationY = d
            } else Modifier
        )
        .cachedDraw(c.fill, c.outline, striped, stripeInk, editing, outline) {
            val w = size.width
            val h = size.height
            val r = BR_CORNER.toPx()
            val o = outline.toPx()
            val box = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r))) }
            val stroke = Stroke(o, pathEffect = if (editing) PathEffect.dashPathEffect(floatArrayOf(14f * density, 9f * density)) else null)
            // The design's stripes at 115 degrees: 8 wide every 22.
            val band = 8.dp.toPx()
            val step = 22.dp.toPx()
            onDrawWithContent {
                drawRoundRect(c.fill, cornerRadius = CornerRadius(r))
                if (striped) clipPath(box) {
                    rotate(25f, Offset(w / 2f, h / 2f)) {
                        val reach = w + h
                        var x = -reach
                        while (x < reach) {
                            drawRect(stripeInk, Offset(w / 2f + x, h / 2f - reach), Size(band, reach * 2f))
                            x += step
                        }
                    }
                }
                drawContent()
                drawRoundRect(c.outline, Offset(o / 2f, o / 2f), Size(w - o, h - o), CornerRadius(r - o / 2f), style = stroke)
            }
        }
        .clip(shape)
}

/**
 * A block of [tone] holding [content], filling its box: a key when
 * [onClick] is given (it sinks onto its shadow while pressed, with the tap
 * feedback), else a plain block.
 */
@Composable
internal fun BrBlock(
    tone: BrTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    editing: Boolean = false,
    stripes: Boolean = false,
    shadow: Dp = BR_SHADOW,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    if (onClick == null) {
        Box(modifier.brBlock(tone, shadow, null, editing, stripes), contentAlignment = contentAlignment, content = content)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val tap = rememberTapFeedback()
    Box(
        modifier
            .brBlock(tone, shadow, pressed, editing, stripes)
            .clickable(interaction, indication = null, onClickLabel = clickLabel, role = Role.Button) { tap(); onClick() },
        contentAlignment = contentAlignment,
        content = content
    )
}

// --- Background ---------------------------------------------------------------------

/** The page: flat, the warm off-white by day, black at night. */
@Composable
internal fun brutalBackground(): Modifier {
    val page = brPage
    return remember(page) { Modifier.background(page) }
}

/** The look of a widget Brutal does not redraw: a plain block, white by day ([SkinFallbackPanel]). */
@Composable
internal fun Modifier.brutalFallbackPanel(): Modifier = brBlock(BrTone.WHITE, shadow = 6.dp).padding(BR_OUTLINE)

// --- Bar ----------------------------------------------------------------------------

/**
 * The bar: a row of outlined button blocks with their hard shadows, the way
 * the design lines up MAP, MUSIC, PHONE and CAR. Apps and Layout first, then
 * the dashboards of the dashboard bar (the one on screen in yellow) where
 * there is room, the time with the dashboard's position, the alerts, the
 * phone and the setup pill when there are any, the OBD link, the menu and,
 * last and widest, Talk in black with yellow letters. A narrow bar keeps the
 * icons and drops the dashboards.
 */
@Composable
internal fun BrutalTopBar(m: TopBarModel) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(BRUTAL_BAR)
            .graphicsLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val labels = maxWidth >= LABELLED_BAR
        val showClock = !m.merged && !ClockInSight.on.value
        val shared = barIsShared()
        val status = m.demo || m.setupPending || shared
        val bar = LocalDashBar.current
        val context = LocalContext.current
        // The dashboards: only on a wide bar, and only as many as leave the fixed blocks their room.
        val tabRoom = ((maxWidth.value - 900f - if (status) 180f else 0f) / 140f).toInt().coerceIn(0, 4)
        val tabs = if (!narrow && bar != null && bar.tabs.size > 1) bar.tabs.take(tabRoom) else emptyList()
        Row(
            Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrBarBlock(
                BrTone.WHITE, m.onApps, stringResource(R.string.dash_all_apps),
                Modifier.weight(if (labels) 1f else 0.8f)
            ) { ink ->
                Icon(Icons.Filled.Apps, contentDescription = null, tint = ink, modifier = Modifier.size(28.dp))
                if (labels && !narrow) BarWord(stringResource(R.string.dash_apps), ink)
            }
            // The pickers wrap their anchor in a box of their own: the row's weight goes on a box around them.
            Box(Modifier.weight(if (labels) 1f else 0.8f)) {
                LayoutPicker(m) { open ->
                    BrBarBlock(BrTone.WHITE, open, stringResource(R.string.dash_screen_layout, m.layout.title), Modifier.fillMaxWidth()) { ink ->
                        LayoutIcon(m.layout, null, ink, Modifier.size(28.dp))
                        if (labels && !narrow) BarWord(stringResource(R.string.dash_layout_button), ink)
                    }
                }
            }
            tabs.forEach { tab ->
                val name = tab.label(context)
                val here = tab.page == bar?.currentPage
                BrBarBlock(if (here) BrTone.YELLOW else BrTone.WHITE, { bar?.onSelect?.invoke(tab) }, name, Modifier.weight(1f)) { ink ->
                    BarWord(name, ink, start = false)
                }
            }
            if (showClock) {
                BrBarBlock(BrTone.WHITE, null, m.clock, Modifier.weight(if (narrow) 1.1f else 1.5f)) { ink ->
                    BrLine(m.clock, brHeavy(30f, ink, figure = true), overflow = TextOverflow.Clip)
                    if (!narrow) PagePositionChip(Modifier.padding(start = 4.dp))
                }
            } else {
                PagePositionChip()
            }
            if (status) {
                BarEnd(Modifier.weight(if (narrow) 1f else 1.4f).fillMaxHeight()) {
                    if (m.demo) {
                        Box(Modifier.layoutId(BarRank.DEMO).padding(end = 8.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
                    }
                    if (m.setupPending) {
                        Box(Modifier.layoutId(BarRank.SETUP).padding(end = 8.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared) }
                    }
                    Row(Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) { VehicleAlerts(m.obdConnection, m.obd) }
                    Box(Modifier.layoutId(BarRank.PHONE).padding(end = 4.dp)) { PhonePill() }
                }
            }
            BrBarObd(m.obdConnection, m.onConnectObd, Modifier.weight(if (narrow) 0.9f else 0.8f))
            Box(Modifier.widthIn(min = 76.dp).weight(0.55f)) {
                MorePicker(m, geminiInBar = true) { open ->
                    BrBarBlock(BrTone.WHITE, open, stringResource(R.string.dash_more), Modifier.fillMaxWidth()) { ink ->
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = ink, modifier = Modifier.size(30.dp))
                    }
                }
            }
            BrTalkBlock(Modifier.weight(if (narrow) 1.2f else 1.3f), narrow)
        }
    }
}

/** A word on a bar block: heavy capitals, after the icon unless it stands alone. */
@Composable
private fun RowScope.BarWord(text: String, ink: Color, start: Boolean = true) {
    if (start) Spacer(Modifier.width(10.dp))
    BrLine(text, brHeavy(22f, ink), Modifier.weight(1f, fill = false))
}

/**
 * A button block of the bar, [BAR_BLOCK] tall: its content centred in a row
 * in the tone's ink. A key when [onClick] is given (sinking onto its shadow
 * while pressed), else a plain block.
 */
@Composable
private fun BrBarBlock(
    tone: BrTone,
    onClick: (() -> Unit)?,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(ink: Color) -> Unit
) {
    val ink = brColors(tone).ink
    BrBlock(
        tone,
        modifier.height(BAR_BLOCK + BR_BAR_SHADOW).semantics(mergeDescendants = true) { contentDescription = description },
        onClick = onClick, clickLabel = description, shadow = BR_BAR_SHADOW, contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) { content(ink) }
    }
}

/**
 * The OBD link as a block: a square dot in the link's colour (hollow when
 * off, blinking while connecting) and OBD, with "!" on error, so it reads
 * without its colour. What a tap does is the shared [ObdMark]'s.
 */
@Composable
private fun BrBarObd(state: ObdConnectionState, onConnect: () -> Unit, modifier: Modifier) {
    val color = obdStatusColor(state)
    val pulse = if (state == ObdConnectionState.CONNECTING) rememberLoop(900, reverse = true, status = true) else null
    val ink = brColors(BrTone.WHITE).ink
    ObdMark(state, onConnect, modifier = modifier, shape = RoundedCornerShape(BR_CORNER)) {
        Box(Modifier.fillMaxWidth().height(BAR_BLOCK + BR_BAR_SHADOW).brBlock(BrTone.WHITE, BR_BAR_SHADOW), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(16.dp)
                        .cachedDraw(state, color, ink, pulse) {
                            val ring = Stroke(3.dp.toPx())
                            val r = CornerRadius(3.dp.toPx())
                            onDrawBehind {
                                when (state) {
                                    ObdConnectionState.DISCONNECTED -> drawRoundRect(ink, Offset(1.5f * density, 1.5f * density), Size(size.width - 3f * density, size.height - 3f * density), r, style = ring)
                                    ObdConnectionState.CONNECTING -> drawRoundRect(color, cornerRadius = r, alpha = 0.3f + 0.7f * (pulse?.value ?: 1f))
                                    else -> {
                                        drawRoundRect(color, cornerRadius = r)
                                        drawRoundRect(ink, Offset(1f * density, 1f * density), Size(size.width - 2f * density, size.height - 2f * density), r, style = Stroke(2.dp.toPx()))
                                    }
                                }
                            }
                        }
                )
                Spacer(Modifier.width(8.dp))
                BrLine(stringResource(R.string.dash_obd_short), brHeavy(22f, ink), caps = false)
                if (state == ObdConnectionState.ERROR) BrLine("!", brHeavy(22f, DashColors.Critical), caps = false)
            }
        }
    }
}

/**
 * Talk: the black block with the yellow word (yellow with black at night),
 * the widest of the bar. It opens Gemini Live (Dashwheel's own or Google's)
 * when there is one, else the unit's voice assistant. While a conversation is
 * open it turns red with End.
 */
@Composable
private fun BrTalkBlock(modifier: Modifier, narrow: Boolean) {
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    val word = stringResource(if (active) R.string.ai_gemini_live_end else R.string.brutal_talk)
    val description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.brutal_talk_cd)
    val ink = if (active) Color.White else brColors(BrTone.INK).ink
    BrBlock(
        BrTone.INK,
        modifier
            .height(BAR_BLOCK + BR_BAR_SHADOW)
            .semantics(mergeDescendants = true) { contentDescription = description },
        onClick = { if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context) },
        clickLabel = description, shadow = BR_BAR_SHADOW, contentAlignment = Alignment.Center
    ) {
        if (active) Box(Modifier.fillMaxSize().background(DashColors.Critical))
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (active) Icons.Filled.Close else Icons.Filled.Mic, contentDescription = null, tint = ink, modifier = Modifier.size(28.dp))
            if (!narrow || active) {
                Spacer(Modifier.width(10.dp))
                BrLine(word, brHeavy(24f, ink))
            }
        }
    }
}

// --- Window frame -------------------------------------------------------------------

/**
 * The frame over a docked app window: the window becomes one more block, its
 * thick outline over the map's edge, its corners cut in the page's colour and
 * its hard shadow falling inside the window's right and bottom edges, so it
 * lines up with the tiles. It never takes touches; the middle stays clear.
 */
@Composable
internal fun BrutalWindowFrame(modifier: Modifier) {
    val page = brPage
    val c = brColors(BrTone.WHITE)
    Box(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val s = BR_SHADOW.toPx()
                val r = BR_CORNER.toPx()
                val o = BR_OUTLINE.toPx()
                val block = RoundRect(Rect(0f, 0f, w - s, h - s), CornerRadius(r))
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(block)
                }
                val stroke = Stroke(o)
                onDrawBehind {
                    drawPath(outside, page)
                    clipPath(outside) { drawRoundRect(c.shadow, Offset(s, s), Size(w - s, h - s), CornerRadius(r)) }
                    drawRoundRect(c.outline, Offset(o / 2f, o / 2f), Size(w - s - o, h - s - o), CornerRadius(r - o / 2f), style = stroke)
                }
            }
    )
}

// --- Theme stamp --------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): on the page, a big
 * yellow block with the speed, a white block and a blue striped one, each
 * with its thick outline and hard shadow, and the black Talk bar under them.
 * Everything follows the stamp's size, so it reads small and large.
 */
internal fun DrawScope.brutalStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    val u = min(w, h) / 100f
    val o = (2.2f * u).coerceAtLeast(1.5f)
    val sh = 3.5f * u
    val r = CornerRadius(2.5f * u)
    val line = if (light) BrInk else BrPaper
    drawRect(if (light) BrPaper else BrInk)
    fun block(x: Float, y: Float, bw: Float, bh: Float, fill: Color, shadow: Color) {
        drawRoundRect(shadow, Offset(x + sh, y + sh), Size(bw, bh), r)
        drawRoundRect(fill, Offset(x, y), Size(bw, bh), r)
        drawRoundRect(line, Offset(x + o / 2f, y + o / 2f), Size(bw - o, bh - o), CornerRadius((r.x - o / 2f).coerceAtLeast(0f)), style = Stroke(o))
    }
    val night = BrNightBlock
    // The yellow block: the next turn's place, the speed in it.
    val yx = w * 0.06f
    val yy = h * 0.08f
    val yw = w * 0.5f
    val yh = h * 0.52f
    block(yx, yy, yw, yh, if (light) BrYellow else night, if (light) BrInk else BrYellow)
    // The number follows the stamp: half the block tall, whatever size the stamp is drawn at.
    number("87", yx + yw * 0.5f, yy + yh * 0.52f, yh * 0.5f / (density * fontScale), if (light) BrInk else BrYellow)
    // The white block.
    val wx = w * 0.62f
    block(wx, yy, w * 0.31f, h * 0.22f, if (light) Color.White else night, if (light) BrInk else BrPaper)
    // The blue block with its stripes.
    val bx = wx
    val by = h * 0.38f
    val bw = w * 0.31f
    val bh = h * 0.22f
    drawRoundRect(if (light) BrInk else BrBlueNight, Offset(bx + sh, by + sh), Size(bw, bh), r)
    drawRoundRect(if (light) BrBlue else night, Offset(bx, by), Size(bw, bh), r)
    clipPath(Path().apply { addRoundRect(RoundRect(Rect(bx, by, bx + bw, by + bh), r)) }) {
        val stripe = if (light) Color.White.copy(alpha = 0.22f) else BrBlueNight.copy(alpha = 0.3f)
        var x = bx - bh
        while (x < bx + bw) {
            drawLine(stripe, Offset(x, by + bh), Offset(x + bh * 0.47f, by), 3f * u)
            x += 8f * u
        }
    }
    drawRoundRect(line, Offset(bx + o / 2f, by + o / 2f), Size(bw - o, bh - o), CornerRadius((r.x - o / 2f).coerceAtLeast(0f)), style = Stroke(o))
    // The bar: white keys and the black Talk.
    val ky = h * 0.74f
    val kh = h * 0.16f
    block(w * 0.06f, ky, w * 0.2f, kh, if (light) Color.White else night, if (light) BrInk else BrPaper)
    block(w * 0.32f, ky, w * 0.2f, kh, if (light) Color.White else night, if (light) BrInk else BrPaper)
    block(w * 0.58f, ky, w * 0.35f, kh, if (light) BrInk else BrYellow, if (light) BrInk else BrPaper)
    drawRoundRect(if (light) BrYellow else BrInk, Offset(w * 0.68f, ky + kh * 0.42f), Size(w * 0.15f, kh * 0.18f), CornerRadius(kh * 0.09f))
}
