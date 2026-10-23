package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Which edge the dashboard's bar stands on (Settings › Display, with the
 * bar's other settings). Only the standard bar moves: a skin draws its own
 * bar, shaped for the bottom edge (its rule on the top side, its glow
 * below), so under one the bar stays at the bottom and the setting is
 * greyed out, like the readouts' ([BarItemsSetting]).
 */

/** Where the bar stands. Names are persisted: append, never rename. */
internal enum class BarPosition(val title: Int, val icon: ImageVector) {
    BOTTOM(R.string.dash_bar_position_bottom, Icons.Filled.VerticalAlignBottom),
    TOP(R.string.dash_bar_position_top, Icons.Filled.VerticalAlignTop);

    companion object {
        private const val PREFS = "dash_bar"
        private const val KEY = "position"

        private val _chosen = MutableStateFlow(BOTTOM)
        val chosen: StateFlow<BarPosition> = _chosen.asStateFlow()
        private var loaded = false

        @Synchronized
        fun load(context: Context) {
            if (loaded) return
            loaded = true
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            _chosen.value = entries.firstOrNull { it.name == saved } ?: BOTTOM
        }

        fun save(context: Context, position: BarPosition) {
            _chosen.value = position
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, position.name).apply()
        }

        /** Where the bar is drawn now: the choice, for a look whose bar can move. */
        fun shown(): BarPosition = barPositionFor(chosen.value, DashColors.Skin)
    }
}

/**
 * The looks whose bar is the standard one and can stand on either edge: Standard, and Canvas
 * when its side rail is off (it then gets the standard bar). Every other skin draws its own.
 */
internal fun barCanMove(skin: DashSkin): Boolean = skin == DashSkin.STANDARD || skin == DashSkin.CANVAS

/** [chosen] where the look's bar can move there; the bottom, where it cannot. */
internal fun barPositionFor(chosen: BarPosition, skin: DashSkin): BarPosition =
    if (barCanMove(skin)) chosen else BarPosition.BOTTOM

/** The position the bar is drawn in now, for composables on or around the bar. */
@Composable
internal fun rememberBarPosition(): BarPosition {
    BarPosition.load(LocalContext.current)
    val chosen by BarPosition.chosen.collectAsState()
    return barPositionFor(chosen, DashColors.Skin)
}

/**
 * Top of a hint popup beside the bar's button: on the side of the button away
 * from the screen's edge the bar stands on, so it never covers the bar. The
 * bottom bar's hint goes above the button, the top bar's under it; kept inside
 * a window [windowHeight] tall.
 */
internal fun hintTop(position: BarPosition, anchorTop: Int, anchorBottom: Int, hintHeight: Int, gap: Int, windowHeight: Int): Int {
    val room = (windowHeight - hintHeight).coerceAtLeast(0)
    return when (position) {
        BarPosition.BOTTOM -> (anchorTop - hintHeight - gap).coerceAtLeast(0)
        BarPosition.TOP -> (anchorBottom + gap).coerceIn(0, room)
    }
}

/** Whether a touch at [y] in an area [height] tall, [edge] being the width of the band, is in the band along the bar's edge. */
internal fun inBarEdge(position: BarPosition, y: Float, height: Int, edge: Float): Boolean = when (position) {
    BarPosition.BOTTOM -> y >= height - edge
    BarPosition.TOP -> y <= edge
}

/** How far a drag of [dy] pixels (down is positive) goes toward bringing the bar in from its edge. */
internal fun barPull(position: BarPosition, dy: Float): Float = if (position == BarPosition.BOTTOM) -dy else dy

/** The bar's position, settings and the bar's own sheet: Bottom, Top. */
@Composable
internal fun BarPositionSetting() {
    val context = LocalContext.current
    BarPosition.load(context)
    val chosen by BarPosition.chosen.collectAsState()
    val movable = barCanMove(DashColors.Skin)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.dash_bar_position),
        color = DashColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
    )
    SegmentedSwitch(
        options = BarPosition.entries,
        // A skin keeps its bar at the bottom, whatever was chosen before.
        chosen = barPositionFor(chosen, DashColors.Skin),
        icon = { it.icon },
        title = { stringResource(it.title) },
        enabled = { movable || it == BarPosition.BOTTOM },
        onChoose = { BarPosition.save(context, it) }
    )
    if (!movable) SwitchHint(stringResource(R.string.dash_bar_position_skin))
}
