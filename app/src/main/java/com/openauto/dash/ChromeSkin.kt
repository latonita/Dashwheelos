package com.openauto.dash

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.Locale
import kotlin.math.min

/*
 * Chrome skin (2026-10-13 car-free trends): the Y2K revival. Liquid chrome
 * lettering, holographic chips, metal bezels and a drop of liquid metal that
 * ripples while parked and swells with the music (ChromeSkinDrop.kt). No car
 * picture: the look is carried by the material. Whole-design skin (see
 * Skins.kt): its page, its bar, the frame over a docked Maps window and the
 * main widgets (ChromeSkinTiles.kt).
 * Mockup: D:/dashwheel-design/v17-no-car/index.html, section 4.
 *
 * At night a near-black page with a faint grid and a soft light in the
 * middle; by day pale silver with dark chrome. Chrome type is a vertical
 * gradient with a bright band near its middle, nearly free to draw. With
 * effects off the type is plain ink, the chips opaque, nothing moves.
 */

internal val ChromePalette = DashPalette(
    Background = Color(0xFF050506), Bar = Color.Transparent, Card = Color(0xFF14141A), CardHi = Color(0x40585C6A),
    Accent = Color(0xFFC9CCD6), Secondary = Color(0xFF9AD8FF), Critical = Color(0xFFFF5A5F), Good = Color(0xFF52E3A4),
    Muted = Color(0xFF9AA0B0), TextPrimary = Color(0xFFEEEFF4), TextSecondary = Color(0xFFB8BDCC),
    Accent2 = Color(0xFFFF9AD5), Line = Color(0x2EFFFFFF), Glow = 0.5f, Bare = true,
    BackgroundStops = listOf(Color(0xFF1B1B24), Color(0xFF0B0B0F), Color(0xFF050506)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Black,
    Skin = DashSkin.CHROME
)

internal val ChromeLightPalette = DashPalette(
    Background = Color(0xFFE9EAEF), Bar = Color.Transparent, Card = Color(0xFFFFFFFF), CardHi = Color(0x24B8BCC8),
    Accent = Color(0xFF4A4E5C), Secondary = Color(0xFF2F7FB0), Critical = Color(0xFFC62828), Good = Color(0xFF0E8A55),
    Muted = Color(0xFF62667A), TextPrimary = Color(0xFF121218), TextSecondary = Color(0xFF3A3D4A),
    Accent2 = Color(0xFFB0407F), Line = Color(0x26000000), Glow = 0f, Bare = true, Light = true,
    BackgroundStops = listOf(Color(0xFFF6F7FA), Color(0xFFE9EAEF), Color(0xFFD8DAE2)),
    Font = DashFont.SANS,
    HeroWeight = FontWeight.Black,
    Skin = DashSkin.CHROME
)

/** The skin's corners (dialogs, menus, tiles being arranged). */
internal val ChromeShapes = skinShapes(30, 24, 30)

/**
 * The Home Chrome was drawn around (SkinLayouts.kt): the time in the top
 * left corner, the next turn under it with the arrival chip at its foot, the
 * music in the middle (the liquid drop over its chrome pill), the speed on
 * the right over the range chip.
 */
internal val ChromeLayout = SkinLayout(
    TemplatePage(
        listOf(BuiltinKind.NAVIGATION, BuiltinKind.SPEED_HUD, BuiltinKind.MEDIA, BuiltinKind.RANGE, BuiltinKind.CLOCK),
        dock = true
    ),
    wide = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, x = 0, y = 0, w = 4, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 0, y = 2, w = 4, h = 5),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 4, y = 0, w = 4, h = 7),
        DashboardItem.BuiltinWidget(BuiltinKind.SPEED_HUD, x = 8, y = 2, w = 4, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.RANGE, x = 8, y = 5, w = 4, h = 2)
    )
)

// --- Shared look ------------------------------------------------------------------

/** Corners of the holographic chips. */
internal val CHROME_CHIP_R = 24.dp

/** The height of the chips and the music pill along the foot of Home, so the row lines up. */
internal val CHROME_CHIP_H = 116.dp

/** The iridescent film's four tints, as in the design: pink, ice blue, lime and amber. */
internal val IrisPink = Color(0xFFFF9AD5)
internal val IrisBlue = Color(0xFF9AD8FF)
internal val IrisLime = Color(0xFFC3FF9A)
internal val IrisAmber = Color(0xFFFFE59A)

