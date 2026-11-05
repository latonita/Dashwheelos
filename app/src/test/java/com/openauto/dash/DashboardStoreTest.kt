package com.openauto.dash

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Grid placement rules and the layout JSON, both pure Kotlin over [DashboardItem]. */
class DashboardStoreTest {

    private fun widget(kind: BuiltinKind, x: Int, y: Int, w: Int, h: Int) =
        DashboardItem.BuiltinWidget(kind, x, y, w, h)

    private fun noOverlaps(items: List<DashboardItem>) {
        for (i in items.indices) for (j in i + 1 until items.size) {
            assertFalse("${items[i]} overlaps ${items[j]}", items[i].overlaps(items[j]))
        }
    }

    @Before
    fun resetRetained() {
        // The store is a singleton; make sure no retained tiles leak between tests.
        DashboardStore.parsePages("[]")
    }

    // --- placement -----------------------------------------------------------

    @Test
    fun firstFreeCellScansRowByRow() {
        assertEquals(0 to 0, DashboardStore.firstFreeCell(emptyList(), 3, 2))
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertEquals(3 to 0, DashboardStore.firstFreeCell(page, 3, 2))
        // Nothing fits a full-width span next to it: goes to the next row.
        assertEquals(0 to 2, DashboardStore.firstFreeCell(page, GRID_COLS, 2))
    }

