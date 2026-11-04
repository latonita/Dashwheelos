package com.openauto.dash.diag

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** The answer to one addressed request. */
sealed interface RawReply {
    /** [bytes] start at the service byte (`58 03 ...`). [complete] is false when only the first frame of a multi-frame reply arrived. */
    data class Positive(val bytes: ByteArray, val complete: Boolean) : RawReply {
        override fun equals(other: Any?) = other is Positive && complete == other.complete && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode() * 31 + complete.hashCode()
    }
    /** `7F service nrc`, never with nrc 78 (that one is waited out). */
    data class Negative(val service: Int, val nrc: Int) : RawReply
    /** NO DATA or silence: the module is not there (or asleep). */
    object NoResponse : RawReply
    /** Refused by the allow-list, a bad request, or the adapter failed. */
    data class Error(val message: String) : RawReply
}

/** What the adapter turned out to accept (probed once per connection). */
data class AdapterCaps(val stn: Boolean, val canReceiveFilter: Boolean, val flowControlSet: Boolean)

/** The adapter as the transport needs it. Implemented by `ObdBluetoothManager` (under its command mutex); faked in tests. */
interface DiagLink {
    /** Runs [block] holding the adapter, so nothing else talks meanwhile; null when there is no live link. */
    suspend fun <T> exclusive(block: () -> T): T?
    /** One command and its reply text (up to the prompt); null when the adapter said nothing. */
    fun send(command: String, timeoutMs: Long): String?
    /** More output of the request in flight, without writing anything; null when none came. */
    fun readMore(timeoutMs: Long): String?
    /** A Bluetooth or phone-relayed link, slower than a cable. */
    val slowLink: Boolean
}

/** The adapter settings the transport cares about. Pure data, so what to send is a pure function ([transition]). */
internal data class LinkSettings(
    val headers: Boolean,
    val sh: Int,
    val cra: Int?,
    val fcsh: Int?,
    val flow: Boolean,
    val st: Int,
    val fixedTiming: Boolean
) {
    companion object {
        /** The state the gauge polling expects: functional address, no filter, headers off, adaptive timing, ST 32. */
        val DEFAULT = LinkSettings(headers = false, sh = 0x7DF, cra = null, fcsh = null, flow = false, st = 0x32, fixedTiming = false)
    }
}

/**
 * The AT commands that take an adapter from [cur] (null: unknown, send everything) to [want]. What is already in
 * effect is skipped. Commands the adapter refused at the probe ([caps]) are not sent.
 */
internal fun transition(cur: LinkSettings?, want: LinkSettings, caps: AdapterCaps): List<String> {
    val out = ArrayList<String>()
    if (cur?.fixedTiming != want.fixedTiming) out += if (want.fixedTiming) "ATAT0" else "ATAT1"
    if (cur?.st != want.st) out += "ATST%02X".format(want.st)
    if (cur?.headers != want.headers) out += if (want.headers) "ATH1" else "ATH0"
    if (cur?.sh != want.sh) out += "ATSH%03X".format(want.sh)
    if (caps.canReceiveFilter && cur?.cra != want.cra) out += if (want.cra != null) "ATCRA%03X".format(want.cra) else "ATCRA"
    if (caps.flowControlSet) {
        if (want.flow) {
            if (cur == null || !cur.flow || cur.fcsh != want.fcsh) {
                out += "ATFCSH%03X".format(want.fcsh ?: want.sh)
                out += "ATFCSD300000"
            }
            if (cur?.flow != true) out += "ATFCSM1"
        } else if (cur?.flow != false) out += "ATFCSM0"
    }
    return out
}

/**
 * Addressed raw requests to one control unit at a time over the shared adapter: sets the CAN addresses (skipping
 * what is already in effect), reassembles the reply, waits out `7F xx 78`, and puts the generic 7DF state back
 * when the session ends. Every request passes [DiagAllowList] before anything is written.
 *
 * The adapter lock is held per request, not per sweep, so gauge polling can run between modules; whoever takes the
 * lock for the generic state calls [restoreLocked] first, and anything else that changes addresses calls
 * [noteForeign] so the cache cannot go stale.
 */
