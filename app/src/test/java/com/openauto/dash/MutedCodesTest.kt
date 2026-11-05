package com.openauto.dash

import com.openauto.dash.diag.DiagProfiles
import com.openauto.dash.diag.ElmReply
import com.openauto.dash.diag.KwpCodec
import com.openauto.dash.diag.ModuleScan
import com.openauto.dash.diag.MutedCodes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Muting a fault code: the store, and what still warns. */
class MutedCodesTest {
    private val root = listOf("src/main/assets", "app/src/main/assets").map { File(it) }.first { it.exists() }
    private val loaded = DiagProfiles.loadJeepWk { File(root, it).inputStream() }

    private fun scan(id: String, payload: String): ModuleScan =
        KwpCodec(loaded.second).parse(loaded.first.modules.first { it.id == id }, ElmReply.parseBytes(payload)!!, true)

    // PCM: U0114 stored, P0676 active, P213A active; TCM: U0114 stored.
    private val scans = listOf(scan("PCM", "5803C11420067660213AE0"), scan("TCM", "5801C11420"), scan("ABS", "5800"))

    @After fun reset() = MutedCodes.setForTest(emptySet())

    @Test
    fun toggleAndPersistRoundTrip() {
        var set = emptySet<String>()
        set = MutedCodes.toggled(set, "u0184")
        set = MutedCodes.toggled(set, "P0676")
        assertEquals(setOf("U0184", "P0676"), set)
        assertEquals(set, MutedCodes.parse(MutedCodes.format(set)))
        assertEquals(setOf("P0676"), MutedCodes.toggled(set, "U0184"))
        assertEquals(emptySet<String>(), MutedCodes.parse(""))
        MutedCodes.setForTest(set)
        assertTrue(MutedCodes.isMuted("U0184"))
        assertFalse(MutedCodes.isMuted("P0001"))
    }

    @Test
    fun warningCodesLeaveOutTheMutedOnes() {
        val s = AiMechanic.State(scans = scans, muted = setOf("U0114"))
        assertEquals(listOf("U0114", "P0676", "P213A"), s.codes)
        assertEquals(listOf("P0676", "P213A"), s.warningCodes)
        // One mute covers every module that reports the code.
        assertEquals(listOf("PCM:P0676:ACTIVE", "PCM:P213A:ACTIVE"), s.warningKeys)
        assertNull(AiMechanic.State(muted = setOf("U0114")).warningCodes)
    }

    @Test
    fun aMutedCodeStaysMutedWhateverItsStatus() {
        val s = AiMechanic.State(scans = scans, muted = setOf("P0676", "U0114", "P213A"))
        assertEquals(emptyList<String>(), s.warningCodes)
        assertEquals(emptyList<String>(), s.warningKeys)
    }

    @Test
    fun theVerdictIgnoresMutedCodes() {
        val all = AiMechanic.State(scans = scans, muted = setOf("U0114", "P0676", "P213A"))
        assertEquals(CarVerdict.FINE, carVerdict(emptyList(), all.warningCodes, Severity.STOP, lampOn = false))
        val some = AiMechanic.State(scans = scans, muted = setOf("U0114"))
        assertEquals(CarVerdict.DRIVE, carVerdict(emptyList(), some.warningCodes, Severity.SOON, lampOn = false))
    }

    @Test
    fun aChangeInAMutedCodeAloneLeavesTheWarningKeysAlone() {
        val muted = setOf("U0114")
        val before = AiMechanic.State(scans = scans, muted = muted)
        // The TCM's only fault is the muted one: clearing it changes the faults, not what warns.
        val after = AiMechanic.State(scans = listOf(scans[0], scan("TCM", "5800"), scans[2]), muted = muted)
        assertTrue(before.faultKeys != after.faultKeys)
        assertEquals(before.warningKeys, after.warningKeys)
        assertEquals(before.warningCodes, after.warningCodes)
    }

    @Test
    fun theFreshCodesOfAScanSkipTheMutedOnes() {
        val keys = AiMechanic.knownKeys(scans)
        val fresh = AiMechanic.newCodes(keys, emptyMap(), 0L).map { it.substringAfter(':') }.distinct()
        assertEquals(listOf("U0114", "P0676", "P213A"), fresh)
        assertEquals(listOf("P0676", "P213A"), AiMechanic.freshUnmuted(fresh, setOf("U0114")))
    }
}
