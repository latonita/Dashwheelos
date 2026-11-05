package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class HexOfTest {
    @Test fun matchesTheFormatter() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.joinToString("") { "%02X".format(it) }, hexOf(bytes))
    }

    @Test fun emptyIsEmpty() = assertEquals("", hexOf(ByteArray(0)))
}
