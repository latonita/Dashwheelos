package com.openauto.dash

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Stops Compose's per-layout accessibility work while an accessibility service is on.
 *
 * Compose's accessibility delegate walks the whole semantics tree (sorted by
 * geometry) after every change, ~2.5 times a second on a dashboard, whenever
 * the system has any accessibility service enabled, ours included. On the
 * unit's ARMv7 that was ~60% of the main thread. Our service reads other apps'
 * windows and asks Dashwheel's own windows only on demand (the focused field
 * for the phone's typing), which still works: the node provider builds the
 * tree lazily when asked. What stops is the eager checking, gated on the
 * delegate's `enabledServices` list; it is emptied here (Compose 1.7.6,
 * reflection, harmless if the fields are not there).
 *
 * Not done while touch exploration (TalkBack) is on: those users need the events.
 */
@Composable
fun QuietComposeAccessibility() {
    val view = LocalView.current
    DisposableEffect(view) {
        val am = view.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am == null) return@DisposableEffect onDispose {}
        val main = Handler(Looper.getMainLooper())
        // Compose's own listeners reset the list; ours runs after them, so apply again, later.
        val apply = Runnable { ComposeA11yQuiet.apply(view, am) }
        val state = AccessibilityManager.AccessibilityStateChangeListener { main.post(apply) }
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { main.post(apply) }
        am.addAccessibilityStateChangeListener(state)
        am.addTouchExplorationStateChangeListener(touch)
        apply.run()
        onDispose {
            main.removeCallbacks(apply)
            am.removeAccessibilityStateChangeListener(state)
            am.removeTouchExplorationStateChangeListener(touch)
        }
    }
}

internal object ComposeA11yQuiet {
    private const val TAG = "ComposeA11yQuiet"
    private const val VIEW_CLASS = "androidx.compose.ui.platform.AndroidComposeView"
    private var loggedFailure = false

    /** Whether the eager checking may be switched off: only without touch exploration. */
    fun shouldQuiet(touchExploration: Boolean): Boolean = !touchExploration

    fun apply(view: View, am: AccessibilityManager) {
        if (!shouldQuiet(am.isTouchExplorationEnabled)) return
        runCatching {
            val host = generateSequence(view.javaClass) { it.superclass }.firstOrNull { it.name == VIEW_CLASS } ?: return
            val delegate = host.getDeclaredField("composeAccessibilityDelegate").apply { isAccessible = true }.get(view) ?: return
            delegate.javaClass.getDeclaredField("enabledServices").apply { isAccessible = true }.set(delegate, emptyList<Any>())
        }.onFailure {
            if (!loggedFailure) { loggedFailure = true; Log.w(TAG, "could not quiet Compose's accessibility: $it") }
        }
    }
}
