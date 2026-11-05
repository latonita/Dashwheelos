package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixWanderTest {
    @Test fun wanderWhileStationaryIsIgnored() = assertTrue(FixWander.ignore(true, 1.5f))

    @Test fun aRealMoveWhileStationaryIsKept() = assertFalse(FixWander.ignore(true, 8f))

    @Test fun theBoundaryIsKept() = assertFalse(FixWander.ignore(true, FixWander.MAX_NOISE_M))

    @Test fun movingNeverIgnoresAFix() = assertFalse(FixWander.ignore(false, 0.5f))
}
