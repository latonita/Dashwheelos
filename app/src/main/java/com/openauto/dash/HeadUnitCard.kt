package com.openauto.dash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openauto.dash.unit.fyt.FytKeys
import com.openauto.dash.unit.mtc.MtcSurvival
import com.openauto.unitproto.UnitFamily
import kotlinx.coroutines.launch

/*
 * Settings → Head unit: which firmware family the unit was found to
 * run ([UnitPlatform]), the driver's say when the detector got it wrong,
 * what that firmware lets Dashwheel do and what each feature still needs,
 * and what keeps Dashwheel alive through the unit's sleep.
 */

/** The family's name for the driver. */
@Composable
internal fun familyName(family: UnitFamily): String = stringResource(
    when (family) {
        UnitFamily.QF -> R.string.unit_family_qf
        UnitFamily.FYT -> R.string.unit_family_fyt
        UnitFamily.MTC -> R.string.unit_family_mtc
        UnitFamily.TOPWAY -> R.string.unit_family_topway
        UnitFamily.REGLINK -> R.string.unit_family_reglink
        UnitFamily.ALLWINNER -> R.string.unit_family_allwinner
        UnitFamily.NWD -> R.string.unit_family_nwd
        UnitFamily.XYAUTO -> R.string.unit_family_xyauto
        UnitFamily.GENERIC, UnitFamily.UNKNOWN -> R.string.unit_family_generic
    }
)

/** The row in the System pane: the family and how it was arrived at. */
@Composable
internal fun HeadUnitRow(onOpen: () -> Unit) {
    val family by UnitPlatform.family.collectAsState()
    val chosen by UnitPlatform.override.collectAsState()
    val name = familyName(family)
    val detail = if (chosen != null) stringResource(R.string.unit_chosen, name) else stringResource(R.string.unit_detected, name)
    SettingsRow(Icons.Filled.Memory, stringResource(R.string.unit_card_title), detail, onOpen)
}

