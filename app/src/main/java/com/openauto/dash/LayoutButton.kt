package com.openauto.dash

import com.openauto.unitproto.UnitFamily

/**
 * Whether the bar offers its "Layout" button (Google Maps dock beside the pages, or another app
 * beside Dashwheel). The XYAuto unit (Android 8.1, Mali-450) cannot show two apps side by side,
 * so there the button is hidden. A dock left over from before stays reachable: while the saved
 * layout is not [DashLayout.GRID] the button shows, so the driver can go back to the dashboards.
 */
internal fun layoutButtonAvailable(family: UnitFamily, current: DashLayout): Boolean =
    family != UnitFamily.XYAUTO || current != DashLayout.GRID
