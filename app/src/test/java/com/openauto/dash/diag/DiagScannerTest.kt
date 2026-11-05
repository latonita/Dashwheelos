package com.openauto.dash.diag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An ELM327 with CAN auto-format on, answering per CAN address: [replies] maps (request id, request hex) to the
 * reassembled reply hex, which is printed the way the adapter prints it (one line, or a length line and indexed
 * frames). Anything else is NO DATA; AT commands are OK.
 */
private class CarLink : DiagLink {
    val replies = HashMap<Pair<Int, String>, String>()
    /** Raw adapter text instead of a formatted reply, for refusals. */
    val raw = HashMap<Pair<Int, String>, String>()
    val requests = ArrayList<Pair<Int, String>>()
    private var sh = 0x7DF

    override suspend fun <T> exclusive(block: () -> T): T? = block()

    override fun send(command: String, timeoutMs: Long): String? {
        if (command == "STI") return "?"
        if (command.startsWith("ATSH")) sh = command.removePrefix("ATSH").toInt(16)
        if (command.startsWith("AT")) return "OK"
        requests += sh to command
        raw[sh to command]?.let { return it }
        return replies[sh to command]?.let(::elm) ?: "NO DATA"
    }

    override fun readMore(timeoutMs: Long): String? = null
    override val slowLink = false

    companion object {
        /** How an ELM327 with CAF on and headers off prints [hex]. */
        fun elm(hex: String): String {
            val b = hex.chunked(2)
            if (b.size <= 7) return b.joinToString(" ")
            val lines = ArrayList<String>()
            lines += "%03X".format(b.size)
            lines += "0: " + b.take(6).joinToString(" ")
            b.drop(6).chunked(7).forEachIndexed { i, chunk -> lines += "${(i + 1) and 0xF}: " + chunk.joinToString(" ") }
            return lines.joinToString("\r")
        }
    }
}

private class FakeGeneric(var read: Result<Obd2Read> = Result.success(Obd2Read(emptyList(), emptyList()))) : GenericObd {
    val calls = ArrayList<String>()
    var clearResult: Result<Unit> = Result.success(Unit)
    override suspend fun read(): Result<Obd2Read> { calls += "read"; return read }
    override suspend fun clear(): Result<Unit> { calls += "clear"; return clearResult }
}

class DiagScannerTest {
    private val root = listOf("src/main/assets", "app/src/main/assets").map { File(it) }.first { it.exists() }
    private val loaded = DiagProfiles.loadJeepWk { File(root, it).inputStream() }
    private val jeep = loaded.first
    private val descriptions = loaded.second

    /** raw_dtc_response_hex per module, from the reference capture of this car (extracted/ecus.csv). */
    private val captured = mapOf(
        "ABS" to "5803C12620C11420D50420",
        "CCN" to "5801985120",
        "EOM" to "5800",
        "ESM" to "5801060720",
        "FCMCGW" to "5801A10020",
        "FDCM" to "5801C41520",
        "ORC" to "58029BB820A12D20",
        "PCM" to "5807046020C11420C10320C11420C10320067660213AE0",
        "RADIO" to "5800",
        "SCM" to "5800",
        "TCM" to "5802C11420C10320",
        "WCM" to "5802407727A10227",
    )

    /**
     * The acceptance oracle: extracted/live_dtc_scan.csv (module, code, status, description), plus ORC and TCM,
     * which that file leaves out, decoded from their captured replies (dtc_response_decoded in ecus.csv).
     */
    private val expected = listOf(
        "ABS|U0126|STORED|Lost Communication With Steering Angle Sensor",
        "ABS|U0114|STORED|Lost Communication With Final Drive Control Module (FDCM)",
        "ABS|U1504|STORED|Implausible Message Data Length Received from Steering Angle Sensor",
        "CCN|B1851|STORED|Flipper Glass Switch Stuck",
        "ESM|P0607|STORED|ECU Internal Performance",
        "FCMCGW|B2100|STORED|Ignition Run/Start Input Circuit/Performance",
        "FDCM|U0415|STORED|Implausible Data Received from ABS",
        "PCM|P0460|STORED|Fuel Level Sensor 1 Circuit",
        "PCM|U0114|STORED|Lost Communication With Final Drive Control Module (FDCM)",
        "PCM|U0103|STORED|Lost Communication with Electric Gear Shift Module",
        "PCM|U0114|STORED|Lost Communication With Final Drive Control Module (FDCM)",
        "PCM|U0103|STORED|Lost Communication with Electric Gear Shift Module",
        "PCM|P0676|ACTIVE|Cylinder 6 Glow Plug Circuit",
        "PCM|P213A|ACTIVE|EGR Airflow Throttle Control Circuit 'B' Open",
        "WCM|C0077|STORED|Low Tire Pressure",
        "WCM|B2102|STORED|Ignition Run/Start Input - Circuit High",
    )