/** Chrome lettering at night: white at the top, a dark dip, a bright band just under the middle, a darker foot. */
private val ChromeInkNight = Brush.verticalGradient(
    0f to Color(0xFFFFFFFF), 0.24f to Color(0xFFD4D7E0), 0.47f to Color(0xFF80848F),
    0.53f to Color(0xFFF4F6FF), 0.7f to Color(0xFFA8ADBB), 1f to Color(0xFF5E626E)
)

/** Dark chrome for the pale page: deep gunmetal with a grey band near the middle. */
private val ChromeInkDay = Brush.verticalGradient(
    0f to Color(0xFF5A5E6A), 0.3f to Color(0xFF2A2C34), 0.47f to Color(0xFF15161B),
    0.53f to Color(0xFF7F8492), 0.7f to Color(0xFF3A3D47), 1f to Color(0xFF1E1F25)
)

/** The ink of chrome type now, or null with effects off, where plain ink reads best in the sun. */
internal fun chromeInk(): Brush? = when {
    DashColors.Effects == DashEffects.NONE -> null
    DashColors.Light -> ChromeInkDay
    else -> ChromeInkNight
}

/** The metal of a bezel, turned at 135 degrees as in the design (night and day). */
internal fun chromeMetalStops(light: Boolean): Array<Pair<Float, Color>> = if (light) {
    arrayOf(0f to Color(0xFFFFFFFF), 0.3f to Color(0xFF6B6F7C), 0.5f to Color(0xFFF6F7FB), 0.7f to Color(0xFF3F424C), 1f to Color(0xFFC9CCD6))
} else {
    arrayOf(0f to Color(0xFFFFFFFF), 0.3f to Color(0xFF7C8090), 0.5f to Color(0xFFF2F4FF), 0.7f to Color(0xFF555966), 1f to Color(0xFFE7E9F3))
}

/** The polished button's face: bright at the top and bottom, a grey waist. */
internal val ChromeButtonFace = Brush.verticalGradient(
    0f to Color(0xFFFFFFFF), 0.5f to Color(0xFFA9ADBB), 1f to Color(0xFFE9EBF5)
)

/** Ink on a polished chrome button, whatever the time of day. */
internal val ChromeOnMetal = Color(0xFF14141A)

/** How strong the glows are now: none by day or with effects off. */
internal val chromeGlow: Float get() = if (DashColors.Light) 0f else DashColors.Effects.scale

// --- Type -------------------------------------------------------------------------

/** The wide face of the design (Unbounded): Roboto stretched sideways. */
private val Wide = TextGeometricTransform(scaleX = 1.16f)
private val LabelWide = TextGeometricTransform(scaleX = 1.06f)

/** Big figures centred in their line box, so they sit where they are put and the chrome band falls across them. */
private val FigureLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** The skin's faces. */
internal enum class ChromeFace {
    /** Wide and black: the distance to the turn. */
    HEAVY,

    /** Wide and light: the speed. */
    THIN,

    /** Wide, medium: the time, chip values, the song. */
    WIDE,

    /** Spaced capitals, wide: the labels ("LEFT IN", "KM/H"). */
    LABEL,

    /** The clean sans of the design (Manrope): streets, artists, hints. */
    SANS
}

/** The skin's type at [size] dp (whatever the system font scale) in [face]; [weight] overrides the face's own. */
@Composable
internal fun chromeType(size: Float, face: ChromeFace, color: Color = DashColors.TextPrimary, weight: FontWeight? = null): TextStyle {
    val px = when (face) {
        ChromeFace.LABEL -> size.coerceAtLeast(MIN_CAPS_DP)
        else -> size
    }
    return TextStyle(
        color = color,
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight ?: when (face) {
            ChromeFace.HEAVY -> FontWeight.Black
            ChromeFace.THIN -> FontWeight.Light
            ChromeFace.WIDE, ChromeFace.LABEL, ChromeFace.SANS -> FontWeight.Medium
        },
        fontSize = fixedSp(px),
        fontFeatureSettings = if (face == ChromeFace.SANS || face == ChromeFace.LABEL) null else "tnum",
        textGeometricTransform = when (face) {
            ChromeFace.HEAVY, ChromeFace.THIN, ChromeFace.WIDE -> Wide
            ChromeFace.LABEL -> LabelWide
            ChromeFace.SANS -> null
        },
        letterSpacing = when (face) {
            ChromeFace.HEAVY -> (-0.03).em
            ChromeFace.THIN -> (-0.04).em
            ChromeFace.WIDE -> 0.01.em
            ChromeFace.LABEL -> 0.26.em
            ChromeFace.SANS -> 0.em
        },
        lineHeight = when (face) {
            ChromeFace.HEAVY, ChromeFace.THIN -> 1.0.em
            ChromeFace.WIDE -> 1.12.em
            ChromeFace.LABEL, ChromeFace.SANS -> 1.22.em
        },
        lineHeightStyle = if (face == ChromeFace.HEAVY || face == ChromeFace.THIN) FigureLine else null
    )
}

