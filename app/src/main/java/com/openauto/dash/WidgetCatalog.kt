package com.openauto.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.StringRes
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CarRepair
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsCarFilled
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PropaneTank
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Commute
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The built-in tile catalogue: names, blurbs and icons (the picker is AddSheet.kt).
 */

/** Display name of a built-in tile, in the current language. */
val BuiltinKind.label: String
    @Composable get() = stringResource(labelRes)

/** One-line description of a built-in tile for the picker, in the current language. */
val BuiltinKind.blurb: String
    @Composable get() = stringResource(blurbRes)

/** What a widget cannot show anything without; [needsRes] is the add sheet's chip while it is missing. */
enum class WidgetSource(@StringRes val needsRes: Int) {
    OBD(R.string.apps_needs_obd),
    PHONE(R.string.apps_needs_phone),
    INTERNET(R.string.apps_needs_internet),
    ROOT(R.string.apps_needs_root),
    CARBOX(R.string.apps_needs_carbox)
}

/**
 * The kinds of car data a car-data tile can show, by the bus keys that feed it (any one will do), whoever supplies them:
 * a canbox, the unit's bus, the car app, the MCU, the adapter. Null for a tile that is not about the car; empty for one
 * only the adapter can fill (fault codes, raw readings). The one list the add sheet's chips and the templates both ask
 * ([carDataFrom]). The mileage stands in for the car app's body and trip computer (as [Cap.BODY_TRIP] does).
 */
internal val BuiltinKind.carDataKeys: Set<Key<*>>?
    get() = when (this) {
        BuiltinKind.TELEMETRY -> setOf(K.VEHICLE_SPEED, K.RPM, K.COOLANT_TEMP, K.ENGINE_LOAD, K.BATTERY_VOLTS)
        BuiltinKind.WARMUP -> setOf(K.COOLANT_TEMP)
        BuiltinKind.BATTERY -> setOf(K.BATTERY_VOLTS)
        BuiltinKind.ENGINE_TEMPS -> setOf(K.COOLANT_TEMP, K.INTAKE_TEMP)
        BuiltinKind.ECO_DRIVE -> setOf(K.VEHICLE_SPEED, K.RPM)
        BuiltinKind.BREAK_TIMER -> setOf(K.VEHICLE_SPEED, K.RPM, K.ENGINE_RUNNING)
        BuiltinKind.FILTER_CARE -> setOf(K.VEHICLE_SPEED, K.COOLANT_TEMP)
        BuiltinKind.RANGE, BuiltinKind.FUEL_TO_DEST -> setOf(K.RANGE, K.FUEL_LEVEL, K.ODOMETER)
        BuiltinKind.GEAR -> setOf(K.GEAR, K.ODOMETER)
        BuiltinKind.DOORS -> setOf(K.DOORS)
        BuiltinKind.CAR_STATUS -> setOf(K.ODOMETER)
        BuiltinKind.INDICATORS -> setOf(K.INDICATORS, K.LIGHTS, K.HANDBRAKE, K.DRIVER_BELT)
        BuiltinKind.OBD_DTC, BuiltinKind.OBD_ALL, BuiltinKind.MY_READINGS, BuiltinKind.CAN_MON -> emptySet()
        else -> null
    }

/** The car-data tiles an OBD adapter can fill whatever the other sources say. */
private val OBD_FED = setOf(
    BuiltinKind.TELEMETRY, BuiltinKind.OBD_DTC, BuiltinKind.OBD_ALL, BuiltinKind.WARMUP, BuiltinKind.BATTERY,
    BuiltinKind.FILTER_CARE, BuiltinKind.ECO_DRIVE, BuiltinKind.BREAK_TIMER, BuiltinKind.ENGINE_TEMPS,
    BuiltinKind.MY_READINGS, BuiltinKind.RANGE, BuiltinKind.FUEL_TO_DEST, BuiltinKind.GEAR
)

/**
 * Whether this car can supply the tile's data from some source: an OBD adapter ([obd]: chosen, dialable or
 * connected) for the tiles it fills, or any non-OBD source delivering one of the tile's keys ([keys], see
 * [UnitPlatform.suppliedKeys]). A tile that is not about the car is always fed.
 */
