package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceNeededTest {
    @Test fun nothingInUseLeavesTheServiceOff() =
        assertFalse(serviceNeeded(false, false, false, false, false, false))

    @Test fun eachFeatureAloneTurnsItOn() {
        assertTrue(serviceNeeded(true, false, false, false, false, false))
        assertTrue(serviceNeeded(false, true, false, false, false, false))
        assertTrue(serviceNeeded(false, false, true, false, false, false))
        assertTrue(serviceNeeded(false, false, false, true, false, false))
        assertTrue(serviceNeeded(false, false, false, false, true, false))
        assertTrue(serviceNeeded(false, false, false, false, false, true))
    }
}
