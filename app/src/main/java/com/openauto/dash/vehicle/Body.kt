package com.openauto.dash.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.openauto.dash.McuReader
import com.openauto.dash.Tyre
import com.openauto.dash.TyrePos
import com.openauto.dash.WheelMaps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * The body of the car as the screens read it: whole values with the old
 * default for "not known" stated here, at the edge, so a reader that wants a
 * Boolean says which way unknown falls (no reverse, no lights) in one place.
 */

/** Reverse gear engaged; unknown reads as not reversing. */
fun reversingNow(): Boolean = Vehicle.bus.now(K.REVERSE) == true

/** [reversingNow] as it changes. */
fun reversingFlow(): Flow<Boolean> = Vehicle.bus.value(K.REVERSE).map { it == true }.distinctUntilChanged()

@Composable
fun rememberReversing(): Boolean {
    val flow = remember { reversingFlow() }
    val on by flow.collectAsState(remember { reversingNow() })
    return on
}

/** The headlights on; unknown reads as off (the light version). */
fun headlightsNow(): Boolean = Vehicle.bus.now(K.HEADLIGHTS) == true

fun headlightsFlow(): Flow<Boolean> = Vehicle.bus.value(K.HEADLIGHTS).map { it == true }.distinctUntilChanged()

@Composable
fun rememberHeadlights(): Boolean {
    val flow = remember { headlightsFlow() }
    val on by flow.collectAsState(remember { headlightsNow() })
    return on
}

/** The ignition as the unit last said it; null where the unit says nothing. */
fun ignitionNow(): Boolean? = Vehicle.bus.now(K.IGNITION)

fun ignitionFlow(): Flow<Boolean?> = Vehicle.bus.value(K.IGNITION)

/** The doors; null until the car says (they are only known from the first change on, on most cars). */
@Composable
fun rememberDoors(): McuReader.DoorState? = rememberVehicle(K.DOORS)

/**
 * The tyres, position by position, as the driver's wheel map for the source that provides them says
 * ([WheelMaps]); empty until a sensor is heard. Raw `K.TYRES` readers bypass the map.
 */
@Composable
fun rememberTyres(): Map<TyrePos, Tyre> {
    val flow = remember { tyresFlow() }
    val tyres by flow.collectAsState(remember { tyresNow() })
    return tyres
}

fun tyresNow(): Map<TyrePos, Tyre> {
    val s = Vehicle.bus.sample(K.TYRES).value ?: return emptyMap()
    return WheelMaps.apply(WheelMaps.maps.value, s.source.id, s.value)
}

fun tyresFlow(): Flow<Map<TyrePos, Tyre>> =
    combine(Vehicle.bus.sample(K.TYRES), WheelMaps.maps) { s, maps ->
        if (s == null) emptyMap() else WheelMaps.apply(maps, s.source.id, s.value)
    }.distinctUntilChanged()
