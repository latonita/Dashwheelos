@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
 * stations, the one playing, a tap to tune. Where the list is one the driver keeps ([StockRadio.editable]), a "+" cell
 * saves the station that is playing (room permitting) and a long
 * press on a station removes it.
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
            val addable = playing?.takeIf { radio.editable && canSaveStation(stations, it) }
            if (stations.isEmpty()) {
                Text(stringResource(radio.emptyTextRes), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                if (addable == null) return@Column
            }
            // Two rows of three: big enough for a finger at the wheel. The saved ones, then the "+" cell (null).
            val cells: List<RadioStation?> = if (addable != null) stations + null else stations
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                cells.chunked(3).forEach { row ->
                    Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
                        row.forEach { s ->
                            if (s == null) {
                                // Saves the station that is playing: outlined, so it doesn't read as a preset.
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxHeight().clip(DashShape.Medium)
                                        .border(1.dp, DashColors.Line, DashShape.Medium)
                                        .combinedClickable(role = Role.Button, onClick = { tap(); radio.add(context, addable!!) }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "+ " + addable!!.mhz, color = DashColors.TextSecondary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = DashSpace.Xs)
                                    )
                                }
                                return@forEach
                            }
                            val on = playing?.freq == s.freq
                            var menu by remember(s.freq) { mutableStateOf(false) }
                            var renaming by remember(s.freq) { mutableStateOf(false) }
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
                                if (renaming) RenameDialog(s, radio.ownName(s), onDone = { renaming = false }, onName = { radio.rename(context, s, it) })
                                WindowMenu(
                                    expanded = menu,
                                    onDismissRequest = { menu = false },
                                    containerColor = DashColors.Card.copy(alpha = 1f),
                                    border = BorderStroke(1.dp, DashColors.Line)
                                ) {
                                    RadioMenuItem(stringResource(R.string.widgets_radio_remove)) {
                                        menu = false
                                        radio.remove(context, s)
                                    }
                                    RadioMenuItem(stringResource(R.string.widgets_radio_rename)) {
                                        menu = false
                                        renaming = true
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

/** Asks for the name of [s]: prefilled, focused with the keyboard up; OK with nothing typed clears the name. */
@Composable
private fun RenameDialog(s: RadioStation, own: String, onDone: () -> Unit, onName: (String) -> Unit) {
    var text by remember { mutableStateOf(own) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun save() { onName(text); onDone() }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = DashColors.Card,
        title = { Text(s.mhz + " MHz", color = DashColors.TextPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filterNot { c -> c in NAME_BREAKS }.take(FmStations.MAX_NAME) },
                singleLine = true,
                label = { Text(stringResource(R.string.widgets_radio_rename)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.focusRequester(focus)
            )
        },
        confirmButton = { TextButton(onClick = { save() }) { Text(stringResource(android.R.string.ok), color = DashColors.TextPrimary) } },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(android.R.string.cancel), color = DashColors.Muted) } }
    )
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

/** Whether the "+" cell shows: [playing] is a station that isn't among [saved] yet, and the tile has room for one more. */
internal fun canSaveStation(saved: List<RadioStation>, playing: RadioStation?): Boolean =
    playing != null && saved.size < StockRadio.SHOWN && saved.none { it.freq == playing.freq }

/** Characters a name can't hold (the stored format is one tab-separated line per station). */
private val NAME_BREAKS = setOf(Char(10), Char(13), Char(9))
