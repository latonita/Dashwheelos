@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.composed
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Date
import androidx.compose.foundation.BorderStroke

/*
 * Shared surfaces and controls: card, glass panel, page background, round buttons, helpers.
 */

/**
 * Feedback for a tap the driver does not watch: a haptic tick from the
 * screen, plus a short beep when the tap sound is on (Settings → Advanced;
 * for head units whose glass has no vibrator). Call it from the click handler.
 */
@Composable
internal fun rememberTapFeedback(): () -> Unit {
    val view = LocalView.current
    val sound = FeedbackStore.sound
    return remember(view, sound) {
        {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (sound) TapSound.play()
        }
    }
}

/** The tap sound setting; [sound] is observable so a change applies at once. */
object FeedbackStore {
    private const val PREFS = "feedback"
    private const val KEY_SOUND = "sound"
    var sound by mutableStateOf(false)
        private set

    fun load(context: Context) {
        sound = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SOUND, false)
    }

    fun save(context: Context, on: Boolean) {
        sound = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SOUND, on).apply()
    }
}

/** One short beep on the system stream; the generator is made once and kept. */
private object TapSound {
    private val generator: ToneGenerator? by lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 55) }.getOrNull()
    }

    fun play() {
        runCatching { generator?.startTone(ToneGenerator.TONE_PROP_BEEP, 40) }
    }
}

/** Shrinks a control to 96 % while pressed (80 ms), so a tap shows even when the finger hides the icon. */
@Composable
internal fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(80), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Secondary round control: frosted disc with a hairline rim. */
@Composable
internal fun GlassRoundButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    val fill: Brush = if (DashColors.Glass) {
        // By day the frost has to be dense to stand off the pale page.
        val (top, bottom) = if (DashColors.Light) 0.9f to 0.6f else 0.16f to 0.05f
        Brush.linearGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom)))
    } else SolidColor(DashColors.CardHi)
    val interaction = remember { MutableInteractionSource() }
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(size)
            .pressScale(interaction)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, DashColors.Line, CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = DashColors.TextPrimary, modifier = Modifier.size(iconSize))
    }
}

/** Primary round control: accent-gradient disc with a halo that scales with the theme's glow. */
@Composable
internal fun GradientRoundButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit
) {
    val accent = DashColors.Accent
    val glow = DashColors.Glow
    val interaction = remember { MutableInteractionSource() }
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(size)
            .pressScale(interaction)
            .drawBehind {
                if (glow > 0f) {
                    val r = this.size.minDimension * 0.85f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.45f * glow), Color.Transparent),
                            center = center, radius = r
                        ),
                        radius = r
                    )
                }
            }
            .clip(CircleShape)
            .background(DashColors.AccentBrush)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = DashColors.OnAccent, modifier = Modifier.size(iconSize))
    }
}

/**
 * Rounded elevated card. Solid themes use a flat surface matching the Android
 * Auto content cards; glass themes use a translucent gradient panel that lets
 * the aurora background show through; bare themes draw no panel at all.
 */
@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = DashShape.Large
    if (DashColors.Bare) {
        // Unclipped: with no panel edge to hide it, a clip would cut glows off in a hard line.
        Box(modifier = modifier) { content() }
    } else if (DashColors.Glass) {
        Box(modifier = modifier.then(glassPanel(shape))) { content() }
    } else {
        Surface(
            modifier = modifier,
            color = DashColors.Card,
            shape = shape,
            content = content
        )
    }
}

/**
 * Opaque card for overlays (the app drawer, sheets) that must hide whatever is
 * behind them on every theme. Glass themes make the normal card translucent,
 * which is right for tiles over the background but wrong for a panel over tiles.
 */
@Composable
internal fun SolidCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = DashColors.Card.copy(alpha = 1f),
        shape = DashShape.Large,
        border = BorderStroke(1.dp, DashColors.Line),
        content = content
    )
}

/**
 * Fill and hairline rim behind an item on a tile: an icon disc, a chip, a list
 * row. Bare themes drop both so the item sits straight on the page background.
 */
internal fun Modifier.itemFill(fill: Color, shape: Shape, rim: Color? = DashColors.Line): Modifier =
    if (DashColors.Bare) this
    else background(fill, shape).then(if (rim != null) Modifier.border(1.dp, rim, shape) else Modifier)

/**
 * Glass surface: translucent white->accent gradient fill, hairline border and a
 * specular highlight along the top edge. The head unit is Android 10, so there
 * is no RenderEffect backdrop blur; the layered translucency carries the look.
 * Light themes use frosted glass: the same layers, but a dense white fill.
 */