/** One run of the skin's text: one line with an ellipsis unless [maxLines] says otherwise; [caps] upper-cases it. */
@Composable
internal fun ChromeLine(
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
 * Text in liquid chrome: the vertical metal gradient across its own line,
 * with a faint cool glow at night (effects at full) or a crisp lift by day.
 * With effects off, plain [plain] ink. [plain] also stands in when the value
 * needs a colour of its own (over the limit, low fuel).
 */
@Composable
internal fun ChromeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    plain: Color? = null,
    align: TextAlign? = null,
    maxLines: Int = 1
) {
    // The metal runs over the whole paragraph: on two lines its band would fall between them, so wrapped text stays plain.
    val ink = if (plain == null && maxLines == 1) chromeInk() else null
    val glow: Shadow? = if (DashColors.Light) {
        softTextShadow(Color(0x33000000), 4f, Offset(0f, 2f))
    } else {
        softTextShadow(Color(0x4DAFC0FF), 26f)
    }
    val styled = when {
        ink != null -> style.copy(brush = ink, shadow = glow)
        else -> style.copy(color = plain ?: style.color, shadow = if (plain != null) null else glow)
    }
    ChromeLine(text, styled, modifier, maxLines = maxLines, align = align, overflow = TextOverflow.Clip)
}

/** The tracked capitals of the labels, in the second ink. */
@Composable
internal fun ChromeLabel(text: String, modifier: Modifier = Modifier, size: Float = 15f, color: Color = DashColors.TextSecondary, align: TextAlign? = null) {
    ChromeLine(text, chromeType(size, ChromeFace.LABEL, color), modifier, align = align, caps = true)
}

// --- Surfaces ---------------------------------------------------------------------

/**
 * A holographic chip behind the content: an iridescent film (pink, ice blue,
 * lime, amber) over smoked glass with a thin light border and a catch of
 * light on its top edge; by day a pearl film with a soft shadow. Effects
 * off: the opaque card and its rim. While arranging, the rim is dashed.
 */