    private fun carWithCapture(): CarLink = CarLink().apply {
        for (m in jeep.modules) captured[m.id]?.let { replies[m.tx to m.readRequest] = it }
    }

    private fun scanner(link: CarLink, generic: GenericObd = FakeGeneric()) = DiagScanner(DiagTransport(link, pendingMs = 50), generic)

    @Test
    fun aSweepOfTheCapturedCarGivesTheLiveScan() = runBlocking {
        val scans = scanner(carWithCapture()).sweep(jeep, descriptions).getOrThrow()
        // The 12 modules that answer, in profile order; the 15 probe-only ones are not asked.
        assertEquals(listOf("PCM", "TCM", "ABS", "ESM", "FDCM", "FCMCGW", "WCM", "CCN", "SCM", "RADIO", "ORC", "EOM"), scans.map { it.module.id })
        assertTrue(scans.all { it.outcome == ScanOutcome.OK })
        val oracle = scans.filter { it.module.id != "ORC" && it.module.id != "TCM" }
            .flatMap { s -> s.codes.map { "${s.module.id}|${it.code}|${it.status}|${it.description}" } }
        assertEquals(expected.sorted(), oracle.sorted())
        // Order within a module is the reply's.
        assertEquals(listOf("P0460", "U0114", "U0103", "U0114", "U0103", "P0676", "P213A"), scans.first { it.module.id == "PCM" }.codes.map { it.code })
        val orc = scans.first { it.module.id == "ORC" }
        assertEquals(listOf("B1BB8" to DtcStatus.STORED, "B212D" to DtcStatus.STORED), orc.codes.map { it.code to it.status })
        val tcm = scans.first { it.module.id == "TCM" }
        assertEquals(listOf("U0114" to DtcStatus.STORED, "U0103" to DtcStatus.STORED), tcm.codes.map { it.code to it.status })
        // Clean modules, and the counts the modules gave.
        for (id in listOf("EOM", "RADIO", "SCM")) assertTrue(id, scans.first { it.module.id == id }.codes.isEmpty())
        assertEquals(7, scans.first { it.module.id == "PCM" }.countReported)
        assertEquals("5803C12620C11420D50420", scans.first { it.module.id == "ABS" }.fingerprint)
    }

    @Test
    fun probeOnlyModulesAreAskedOnlyWhenAllAre() = runBlocking {
        val link = carWithCapture()
        scanner(link).sweep(jeep, descriptions, probeAll = false).getOrThrow()
        assertFalse(link.requests.any { it.first == 0x710 }) // AHBM
        link.requests.clear()
        val all = scanner(link).sweep(jeep, descriptions, probeAll = true).getOrThrow()
        assertEquals(27, all.size)
        assertTrue(link.requests.any { it.first == 0x710 })
        assertEquals(ScanOutcome.NO_RESPONSE, all.first { it.module.id == "AHBM" }.outcome)
    }

    @Test
    fun aSilentCarIsAFailedSweep() = runBlocking {
        val r = scanner(CarLink()).sweep(jeep, descriptions)
        assertTrue(r.exceptionOrNull() is NoModuleAnsweredException)
    }

    @Test
    fun aRefusalIsAnAnswer() = runBlocking {
        val link = CarLink().apply { raw[0x784 to "1800FF00"] = "7F 18 11" }
        val scans = scanner(link).sweep(jeep, descriptions).getOrThrow()
        val abs = scans.first { it.module.id == "ABS" }
        assertEquals(ScanOutcome.REFUSED, abs.outcome)
        assertEquals("7F1811", abs.fingerprint)
    }

    @Test
    fun theGenericProfileIsTodaysRead() = runBlocking {
        val generic = FakeGeneric(Result.success(Obd2Read(stored = listOf("P0420", "P0171"), pending = listOf("P0300"))))
        val link = CarLink()
        val scans = scanner(link, generic).sweep(DiagProfiles.GenericObd2, DtcDescriptions.EMPTY).getOrThrow()
        assertEquals(listOf("read"), generic.calls)
        // The generic path never goes through the addressed transport.
        assertTrue(link.requests.isEmpty())
        val s = scans.single()
        assertEquals("ENGINE", s.module.id)
        assertEquals(listOf("P0420" to DtcStatus.STORED, "P0171" to DtcStatus.STORED, "P0300" to DtcStatus.PENDING), s.codes.map { it.code to it.status })
        assertEquals(0x0420, s.codes[0].raw)
    }

    @Test
    fun theGenericFailureIsKept() = runBlocking {
        val generic = FakeGeneric(Result.failure(IllegalStateException("The engine computer didn't answer")))
        val r = scanner(CarLink(), generic).sweep(DiagProfiles.GenericObd2, DtcDescriptions.EMPTY)
        assertEquals("The engine computer didn't answer", r.exceptionOrNull()?.message)
    }

