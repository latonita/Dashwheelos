package com.openauto.unitproto.canbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** A whole frame as the spec's test-vector table prints it; parsing it also proves the vector's checksum. */
private fun frame(hex: String): CanFrame {
    val raw = hex.trim().split(" ").map { it.toInt(16) }.toIntArray()
    return HiworldFraming.parse(bytes(*raw)) ?: error("bad checksum or framing: $hex")
}

private val decoder = Jpf20lDecoder()

private fun decode(hex: String): List<CanboxValue> = decoder.decode(frame(hex))

private inline fun <reified T : CanboxValue> List<CanboxValue>.one(): T = filterIsInstance<T>().single()

/** A STATE payload (S0..S6) as a frame. */
private fun state(s0: Int, s1: Int = 0, s2: Int = 0, s3: Int = 0, s4: Int = 0, s5: Int = 0xFF, s6: Int = 0xFF) =
    decoder.decode(CanFrame(Jpf20lCmd.STATE, bytes(s0, s1, s2, s3, s4, s5, s6)))

/** The telemetry-v2 layout (`TELEMETRY-V2-SPEC.md`), byte by byte, with the spec's section 10 vectors. */
class Jpf20lDecoderTest {

    // ------------------------------------------------------------------ the spec's vectors

    @Test
    fun everySpecVector_hasAValidChecksum() {
        for (v in listOf(
            "AA 55 02 E0 00 00 E1",
            "AA 55 04 B0 02 01 BD 2A 9D",
            "AA 55 04 B0 02 01 BD 00 73",
            "AA 55 0A B3 02 76 0A 8D 37 04 B0 3C 04 80 76",
            "AA 55 0A B3 00 00 02 44 0A 00 64 28 03 F0 8B",
            "AA 55 0A B3 FF FF FF FF FF FF FF FF FF FF B2",
            "AA 55 07 B2 2B 04 19 10 01 03 FF 13",
            "AA 55 07 B2 23 04 19 10 01 03 FD 09",
            "AA 55 07 B2 A1 00 00 00 00 FF A1 F9",
            "AA 55 05 B4 85 8F 5C 02 C6 F0",
            "AA 55 05 B4 FF 8F 5C 02 C6 6A",
            "AA 55 03 B5 00 FA 0A BB",
            "AA 55 03 B5 FF B5 FF 6A",
            "AA 55 06 B8 12 D6 87 06 01 0A 3D",
            "AA 55 06 B8 12 D6 87 00 00 00 2C",
            "AA 55 06 B8 FF FF FF FF FF FF B7"
        )) assertNotNull(v, frame(v))
    }

    @Test
    fun heartbeat_isInformationOnly() {
        val hb = decode("AA 55 04 B0 02 01 BD 2A 9D").single() as CanboxValue.Heartbeat
        assertEquals(CanboxValue.Heartbeat(layout = 2, capabilities = 0x01BD, counter = 0x2A), hb)
        assertEquals(0, (decode("AA 55 04 B0 02 01 BD 00 73").single() as CanboxValue.Heartbeat).counter)
        // The capability bitmap: bit n = the build sends 0xB0 + n.
        for (cmd in Jpf20lCmd.DECODED) assertTrue(hb.capabilities and (1 shl (cmd - 0xB0)) != 0)
        assertTrue(decoder.decode(CanFrame(Jpf20lCmd.HEARTBEAT, bytes(2, 1))).isEmpty())
    }

    @Test
    fun fast_63kmh_2701rpm() {
        val v = decode("AA 55 0A B3 02 76 0A 8D 37 04 B0 3C 04 80 76")
        assertEquals(CanboxValue.SpeedKmh(63), v.one<CanboxValue.SpeedKmh>())
        assertEquals(CanboxValue.Rpm(2701), v.one<CanboxValue.Rpm>())
        assertEquals(CanboxValue.EngineLoadPct(55), v.one<CanboxValue.EngineLoadPct>())
        assertEquals(12.0, v.one<CanboxValue.FuelFlowLph>().lph!!, 1e-9)
        assertEquals(240.0, v.one<CanboxValue.OilPressureKpa>().kpa!!, 1e-9)
        assertEquals(1152.0, v.one<CanboxValue.ManifoldHpa>().hpa!!, 1e-9)
    }