@Composable
internal fun Modifier.chromeHolo(radius: Dp = CHROME_CHIP_R, editing: Boolean = false): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val card = DashColors.Card
    val line = DashColors.Line
    val shape = remember(radius) { RoundedCornerShape(radius) }
    return this
        .cachedDraw(light, effects, card, line, editing, radius) {
            val w = size.width
            val h = size.height
            val r = radius.toPx().coerceAtMost(min(w, h) / 2f)
            val plain = effects == DashEffects.NONE
            val base: Brush = when {
                plain -> SolidColor(card)
                light -> SolidColor(Color.White.copy(alpha = 0.62f))
                else -> SolidColor(Color(0xC40B0B10))
            }
            // The design's film, a little stronger by day so it shows on the pearl.
            val k = if (light) 1.6f else 1.15f
            val film = if (!plain) Brush.linearGradient(
                listOf(IrisPink.copy(alpha = 0.16f * k), IrisBlue.copy(alpha = 0.16f * k), IrisLime.copy(alpha = 0.14f * k), IrisAmber.copy(alpha = 0.16f * k)),
                Offset.Zero, Offset(w, h * 0.9f)
            ) else null
            val rimInk = when {
                plain -> line.copy(alpha = (line.alpha * 1.8f).coerceAtMost(1f))
                light -> Color(0x1F000000)
                else -> Color(0x2EFFFFFF)
            }.let { if (editing) it.copy(alpha = (it.alpha * 2.2f).coerceAtMost(0.85f)) else it }
            val rimW = (if (editing) 1.5f else 1f) * density
            val rimStroke = Stroke(rimW, pathEffect = if (editing) PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density)) else null)
            val half = rimW / 2f
            val catch = if (!plain) Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = if (light) 0.95f else 0.32f), Color.Transparent), r * 0.5f, w - r * 0.5f
            ) else null
            val shadow = if (light && !plain) floatArrayOf(2f, 3f, 0.04f, 5f, 7f, 0.03f, 9f, 11f, 0.02f) else null
            onDrawBehind {
                if (shadow != null) {
                    var i = 0
                    while (i < shadow.size) {
                        val g = shadow[i] * density
                        drawRoundRect(Color(0xFF14161C).copy(alpha = shadow[i + 2]), Offset(-g, -g + shadow[i + 1] * density), Size(w + 2f * g, h + 2f * g), CornerRadius(r + g))
                        i += 3
                    }
                }
                drawRoundRect(base, cornerRadius = CornerRadius(r))
                if (film != null) drawRoundRect(film, cornerRadius = CornerRadius(r))
                drawRoundRect(rimInk, Offset(half, half), Size(w - rimW, h - rimW), CornerRadius(r - half), style = rimStroke)
                if (catch != null) drawLine(catch, Offset(r * 0.5f, half + density), Offset(w - r * 0.5f, half + density), density)
            }
        }
        .clip(shape)
}

/**
 * A chrome bezel: a ring of turned metal [ring] wide around a smoked (by day
 * pearl) inside, round as a pill unless [radius] says otherwise, with a soft
 * light around it at night. Effects off: a plain grey ring on the card.
 */
@Composable
internal fun Modifier.chromeBezel(ring: Dp = 3.dp, radius: Dp? = null, editing: Boolean = false): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val card = DashColors.Card
    val glowK = chromeGlow
    val shape = remember(radius) { if (radius == null) RoundedCornerShape(50) else RoundedCornerShape(radius) }
    return this
        .cachedDraw(light, effects, card, glowK, ring, radius, editing) {
            val w = size.width
            val h = size.height
            val r = (radius?.toPx() ?: (h / 2f)).coerceAtMost(min(w, h) / 2f)
            val t = ring.toPx()
            val plain = effects == DashEffects.NONE
            val metal: Brush = if (plain) SolidColor(if (light) Color(0xFF8A8F9C) else Color(0xFF9A9FAC))
            else Brush.linearGradient(*chromeMetalStops(light), start = Offset.Zero, end = Offset(w * 0.55f, h * 1.6f))
            val inside: Brush = when {
                plain -> SolidColor(card)
                light -> Brush.verticalGradient(listOf(Color(0xFFFDFDFE), Color(0xFFE6E8EE)))
                else -> Brush.verticalGradient(listOf(Color(0xFF16161D), Color(0xFF09090C)))
            }
            val dashed = if (editing) Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density))) else null
            onDrawBehind {
                if (glowK > 0f) {
                    for (k in 1..3) {
                        val g = k * 3f * density
                        drawRoundRect(Color(0xFFB4C4FF).copy(alpha = 0.035f * glowK), Offset(-g, -g), Size(w + 2f * g, h + 2f * g), CornerRadius(r + g))
                    }
                } else if (light && !plain) {
                    for (k in 1..3) {
                        val g = k * 2.5f * density
                        drawRoundRect(Color(0xFF14161C).copy(alpha = 0.035f), Offset(-g, -g + k * 2f * density), Size(w + 2f * g, h + 2f * g), CornerRadius(r + g))
                    }
                }
                drawRoundRect(metal, cornerRadius = CornerRadius(r))
                drawRoundRect(inside, Offset(t, t), Size(w - 2f * t, h - 2f * t), CornerRadius((r - t).coerceAtLeast(0f)))
                if (dashed != null) drawRoundRect(DashColors.TextSecondary, Offset(t, t), Size(w - 2f * t, h - 2f * t), CornerRadius((r - t).coerceAtLeast(0f)), style = dashed)
            }
        }
        .clip(shape)
}

