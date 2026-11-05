package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.openauto.dash.unit.GenericAdapter
import com.openauto.dash.unit.PlainAdapters
import com.openauto.dash.unit.QfAdapter
import com.openauto.dash.unit.fyt.FytAdapter
import com.openauto.dash.unit.mtc.MtcAdapter
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.unit.xyauto.XyautoAdapter
import com.openauto.unitproto.Detection
import com.openauto.unitproto.UnitDetector
import com.openauto.unitproto.UnitFamily
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Which head unit firmware this is, found out once at start ([init]), and the
 * adapter that speaks to it ([adapter]). The driver can name the family in
 * Settings when the detector got it wrong ([setOverride]); the choice takes
 * effect at the next start ([restart]).
 *
 * What the adapter's pieces actually delivered this run is [used], for the
 * Settings card and the compatibility report: the platform's own pieces mark themselves
 * ([markUsed]), and a kind of car data is there while the vehicle bus has its key ([Cap.key]).
 */
object UnitPlatform {
    private const val TAG = "UnitPlatform"
    private const val PREFS = "unit_platform"
    private const val KEY_OVERRIDE = "override"

    @Volatile private var _detected: Detection? = null
    /** The detector's own answer, override or not. */
    val detected: Detection get() = _detected ?: Detection(UnitFamily.UNKNOWN, 0, emptyList())

    private val _override = MutableStateFlow<UnitFamily?>(null)
    /** The family the driver named in Settings, or null for the detector's. */
    val override: StateFlow<UnitFamily?> = _override.asStateFlow()

    private val _family = MutableStateFlow(UnitFamily.UNKNOWN)
    /** The family in force: the override, else the detected one. */
    val family: StateFlow<UnitFamily> = _family.asStateFlow()

    @Volatile private var _adapter: UnitAdapter? = null
    /** The adapter for [family]; the empty one until [init] ran (nothing then claims anything). */
    val adapter: UnitAdapter get() = _adapter ?: GenericAdapter.EMPTY

    private val marked = MutableStateFlow<Set<Cap>>(emptySet())
    private val dataCaps = Cap.entries.filter { it.key != null }

    /** The capabilities that delivered something: the ones marked this run, and the kinds of car data the bus has now. */
    fun usedNow(): Set<Cap> = marked.value + dataCaps.filter { Vehicle.bus.has(it.key!!).value }

    /** [usedNow] as it changes. */
    val used: Flow<Set<Cap>> by lazy {
        combine(listOf<Flow<Any?>>(marked) + dataCaps.map { Vehicle.bus.has(it.key!!) }) { usedNow() }.distinctUntilChanged()
    }

    /**
     * Whether this unit can give [cap]'s data: its firmware claims it, or some source says it now (a unit bus, the
     * car app). For what is offered before any data came (the tiles to add, the first layout), where "has data now" would
     * leave a parked car's tiles out.
     */
    fun delivers(cap: Cap): Boolean = adapter.caps.has(cap) || cap.key?.let { Vehicle.bus.has(it).value } == true

    /**
     * The kinds of car data this car can give, whoever supplies them: the keys the firmware claims ([Cap.key]; a
     * claimed speed and revs stand for the revs too) and the keys the bus has now (a canbox, the unit's bus, the car
     * app, the MCU, an adapter). What decides which car-data tiles are offered ([carDataFrom]), so a parked car with
     * the canbox silent still counts.
     */
    fun suppliedKeys(): Set<Key<*>> = buildSet {
        for (cap in dataCaps) if (adapter.caps.has(cap)) {
            add(cap.key!!)
            if (cap == Cap.SPEED_RPM) add(K.RPM)
        }
        for (key in K.all) if (Vehicle.bus.has(key).value) add(key)
    }

    /** [suppliedKeys] as the bus changes (the claims are fixed for the run). */
    val supplied: Flow<Set<Key<*>>> by lazy {
        combine(K.all.map { Vehicle.bus.has(it) }) { suppliedKeys() }.distinctUntilChanged()
    }

    private val packages = ConcurrentHashMap<String, Boolean>()
    @Volatile private var appContext: Context? = null