    @Test
    fun fast_idle_zeroIsARealReading() {
        val v = decode("AA 55 0A B3 00 00 02 44 0A 00 64 28 03 F0 8B")
        assertEquals(CanboxValue.SpeedKmh(0), v.one<CanboxValue.SpeedKmh>())
        assertEquals(CanboxValue.Rpm(580), v.one<CanboxValue.Rpm>())
        assertEquals(CanboxValue.EngineLoadPct(10), v.one<CanboxValue.EngineLoadPct>())
        assertEquals(1.0, v.one<CanboxValue.FuelFlowLph>().lph!!, 1e-9)
        assertEquals(160.0, v.one<CanboxValue.OilPressureKpa>().kpa!!, 1e-9)
        assertEquals(1008.0, v.one<CanboxValue.ManifoldHpa>().hpa!!, 1e-9)
    }

    @Test
    fun fast_everyFieldUnavailable_isSixWithdrawals() {
        val v = decode("AA 55 0A B3 FF FF FF FF FF FF FF FF FF FF B2")
        assertEquals(
            listOf<CanboxValue>(
                CanboxValue.SpeedKmh(null), CanboxValue.Rpm(null), CanboxValue.EngineLoadPct(null),
                CanboxValue.FuelFlowLph(null), CanboxValue.OilPressureKpa(null), CanboxValue.ManifoldHpa(null)
            ),
            v
        )
    }

