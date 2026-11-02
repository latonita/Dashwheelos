package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.openauto.dash.vehicle.Publisher
import com.openauto.dash.vehicle.SourceKind
import com.openauto.unitproto.UnitFamily
import kotlinx.coroutines.flow.StateFlow

/*
 * How Dashwheel speaks to one family of head unit firmware. Each facade the
 * rest of the app already uses (CarPower, CarBox, HeadUnitKeys, MediaVolume,
 * UnitLauncher, RomPopups...) asks [UnitPlatform.adapter] for the piece it
 * needs and does nothing when the piece is null: that is how a feature stays
 * out of the way on a unit whose firmware has no such thing.
 *
 * A piece is non-null only when its firmware hook was actually found on the
 * unit (the property set, the package installed, the binder registered), so
 * a wrong family never starts code the unit can't answer.
 */
interface UnitAdapter {
    val family: UnitFamily
    val caps: UnitCaps
    val ignition: IgnitionSource?
    val bus: VehicleBusSource?
    val keys: KeySource?
    val phone: PhoneBridge?
    val media: MediaBridge?
    /** The unit's FM radio app, for the media tile and the radio presets tile; null where it has none. */
    val radio: StockRadio? get() = null
    val volume: VolumeControl?
    val launcher: LauncherTakeover?
    val popups: PopupTakeover?
    val survival: SleepSurvival?
    val camera: CameraControl?
    /** What the Signal Finder listens to and lists on this family. */
    val catalogue: SignalCatalogue
    /** The firmware's own apps this adapter talks to: the compatibility report lists which are there. */
    val vendorPackages: Set<String>
}

enum class IgnitionEvent { ON, OFF, SLEEP_SOON, WAKE }

/** The ignition as the firmware tells it: a property to read, and broadcasts to follow. */
interface IgnitionSource {
    /** On / off right now, or null when the unit can't say (broadcasts only). */
    fun read(): Boolean?
    /** The broadcast actions to listen for. */
    val actions: Set<String>
    /** What one of those broadcasts means, or null when it is noise. */
    fun decode(intent: Intent): IgnitionEvent?
}

/**
 * The car's data bus of one family, started once ([UnitBus] does). It decodes what its firmware says and puts
 * the keys itself, as the source `unit.bus` ([SourceKind.CANBOX]): [publisher] is that source's handle (what it
 * puts counts as word from the bus, and the speed it puts is already the driver's to correct: see
 * [unitBusSpeed]). The Signal Finder's raw values are no key, so they go to [raw] under their source key
 * ("fyt:7:1031#0"), also as word from the bus.
 *
 * A bus that goes quiet is dropped by [silenceMs], and one whose feed is lost says so by flipping [listening]
 * to false: either way everything it put is withdrawn and the source goes down, back up with its next word.
 */
interface VehicleBusSource {
    fun start(context: Context, publisher: Publisher, raw: (key: String, value: String) -> Unit)
    fun stop()
    val listening: StateFlow<Boolean>
    /**
     * How long this family's bus may say nothing before it counts as gone. Chosen per family from how often it
     * speaks; a family that puts through other sources (the QF firmware's) has no use for it.
     */
    val silenceMs: Long get() = UnitBus.DEFAULT_SILENCE_MS
    /** The Signal Finder's learned keys ([LearnedSignals]): a bus that has to ask for each value follows these for good. */
    fun watch(keys: Set<String>) = Unit
    /** The finder's sniffer opened ([on]) or closed: a bus that has to ask for each value asks for everything meanwhile. */
    fun sniff(on: Boolean) = Unit
}

/** One press of a unit key: the firmware's own code, down or up, with Android's event when the firmware passes one. */
data class UnitKeyPress(val code: Int, val down: Boolean, val event: KeyEvent? = null)

