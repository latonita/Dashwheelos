package com.openauto.dash.unit.xyauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.Cap
import com.openauto.dash.CapClaim
import com.openauto.dash.IgnitionSource
import com.openauto.dash.SignalCatalogue
import com.openauto.dash.UnitAdapter
import com.openauto.dash.UnitCaps
import com.openauto.dash.UnitPlatform
import com.openauto.dash.VehicleBusSource
import com.openauto.dash.unit.AccBroadcastIgnition
import com.openauto.dash.unit.KeyBroadcastSource
import com.openauto.dash.vehicle.Publisher
import com.openauto.unitproto.UnitFamily
import com.openauto.unitproto.canbox.CanboxPipeline
import com.openauto.unitproto.canbox.CanboxValue
import com.openauto.unitproto.xyauto.XyautoProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * XYAuto units (MediaTek AC8227L / AC8257, the "9320" class).
 *
 * These firmwares own the canbox UART in a framework service no app can open,
 * and hand what they decode to Android as plain broadcasts. Two of those are
 * pre-decoded by the per-brand plugin (speed and revs); everything else the car
 * says is only reachable through the plugin's raw mirror, which repeats every
 * valid frame verbatim - and which is **off until asked for**.
 *
 * So the adapter switches the mirror on, takes the two cheap values as they
 * come and decodes the frames with the canbox's own decoder (unitproto's
 * `canbox` package, picked by the box's version): what the bytes mean depends
 * on the canbox behind the unit, not on the unit. What the values become on
 * the vehicle bus is [XyautoBusPublisher]'s business.
 */
internal class XyautoAdapter(context: Context) : UnitAdapter {

    private val app = context.applicationContext
    private val hasCanStack = XyautoProtocol.PACKAGES.any { UnitPlatform.hasPackage(app, it) }

    override val family = UnitFamily.XYAUTO
    override val ignition: IgnitionSource = AccBroadcastIgnition
    override val bus: VehicleBusSource? = if (hasCanStack) XyautoBus(app) else null
    override val keys = KeyBroadcastSource
    override val phone = null
    override val media = null
    override val volume = null
    override val launcher = null
    override val popups = null
    override val survival = null
    override val camera = null

    override val caps: UnitCaps = UnitCaps(buildList {
        add(CapClaim(Cap.IGNITION))
        add(CapClaim(Cap.UNIT_KEYS))
        if (hasCanStack) {
            add(CapClaim(Cap.VEHICLE_BUS))
            add(CapClaim(Cap.SPEED_RPM))
            add(CapClaim(Cap.DOORS))
            add(CapClaim(Cap.REVERSE))
            add(CapClaim(Cap.HEADLIGHTS))
        }
    })

    override val catalogue = SignalCatalogue(
        broadcasts = XyautoProtocol.BROADCASTS,
        propPrefixes = setOf("ro.product.xy.", "persist.sys.xy.", "persist.vendor.xy.", "sys.xy."),
        settingsSystem = true
    )

    override val vendorPackages: Set<String> = XyautoProtocol.PACKAGES
}

/**
 * The firmware's broadcasts, received and decoded: the frames' bytes go through the canbox pipeline (which
 * decoder reads them is the box's business) and the values to [XyautoBusPublisher]; the Signal Finder gets
 * every frame's payload as it came.
 */
private class XyautoBus(private val app: Context) : VehicleBusSource {

    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening

    /**
     * The H1N4JPF20L box sends its heartbeat (0xB0) once a second and repeats every other frame within about 10 s
     * even when nothing changes, all of it only while the ignition is on (its whole block is gated on ACC). Five
     * seconds without a frame is therefore a box that is off, not a quiet one: the source goes down and what it
     * said is withdrawn.
     */
    override val silenceMs: Long = 5_000L

    private var publisher: XyautoBusPublisher? = null
    private var raw: ((String, String) -> Unit)? = null
    private var registered = false

    // Reads the stream as a stock JPF2 box until the box names its firmware
    // (0xF0), then as whatever the registry says that is.
    private val pipeline = CanboxPipeline()

    override fun start(context: Context, publisher: Publisher, raw: (key: String, value: String) -> Unit) {
        this.publisher = XyautoBusPublisher(publisher)
        this.raw = raw
        if (!registered) {
            val filter = IntentFilter().apply { XyautoProtocol.BROADCASTS.forEach(::addAction) }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        // The mirror is off until asked: without this, rxpduses carries only the
        // frames that FAILED their checksum, and a silent sniffer looks exactly
        // like a silent bus.
        app.sendBroadcast(Intent(XyautoProtocol.ACTION_PRINTDATA).putExtra(XyautoProtocol.EXTRA_IS_START, true))
        Log.i(TAG, "listening for the canbox; raw mirror requested")
    }

    override fun stop() {
        // Deliberately not unregistered: the dashboard is the home app and the
        // car keeps talking. Matches the other adapters.
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = publisher ?: return
            when (intent.action) {
                XyautoProtocol.ACTION_RX -> frame(p, intent.getByteArrayExtra(XyautoProtocol.EXTRA_RX))
                // The two the stock plugin decodes for us, whatever the car.
                XyautoProtocol.ACTION_SPEED -> intent.getIntExtra(XyautoProtocol.EXTRA_CANBUS_DATA, -1)
                    .takeIf { it in 0..400 }?.let { p.apply(CanboxValue.SpeedKmh(it)) }
                XyautoProtocol.ACTION_RPM -> intent.getIntExtra(XyautoProtocol.EXTRA_CANBUS_DATA, -1)
                    .takeIf { it in 0..12_000 }?.let { p.apply(CanboxValue.Rpm(it)) }
                XyautoProtocol.ACTION_CONNECTION ->
                    _listening.value = intent.getBooleanExtra(XyautoProtocol.EXTRA_CONNECTION, false)
            }
        }
    }

    private fun frame(p: XyautoBusPublisher, bytes: ByteArray?) {
        val decoded = pipeline.feed(bytes) ?: return
        _listening.value = true
        UnitPlatform.markUsed(Cap.VEHICLE_BUS)
        // Every frame is offered to the Signal Finder as it came, so a value
        // nothing decodes yet can still be learned by demonstration.
        raw?.invoke("can:${decoded.name}", decoded.frame.payload.joinToString("") { "%02X".format(it) })
        p.apply(decoded.values)
    }

    private companion object {
        const val TAG = "XyautoBus"
    }
}
