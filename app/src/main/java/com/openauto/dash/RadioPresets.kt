@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An FM frequency in the radio's 10 kHz units (64 to 108 MHz, Eastern Europe's band included); AM ones are in kHz. */
internal fun isFm(freq: Int): Boolean = freq in 6_000..11_000

private val NO_STATIONS: StateFlow<List<RadioStation>> = MutableStateFlow(emptyList())
private val NO_STATION: StateFlow<RadioStation?> = MutableStateFlow(null)

/**
 * The unit's FM radio from a tile ([StockRadio]: [QfRadio] or [XyFmRadio], whichever the unit's adapter has): its
 * stations, the one playing, a tap to tune. Where the list is one Dashwheel keeps ([StockRadio.editable]), a long
 * press on a station pins it to the front or removes it.
 */
@Composable
internal fun RadioPresetsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val radio = UnitPlatform.adapter.radio
    DisposableEffect(radio) { radio?.start(context); onDispose { radio?.stop(context) } }
    val stations by (radio?.stations ?: NO_STATIONS).collectAsState()
    val playing by (radio?.playing ?: NO_STATION).collectAsState()
    val tap = rememberTapFeedback()
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Radio, contentDescription = null, tint = DashColors.Accent)
                Text(
                    "  " + (playing?.let { p -> listOf(p.name, p.mhz).filter { it.isNotEmpty() }.joinToString("  ") } ?: stringResource(R.string.widgets_radio)),
                    color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { tap(); radio?.previous(context) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.widgets_radio_previous), tint = DashColors.TextPrimary)
                }
                IconButton(onClick = { tap(); radio?.next(context) }, modifier = Modifier.size(DashSize.Touch)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.widgets_radio_next), tint = DashColors.TextPrimary)
                }
            }
            if (radio == null || !radio.installed(context)) {
                Text(stringResource(R.string.widgets_radio_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (stations.isEmpty()) {
                Text(stringResource(radio.emptyTextRes), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val pinnedCount = stations.count { it.pinned }
            // Two rows of three: big enough for a finger at the wheel.
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                stations.chunked(3).forEach { row ->
                    Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                        row.forEach { s ->
                            val on = playing?.freq == s.freq
                            var menu by remember(s.freq) { mutableStateOf(false) }
                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight().clip(DashShape.Medium)
                                    .background(if (on) DashColors.Accent.copy(alpha = 0.25f) else DashColors.CardHi)
                                    .combinedClickable(
                                        role = Role.Button,
                                        onClick = { tap(); radio.tune(context, s) },
                                        onLongClick = if (radio.editable) ({ tap(); menu = true }) else null
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                // The name when the radio has told us one, the frequency under it.
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = DashSpace.Xs)) {
                                    Text(
                                        s.name.ifEmpty { s.mhz }, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                                    )
                                    if (s.name.isNotEmpty()) Text(s.mhz, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                                WindowMenu(
                                    expanded = menu,
                                    onDismissRequest = { menu = false },
                                    containerColor = DashColors.Card.copy(alpha = 1f),
                                    border = BorderStroke(1.dp, DashColors.Line)
                                ) {
                                    // Pins are as many as the tile has room for.
                                    if (s.pinned || pinnedCount < StockRadio.SHOWN) {
                                        RadioMenuItem(stringResource(if (s.pinned) R.string.widgets_radio_unpin else R.string.widgets_radio_pin)) {
                                            menu = false
                                            radio.togglePin(context, s)
                                        }
                                    }
                                    RadioMenuItem(stringResource(R.string.widgets_radio_remove)) {
                                        menu = false
                                        radio.remove(context, s)
                                    }
                                }
                            }
                        }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        modifier = Modifier.heightIn(min = DashSize.MenuRow),
        text = { Text(text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge) },
        onClick = onClick
    )
}

@Composable
internal fun radioPresetsFace(): WidgetFace {
    val context = LocalContext.current
    val radio = UnitPlatform.adapter.radio
    DisposableEffect(radio) { radio?.start(context); onDispose { radio?.stop(context) } }
    val playing by (radio?.playing ?: NO_STATION).collectAsState()
    return WidgetFace(
        icon = Icons.Filled.Radio,
        title = BuiltinKind.RADIO_PRESETS.label,
        value = playing?.mhz ?: "",
        unit = if (playing != null) "MHz" else "",
        caption = playing?.name.orEmpty(),
        actions = listOf(
            FaceAction(Icons.Filled.SkipPrevious, stringResource(R.string.widgets_radio_previous), onClick = { radio?.previous(context) }),
            FaceAction(Icons.Filled.SkipNext, stringResource(R.string.widgets_radio_next), onClick = { radio?.next(context) })
        )
    )
}
