package com.openauto.dash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openauto.dash.vehicle.Domain
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.rememberVehicle

/*
 * The Custom gauge tile: one vehicle-bus signal, as a dial, a horizontal or a
 * vertical bar, or a plain number. The dial is the engine gauges' own
 * ([AnalogGauge]) and the bar is drawn the way the rev bar is, so every look
 * (glass, bare, transparent panels) styles it as it does them. The settings
 * live on the tile ([DashboardItem.BuiltinWidget.opts], [GaugeConfig]).
 */

/** A key's name in the current language: `gauge_key_<id>` where there is one (also for a key a branch added), else its id as words. */
@Composable
internal fun gaugeKeyTitle(key: Key<*>): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(key.id, locale) {
        val id = context.resources.getIdentifier("gauge_key_${key.id}", "string", context.packageName)
        if (id != 0) context.getString(id) else humanizeKeyId(key.id)
    }
}

@Composable
private fun gaugeDomainTitle(domain: Domain): String {
    val context = LocalContext.current
    val id = context.resources.getIdentifier("gauge_domain_${domain.name.lowercase()}", "string", context.packageName)
    return if (id != 0) stringResource(id) else humanizeKeyId(domain.name.lowercase())
}

/** The key's unit as this driver sees it (km/h or mph, °C or °F, bar or psi). */
@Composable
private fun rememberGaugeUnit(key: Key<*>?): GaugeUnit {
    val units by Units.current.collectAsState()
    val pressure = if (key?.unit == "kPa") Tyres.unit.collectAsState().value else PressureUnit.BAR
    return remember(key?.unit, units, pressure) { gaugeUnit(key?.unit, units, pressure) }
}

/** The signal's number, null while the bus has none (and null for a key that is not numeric). */
@Suppress("UNCHECKED_CAST")
@Composable
private fun rememberGaugeValue(key: Key<*>?): Double? {
    if (key == null) return null
    // Read as Any: a Key<Double> that held anything else must not fail a cast of its own.
    val raw: Any? = rememberVehicle(key as Key<Any>)
    return (raw as? Number)?.toDouble()
}

