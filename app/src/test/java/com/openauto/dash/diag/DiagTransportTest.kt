package com.openauto.dash.diag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted adapter: [answers] by exact command, "OK" for the AT commands in [accepts], "?" for the rest. */
private class FakeLink(
    var stn: Boolean = false,
    var supportsCra: Boolean = true,
    var supportsFcs: Boolean = true,
    var slow: Boolean = false
) : DiagLink {
    val sent = ArrayList<String>()
    val answers = HashMap<String, String?>()
    val later = ArrayDeque<String?>()
    var connected = true

    override suspend fun <T> exclusive(block: () -> T): T? = if (connected) block() else null

    override fun send(command: String, timeoutMs: Long): String? {
        sent += command
        if (answers.containsKey(command)) return answers[command]
        return when {
            command == "STI" -> if (stn) "STN2230 v5.6.19" else "?"
            command.startsWith("ATCRA") -> if (supportsCra) "OK" else "?"
            command.startsWith("ATFC") -> if (supportsFcs) "OK" else "?"
            command.startsWith("AT") -> "OK"
            else -> "NO DATA"
        }
    }

    override fun readMore(timeoutMs: Long): String? = later.removeFirstOrNull()
    override val slowLink get() = slow

    fun requestsOnly() = sent.filter { !it.startsWith("AT") && it != "STI" }
    fun clear() = sent.clear()
}