    @Test
    fun canPlaceRespectsBoundsAndOtherTiles() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertFalse(DashboardStore.canPlace(page, null, 1, 0, 3, 2))
        assertTrue(DashboardStore.canPlace(page, 0, 1, 0, 3, 2)) // ignoring itself
        assertTrue(DashboardStore.canPlace(page, null, 3, 0, 3, 2))
        assertFalse(DashboardStore.canPlace(page, null, GRID_COLS - 2, 0, 3, 2))
        assertFalse(DashboardStore.canPlace(page, null, -1, 0, 3, 2))
    }

    @Test
    fun moveToFreeCellJustMoves() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2), widget(BuiltinKind.AUDIO, 3, 0, 3, 2))
        val result = DashboardStore.moveResolving(page, 0, 0, 4)
        assertNotNull(result)
        assertEquals(widget(BuiltinKind.CLOCK, 0, 4, 3, 2), result!![0])
        assertEquals(page[1], result[1])
    }

    @Test
    fun moveOntoSingleSameSizedTileSwaps() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2), widget(BuiltinKind.AUDIO, 3, 0, 3, 2))
        val result = DashboardStore.moveResolving(page, 0, 3, 0)!!
        assertEquals(widget(BuiltinKind.CLOCK, 3, 0, 3, 2), result[0])
        assertEquals(widget(BuiltinKind.AUDIO, 0, 0, 3, 2), result[1])
    }

    @Test
    fun moveOntoSeveralTilesNudgesThemAside() {
        val page = listOf(
            widget(BuiltinKind.CLOCK, 0, 0, 3, 2),
            widget(BuiltinKind.AUDIO, 3, 0, 3, 2),
            widget(BuiltinKind.WEATHER, 6, 0, 3, 2)
        )
        val result = DashboardStore.moveResolving(page, 0, 4, 0)
        assertNotNull(result)
        assertEquals(4, result!![0].x)
        assertEquals(0, result[0].y)
        assertEquals(3, result.size)
        noOverlaps(result)
    }

    @Test
    fun moveOutOfBoundsIsRefused() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertNull(DashboardStore.moveResolving(page, 0, GRID_COLS - 1, 0))
        assertNull(DashboardStore.moveResolving(page, 5, 0, 0))
    }

    @Test
    fun nearestFreeCellPrefersTheClosestSpot() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        assertEquals(3 to 0, DashboardStore.nearestFreeCell(page, 3, 2, 2, 0))
        assertEquals(0 to 3, DashboardStore.nearestFreeCell(page, 3, 2, 0, 3)) // free right there
        assertEquals(0 to 2, DashboardStore.nearestFreeCell(page, 3, 2, 0, 1)) // one row down beats four across
        assertNull(DashboardStore.nearestFreeCell(page, GRID_COLS, GRID_ROWS, 0, 0))
    }

    @Test
    fun repairOverlapsKeepsTheFirstAndRelocatesTheRest() {
        val page = listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2), widget(BuiltinKind.AUDIO, 0, 0, 3, 2))
        val fixed = DashboardStore.repairOverlaps(page)
        assertEquals(2, fixed.size)
        assertEquals(page[0], fixed[0])
        noOverlaps(fixed)
    }

    // --- JSON ----------------------------------------------------------------

    @Test
    fun layoutRoundTripsThroughJson() {
        val pages = listOf(
            listOf(
                widget(BuiltinKind.NAVMAP, 0, 0, 5, 3),
                DashboardItem.AppShortcut("com.example.a", 5, 0, 2, 2),
                DashboardItem.SplitPair("com.example.a", "com.example.b", 7, 0, 2, 2),
                DashboardItem.LaunchBar(listOf("com.example.a", "com.example.b"), 0, 3, 8, 1),
                DashboardItem.SystemWidget(42, 0, 4, 5, 3),
                DashboardItem.AppWindow("com.google.android.apps.youtube.music", 5, 3, 5, 3),
                DashboardItem.AppWindow("com.waze", 10, 3, 2, 3, inside = true)
            ),
            emptyList(),
            listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2))
        )
        val json = DashboardStore.serializePages(pages)
        assertTrue(json.startsWith("{"))
        assertEquals(pages, DashboardStore.parsePages(json))
    }

    @Test
    fun legacyBareArrayStillLoads() {
        val legacy = """[[{"t":"builtin","k":"CLOCK","gx":1,"gy":2,"gw":3,"gh":2}],[],[]]"""
        val pages = DashboardStore.parsePages(legacy)!!
        assertEquals(3, pages.size)
        assertEquals(listOf(widget(BuiltinKind.CLOCK, 1, 2, 3, 2)), pages[0])
    }

    @Test
    fun legacyTileWithoutCoordinatesGetsItsKindDefaultSize() {
        val pages = DashboardStore.parsePages("""[[{"t":"builtin","k":"CLOCK"}]]""")!!
        val clock = pages[0].single()
        assertEquals(-1, clock.x) // unplaced sentinel: load() flows it in
        assertEquals(BuiltinKind.CLOCK.defaultW, clock.w)
        assertEquals(BuiltinKind.CLOCK.defaultH, clock.h)
    }

    @Test
    fun unknownTilesAreCarriedThroughToTheNextSave() {
        val fromNewerBuild = """{"v":9,"pages":[[
            {"t":"hologram","gx":0,"gy":0,"gw":3,"gh":2},
            {"t":"builtin","k":"WARP_DRIVE","gx":3,"gy":0,"gw":3,"gh":2},
            {"t":"app","pkg":"com.example.a","gx":6,"gy":0,"gw":2,"gh":2}
        ]]}"""
        val pages = DashboardStore.parsePages(fromNewerBuild)!!
        assertEquals(listOf(DashboardItem.AppShortcut("com.example.a", 6, 0, 2, 2)), pages[0])

        val saved = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { pages.getOrElse(it) { emptyList() } })
        assertTrue(saved.contains("hologram"))
        assertTrue(saved.contains("WARP_DRIVE"))
        assertTrue(saved.contains("com.example.a"))
    }

    @Test
    fun droppedKindsLeaveTheLayoutInsteadOfBeingRetained() {
        // The 3D car widget was removed; a layout saved with one loses that tile
        // for good, while tiles from a newer build are still carried through.
        val saved = """{"v":1,"pages":[[
            {"t":"builtin","k":"CAR3D","gx":0,"gy":0,"gw":4,"gh":3},
            {"t":"builtin","k":"WARP_DRIVE","gx":4,"gy":0,"gw":3,"gh":2},
            {"t":"builtin","k":"CLOCK","gx":7,"gy":0,"gw":3,"gh":2}
        ]]}"""
        val pages = DashboardStore.parsePages(saved)!!
        assertEquals(listOf(BuiltinKind.CLOCK), pages[0].map { (it as DashboardItem.BuiltinWidget).kind })

        val again = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { pages.getOrElse(it) { emptyList() } })
        assertFalse(again.contains("CAR3D"))
        assertTrue(again.contains("WARP_DRIVE"))
    }

    @Test
    fun unknownTilesStayWithTheirOwnLayout() {
        val full = """{"v":9,"pages":[[{"t":"hologram","gx":0,"gy":0,"gw":3,"gh":2}]]}"""
        val half = """{"v":9,"pages":[[{"t":"jetpack","gx":0,"gy":0,"gw":3,"gh":2}]]}"""
        val fullPages = DashboardStore.parsePages(full, "")!!
        // Reading the other arrangement (as a page swipe does) must not swap its unknown tiles in.
        DashboardStore.parsePages(half, "_half")

        val saved = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { fullPages.getOrElse(it) { emptyList() } }, "")
        assertTrue(saved.contains("hologram"))
        assertFalse(saved.contains("jetpack"))
    }

    // --- tile designs ---------------------------------------------------------

    @Test
    fun tileDesignRoundTripsAndSurvivesMovesAndResizes() {
        val gauge = widget(BuiltinKind.SPEED_HUD, 0, 0, 3, 2).copy(design = WidgetDesign.NEON)
        val pages = listOf(listOf(gauge, widget(BuiltinKind.CLOCK, 3, 0, 3, 2)), emptyList(), emptyList())
        assertEquals(pages, DashboardStore.parsePages(DashboardStore.serializePages(pages)))

        val moved = DashboardStore.moveResolving(pages[0], 0, 6, 3)!!
        assertEquals(WidgetDesign.NEON, (moved[0] as DashboardItem.BuiltinWidget).design)
        assertEquals(WidgetDesign.NEON, (gauge.withCell(0, 0, 5, 3) as DashboardItem.BuiltinWidget).design)
    }

    @Test
    fun standardDesignIsNotWrittenSoOlderBuildsSeeTheSameJson() {
        val json = DashboardStore.serializePages(listOf(listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2)), emptyList(), emptyList()))
        assertFalse(json.contains("\"d\""))
        val styled = DashboardStore.serializePages(
            listOf(listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2).copy(design = WidgetDesign.FLAP)), emptyList(), emptyList())
        )
        assertTrue(styled.contains("\"d\":\"FLAP\""))
    }

    @Test
    fun unknownOrMissingDesignFallsBackToStandard() {
        val pages = DashboardStore.parsePages(
            """{"v":1,"pages":[[{"t":"builtin","k":"CLOCK","d":"HOLOGRAM","gx":0,"gy":0,"gw":3,"gh":2},
               {"t":"builtin","k":"WEATHER","gx":3,"gy":0,"gw":4,"gh":2}]]}"""
        )!!
        assertEquals(WidgetDesign.STANDARD, (pages[0][0] as DashboardItem.BuiltinWidget).design)
        assertEquals(WidgetDesign.STANDARD, (pages[0][1] as DashboardItem.BuiltinWidget).design)
        assertEquals(WidgetDesign.STANDARD, WidgetDesign.fromName(null))
        assertEquals(WidgetDesign.NEON, WidgetDesign.fromName("NEON"))
    }

    @Test
    fun retiredDesignsBecomeTheirClosestSibling() {
        assertEquals(WidgetDesign.HERO, WidgetDesign.fromName("MINIMAL"))
        assertEquals(WidgetDesign.NEON, WidgetDesign.fromName("GAUGE"))
        assertEquals(WidgetDesign.CHRONO, WidgetDesign.fromName("BLUEPRINT"))
        val pages = DashboardStore.parsePages(
            """{"v":1,"pages":[[{"t":"builtin","k":"MEDIA","d":"RING","gx":0,"gy":0,"gw":4,"gh":2}]]}"""
        )!!
        assertEquals(WidgetDesign.GLASS, (pages[0][0] as DashboardItem.BuiltinWidget).design)
    }

    @Test
    fun anyTileCanShrinkToOneCell() {
        val clock = widget(BuiltinKind.CLOCK, 2, 2, 3, 2).withCell(2, 2, 1, 1)
        assertEquals(1, clock.w)
        assertEquals(1, clock.h)
        val bar = DashboardItem.LaunchBar(listOf("a"), 0, 6, 8, 1).withCell(0, 6, 1, 1)
        assertEquals(1, bar.w)
    }

    @Test
    fun zoomStepsOnWholeTenthsWithinItsRange() {
        assertEquals(1.1f, zoomStep(1f, 1), 0f)
        assertEquals(0.9f, zoomStep(1f, -1), 0f)
        // Ten taps up and ten down come back exactly, never 0.9999.
        var z = 1f
        repeat(10) { z = zoomStep(z, 1) }
        repeat(10) { z = zoomStep(z, -1) }
        assertEquals(1f, z, 0f)
        assertEquals(ZOOM_MAX, zoomStep(ZOOM_MAX, 1), 0f)
        assertEquals(ZOOM_MIN, zoomStep(ZOOM_MIN, -1), 0f)
        assertEquals(ZOOM_MAX, widget(BuiltinKind.CLOCK, 0, 0, 3, 2).withZoom(9f).zoom, 0f)
    }

    @Test
    fun zoomRoundTripsAndSurvivesMovesAndResizes() {
        val zoomed = widget(BuiltinKind.CLOCK, 0, 0, 3, 2).withZoom(1.4f)
        val pages = listOf(listOf(zoomed, DashboardItem.AppShortcut("a", 4, 0).withZoom(0.7f)), emptyList(), emptyList())
        assertEquals(pages, DashboardStore.parsePages(DashboardStore.serializePages(pages)))
        assertEquals(1.4f, zoomed.withCell(5, 3, 2, 1).zoom, 0f)
        // The usual size is not written, so older builds see the same JSON.
        assertFalse(DashboardStore.serializePages(listOf(listOf(widget(BuiltinKind.CLOCK, 0, 0, 3, 2)), emptyList(), emptyList())).contains("\"z\""))
        assertFalse(DashboardItem.AppWindow("maps").canZoom())
        // Inside its tile an app is drawn at the tile's density, so zooming it works.
        assertTrue(DashboardItem.AppWindow("maps", inside = true).canZoom())
        assertTrue(zoomed.canZoom())
        // Where apps can run inside, a zoomed window tile moves inside to get its own density.
        assertTrue(DashboardItem.AppWindow("waze").canZoom(insideAllowed = true))
        assertFalse(DashboardItem.AppWindow("waze").runsInside(insideAllowed = true))
        assertTrue(DashboardItem.AppWindow("waze").withZoom(1.3f).let { it as DashboardItem.AppWindow }.runsInside(insideAllowed = true))
        assertFalse(DashboardItem.AppWindow("waze").withZoom(1.3f).let { it as DashboardItem.AppWindow }.runsInside(insideAllowed = false))
    }

    @Test
    fun appInsideItsTileIsSavedAsSuch() {
        val inside = DashboardItem.AppWindow("com.google.android.apps.youtube.music", 0, 0, 5, 3, inside = true)
        val json = DashboardStore.serializePages(listOf(listOf(inside), emptyList(), emptyList()))
        assertTrue(json.contains("\"in\":true"))
        assertEquals(inside, DashboardStore.parsePages(json)!!.first().first())
        // A window tile saved before this option stays a window.
        val old = """[[{"t":"appwin","pkg":"com.waze","gx":0,"gy":0,"gw":5,"gh":3}],[],[]]"""
        assertEquals(false, (DashboardStore.parsePages(old)!!.first().first() as DashboardItem.AppWindow).inside)
    }

    @Test
    fun everyWidgetHasAtLeastTenDesigns() {
        val generic = WidgetDesign.entries.filter { !it.isSignature }
        // The standard renderer plus the generic faces apply to every built-in kind.
        assertTrue(generic.size >= 11)
        assertEquals(1, generic.count { it.layout == null })
        // No two generic designs are the same layout in the same material.
        assertEquals(generic.size, generic.map { it.layout to it.look }.toSet().size)
        BuiltinKind.entries.forEach { kind -> assertTrue("$kind", WidgetDesign.offeredFor(kind, framed = false).size >= 10) }
    }

    @Test
    fun copperSetReachesEveryWidget() {
        val copper = listOf(WidgetDesign.COPPER_COCKPIT, WidgetDesign.TRI_LED, WidgetDesign.COPPER_BLADE, WidgetDesign.LIGHT_BAR)
        // Generic designs: every redrawn widget offers them, and they are saved by name like the rest.
        assertTrue(copper.none { it.isSignature })
        BuiltinKind.entries.forEach { kind -> assertTrue("$kind", WidgetDesign.offeredFor(kind, framed = false).containsAll(copper)) }
        copper.forEach { assertEquals(it, WidgetDesign.fromName(it.name)) }
        // Two materials, each worn by two different layouts.
        assertEquals(setOf(FaceLookKind.COPPER, FaceLookKind.PETROL), copper.map { it.look }.toSet())
        assertEquals(copper.size, copper.map { it.layout }.toSet().size)
    }

    @Test
    fun cabinSetReachesEveryWidgetItCanShow() {
        val cabins = listOf(WidgetDesign.LANE_VIEW, WidgetDesign.TREND, WidgetDesign.PULSE, WidgetDesign.CONTOUR)
        val charts = listOf(WidgetDesign.TREND, WidgetDesign.PULSE, WidgetDesign.CONTOUR)
        assertTrue(cabins.none { it.isSignature })
        // The three charts draw no rows: a list widget would show its headline and nothing to read or tap.
        val lists = setOf(
            BuiltinKind.QUICK_DIAL, BuiltinKind.NOTIFICATIONS, BuiltinKind.CALENDAR, BuiltinKind.OBD_DTC, BuiltinKind.FUEL_PRICES,
            BuiltinKind.DRIVE_HISTORY
        )
        // Time charted over the last minutes is a straight line.
        val clocks = setOf(BuiltinKind.CLOCK, BuiltinKind.BREAK_TIMER)
        BuiltinKind.entries.forEach { kind ->
            val offered = WidgetDesign.offeredFor(kind, framed = false)
            assertTrue("$kind", WidgetDesign.LANE_VIEW in offered)
            charts.forEach { assertEquals("$kind $it", kind !in lists && !(it == WidgetDesign.TREND && kind in clocks), it in offered) }
        }
        // A tile saved with one of them before is still read back; it is drawn as Standard (DesignedTile).
        assertEquals(WidgetDesign.TREND, WidgetDesign.fromName("TREND"))
        assertFalse(WidgetDesign.TREND.appliesTo(BuiltinKind.OBD_DTC))
        assertTrue(WidgetDesign.PULSE.appliesTo(BuiltinKind.CLOCK))
        cabins.forEach { assertEquals(it, WidgetDesign.fromName(it.name)) }
        // One cabin per material and per layout, all in their own picker family.
        assertEquals(cabins.size, cabins.map { it.look }.toSet().size)
        assertEquals(cabins.size, cabins.map { it.layout }.toSet().size)
        assertTrue(cabins.all { DesignFamily.of(it) == DesignFamily.CABINS })
        // The tyre map is only offered on the tyres.
        assertTrue(WidgetDesign.TYRE_MAP.appliesTo(BuiltinKind.TYRES))
        assertFalse(WidgetDesign.TYRE_MAP.appliesTo(BuiltinKind.MEDIA))
    }

    @Test
    fun faceNumberReadsTheHeadline() {
        val face = WidgetFace(Icons.Filled.Speed, "t", "24,5", "km")
        assertEquals(24.5f, faceNumber(face)!!, 0.001f)
        assertEquals(80f, faceNumber(face.copy(value = "Song", textValue = true, fraction = 0.8f))!!, 0.001f)
        assertEquals(42f, faceNumber(face.copy(number = 42f))!!, 0.001f)
        assertNull(faceNumber(face.copy(value = "Song", textValue = true)))
        // No reading yet: the "--" and its fraction of 0 are not a 0 for the chart.
        assertTrue(face.copy(value = "--").idle)
        assertFalse(face.idle)
        assertNull(faceNumber(face.copy(value = "--", fraction = 0f)))
        assertNull(faceNumber(face.copy(value = "--", fraction = 0.8f, number = 0f)))
    }

    @Test
    fun widgetSpecificDesignsOnlyReachTheirWidgets() {
        val framed = setOf(BuiltinKind.NAVMAP, BuiltinKind.PIP_ANCHOR, BuiltinKind.MAPS_INSIDE, BuiltinKind.MY_CAR)
        // Every redrawn widget gets at least two designs made for it; live views get none,
        // and the dashboard bar, all buttons, has no designs at all.
        // The speed keeps one: the road sign went, a number in a red ring reads as the limit.
        BuiltinKind.entries.filter { it !in framed && it != BuiltinKind.DASH_BAR && it != BuiltinKind.CUSTOM_GAUGE }.forEach { kind ->
            val least = if (kind == BuiltinKind.SPEED_HUD) 1 else 2
            assertTrue("$kind", WidgetDesign.entries.count { it.isSignature && it.appliesTo(kind) } >= least)
        }
        assertFalse(WidgetDesign.ROAD_SIGN.appliesTo(BuiltinKind.SPEED_HUD))
        // A retired design's tiles wear its successor.
        assertEquals(WidgetDesign.FACES, WidgetDesign.fromName("ROTARY_PHONE"))
        framed.forEach { kind -> assertTrue(WidgetDesign.offeredFor(kind, framed = true).none { it.isSignature }) }
        assertTrue(WidgetDesign.THERMOMETER.appliesTo(BuiltinKind.WARMUP))
        assertFalse(WidgetDesign.THERMOMETER.appliesTo(BuiltinKind.MEDIA))
        // Picker order: Standard, the widget's own designs, then the generic ones.
        val offered = WidgetDesign.offeredFor(BuiltinKind.WARMUP, framed = false)
        assertEquals(WidgetDesign.STANDARD, offered.first())
        val firstGeneric = offered.indexOfFirst { !it.isSignature && it != WidgetDesign.STANDARD }
        assertTrue(offered.drop(1).take(firstGeneric - 1).all { it.isSignature })
        // A widget-specific design is saved and read back like any other.
        val tank = widget(BuiltinKind.RANGE, 0, 0, 3, 3).copy(design = WidgetDesign.FUEL_TANK)
        val pages = listOf(listOf(tank), emptyList(), emptyList())
        assertEquals(pages, DashboardStore.parsePages(DashboardStore.serializePages(pages)))
    }

    // --- minimum sizes -------------------------------------------------------

    @Test
    fun everyKindsDefaultSizeIsAtLeastItsMinimum() {
        for (kind in BuiltinKind.entries) {
            val (w, h) = kind.minSize()
            assertTrue("$kind default ${kind.defaultW}x${kind.defaultH} under its minimum ${w}x$h", kind.defaultW >= w && kind.defaultH >= h)
            assertTrue("$kind minimum wider than the grid", w <= GRID_COLS && h <= GRID_ROWS)
        }
        // The kinds read at speed never shrink to one cell; a list keeps three columns.
        assertEquals(2 to 2, BuiltinKind.SPEED_HUD.minSize())
        assertEquals(3 to 2, BuiltinKind.QUICK_DIAL.minSize())
        assertEquals(3 to 3, BuiltinKind.NAVMAP.minSize())
    }

    @Test
    fun aSavedTileSmallerThanItsMinimumStillLoadsAtItsSize() {
        // A one-cell speed tile from an older build, at the grid's far corner: kept, not grown into a neighbour or dropped.
        val small = widget(BuiltinKind.SPEED_HUD, GRID_COLS - 1, GRID_ROWS - 1, 1, 1)
        val neighbour = widget(BuiltinKind.CLOCK, GRID_COLS - 3, GRID_ROWS - 2, 2, 1)
        val pages = listOf(listOf(small, neighbour), emptyList(), emptyList())
        val back = DashboardStore.parsePages(DashboardStore.serializePages(pages))!!
        assertEquals(listOf(small, neighbour), back[0])
        assertEquals(listOf(small, neighbour), DashboardStore.repairOverlaps(back[0]))
        // Where it sits there is no room for its kind's minimum, so the resize floor is what is left.
        assertEquals(1, small.minW())
        assertEquals(1, small.minH())
        // In the open its minimum is its kind's.
        assertEquals(2, widget(BuiltinKind.SPEED_HUD, 0, 0, 1, 1).minW())
        // withCell keeps a saved span; the placer passes the minimum itself.
        assertEquals(1, widget(BuiltinKind.SPEED_HUD, 0, 0, 1, 1).withCell(0, 0, 1, 1).w)
    }

    @Test
    fun garbageIsRejectedNotDefaulted() {
        assertNull(DashboardStore.parsePages("not json at all"))
        assertNull(DashboardStore.parsePages("{"))
    }

    // --- layout variants -----------------------------------------------------

    @Test
    fun variantsGetTheirOwnKeysAndBackups() {
        assertEquals("pages", DashboardStore.pagesKey(""))
        assertEquals("pages_backup", DashboardStore.backupKey(""))
        assertEquals("pages_v", DashboardStore.pagesKey("_v"))
        assertEquals("pages_backup_v", DashboardStore.backupKey("_v"))
        // The legacy copies' keys, read once by the merge.
        assertEquals("pages_half", DashboardStore.pagesKey(DashboardStore.LEGACY_HALF))
        // A variant's backup must never collide with another variant's main key.
        assertFalse(DashboardStore.backupKey("") == DashboardStore.pagesKey("_v"))
    }

    // --- one arrangement per screen direction --------------------------------

    @Test
    fun theMergeKeepsTheArrangementLastUsed_whenItDiffersFromTheBase() {
        // The copy the user was on, edited: it becomes the one arrangement.
        assertEquals("half", DashboardStore.chooseKept(base = "base", lastUsed = "half"))
        // A copy never edited (still the base's twin), or none at all: the base stays.
        assertEquals("base", DashboardStore.chooseKept(base = "base", lastUsed = "base"))
        assertEquals("base", DashboardStore.chooseKept(base = "base", lastUsed = null))
        // Only ever used the copy: it is kept.
        assertEquals("canvas", DashboardStore.chooseKept<String>(base = null, lastUsed = "canvas"))
        assertNull(DashboardStore.chooseKept<String>(base = null, lastUsed = null))
    }

    @Test
    fun theLegacySuffixFollowsTheLayoutAndTheme() {
        assertEquals("", DashboardStore.legacySuffix(canvas = false, overApp = false, docked = false))
        assertEquals("_half", DashboardStore.legacySuffix(canvas = false, overApp = false, docked = true))
        assertEquals("_canvas", DashboardStore.legacySuffix(canvas = true, overApp = false, docked = false))
        assertEquals("_canvas_half", DashboardStore.legacySuffix(canvas = true, overApp = false, docked = true))
        assertEquals("_tabs", DashboardStore.legacySuffix(canvas = true, overApp = true, docked = true))
        // An app under the dashboards only counted with Canvas.
        assertEquals("_half", DashboardStore.legacySuffix(canvas = false, overApp = true, docked = true))
    }

    @Test
    fun turningTheScreenReflowsEveryTileIntoTheNewGrid_droppingNone() {
        // The Daily template on a wide screen (12 x 7), on every page.
        val wide = TemplatePlacer.pages(
            DashTemplate.DAILY,
            TemplateScreen.of(1280f, 576f, obdPaired = true, driverOnRight = false, mapsDocked = false, dockApps = listOf("a.b", "c.d"))
        )
        val before = wide.sumOf { it.size }
        ScreenShape.vertical = true
        try {
            // Upright the grid is 7 x 12: every tile finds a place, in bounds, apart from the others.
            val upright = DashboardStore.reflow(wide)
            assertEquals(DashboardStore.PAGE_COUNT, upright.size)
            assertEquals(before, upright.sumOf { it.size })
            upright.forEach { page ->
                page.forEach { t ->
                    assertTrue("$t out of the 7x12 grid", t.x >= 0 && t.y >= 0 && t.x + t.w <= GRID_COLS && t.y + t.h <= GRID_ROWS)
                    assertTrue("$t under its minimum", t.w >= t.minW() && t.h >= t.minH())
                }
                noOverlaps(page)
            }
            // A tile keeps its page when its page has room; the Home page's kinds are still Home's.
            val homeKinds = { pages: List<List<DashboardItem>> -> pages[DashboardStore.CENTER].filterIsInstance<DashboardItem.BuiltinWidget>().map { it.kind }.toSet() }
            assertEquals(homeKinds(wide), homeKinds(upright))
            // The dock keeps its apps.
            assertEquals(listOf("a.b", "c.d"), upright[DashboardStore.CENTER].filterIsInstance<DashboardItem.LaunchBar>().single().packages)
        } finally {
            ScreenShape.vertical = false
        }
    }

    @Test
    fun reflowSendsATileWithNoRoomLeftToTheNearestPage() {
        // A page packed with 1x1 tiles beyond what the grid holds the other way up cannot happen,
        // but a page of wide tiles can: twelve 12x1 strips on a wide screen are too many for 7 rows... as 7x1 strips they need 12 rows.
        // Here: eight full-width strips of a kind whose minimum is 2x1 become 7-wide strips; 12 rows hold them all on their page.
        val strips = List(8) { i -> DashboardItem.BuiltinWidget(BuiltinKind.AUDIO, 0, i.coerceAtMost(GRID_ROWS - 1), GRID_COLS, 1) }
        val pages = List(DashboardStore.PAGE_COUNT) { if (it == 0) strips else emptyList() }
        ScreenShape.vertical = true
        try {
            val out = DashboardStore.reflow(pages)
            assertEquals(8, out.sumOf { it.size })
            // Fourteen tall 1x7 columns on the wide grid: upright (7 across) only seven fit at full height, and the
            // placer shrinks the rest to what fits rather than losing one; none ends up dropped.
            val columns = List(14) { i -> DashboardItem.BuiltinWidget(BuiltinKind.AUDIO, i.coerceAtMost(GRID_COLS - 1), 0, 1, 7) }
            val tall = DashboardStore.reflow(List(DashboardStore.PAGE_COUNT) { if (it == 2) columns else emptyList() })
            assertEquals(14, tall.sumOf { it.size })
            tall.forEach(::noOverlaps)
        } finally {
            ScreenShape.vertical = false
        }
    }

    @Test
    fun parsingOneVariantDoesNotLeakUnknownTilesIntoAnother() {
        val withStranger = """{"v":1,"pages":[[{"t":"hologram","gx":0,"gy":0,"gw":3,"gh":2}],[],[]]}"""
        DashboardStore.parsePages(withStranger)
        // The next layout parsed (another variant) starts clean.
        DashboardStore.parsePages("""{"v":1,"pages":[[],[],[]]}""")
        val saved = DashboardStore.serializePages(List(DashboardStore.PAGE_COUNT) { emptyList() })
        assertFalse(saved.contains("hologram"))
    }
}