@Composable
internal fun CustomGaugeCard(config: GaugeConfig, modifier: Modifier = Modifier) {
    val key = remember(config.keyId) { gaugeKeyById(config.keyId) }
    val gu = rememberGaugeUnit(key)
    val value = rememberGaugeValue(key)
    val (lo, hi) = remember(config, key?.unit) { gaugeRange(config, key?.unit) }
    val fraction = gaugeFraction(value, lo, hi)
    val text = formatGaugeValue(value, gu)
    val zone = remember(config, lo, hi) { redZone(config, lo, hi) }
    val inRed = inRedZone(value, config) && zone != null
    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // One row high (a bar tile): the title and the bar, whatever the style; a number keeps its figure beside the title.
            val compact = maxHeight < COMPACT_HEIGHT
            val style = if (compact && config.style != GaugeStyle.NUMBER) GaugeStyle.BAR_H else config.style
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = DashSpace.Lg, vertical = if (compact) DashSpace.Sm else DashSpace.Lg),
                verticalArrangement = Arrangement.spacedBy(if (compact) DashSpace.Xs else DashSpace.Sm)
            ) {
                TileHeader(if (key != null) gaugeKeyTitle(key) else stringResource(R.string.gauge_kind)) {
                    if (compact && style == GaugeStyle.NUMBER && key != null) {
                        Text(text, color = if (value == null) DashColors.Muted else DashColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    if (gu.label.isNotEmpty()) {
                        Text(gu.label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    when {
                        key == null -> Text(
                            stringResource(if (config.keyId == null) R.string.gauge_pick_hint else R.string.gauge_unknown_signal),
                            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis
                        )
                        compact && style == GaugeStyle.NUMBER -> Unit
                        style == GaugeStyle.DIAL -> AnalogGauge(
                            value = ((value ?: lo) - lo).toFloat(),
                            maxValue = (hi - lo).toFloat(),
                            valueText = text,
                            label = "",
                            unit = gu.label,
                            accent = DashColors.Accent,
                            modifier = Modifier.fillMaxSize(),
                            // No red zone unless the driver set one: this tile knows the scale, not the limits.
                            redlineFraction = if (zone != null && !config.redBelow) zone.from else 1.0001f,
                            redBelowFraction = if (zone != null && config.redBelow) zone.to else 0f,
                            dimmed = value == null
                        )
                        style == GaugeStyle.BAR_H -> Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
                            // The bar alone: no figure, the title says what it is.
                            GaugeBar(fraction, vertical = false, zone = zone, inRed = inRed, modifier = Modifier.fillMaxWidth().height(14.dp))
                            if (!compact) {
                                Spacer(Modifier.height(DashSpace.Xs))
                                ScaleEnds(lo, hi, gu)
                            }
                        }
                        style == GaugeStyle.BAR_V -> Row(horizontalArrangement = Arrangement.spacedBy(DashSpace.Lg), modifier = Modifier.fillMaxSize()) {
                            GaugeBar(fraction, vertical = true, zone = zone, inRed = inRed, modifier = Modifier.width(26.dp).fillMaxHeight())
                            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                                Text(formatGaugeValue(hi, gu), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                BigNumber(text, value == null, inRed, Modifier.fillMaxWidth().weight(1f, fill = false))
                                Text(formatGaugeValue(lo, gu), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        else -> BigNumber(text, value == null, inRed, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** Under this a tile is one row high: a title and a bar. */
private val COMPACT_HEIGHT = 110.dp

/** The figure, as large as its box allows. */
@Composable
private fun BigNumber(text: String, dimmed: Boolean, red: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // A digit is about 0.6 em wide: the size that fits the width, held by the height.
        val byWidth = maxWidth.value / (text.length.coerceAtLeast(2) * 0.62f)
        val size = minOf(byWidth, maxHeight.value * 0.8f, 120f).coerceAtLeast(14f)
        Text(
            text,
            color = if (dimmed) DashColors.Muted else if (red) DashColors.Critical else DashColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = size.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun ScaleEnds(lo: Double, hi: Double, gu: GaugeUnit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatGaugeValue(lo, gu), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(formatGaugeValue(hi, gu), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * A rounded bar filling from the start (the left, or the bottom) in the accent's gradient, with the glow the
 * theme gives the rev bar; empty while there is no [fraction].
 */
@Composable
private fun GaugeBar(fraction: Float?, vertical: Boolean, zone: GaugeRedZone?, inRed: Boolean, modifier: Modifier = Modifier) {
    // Read only while drawing, so the slide redraws the bar and does not recompose the tile.
    val shown = animateFloatAsState(targetValue = fraction ?: 0f, animationSpec = tween(durationMillis = 400), label = "gaugeBar")
    val accent = DashColors.Accent
    val accent2 = DashColors.Accent2
    val glow = DashColors.Glow
    val critical = DashColors.Critical
    // Bare themes have no card behind the bar, so a background-coloured track would vanish.
    val track = if (DashColors.Glass) DashColors.well(0.35f) else if (DashColors.Bare) DashColors.CardHi else DashColors.Background
    Spacer(
        modifier = modifier.drawWithCache {
            val thick = if (vertical) size.width else size.height
            val corner = CornerRadius(thick / 2f)
            val fill: Brush = if (inRed) SolidColor(critical) else if (vertical) Brush.verticalGradient(listOf(accent2, accent), startY = 0f, endY = size.height)
                else Brush.horizontalGradient(listOf(accent, accent2), endX = size.width)
            onDrawBehind {
                drawRoundRect(color = track, size = size, cornerRadius = corner)
                if (zone != null) {
                    // The red part of the track, clipped to its rounded ends.
                    val path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, corner)) }
                    clipPath(path) {
                        val red = critical.copy(alpha = 0.45f)
                        if (vertical) {
                            drawRect(red, Offset(0f, size.height * (1f - zone.to)), Size(size.width, size.height * (zone.to - zone.from)))
                        } else {
                            drawRect(red, Offset(size.width * zone.from, 0f), Size(size.width * (zone.to - zone.from), size.height))
                        }
                    }
                }
                val f = shown.value
                if (f <= 0f) return@onDrawBehind
                val fillSize = if (vertical) Size(size.width, size.height * f) else Size(size.width * f, size.height)
                val topLeft = if (vertical) Offset(0f, size.height - fillSize.height) else Offset.Zero
                if (glow > 0f) {
                    val grow = thick * 0.6f
                    drawRoundRect(
                        brush = fill,
                        topLeft = if (vertical) Offset(topLeft.x - grow, topLeft.y) else Offset(topLeft.x, -grow),
                        size = if (vertical) Size(fillSize.width + 2 * grow, fillSize.height) else Size(fillSize.width, fillSize.height + 2 * grow),
                        cornerRadius = CornerRadius(thick),
                        alpha = 0.30f * glow
                    )
                }
                drawRoundRect(brush = fill, topLeft = topLeft, size = fillSize, cornerRadius = corner)
            }
        }
    )
}

/** The tile panel's way in to a copy's own settings: the dialog below, saved into the tile. */
internal fun gaugeSettings(tile: DashboardItem.BuiltinWidget, onChange: (DashboardItem) -> Unit) =
    TileSettings(R.string.gauge_options) { dismiss ->
        CustomGaugeDialog(
            initial = GaugeConfig.decode(tile.opts),
            onSave = { onChange(tile.copy(opts = it.encode())); dismiss() },
            onDismiss = dismiss
        )
    }

/** Signal, style and scale of one Custom gauge. Saving hands back the whole config; closing it leaves the tile as it was. */
@Composable
internal fun CustomGaugeDialog(initial: GaugeConfig, onSave: (GaugeConfig) -> Unit, onDismiss: () -> Unit) {
    var keyId by remember { mutableStateOf(initial.keyId) }
    var style by remember { mutableStateOf(initial.style) }
    val key = remember(keyId) { gaugeKeyById(keyId) }
    val gu = rememberGaugeUnit(key)
    // The scale's ends are typed in the shown unit; blank is the usual scale.
    var redBelow by remember { mutableStateOf(initial.redBelow) }
    var redText by remember { mutableStateOf(initial.red?.let { v -> key?.let { formatGaugeEnd(v, gu) } }.orEmpty()) }
    var minText by remember { mutableStateOf(initial.min?.let { v -> key?.let { formatGaugeEnd(v, gu) } }.orEmpty()) }
    var maxText by remember { mutableStateOf(initial.max?.let { v -> key?.let { formatGaugeEnd(v, gu) } }.orEmpty()) }
    val keys = remember { gaugeKeys() }
    val titles = keys.associateWith { gaugeKeyTitle(it) }
    val groups = groupGaugeKeys(keys, name = { titles.getValue(it) }, tag = { it })
    val tap = rememberTapFeedback()
    val units by Units.current.collectAsState()
    val pressure = PressureUnit.BAR
    // The usual scale, in the shown unit, as the fields' hint.
    val (defLo, defHi) = defaultGaugeRange(keyId, key?.unit)
    val shownScaleUnit = if (gu.fromShown != null) gu.label else key?.unit.orEmpty()

    SettingsSheet(
        title = stringResource(R.string.gauge_sheet_title),
        onDismiss = onDismiss,
        actions = {
            SheetButton(stringResource(R.string.dash_close), primary = false) { onDismiss() }
            SheetButton(stringResource(R.string.gauge_save)) {
                onSave(
                    GaugeConfig(
                        keyId = keyId, style = style,
                        min = if (key == null) null else parseGaugeEnd(minText, gu),
                        max = if (key == null) null else parseGaugeEnd(maxText, gu),
                        red = if (key == null) null else parseGaugeEnd(redText, gu),
                        redBelow = redBelow
                    )
                )
            }
        }
    ) {
        PickerLabel(stringResource(R.string.gauge_style))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                GaugeStyle.DIAL to R.string.gauge_style_dial, GaugeStyle.BAR_H to R.string.gauge_style_bar_h,
                GaugeStyle.BAR_V to R.string.gauge_style_bar_v, GaugeStyle.NUMBER to R.string.gauge_style_number
            ).forEach { (s, label) -> CategoryChip(stringResource(label), style == s) { style = s } }
        }
        if (key != null && style != GaugeStyle.NUMBER) {
            PickerLabel(stringResource(R.string.gauge_range, shownScaleUnit))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ScaleField(stringResource(R.string.gauge_min), minText, formatGaugeEnd(defLo, gu), { minText = it }, Modifier.weight(1f))
                ScaleField(stringResource(R.string.gauge_red), redText, stringResource(R.string.gauge_red_none), { redText = it }, Modifier.weight(1f))
                ScaleField(stringResource(R.string.gauge_max), maxText, formatGaugeEnd(defHi, gu), { maxText = it }, Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = DashSize.Touch),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.gauge_red_below), color = DashColors.TextPrimary, modifier = Modifier.weight(1f))
                Switch(checked = redBelow, onCheckedChange = { redBelow = it })
            }
            Text(stringResource(R.string.gauge_range_default), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        PickerLabel(stringResource(R.string.gauge_signal))
        groups.forEach { (domain, inDomain) ->
            Text(
                gaugeDomainTitle(domain).uppercase(),
                color = DashColors.Muted, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                style = MaterialTheme.typography.labelMedium, maxLines = 1,
                modifier = Modifier.padding(top = 6.dp)
            )
            inDomain.forEach { k ->
                SignalRow(k, selected = k.id == keyId) {
                    tap()
                    if (k.id != keyId) {
                        // The old signal's scale means nothing for the new one.
                        keyId = k.id; minText = ""; maxText = ""
                        // Its usual alarm, to keep, change or clear.
                        val red = defaultGaugeRed(k.id)
                        redText = red?.let { formatGaugeEnd(it.first, gaugeUnit(k.unit, units, pressure)) }.orEmpty()
                        redBelow = red?.second ?: false
                    }
                }
            }
        }
    }
}

@Composable
private fun ScaleField(label: String, text: String, default: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        placeholder = { Text(default, color = DashColors.Muted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        colors = fieldColors()
    )
}

@Composable
private fun PickerLabel(text: String) {
    Text(text, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
}

/** One signal in the picker: its name and unit, "no data yet" while the bus has had nothing for it. */
@Composable
private fun SignalRow(key: Key<*>, selected: Boolean, onClick: () -> Unit) {
    val members = if (key is Key.BestOf<*>) key.members else listOf(key)
    val has = members.map { Vehicle.bus.has(it).collectAsState().value }.any { it }
    val gu = rememberGaugeUnit(key)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.Touch)
            .clip(DashShape.Small)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                gaugeKeyTitle(key),
                color = if (selected) DashColors.AccentInk else DashColors.TextPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (!has) Text(stringResource(R.string.gauge_no_data), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (gu.label.isNotEmpty()) {
            Text(gu.label, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = DashColors.Accent)
    }
}