class DiagTransportTest {
    private fun transport(link: FakeLink) = DiagTransport(link, pendingMs = 50)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun forbiddenRequestsNeverTouchTheAdapter() = runBlocking {
        val link = FakeLink()
        val t = transport(link)
        for (hex in listOf("27 01", "2E F1 90", "31 01 00", "11 01", "10 03", "23 00", "ATZ", "zz")) {
            assertTrue(hex, t.request(0x784, 0x785, hex) is RawReply.Error)
        }
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun readsAMultiFrameReply() = runBlocking {
        val link = FakeLink()
        link.answers["1800FF00"] = "10 0B 58 03 C1 26 20 C1\r21 14 20 D5 04 20 00 00"
        val r = transport(link).request(0x784, 0x785, "18 00 FF 00")
        assertEquals(RawReply.Positive(bytes(0x58, 0x03, 0xC1, 0x26, 0x20, 0xC1, 0x14, 0x20, 0xD5, 0x04, 0x20), true), r)
    }

    @Test
    fun probeFindsWhatTheAdapterAccepts() = runBlocking {
        val full = transport(FakeLink(stn = true)).caps()
        assertEquals(AdapterCaps(stn = true, canReceiveFilter = true, flowControlSet = true), full)
        val clone = transport(FakeLink(supportsCra = false, supportsFcs = false)).caps()
        assertEquals(AdapterCaps(stn = false, canReceiveFilter = false, flowControlSet = false), clone)
    }

    @Test
    fun probeIsCachedAndPutsTheStateBack() = runBlocking {
        val link = FakeLink()
        val t = transport(link)
        t.caps(); t.caps()
        assertEquals(1, link.sent.count { it == "STI" })
        assertTrue(link.sent.contains("ATFCSM0"))
        assertEquals("ATCRA", link.sent.last())
    }

    @Test
    fun resetForgetsTheProbe() = runBlocking {
        val link = FakeLink()
        val t = transport(link)
        t.caps()
        t.reset()
        t.caps()
        assertEquals(2, link.sent.count { it == "STI" })
    }

    @Test
    fun setsTheAddressesThenRestoresTheGenericState() = runBlocking {
        val link = FakeLink()
        val t = transport(link)
        t.caps(); link.clear()
        t.request(0x784, 0x785, "18 00 FF 00")
        val s = link.sent
        assertTrue(s.contains("ATSH784")); assertTrue(s.contains("ATCRA785"))
        assertTrue(s.contains("ATFCSH784")); assertTrue(s.contains("ATFCSD300000")); assertTrue(s.contains("ATFCSM1"))
        assertTrue(s.indexOf("ATFCSM1") < s.indexOf("1800FF00"))
        // restored at the end
        assertTrue(s.subList(s.indexOf("1800FF00"), s.size).containsAll(listOf("ATFCSM0", "ATCRA", "ATSH7DF", "ATAT1", "ATST32")))
    }

    @Test
    fun withModulesRestoresOnceAndSkipsWhatIsAlreadySet() = runBlocking {
        val link = FakeLink()
        val t = transport(link)
        t.caps(); link.clear()
        t.withModules {
            t.request(0x784, 0x785, "18 00 FF 00")
            t.request(0x784, 0x785, "17")
            t.request(0x788, 0x789, "18 00 FF 00")
        }
        assertEquals(1, link.sent.count { it == "ATSH784" })
        assertEquals(1, link.sent.count { it == "ATFCSM1" })
        assertEquals(1, link.sent.count { it == "ATSH788" })
        assertEquals(1, link.sent.count { it == "ATSH7DF" })
        assertEquals(1, link.sent.count { it == "ATFCSM0" })
        // the second module changed the address, not the timing
        assertEquals(1, link.sent.count { it == "ATST40" })
        assertEquals(1, link.sent.count { it == "ATAT0" })
    }

    @Test
    fun aForeignAddressCommandInvalidatesTheCache() = runBlocking {
        val link = FakeLink()
        val t = transport(link)
        t.caps()
        t.withModules {
            t.request(0x784, 0x785, "18 00 FF 00")
            link.clear()
            t.noteForeign("ATSH7E0")
            t.request(0x784, 0x785, "18 00 FF 00")
        }
        assertTrue(link.sent.contains("ATSH784"))
        assertTrue(link.sent.contains("ATCRA785"))
    }

    @Test
    fun harmlessForeignCommandsKeepTheCache() = runBlocking {
        val link = FakeLink()
        val t = transport(link)
        t.caps()
        t.withModules {
            t.request(0x784, 0x785, "18 00 FF 00")
            link.clear()
            t.noteForeign("ATRV"); t.noteForeign("0100"); t.noteForeign("ATDPN")
            t.request(0x784, 0x785, "18 00 FF 00")
        }
        assertFalse(link.sent.contains("ATSH784"))
    }

    @Test
    fun restoreLockedPutsTheStateBackForAnotherUser() = runBlocking {
        val link = FakeLink()
        val t = transport(link)
        t.caps()
        t.withModules {
            t.request(0x784, 0x785, "18 00 FF 00")
            link.clear()
            t.restoreLocked() // the gauge poll is about to take the adapter
            assertTrue(link.sent.contains("ATSH7DF"))
            link.clear()
            t.restoreLocked()
            assertTrue(link.sent.isEmpty())
            t.request(0x784, 0x785, "18 00 FF 00")
            assertTrue(link.sent.contains("ATSH784")) // the next module sets it all again
        }
    }

    @Test
    fun waitsOutResponsePending() = runBlocking {
        val link = FakeLink()
        link.answers["14FF00"] = "7F 14 78"
        link.later += "54 FF 00"
        val r = transport(link).request(0x784, 0x785, "14 FF 00")
        assertEquals(RawReply.Positive(bytes(0x54, 0xFF, 0x00), true), r)
    }

    @Test
    fun pendingThatNeverEndsIsAnError() = runBlocking {
        val link = FakeLink()
        link.answers["14FF00"] = "7F 14 78"
        assertTrue(transport(link).request(0x784, 0x785, "14 FF 00") is RawReply.Error)
    }

    @Test
    fun negativeAndNoData() = runBlocking {
        val link = FakeLink()
        link.answers["1800FF00"] = "7F 18 11"
        val t = transport(link)
        assertEquals(RawReply.Negative(0x18, 0x11), t.request(0x784, 0x785, "18 00 FF 00"))
        assertEquals(RawReply.NoResponse, t.request(0x600, 0x500, "18 00 FD 00"))
        link.answers["1800FE00"] = null
        assertEquals(RawReply.NoResponse, t.request(0x600, 0x500, "18 00 FE 00"))
    }

    @Test
    fun fallbackWithoutFiltersUsesHeadersAndDropsForeignFrames() = runBlocking {
        val link = FakeLink(supportsCra = false, supportsFcs = false)
        link.answers["1800FF00"] = "7E8 03 7F 18 78\r785 10 0B 58 03 C1 26 20 C1"
        val r = transport(link).request(0x784, 0x785, "18 00 FF 00")
        assertTrue(link.sent.contains("ATH1"))
        r as RawReply.Positive
        assertFalse(r.complete)
        assertEquals(6, r.bytes.size)
        // and the generic state (headers off) comes back
        assertEquals("ATH0", link.sent.filter { it.startsWith("ATH") }.last())
    }

    @Test
    fun fallbackNeverSendsFilterOrFlowControlAfterTheProbe() = runBlocking {
        val link = FakeLink(supportsCra = false, supportsFcs = false)
        val t = transport(link)
        t.caps(); link.clear()
        t.request(0x784, 0x785, "18 00 FF 00")
        assertTrue(link.sent.none { it.startsWith("ATCRA") || it.startsWith("ATFC") })
    }

    @Test
    fun silentAdapterOnASetupCommandIsAnError() = runBlocking {
        val link = FakeLink()
        link.answers["ATSH784"] = null
        val r = transport(link).request(0x784, 0x785, "18 00 FF 00")
        assertTrue(r is RawReply.Error)
        assertTrue(link.requestsOnly().isEmpty())
    }

    @Test
    fun notConnectedIsAnError() = runBlocking {
        val link = FakeLink().also { it.connected = false }
        assertTrue(transport(link).request(0x784, 0x785, "18 00 FF 00") is RawReply.Error)
    }

    @Test
    fun bluetoothCloneGetsALongerStTimeThanAnStn() = runBlocking {
        val clone = FakeLink(slow = true)
        transport(clone).request(0x784, 0x785, "18 00 FF 00")
        assertTrue(clone.sent.contains("ATST80"))
        val stn = FakeLink(slow = true, stn = true)
        transport(stn).request(0x784, 0x785, "18 00 FF 00")
        assertTrue(stn.sent.contains("ATST40"))
    }

    @Test
    fun transitionFromUnknownSendsEverythingAndFromEqualSendsNothing() {
        val caps = AdapterCaps(stn = false, canReceiveFilter = true, flowControlSet = true)
        val want = LinkSettings(headers = false, sh = 0x784, cra = 0x785, fcsh = 0x784, flow = true, st = 0x40, fixedTiming = true)
        assertEquals(
            listOf("ATAT0", "ATST40", "ATH0", "ATSH784", "ATCRA785", "ATFCSH784", "ATFCSD300000", "ATFCSM1"),
            transition(null, want, caps)
        )
        assertTrue(transition(want, want, caps).isEmpty())
        assertEquals(listOf("ATCRA", "ATFCSM0", "ATSH7DF", "ATST32", "ATAT1").sorted(), transition(want, LinkSettings.DEFAULT, caps).sorted())
    }
}
