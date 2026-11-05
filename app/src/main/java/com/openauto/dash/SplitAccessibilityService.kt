package com.openauto.dash

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * A minimal [AccessibilityService] whose only job is to trigger the system's
 * built-in split-screen **the same way the manual recents gesture does** —
 * `performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)`.
 *
 * This head unit's ROM ignores the AOSP windowing APIs (`am task resize`,
 * `ActivityOptions.setLaunchWindowingMode`) that [SplitLauncher] falls back to,
 * but it still honours SystemUI's own split path — the one the user reaches by
 * long-pressing an app in recents and dragging it to a side. That path is
 * exactly what this global action drives, so routing through an accessibility
 * service is the closest programmatic match to the working manual gesture.
 *
 * The service holds no long-lived state and observes no events; it exists only
 * so the launcher can ask SystemUI to dock the foreground task on demand. The
 * user must enable it once under Settings → Accessibility (see
 * [SplitLauncher.openAccessibilitySettings]).
 */
class SplitAccessibilityService : AccessibilityService() {

    // Text built here follows the language picked in the launcher.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onServiceConnected() {
        instance = this
        connectedState.value = true
        SecondScreenController.serviceFiltersKeys = serviceInfo?.let {
            it.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        } == true
        Log.d(TAG, "connected")
        updateOverlayForSplit()
        if (Edition.full) {
            wazeWatch?.cancel()
            wazeWatch = mainScope.launch { NavDirections.running.collect { wazeRuns = it == WazeScreen.PACKAGE; watchContent(wazeRuns || mapsInFront) } }
        }
        keyWatch?.cancel()
        keyWatch = mainScope.launch {
            combine(
                SteeringWheelStore.mapping, SteeringWheelStore.buttons, HeadUnitKeys.listeningFlow,
                SecondScreenStore.config, SecondScreenController.status, EmbeddedApp.keysAwayFlow
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val config = values[3] as SecondScreenConfig
                val status = values[4] as SecondScreenStatus
                keysWanted(
                    learning = values[0] as Boolean,
                    wheelBound = (values[1] as List<*>).any { (it as? WheelButton)?.assignment != null },
                    unitKeys = values[2] as Boolean,
                    clusterKeys = (config.pageKeys.isNotEmpty() || config.mediaKeysTurnPages) &&
                        (status.output == SecondScreenOutput.VIDEO_CLUSTER || status.output == SecondScreenOutput.DATA),
                    keysAway = values[5] as Boolean
                )
            }.distinctUntilChanged().collect { filterKeys(it) }
        }
    }

    private var keyWatch: kotlinx.coroutines.Job? = null

    /**
     * Whether this service should see the unit's keys before every app. The
     * filter hands every key it doesn't use back to Android, which on the
     * K706 then sees it twice, the second time as a long press: the unit's own
     * screen-off key switched the screen off and straight back on. So the keys
     * pass through here only while something needs them: a button being
     * learned, the second screen's page keys while its cluster shows, Home and
     * Back while an app inside a tile holds them, and the steering wheel's
     * buttons on a unit whose own key service ([HeadUnitKeys]) doesn't deliver them.
     */
    private fun filterKeys(on: Boolean) {
        val info = serviceInfo ?: return
        val flag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val flags = if (on) info.flags or flag else info.flags and flag.inv()
        if (flags == info.flags) return
        info.flags = flags
        serviceInfo = info
        SecondScreenController.serviceFiltersKeys = on
        Log.i(TAG, "the unit's keys ${if (on) "pass through this service" else "no longer pass through this service"}")
    }

    /** Collects which app guides, to hear Waze's screen only while it runs. */
    private var wazeWatch: kotlinx.coroutines.Job? = null
    private var wazeRuns = false
    /** Google Maps has the screen: its route preview names the destination ([MapsScreen]). */
    private var mapsInFront = false
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Window content changes, heard only while Waze runs ([WazeScreen]) or Maps
     * is in front ([MapsScreen]): every app's screen sends them, and the rest
     * of the time they'd be dropped anyway.
     */
    private fun watchContent(on: Boolean) {
        val info = serviceInfo ?: return
        val types = if (on) info.eventTypes or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        else info.eventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED.inv()
        if (types == info.eventTypes) return
        info.eventTypes = types
        serviceInfo = info
        Log.i(TAG, "the navigation app's screen ${if (on) "read" else "no longer read"}")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        connectedState.value = false
        SecondScreenController.serviceFiltersKeys = false
        hideSwapOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        wazeWatch?.cancel()
        keyWatch?.cancel()
        instance = null
        connectedState.value = false
        SecondScreenController.serviceFiltersKeys = false
        hideSwapOverlay()
        super.onDestroy()
    }

    // Window changes are the only events we watch, and only to keep the floating
    // swap button visible exactly while the screen is split. The system batches
    // them (notificationTimeout in the config) and hands over only the last of
    // a burst, so its change flags can be a trailing focus change that hides the
    // pane move before it: every window event re-checks the panes.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                updateOverlayForSplit()
                checkProjectionOnScreen()
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName != null) {
                    // A tile's app torn off its tile shows here before the dashboard even pauses.
                    EmbeddedApp.tileAppInFront(event.packageName)
                    val maps = event.packageName == MapsScreen.PACKAGE
                    if (maps != mapsInFront) {
                        mapsInFront = maps
                        watchContent(wazeRuns || mapsInFront)
                    }
                }
                WazeScreen.onEvent(this, event)
                MapsScreen.onEvent(this, event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                WazeScreen.onEvent(this, event)
                MapsScreen.onEvent(this, event)
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {}

    // Every hardware key, before any app: the second screen's page keys while
    // its cluster shows, then a learned steering wheel button runs (or is
    // captured while learning) whatever is in front. Home and Back are brought
    // back to the main screen when an app inside a tile took them (EmbeddedApp).
    override fun onKeyEvent(event: KeyEvent): Boolean =
        SecondScreenController.onWheelKey(event) ||
            SteeringWheelStore.onKeyEvent(this, event) ||
            EmbeddedApp.keyWhileAway(this, event) ||
            EmbeddedApp.firmwareKey(this, event)

    /**
     * The on-screen bounds of the two split panes, ordered left-to-right, or null
     * when the display isn't showing two real app windows. Panes are the
     * application windows with non-empty bounds; windows with zero-size bounds are
     * bare handles, not visible panes, and are ignored. Only window types/bounds
     * are read here, never any window content.
     *
     * [isInSplitMode] resolves the two panes through this helper; [splitPanePackages]
     * applies the same ordering so the two never disagree about which pane is which.
     */
    private fun splitPaneBounds(): Pair<Rect, Rect>? = runCatching {
        val all = windows ?: emptyList()
        try {
            val rects = all
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .map { Rect().also { r -> it.getBoundsInScreen(r) } }
                .filter { it.width() > 0 && it.height() > 0 }
                .sortedBy { it.left }
            if (rects.size < 2) null else rects.first() to rects.last()
        } finally {
            recycle(all)
        }
    }.getOrNull()

    /**
     * True when the display is currently showing two apps side by side. Detected
     * from the two split panes' bounds ([splitPaneBounds]): a genuine split has its
     * panes separated on at least one axis, so if both pane centres coincide within
     * a lenient 3% tolerance one pane is covering the other (a transient state while
     * switching apps) and it is not treated as split.
     */
    private fun isInSplitMode(): Boolean {
        val (a, b) = splitPaneBounds() ?: return false
        // A floating (freeform / picture-in-picture) window sits *over* the
        // other app; genuine split panes never overlap. Not a split.
        if (Rect.intersects(a, b)) return false
        val centerAx = a.left + a.width() / 2f
        val centerAy = a.top + a.height() / 2f
        val centerBx = b.left + b.width() / 2f
        val centerBy = b.top + b.height() / 2f
        val tolerancePx = resources.displayMetrics.widthPixels * 0.03f
        return kotlin.math.abs(centerAx - centerBx) >= tolerancePx ||
            kotlin.math.abs(centerAy - centerBy) >= tolerancePx
    }

    /** Show the floating swap button while split, hide it otherwise. */
    private fun updateOverlayForSplit() {
        if (isInSplitMode() && !mapsDocked()) showSwapOverlay() else hideSwapOverlay()
    }

    /**
     * The dashboard's own Maps dock (Disposition: maps left or right) puts the
     * Maps window beside the dashboard, which reads as two panes; opening the ⋯
     * menu then moved Maps aside and brought the swap button up. Not a split.
     */
    private fun mapsDocked(): Boolean =
        PipAnchor.dockActive.value || DashLayoutStore.load(this) != DashLayout.GRID &&
            splitPanePackages()?.toList()?.contains(PipAnchor.MAPS_PACKAGE) == true

    /**
     * zlink says when CarPlay / Android Auto comes on screen but not always when
     * it goes: with no zlink window left, it is gone, and the alerts are free of
     * the pill again ([UnitSignals.projectionOnScreen]).
     */
    private fun checkProjectionOnScreen() {
        if (!UnitSignals.projectionOnScreen.value) return
        val shown = runCatching {
            val all = windows ?: return
            try {
                all.any { w ->
                    w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        w.root?.let { root -> (root.packageName == UnitSignals.ZLINK_PACKAGE).also { recycle(root) } } == true
                }
            } finally {
                recycle(all)
            }
        }.getOrDefault(true)
        if (!shown) UnitSignals.projectionLeftScreen()
    }

    /**
     * A split the firmware left half done: the dashboard still in its pane, no
     * app beside it, the divider still up. Nothing to swap, so the toggle (which
     * exits split on this ROM) gives the dashboard the whole screen back.
     */
    private fun leaveHalfSplit(): Boolean {
        if (!MainActivity.inMultiWindowNow()) return false
        Log.i(TAG, "split with no app beside the dashboard: leaving it")
        return toggleSplitScreen()
    }

    private fun toggleSplitScreen(): Boolean =
        runCatching { performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) }
            .onFailure { Log.e(TAG, "toggle split-screen failed", it) }
            .getOrDefault(false)

    /**
     * The two split panes' package names, ordered left-to-right / top-to-bottom to
     * match [splitPaneBounds]. Only each window's owning package is read — never
     * its content — which is all that's needed to relaunch the same two apps in the
     * opposite order. Null when two *distinct* app packages can't be resolved.
     */
    private fun splitPanePackages(): Pair<String, String>? = runCatching {
        val all = windows ?: emptyList()
        try {
            val panes = all
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { w ->
                    val r = Rect().also { w.getBoundsInScreen(it) }
                    val root = w.root
                    val pkg = root?.packageName?.toString()
                    root?.let { recycle(it) }
                    if (r.width() > 0 && r.height() > 0 && !pkg.isNullOrBlank()) r to pkg else null
                }
                .sortedWith(compareBy({ it.first.left }, { it.first.top }))
            if (panes.size < 2 || panes.first().second == panes.last().second) null
            else panes.first().second to panes.last().second
        } finally {
            recycle(all)
        }
    }.getOrNull()

    // Before Android 13 the window and node objects handed out by the system
    // come from a pool and go back to it only when recycled; since, recycling
    // does nothing.
    @Suppress("DEPRECATION")
    private fun recycle(windows: List<AccessibilityWindowInfo>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) windows.forEach { runCatching { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runCatching { node.recycle() }
    }

    /**
     * Swap the two panes. This ROM's SystemUI has **no working swap gesture**
     * (double-tapping the divider does nothing on the head unit), so instead we
     * recreate the split with the apps reversed, reusing the same
     * dock-then-launch-adjacent path [SplitLauncher] already uses to *create* a
     * split here. We read only the two panes' package names and relaunch them in
     * the opposite order.
     *
     * The current split is collapsed first: this ROM's [GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN]
     * *exits* split when already split, and [SplitLauncher.launchSplitPair] expects
     * to start from full screen, so recreating without collapsing would just drop
     * out of split. We toggle back to full screen, then rebuild the pair reversed.
     */
    private fun swapPanes(): Boolean {
        val (first, second) = splitPanePackages() ?: return leaveHalfSplit()
        toggleSplitScreen()
        Handler(Looper.getMainLooper()).postDelayed(
            { SplitLauncher.launchSplitPair(this, second, first) },
            SPLIT_COLLAPSE_MS
        )
        return true
    }

    // --- Global floating swap button ----------------------------------------
    //
    // A draggable button that floats above every app (not just the launcher) so
    // the split panes can be swapped from anywhere. It rides on a
    // TYPE_ACCESSIBILITY_OVERLAY window, which this already-enabled service may
    // add without the separate "draw over other apps" permission. Tap = swap;
    // long-press then drag = reposition (the spot is remembered).

    private var overlayView: View? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showSwapOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return

        val size = dp(56)
        val button = TextView(this).apply {
            text = "⇄"
            contentDescription = this@SplitAccessibilityService.getString(R.string.apps_swap_split_panes)
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(OVERLAY_COLOR)
                setStroke(dp(2), 0x66000000)
            }
        }

        // Prefer a real app overlay — it reliably receives touch. An accessibility
        // overlay is the fallback (some ROMs make it pass-through, so it can't be
        // tapped or dragged), used only when the draw-over-apps op isn't granted.
        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(this)) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }

        val metrics = resources.displayMetrics
        val prefs = getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size, size,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, metrics.widthPixels - size - dp(16))
            y = prefs.getInt(KEY_Y, (metrics.heightPixels - size) / 2)
        }

        button.setOnTouchListener(swapTouchListener(wm, button, params, prefs))

        runCatching { wm.addView(button, params) }
            .onSuccess { overlayView = button }
            .onFailure { Log.e(TAG, "add swap overlay failed", it) }
    }

    /** Tap vs. long-press-then-drag handling for the floating swap button. */
    @SuppressLint("ClickableViewAccessibility")
    private fun swapTouchListener(
        wm: WindowManager,
        button: View,
        params: WindowManager.LayoutParams,
        prefs: android.content.SharedPreferences
    ): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val handler = Handler(Looper.getMainLooper())

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false   // long-press held → in move mode
        var moved = false      // actually repositioned the button while dragging

        // Only a long-press puts the button into move mode. A plain tap — even one
        // that jitters a few pixels, which is normal on a car touchscreen — is not
        // treated as a drag, so it still swaps on release.
        val armDrag = Runnable {
            dragging = true
            button.animate().scaleX(1.15f).scaleY(1.15f).alpha(0.9f).setDuration(120).start()
        }

        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragging = false; moved = false
                    handler.postDelayed(armDrag, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    // A big move before the long-press fires cancels move mode, so
                    // it stays a tap (swap) rather than snapping into a drag.
                    if (!dragging && hypot(dx, dy) > slop * 3) {
                        handler.removeCallbacks(armDrag)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        if (hypot(dx, dy) > slop) moved = true
                        runCatching { wm.updateViewLayout(button, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(armDrag)
                    button.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
                    if (moved) {
                        // A real drag: remember where it was moved to.
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        // Tap (or long-press without moving): swap the panes.
                        swapPanes()
                    }
                    dragging = false; moved = false
                    true
                }
                else -> false
            }
        }
    }

    private fun hideSwapOverlay() {
        val view = overlayView ?: return
        overlayView = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as? WindowManager)?.removeView(view)
        }.onFailure { Log.e(TAG, "remove swap overlay failed", it) }
    }

    companion object {
        private const val TAG = "SplitA11yService"

        /** Time for the ROM to leave split mode before we rebuild the pair reversed. */
        private const val SPLIT_COLLAPSE_MS = 500L

        private const val OVERLAY_PREFS = "split_overlay_prefs"
        private const val KEY_X = "swap_x"
        private const val KEY_Y = "swap_y"
        private val OVERLAY_COLOR = 0xFF1A73E8.toInt()

        @Volatile
        private var instance: SplitAccessibilityService? = null

        /** True once the service is enabled and the system bound it. */
        val isConnected: Boolean get() = instance != null

        /** How long the service gets to come up once turned on, and how often it is looked for. */
        private const val BIND_WAIT_MS = 5_000L
        /** Play edition: the driver's own trip through the disclosure and the system page. */
        private const val DISCLOSURE_WAIT_MS = 60_000L
        private const val BIND_POLL_MS = 250L

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        @Volatile private var autoStarted = false

        /**
         * At start, once per process: the service is turned on by itself when
         * it is off and a privileged shell is known ([PrivilegedShell]), so no
         * one has to find Android's accessibility settings in the car. Typing
         * from the phone, the split, the radar over the reversing camera and
         * the wheel buttons all go through it.
         */
        fun autoTurnOn(context: Context) {
            // Google Play: the driver turns the service on, never the app (AccessDisclosure).
            if (Edition.play) return
            if (autoStarted) return
            autoStarted = true
            val app = context.applicationContext
            scope.launch {
                val access = PrivilegedShell.access.first { it != PrivilegedShell.Access.UNKNOWN }
                if (!access.shell || isConnected) return@launch
                // Only once a feature that goes through the service is in use; never turned off here.
                neededFlow().first { it }
                if (!isConnected) turnOn(app)
            }
        }

        /**
         * Whether a feature that needs the service is switched on (see [serviceNeeded]). The
         * on-demand ones (the phone's typing, split screen taps) start it themselves or fall back.
         */
        private fun neededFlow(): kotlinx.coroutines.flow.Flow<Boolean> = combine(
            SteeringWheelStore.mapping, SteeringWheelStore.buttons, SecondScreenStore.config,
            SecondScreenController.status, EmbeddedApp.keysAwayFlow, RomPopups.replaced, ReverseView.on,
            NavDirections.running
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val config = values[2] as SecondScreenConfig
            val status = values[3] as SecondScreenStatus
            serviceNeeded(
                wheelBound = values[0] as Boolean || (values[1] as List<*>).any { (it as? WheelButton)?.assignment != null },
                clusterKeys = (config.pageKeys.isNotEmpty() || config.mediaKeysTurnPages) &&
                    (status.output == SecondScreenOutput.VIDEO_CLUSTER || status.output == SecondScreenOutput.DATA),
                keysAway = values[4] as Boolean,
                radarReplaced = RomPopups.Kind.RADAR in (values[5] as Set<*>),
                reverseView = values[6] as Boolean,
                wazeRuns = Edition.full && values[7] == WazeScreen.PACKAGE
            )
        }.distinctUntilChanged()

        /**
         * Adds the service to the enabled ones through the privileged shell, the
         * others kept, then gives the system a few seconds to bind it. True once
         * it is bound. Also asked from the phone's keyboard card ([PhoneKeyboard]).
         */
        suspend fun turnOn(context: Context): Boolean {
            if (isConnected) return true
            // The Play edition never switches the service on itself: it says what
            // the service is for and offers the system page, then waits a while
            // for the driver to turn it on there.
            val wait = if (Edition.play) {
                withContext(Dispatchers.Main) { AccessDisclosure.show(context, AccessDisclosure.Kind.ACCESSIBILITY) }
                DISCLOSURE_WAIT_MS
            } else {
                val service = ComponentName(context, SplitAccessibilityService::class.java).flattenToString()
                runCatching { DockShell.shell(context, enableCommand(service)) }
                    .onFailure { Log.w(TAG, "could not turn the service on: ${it.message}") }
                BIND_WAIT_MS
            }
            var waited = 0L
            while (!isConnected && waited < wait) {
                delay(BIND_POLL_MS)
                waited += BIND_POLL_MS
            }
            Log.i(TAG, if (isConnected) "turned on" else "still off after turning it on")
            return isConnected
        }

        /** The shell line that adds [service] to the enabled accessibility services, keeping those already there. */
        fun enableCommand(service: String): String =
            "s=\$(settings get secure enabled_accessibility_services); " +
                "case \":\$s:\" in *\":$service:\"*) ;; " +
                "*) if [ -z \"\$s\" ] || [ \"\$s\" = null ]; then s=$service; else s=\"\$s:$service\"; fi; " +
                "settings put secure enabled_accessibility_services \"\$s\" ;; esac; " +
                "settings put secure accessibility_enabled 1"

        private val connectedState = kotlinx.coroutines.flow.MutableStateFlow(false)
        /** [isConnected], followed: the radar leans on it to draw above the reversing camera ([RomPopups]). */
        val connected: kotlinx.coroutines.flow.StateFlow<Boolean> = connectedState

        /**
         * Ask SystemUI to toggle split-screen. Returns false when the service
         * is not enabled/bound, so callers can fall back to another strategy.
         */
        fun requestSplit(): Boolean = instance?.toggleSplitScreen() ?: false

        /**
         * True when the screen shows two apps side by side right now (false
         * when the service is not enabled/bound). The toggle action *exits*
         * split on this ROM, so callers check this before asking for a split.
         */
        fun isSplit(): Boolean = instance?.isInSplitMode() ?: false

        /**
         * Swap the left/right (or top/bottom) split panes. Returns false when the
         * service is not enabled/bound.
         */
        fun swapSplit(): Boolean = instance?.swapPanes() ?: false

        /**
         * The service as a window context, for an accessibility overlay: the
         * one window type that sits above the ROM's reversing camera
         * ([AlertWindow]). Null when the service is not bound.
         */
        fun overlayHost(): Context? = instance

        /**
         * The view that has the keyboard focus, in whatever window: where text
         * from the phone is typed ([PhoneKeyboard]). Null when nothing has it
         * or the service is not bound; the caller recycles it.
         *
         * Android asks the focused view for its focused field, and a Compose
         * screen (Dashwheel's own, and many apps') answers with the whole screen
         * instead of the field the cursor is in: the phone's text then went to
         * the clipboard, "no field selected". So when the answer is not a
         * field, the windows are searched for the field marked focused.
         */
        fun inputFocus(): AccessibilityNodeInfo? {
            val service = instance ?: return null
            val direct = runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (direct == null || !direct.isEditable) {
                val field = runCatching { focusedField(service) }.getOrNull()
                if (field != null) {
                    direct?.let(::recycleNode)
                    return field
                }
            }
            return direct
        }

        /** Nodes looked at, at most, per window: a field is near the top of any screen worth typing into. */
        private const val FIELD_SEARCH_LIMIT = 4_000

        /** The editable node marked focused, in the focused window first, then the others; null when there is none. */
        private fun focusedField(service: AccessibilityService): AccessibilityNodeInfo? {
            val windows = runCatching { service.windows }.getOrNull().orEmpty()
                .sortedByDescending { it.isFocused || it.isActive }
            val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(service.rootInActiveWindow) }
            for ((index, root) in roots.withIndex()) {
                val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
                var seen = 0
                var found: AccessibilityNodeInfo? = null
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (found == null && seen < FIELD_SEARCH_LIMIT && node.isEditable && node.isFocused) {
                        found = node
                        continue
                    }
                    if (found == null && seen < FIELD_SEARCH_LIMIT) {
                        seen++
                        for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                    }
                    recycleNode(node)
                }
                if (found != null) {
                    roots.drop(index + 1).forEach(::recycleNode)
                    return found
                }
            }
            return null
        }

        // Before Android 13 node objects come from a pool and go back to it only when recycled.
        @Suppress("DEPRECATION")
        private fun recycleNode(node: AccessibilityNodeInfo) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runCatching { node.recycle() }
        }

        /** Any `GLOBAL_ACTION_*` (back, recents, notifications...); false when the service is not bound. */
        fun globalAction(action: Int): Boolean =
            instance?.let { runCatching { it.performGlobalAction(action) }.getOrDefault(false) } ?: false
    }
}

/** See [SplitAccessibilityService.filterKeys]; pure, for the tests. */
internal fun keysWanted(learning: Boolean, wheelBound: Boolean, unitKeys: Boolean, clusterKeys: Boolean, keysAway: Boolean): Boolean =
    learning || clusterKeys || keysAway || (wheelBound && !unitKeys)

/**
 * Whether Dashwheel should switch its accessibility service on by itself: a learned or
 * assigned steering wheel button, the second screen's page keys, an app inside a tile
 * holding the unit's keys, the car app's radar replaced, the reversing camera view, or
 * Waze guiding. The service makes Android (and Compose) do accessibility work on every
 * screen change, so it stays off while none of them is in use; pure, for the tests.
 */
internal fun serviceNeeded(
    wheelBound: Boolean, clusterKeys: Boolean, keysAway: Boolean,
    radarReplaced: Boolean, reverseView: Boolean, wazeRuns: Boolean
): Boolean = wheelBound || clusterKeys || keysAway || radarReplaced || reverseView || wazeRuns
