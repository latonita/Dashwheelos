package com.openauto.dash

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.StateFlow

/**
 * The cluster ([ClusterScreen]) on the streamed display ([StreamDisplay]).
 *
 * Made on the application's context with a lifecycle of its own
 * ([OverlayOwner]): it keeps drawing while another app covers the dashboard,
 * which is when a cluster is most useful. The display being the launcher's own
 * private one, Android gives the window the private-presentation type, which
 * needs no permission. Its density is set from the streamed picture's height,
 * so the widgets come out the same size on any monitor.
 */
internal class ClusterPresentation(
    context: Context,
    display: Display,
    private val page: StateFlow<ClusterPage>,
    private val pages: StateFlow<List<ClusterPage>>,
    private val overscanPct: Int,
    /** True while the dashboard itself is on screen and keeps the palette in step. */
    private val dashboardShowing: StateFlow<Boolean>
) : Presentation(context, display) {

    private val owner = OverlayOwner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        val height = display.mode.physicalHeight.coerceAtLeast(240)
        // In Dashwheel's language, one picked inside it included. Presentation builds its context
        // with createDisplayContext, which on Android 10 drops a language set on the context it
        // was given: the Pi showed the board in the unit's language (English) while Dashwheel
        // and the Pi's own lines were in French. Applied again here, on the display's context.
        val view = ComposeView(AppLanguage.wrap(context)).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                // The dashboard's widgets, sized as on a 600 dp tall screen, whatever the picture.
                val density = Density(height / CLUSTER_HEIGHT_DP, fontScale = 1f)
                // The 3D map asks for the location through an activity, which a
                // Presentation has none of: the dashboard asked already.
                CompositionLocalProvider(LocalDensity provides density, LocalActivityResultRegistryOwner provides NoResults) {
                    val dashboard by dashboardShowing.collectAsState()
                    // With the dashboard stopped (an app in front), its palette
                    // stops following the time of day: the cluster takes over.
                    if (!dashboard) ClusterThemeSync()
                    OpenAutoDashTheme {
                        ClusterScreen(page, pages, overscanPct)
                    }
                }
            }
        }
        setContentView(view)
    }

    override fun onStop() {
        owner.destroy()
        super.onStop()
    }

    private companion object {
        const val CLUSTER_HEIGHT_DP = 600f

        /** Asks for nothing: what the dashboard itself must ask is asked on the dashboard. */
        val NoResults = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) = Unit
            }
        }
    }
}

/** The palette as the dashboard would pick it, while the dashboard can't. */
@androidx.compose.runtime.Composable
private fun ClusterThemeSync() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mode = androidx.compose.runtime.remember { DashThemeStore.load(context) }
    val appearance = androidx.compose.runtime.remember { DashThemeStore.loadAppearance(context) }
    val effects = androidx.compose.runtime.remember { DashThemeStore.loadEffects(context) }
    val transparent = androidx.compose.runtime.remember { DashThemeStore.loadTransparentPanels(context) }
    DashColors.Sync(mode, appearance, effects, transparent)
}
