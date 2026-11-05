package com.openauto.dash

import com.openauto.dash.vehicle.Domain
import com.openauto.dash.vehicle.K
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The Custom gauge tile's pure parts: its settings, the signal list, scales and figures. */
class CustomGaugeTest {

    @Before
    fun resetRetained() {
        DashboardStore.parsePages("[]")
    }

    // --- per-copy settings ---------------------------------------------------

    @Test
    fun settingsRoundTripThroughTheirString() {
        val c = GaugeConfig("battery_volts", GaugeStyle.BAR_H, 10.5, 15.0)
        assertEquals(c, GaugeConfig.decode(c.encode()))
        // The defaults stay blank in the string.
        val d = GaugeConfig("rpm", GaugeStyle.NUMBER)
        assertEquals("g2|rpm|NUMBER||||0", d.encode())
        assertEquals(d, GaugeConfig.decode(d.encode()))
        assertEquals(GaugeConfig(), GaugeConfig.decode(GaugeConfig().encode()))
        assertEquals(GaugeConfig("steering_angle", GaugeStyle.DIAL, -540.0, 540.0), GaugeConfig.decode("g1|steering_angle|DIAL|-540|540"))
        // The red zone travels with it.
        val red = GaugeConfig("battery_volts", GaugeStyle.BAR_H, 9.0, 16.0, red = 11.8, redBelow = true)
        assertEquals(red, GaugeConfig.decode(red.encode()))
        assertEquals("g2|rpm|DIAL|||6000|0", GaugeConfig("rpm", red = 6000.0).encode())
    }

    @Test
    fun theFirstFormatStillReadsWithoutAZone() {
        val old = GaugeConfig.decode("g1|battery_volts|BAR_H|10|15")
        assertEquals(GaugeConfig("battery_volts", GaugeStyle.BAR_H, 10.0, 15.0), old)
        assertNull(old.red)
        assertFalse(old.redBelow)
        // A g1 string never carries a zone, even with stray fields.
        assertNull(GaugeConfig.decode("g1|rpm|DIAL|||6000|1").red)
    }

    @Test
    fun theRedZoneRunsFromTheThresholdOrUpToIt() {
        val above = GaugeConfig("rpm", min = 0.0, max = 8000.0, red = 6000.0)
        assertEquals(GaugeRedZone(0.75f, 1f), redZone(above, 0.0, 8000.0))
        assertTrue(inRedZone(6000.0, above) && inRedZone(7000.0, above))
        assertFalse(inRedZone(5999.0, above))
        val below = GaugeConfig("battery_volts", red = 12.0, redBelow = true)
        assertEquals(GaugeRedZone(0f, 0.5f), redZone(below, 10.0, 14.0))
        assertTrue(inRedZone(11.0, below) && inRedZone(12.0, below))
        assertFalse(inRedZone(12.1, below))
        // No value, no red; no threshold, no zone.
        assertFalse(inRedZone(null, above))
        assertNull(redZone(GaugeConfig("rpm"), 0.0, 8000.0))
        assertFalse(inRedZone(9999.0, GaugeConfig("rpm")))
        // A threshold off the scale draws nothing.
        assertNull(redZone(GaugeConfig("rpm", red = 9000.0, redBelow = true).copy(redBelow = false, red = 8000.0), 0.0, 8000.0))
        assertNull(redZone(GaugeConfig("rpm", red = 0.0, redBelow = true), 0.0, 8000.0))
        assertEquals(GaugeRedZone(0.5f, 1f), redZone(GaugeConfig("x", red = 12.0), 10.0, 14.0))
    }

    @Test
    fun onlyTheUsualAlarmsStartWithARedZone() {
        assertEquals(6000.0 to false, defaultGaugeRed("rpm"))
        assertEquals(105.0 to false, defaultGaugeRed("coolant_temp"))
        assertEquals(11.8 to true, defaultGaugeRed("battery_volts"))
        assertNull(defaultGaugeRed("vehicle_speed"))
        assertNull(defaultGaugeRed(null))
    }

    @Test
    fun aGaugeTileCanBeOneRowHigh() {
        assertEquals(2 to 1, BuiltinKind.CUSTOM_GAUGE.minSize())
    }