    @Test
    fun fast_zeroLoadFlowAndOilAreReadings_notBlanks() {
        val v = decoder.decode(CanFrame(Jpf20lCmd.FAST, bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(0, v.one<CanboxValue.EngineLoadPct>().pct)
        assertEquals(0.0, v.one<CanboxValue.FuelFlowLph>().lph!!, 0.0)
        assertEquals(0.0, v.one<CanboxValue.OilPressureKpa>().kpa!!, 0.0)
        assertEquals(0, v.one<CanboxValue.Rpm>().rpm)
    }

    @Test
    fun fast_speedRoundsToWholeKmh() {
        fun kmh(deci: Int) = Jpf20lSignals.speedKmh(bytes(deci shr 8, deci and 0xFF))
        assertEquals(63, kmh(630)); assertEquals(63, kmh(625)); assertEquals(62, kmh(624)); assertEquals(0, kmh(4))
        assertNull(kmh(0xFFFF))
        assertNull(kmh(4001))
    }

    @Test
    fun fast_saturatedBoostIsKept_flowAboveTheBoundIsDropped() {
        assertEquals(2032.0, Jpf20lSignals.manifoldHpa(bytes(0, 0, 0, 0, 0, 0, 0, 0, 0x07, 0xF0))!!, 0.0)
        assertEquals(512.0, Jpf20lSignals.fuelFlowLph(bytes(0, 0, 0, 0, 0, 0xC8, 0x00))!!, 0.0)
        assertNull(Jpf20lSignals.fuelFlowLph(bytes(0, 0, 0, 0, 0, 0xC8, 0x01)))
    }

    // ------------------------------------------------------------------ STATE

    @Test
    fun state_allAlive_onEngineKeyDoorLampsMilFourHighGearD() {
        val v = decode("AA 55 07 B2 2B 04 19 10 01 03 FF 13")
        assertEquals(IgnitionPosition.ON, v.one<CanboxValue.Ignition>().position)
        assertEquals(true, v.one<CanboxValue.EngineRunning>().running)
        assertEquals(false, v.one<CanboxValue.AccessoryPower>().on)
        assertEquals(true, v.one<CanboxValue.KeyIn>().on)
        assertEquals(false, v.one<CanboxValue.Reverse>().on)
        assertEquals(false, v.one<CanboxValue.ParkingBrake>().on)
        assertEquals(false, v.one<CanboxValue.BrakePedal>().on)
        assertEquals(false, v.one<CanboxValue.DriverBelt>().unfastened)
        // S1 = 04 is bit 2 = door FL in the spec's table (bit 2 = FL, 3 = FR, 4 = RR, 5 = RL).
        assertEquals(
            DoorBits(frontLeft = true, frontRight = false, rearLeft = false, rearRight = false, tailgate = false, bonnet = false),
            v.one<CanboxValue.Doors>().doors
        )
        assertEquals(
            LightBits(left = true, right = false, hazard = false, position = true, lowBeam = true, highBeam = false, frontFog = false, rearFog = false),
            v.one<CanboxValue.BodyLights>().bits
        )
        assertEquals(CruiseState.OFF, v.one<CanboxValue.Cruise>().state)
        assertEquals(false, v.one<CanboxValue.AcCompressor>().on)
        assertEquals(setOf(Telltale.MIL), v.one<CanboxValue.Telltales>().lit)
        assertEquals(TransferCaseRange.HIGH, v.one<CanboxValue.TransferCase>().range)
        assertEquals(CanboxValue.Gear(GearPosition.DRIVE), v.one<CanboxValue.Gear>())
    }

    @Test
    fun state_002Dead_engineRunningIsWithdrawn_notFalse() {
        val v = decode("AA 55 07 B2 23 04 19 10 01 03 FD 09")
        assertNull(v.one<CanboxValue.EngineRunning>().running)
        // The others are untouched.
        assertEquals(IgnitionPosition.ON, v.one<CanboxValue.Ignition>().position)
        assertEquals(true, v.one<CanboxValue.KeyIn>().on)
        assertEquals(CanboxValue.Gear(GearPosition.DRIVE), v.one<CanboxValue.Gear>())
    }

    @Test
    fun state_keyOff_parkingBrakeOn_everyOtherSourceDead() {
        val v = decode("AA 55 07 B2 A1 00 00 00 00 FF A1 F9")
        assertEquals(IgnitionPosition.KEY_OFF, v.one<CanboxValue.Ignition>().position)
        assertEquals(true, v.one<CanboxValue.KeyIn>().on)
        assertEquals(true, v.one<CanboxValue.ParkingBrake>().on)
        // 002, 003, 006, 013, 1B2 are dead: all their values are withdrawals.
        assertNull(v.one<CanboxValue.EngineRunning>().running)
        assertNull(v.one<CanboxValue.AccessoryPower>().on)
        assertNull(v.one<CanboxValue.Reverse>().on)
        assertNull(v.one<CanboxValue.BrakePedal>().on)
        assertNull(v.one<CanboxValue.DriverBelt>().unfastened)
        assertNull(v.one<CanboxValue.Doors>().doors)
        assertNull(v.one<CanboxValue.BodyLights>().bits)
        assertNull(v.one<CanboxValue.Cruise>().state)
        assertNull(v.one<CanboxValue.AcCompressor>().on)
        assertNull(v.one<CanboxValue.Telltales>().lit)
        assertNull(v.one<CanboxValue.TransferCase>().range)
        assertEquals(CanboxValue.Gear(null), v.one<CanboxValue.Gear>())
    }

    @Test
    fun state_eachSourceBitGatesExactlyItsFields() {
        // Every data bit set, every source alive but one.
        fun without(src: Int) = state(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x03, 0xFF and src.inv())
        with(without(Jpf20lSignals.SRC_IGNITION)) { assertNull(one<CanboxValue.Ignition>().position); assertNotNull(one<CanboxValue.KeyIn>().on) }
        with(without(Jpf20lSignals.SRC_ENGINE)) { assertNull(one<CanboxValue.EngineRunning>().running); assertNotNull(one<CanboxValue.Reverse>().on) }
        with(without(Jpf20lSignals.SRC_ASSIST)) {
            assertNull(one<CanboxValue.Reverse>().on); assertNull(one<CanboxValue.BrakePedal>().on)
            assertNull(one<CanboxValue.Cruise>().state); assertNull(one<CanboxValue.AcCompressor>().on)
            assertNull(one<CanboxValue.Telltales>().lit); assertNull(one<CanboxValue.Gear>().position)
            assertNotNull(one<CanboxValue.DriverBelt>().unfastened)
        }
        with(without(Jpf20lSignals.SRC_LIGHTS)) { assertNull(one<CanboxValue.BodyLights>().bits); assertNull(one<CanboxValue.AccessoryPower>().on) }
        with(without(Jpf20lSignals.SRC_BODY)) { assertNull(one<CanboxValue.DriverBelt>().unfastened); assertNull(one<CanboxValue.Doors>().doors) }
        with(without(Jpf20lSignals.SRC_PARK_BRAKE)) { assertNull(one<CanboxValue.ParkingBrake>().on) }
        with(without(Jpf20lSignals.SRC_4WD)) { assertNull(one<CanboxValue.TransferCase>().range); assertNotNull(one<CanboxValue.Telltales>().lit) }
        with(without(Jpf20lSignals.SRC_KEY)) { assertNull(one<CanboxValue.KeyIn>().on); assertNotNull(one<CanboxValue.ParkingBrake>().on) }
    }

    @Test
    fun state_s0_bitsMapToTheirFields() {
        with(state(0x03 or 0x08 or 0x10 or 0x20 or 0x40 or 0x80)) {
            assertEquals(true, one<CanboxValue.EngineRunning>().running)
            assertEquals(true, one<CanboxValue.AccessoryPower>().on)
            assertEquals(true, one<CanboxValue.KeyIn>().on)
            assertEquals(true, one<CanboxValue.Reverse>().on)
            assertEquals(true, one<CanboxValue.ParkingBrake>().on)
        }
        with(state(0x03)) {
            assertEquals(false, one<CanboxValue.EngineRunning>().running)
            assertEquals(false, one<CanboxValue.AccessoryPower>().on)
            assertEquals(false, one<CanboxValue.KeyIn>().on)
            assertEquals(false, one<CanboxValue.Reverse>().on)
            assertEquals(false, one<CanboxValue.ParkingBrake>().on)
        }
    }

    @Test
    fun state_ignitionEnum_andAnUnknownCode() {
        val want = mapOf(
            0 to IgnitionPosition.NO_KEY, 1 to IgnitionPosition.KEY_OFF, 2 to IgnitionPosition.ACC,
            3 to IgnitionPosition.ON, 4 to IgnitionPosition.CRANK, 5 to null, 6 to null, 7 to null
        )
        for ((code, pos) in want) assertEquals("code $code", pos, state(code).one<CanboxValue.Ignition>().position)
    }

    @Test
    fun state_gear_everyCode() {
        fun g(code: Int) = state(0, s5 = code).one<CanboxValue.Gear>()
        assertEquals(CanboxValue.Gear(GearPosition.PARK), g(0))
        assertEquals(CanboxValue.Gear(GearPosition.REVERSE), g(1))
        assertEquals(CanboxValue.Gear(GearPosition.NEUTRAL), g(2))
        assertEquals(CanboxValue.Gear(GearPosition.DRIVE), g(3))
        // AutoStick 1..4 keeps the digit.
        for (n in 1..4) assertEquals(CanboxValue.Gear(GearPosition.MANUAL, n), g(3 + n))
        assertEquals(CanboxValue.Gear(null), g(0xFF))
        assertEquals(CanboxValue.Gear(null), g(8))
    }

    @Test
    fun state_transferCase_enum() {
        fun t(code: Int) = state(0, s4 = code).one<CanboxValue.TransferCase>().range
        assertEquals(TransferCaseRange.HIGH, t(1))
        assertEquals(TransferCaseRange.LOW, t(2))
        assertEquals(TransferCaseRange.SHIFTING, t(3))
        assertNull(t(0))
        assertNull(t(7))
        // The other S4 bits do not leak into the range.
        assertEquals(TransferCaseRange.HIGH, t(0x39))
    }

    @Test
    fun state_indicators_hazardAndLamps() {
        fun l(s2: Int) = state(0, s2 = s2).one<CanboxValue.BodyLights>().bits!!
        val hazard = l(0x07)
        assertTrue(hazard.hazard && hazard.left && hazard.right)
        val right = l(0x02)
        assertTrue(right.right && !right.left && !right.hazard)
        val all = l(0xF8)
        assertTrue(all.position && all.lowBeam && all.highBeam && all.frontFog && all.rearFog && !all.left && !all.hazard)
        val none = l(0)
        assertTrue(!none.left && !none.right && !none.hazard && !none.position && !none.rearFog)
    }

    @Test
    fun state_body_doorsBeltBrake() {
        val v = state(0, s1 = 0x7F)
        assertEquals(true, v.one<CanboxValue.BrakePedal>().on)
        assertEquals(true, v.one<CanboxValue.DriverBelt>().unfastened)
        assertEquals(DoorBits(true, true, true, true, true, bonnet = false), v.one<CanboxValue.Doors>().doors)
        // FL, FR, RR, RL are bits 2, 3, 4, 5; the hatch is bit 6.
        fun d(bit: Int) = state(0, s1 = bit).one<CanboxValue.Doors>().doors!!
        assertTrue(d(0x04).frontLeft); assertTrue(d(0x08).frontRight)
        assertTrue(d(0x10).rearRight); assertTrue(d(0x20).rearLeft); assertTrue(d(0x40).tailgate)
        // The bonnet has no source: never open.
        assertTrue(!state(0, s1 = 0xFF).one<CanboxValue.Doors>().doors!!.bonnet)
    }

    @Test
    fun state_assist_cruiseAcAndTelltales() {
        fun c(s3: Int) = state(0, s3 = s3).one<CanboxValue.Cruise>().state
        assertEquals(CruiseState.OFF, c(0))
        assertEquals(CruiseState.ARMED, c(0x01))
        assertEquals(CruiseState.ENGAGED, c(0x03))
        assertEquals(CruiseState.ENGAGED, c(0x02))
        assertEquals(true, state(0, s3 = 0x04).one<CanboxValue.AcCompressor>().on)
        assertEquals(false, state(0, s3 = 0xFB).one<CanboxValue.AcCompressor>().on)

        assertEquals(
            setOf(Telltale.ESP_OFF, Telltale.MIL, Telltale.OIL, Telltale.CHARGE, Telltale.ABS),
            state(0, s3 = 0xF8).one<CanboxValue.Telltales>().lit
        )
        assertEquals(
            setOf(Telltale.ESP, Telltale.GLOW, Telltale.WATER_IN_FUEL),
            state(0, s4 = 0x38).one<CanboxValue.Telltales>().lit
        )
        assertEquals(emptySet<Telltale>(), state(0).one<CanboxValue.Telltales>().lit)
    }

    @Test
    fun shortPayloads_sayNothing() {
        assertTrue(decoder.decode(CanFrame(Jpf20lCmd.STATE, bytes(0x2B, 0x04, 0x19, 0x10, 0x01, 0x03))).isEmpty())
        assertTrue(decoder.decode(CanFrame(Jpf20lCmd.FAST, bytes(0, 0))).isEmpty())
        assertTrue(decoder.decode(CanFrame(Jpf20lCmd.ENGINE, bytes(0, 0))).isEmpty())
        assertTrue(decoder.decode(CanFrame(Jpf20lCmd.AMBIENT, bytes(0))).isEmpty())
        assertTrue(decoder.decode(CanFrame(Jpf20lCmd.TRIP, bytes(0, 0, 0))).isEmpty())
    }

    // ------------------------------------------------------------------ ENGINE, AMBIENT, TRIP

    @Test
    fun engine_vectors() {
        val v = decode("AA 55 05 B4 85 8F 5C 02 C6 F0")
        assertEquals(CanboxValue.CoolantC(93), v.one<CanboxValue.CoolantC>())
        assertEquals(14.3, v.one<CanboxValue.BatteryVolts>().volts!!, 1e-9)
        assertEquals(CanboxValue.FuelLevelPct(92), v.one<CanboxValue.FuelLevelPct>())
        assertEquals(71.0, v.one<CanboxValue.FuelLitres>().litres!!, 1e-9)

        // 02C stale: coolant alone is withdrawn, the rest stays.
        val d = decode("AA 55 05 B4 FF 8F 5C 02 C6 6A")
        assertNull(d.one<CanboxValue.CoolantC>().celsius)
        assertEquals(14.3, d.one<CanboxValue.BatteryVolts>().volts!!, 1e-9)
        assertEquals(92, d.one<CanboxValue.FuelLevelPct>().pct)
        assertEquals(71.0, d.one<CanboxValue.FuelLitres>().litres!!, 1e-9)
    }

    @Test
    fun engine_sentinels_andTheEdges() {
        val none = decoder.decode(CanFrame(Jpf20lCmd.ENGINE, bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
        assertEquals(
            listOf<CanboxValue>(CanboxValue.CoolantC(null), CanboxValue.BatteryVolts(null), CanboxValue.FuelLevelPct(null), CanboxValue.FuelLitres(null)),
            none
        )
        // FE is still a real coolant reading (214), 0 is -40.
        assertEquals(214, Jpf20lSignals.coolantTempC(bytes(0xFE)))
        assertEquals(-40, Jpf20lSignals.coolantTempC(bytes(0x00)))
        assertEquals(0, Jpf20lSignals.fuelLevelPct(bytes(0, 0, 0)))
        assertNull(Jpf20lSignals.fuelLevelPct(bytes(0, 0, 101)))
        assertEquals(25.4, Jpf20lSignals.voltage(bytes(0, 0xFE))!!, 1e-9)
    }

    @Test
    fun ambient_vectors() {
        val v = decode("AA 55 03 B5 00 FA 0A BB")
        assertEquals(25.0, v.one<CanboxValue.OutsideTempC>().celsius!!, 1e-9)
        assertEquals(10, v.one<CanboxValue.EngineOffMinutes>().minutes)

        val n = decode("AA 55 03 B5 FF B5 FF 6A")
        assertEquals(-7.5, n.one<CanboxValue.OutsideTempC>().celsius!!, 1e-9)
        assertNull(n.one<CanboxValue.EngineOffMinutes>().minutes)

        // 0x8000 is the sentinel.
        assertNull(Jpf20lSignals.outsideTempC(bytes(0x80, 0x00, 0)))
        assertEquals(0, Jpf20lSignals.minutesSinceRun(bytes(0, 0, 0)))
        assertEquals(254, Jpf20lSignals.minutesSinceRun(bytes(0, 0, 0xFE)))
    }

    @Test
    fun trip_vectors() {
        val v = decode("AA 55 06 B8 12 D6 87 06 01 0A 3D")
        assertEquals(123_456.7, v.one<CanboxValue.OdometerKm>().km!!, 1e-6)
        assertEquals(CompassReading(sector = 6, headingDeg = 266), v.one<CanboxValue.Compass>().reading)

        // North 0 degrees is a real reading.
        val north = decode("AA 55 06 B8 12 D6 87 00 00 00 2C")
        assertEquals(CompassReading(0, 0), north.one<CanboxValue.Compass>().reading)

        val none = decode("AA 55 06 B8 FF FF FF FF FF FF B7")
        assertNull(none.one<CanboxValue.OdometerKm>().km)
        assertNull(none.one<CanboxValue.Compass>().reading)

        // A new car: 0.0 km is a reading, not a blank.
        assertEquals(0.0, Jpf20lSignals.odometerKm(bytes(0, 0, 0, 0xFF, 0xFF, 0xFF))!!, 0.0)
    }

    @Test
    fun compass_needsBothFieldsGood() {
        assertNull(Jpf20lSignals.compass(bytes(0, 0, 0, 8, 0x00, 0x10)))   // sector 8
        assertNull(Jpf20lSignals.compass(bytes(0, 0, 0, 3, 0x01, 0x67)))   // heading 359
        assertEquals(CompassReading(3, 358), Jpf20lSignals.compass(bytes(0, 0, 0, 3, 0x01, 0x66)))
    }

    // ------------------------------------------------------------------ tyre, and the stock stream

    @Test
    fun tyre_isUnchanged() {
        val v = decoder.decode(CanFrame(Jpf20lCmd.TYRE, bytes(0x02, 0x00, 0xE6, 0x78, 0x08)))
        val r = (v.single() as CanboxValue.Tyre).reading
        assertEquals(TyreReading.REAR_RIGHT, r.position)
        assertEquals(230, r.kPa)
        assertEquals(0x78 - 50, r.celsius)
        assertTrue(r.lowPressureWarn)
        // No temperature and no pressure are separate nulls.
        val t = Jpf20lSignals.tyre(bytes(0x00, 0xFF, 0xFF, 0xFF, 0x04))!!
        assertNull(t.kPa); assertNull(t.celsius); assertTrue(t.noSignal)
        assertNull(Jpf20lSignals.tyre(bytes(0x05, 0, 0, 0, 0)))
        assertNull(Jpf20lSignals.tyre(bytes(0x00, 0x00, 0xE6)))
    }

    @Test
    fun theBlockTakesBack_revsReverseDoorsAndGear_fromTheStockFrames() {
        // Stock 0x32: gear D (4), rpm 1000.
        val drive = decoder.decode(CanFrame(HiworldJpf2Cmd.DRIVE, bytes(0x00, 0x04, 0x03, 0xE8, 0, 0, 0, 0)))
        assertTrue(drive.isEmpty())
        // Stock 0x12 doors, stock 0x72 reverse bit: gone; the rest of 0x72 still talks.
        assertTrue(decoder.decode(CanFrame(HiworldJpf2Cmd.BODY, bytes(0, 0, 0x80, 0, 0))).isEmpty())
        val st = decoder.decode(CanFrame(HiworldJpf2Cmd.STATE, bytes(0x07, 0x00, 0x00, 0x33, 0x00, 0x00)))
        assertTrue(st.none { it is CanboxValue.Reverse })
        assertTrue(st.contains(CanboxValue.Acc(true)))
        assertTrue(st.contains(CanboxValue.Lights(true)))
        assertTrue(st.contains(CanboxValue.Dimmer(0x33)))
        // The version stays.
        val ver = "H1N4JPF20L-051126".map { it.code.toByte() }.toByteArray()
        assertEquals(listOf<CanboxValue>(CanboxValue.Version("H1N4JPF20L-051126")), decoder.decode(CanFrame(HiworldJpf2Cmd.VERSION, ver)))
    }

    @Test
    fun theOldBlock_isGone() {
        // 0xB1, 0xB6, 0xB9..0xBF are never sent and decode to nothing, whatever they carry.
        for (cmd in listOf(0xB1, 0xB6, 0xB9, 0xBA, 0xBB, 0xBC, 0xBD, 0xBE, 0xBF)) {
            assertTrue("0x%02X".format(cmd), decoder.decode(CanFrame(cmd, bytes(0x00, 0x39, 0x09, 0x92, 0x2D, 0x11, 0x10))).isEmpty())
        }
        assertEquals(setOf(0xB0, 0xB2, 0xB3, 0xB4, 0xB5, 0xB7, 0xB8), Jpf20lCmd.DECODED)
        assertEquals("state", decoder.commandName(0xB2))
        assertEquals("fast", decoder.commandName(0xB3))
        assertEquals("cmd_BC", decoder.commandName(0xBC))
    }
}