internal fun BuiltinKind.carDataFrom(obd: Boolean, keys: Set<Key<*>>): Boolean {
    val wanted = carDataKeys ?: return true
    return (obd && this in OBD_FED) || wanted.any { it in keys }
}

/**
 * The sources this kind needs to show anything. A car-data kind that something other than the adapter can feed
 * (the box, the unit's bus, the car app) no longer asks for the adapter ([carDataFrom]).
 */
internal val BuiltinKind.needs: Set<WidgetSource>
    get() = when (this) {
        BuiltinKind.TELEMETRY, BuiltinKind.OBD_DTC, BuiltinKind.OBD_ALL, BuiltinKind.WARMUP, BuiltinKind.BATTERY,
        BuiltinKind.FILTER_CARE, BuiltinKind.ECO_DRIVE, BuiltinKind.ENGINE_TEMPS, BuiltinKind.MY_READINGS,
        BuiltinKind.BREAK_TIMER, BuiltinKind.RANGE, BuiltinKind.FUEL_TO_DEST, BuiltinKind.GEAR ->
            if (carDataFrom(obd = false, keys = UnitPlatform.suppliedKeys())) emptySet() else setOf(WidgetSource.OBD)
        BuiltinKind.SHARE_ETA -> setOf(WidgetSource.PHONE)
        BuiltinKind.WEATHER, BuiltinKind.WEATHER_ALERTS, BuiltinKind.FUEL_PRICES, BuiltinKind.SPEED_CAMERAS,
        BuiltinKind.SPEED_LIMIT, BuiltinKind.HOME_WORK -> setOf(WidgetSource.INTERNET)
        BuiltinKind.CAN_MON -> setOf(WidgetSource.ROOT)
        BuiltinKind.DOORS -> if (carDataFrom(obd = false, keys = UnitPlatform.suppliedKeys())) emptySet() else setOf(WidgetSource.ROOT)
        BuiltinKind.CAR_STATUS -> setOf(WidgetSource.CARBOX)
        else -> emptySet()
    }

/**
 * The sources present right now, for the add sheet's chips: an adapter paired
 * or connected, the phone linked, a network with internet (looked at once, as
 * the sheet opens), root, the car box.
 */
@Composable
internal fun rememberAvailableSources(): Set<WidgetSource> {
    val context = LocalContext.current
    val obd by ObdBluetoothManager.connectionState.collectAsState()
    val phone by PhoneLink.state.collectAsState()
    val access = shellAccess()
    val online = remember { hasInternet(context) }
    return buildSet {
        if (obd == ObdConnectionState.CONNECTED || ObdBluetoothManager.adapter.value.canDial) add(WidgetSource.OBD)
        if (phone is PhoneLinkState.Connected) add(WidgetSource.PHONE)
        if (online) add(WidgetSource.INTERNET)
        if (access.root) add(WidgetSource.ROOT)
        if (UnitPlatform.suppliedKeys().contains(K.ODOMETER)) add(WidgetSource.CARBOX)
    }
}