@Composable
internal fun glassPanel(shape: RoundedCornerShape): Modifier {
    val line = DashColors.Line
    val accent = DashColors.Accent
    val (top, bottom) = if (DashColors.Light) 0.78f to 0.52f else 0.10f to 0.035f
    return Modifier
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = top),
                    Color.White.copy(alpha = bottom),
                    accent.copy(alpha = 0.06f)
                )
            )
        )
        .border(1.dp, line, shape)
        // The highlight's gradient is built once per size, not on every draw.
        .drawWithCache {
            val inset = size.width * 0.18f
            val highlight = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                startX = inset, endX = size.width - inset
            )
            onDrawWithContent {
                drawContent()
                drawLine(
                    brush = highlight,
                    start = Offset(inset, 1f),
                    end = Offset(size.width - inset, 1f),
                    strokeWidth = 1.5f
                )
            }
        }
}

/**
 * Page background: the theme's gradient, plus (on glass themes) a cool wash
 * from the top and a violet wash from the bottom-right corner.
 */
@Composable
internal fun dashBackground(): Modifier {
    if (DashColors.Skin != DashSkin.STANDARD) return skinBackground()
    val stops = DashColors.BackgroundStops
    val glass = DashColors.Glass
    val glow = DashColors.Glow
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    // Gradients are built once per size and palette, not on every draw of the
    // page; the modifier itself is kept too, or each recomposition of the
    // caller would hand the node a new one and throw the cache away.
    return remember(stops, glass, glow, accent, accent2) { Modifier.drawWithCache {
        // A flat theme lists one colour twice: a plain fill, not a gradient
        // shader over the whole screen for the same pixels.
        val flat = stops.all { it == stops.first() }
        val page = if (flat) SolidColor(stops.first()) else Brush.linearGradient(
            colors = stops,
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
        val topC = Offset(size.width * 0.5f, -size.height * 0.15f)
        val topR = size.width * 0.45f
        val cornerC = Offset(size.width * 1.05f, size.height * 1.05f)
        val cornerR = size.width * 0.40f
        val topWash = if (glass) Brush.radialGradient(listOf(accent.copy(alpha = 0.22f * glow), Color.Transparent), center = topC, radius = topR) else null
        val cornerWash = if (glass) Brush.radialGradient(listOf(accent2.copy(alpha = 0.24f * glow), Color.Transparent), center = cornerC, radius = cornerR) else null
        onDrawBehind {
            drawRect(brush = page)
            if (topWash != null) drawCircle(brush = topWash, radius = topR, center = topC)
            if (cornerWash != null) drawCircle(brush = cornerWash, radius = cornerR, center = cornerC)
        }
    } }
}

/**
 * Average colour of a bitmap (used for the album-art colour bleed). Averages
 * an 8x8 thumbnail: scaling straight to 1x1 samples only a few pixels in the
 * middle of the cover.
 */
internal fun Bitmap.averageColor(): Color = runCatching {
    val thumb = Bitmap.createScaledBitmap(this, 8, 8, true)
    val px = IntArray(64)
    thumb.getPixels(px, 0, 8, 0, 0, 8, 8)
    if (thumb !== this) thumb.recycle()
    var a = 0; var r = 0; var g = 0; var b = 0
    for (p in px) {
        a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
    }
    Color(r / 64, g / 64, b / 64, a / 64)
}.getOrDefault(Color.Gray)

/**
 * Starts [intent] as a new task, swallowing the ActivityNotFound / permission
 * failures a stripped head-unit ROM can throw. True if it started.
 */
internal fun Context.launchSafely(intent: Intent): Boolean =
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

private fun debugEmuMapOverride(): Boolean = runCatching {
    val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
    get.invoke(null, "debug.dash.emumap", "") == "1"
}.getOrDefault(false)

/**
 * True on an Android emulator. The GL-heavy MapLibre map tile crashed natively
 * on the emulator's software renderer, so it shows a placeholder there;
 * everything else stays testable on an AVD.
 *
 * Debug builds can opt out with `adb shell setprop debug.dash.emumap 1`: this
 * is for a host-GPU AVD (`-gpu host`), where the real map renders. If the
 * property can't be read, the guard behaves as usual.
 */
internal val isEmulator: Boolean by lazy {
    if (BuildConfig.DEBUG && debugEmuMapOverride()) return@lazy false
    val fp = android.os.Build.FINGERPRINT.lowercase()
    val model = android.os.Build.MODEL.lowercase()
    val product = android.os.Build.PRODUCT.lowercase()
    val hw = android.os.Build.HARDWARE.lowercase()
    fp.startsWith("generic") || fp.contains("emulator") || fp.contains("emu64") ||
        model.contains("emulator") || model.contains("android sdk built for") ||
        product.contains("sdk") || product.startsWith("emu") ||
        hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("cutf")
}

/**
 * Grab bar between the Maps dock and the dashboard pages. Drags report pixel
 * deltas along the split; the caller converts them into the dock's share.
 */
@Composable
internal fun DockDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit, vertical: Boolean = false) {
    Box(
        modifier = Modifier
            // Upright, the dock is above or below the pages: the divider lies across and drags up/down.
            .then(if (vertical) Modifier.fillMaxWidth().height(24.dp) else Modifier.fillMaxHeight().width(24.dp))
            .pointerInput(vertical) {
                val done = { onDragEnd() }
                if (vertical) {
                    detectVerticalDragGestures(onDragEnd = done, onDragCancel = done) { change, dy ->
                        change.consume()
                        onDrag(dy)
                    }
                } else {
                    detectHorizontalDragGestures(onDragEnd = done, onDragCancel = done) { change, dx ->
                        change.consume()
                        onDrag(dx)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(if (vertical) 56.dp else 5.dp)
                .height(if (vertical) 5.dp else 56.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(DashColors.TextSecondary.copy(alpha = 0.55f))
        )
    }
}

internal fun currentClock(units: UnitSystem = Units.current.value): String = units.time(Date())

/**
 * [content] that goes away with a swipe to either side, like a notification on
 * Android: let go past about a third of its width and it slides out, then
 * [onDismiss] runs. A shorter swipe springs back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeAway(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.35f })
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) dismiss()
    }
    SwipeToDismissBox(state = state, backgroundContent = {}, modifier = modifier) { content() }
}

/**
 * Keeps the system bars away while a pop-up window (a dialog, a menu) is up.
 * The launcher runs immersive, but a dialog or a popup is a window of its
 * own and Android shows the bars for it, which shifted the whole screen the
 * moment a menu opened. Setting the flags on the pop-up's own root view is
 * enough: the window takes the union of its views' flags. With the head
 * unit's status bar forced on (a docked app window), only the navigation bar
 * is hidden, as for the launcher's own window.
 */
@Suppress("DEPRECATION")
/**
 * A dialog is its own window: while one is up, keys go to it and never reach
 * [MainActivity.dispatchKeyEvent], so a learned steering wheel button would
 * fall through to Android's default (a media key plays or pauses the music).
 * This puts [SteeringWheelStore] in front of the dialog's own key handling.
 */
private class WheelKeyWindowCallback(
    private val context: android.content.Context,
    private val inner: android.view.Window.Callback
) : android.view.Window.Callback by inner {
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        SteeringWheelStore.onKeyEvent(context, event) || inner.dispatchKeyEvent(event)

    companion object {
        fun install(window: android.view.Window) {
            val current = window.callback ?: return
            if (current is WheelKeyWindowCallback) return
            window.callback = WheelKeyWindowCallback(window.context.applicationContext, current)
        }
    }
}

internal fun Modifier.immersiveWindow(): Modifier = composed {
    val view = LocalView.current
    // A dialog tells Gemini Live's alert, which would otherwise cover it.
    DisposableEffect(view) {
        val inDialog = generateSequence(view as View?) { it.parent as? View }
            .any { it is androidx.compose.ui.window.DialogWindowProvider }
        if (inDialog) GeminiLive.dialogShown(view.context, true)
        onDispose { if (inDialog) GeminiLive.dialogShown(view.context, false) }
    }
    SideEffect {
        var flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (!MainActivity.statusBarForced) flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        // The launcher's own window loses its hide flags the moment the pop-up
        // takes the focus; put them back, or a bar-sized strip shows meanwhile.
        (view.context.activity() as? MainActivity)?.enableImmersiveFullscreen()
        // A dialog: its own window, reached through the view that hosts the content.
        val dialogWindow = generateSequence(view as View?) { it.parent as? View }
            .mapNotNull { (it as? androidx.compose.ui.window.DialogWindowProvider)?.window }
            .firstOrNull()
        if (dialogWindow != null) {
            WheelKeyWindowCallback.install(dialogWindow)
            if (dialogWindow.decorView.systemUiVisibility != flags) {
                // A full-width dialog's decor otherwise paints the theme's
                // navigation bar colour over the bar's strip, hidden or not.
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
                dialogWindow.navigationBarColor = android.graphics.Color.TRANSPARENT
                dialogWindow.statusBarColor = android.graphics.Color.TRANSPARENT
                dialogWindow.decorView.systemUiVisibility = flags
                dialogWindow.attributes = dialogWindow.attributes.also { it.systemUiVisibility = flags }
            }
            return@SideEffect
        }
        // A popup (a menu): the compose view is the window's root; its layout
        // params are the window's, and updating them re-applies the flags.
        val root = view.rootView
        val params = root.layoutParams as? android.view.WindowManager.LayoutParams
        if (params != null && params.systemUiVisibility != flags) {
            params.systemUiVisibility = flags
            root.systemUiVisibility = flags
            val wm = root.context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            runCatching { wm.updateViewLayout(root, params) }
        } else if (root.systemUiVisibility != flags) {
            root.systemUiVisibility = flags
        }
    }
    this
}

/** The activity behind a view's context, through any wrappers. */
internal fun Context.activity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.activity()
    else -> null
}
