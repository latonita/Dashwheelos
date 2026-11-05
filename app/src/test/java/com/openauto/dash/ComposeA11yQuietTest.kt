package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeA11yQuietTest {
    @Test fun quietsOnlyWithoutTouchExploration() {
        assertTrue(ComposeA11yQuiet.shouldQuiet(false))
        assertFalse(ComposeA11yQuiet.shouldQuiet(true))
    }
}