    /** Found out once; a second call does nothing. */
    @Synchronized
    fun init(context: Context) {
        if (_adapter != null) return
        val app = context.applicationContext
        appContext = app
        val probe = AndroidUnitProbe(app)
        val debugOverride = if (BuildConfig.DEBUG) SysProps.getOrNull(UnitDetector.OVERRIDE_PROP) else null
        val d = UnitDetector.detect(probe, debugOverride)
        _detected = d
        val chosen = UnitFamily.parse(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OVERRIDE, null))
            ?.takeIf { it != UnitFamily.UNKNOWN }
        _override.value = chosen
        val family = chosen ?: d.family
        _family.value = family
        _adapter = when (family) {
            UnitFamily.QF -> QfAdapter(app)
            UnitFamily.FYT -> FytAdapter(app)
            UnitFamily.MTC -> MtcAdapter(app)
            UnitFamily.XYAUTO -> XyautoAdapter(app)
            // Topway, Reglink, Allwinner, NWD and the unrecognised: plain Android plus their few hooks.
            else -> PlainAdapters.forFamily(app, family)
        }
        Log.i(
            TAG,
            "firmware $family (detected ${d.family}, score ${d.score}${if (d.overridden) ", debug override" else ""}" +
                "${if (chosen != null) ", chosen in Settings" else ""}): ${d.evidence.joinToString()}" +
                (if (d.runnersUp.isNotEmpty()) "; also ${d.runnersUp}" else "")
        )
    }

    /** Names the family for the next start (null: back to the detector's). */
    fun setOverride(context: Context, family: UnitFamily?) {
        val value = family?.takeIf { it != UnitFamily.UNKNOWN }
        _override.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (value == null) remove(KEY_OVERRIDE) else putString(KEY_OVERRIDE, value.name)
        }.apply()
    }

    /** Starts the launcher over, so a new override takes effect: the process ends and Android starts Home again. */
    fun restart(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { app.startActivity(intent) }
        exitProcess(0)
    }

    /** A capability delivered something: the card shows it live, the report says it was used. */
    fun markUsed(cap: Cap) {
        if (cap in marked.value) return
        marked.update { it + cap }
    }

    /** Whether [pkg] is installed, asked once per package (the firmware's apps don't come and go). */
    fun hasPackage(context: Context, pkg: String): Boolean =
        packages.getOrPut(pkg) { isPackageInstalled(context.applicationContext.packageManager, pkg) }

    /** The report's lines about the unit's firmware: the family, what pointed at it, what was used. */
    fun reportLines(): List<Pair<String, String>> {
        val d = detected
        val chosen = _override.value
        val family = _family.value
        return listOfNotNull(
            "Firmware" to (family.name + if (chosen != null && chosen != d.family) " (chosen; detected ${d.family})" else ""),
            d.evidence.takeIf { it.isNotEmpty() }?.let { "Evidence" to it.take(5).joinToString(", ") },
            d.runnersUp.takeIf { it.isNotEmpty() }?.let { "Also like" to it.entries.joinToString(", ") { "${it.key} ${it.value}" } },
            "Caps used" to usedNow().sorted().joinToString(", ").ifEmpty { "none" },
            run {
                val access = PrivilegedShell.access.value
                val granted = PrivilegedShell.settingsGranted
                val used = usedNow()
                val rest = adapter.caps.claimed - used
                val locked = rest.filter { adapter.caps.state(it, access, granted, used) == CapState.LOCKED }
                "Caps waiting" to (rest - locked.toSet()).sorted().joinToString(", ").ifEmpty { "none" } +
                    (if (locked.isNotEmpty()) "; locked: ${locked.sorted().joinToString(", ") { "$it (${adapter.caps.need(it)})" }}" else "")
            },
            adapter.vendorPackages.takeIf { it.isNotEmpty() }?.let { pkgs ->
                val app = appContext
                "Vendor apps" to if (app == null) pkgs.joinToString(", ")
                else pkgs.joinToString(", ") { if (hasPackage(app, it)) it else "$it (absent)" }
            }
        )
    }
}

/** Whether [pkg] is installed, through [pm]. */
internal fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean =
    runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
