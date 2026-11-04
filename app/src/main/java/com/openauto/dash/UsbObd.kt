package com.openauto.dash

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.coroutines.resume

/** A USB serial device plugged into the unit that may be the OBD adapter. */
data class UsbObdAdapter(
    /** Vendor and product id ("0403:6001"): what is saved, since the same cable comes back under another device path. */
    val key: String,
    val label: String,
    internal val device: UsbDevice
)

/**
 * OBD adapters on a USB cable: ELM327 cables (FTDI, CH340, PL2303, CP210x)
 * and adapters such as the OBDX Pro VT that show up as a plain USB serial
 * port. Some head units (Joying) refuse Bluetooth devices that aren't their
 * own, and a cable doesn't share a radio with the phone either.
 *
 * Only a device the driver let Dashwheel use (Android's own dialog, when it
 * is plugged in or on Connect) or saved as the adapter is ever opened:
 * opening one takes it from its kernel driver, and a head unit can have
 * serial chips of its own on the same bus.
 *
 * Gives [ObdBluetoothManager] the same byte streams a Bluetooth socket would.
 */
internal object UsbObd {
    private const val TAG = "Obd"
    private const val PREFS = "obd_prefs"
    private const val KEY_DEVICE = "obd_usb"
    /**
     * The saved device answered as an ELM327, or the driver picked it: only
     * then does Automatic take it. A head unit's own serial devices (a TPMS
     * receiver on a CH340, a GPS, a DVR) use the same chips as OBD cables.
     */
    private const val KEY_OK = "obd_usb_ok"
    private const val KEY_BAUD = "obd_usb_baud"
    private const val ACTION_PERMISSION = "com.openauto.dash.USB_OBD_PERMISSION"

    /**
     * The speeds ELM327 adapters come set to: 38400 for most cables, 115200
     * for STN chips (OBDLink, OBDX), then the rarer ones. A virtual serial
     * port (CDC) ignores it and answers on the first.
     */
    private val BAUDS = listOf(38_400, 115_200, 500_000, 9_600, 230_400, 57_600)
    private const val PROBE_MS = 1_200L
    private const val PERMISSION_TIMEOUT_MS = 60_000L

    private fun manager(context: Context): UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** Every serial device plugged in, whether or not Dashwheel may use it yet. */
    fun attached(context: Context): List<UsbObdAdapter> {
        val manager = manager(context) ?: return emptyList()
        val prober = UsbSerialProber.getDefaultProber()
        return runCatching { manager.deviceList.values.toList() }.getOrDefault(emptyList())
            .mapNotNull { device -> prober.probeDevice(device)?.let { adapterOf(it) } }
            .sortedBy { it.label.lowercase() }
    }

    private fun adapterOf(driver: UsbSerialDriver): UsbObdAdapter {
        val device = driver.device
        val chip = driver.javaClass.simpleName.removeSuffix("SerialDriver")
        val name = runCatching { device.productName }.getOrNull()?.trim().orEmpty()
        return UsbObdAdapter(keyOf(device), name.ifEmpty { "USB $chip" }, device)
    }

    fun keyOf(device: UsbDevice): String = "%04x:%04x".format(Locale.US, device.vendorId, device.productId)

    fun permitted(context: Context, adapter: UsbObdAdapter): Boolean =
        runCatching { manager(context)?.hasPermission(adapter.device) == true }.getOrDefault(false)

    fun savedKey(context: Context): String? = prefs(context).getString(KEY_DEVICE, null)