    @Test
    fun aClearSendsItsStepsInOrderThenReadsAgain() = runBlocking {
        val link = CarLink().apply {
            replies[0x784 to "1092"] = "5092"
            replies[0x784 to "14FF00"] = "54FF00"
            replies[0x784 to "1800FF00"] = "5800"
        }
        val abs = jeep.modules.first { it.id == "ABS" }
        val after = scanner(link).clear(abs, descriptions).getOrThrow()
        assertEquals(listOf("1092", "14FF00", "1800FF00"), link.requests.map { it.second })
        assertTrue(link.requests.all { it.first == 0x784 })
        assertTrue(after.codes.isEmpty())
        assertEquals(ScanOutcome.OK, after.outcome)
    }

    @Test
    fun aClearStopsAtTheFirstRefusal() = runBlocking {
        val link = CarLink().apply {
            raw[0x6E0 to "1092"] = "7F 10 22"
            replies[0x6E0 to "14FF00"] = "54FF00"
        }
        val orc = jeep.modules.first { it.id == "ORC" }
        val r = scanner(link).clear(orc, descriptions)
        val e = r.exceptionOrNull() as ClearRefusedException
        assertEquals("1092", e.step)
        assertEquals(RawReply.Negative(0x10, 0x22), e.reply)
        // Nothing after the refused step: no clear, no re-read.
        assertEquals(listOf("1092"), link.requests.map { it.second })
    }

    @Test
    fun aRefusedClearIsNotReadAgain() = runBlocking {
        val link = CarLink().apply {
            replies[0x784 to "1092"] = "5092"
            raw[0x784 to "14FF00"] = "7F 14 22"
        }
        val r = scanner(link).clear(jeep.modules.first { it.id == "ABS" }, descriptions)
        assertEquals("14FF00", (r.exceptionOrNull() as ClearRefusedException).step)
        assertEquals(listOf("1092", "14FF00"), link.requests.map { it.second })
    }

    @Test
    fun anUnansweredClearStepStops() = runBlocking {
        val link = CarLink()
        val r = scanner(link).clear(jeep.modules.first { it.id == "TCM" }, descriptions)
        assertEquals(RawReply.NoResponse, (r.exceptionOrNull() as ClearRefusedException).reply)
        assertEquals(listOf("1092"), link.requests.map { it.second })
    }

    @Test
    fun theGenericClearIsMode04ThenARead() = runBlocking {
        val generic = FakeGeneric()
        val after = scanner(CarLink(), generic).clear(DiagProfiles.GenericObd2.modules.single(), DtcDescriptions.EMPTY).getOrThrow()
        assertEquals(listOf("clear", "read"), generic.calls)
        assertTrue(after.codes.isEmpty())
        generic.calls.clear()
        generic.clearResult = Result.failure(IllegalStateException("rejected"))
        val r = scanner(CarLink(), generic).clear(DiagProfiles.GenericObd2.modules.single(), DtcDescriptions.EMPTY)
        assertEquals("rejected", r.exceptionOrNull()?.message)
        assertEquals(listOf("clear"), generic.calls)
    }

    @Test
    fun progressIsBackToIdleAfterwards() = runBlocking {
        val s = scanner(carWithCapture())
        s.sweep(jeep, descriptions)
        assertNull(s.progress.value)
    }

    // --- change detection --------------------------------------------------------------------------------------

    private fun scan(id: String, payload: String, outcome: ScanOutcome = ScanOutcome.OK): ModuleScan {
        val m = jeep.modules.first { it.id == id }
        return if (outcome == ScanOutcome.OK) KwpCodec(descriptions).parse(m, ElmReply.parseBytes(payload)!!, true)
        else ModuleScan(m, outcome, emptyList(), null, "")
    }

    @Test
    fun theSameRepliesAreNoChange() {
        val a = listOf(scan("PCM", "5801067620"), scan("ABS", "5800"))
        val b = listOf(scan("PCM", "5801067620"), scan("ABS", "5800"))
        assertTrue(DiagScanner.sameAs(a, b))
        assertFalse(DiagScanner.sameAs(null, b))
    }

    @Test
    fun aNewCodeAClearedCodeOrStoredToActiveIsAChange() {
        val before = listOf(scan("PCM", "5801067620"), scan("ABS", "5800"))
        assertFalse(DiagScanner.sameAs(before, listOf(scan("PCM", "5801067620"), scan("ABS", "5801C12620"))))
        assertFalse(DiagScanner.sameAs(before, listOf(scan("PCM", "5800"), scan("ABS", "5800"))))
        // Stored (20) to active (60).
        assertFalse(DiagScanner.sameAs(before, listOf(scan("PCM", "5801067660"), scan("ABS", "5800"))))
        assertFalse(DiagScanner.sameAs(before, listOf(scan("PCM", "5801067620"), scan("ABS", "", ScanOutcome.NO_RESPONSE))))
    }