private fun hasInternet(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

internal fun kindIcon(kind: BuiltinKind): ImageVector = when (kind) {
    BuiltinKind.NAVMAP -> Icons.Filled.Navigation
    BuiltinKind.NAVIGATION -> Icons.Filled.Directions
    BuiltinKind.PIP_ANCHOR -> Icons.Filled.PictureInPicture
    BuiltinKind.MAPS_INSIDE -> Icons.Filled.Map
    BuiltinKind.MEDIA -> Icons.Filled.MusicNote
    BuiltinKind.TELEMETRY -> Icons.Filled.Speed
    BuiltinKind.OBD_DTC -> Icons.Filled.Warning
    BuiltinKind.OBD_ALL -> Icons.Filled.Sensors
    BuiltinKind.RANGE -> Icons.Filled.LocalGasStation
    BuiltinKind.DOORS -> Icons.Filled.SensorDoor
    BuiltinKind.CAN_MON -> Icons.Filled.Sensors
    BuiltinKind.SPEED_HUD -> Icons.Filled.Speed
    BuiltinKind.COMPASS -> Icons.Filled.Explore
    BuiltinKind.TRIP -> Icons.Filled.Timeline
    BuiltinKind.GFORCE -> Icons.Filled.Adjust
    BuiltinKind.PARKING -> Icons.Filled.LocalParking
    BuiltinKind.CLOCK -> Icons.Filled.Schedule
    BuiltinKind.WEATHER -> Icons.Filled.WbSunny
    BuiltinKind.CALENDAR -> Icons.Filled.Event
    BuiltinKind.QUICK_DIAL -> Icons.Filled.Call
    BuiltinKind.NOTIFICATIONS -> Icons.Filled.Notifications
    BuiltinKind.AUDIO -> Icons.Filled.VolumeUp
    BuiltinKind.FILTER_CARE -> Icons.Filled.FilterAlt
    BuiltinKind.WARMUP -> Icons.Filled.Thermostat
    BuiltinKind.BATTERY -> Icons.Filled.BatteryChargingFull
    BuiltinKind.MY_CAR -> Icons.Filled.CarRepair
    BuiltinKind.CAR_STATUS -> Icons.Filled.DirectionsCarFilled
    BuiltinKind.TYRES -> Icons.Filled.TireRepair
    BuiltinKind.ECO_DRIVE -> Icons.Filled.Eco
    BuiltinKind.BREAK_TIMER -> Icons.Filled.Coffee
    BuiltinKind.FUEL_TO_DEST -> Icons.Filled.EvStation
    BuiltinKind.SERVICE -> Icons.Filled.Build
    BuiltinKind.FUEL_PRICES -> Icons.Filled.LocalGasStation
    BuiltinKind.DASH_BAR -> Icons.Filled.Dashboard
    BuiltinKind.HEAD_UNIT -> Icons.Filled.Memory
    BuiltinKind.GEAR -> Icons.Filled.SettingsInputComponent
    BuiltinKind.SCREEN_LIGHT -> Icons.Filled.Brightness6
    BuiltinKind.RADIOS -> Icons.Filled.Wifi
    BuiltinKind.UNIT_LIGHT -> Icons.Filled.Brightness7
    BuiltinKind.SPEED_CAMERAS -> Icons.Filled.CameraAlt
    BuiltinKind.RADIO_PRESETS -> Icons.Filled.Radio
    BuiltinKind.HOME_WORK -> Icons.Filled.Home
    BuiltinKind.SHARE_ETA -> Icons.Filled.Send
    BuiltinKind.VOICE_NOTES -> Icons.Filled.Mic
    BuiltinKind.QUICK_SWITCHES -> Icons.Filled.ToggleOn
    BuiltinKind.GPS_STATUS -> Icons.Filled.GpsFixed
    BuiltinKind.PERF_TIMER -> Icons.Filled.Timer
    BuiltinKind.WIFI_NETWORKS -> Icons.Filled.NetworkWifi
    BuiltinKind.BT_DEVICES -> Icons.Filled.BluetoothConnected
    BuiltinKind.SPEED_LIMIT -> Icons.Filled.Speed
    BuiltinKind.PARKING_TIMER -> Icons.Filled.LocalParking
    BuiltinKind.FUEL_LOG -> Icons.Filled.LocalGasStation
    BuiltinKind.WEATHER_ALERTS -> Icons.Filled.Warning
    BuiltinKind.ENGINE_TEMPS -> Icons.Filled.Thermostat
    BuiltinKind.COMMUTE -> Icons.Filled.Commute
    BuiltinKind.LPG_TANK -> Icons.Filled.PropaneTank
    BuiltinKind.MY_READINGS -> Icons.Filled.Calculate
    BuiltinKind.DRIVE_HISTORY -> Icons.Filled.History
    BuiltinKind.NOW -> Icons.Filled.Layers
    BuiltinKind.INDICATORS -> Icons.Filled.Lightbulb
}