/** Whole surface tappable with the tap feedback, or nothing when [enabled] is false (arranging, nothing to do). */
@Composable
internal fun Modifier.chromeTap(enabled: Boolean, label: String, onClick: () -> Unit): Modifier {
    if (!enabled) return this
    val tap = rememberTapFeedback()
    return clickable(onClickLabel = label, role = Role.Button) { tap(); onClick() }
}

/**
 * A round polished chrome button [size] across with [icon] in dark ink, a
 * soft light around it at night; [alert] turns it red (Talk while listening).
 */
@Composable
internal fun ChromeMetalButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    enabled: Boolean = true,
    alert: Boolean = false,
    onClick: () -> Unit
) {
    val tap = rememberTapFeedback()
    val glowK = chromeGlow
    val light = DashColors.Light
    val plain = DashColors.Effects == DashEffects.NONE
    val critical = DashColors.Critical
    Box(
        modifier = Modifier
            .size(size)
            .cachedDraw(glowK, light, plain, alert, critical) {
                val r = this.size.minDimension / 2f
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                val face: Brush = when {
                    alert -> Brush.verticalGradient(listOf(critical.copy(alpha = 1f), Color(0xFF9E1B22)))
                    plain -> SolidColor(Color(0xFFD0D3DC))
                    else -> ChromeButtonFace
                }
                val halo = if (glowK > 0f || alert) Brush.radialGradient(
                    0f to Color.Transparent, 0.66f to Color.Transparent,
                    0.74f to (if (alert) critical else Color(0xFFC8D4FF)).copy(alpha = 0.3f * (if (alert) 1f else glowK)),
                    1f to Color.Transparent, center = c, radius = r * 1.4f
                ) else null
                val rim = if (light) Color(0x66000000) else Color(0x80FFFFFF)
                onDrawBehind {
                    if (halo != null) drawCircle(halo, r * 1.4f, c)
                    if (light && !plain) drawCircle(Color(0x1F14161C), r, Offset(c.x, c.y + 2f * density))
                    drawCircle(face, r, c)
                    drawCircle(rim, r - density / 2f, c, style = Stroke(density))
                    if (!plain && !alert) drawArc(
                        Color.White.copy(alpha = 0.9f), 200f, 140f, false,
                        Offset(c.x - r + 3f * density, c.y - r + 3f * density), Size(2f * r - 6f * density, 2f * r - 6f * density),
                        style = Stroke(1.2f * density)
                    )
                }
            }
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (alert) Color.White else ChromeOnMetal, modifier = Modifier.size(size * 0.42f))
    }
}

// --- Background -------------------------------------------------------------------

/** The page's base, light in the middle, and the grid's spacing, as in the design. */
private val NightBase = Color(0xFF050506)
private val NightMiddle = Color(0xFF1B1B24)
private val DayBase = Color(0xFFDCDEE5)
private val DayMiddle = Color(0xFFFAFBFD)
private const val GRID_DP = 64f

/**
 * The page's soft light: a radial of the design's 800 x 500 ellipse on a
 * 1280 x 720 page, centred on ([cx], [cy]). Drawn under a vertical squash of
 * [glowSquash] about that centre, so the circle becomes the ellipse.
 */
private fun pageGlow(cx: Float, cy: Float, pageW: Float, light: Boolean): Brush =
    Brush.radialGradient(0f to (if (light) DayMiddle else NightMiddle), 0.7f to (if (light) DayBase else NightBase), center = Offset(cx, cy), radius = pageW * 0.625f)

/** The squash that turns [pageGlow]'s circle into the design's ellipse on a [pageW] x [pageH] page. */
private fun glowSquash(pageW: Float, pageH: Float): Float = (pageH * 0.694f) / (pageW * 0.625f).coerceAtLeast(1f)

/** Fills x 0..[w], y 0..[h] with [glow], squashed by [squash] about ([cx], [cy]). */
private fun DrawScope.drawPageGlow(glow: Brush, cx: Float, cy: Float, squash: Float, w: Float, h: Float) {
    scale(1f, squash, Offset(cx, cy)) {
        val top = cy - cy / squash
        val bottom = cy + (h - cy) / squash
        drawRect(glow, Offset(0f, top), Size(w, bottom - top))
    }
}

/**
 * The page: near black with a faint 64 dp grid and a soft light in the
 * middle, where the liquid drop floats; by day pale silver with a darker
 * grid. Nothing on the page moves. Effects off: the plain base.
 */
