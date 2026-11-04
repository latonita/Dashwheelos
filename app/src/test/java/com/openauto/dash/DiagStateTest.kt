package com.openauto.dash

import com.openauto.dash.diag.DiagProfiles
import com.openauto.dash.diag.DiagScanner
import com.openauto.dash.diag.ElmReply
import com.openauto.dash.diag.KwpCodec
import com.openauto.dash.diag.ModuleScan
import com.openauto.dash.diag.Obd2Read
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The mechanic's state module by module, and what the older consumers still see of it. */
class DiagStateTest {
    private val root = listOf("src/main/assets", "app/src/main/assets").map { File(it) }.first { it.exists() }
    private val loaded = DiagProfiles.loadJeepWk { File(root, it).inputStream() }

    private fun scan(id: String, payload: String): ModuleScan =
        KwpCodec(loaded.second).parse(loaded.first.modules.first { it.id == id }, ElmReply.parseBytes(payload)!!, true)

    private val jeepScans = listOf(
        scan("PCM", "5803C11420067660213AE0"),
        scan("TCM", "5801C11420"),
        scan("ABS", "5800"),
    )

    @Test
    fun codesAreEveryModulesEachOnce() {
        val s = AiMechanic.State(scans = jeepScans)
        assertEquals(listOf("U0114", "P0676", "P213A"), s.codes)
        assertNull(AiMechanic.State().codes)
        assertEquals(emptyList<String>(), AiMechanic.State(scans = listOf(scan("ABS", "5800"))).codes)
        assertEquals(listOf("PCM", "TCM"), s.where("U0114").map { it.first.module.id })
        assertEquals("Cylinder 6 Glow Plug Circuit", s.description("P0676"))
    }

    @Test
    fun faultKeysFollowModuleAndStatus() {
        val s = AiMechanic.State(scans = jeepScans)
        assertEquals(listOf("PCM:U0114:STORED", "PCM:P0676:ACTIVE", "PCM:P213A:ACTIVE", "TCM:U0114:STORED"), s.faultKeys)
        assertEquals(listOf("PCM:U0114", "PCM:P0676", "PCM:P213A", "TCM:U0114"), AiMechanic.knownKeys(jeepScans))
    }

    @Test
    fun theDemosCodesAreAGenericScan() {
        val s = AiMechanic.State.ofCodes(listOf("P0420", "P0171"))
        assertEquals(listOf("P0420", "P0171"), s.codes)
        assertEquals("ENGINE", s.scans!!.single().module.id)
        assertNull(AiMechanic.State.ofCodes(null).codes)
    }

    @Test
    fun geminiIsToldTheModulesOnlyForAModuleScan() {
        val where = MechanicPrompt.where(jeepScans)
        assertEquals("Engine (PCM) stored, Transmission (TCM) stored", where["U0114"])
        assertEquals("Engine (PCM) active", where["P0676"])
        val prompt = MechanicPrompt.build(listOf("U0114", "P0676"), "a Jeep", AiLanguage.ENGLISH, null, where = where)
        assertTrue(prompt.contains("U0114 = Engine (PCM) stored, Transmission (TCM) stored; P0676 = Engine (PCM) active"))

        val generic = listOf(DiagScanner.obd2Scan(DiagProfiles.GenericObd2.modules.single(), Obd2Read(listOf("P0128"), emptyList())))
        assertTrue(MechanicPrompt.where(generic).isEmpty())
        val plain = MechanicPrompt.build(listOf("P0128"), "a car", AiLanguage.ENGLISH, null)
        assertEquals(plain, MechanicPrompt.build(listOf("P0128"), "a car", AiLanguage.ENGLISH, null, where = MechanicPrompt.where(generic)))
    }
}