    /** [key] is the adapter: it answered as one, or the driver picked it. */
    fun save(context: Context, key: String) {
        val prefs = prefs(context)
        val edit = prefs.edit().putString(KEY_DEVICE, key).putBoolean(KEY_OK, true)
        // Another adapter may run at another speed.
        if (key != prefs.getString(KEY_DEVICE, null)) edit.remove(KEY_BAUD)
        edit.apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_DEVICE).remove(KEY_BAUD).remove(KEY_OK).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The adapter to use with Automatic: the saved one, plugged in, and only
     * once it answered as an adapter or the driver picked it. Automatic never
     * tries another serial device: opening it takes it away from the app that
     * uses it (the unit's TPMS receiver lost its tyres that way). Saved by an
     * older version without that proof, it isn't taken either.
     */
    fun chosen(context: Context): UsbObdAdapter? {
        val prefs = prefs(context)
        val saved = prefs.getString(KEY_DEVICE, null) ?: return null
        if (!prefs.getBoolean(KEY_OK, false)) return null
        return attached(context).firstOrNull { it.key == saved }
    }

    /** The adapter to use with USB chosen in Settings: as [chosen], else the saved one, one Dashwheel may use, or the first plugged in. */
    fun pick(context: Context, anyDevice: Boolean): UsbObdAdapter? {
        chosen(context)?.let { return it }
        if (!anyDevice) return null
        val all = attached(context)
        val saved = savedKey(context)
        return all.firstOrNull { it.key == saved } ?: all.firstOrNull { permitted(context, it) } ?: all.firstOrNull()
    }

    /** Android's dialog asking to let Dashwheel use [adapter]; true once allowed. */
    suspend fun allow(context: Context, adapter: UsbObdAdapter): Boolean {
        val manager = manager(context) ?: return false
        if (manager.hasPermission(adapter.device)) return true
        val app = context.applicationContext
        return withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        runCatching { app.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
                ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
                // Mutable: Android adds whether it was allowed. Addressed to this app only.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val intent = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
                manager.requestPermission(adapter.device, intent)
            }
        } ?: false
    }

    sealed class Opened {
        class Streams(val input: InputStream, val output: OutputStream) : Opened()
        /** Android wouldn't open it (unplugged meanwhile, or taken). */
        data object Failed : Opened()
        /** Opened, but nothing answered like an ELM327 at any speed. */
        data object Silent : Opened()
    }

    /**
     * Opens [adapter] (allowed already) and finds the speed it talks at: the
     * one that worked last time first, then [BAUDS].
     */
    fun open(context: Context, adapter: UsbObdAdapter): Opened {
        val manager = manager(context) ?: return Opened.Failed
        val driver = UsbSerialProber.getDefaultProber().probeDevice(adapter.device) ?: return Opened.Failed
        val port = driver.ports.firstOrNull() ?: return Opened.Failed
        val connection = runCatching { manager.openDevice(adapter.device) }.getOrNull() ?: return Opened.Failed
        try {
            port.open(connection)
        } catch (e: IOException) {
            Log.w(TAG, "USB ${adapter.key}: ${e.message}")
            runCatching { connection.close() }
            return Opened.Failed
        }
        val link = PortLink(port)
        val saved = prefs(context).getInt(KEY_BAUD, 0)
        val order = listOf(saved).filter { it > 0 } + BAUDS.filter { it != saved }
        try {
            for (baud in order) {
                val answers = try {
                    answersAt(port, baud)
                } catch (e: UnsupportedOperationException) {
                    false // a speed this chip can't run at
                } catch (e: IllegalArgumentException) {
                    false
                }
                if (!answers) continue
                Log.i(TAG, "USB ${adapter.key} (${adapter.label}) answers at $baud baud")
                if (baud != saved) prefs(context).edit().putInt(KEY_BAUD, baud).apply()
                link.start()
                return Opened.Streams(link.input, link.output)
            }
        } catch (e: IOException) {
            // Unplugged while it was tried.
            Log.w(TAG, "USB ${adapter.key}: ${e.message}")
            link.close()
            return Opened.Failed
        }
        Log.w(TAG, "USB ${adapter.key} (${adapter.label}): no ELM327 answer at any speed")
        link.close()
        return Opened.Silent
    }

    /** Sets [baud] and asks "ATI": an ELM327 answers in plain text ending with its ">" prompt. */
    private fun answersAt(port: UsbSerialPort, baud: Int): Boolean {
        port.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // Some adapters only talk once the line says a program is there.
        runCatching { port.setDTR(true) }
        runCatching { port.setRTS(true) }
        val buffer = ByteArray(READ_CHUNK)
        // An adapter asleep wakes on the first character and drops it.
        port.write("\r".toByteArray(), WRITE_TIMEOUT_MS)
        drain(port, buffer, 300)
        port.write("ATI\r".toByteArray(), WRITE_TIMEOUT_MS)
        val reply = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + PROBE_MS
        while (SystemClock.elapsedRealtime() < deadline && !reply.contains(">")) {
            val n = port.read(buffer, READ_POLL_MS)
            for (i in 0 until n) reply.append((buffer[i].toInt() and 0xFF).toChar())
        }
        return looksLikeElm(reply.toString())
    }