class DiagTransport(
    private val link: DiagLink,
    private val pendingMs: Long = PENDING_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    @Volatile private var caps: AdapterCaps? = null
    /** What the adapter is set to; null when unknown. Only touched under the adapter lock (or by [reset]). */
    @Volatile private var state: LinkSettings? = null
    /** Whether the adapter may be off the generic state because of us. */
    @Volatile private var touched = false
    /** The adapter is known to be on 11-bit CAN (protocol 6 or 8), not searching. */
    @Volatile private var canProtocol = false
    private val depth = AtomicInteger(0)

    /** Probed once per connection and cached. All false when there is no link or it said nothing. */
    suspend fun caps(): AdapterCaps {
        caps?.let { return it }
        return link.exclusive { probeLocked() } ?: AdapterCaps(stn = false, canReceiveFilter = false, flowControlSet = false)
    }

    /**
     * Sends [payloadHex] ("18 00 FF 00") to the unit at CAN id [tx], which answers on [rx]. Refused requests return
     * [RawReply.Error] without touching the adapter. Outside [withModules] the generic state is put back afterwards.
     */
    suspend fun request(tx: Int, rx: Int, payloadHex: String, timeoutMs: Long = 2000): RawReply {
        val payload = DiagAllowList.parseHex(payloadHex) ?: return RawReply.Error("not hex: $payloadHex")
        DiagAllowList.refusal(payload)?.let { return RawReply.Error(it) }
        if (tx !in 0..0x7FF || rx !in 0..0x7FF) return RawReply.Error("only 11-bit CAN ids")
        return link.exclusive {
            try {
                requestLocked(tx, rx, payload, timeoutMs)
            } finally {
                if (depth.get() == 0) restoreLocked()
            }
        } ?: RawReply.Error("adapter not connected")
    }

    /** A sweep over several modules: the generic state is restored once, when the outermost block ends. */
    suspend fun <T> withModules(block: suspend () -> T): T {
        depth.incrementAndGet()
        try {
            return block()
        } finally {
            if (depth.decrementAndGet() == 0) withContext(NonCancellable) { link.exclusive { restoreLocked() } }
        }
    }

    /** Under the adapter lock: puts the generic 7DF state back if we left it. Cheap when clean. */
    fun restoreLocked() {
        if (!touched) return
        val caps = caps ?: AdapterCaps(false, false, false)
        for (command in transition(state, LinkSettings.DEFAULT, caps)) link.send(command, AT_TIMEOUT_MS)
        state = LinkSettings.DEFAULT
        touched = false
    }

    /** A command from outside this class is about to go out: if it changes addresses or timing, what we cached is stale. */
    fun noteForeign(command: String) {
        val c = command.trim().uppercase()
        if (!c.startsWith("AT")) return
        if (c == "ATD" || PROTOCOL_RESETS.any { c.startsWith(it) }) canProtocol = false
        if (HARMLESS.any { c.startsWith(it) }) return
        state = null
    }

    /** The link is gone or replaced: forget the capabilities and the cache. */
    fun reset() {
        caps = null
        state = null
        touched = false
        canProtocol = false
    }

    /**
     * Puts the adapter on 11-bit CAN before anything addressed goes out. In automatic mode (ATSP0) an adapter that
     * has not found the protocol yet starts a search on each request, which outlasts the request's timeout
     * ("SEARCHING...", then "STOPPED"), and an STN refuses ATCRA until a CAN protocol is set. Every module this
     * transport talks to is on 11-bit CAN (the request takes only 11-bit ids), so 6 (500 kbit/s) is set unless
     * the adapter already sits on 6 or 8. True when the adapter is on 11-bit CAN.
     */
    private fun ensureCanLocked(): Boolean {
        if (canProtocol) return true
        val dpn = link.send("ATDPN", AT_TIMEOUT_MS)?.uppercase()?.replace(">", "")?.trim()?.removePrefix("A")
        if (dpn == "6" || dpn == "8") {
            canProtocol = true
            return true
        }
        val reply = link.send("ATSP6", AT_TIMEOUT_MS)
        canProtocol = reply != null && reply.uppercase().contains("OK")
        state = null
        return canProtocol
    }

    private fun probeLocked(): AdapterCaps {
        caps?.let { return it }
        // The receive filter is only accepted once a CAN protocol is set.
        ensureCanLocked()
        fun ok(reply: String?) = reply != null && reply.uppercase().contains("OK")
        val sti = link.send("STI", AT_TIMEOUT_MS)
        val stn = sti != null && !sti.contains('?') && sti.isNotBlank()
        val cra = ok(link.send("ATCRA7E8", AT_TIMEOUT_MS))
        val fcsh = ok(link.send("ATFCSH7E0", AT_TIMEOUT_MS))
        val fcsd = fcsh && ok(link.send("ATFCSD300000", AT_TIMEOUT_MS))
        val fcsm = fcsd && ok(link.send("ATFCSM1", AT_TIMEOUT_MS))
        val silent = sti == null && !cra && !fcsh
        // Put it back whatever it took.
        if (fcsm || fcsd) link.send("ATFCSM0", AT_TIMEOUT_MS)
        if (cra) link.send("ATCRA", AT_TIMEOUT_MS)
        val found = AdapterCaps(stn = stn, canReceiveFilter = cra, flowControlSet = fcsm)
        state = null
        if (!silent) caps = found
        return found
    }

    private fun requestLocked(tx: Int, rx: Int, payload: ByteArray, timeoutMs: Long): RawReply {
        if (!ensureCanLocked()) return RawReply.Error("adapter would not take CAN protocol 6")
        val caps = probeLocked()
        val st = if (link.slowLink && !caps.stn) ST_SLOW else ST_FAST
        val want = LinkSettings(
            headers = !caps.canReceiveFilter,
            sh = tx,
            cra = if (caps.canReceiveFilter) rx else null,
            fcsh = if (caps.flowControlSet) tx else null,
            flow = caps.flowControlSet,
            st = st,
            fixedTiming = true
        )
        touched = true
        for (command in transition(state, want, caps)) {
            val reply = link.send(command, AT_TIMEOUT_MS)
            if (reply == null || reply.contains('?')) {
                state = null
                return RawReply.Error(if (reply == null) "adapter silent on $command" else "adapter refused $command")
            }
        }
        state = want

        val service = payload[0].toInt() and 0xFF
        val readMs = if (link.slowLink && !caps.stn) timeoutMs + SLOW_EXTRA_MS else timeoutMs
        val hex = payload.joinToString("") { "%02X".format(it) }
        var text = link.send(hex, readMs) ?: return RawReply.NoResponse
        var outcome = ElmReply.outcome(ElmReply.parse(text, want.headers, rx), service)
        if (outcome == ElmReply.Outcome.Pending) {
            val deadline = clock() + pendingMs
            while (outcome == ElmReply.Outcome.Pending) {
                val left = deadline - clock()
                if (left <= 0) return RawReply.Error("module kept answering 'response pending'")
                val more = link.readMore(minOf(left, 1000))
                if (!more.isNullOrBlank()) {
                    text = text + "\r" + more
                    outcome = ElmReply.outcome(ElmReply.parse(text, want.headers, rx), service)
                }
            }
        }
        return when (outcome) {
            is ElmReply.Outcome.Final -> outcome.reply
            else -> RawReply.NoResponse
        }
    }

    private companion object {
        const val PENDING_MS = 5_000L
        const val AT_TIMEOUT_MS = 2_000L
        const val SLOW_EXTRA_MS = 1_000L
        /** ATST units are 4 ms: 256 ms on a cable or an STN, 512 ms on a Bluetooth clone. */
        const val ST_FAST = 0x40
        const val ST_SLOW = 0x80
        /** AT commands that read or set only echo/linefeeds: they leave addresses alone. */
        val HARMLESS = listOf("ATDP", "ATRV", "ATI", "ATE", "ATL")
        /** AT commands after which the protocol may be automatic again (or searching). */
        val PROTOCOL_RESETS = listOf("ATSP", "ATTP", "ATZ", "ATWS", "ATPC")
    }
}