/** The unit's own keys as its firmware hands them out (a binder callback, a vendor broadcast). */
interface KeySource {
    /** Names the code space, so a learned button remembers which firmware's code it is ("qf", "fyt", "mtc"). */
    val vendor: String
    fun start(context: Context, onKey: (UnitKeyPress) -> Unit)
    fun stop()
    val listening: StateFlow<Boolean>
    /** The firmware's own name for a key code, for the learning screen; null for an unnamed one. */
    @StringRes fun nameRes(code: Int): Int?
}

/** Calls on the unit's own Bluetooth phone app. */
interface PhoneBridge {
    fun start(context: Context)
    val call: StateFlow<PhoneCall?>
    fun accept()
    fun decline()
    fun hangUp()
}

/** The unit's own players: Bluetooth music and the radio. */
interface MediaBridge {
    fun start(context: Context)
    /** Whether [pkg] is one of the firmware's own players. */
    fun isStock(pkg: String?): Boolean
    /** The unit's current audio source (a package name), as its framework records it; null when it doesn't. */
    fun source(): String?
}

/** The unit's own volume, which Android's media volume doesn't reach. */
interface VolumeControl {
    val max: Int
    val need: Need
    /** The level the unit has now, or null when it can't be read. */
    fun read(): VolumeShown?
    /** The sound the volume applies to now ("radio_type"...), "" on a unit with one volume for all. */
    fun source(): String
    suspend fun write(context: Context, level: Int, muted: Boolean)
}

/** The firmware's own launcher choice, apart from Android's default Home. */
interface LauncherTakeover {
    val need: Need
    /** The package the firmware starts as Home, or null when it can't be read. */
    fun ownChoice(): String?
    /** Points the firmware's choice at Dashwheel; true when asked (read [ownChoice] later to know if it took). */
    suspend fun claim(context: Context): Boolean
    /** Points the firmware's choice back at [pkg] ([HomeHandBack]); false where it can't. */
    suspend fun handTo(context: Context, pkg: String): Boolean = false
}

/** The firmware's pop-ups Dashwheel can hide to show its own ([RomPopups]). */
interface PopupTakeover {
    /** Whether this unit has the ROM app whose pop-up [kind] would replace. */
    fun canHide(kind: RomPopups.Kind): Boolean
    fun need(kind: RomPopups.Kind): Need
}

/** One thing to do so Dashwheel survives the unit's sleep; [automatic] ones Dashwheel does itself once their need is met. */
data class SurvivalStep(@StringRes val title: Int, @StringRes val detail: Int, val need: Need, val automatic: Boolean)

interface SleepSurvival {
    fun steps(): List<SurvivalStep>
    /** Does the automatic steps whose need [access] meets; true when all of them held. */
    suspend fun apply(context: Context, access: PrivilegedShell.Access): Boolean
    /** Called at every boot ([BootReceiver]): what a reboot undid is put back. */
    fun atBoot(context: Context)
}

/** The firmware's reversing camera app and how it turns the picture. */
interface CameraControl {
    /** The ROM's camera app, which has to let the camera go, or null when there is none to stop. */
    val romApp: String?
    /** The camera id the ROM app opens for reverse. */
    val defaultCameraId: String
    /** The picture's turn (0 as is, 1 left-right, 2 upside down, 3 both) from the ROM's mirror [setting]. */
    fun turn(setting: Int): Int
}

/** What the Signal Finder listens to on a family, beyond what every Android unit has. */
data class SignalCatalogue(
    /** The firmware's broadcasts worth hearing (car, power, camera news). */
    val broadcasts: Set<String> = emptySet(),
    /** Read-only property prefixes still worth listing (the getprop dump otherwise drops ro.*). */
    val propPrefixes: Set<String> = emptySet(),
    /** Whether Settings.System holds car state on this family (MTC's doors). */
    val settingsSystem: Boolean = false,
    val settingsSecure: Boolean = false
) {
    companion object {
        val NONE = SignalCatalogue()
    }
}