    @Test
    fun anAdapterErrorKeepsTheModulesLastAnswer() {
        val before = listOf(scan("PCM", "5801067620"), scan("ABS", "5800"))
        val fresh = listOf(scan("PCM", "", ScanOutcome.ERROR), scan("ABS", "", ScanOutcome.NO_RESPONSE))
        val merged = DiagScanner.merge(before, fresh)
        assertEquals(before[0], merged[0])
        assertEquals(ScanOutcome.NO_RESPONSE, merged[1].outcome)
        assertTrue(DiagScanner.sameAs(before, DiagScanner.merge(before, listOf(scan("PCM", "", ScanOutcome.ERROR), scan("ABS", "5800")))))
    }

    @Test
    fun aClearedModuleReplacesItsEntry() {
        val before = listOf(scan("PCM", "5801067620"), scan("ABS", "5801C12620"))
        val after = DiagScanner.replace(before, scan("ABS", "5800"))
        assertEquals(listOf("PCM", "ABS"), after.map { it.module.id })
        assertTrue(after[1].codes.isEmpty())
        assertEquals(before[0], after[0])
    }

    @Test
    fun theGenericFingerprintIgnoresOrder() {
        val m = DiagProfiles.GenericObd2.modules.single()
        val a = DiagScanner.obd2Scan(m, Obd2Read(listOf("P0420", "P0171"), emptyList()))
        val b = DiagScanner.obd2Scan(m, Obd2Read(listOf("P0171", "P0420"), emptyList()))
        val c = DiagScanner.obd2Scan(m, Obd2Read(listOf("P0171"), listOf("P0420")))
        assertEquals(a.fingerprint, b.fingerprint)
        assertFalse(a.fingerprint == c.fingerprint)
    }

    @Test
    fun codesGoBackToTheirBytes() {
        for (raw in listOf(0x0676, 0xC126, 0x9851, 0x4077, 0xA102, 0x213A, 0xD504)) {
            assertEquals(raw, DiagScanner.rawOf(com.openauto.dash.ObdParser.decodeDtc(raw shr 8, raw and 0xFF)))
        }
        assertEquals(-1, DiagScanner.rawOf("X0000"))
        assertEquals(-1, DiagScanner.rawOf("P12"))
    }

    // --- profile choice ----------------------------------------------------------------------------------------

    @Test
    fun autoPicksJeepByVinOrMake() {
        val auto = DiagChoice.AUTO
        assertEquals(DiagChoice.JEEP_WK, DiagSettings.resolve(auto, "1J8HCE8M45Y588369", ""))
        assertEquals(DiagChoice.JEEP_WK, DiagSettings.resolve(auto, "1j4gr48k35c123456", "Chrysler"))
        assertEquals(DiagChoice.JEEP_WK, DiagSettings.resolve(auto, " 1C4RJFAG5FC123456", ""))
        assertEquals(DiagChoice.JEEP_WK, DiagSettings.resolve(auto, "", "Jeep"))
        assertEquals(DiagChoice.JEEP_WK, DiagSettings.resolve(auto, "", "  JEEP Grand Cherokee"))
        assertEquals(DiagChoice.GENERIC, DiagSettings.resolve(auto, "VF7UA5FS0BJ123456", "Citroën"))
        assertEquals(DiagChoice.GENERIC, DiagSettings.resolve(auto, "", ""))
        assertEquals(DiagChoice.GENERIC, DiagSettings.resolve(auto, "", "Grand Jeepster"))
    }

    @Test
    fun anExplicitChoiceWins() {
        assertEquals(DiagChoice.GENERIC, DiagSettings.resolve(DiagChoice.GENERIC, "1J8HCE8M45Y588369", "Jeep"))
        assertEquals(DiagChoice.JEEP_WK, DiagSettings.resolve(DiagChoice.JEEP_WK, "VF7UA5FS0BJ123456", "Citroën"))
    }

    @Test
    fun capturedAbsMultiFrameAlsoReadsAsRawFrames() = runBlocking {
        // The capture's own shape: raw ISO-TP frames.
        val link = CarLink().apply { raw[0x784 to "1800FF00"] = "10 0B 58 03 C1 26 20 C1\r21 14 20 D5 04 20 00 00" }
        val abs = scanner(link).sweep(jeep, descriptions).getOrThrow().first { it.module.id == "ABS" }
        assertEquals(listOf("U0126", "U0114", "U1504"), abs.codes.map { it.code })
        assertEquals(ScanOutcome.OK, abs.outcome)
    }
}
