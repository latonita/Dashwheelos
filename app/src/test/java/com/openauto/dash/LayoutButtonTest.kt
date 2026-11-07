package com.openauto.dash

import com.openauto.unitproto.UnitFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutButtonTest {
    @Test fun hiddenOnXyautoWithDashboards() =
        assertFalse(layoutButtonAvailable(UnitFamily.XYAUTO, DashLayout.GRID))

    @Test fun xyautoWithLeftoverDockKeepsTheWayBack() {
        assertTrue(layoutButtonAvailable(UnitFamily.XYAUTO, DashLayout.MAPS_LEFT))
        assertTrue(layoutButtonAvailable(UnitFamily.XYAUTO, DashLayout.MAPS_RIGHT))
    }

    @Test fun otherUnitsAlwaysHaveIt() {
        for (f in UnitFamily.entries.filter { it != UnitFamily.XYAUTO })
            for (l in DashLayout.entries) assertTrue(layoutButtonAvailable(f, l))
    }
}
