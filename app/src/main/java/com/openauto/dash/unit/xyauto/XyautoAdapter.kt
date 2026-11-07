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
import com.openauto.dash.StockRadio
import com.openauto.dash.UnitAdapter
import com.openauto.dash.UnitCaps
import com.openauto.dash.UnitPlatform
import com.openauto.dash.VehicleBusSource
import com.openauto.dash.XyFmRadio
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
    private val hasRadio = UnitPlatform.hasPackage(app, XyFmRadio.PACKAGE)

    override val family = UnitFamily.XYAUTO
    override val ignition: IgnitionSource = AccBroadcastIgnition
    override val bus: VehicleBusSource? = if (hasCanStack) XyautoBus(app) else null
    override val keys = KeyBroadcastSource
    override val phone = null
    override val media = null
    override val radio: StockRadio? = if (hasRadio) XyFmRadio else null
    override val volume = null
    override val launcher = null
    override val popups = null
    override val survival = null
    override val camera = null

    override val caps: UnitCaps = UnitCaps(buildList {
        add(CapClaim(Cap.IGNITION))
        add(CapClaim(Cap.UNIT_KEYS))
        if (hasRadio) add(CapClaim(Cap.MEDIA_BRIDGE))
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

    override val vendorPackages: Set<String> = XyautoProtocol.PACKAGES + XyFmRadio.PACKAGE

    override val ignitionOnBus: Boolean get() = hasCanStack
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
     * The H1N4JPF20L box (telemetry v2) sends its FAST frame (0xB3) every 100 ms whatever the car does, even when
     * every field is a sentinel, and only while the ignition is on (its whole block is gated on ACC). A second and
     * a half without any frame is therefore a box that is off or gone (the spec's 0.5 s, with room for a late
     * broadcast): the source goes down and what it said is withdrawn.
     */
    override val silenceMs: Long = 1_500L

    @Volatile private var publisher: XyautoBusPublisher? = null
    @Volatile private var raw: ((String, String) -> Unit)? = null
    private var registered = false

    /** The Signal Finder or the CAN monitor is open: every frame's bytes are wanted as text. */
    @Volatile private var sniffing = false
    /** The learned signals' keys: those frames' bytes are wanted as text for good. */
    @Volatile private var watched: Set<String> = emptySet()

    override fun sniff(on: Boolean) { sniffing = on }
    override fun watch(keys: Set<String>) { watched = keys }

    // The frames arrive about six a second: decoded and published on a thread of their own, off the UI's.
    private val worker by lazy { android.os.HandlerThread("xyauto-bus").apply { start() } }

    // Reads the stream as a stock JPF2 box until the box names its firmware
    // (0xF0), then as whatever the registry says that is.
    private val pipeline = CanboxPipeline()

    override fun start(context: Context, publisher: Publisher, raw: (key: String, value: String) -> Unit) {
        this.publisher = XyautoBusPublisher(publisher, log = { Log.i(TAG, it) })
        this.raw = raw
        if (!registered) {
            val filter = IntentFilter().apply { XyautoProtocol.BROADCASTS.forEach(::addAction) }
            app.registerReceiver(receiver, filter, null, android.os.Handler(worker.looper), Context.RECEIVER_EXPORTED)
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
        // nothing decodes yet can still be learned by demonstration. The text is
        // only made while the finder is open or a learned signal reads this
        // frame; an empty value just says the box spoke.
        val key = "can:${decoded.name}"
        raw?.invoke(key, if (sniffing || key in watched) hexOf(decoded.frame.payload) else "")
        p.apply(decoded.values)
    }

    private companion object {
        const val TAG = "XyautoBus"
    }
}

private val HEX = "0123456789ABCDEF".toCharArray()

/** Upper-case hex of [bytes], two digits each, without the formatter. */
internal fun hexOf(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}