    private fun drain(port: UsbSerialPort, buffer: ByteArray, ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) port.read(buffer, READ_POLL_MS)
    }

    /** USB reads are whole packets: a buffer this size never cuts one. */
    private const val READ_CHUNK = 4096
    private const val READ_POLL_MS = 50
    private const val WRITE_TIMEOUT_MS = 1_000

    /**
     * The port as the streams [ObdBluetoothManager] reads and writes. One reader thread blocks on
     * `port.read(buffer, 0)` and fills a buffer the streams read from.
     *
     * A read with a timeout goes through `bulkTransfer`, which on this unit (API 27) lost a whole USB packet when
     * the timeout ran out as the packet came in: a long ISO-TP reply lost its first 62 characters, and a short
     * "OK" went missing. A read without a timeout goes through `UsbRequest` and loses nothing.
     */
    private class PortLink(private val port: UsbSerialPort) {
        private val lock = Object()
        private var data = ByteArray(READ_CHUNK)
        private var start = 0
        private var end = 0
        @Volatile private var closed = false
        @Volatile private var failure: IOException? = null
        private var reader: Thread? = null

        /** Starts the reader. Before this, the speed probe reads the port directly. */
        fun start() {
            if (reader != null) return
            reader = Thread({
                val buffer = ByteArray(READ_CHUNK)
                while (!closed) {
                    val n = try {
                        port.read(buffer, 0)
                    } catch (e: IOException) {
                        if (!closed) failure = e
                        break
                    } catch (e: RuntimeException) {
                        if (!closed) failure = IOException(e)
                        break
                    }
                    if (n > 0) synchronized(lock) {
                        if (end + n > data.size) {
                            // Compact, then grow if still short.
                            val keep = end - start
                            val grown = if (keep + n > data.size) ByteArray(maxOf(data.size * 2, keep + n)) else data
                            System.arraycopy(data, start, grown, 0, keep)
                            data = grown; start = 0; end = keep
                        }
                        System.arraycopy(buffer, 0, data, end, n)
                        end += n
                        lock.notifyAll()
                    }
                }
                synchronized(lock) { lock.notifyAll() }
            }, "UsbObd-reader").apply { isDaemon = true; start() }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { port.close() }
            synchronized(lock) { lock.notifyAll() }
        }

        /** Bytes waiting, after waiting up to [timeoutMs] for some. */
        private fun fill(timeoutMs: Long): Int = synchronized(lock) {
            if (start == end && !closed && failure == null && timeoutMs > 0) lock.wait(timeoutMs)
            if (start < end) return end - start
            failure?.let { throw it }
            if (closed) throw IOException("USB link closed")
            0
        }

        val input = object : InputStream() {
            override fun available(): Int = fill(0)

            override fun read(): Int {
                while (true) synchronized(lock) {
                    if (fill(BLOCKING_POLL_MS) > 0) return data[start++].toInt() and 0xFF
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (true) synchronized(lock) {
                    if (fill(BLOCKING_POLL_MS) > 0) {
                        val n = minOf(len, end - start)
                        System.arraycopy(data, start, b, off, n)
                        start += n
                        return n
                    }
                }
            }

            override fun close() = this@PortLink.close()
        }

        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) throw IOException("USB link closed")
                port.write(if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len), WRITE_TIMEOUT_MS)
            }

            override fun close() = this@PortLink.close()
        }

        private companion object {
            const val BLOCKING_POLL_MS = 200L
        }
    }
}

/**
 * Whether [reply] to "ATI" came from an ELM327 talking at the speed set:
 * readable text ending with its ">" prompt. At the wrong speed the bytes
 * come out as noise, and a modem answers without the prompt.
 */
internal fun looksLikeElm(reply: String): Boolean {
    if (!reply.contains('>')) return false
    val printable = reply.count { it == '\r' || it == '\n' || it in ' '..'~' }
    return printable >= reply.length * 0.9
}