@Composable
internal fun chromeBackground(): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    return remember(light, effects) { chromeBackdrop(light, effects) }
}

private fun chromeBackdrop(light: Boolean, effects: DashEffects): Modifier = Modifier.drawWithCache {
    val w = size.width
    val h = size.height
    val base = if (light) DayBase else NightBase
    val plain = effects == DashEffects.NONE
    val glow = pageGlow(w / 2f, h / 2f, w, light)
    val squash = glowSquash(w, h)
    val gridInk = if (light) Color(0x0A000000) else Color(0x08FFFFFF)
    val step = GRID_DP * density
    onDrawBehind {
        drawRect(base)
        if (plain) return@onDrawBehind
        drawPageGlow(glow, w / 2f, h / 2f, squash, w, h)
        var x = step
        while (x < w) {
            drawLine(gridInk, Offset(x, 0f), Offset(x, h), 1f)
            x += step
        }
        var y = step
        while (y < h) {
            drawLine(gridInk, Offset(0f, y), Offset(w, y), 1f)
            y += step
        }
    }
}

// --- Bar --------------------------------------------------------------------------

/** The bar's height: its chrome pills and the Talk button. */
private val CHROME_BAR = 72.dp

/** A key inside a bar pill: round, [DashSize.TouchPrimary] across. */
private val BAR_KEY = 56.dp

/**
 * The bar: chrome-bezel pills holding the icon keys, as on a Y2K stereo.
 * All apps and the layout on the left; the time in chrome with the date in
 * spaced capitals (not while a clock tile or the head unit's own bar shows
 * the time) and the dashboard's position in the middle; the setup pill,
 * vehicle alerts, the phone and the demo badge where they fit; then the OBD
 * link and the menu in their pill, and Talk as a polished chrome button.
 */
@Composable
internal fun ChromeTopBar(m: TopBarModel) {
    val now = rememberNow(60_000L)
    val dateFmt = rememberDateFormat("EEEd", best = true)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHROME_BAR)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .graphicsLayer()
    ) {
        val narrow = maxWidth < NARROW_BAR
        val shared = barIsShared()
        val showDate = maxWidth >= 640.dp && !(narrow && (shared || m.setupPending))
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = if (narrow) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                ChromeBarPill {
                    ChromeBarKey(Icons.Filled.Apps, stringResource(R.string.dash_all_apps), m.onApps)
                    LayoutPicker(m) { open ->
                        ChromeBarKeyBox(stringResource(R.string.dash_screen_layout, m.layout.title), open) {
                            LayoutIcon(m.layout, null, DashColors.TextPrimary, Modifier.size(26.dp))
                        }
                    }
                }
            }
            Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!m.merged && !ClockInSight.on.value) {
                    ChromeText(m.clock, chromeType(28f, ChromeFace.WIDE), Modifier.alignByBaseline())
                    if (showDate) {
                        Spacer(Modifier.width(12.dp))
                        ChromeLine(dateFmt.format(now), chromeType(15f, ChromeFace.LABEL, DashColors.TextSecondary), Modifier.alignByBaseline(), caps = true)
                    }
                }
                PagePositionChip(Modifier.padding(start = 10.dp))
            }
            BarEnd(modifier = Modifier.weight(1f)) {
                if (m.demo) {
                    Box(Modifier.layoutId(BarRank.DEMO).padding(end = 8.dp)) { DemoBadge(onStop = m.onDemo, compact = true) }
                }
                if (m.setupPending) {
                    Box(Modifier.layoutId(BarRank.SETUP).padding(end = 8.dp)) { SetupPill(onClick = { m.onSetup(false) }, compact = narrow || shared) }
                }
                Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                    VehicleAlerts(m.obdConnection, m.obd)
                }
                Box(Modifier.layoutId(BarRank.PHONE).padding(end = 8.dp)) { PhonePill() }
                Box(Modifier.layoutId(BarRank.OBD).fillMaxHeight()) {
                    ChromeBarPill {
                        ObdPill(m.obdConnection, m.onConnectObd)
                        MorePicker(m, geminiInBar = true) { open ->
                            ChromeBarKey(Icons.Filled.MoreVert, stringResource(R.string.dash_more), open)
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            ChromeTalkButton()
        }
    }
}

