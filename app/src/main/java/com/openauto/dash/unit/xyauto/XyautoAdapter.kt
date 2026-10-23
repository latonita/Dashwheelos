package com.openauto.dash.unit.xyauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.Cap
import com.openauto.dash.CapClaim
import com.openauto.dash.CarSink
import com.openauto.dash.IgnitionSource
import com.openauto.dash.SignalCatalogue
import com.openauto.dash.UnitAdapter
import com.openauto.dash.UnitCaps
import com.openauto.dash.UnitPlatform
import com.openauto.dash.VehicleBusSource
import com.openauto.dash.unit.AccBroadcastIgnition
import com.openauto.dash.unit.KeyBroadcastSource
import com.openauto.unitproto.UnitFamily
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
 * So the adapter is transport only: it takes the two cheap values as they
 * come, switches the mirror on and hands the frames' bytes on. What those
 * bytes mean depends on the canbox behind the unit, not on the unit, so the
 * adapter knows nothing about them.
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
        }
    })

    override val catalogue = SignalCatalogue(
        broadcasts = XyautoProtocol.BROADCASTS,
        propPrefixes = setOf("ro.product.xy.", "persist.sys.xy.", "persist.vendor.xy.", "sys.xy."),
        settingsSystem = true
    )

    override val vendorPackages: Set<String> = XyautoProtocol.PACKAGES
}

/** The firmware's broadcasts, received and handed on: no byte of a frame is interpreted here. */
private class XyautoBus(private val app: Context) : VehicleBusSource {

    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening

    private var sink: CarSink? = null
    private var registered = false

    override fun start(context: Context, sink: CarSink) {
        this.sink = sink
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
            val s = sink ?: return
            when (intent.action) {
                XyautoProtocol.ACTION_RX -> frame(s, intent.getByteArrayExtra(XyautoProtocol.EXTRA_RX))
                // The two the stock plugin decodes for us, whatever the car.
                XyautoProtocol.ACTION_SPEED -> speed(s, intent.getIntExtra(XyautoProtocol.EXTRA_CANBUS_DATA, -1))
                XyautoProtocol.ACTION_RPM -> rpm(s, intent.getIntExtra(XyautoProtocol.EXTRA_CANBUS_DATA, -1))
                XyautoProtocol.ACTION_CONNECTION ->
                    _listening.value = intent.getBooleanExtra(XyautoProtocol.EXTRA_CONNECTION, false)
            }
        }
    }

    private fun frame(s: CarSink, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        _listening.value = true
        UnitPlatform.markUsed(Cap.VEHICLE_BUS)
        // The Signal Finder gets every frame as it came, so a value nothing
        // decodes yet can still be learned by demonstration.
        s.raw("can:rx", bytes.joinToString("") { "%02X".format(it) })
    }

    private fun speed(s: CarSink, kmh: Int) {
        if (kmh !in 0..400) return
        s.speedKmh(kmh.toFloat())
    }

    private fun rpm(s: CarSink, rpm: Int) {
        if (rpm !in 0..12_000) return
        s.rpm(rpm)
    }

    private companion object {
        const val TAG = "XyautoBus"
    }
}