    @Test
    fun unreadableSettingsAreTheBlankOne() {
        assertEquals(GaugeConfig(), GaugeConfig.decode(null))
        assertEquals(GaugeConfig(), GaugeConfig.decode(""))
        assertEquals(GaugeConfig(), GaugeConfig.decode("nonsense"))
        // A newer build's format, and a style this build lacks.
        assertEquals(GaugeConfig(), GaugeConfig.decode("g3|rpm|DIAL||"))
        assertEquals(GaugeConfig("rpm", GaugeStyle.DIAL), GaugeConfig.decode("g1|rpm|HOLOGRAM||"))
        assertEquals(GaugeConfig("rpm", GaugeStyle.BAR_V, null, null), GaugeConfig.decode("g1|rpm|BAR_V|abc|NaN"))
    }

    @Test
    fun eachCopyKeepsItsOwnSettingsInTheLayout() {
        val speed = GaugeConfig("speed", GaugeStyle.DIAL).encode()
        val volts = GaugeConfig("battery_volts", GaugeStyle.BAR_H, 10.0, 15.0).encode()
        val a = DashboardItem.BuiltinWidget(BuiltinKind.CUSTOM_GAUGE, 0, 0, 3, 3, opts = speed)
        val b = DashboardItem.BuiltinWidget(BuiltinKind.CUSTOM_GAUGE, 3, 0, 3, 3, opts = volts)
        val plain = DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, 6, 0, 3, 2)
        val pages = listOf(listOf(a, b, plain), emptyList(), emptyList())
        val back = DashboardStore.parsePages(DashboardStore.serializePages(pages))!!
        assertEquals(pages, back)
        assertEquals(GaugeConfig("battery_volts", GaugeStyle.BAR_H, 10.0, 15.0), GaugeConfig.decode((back[0][1] as DashboardItem.BuiltinWidget).opts))
        // A tile with no settings writes none: older builds and other kinds read the same layout.
        assertFalse(DashboardStore.serializePages(listOf(listOf(plain), emptyList(), emptyList())).contains("\"o\""))
    }

    // --- the signal list -----------------------------------------------------

    @Test
    fun theListHoldsEveryNumericKeyAndNoState() {
        val keys = gaugeKeys()
        assertTrue(K.RPM in keys && K.BATTERY_VOLTS in keys && K.ODOMETER in keys && K.TRIP_DISTANCE in keys)
        // The first-available ones count, and their members too.
        assertTrue(K.SPEED in keys && K.AMBIENT_TEMP in keys && K.VEHICLE_SPEED in keys)
        // Gear, doors, lights and the rest have no display string on the bus.
        assertTrue(listOf(K.GEAR, K.DOORS, K.LIGHTS, K.TYRES, K.CLIMATE, K.RADAR, K.IGNITION, K.ENGINE_RUNNING).none { it in keys })
        // It is the catalogue: nothing a key is added to is left out.
        assertEquals(K.all.count { it.isGaugeNumeric() }, keys.size)
        assertEquals(keys.size, keys.map { it.id }.toSet().size)
    }

    @Test
    fun keysAreFoundByIdAndAnUnknownOneIsNull() {
        assertEquals(K.COOLANT_TEMP, gaugeKeyById("coolant_temp"))
        assertNull(gaugeKeyById("no_such_key"))
        assertNull(gaugeKeyById(null))
        // Over a list of its own: a key a branch adds is found the same way.
        assertEquals(K.RPM, gaugeKeyById("rpm", listOf(K.RPM)))
        assertNull(gaugeKeyById("coolant_temp", listOf(K.RPM)))
    }

    @Test
    fun theGroupsFollowReadingOrderAndSortByName() {
        val groups = groupGaugeKeys(gaugeKeys(), name = { humanizeKeyId(it.id) }, tag = { it })
        assertEquals(Domain.MOTION, groups.first().first)
        val domains = groups.map { it.first }
        assertEquals(domains.toSet().size, domains.size)
        for ((_, ks) in groups) {
            val names = ks.map { humanizeKeyId(it.id) }
            assertEquals(names.sortedWith(String.CASE_INSENSITIVE_ORDER), names)
        }
        // Every key is in exactly one group.
        assertEquals(gaugeKeys().size, groups.sumOf { it.second.size })
    }

    @Test
    fun aKeyWithoutAWrittenNameReadsAsWords() {
        assertEquals("Fuel litres", humanizeKeyId("fuel_litres"))
        assertEquals("Rpm", humanizeKeyId("rpm"))
        assertEquals("Instrument dimmer", humanizeKeyId("instrument_dimmer"))
    }

    // --- scales ----------------------------------------------------------------

    @Test
    fun everyNumericKeyHasASensibleDefaultScale() {
        for (k in gaugeKeys()) {
            val (lo, hi) = defaultGaugeRange(k.id, k.unit)
            assertTrue("${k.id}: $lo..$hi", hi > lo)
        }
        assertEquals(0.0 to 240.0, defaultGaugeRange("vehicle_speed", "km/h"))
        assertEquals(9.0 to 16.0, defaultGaugeRange("battery_volts", "V"))
        assertEquals(40.0 to 130.0, defaultGaugeRange("coolant_temp", "°C"))
        // A key nobody knows takes its unit's scale, and a unit nobody knows 0..100.
        assertEquals(0.0 to 100.0, defaultGaugeRange("new_key", "%"))
        assertEquals(0.0 to 30.0, defaultGaugeRange("new_key", "L/h"))
        assertEquals(0.0 to 100.0, defaultGaugeRange("new_key", "furlongs"))
        assertEquals(0.0 to 100.0, defaultGaugeRange(null, null))
    }

    @Test
    fun theDriversEndsBeatTheDefaultsWhenTheyMakeAScale() {
        val k = "battery_volts"
        assertEquals(9.0 to 16.0, gaugeRange(GaugeConfig(k), "V"))
        assertEquals(10.0 to 15.0, gaugeRange(GaugeConfig(k, min = 10.0, max = 15.0), "V"))
        // One end set: the other stays the default.
        assertEquals(11.0 to 16.0, gaugeRange(GaugeConfig(k, min = 11.0), "V"))
        assertEquals(9.0 to 14.0, gaugeRange(GaugeConfig(k, max = 14.0), "V"))
        // An upside-down or empty scale falls back whole.
        assertEquals(9.0 to 16.0, gaugeRange(GaugeConfig(k, min = 20.0, max = 12.0), "V"))
        assertEquals(9.0 to 16.0, gaugeRange(GaugeConfig(k, min = 12.0, max = 12.0), "V"))
        // A min above the default max alone would make one too.
        assertEquals(9.0 to 16.0, gaugeRange(GaugeConfig(k, min = 20.0), "V"))
    }

    @Test
    fun theFractionIsWhereTheValueSitsAndNullWithoutOne() {
        assertEquals(0.5f, gaugeFraction(120.0, 0.0, 240.0)!!, 1e-6f)
        assertEquals(0f, gaugeFraction(-5.0, 0.0, 240.0)!!, 1e-6f)
        assertEquals(1f, gaugeFraction(500.0, 0.0, 240.0)!!, 1e-6f)
        assertEquals(0.5f, gaugeFraction(0.0, -540.0, 540.0)!!, 1e-6f)
        assertNull(gaugeFraction(null, 0.0, 240.0))
        assertNull(gaugeFraction(Double.NaN, 0.0, 240.0))
        assertNull(gaugeFraction(1.0, 5.0, 5.0))
    }

    // --- figures and units -----------------------------------------------------

    private val metric = UnitSystem()
    private val us = UnitSystem(imperial = true, fahrenheit = true, economy = Economy.MPG_US)
    private val en = Locale.US

    @Test
    fun noDataIsTwoDashes() {
        assertEquals("--", formatGaugeValue(null, gaugeUnit("km/h", metric), en))
        assertEquals("--", formatGaugeValue(Double.NaN, gaugeUnit("V", metric), en))
        // Economy has no figure at a standstill in the inverse units.
        assertEquals("--", formatGaugeValue(0.0, gaugeUnit("L/100 km", us), en))
    }

    @Test
    fun metricFiguresAreAsTheCarSaysThem() {
        assertEquals("87", formatGaugeValue(86.6, gaugeUnit("km/h", metric), en))
        assertEquals("km/h", gaugeUnit("km/h", metric).label)
        assertEquals("13.8", formatGaugeValue(13.84, gaugeUnit("V", metric), en))
        assertEquals("V", gaugeUnit("V", metric).label)
        assertEquals("3200", formatGaugeValue(3200.2, gaugeUnit("rpm", metric), en))
        assertEquals("92", formatGaugeValue(91.5, gaugeUnit("°C", metric), en))
        assertEquals("°C", gaugeUnit("°C", metric).label)
        assertEquals("9.4", formatGaugeValue(9.43, gaugeUnit("L/100 km", metric), en))
        assertEquals("-12", formatGaugeValue(-12.0, gaugeUnit("°", metric), en))
        // A small negative that rounds away reads 0.
        assertEquals("0", formatGaugeValue(-0.2, gaugeUnit("°", metric), en))
    }

    @Test
    fun theUnitsFollowTheAppsChoices() {
        assertEquals("62", formatGaugeValue(100.0, gaugeUnit("km/h", us), en))
        assertEquals("mph", gaugeUnit("km/h", us).label)
        assertEquals("62", formatGaugeValue(100.0, gaugeUnit("km", us), en))
        assertEquals("mi", gaugeUnit("km", us).label)
        assertEquals("212", formatGaugeValue(100.0, gaugeUnit("°C", us), en))
        assertEquals("°F", gaugeUnit("°C", us).label)
        assertEquals("-40", formatGaugeValue(-40.0, gaugeUnit("°C", us), en))
        assertEquals("mpg", gaugeUnit("L/100 km", us).label)
        assertEquals("25.0", formatGaugeValue(9.4, gaugeUnit("L/100 km", us), en))
        // Volts and revs never convert.
        assertEquals("13.8", formatGaugeValue(13.84, gaugeUnit("V", us), en))
        assertEquals("rpm", gaugeUnit("rpm", us).label)
    }

    @Test
    fun pressureFollowsTheTyrePressureUnit() {
        assertEquals("2.3", formatGaugeValue(230.0, gaugeUnit("kPa", metric, PressureUnit.BAR), en))
        assertEquals("bar", gaugeUnit("kPa", metric, PressureUnit.BAR).label)
        assertEquals("230", formatGaugeValue(230.0, gaugeUnit("kPa", metric, PressureUnit.KPA), en))
        assertEquals("33", formatGaugeValue(230.0, gaugeUnit("kPa", metric, PressureUnit.PSI), en))
        assertEquals("psi", gaugeUnit("kPa", metric, PressureUnit.PSI).label)
    }

    @Test
    fun anUnknownUnitIsShownAsIsWithADecimalUnderAHundred() {
        val u = gaugeUnit("furlongs", metric)
        assertEquals("furlongs", u.label)
        assertEquals("12.5", formatGaugeValue(12.5, u, en))
        assertEquals("150", formatGaugeValue(150.4, u, en))
        assertEquals("", gaugeUnit(null, metric).label)
    }

    // --- the editor's fields ---------------------------------------------------

    @Test
    fun theScaleEndsAreTypedInTheShownUnitAndStoredInTheKeys() {
        val f = gaugeUnit("°C", us)
        // 212 °F typed is 100 °C stored, and shown back as typed.
        assertEquals(100.0, parseGaugeEnd("212", f)!!, 1e-9)
        assertEquals("212", formatGaugeEnd(100.0, f))
        assertEquals(37.5, parseGaugeEnd("37,5", gaugeUnit("°C", metric))!!, 1e-9)
        assertEquals("37.5", formatGaugeEnd(37.5, gaugeUnit("°C", metric)))
        assertEquals("120", formatGaugeEnd(120.0, gaugeUnit("km/h", metric)))
        val mph = gaugeUnit("km/h", us)
        assertEquals(160.9344, parseGaugeEnd("100", mph)!!, 1e-6)
        assertNull(parseGaugeEnd("", f))
        assertNull(parseGaugeEnd("abc", f))
        assertNull(parseGaugeEnd("NaN", f))
    }

    @Test
    fun aNonLinearUnitKeepsTheKeysOwnFigureInTheFields() {
        // mpg is not a straight line from L/100 km: the scale stays in L/100 km.
        val mpg = gaugeUnit("L/100 km", us)
        assertNull(mpg.fromShown)
        assertEquals(15.0, parseGaugeEnd("15", mpg)!!, 1e-9)
        assertEquals("15", formatGaugeEnd(15.0, mpg))
        assertNotEquals("15", formatGaugeValue(15.0, mpg, en))
    }
}
