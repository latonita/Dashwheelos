package com.openauto.dash.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagCodecTest {
    private val root = listOf("src/main/assets", "app/src/main/assets").map { File(it) }.first { it.exists() }
    private val loaded = DiagProfiles.loadJeepWk { File(root, it).inputStream() }
    private val profile = loaded.first
    private val descriptions = loaded.second
    private val kwp = KwpCodec(descriptions)

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun mod(id: String) = profile.modules.first { it.id == id }
    private fun scan(id: String, hex: String, complete: Boolean = true) = kwp.parse(mod(id), bytes(hex), complete)
    private fun list(s: ModuleScan) = s.codes.map { "${it.code}/${it.status.name.lowercase()}" }

    /** Captured replies (ecus.csv raw_dtc_response_hex) and the live list (live_dtc_scan.csv). */
    private val captured = mapOf(
        "ABS" to ("5803C12620C11420D50420" to listOf("U0126/stored", "U0114/stored", "U1504/stored")),
        "CCN" to ("5801985120" to listOf("B1851/stored")),
        "EOM" to ("5800" to emptyList()),
        "ESM" to ("5801060720" to listOf("P0607/stored")),
        "FCMCGW" to ("5801A10020" to listOf("B2100/stored")),
        "FDCM" to ("5801C41520" to listOf("U0415/stored")),
        "ORC" to ("58029BB820A12D20" to listOf("B1BB8/stored", "B212D/stored")),
        "PCM" to (
            "5807046020C11420C10320C11420C10320067660213AE0" to listOf(
                "P0460/stored", "U0114/stored", "U0103/stored", "U0114/stored", "U0103/stored",
                "P0676/active", "P213A/active",
            )
            ),
        "RADIO" to ("5800" to emptyList()),
        "SCM" to ("5800" to emptyList()),
        "TCM" to ("5802C11420C10320" to listOf("U0114/stored", "U0103/stored")),
        "WCM" to ("5802407727A10227" to listOf("C0077/stored", "B2102/stored")),
    )

    @Test fun everyCapturedReplyMatchesTheLiveScan() {
        assertEquals(12, captured.size)
        captured.forEach { (id, p) ->
            val s = scan(id, p.first)
            assertEquals(id, ScanOutcome.OK, s.outcome)
            assertEquals(id, p.second, list(s))
            assertEquals(id, p.second.size, s.countReported)
            assertEquals(id, p.first, s.fingerprint)
        }
    }

    @Test fun descriptionsResolveForLiveCodes() {
        assertEquals(
            "Lost Communication With Steering Angle Sensor",
            scan("ABS", captured.getValue("ABS").first).codes[0].description,
        )
        assertEquals("Cylinder 6 Glow Plug Circuit", scan("PCM", captured.getValue("PCM").first).codes[5].description)
        assertEquals("Low Tire Pressure", scan("WCM", captured.getValue("WCM").first).codes[0].description)
        captured.forEach { (id, p) ->
            scan(id, p.first).codes.forEach { assertNotNull("$id ${it.code}", it.description) }
        }
        assertNull(descriptions.lookup("ABS", 0x0000))
    }

    @Test fun letterAndDigitMapping() {
        val m = mod("ABS")
        fun code(hi: Int, lo: Int) = kwp.parse(m, byteArrayOf(0x58, 1, hi.toByte(), lo.toByte(), 0x20), true).codes[0].code
        assertEquals("U0114", code(0xC1, 0x14))
        assertEquals("B1203", code(0x92, 0x03))
        assertEquals("C0077", code(0x40, 0x77))
        assertEquals("P0676", code(0x06, 0x76))
    }

    @Test fun noFaults() {
        val s = scan("EOM", "5800")
        assertEquals(ScanOutcome.OK, s.outcome)
        assertTrue(s.codes.isEmpty())
        assertEquals(0, s.countReported)
    }

    @Test fun statusBits() {
        val s = scan("PCM", "5803" + "0100" + "00" + "0200" + "40" + "0300" + "60")
        assertEquals(listOf(DtcStatus.STORED, DtcStatus.PENDING, DtcStatus.ACTIVE), s.codes.map { it.status })
    }

    @Test fun nIsRespectedOverLength() {
        val s = scan("ABS", "5801C12620C11420")
        assertEquals(listOf("U0126/stored"), list(s))
        assertEquals(ScanOutcome.OK, s.outcome)
    }

    @Test fun firstFrameOnlyIsPartial() {
        // ISO-TP first frame of the ABS reply carries 6 data bytes: 58 03 C1 26 20 C1
        val s = scan("ABS", "5803C12620C1", complete = false)
        assertEquals(ScanOutcome.PARTIAL, s.outcome)
        assertEquals(3, s.countReported)
        assertEquals(listOf("U0126/stored"), list(s))
    }

    @Test fun refusalAndSilence() {
        assertEquals(ScanOutcome.REFUSED, scan("ABS", "7F1811").outcome)
        assertEquals(ScanOutcome.NO_RESPONSE, kwp.parse(mod("ABS"), ByteArray(0), true).outcome)
        assertEquals(ScanOutcome.ERROR, scan("ABS", "4301").outcome)
    }

    @Test fun udsSample() {
        val m = mod("ABS").copy(protocol = DiagProtocol.UDS)
        // 59 02 FF, C1 26 00 status 0x09 (failed), 01 33 00 status 0x04 (pending), 00 77 00 status 0x08 (confirmed only)
        val s = UdsCodec(descriptions).parse(m, bytes("5902FF" + "C1260009" + "01330004" + "00770008"), true)
        assertEquals(ScanOutcome.OK, s.outcome)
        assertEquals(listOf("U0126/active", "P0133/pending", "P0077/stored"), list(s))
        assertEquals("Lost Communication With Steering Angle Sensor", s.codes[0].description)
        assertEquals(ScanOutcome.PARTIAL, UdsCodec().parse(m, bytes("5902FFC12600"), true).outcome)
        assertEquals(ScanOutcome.OK, UdsCodec().parse(m, bytes("5902FF"), true).outcome)
    }

    @Test fun obd2Sample() {
        val m = DiagProfiles.GenericObd2.modules[0]
        val c = Obd2Codec()
        val s03 = c.parse(m, bytes("43020133C114"), true)
        assertEquals(listOf("P0133/stored", "U0114/stored"), list(s03))
        assertEquals(2, s03.countReported)
        assertEquals(listOf("P0133/pending"), list(c.parse(m, bytes("47010133"), true)))
        // older layout, zero padded
        assertEquals(listOf("P0133/stored"), list(c.parse(m, bytes("43013300000000"), true)))
        assertEquals(ScanOutcome.OK, c.parse(m, bytes("4300"), true).outcome)
        assertEquals(ScanOutcome.PARTIAL, c.parse(m, bytes("43040133"), true).outcome)
    }

    @Test fun profileLoads() {
        assertEquals("jeep-wk", profile.id)
        assertEquals(27, profile.modules.size)
        assertEquals(12, profile.modules.count { !it.probeOnly })
        assertEquals(15, profile.modules.count { it.probeOnly })
        val abs = mod("ABS")
        assertEquals(0x784, abs.tx)
        assertEquals(0x785, abs.rx)
        assertEquals(DiagProtocol.KWP_CAN, abs.protocol)
        assertEquals("1800FF00", abs.readRequest)
        assertEquals(listOf("1092", "14FF00"), abs.clearSteps)
        assertEquals(ClearRisk.NORMAL, abs.clearRisk)
        assertEquals(setOf("ORC", "WCM", "PCM"), profile.modules.filter { it.clearRisk == ClearRisk.SENSITIVE }.map { it.id }.toSet())
        assertEquals(0x7E8, mod("PCM").rx)
        assertTrue(descriptions.size > 1000)
    }

    @Test fun genericProfile() {
        val m = DiagProfiles.GenericObd2.modules.single()
        assertEquals("ENGINE", m.id)
        assertEquals("Engine (OBD-II)", m.name)
        assertEquals(0x7DF, m.tx)
        assertEquals(0x7E8, m.rx)
        assertEquals(listOf("04"), m.clearSteps)
    }
}