/** A chrome-bezel pill of the bar, holding its keys in a row. */
@Composable
private fun ChromeBarPill(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .chromeBezel(2.dp)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun ChromeBarKey(icon: ImageVector, description: String, onClick: () -> Unit) {
    ChromeBarKeyBox(description, onClick) {
        Icon(icon, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(28.dp))
    }
}

/** A round key of a bar pill: [BAR_KEY] across, the icon alone. */
@Composable
private fun ChromeBarKeyBox(description: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(BAR_KEY)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { icon() }
}

/**
 * Talk: a polished chrome button with the microphone. It opens Gemini Live
 * (Dashwheel's own or Google's) when there is one, else the unit's voice
 * assistant. While a conversation is open it turns red with an end mark.
 */
@Composable
private fun ChromeTalkButton() {
    val context = LocalContext.current
    val gemini = remember { GeminiLive.available(context) }
    val active by GeminiLive.active.collectAsState()
    ChromeMetalButton(
        icon = if (active) Icons.Filled.Close else Icons.Filled.Mic,
        description = stringResource(if (active) R.string.ai_gemini_live_end else R.string.chrome_talk_cd),
        size = 56.dp,
        alert = active
    ) {
        if (gemini || active) GeminiLive.toggle(context) else startVoiceAssistant(context)
    }
}

// --- Window frame -----------------------------------------------------------------

/**
 * The frame over a docked app window: its corners rounded off in the page
 * behind them (the same soft light, read from where the window sits), and a
 * bezel of turned chrome around it with a dark line inside, so the map sits
 * in the metal like a screen in a Y2K stereo. The middle stays clear.
 */
@Composable
internal fun ChromeWindowFrame(modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val view = androidx.compose.ui.platform.LocalView.current
    val origin = remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> origin.value = c.positionInWindow() }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = CHROME_CHIP_R.toPx()
                val o = origin.value ?: Offset.Zero
                val plain = effects == DashEffects.NONE
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, w, h))
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(r)))
                }
                val base = if (light) DayBase else NightBase
                val vw = view.width.toFloat()
                val vh = view.height.toFloat()
                val cx = vw / 2f - o.x
                val cy = vh / 2f - o.y
                val glow = pageGlow(cx, cy, vw, light)
                val squash = glowSquash(vw, vh)
                val ring = 3.dp.toPx()
                val metal: Brush = if (plain) SolidColor(if (light) Color(0xFF8A8F9C) else Color(0xFF9A9FAC))
                else Brush.linearGradient(*chromeMetalStops(light), start = Offset.Zero, end = Offset(w * 0.5f, h * 1.4f))
                val inner = Stroke(1.dp.toPx())
                onDrawBehind {
                    drawPath(mask, base)
                    if (!plain) {
                        // The page's light over the corners, squashed as on the page itself.
                        clipPath(mask) { drawPageGlow(glow, cx, cy, squash, w, h) }
                    }
                    drawRoundRect(metal, Offset(ring / 2f, ring / 2f), Size(w - ring, h - ring), CornerRadius(r - ring / 2f), style = Stroke(ring))
                    drawRoundRect(Color.Black.copy(alpha = if (light) 0.35f else 0.6f), Offset(ring + inner.width / 2f, ring + inner.width / 2f), Size(w - 2f * ring - inner.width, h - 2f * ring - inner.width), CornerRadius(r - ring), style = inner)
                }
            }
    )
}

/** The holographic glass behind a widget Chrome does not redraw ([SkinFallbackPanel]). */
@Composable
internal fun Modifier.chromeFallbackPanel(): Modifier = chromeHolo()

// --- Theme stamp --------------------------------------------------------------------

/**
 * The skin as a stamp in the theme picker (ThemePane): the black page with
 * its grid, the liquid chrome drop in the middle, a chrome "300" and the
 * thin chrome "48" either side, and the music pill in its bezel between two
 * holographic chips along the foot. Everything follows the stamp's size.
 */
