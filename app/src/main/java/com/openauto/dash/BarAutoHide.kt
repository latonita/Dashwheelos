package com.openauto.dash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

/*
 * The bar's auto-hide (Settings › Look): once the bar has sat unused
 * for the chosen number of seconds it slides away. It floats over the pages,
 * which keep the whole height either way; a slim pill over their edge on the
 * bar's side shows where it went, and a swipe from that edge (up from the
 * bottom one, down from the top one) brings it back.
 */

/** Longest delay the Look settings offer before the bar hides, in seconds. */
internal const val MAX_BAR_HIDE_SECONDS = 20

/** A swipe up always leaves the bar this long to be used, even with a shorter delay (0 s included). */
internal const val BAR_REVEAL_GRACE_MS = 3_000L

/** How long the "swipe up" words stay over the pill after the bar hides; the pill stays. */
private const val HANDLE_WORDS_MS = 4_000L

private const val BAR_SLIDE_MS = 250

/** Shortest wait before the bar hides: long enough for a menu opened by a tap to hold it. */
private const val BAR_SETTLE_MS = 400L

/** Band along the pages' edge on the bar's side where a swipe toward the middle brings the hidden bar back. */
private val REVEAL_EDGE = 48.dp

/**
 * How long the bar waits, unused, before it hides: [seconds] (0 to
 * [MAX_BAR_HIDE_SECONDS]), but never less than [BAR_REVEAL_GRACE_MS] right
 * after a swipe brought it back, or a short delay would take it away again
 * before the finger could reach it.
 */
internal fun barHideDelayMs(seconds: Int, afterReveal: Boolean): Long {
    val ms = seconds.coerceIn(0, MAX_BAR_HIDE_SECONDS) * 1_000L
    return if (afterReveal) maxOf(ms, BAR_REVEAL_GRACE_MS) else ms
}

internal object BarAutoHide {
    /**
     * Menus open from the bar. The bar stays while any is: hiding it would
     * take the menu anchored on it along.
     */
    var openMenus by mutableIntStateOf(0)

    /**
     * The tour is lighting up something on the bar ([TourStep.onBar]). The bar
     * stays under it: sliding away would leave the tip pointing at nothing.
     */
    var heldByTour by mutableStateOf(false)
}

/** Whether the bar is up, shared by the bar, the pages' swipe up and the pill. */
@Stable
internal class BarAutoHideState {
    val visible = MutableTransitionState(true)

    /** The bar is up because of a swipe and has not been touched since. */
    var afterReveal by mutableStateOf(false)

    /** The bar has finished sliding away. */
    val hidden: Boolean get() = visible.isIdle && !visible.currentState

    fun reveal() {
        afterReveal = true
        visible.targetState = true
    }
}

@Composable
internal fun rememberBarAutoHideState(): BarAutoHideState = remember { BarAutoHideState() }

/**
 * The bar ([bar]) floating over the pages, hiding itself after
 * [hideSeconds] unused seconds. A finger on the bar, a menu open from it or a
 * tip of the tour pointing at it keeps it up; the wait starts again once they
 * let go.
 *
 * The pages under it keep the whole height, so it slides in and out without
 * resizing anything. Docked app windows are drawn above everything on this
 * head unit: while the bar is up, one it overlaps steps aside, as for a menu,
 * and comes back once the bar has gone.
 */
