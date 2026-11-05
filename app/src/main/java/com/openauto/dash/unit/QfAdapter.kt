package com.openauto.dash.unit

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.openauto.dash.CameraControl
import com.openauto.dash.Cap
import com.openauto.dash.CapClaim
import com.openauto.dash.CarBox
import com.openauto.dash.DockShell
import com.openauto.dash.HeadUnitKeys
import com.openauto.dash.HeadUnitMedia
import com.openauto.dash.HeadUnitPhone
import com.openauto.dash.IgnitionEvent
import com.openauto.dash.IgnitionSource
import com.openauto.dash.KeySource
import com.openauto.dash.LauncherTakeover
import com.openauto.dash.McuFeed
import com.openauto.dash.MediaBridge
import com.openauto.dash.Need
import com.openauto.dash.PhoneBridge
import com.openauto.dash.PhoneCall
import com.openauto.dash.QfRadio
import com.openauto.dash.PopupTakeover
import com.openauto.dash.R
import com.openauto.dash.RomPopups
import com.openauto.dash.SignalCatalogue
import com.openauto.dash.SleepSurvival
import com.openauto.dash.StockRadio
import com.openauto.dash.SysProps
import com.openauto.dash.Tyres
import com.openauto.dash.UnitAdapter
import com.openauto.dash.UnitCaps
import com.openauto.dash.UnitKeyPress
import com.openauto.dash.UnitPlatform
import com.openauto.dash.VehicleBusSource
import com.openauto.dash.vehicle.Publisher
import com.openauto.dash.VolumeControl
import com.openauto.dash.VolumeShown
import com.openauto.dash.VolumeSource
import com.openauto.dash.isEmulator
import com.openauto.dash.romMirror
import com.openauto.unitproto.UnitFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * The QF firmware (Hizpo / ROCO K706, QF001 UIS7862 and QF005 UIS8581a): the
 * unit Dashwheel grew up on. Every hook here was worked out from that
 * firmware; the objects that speak them (CarPower's broadcasts, CarBox's car
 * app data, McuFeed's MCU binder, HeadUnitKeys' key service, HeadUnitPhone,
 * HeadUnitMedia, MediaVolume's unit volume, UnitLauncher's SET_LAUNCHER,
 * RomPopups' settings) stay where they are; this adapter names which of them
 * this unit has, by the same checks they used to make themselves.
 */
internal class QfAdapter(context: Context) : UnitAdapter {
    private val app = context.applicationContext

    override val family = UnitFamily.QF

    private fun has(pkg: String) = UnitPlatform.hasPackage(app, pkg)

    /** The framework sets this at boot from the MCU: the one sure sign of the QF firmware's power signals. */
    private val announcesIgnition = SysProps.getOrNull(PROP_ACC) != null
    private val hasVehicleApp = has(VEHICLE_PACKAGE)
    private val hasMcu = SysProps.hasService(MCU_SERVICE)
    private val hasKeyService = SysProps.hasService(KEY_SERVICE)
    private val hasBt = has(BT_PACKAGE)
    private val hasRadio = has(RADIO_PACKAGE)
    private val hasUnitVolume = (Build.BRAND.equals("ROCO", ignoreCase = true) && Build.DEVICE.equals("K706", ignoreCase = true)) ||
        SysProps.getOrNull(QfVolume.TYPE_PROP) != null || hasMcu
    private val hasLauncherChoice = SysProps.getOrNull(QfLauncher.PROP) != null
    private val hasPlatformProp = SysProps.getOrNull(PLATFORM_PROP) != null

    override val ignition: IgnitionSource? = if (announcesIgnition) QfIgnition else null
    override val bus: VehicleBusSource? = if (hasVehicleApp || hasMcu) QfBus else null
    override val keys: KeySource? = if (hasKeyService) QfKeys else null
    override val phone: PhoneBridge? = if (hasBt) QfPhone else null
    override val media: MediaBridge? = if (hasBt || hasRadio) QfMedia else null
    override val radio: StockRadio? = if (hasRadio) QfRadio else null
    override val volume: VolumeControl? = if (hasUnitVolume) QfVolume else null
    override val launcher: LauncherTakeover? = if (hasLauncherChoice) QfLauncher else null
    override val popups: PopupTakeover = QfPopups(app)
    override val survival: SleepSurvival? = null
    override val camera: CameraControl = QfCamera

    override val caps: UnitCaps = UnitCaps(
        buildList {
            if (announcesIgnition) { add(CapClaim(Cap.IGNITION)); add(CapClaim(Cap.SLEEP_SOON)) }
            if (hasVehicleApp) {
                add(CapClaim(Cap.VEHICLE_BUS, Need.SETTINGS))
                listOf(Cap.DOORS, Cap.REVERSE, Cap.STEERING, Cap.RADAR, Cap.CLIMATE, Cap.BODY_TRIP, Cap.OUTSIDE_TEMP)
                    .forEach { add(CapClaim(it, Need.SETTINGS)) }
                add(CapClaim(Cap.POPUP_HIDE, Need.SETTINGS))
                add(CapClaim(Cap.CAMERA_CONTROL, Need.ROOT))
            }
            if (hasMcu) { add(CapClaim(Cap.VOLTS)); add(CapClaim(Cap.HEADLIGHTS)) }
            if (hasKeyService) add(CapClaim(Cap.UNIT_KEYS))
            if (SysProps.getOrNull("persist.sys.maps") != null) add(CapClaim(Cap.KEY_TARGETS, Need.SHELL))
            if (hasBt) { add(CapClaim(Cap.PHONE_BRIDGE)); add(CapClaim(Cap.MEDIA_BRIDGE)) }
            if (hasUnitVolume) add(CapClaim(Cap.VOLUME_UNIT, Need.SHELL))
            if (hasLauncherChoice) add(CapClaim(Cap.LAUNCHER_TAKEOVER))
            if (hasPlatformProp || isEmulator) add(CapClaim(Cap.FREEFORM_BAR, Need.SHELL))
            if (hasPlatformProp) add(CapClaim(Cap.BOOT_LOGO, Need.ROOT))
            if (has(Tyres.TPMS_PACKAGE)) add(CapClaim(Cap.TYRES))
        }
    )

    /** The firmware's broadcasts worth listening to: its car, power and camera news. */
    override val catalogue = SignalCatalogue(broadcasts = BROADCASTS)

    override val vendorPackages: Set<String> = setOf(
        VEHICLE_PACKAGE, BT_PACKAGE, "com.qf.backcar", "com.qf.framework", RADIO_PACKAGE, Tyres.TPMS_PACKAGE, "com.zjinnova.zlink"
    )

    companion object {
        const val PROP_ACC = "sys.qf.is.acc.on"
        const val PLATFORM_PROP = "ro.qf.platform"
        const val MCU_SERVICE = "mcu_service"
        const val KEY_SERVICE = "util_service"
        const val VEHICLE_PACKAGE = "com.qf.vehicle"
        const val BT_PACKAGE = "com.qf.bluetooth"
        const val RADIO_PACKAGE = "com.android.fmradio.ext"

        /** The QF firmware's broadcasts worth hearing in the Signal Finder. */
        val BROADCASTS = setOf(
            "com.qf.action.BACKCAR_START", "com.qf.action.BACKCAR_STOP",
            "com.qf.action.headlight_state", "com.qf.action.ACC_ON", "com.qf.action.ACC_OFF",
            "com.qf.action.READY_GO_SLEEP_PRE", "com.qf.action.VOLUME_CHANGED",
            "com.qf.vehicle.action.swa", "com.qf.vehicle.action.popup_enable",
            "com.qf.action.BT.MUSIC.INFO", "TPMS_APP_STATE_SEND", "com.zjinnova.zlink"
        )
    }
}

/** ACC on when the key turns, ACC off then "ready to sleep" when it's switched off; the property is written first. */
internal object QfIgnition : IgnitionSource {
    private const val ACTION_ACC_ON = "com.qf.action.ACC_ON"
    private const val ACTION_ACC_OFF = "com.qf.action.ACC_OFF"
    private const val ACTION_SLEEP_SOON = "com.qf.action.READY_GO_SLEEP_PRE"

    override fun read(): Boolean? = when (SysProps.get(QfAdapter.PROP_ACC)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    override val actions: Set<String> = setOf(ACTION_ACC_ON, ACTION_ACC_OFF, ACTION_SLEEP_SOON)

    override fun decode(intent: Intent): IgnitionEvent? = when (intent.action) {
        ACTION_ACC_ON -> IgnitionEvent.ON
        ACTION_ACC_OFF -> IgnitionEvent.OFF
        ACTION_SLEEP_SOON -> IgnitionEvent.SLEEP_SOON
        else -> null
    }
}

/**
 * The car app's shared data ([CarBox]) and the MCU's stream ([McuFeed]): each starts only where its hook is. They are
 * sources of their own on the vehicle bus (`qf.car_app`, `qf.mcu`, `qf.broadcast`), so the `unit.bus` publisher is not used here.
 */
internal object QfBus : VehicleBusSource {
    override fun start(context: Context, publisher: Publisher, raw: (String, String) -> Unit) {
        CarBox.start(context)
        McuFeed.start(context)
    }

    override fun stop() = Unit

    override val listening: StateFlow<Boolean> get() = McuFeed.listening
}

/** The firmware's key service ([HeadUnitKeys]), which hands its keys to the wheel map itself. */
internal object QfKeys : KeySource {
    override val vendor = "qf"

    override fun start(context: Context, onKey: (UnitKeyPress) -> Unit) = HeadUnitKeys.start(context)

    override fun stop() = Unit

    override val listening: StateFlow<Boolean> get() = HeadUnitKeys.listeningFlow

    override fun nameRes(code: Int): Int? = HeadUnitKeys.nameRes[code]
}

internal object QfPhone : PhoneBridge {
    override fun start(context: Context) = HeadUnitPhone.start(context)
    override val call: StateFlow<PhoneCall?> get() = HeadUnitPhone.call
    override fun accept() = HeadUnitPhone.accept()
    override fun decline() = HeadUnitPhone.decline()
    override fun hangUp() = HeadUnitPhone.hangUp()
}

internal object QfMedia : MediaBridge {
    override fun start(context: Context) = HeadUnitMedia.start(context)
    override fun isStock(pkg: String?): Boolean = HeadUnitMedia.isStock(pkg)
    override fun source(): String? = HeadUnitMedia.source()
}

/**
 * The QF unit's own volume: 0..[MAX] for the current source, which the
 * framework keeps in a system property and sends to the MCU
 * (android.qf.os.VolumeState). Written through the privileged shell, the
 * same two steps the firmware takes, so the knob carries on from the new level.
 */
internal object QfVolume : VolumeControl {
    private const val TAG = "QfVolume"
    const val MAX = 32
    /** android.qf.mcu.IMcuManager: RPC_SetVolumeMute(boolean) and RPC_SetVolume(byte). */
    private const val TX_SET_MUTE = 6
    private const val TX_SET_VOLUME = 13
    /** Which source the knob currently changes (media_type, radio_type, btcall_type, aux_type). */
    const val TYPE_PROP = "sys.current.vol.type"
    private const val MUTE_PROP = "sys.mute.state"
    /** The level a source starts from before it was ever changed. */
    private const val DEFAULT_PROP = "persist.sys.main_volume"
    private const val DEFAULT = 12

    override val max: Int get() = MAX
    override val need: Need get() = Need.SHELL

    override fun source(): String = SysProps.get(TYPE_PROP).orEmpty()

    /** Where the firmware saves the current source's level (android.qf.os.VolumeManager). */
    private fun levelProp(): String = when (source()) {
        "radio_type" -> "sys.radio.vol"
        "btcall_type" -> "sys.call.vol"
        "aux_type" -> "sys.aux.vol"
        else -> "sys.media.vol"
    }

    private fun savedLevel(): Int =
        (SysProps.get(levelProp())?.toIntOrNull() ?: SysProps.get(DEFAULT_PROP)?.toIntOrNull() ?: DEFAULT).coerceIn(0, MAX)

    override fun read(): VolumeShown = VolumeShown(savedLevel(), MAX, SysProps.get(MUTE_PROP) == "true", VolumeSource.of(source()))

    /** The two steps of the firmware's own VolumeState: save the level and mute state, then tell the MCU. */
    override suspend fun write(context: Context, level: Int, muted: Boolean) {
        val prop = levelProp()
        val wasMuted = SysProps.get(MUTE_PROP) == "true"
        val wasLevel = SysProps.get(prop)?.toIntOrNull()
        val cmds = buildList {
            if (muted != wasMuted) {
                add("setprop $MUTE_PROP $muted")
                add("service call ${QfAdapter.MCU_SERVICE} $TX_SET_MUTE i32 ${if (muted) 1 else 0}")
            }
            if (level != wasLevel) {
                add("setprop $prop $level")
                add("service call ${QfAdapter.MCU_SERVICE} $TX_SET_VOLUME i32 $level")
            }
        }
        if (cmds.isEmpty()) return
        val out = DockShell.shell(context, cmds.joinToString("; "))
        // "service call" answers "Result: Parcel(...)"; anything else is an error message.
        if (!out.contains("Result: Parcel")) throw IllegalStateException(out.trim().lines().firstOrNull().orEmpty())
    }
}

/**
 * The firmware never asks Android which app is Home: every Home it starts is
 * the package in `persist.sys.qf.launcher`, set from its own launcher picker.
 * Its CarSettings service sets that choice for any app sending SET_LAUNCHER
 * with the package name, as the picker does; no root needed.
 */
internal object QfLauncher : LauncherTakeover {
    private const val TAG = "QfLauncher"
    const val PROP = "persist.sys.qf.launcher"
    private const val ACTION = "com.qf.action.SET_LAUNCHER"
    private const val EXTRA_PACKAGE = "packageName"

    override val need: Need get() = Need.NONE

    override fun ownChoice(): String? = SysProps.getOrNull(PROP)

    override suspend fun claim(context: Context): Boolean =
        runCatching { context.sendBroadcast(Intent(ACTION).putExtra(EXTRA_PACKAGE, context.packageName)) }
            .onFailure { Log.w(TAG, "can't ask the unit to start Dashwheel as its launcher", it) }
            .isSuccess

    override suspend fun handTo(context: Context, pkg: String): Boolean =
        runCatching { context.sendBroadcast(Intent(ACTION).putExtra(EXTRA_PACKAGE, pkg)) }
            .onFailure { Log.w(TAG, "can't give the unit's launcher back to $pkg", it) }
            .isSuccess
}

/** Which ROM pop-ups this unit has ([RomPopups] does the hiding, with the car app's global settings). */
internal class QfPopups(private val app: Context) : PopupTakeover {
    override fun canHide(kind: RomPopups.Kind): Boolean = when (kind) {
        RomPopups.Kind.CALL -> UnitPlatform.hasPackage(app, QfAdapter.BT_PACKAGE)
        RomPopups.Kind.DOORS, RomPopups.Kind.RADAR, RomPopups.Kind.AC, RomPopups.Kind.BELT -> UnitPlatform.hasPackage(app, QfAdapter.VEHICLE_PACKAGE)
        RomPopups.Kind.TYRES -> UnitPlatform.hasPackage(app, Tyres.TPMS_PACKAGE)
        RomPopups.Kind.VOLUME -> UnitPlatform.hasPackage(app, FIRMWARE_PACKAGE)
    }

    override fun need(kind: RomPopups.Kind): Need = when (kind) {
        RomPopups.Kind.DOORS, RomPopups.Kind.RADAR, RomPopups.Kind.AC, RomPopups.Kind.BELT -> Need.SETTINGS
        RomPopups.Kind.CALL, RomPopups.Kind.TYRES -> Need.NONE
        RomPopups.Kind.VOLUME -> Need.ROOT
    }

    companion object {
        /** The firmware's own framework app, whose volume bar the Magisk overlay hides. */
        const val FIRMWARE_PACKAGE = "com.qf.framework"
    }
}

/** The ROM's camera app (com.qf.backcar) and its mirror setting, which counts the other way on the Unisoc platforms. */
internal object QfCamera : CameraControl {
    override val romApp: String = "com.qf.backcar"
    override val defaultCameraId: String = "1"
    override fun turn(setting: Int): Int = romMirror(setting, SysProps.get(QfAdapter.PLATFORM_PROP).orEmpty())
}