/** The sheet: family choice, the firmware's features and their state, the sleep survival steps. */
@Composable
internal fun HeadUnitSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val family by UnitPlatform.family.collectAsState()
    val chosen by UnitPlatform.override.collectAsState()
    val used by UnitPlatform.used.collectAsState(UnitPlatform.usedNow())
    val access by PrivilegedShell.access.collectAsState()
    val adapter = UnitPlatform.adapter
    val detected = UnitPlatform.detected
    var pending by remember { mutableStateOf(chosen) }
    val changed = pending != chosen

    SettingsSheet(
        title = stringResource(R.string.unit_card_title),
        onDismiss = onDismiss,
        actions = {
            if (changed) {
                SheetButton(stringResource(R.string.unit_restart)) {
                    UnitPlatform.setOverride(context, pending)
                    UnitPlatform.restart(context)
                }
            }
            SheetButton(stringResource(R.string.ai_done), primary = !changed, onClick = onDismiss)
        }
    ) {
        Text(
            stringResource(if (chosen != null) R.string.unit_chosen else R.string.unit_detected, familyName(family)),
            color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium
        )
        if (detected.evidence.isNotEmpty()) {
            Text(detected.evidence.take(4).joinToString(", "), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        SettingsSection(stringResource(R.string.unit_choose_title))
        ChoiceLine(stringResource(R.string.unit_auto) + " (" + familyName(detected.family) + ")", pending == null) { pending = null }
        for (f in UnitFamily.entries.filter { it != UnitFamily.UNKNOWN }) {
            ChoiceLine(familyName(f), pending == f) { pending = f }
        }
        if (changed) Text(stringResource(R.string.unit_restart_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)

        val caps = adapter.caps.claimed.filter { capName(it) != null }.sortedBy { it.ordinal }
        if (caps.isNotEmpty()) {
            SettingsSection(stringResource(R.string.unit_caps_section))
            for (cap in caps) {
                val state = adapter.caps.state(cap, access, PrivilegedShell.settingsGranted, used)
                val need = adapter.caps.need(cap) ?: Need.NONE
                val stateText = when (state) {
                    CapState.LIVE -> stringResource(R.string.unit_state_live)
                    CapState.READY -> stringResource(R.string.unit_state_ready)
                    CapState.LOCKED -> needText(need)
                    CapState.ABSENT -> ""
                }
                val note = adapter.caps.note(cap)?.let { stringResource(it) }
                CapLine(stringResource(capName(cap)!!), listOfNotNull(stateText, note).joinToString(". "), state)
            }
        }

        // The FYT wheel keys are opt-in: the firmware's own apps lose them meanwhile.
        if (family == UnitFamily.FYT && adapter.caps.has(Cap.UNIT_KEYS)) {
            var take by remember { mutableStateOf(FytKeys.enabled(context)) }
            SettingsToggle(Icons.Filled.DirectionsCar, stringResource(R.string.unit_fyt_keys_title), stringResource(R.string.unit_fyt_keys_detail), take) {
                take = it
                FytKeys.setEnabled(context, it)
            }
        }

        val survival = adapter.survival
        if (survival != null) {
            SettingsSection(stringResource(R.string.unit_survival_section))
            var applied by remember { mutableStateOf(false) }
            for (step in survival.steps()) {
                val can = step.automatic && UnitCaps.needMet(step.need, access, PrivilegedShell.settingsGranted)
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(stringResource(step.title), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(step.detail) + if (!step.automatic || can) "" else " " + needText(step.need),
                        color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            val automatic = survival.steps().any { it.automatic && UnitCaps.needMet(it.need, access, PrivilegedShell.settingsGranted) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (automatic) {
                    SheetButton(stringResource(if (applied) R.string.unit_applied else R.string.unit_apply), primary = !applied) {
                        scope.launch { applied = survival.apply(context, access) }
                    }
                }
                (survival as? MtcSurvival)?.bootControlIntent()?.let { intent ->
                    Spacer(Modifier.width(8.dp))
                    SheetButton(stringResource(R.string.unit_open_whitelist), primary = false) { context.launchSafely(intent) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** What a need reads as, when it isn't met. */
@Composable
private fun needText(need: Need): String = stringResource(
    when (need) {
        Need.NONE -> R.string.unit_state_ready
        Need.SETTINGS -> R.string.unit_needs_settings
        Need.SHELL -> R.string.unit_needs_shell
        Need.ROOT -> R.string.unit_needs_root
    }
)

/** The capability's name for the driver; null for the ones not worth a line of their own. */
private fun capName(cap: Cap): Int? = when (cap) {
    Cap.IGNITION -> R.string.unit_cap_ignition
    Cap.SLEEP_SOON -> null
    Cap.VEHICLE_BUS -> R.string.unit_cap_bus
    Cap.DOORS -> R.string.unit_cap_doors
    Cap.REVERSE -> R.string.signals_reverse
    Cap.STEERING -> R.string.unit_cap_steering
    Cap.RADAR -> R.string.alert_radar_title
    Cap.CLIMATE -> R.string.unit_cap_climate
    Cap.BODY_TRIP -> R.string.unit_cap_body
    Cap.SPEED_RPM -> R.string.unit_cap_speed
    Cap.VOLTS -> R.string.unit_cap_volts
    Cap.HEADLIGHTS -> R.string.signals_headlights
    Cap.OUTSIDE_TEMP -> R.string.unit_cap_temp
    Cap.UNIT_KEYS -> R.string.wheel_title
    Cap.KEY_TARGETS -> R.string.keys_section
    Cap.PHONE_BRIDGE -> R.string.unit_cap_phone
    Cap.MEDIA_BRIDGE -> R.string.unit_cap_media
    Cap.VOLUME_UNIT -> R.string.unit_cap_volume
    Cap.LAUNCHER_TAKEOVER -> R.string.unit_cap_launcher
    Cap.POPUP_HIDE -> R.string.unit_cap_popups
    Cap.SLEEP_SURVIVAL -> R.string.unit_survival_section
    Cap.CAMERA_CONTROL -> R.string.unit_cap_camera
    Cap.FREEFORM_BAR -> R.string.settings_unit_bar_title
    Cap.BOOT_LOGO -> R.string.boot_menu
    Cap.TYRES -> R.string.unit_cap_tyres
}

@Composable
private fun ChoiceLine(text: String, chosen: Boolean, onChoose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(DashShape.Medium).clickable(onClick = onChoose).padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Icon(
            if (chosen) Icons.Filled.Check else Icons.Filled.RadioButtonUnchecked, contentDescription = null,
            tint = if (chosen) DashColors.Accent else DashColors.TextSecondary
        )
        Spacer(Modifier.width(12.dp))
        Text(text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CapLine(title: String, detail: String, state: CapState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotEmpty()) Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            when (state) {
                CapState.LIVE -> Icons.Filled.Check
                else -> Icons.Filled.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = when (state) {
                CapState.LIVE -> DashColors.Accent
                CapState.READY -> DashColors.TextSecondary
                else -> DashColors.TextSecondary.copy(alpha = 0.5f)
            }
        )
    }
}
