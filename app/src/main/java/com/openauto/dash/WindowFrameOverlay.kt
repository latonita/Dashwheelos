package com.openauto.dash

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/*
 * The skin's frame over a docked app window (Google Maps). The docked window
 * is another app's, so nothing inside the dashboard can draw on it; a separate
 * overlay window above it can. The overlay covers the window's screen bounds
 * exactly, never takes touches (they reach Maps untouched) and draws
 * [SkinWindowFrame]: a mask that shapes the map and the skin's bezel.
 */

private const val TAG = "WindowFrame"

/**
 * Shows the active skin's frame over [bounds] (screen pixels) while it is
 * non-null; removes it when [bounds] turns null or this leaves composition.
 * Colour-only themes draw no frame. Needs "display over other apps"; when that
 * is missing it is granted through the same shell the dock already uses.
 */
@Composable
internal fun WindowFrameOverlay(bounds: ScreenRect?) {
    val context = LocalContext.current
    val skinned = DashColors.Skin != DashSkin.STANDARD
    val wanted = if (skinned) bounds else null
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val holder = remember { FrameWindow(context) }

    LaunchedEffect(wanted != null, allowed) {
        if (wanted != null && !allowed) allowed = PipAnchor.grantOverlayPermission(context)
    }
    // Update in place while the window moves (divider drags, re-docks).
    LaunchedEffect(wanted, allowed) {
        if (wanted != null && allowed) holder.show(wanted) else holder.remove()
    }
    DisposableEffect(Unit) {
        onDispose { holder.remove() }
    }
}

/** One overlay window hosting the frame composition, added and moved on demand. */
private class FrameWindow(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show(r: ScreenRect) {
        val lp = params ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Dashwheel window frame"
            // Android 12+ drops touches that pass through another app's window
            // unless it is at most 80 % opaque; below that it makes no difference.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alpha = 0.8f
        }
        lp.x = r.left
        lp.y = r.top
        lp.width = (r.right - r.left).coerceAtLeast(1)
        lp.height = (r.bottom - r.top).coerceAtLeast(1)
        params = lp

        val existing = view
        if (existing != null) {
            runCatching { wm.updateViewLayout(existing, lp) }.onFailure { Log.w(TAG, "move failed", it) }
            return
        }
        val activity = context.findComponentActivity() ?: return
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setContent { QuietComposeAccessibility(); SkinWindowFrame(Modifier.fillMaxSize()) }
        }
        runCatching { wm.addView(v, lp) }
            .onSuccess { view = v; Log.i(TAG, "frame over [${r.left},${r.top} ${r.right},${r.bottom}]") }
            .onFailure { Log.w(TAG, "could not add the frame window", it) }
    }

    fun remove() {
        val v = view ?: return
        view = null
        runCatching { wm.removeViewImmediate(v) }.onFailure { Log.w(TAG, "remove failed", it) }
    }
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}
