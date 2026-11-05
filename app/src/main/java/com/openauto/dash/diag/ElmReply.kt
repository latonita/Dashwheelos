package com.openauto.dash.diag

/**
 * What an ELM327/STN prints for one addressed request, turned into bytes. Pure functions, no socket.
 *
 * Shapes handled (one line per frame, `\r` between lines):
 *  - single frames with CAF on: `58 00`
 *  - multi-frame with CAF on and headers off: a length line `00B`, then `0: 58 03 C1 26 20 C1`, `1: 14 20 ...`
 *  - raw ISO-TP frames (as the reference capture shows them): `10 0B 58 03 C1 26 20 C1`, `21 14 20 D5 ...`,
 *    with or without spaces
 *  - headers on (fallback): `785 10 0B 58 03 ...`; frames from other senders are dropped
 *  - several messages in one reply, e.g. `7F 14 78` followed by `54 FF 00`
 */
object ElmReply {
    /** One reassembled message. [complete] is false when the frames after the first never came. */
    class Message(val sender: Int?, val data: ByteArray, val complete: Boolean)

    sealed interface Parsed {
        class Messages(val list: List<Message>) : Parsed
        /** The adapter said nothing useful: NO DATA, UNABLE TO CONNECT. */
        object NoData : Parsed
        /** The adapter reported a fault (`?`, CAN ERROR, BUS BUSY, ...). */
        class Fault(val text: String) : Parsed
    }

    /** What the request came to, once its messages are looked at. */
    sealed interface Outcome {
        class Final(val reply: RawReply) : Outcome
        /** Only `7F sid 78` so far: keep reading. */
        object Pending : Outcome
        object None : Outcome
    }

    private val HEX_LINE = Regex("^[0-9A-F: ]+$")
    private val LENGTH_LINE = Regex("^[0-9A-F]{3}$")
    private val INDEXED = Regex("^([0-9A-F]):\\s*([0-9A-F ]*)$")

    private class Builder(val sender: Int?, val expected: Int, val formatted: Boolean) {
        val bytes = ArrayList<Byte>()
        var nextSeq = 1
        fun done() = bytes.size >= expected
    }

    /**
     * [headersOn]: the lines start with the sender's CAN id (ATH1). [rx]: with headers on, only frames from this
     * sender count (null keeps all).
     */
    fun parse(text: String?, headersOn: Boolean, rx: Int?): Parsed {
        if (text.isNullOrBlank()) return Parsed.NoData
        val lines = text.uppercase().split('\r', '\n').map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }
        val builders = ArrayList<Builder>()
        val open = HashMap<Int?, Builder>()
        var fault: String? = null

        fun start(sender: Int?, expected: Int, formatted: Boolean): Builder =
            Builder(sender, expected, formatted).also { builders += it; open[sender] = it }

        fun addFrame(sender: Int?, b: ByteArray) {
            if (b.isEmpty()) return
            val pci = b[0].toInt() and 0xFF
            when {
                pci >= 0x30 -> // no PCI: a whole CAF-on payload (a response starts at 0x40 or 0x7F)
                    start(sender, b.size, false).also { for (x in b) it.bytes += x }
                pci shr 4 == 0 -> { // single frame
                    val n = pci and 0x0F
                    start(sender, n, false).also { it.bytes += b.drop(1).take(n) }
                }
                pci shr 4 == 1 && b.size >= 2 -> { // first frame
                    val n = ((pci and 0x0F) shl 8) or (b[1].toInt() and 0xFF)
                    start(sender, n, false).also { it.bytes += b.drop(2) }
                }
                pci shr 4 == 2 -> { // consecutive frame
                    val cur = open[sender]
                    if (cur != null && !cur.formatted && !cur.done() && (pci and 0x0F) == cur.nextSeq) {
                        cur.bytes += b.drop(1)
                        cur.nextSeq = (cur.nextSeq + 1) and 0x0F
                    }
                }
                // 3x flow control and the rest: not data
            }
        }

        for (line in lines) {
            if (!HEX_LINE.matches(line)) {
                if (line.contains("?") || line.contains("ERROR") || line.contains("BUS BUSY") || line.contains("BUFFER FULL")) {
                    fault = fault ?: line
                }
                // NO DATA, OK, echoes: ignored; no messages at the end means no data
                continue
            }
            if (headersOn) {
                val (id, body) = splitHeader(line) ?: continue
                if (rx != null && id != rx) continue
                addFrame(id, body)
                continue
            }
            val indexed = INDEXED.matchEntire(line)
            if (indexed != null) {
                val cur = open[null]
                if (cur != null && cur.formatted) {
                    parseBytes(indexed.groupValues[2])?.let { for (x in it) cur.bytes += x }
                }
                continue
            }
            if (LENGTH_LINE.matches(line)) {
                start(null, line.toInt(16), true)
                continue
            }
            parseBytes(line)?.let { addFrame(null, it) }
        }
        val messages = builders.filter { it.bytes.isNotEmpty() }
            .map { Message(it.sender, it.bytes.take(it.expected).toByteArray(), it.done()) }
        return when {
            messages.isNotEmpty() -> Parsed.Messages(messages)
            fault != null -> Parsed.Fault(fault)
            else -> Parsed.NoData
        }
    }

    /** `785 10 0B 58` or spaceless `78510 0B..`: the 3-digit id and the bytes after it. */
    private fun splitHeader(line: String): Pair<Int, ByteArray>? {
        val id: String
        val rest: String
        if (line.contains(' ')) {
            val first = line.substringBefore(' ')
            if (first.length != 3) return null
            id = first
            rest = line.substringAfter(' ')
        } else {
            if (line.length < 5 || line.length % 2 == 0) return null
            id = line.substring(0, 3)
            rest = line.substring(3)
        }
        val bytes = parseBytes(rest) ?: return null
        return id.toInt(16) to bytes
    }

    /** `58 03 C1` or `5803C1` to bytes; null when it is not whole bytes. */
    fun parseBytes(text: String): ByteArray? {
        val clean = text.filter { !it.isWhitespace() }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return runCatching { ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    /** Picks the answer to a request for [service] out of [parsed]. */
    fun outcome(parsed: Parsed, service: Int): Outcome {
        when (parsed) {
            is Parsed.NoData -> return Outcome.None
            is Parsed.Fault -> return Outcome.Final(RawReply.Error(parsed.text))
            is Parsed.Messages -> {
                var pending = false
                for (m in parsed.list) {
                    val d = m.data
                    val b0 = d[0].toInt() and 0xFF
                    if (b0 == 0x7F && d.size >= 3 && (d[1].toInt() and 0xFF) == service) {
                        val nrc = d[2].toInt() and 0xFF
                        if (nrc == 0x78) pending = true else return Outcome.Final(RawReply.Negative(service, nrc))
                    } else if (b0 == (service + 0x40) and 0xFF) {
                        return Outcome.Final(RawReply.Positive(d, m.complete))
                    }
                }
                return if (pending) Outcome.Pending else Outcome.None
            }
        }
    }
}