internal fun DrawScope.chromeStamp(p: DashPalette, number: StampNumber) {
    val w = size.width
    val h = size.height
    val light = p.Light
    drawRect(if (light) DayBase else NightBase)
    drawRect(
        Brush.radialGradient(listOf(if (light) DayMiddle else NightMiddle, Color.Transparent), Offset(w / 2f, h * 0.45f), w * 0.45f)
    )
    val gridInk = if (light) Color(0x12000000) else Color(0x0FFFFFFF)
    val step = h / 7f
    var gx = step
    while (gx < w) { drawLine(gridInk, Offset(gx, 0f), Offset(gx, h), 1f); gx += step }
    var gy = step
    while (gy < h) { drawLine(gridInk, Offset(0f, gy), Offset(w, gy), 1f); gy += step }
    // The drop.
    drawChromeDrop(Offset(w * 0.5f, h * 0.42f), h * 0.24f, phase = 0.13f, energy = 0.4f, light = light, glowK = if (light) 0f else 1f)
    // The figures in chrome, drawn with the platform's paint so the metal runs through the letters.
    chromeStampText("300", w * 0.06f, h * 0.5f, h * 0.2f, robotoWeight(900), Paint.Align.LEFT, light)
    chromeStampText("48", w * 0.95f, h * 0.53f, h * 0.3f, robotoWeight(300), Paint.Align.RIGHT, light)
    // The foot: two holographic chips and the pill in its bezel.
    val chipTop = h * 0.78f
    val chipH = h * 0.15f
    val corner = CornerRadius(chipH * 0.3f)
    val film = Brush.linearGradient(
        listOf(IrisPink.copy(alpha = 0.3f), IrisBlue.copy(alpha = 0.3f), IrisLime.copy(alpha = 0.26f), IrisAmber.copy(alpha = 0.3f))
    )
    val rim = if (light) Color(0x22000000) else Color(0x40FFFFFF)
    for (x in floatArrayOf(w * 0.04f, w * 0.77f)) {
        drawRoundRect(film, Offset(x, chipTop), Size(w * 0.19f, chipH), corner)
        drawRoundRect(rim, Offset(x, chipTop), Size(w * 0.19f, chipH), corner, style = Stroke(1f))
    }
    val pillL = w * 0.27f
    val pillW = w * 0.46f
    val metal = Brush.linearGradient(*chromeMetalStops(light), start = Offset(pillL, chipTop), end = Offset(pillL + pillW * 0.5f, chipTop + chipH * 2f))
    drawRoundRect(metal, Offset(pillL, chipTop), Size(pillW, chipH), CornerRadius(chipH / 2f))
    val t = (h * 0.008f).coerceAtLeast(1f)
    drawRoundRect(if (light) Color(0xFFF4F5F8) else Color(0xFF101015), Offset(pillL + t, chipTop + t), Size(pillW - 2f * t, chipH - 2f * t), CornerRadius(chipH / 2f - t))
    val discC = Offset(pillL + chipH * 0.62f, chipTop + chipH / 2f)
    drawCircle(Brush.sweepGradient(listOf(IrisPink, IrisBlue, IrisLime, IrisAmber, IrisPink), discC), chipH * 0.34f, discC)
    drawCircle(ChromeButtonFace, chipH * 0.32f, Offset(pillL + pillW - chipH * 0.6f, chipTop + chipH / 2f))
}

/** [text] in chrome at ([x], baseline-ish centre [cy]), [px] tall, wide like the skin's faces. */
private fun DrawScope.chromeStampText(text: String, x: Float, cy: Float, px: Float, face: Typeface, align: Paint.Align, light: Boolean) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = px
        textScaleX = 1.16f
        textAlign = align
    }
    val top = cy - px * 0.72f
    val bottom = cy + px * 0.05f
    val stops = if (light) {
        intArrayOf(0xFF5A5E6A.toInt(), 0xFF2A2C34.toInt(), 0xFF15161B.toInt(), 0xFF7F8492.toInt(), 0xFF3A3D47.toInt(), 0xFF1E1F25.toInt())
    } else {
        intArrayOf(Color.White.toArgb(), 0xFFD4D7E0.toInt(), 0xFF80848F.toInt(), 0xFFF4F6FF.toInt(), 0xFFA8ADBB.toInt(), 0xFF5E626E.toInt())
    }
    paint.shader = LinearGradient(0f, top, 0f, bottom, stops, floatArrayOf(0f, 0.24f, 0.47f, 0.53f, 0.7f, 1f), Shader.TileMode.CLAMP)
    drawContext.canvas.nativeCanvas.drawText(text, x, cy, paint)
}