@Composable
internal fun AutoHidingBar(
    state: BarAutoHideState,
    hideSeconds: Int,
    modifier: Modifier = Modifier,
    position: BarPosition = BarPosition.BOTTOM,
    bar: @Composable () -> Unit
) {
    var pressing by remember { mutableStateOf(false) }
    // Bumped when a finger leaves the bar: the wait starts over.
    var touches by remember { mutableIntStateOf(0) }
    val hold = pressing || BarAutoHide.openMenus > 0 || BarAutoHide.heldByTour

    LaunchedEffect(hold, touches, hideSeconds, state.visible.targetState) {
        if (!hold && state.visible.targetState) {
            // Never at once, even at 0 s: a tap on ⋮ lifts the finger a frame
            // before its menu counts itself open, and hiding the bar in between
            // took the menu with it. The menu is checked again before going.
            delay(maxOf(barHideDelayMs(hideSeconds, state.afterReveal), BAR_SETTLE_MS))
            if (BarAutoHide.openMenus == 0) state.visible.targetState = false
        }
    }

    AnimatedVisibility(
        visibleState = state.visible,
        modifier = modifier.fillMaxWidth(),
        // Slides in from the edge it stands on.
        enter = slideInVertically(tween(BAR_SLIDE_MS)) { if (position == BarPosition.TOP) -it else it } + fadeIn(tween(BAR_SLIDE_MS)),
        exit = slideOutVertically(tween(BAR_SLIDE_MS)) { if (position == BarPosition.TOP) -it else it } + fadeOut(tween(BAR_SLIDE_MS))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .keepClearOfWindows()
                // Watches, never consumes: the bar's buttons and its page swipe work as before.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.any { it.pressed }
                            if (down != pressing) {
                                pressing = down
                                state.afterReveal = false
                                if (!down) touches++
                            }
                        }
                    }
                }
        ) { bar() }
    }
}

/**
 * On the pages: while the bar is hidden, a swipe that starts in the band
 * along their edge on the bar's [position] and goes toward the middle (up
 * from the bottom, down from the top) brings it back ([onRevealed] runs as
 * it does). Taps there still reach the tiles, and a sideways swipe still
 * turns the page; only a swipe going that way is taken, before the pages can
 * scroll with it.
 */
internal fun Modifier.swipeRevealsBar(state: BarAutoHideState, position: BarPosition, onRevealed: () -> Unit): Modifier =
    pointerInput(state, position, onRevealed) {
        val edge = REVEAL_EDGE.toPx()
        val slop = viewConfiguration.touchSlop
        val threshold = 16.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!state.hidden || !inBarEdge(position, down.position.y, size.height, edge)) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            var claimed = false
            var revealed = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (!claimed) {
                    // Sideways first: that is the pages' own swipe.
                    if (abs(dx) > slop && abs(dx) > abs(dy)) break
                    if (barPull(position, dy) > slop) claimed = true
                }
                if (claimed) {
                    change.consume()
                    if (!revealed && barPull(position, dy) >= threshold) {
                        revealed = true
                        state.reveal()
                        onRevealed()
                    }
                }
            }
        }
    }

/**
 * Drawn over the pages' edge on the bar's side while the bar is hidden: a
 * slim pill, and for a few seconds after the bar goes the words "swipe up"
 * (down, for the top bar) on its inner side. A tap on the pill brings the bar
 * back as well as the swipe, which the pages read ([swipeRevealsBar]): on some
 * units the swipe also pulls in Android's own bars.
 */
@Composable
internal fun BarHandle(state: BarAutoHideState, position: BarPosition, modifier: Modifier = Modifier) {
    if (!state.hidden) return
    var words by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HANDLE_WORDS_MS)
        words = false
    }
    val label = stringResource(R.string.dash_bar_show)
    val top = position == BarPosition.TOP
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label
            ) { state.reveal() }
            .semantics { contentDescription = label }
            .padding(start = 40.dp, end = 40.dp, top = if (top) 4.dp else 12.dp, bottom = if (top) 12.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The pill stays on the edge, the words on its inner side.
        val pill: @Composable () -> Unit = {
            Box(
                Modifier
                    .padding(top = if (top) 4.dp else 8.dp, bottom = if (top) 8.dp else 4.dp)
                    .size(width = 72.dp, height = 5.dp)
                    .clip(DashShape.Pill)
                    .background(DashColors.TextSecondary.copy(alpha = 0.55f))
            )
        }
        if (top) pill()
        AnimatedVisibility(words, enter = fadeIn(), exit = fadeOut()) {
            Text(
                stringResource(if (top) R.string.dash_bar_swipe_down_hint else R.string.dash_bar_swipe_up_hint),
                color = DashColors.TextPrimary,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier
                    .padding(top = if (top) 6.dp else 0.dp, bottom = if (top) 0.dp else 6.dp)
                    .clip(DashShape.Pill)
                    .background(DashColors.Card.copy(alpha = 1f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        if (!top) pill()
    }
}
