package com.openauto.unitproto.canbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bytes written the way the protocol table does, so a test reads like the frame on the wire. */
private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** The custom 0xB0-0xBD block, byte by byte. */
class Jpf20lDecoderTest {

    @Test
    fun aTemperatureOf0xFF_meansNotAvailable_notPlus215() {
        // The trap this rule exists for: every +40-biased byte from 00 to FF
        // otherwise decodes inside -40..215, so without a reserved code a
        // temperature field cannot say "unknown" -- and 0xFF, the filler a car
        // puts on the bus for an absent sensor, would read as a perfectly
        // acceptable 215 C and light the overheat alert.
        val payload = bytes(0xFF, 0xFF, 0x5C, 0x05, 0x96)
        assertNull("coolant 0xFF must be unknown, not 215 C", Jpf20lSignals.coolantTempC(payload))
        assertNull("intake 0xFF must be unknown, not 215 C", Jpf20lSignals.intakeTempC(payload))
        // ...and the rest of the frame is still good. This is the whole point:
        // one dead sensor must not cost the other three fields.
        assertEquals(92, Jpf20lSignals.fuelLevelPct(payload))
        assertEquals(14.3, Jpf20lSignals.voltage(payload)!!, 0.001)
        // 0xFE is still a real reading, so the reserved code is exactly one byte.
        assertEquals(214, Jpf20lSignals.coolantTempC(bytes(0xFE, 0x55, 0x00, 0x00, 0x00)))
    }

    // ------------------------------------------------- stock 0x12 and 0xB0

    @Test
    fun theHeartbeatSaysWhichGroupsAreLive() {
        // payload[0] revision, payload[1] the bitmask:
        // bit0 B2, bit1 B3, bit2 B4, bit3 B5, bit4 B7, bit5 B8.
        val all = bytes(0x01, 0x2F)          // everything but TYRE
        assertEquals(1, Jpf20lSignals.heartbeatRevision(all))
        assertEquals(true, Jpf20lSignals.heartbeatValid(all, Jpf20lCmd.IGNITION))
        assertEquals(true, Jpf20lSignals.heartbeatValid(all, Jpf20lCmd.DRIVETRAIN))
        assertEquals(true, Jpf20lSignals.heartbeatValid(all, Jpf20lCmd.ENGINE))
        assertEquals(true, Jpf20lSignals.heartbeatValid(all, Jpf20lCmd.CLIMATE))
        assertEquals(false, Jpf20lSignals.heartbeatValid(all, Jpf20lCmd.TYRE))
        assertEquals(true, Jpf20lSignals.heartbeatValid(all, Jpf20lCmd.FUEL))

        // A dead coolant sender: ENGINE goes invalid, the rest stay live.
        val noEngine = bytes(0x01, 0x2B)
        assertEquals(false, Jpf20lSignals.heartbeatValid(noEngine, Jpf20lCmd.ENGINE))
        assertEquals(true, Jpf20lSignals.heartbeatValid(noEngine, Jpf20lCmd.DRIVETRAIN))

        // Null is "no opinion", never "invalid" - a short payload or a command
        // with no bit must not blank anything.
        assertNull(Jpf20lSignals.heartbeatValid(all, Jpf20lCmd.VERSION))
        assertNull(Jpf20lSignals.heartbeatValid(bytes(0x01), Jpf20lCmd.ENGINE))
        assertNull(Jpf20lSignals.heartbeatValid(null, Jpf20lCmd.ENGINE))
    }

    // ------------------------------------------ 0xBB odometer, 0xBD compass
    //
    // The vectors of CUSTOM-TX-SPEC.md section 9.6, as literal bytes: the
    // firmware's own tests use the same seven, so a disagreement here is a
    // contract break, not a style difference.

    @Test
    fun theOdometer_isAVerbatim24BitCountOfTenthsOfAKilometre() {
        val f = HiworldFraming.parse(bytes(0xAA, 0x55, 0x03, 0xBB, 0x12, 0xD6, 0x87, 0x2C))!!
        assertEquals(Jpf20lCmd.ODOMETER, f.cmd)
        // 0x12D687 = 1 234 567 counts. Not divided to whole km: the last digit
        // is what lets a 100 m leg be measured.
        assertEquals(123_456.7, Jpf20lSignals.odometerKm(f.payload)!!, 0.001)

        val blank = HiworldFraming.parse(bytes(0xAA, 0x55, 0x03, 0xBB, 0xFF, 0xFF, 0xFF, 0xBA))!!
        assertNull("FFFFFF is not available, not 1 677 721.5 km", Jpf20lSignals.odometerKm(blank.payload))
        // One below the sentinel is still a reading, and so is a new car's zero.
        assertEquals(1_677_721.4, Jpf20lSignals.odometerKm(bytes(0xFF, 0xFF, 0xFE))!!, 0.001)
        assertEquals(0.0, Jpf20lSignals.odometerKm(bytes(0x00, 0x00, 0x00))!!, 0.0)
        assertNull(Jpf20lSignals.odometerKm(bytes(0x12, 0xD6)))
        assertNull(Jpf20lSignals.odometerKm(null))
    }

    @Test
    fun theCompass_carriesTheCarsSectorAndTheMagneticHeading() {
        fun frame(vararg b: Int) = HiworldFraming.parse(bytes(*b))!!.also { assertEquals(Jpf20lCmd.COMPASS, it.cmd) }

        // West, 266 degrees: the real 1B4 payload 06 85 07 00 00 00 (0x85 * 2).
        assertEquals(CompassReading(6, 266), Jpf20lSignals.compass(frame(0xAA, 0x55, 0x03, 0xBD, 0x06, 0x01, 0x0A, 0xD0).payload))
        // Due north is a real reading, not a blank.
        assertEquals(CompassReading(0, 0), Jpf20lSignals.compass(frame(0xAA, 0x55, 0x03, 0xBD, 0x00, 0x00, 0x00, 0xBF).payload))
        // North at 358: sector and heading look inconsistent at the wrap, legitimately.
        assertEquals(CompassReading(0, 358), Jpf20lSignals.compass(frame(0xAA, 0x55, 0x03, 0xBD, 0x00, 0x01, 0x66, 0x26).payload))
        // Not available, before the first good sample.
        assertNull(Jpf20lSignals.compass(frame(0xAA, 0x55, 0x03, 0xBD, 0xFF, 0xFF, 0xFF, 0xBC).payload))
    }

    @Test
    fun theCompass_isGatedOnBothBytes_andNothingIsMasked() {
        // The car's 7/NW at 292 degrees: the formula would say W, the car wins.
        assertEquals(CompassReading(7, 292), Jpf20lSignals.compass(bytes(0x07, 0x01, 0x24)))
        // A sector of 9 no longer means what the table says. Masking it to 1
        // would publish "NE"; the whole sample goes instead.
        assertNull(Jpf20lSignals.compass(bytes(0x09, 0x00, 0x5A)))
        // A good sector with a bad heading is no reading either.
        assertNull(Jpf20lSignals.compass(bytes(0x02, 0x01, 0xFE)))     // 510, the 1B4 b1 = FF trap
        assertNull(Jpf20lSignals.compass(bytes(0x02, 0x01, 0x68)))     // 360 is never sent
        assertNull(Jpf20lSignals.compass(bytes(0x02, 0x00)))
        assertNull(Jpf20lSignals.compass(null))
    }

    @Test
    fun theHeartbeatsLastTwoBits_areTheOdometerAndTheCompass() {
        val f = HiworldFraming.parse(bytes(0xAA, 0x55, 0x02, 0xB0, 0x01, 0xEF, 0xA1))!!
        assertEquals(Jpf20lCmd.HEARTBEAT, f.cmd)
        assertEquals(true, Jpf20lSignals.heartbeatValid(f.payload, Jpf20lCmd.ODOMETER))
        assertEquals(true, Jpf20lSignals.heartbeatValid(f.payload, Jpf20lCmd.COMPASS))
        assertEquals(false, Jpf20lSignals.heartbeatValid(f.payload, Jpf20lCmd.TYRE))
        // The older 0x2F heartbeat says both are not live.
        assertEquals(false, Jpf20lSignals.heartbeatValid(bytes(0x01, 0x2F), Jpf20lCmd.ODOMETER))
        assertEquals(false, Jpf20lSignals.heartbeatValid(bytes(0x01, 0x2F), Jpf20lCmd.COMPASS))
        assertEquals(true, Jpf20lSignals.heartbeatValid(bytes(0x01, 0x40), Jpf20lCmd.ODOMETER))
        assertEquals(true, Jpf20lSignals.heartbeatValid(bytes(0x01, 0x80), Jpf20lCmd.COMPASS))
        assertEquals("odometer", Jpf20lCmd.name(Jpf20lCmd.ODOMETER))
        assertEquals("compass", Jpf20lCmd.name(Jpf20lCmd.COMPASS))
    }

    @Test
    fun theCommands_areDistinctAndNamed() {
        val all = listOf(
            Jpf20lCmd.HEARTBEAT, Jpf20lCmd.VERSION, Jpf20lCmd.IGNITION, Jpf20lCmd.DRIVETRAIN,
            Jpf20lCmd.ENGINE, Jpf20lCmd.CLIMATE, Jpf20lCmd.BODY, Jpf20lCmd.TYRE,
            Jpf20lCmd.FUEL, Jpf20lCmd.KEY
        )
        assertEquals(all.size, all.toSet().size)
        assertEquals("drivetrain", Jpf20lCmd.name(Jpf20lCmd.DRIVETRAIN))
        assertEquals("tyre", Jpf20lCmd.name(Jpf20lCmd.TYRE))
        assertNull(Jpf20lCmd.name(0x7F))
        assertEquals("cmd_7F", Jpf20lDecoder().commandName(0x7F))
        // The block exists so the box's own commands keep their meaning.
        assertTrue(Jpf20lCmd.DECODED.all { it in 0xB0..0xBF })
        assertEquals("fuel", Jpf20lCmd.name(Jpf20lCmd.FUEL))
        assertEquals(setOf(0xB0, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD), Jpf20lCmd.DECODED)
        // Stock commands are decoded too, and deliberately kept out of DECODED
        // so the "our block" invariant above stays true.
        assertEquals(setOf(0x12, 0x72, 0x32, 0xF0), HiworldJpf2Cmd.DECODED)
        assertEquals("stock_body", Jpf20lDecoder().commandName(HiworldJpf2Cmd.BODY))
    }

    // --------------------------------------------------------- 0x21 drivetrain

    @Test
    fun speed_isABigEndianWordInRange() {
        // 0x0039 = 57 km/h.
        assertEquals(57, Jpf20lSignals.speedKmh(bytes(0x00, 0x39, 0x00, 0x00, 0x00, 0x00)))
        assertEquals(0, Jpf20lSignals.speedKmh(bytes(0x00, 0x00)))
        assertEquals(400, Jpf20lSignals.speedKmh(bytes(0x01, 0x90)))   // the top of the range
        assertNull(Jpf20lSignals.speedKmh(bytes(0x01, 0x91)))          // 401
        assertNull(Jpf20lSignals.speedKmh(bytes(0xFF, 0xFF)))          // the filler
        assertNull(Jpf20lSignals.speedKmh(bytes(0x00)))                // short
        assertNull(Jpf20lSignals.speedKmh(null))
    }

    @Test
    fun rpm_isABigEndianWordInRange() {
        // 0x0992 = 2450 rpm, at the drivetrain frame's offset 2.
        assertEquals(2450, Jpf20lSignals.rpm(bytes(0x00, 0x39, 0x09, 0x92, 0x00, 0x00)))
        assertEquals(0, Jpf20lSignals.rpm(bytes(0x00, 0x00, 0x00, 0x00)))
        assertEquals(12_000, Jpf20lSignals.rpm(bytes(0x00, 0x00, 0x2E, 0xE0)))
        assertNull(Jpf20lSignals.rpm(bytes(0x00, 0x00, 0x2E, 0xE1)))   // 12001
        assertNull(Jpf20lSignals.rpm(bytes(0x00, 0x00, 0xFF, 0xFF)))
        assertNull(Jpf20lSignals.rpm(bytes(0x00, 0x00, 0x09)))         // short
        assertNull(Jpf20lSignals.rpm(null))
    }

    @Test
    fun loadAndThrottle_arePercentBytes() {
        val payload = bytes(0x00, 0x39, 0x09, 0x92, 0x2D, 0x64)
        assertEquals(45, Jpf20lSignals.engineLoadPct(payload))
        assertEquals(100, Jpf20lSignals.throttlePct(payload))
        assertEquals(0, Jpf20lSignals.engineLoadPct(bytes(0, 0, 0, 0, 0x00, 0x00)))
        assertNull(Jpf20lSignals.engineLoadPct(bytes(0, 0, 0, 0, 0x65, 0x00)))   // 101
        assertNull(Jpf20lSignals.throttlePct(bytes(0, 0, 0, 0, 0x00, 0xFF)))     // the filler
        assertNull(Jpf20lSignals.engineLoadPct(bytes(0, 0, 0, 0)))               // short
        assertNull(Jpf20lSignals.throttlePct(bytes(0, 0, 0, 0, 0x00)))           // short
        assertNull(Jpf20lSignals.engineLoadPct(null))
        assertNull(Jpf20lSignals.throttlePct(null))
    }

    @Test
    fun aWholeDrivetrainFrame_decodes() {
        val f = HiworldFraming.parse(HiworldFraming.encode(Jpf20lCmd.DRIVETRAIN, bytes(0x00, 0x39, 0x09, 0x92, 0x2D, 0x11)))!!
        assertEquals(57, Jpf20lSignals.speedKmh(f.payload))
        assertEquals(2450, Jpf20lSignals.rpm(f.payload))
        assertEquals(45, Jpf20lSignals.engineLoadPct(f.payload))
        assertEquals(17, Jpf20lSignals.throttlePct(f.payload))
    }

    // ------------------------------------------------------------- 0x22 engine

    @Test
    fun theEngineTemperatures_areBytesBiasedByForty() {
        // 90 degC = 130 = 0x82; -40 degC = 0; 20 degC = 60 = 0x3C.
        val payload = bytes(0x82, 0x3C, 0x4B, 0x04, 0xD2)
        assertEquals(90, Jpf20lSignals.coolantTempC(payload))
        assertEquals(20, Jpf20lSignals.intakeTempC(payload))
        assertEquals(-40, Jpf20lSignals.coolantTempC(bytes(0x00, 0x00)))
        assertEquals(-40, Jpf20lSignals.intakeTempC(bytes(0x00, 0x00)))
        // 0xFF is NOT 215 C -- it is the reserved "not available" code, so the
        // top of the usable range is 0xFE = 214 C. See TEMP_NOT_AVAILABLE.
        assertNull(Jpf20lSignals.coolantTempC(bytes(0xFF, 0xFF)))
        assertEquals(214, Jpf20lSignals.coolantTempC(bytes(0xFE, 0xFE)))
        assertNull(Jpf20lSignals.intakeTempC(bytes(0xFF, 0xFF)))
        assertEquals(214, Jpf20lSignals.intakeTempC(bytes(0xFE, 0xFE)))
        assertNull(Jpf20lSignals.coolantTempC(ByteArray(0)))               // short
        assertNull(Jpf20lSignals.intakeTempC(bytes(0x82)))                 // short
        assertNull(Jpf20lSignals.coolantTempC(null))
        assertNull(Jpf20lSignals.intakeTempC(null))
    }

    @Test
    fun fuelLevel_isAPercentByte() {
        assertEquals(75, Jpf20lSignals.fuelLevelPct(bytes(0x82, 0x3C, 0x4B, 0x04, 0xD2)))
        assertEquals(0, Jpf20lSignals.fuelLevelPct(bytes(0, 0, 0x00)))
        assertEquals(100, Jpf20lSignals.fuelLevelPct(bytes(0, 0, 0x64)))
        assertNull(Jpf20lSignals.fuelLevelPct(bytes(0, 0, 0x65)))   // 101
        assertNull(Jpf20lSignals.fuelLevelPct(bytes(0, 0, 0xFF)))
        assertNull(Jpf20lSignals.fuelLevelPct(bytes(0, 0)))         // short
        assertNull(Jpf20lSignals.fuelLevelPct(null))
    }

    @Test
    fun voltage_isAWordOfCentivolts() {
        // 0x04D2 = 1234 = 12.34 V.
        assertEquals(12.34, Jpf20lSignals.voltage(bytes(0x82, 0x3C, 0x4B, 0x04, 0xD2))!!, 1e-9)
        assertEquals(0.0, Jpf20lSignals.voltage(bytes(0, 0, 0, 0x00, 0x00))!!, 1e-9)
        assertEquals(30.0, Jpf20lSignals.voltage(bytes(0, 0, 0, 0x0B, 0xB8))!!, 1e-9)   // 3000
        assertNull(Jpf20lSignals.voltage(bytes(0, 0, 0, 0x0B, 0xB9)))                   // 30.01 V
        assertNull(Jpf20lSignals.voltage(bytes(0, 0, 0, 0xFF, 0xFF)))                   // the filler
        assertNull(Jpf20lSignals.voltage(bytes(0, 0, 0, 0x04)))                         // short
        assertNull(Jpf20lSignals.voltage(null))
    }

    // ------------------------------------------------------------ 0x23 climate

    @Test
    fun outsideTemperature_isASignedWordOfDecidegrees() {
        assertEquals(23.5, Jpf20lSignals.outsideTempC(bytes(0x00, 0xEB))!!, 1e-9)    // 235
        assertEquals(-12.3, Jpf20lSignals.outsideTempC(bytes(0xFF, 0x85))!!, 1e-9)   // -123
        assertEquals(0.0, Jpf20lSignals.outsideTempC(bytes(0x00, 0x00))!!, 1e-9)
        assertEquals(-50.0, Jpf20lSignals.outsideTempC(bytes(0xFE, 0x0C))!!, 1e-9)   // -500
        assertEquals(80.0, Jpf20lSignals.outsideTempC(bytes(0x03, 0x20))!!, 1e-9)    // 800
        assertNull(Jpf20lSignals.outsideTempC(bytes(0xFE, 0x0B)))                    // -50.1
        assertNull(Jpf20lSignals.outsideTempC(bytes(0x03, 0x21)))                    // 80.1
        assertNull(Jpf20lSignals.outsideTempC(bytes(0x7F, 0xFF)))                    // the filler
        assertNull(Jpf20lSignals.outsideTempC(bytes(0x00)))                          // short
        assertNull(Jpf20lSignals.outsideTempC(null))
    }

    // --------------------------------------------------------------- 0x24 body

    @Test
    fun doors_decodeOneBitEach() {
        assertEquals(
            DoorBits(frontLeft = false, frontRight = false, rearLeft = false, rearRight = false, tailgate = false, bonnet = false),
            Jpf20lSignals.doors(bytes(0x00))
        )
        assertFalse(Jpf20lSignals.doors(bytes(0x00))!!.anyOpen)

        val all = Jpf20lSignals.doors(bytes(0x3F))!!
        assertTrue(all.frontLeft && all.frontRight && all.rearLeft && all.rearRight && all.tailgate && all.bonnet)
        assertTrue(all.anyOpen)

        // Each bit on its own, so a swapped pair cannot pass.
        assertEquals(true, Jpf20lSignals.doors(bytes(0x01))!!.frontLeft)
        assertEquals(false, Jpf20lSignals.doors(bytes(0x01))!!.frontRight)
        assertEquals(true, Jpf20lSignals.doors(bytes(0x02))!!.frontRight)
        assertEquals(true, Jpf20lSignals.doors(bytes(0x04))!!.rearLeft)
        assertEquals(true, Jpf20lSignals.doors(bytes(0x08))!!.rearRight)
        assertEquals(true, Jpf20lSignals.doors(bytes(0x10))!!.tailgate)
        assertEquals(true, Jpf20lSignals.doors(bytes(0x20))!!.bonnet)

        // The unused top bits change nothing.
        assertEquals(Jpf20lSignals.doors(bytes(0x01)), Jpf20lSignals.doors(bytes(0xC1)))

        // A mix: front left and the bonnet.
        val mix = Jpf20lSignals.doors(bytes(0x21))!!
        assertTrue(mix.frontLeft)
        assertTrue(mix.bonnet)
        assertFalse(mix.rearLeft)
        assertTrue(mix.anyOpen)

        assertNull(Jpf20lSignals.doors(ByteArray(0)))
        assertNull(Jpf20lSignals.doors(null))
    }

    // --------------------------------------------------------------- 0x25 tyre

    @Test
    fun aTyreReading_decodesPositionPressureTemperatureAndFlags() {
        // Rear right (the WCM's order), 0x00E6 = 230 kPa, 0x50 = 80 - 40 = 40 degC, no flags.
        assertEquals(
            TyreReading(TyreReading.REAR_RIGHT, 230, 40, sensorBatteryLow = false, leak = false, noSignal = false),
            Jpf20lSignals.tyre(bytes(0x02, 0x00, 0xE6, 0x50, 0x00))
        )
        assertEquals(
            TyreReading(TyreReading.SPARE, 0, -40, sensorBatteryLow = true, leak = true, noSignal = true),
            Jpf20lSignals.tyre(bytes(0x04, 0x00, 0x00, 0x00, 0x07))
        )
    }

    @Test
    fun theTyreFlags_areOneBitEach() {
        val base = bytes(0x00, 0x00, 0xE6, 0x50, 0x00)
        fun withFlags(f: Int) = Jpf20lSignals.tyre(bytes(0x00, 0x00, 0xE6, 0x50, f))!!

        val none = Jpf20lSignals.tyre(base)!!
        assertFalse(none.sensorBatteryLow || none.leak || none.noSignal)

        assertTrue(withFlags(0x01).sensorBatteryLow)
        assertFalse(withFlags(0x01).leak)
        assertFalse(withFlags(0x01).noSignal)
        assertTrue(withFlags(0x02).leak)
        assertFalse(withFlags(0x02).sensorBatteryLow)
        assertTrue(withFlags(0x04).noSignal)
        assertFalse(withFlags(0x04).leak)
        // Unused bits are ignored.
        assertEquals(withFlags(0x01), withFlags(0xF9))
    }

    @Test
    fun everyWheelPosition_isAccepted_andNothingBeyondTheSpare() {
        for (p in TyreReading.FRONT_LEFT..TyreReading.SPARE) {
            assertEquals(p, Jpf20lSignals.tyre(bytes(p, 0x00, 0xE6, 0x50, 0x00))?.position)
        }
        assertNull(Jpf20lSignals.tyre(bytes(0x05, 0x00, 0xE6, 0x50, 0x00)))
        assertNull(Jpf20lSignals.tyre(bytes(0xFF, 0x00, 0xE6, 0x50, 0x00)))
    }

    @Test
    fun theBoxesPolledTyres_decode_withNoTemperature() {
        // TPMS-POLL-SPEC.md §6, as wire bytes: the four corners of the WCM's reply 06 61 3B 21 20 21 1E.
        fun wheel(vararg wire: Int) = HiworldFraming.parse(bytes(*wire))!!.let {
            assertEquals(Jpf20lCmd.TYRE, it.cmd)
            Jpf20lSignals.tyre(it.payload)!!
        }
        fun live(pos: Int, kPa: Int) = TyreReading(pos, kPa, null, sensorBatteryLow = false, leak = false, noSignal = false)
        assertEquals(live(TyreReading.FRONT_LEFT, 228), wheel(0xAA, 0x55, 0x05, 0xB7, 0x00, 0x00, 0xE4, 0xFF, 0x00, 0x9E))
        assertEquals(live(TyreReading.FRONT_RIGHT, 221), wheel(0xAA, 0x55, 0x05, 0xB7, 0x01, 0x00, 0xDD, 0xFF, 0x00, 0x98))
        assertEquals(live(TyreReading.REAR_RIGHT, 228), wheel(0xAA, 0x55, 0x05, 0xB7, 0x02, 0x00, 0xE4, 0xFF, 0x00, 0xA0))
        assertEquals(live(TyreReading.REAR_LEFT, 207), wheel(0xAA, 0x55, 0x05, 0xB7, 0x03, 0x00, 0xCF, 0xFF, 0x00, 0x8C))
        // Before the first poll, or stale: the wheel is still named, with nothing to show.
        assertEquals(
            TyreReading(TyreReading.FRONT_LEFT, null, null, sensorBatteryLow = false, leak = false, noSignal = true),
            wheel(0xAA, 0x55, 0x05, 0xB7, 0x00, 0xFF, 0xFF, 0xFF, 0x04, 0xBC)
        )
    }

    @Test
    fun aTyreValueOutOfRange_isNullOnItsOwn() {
        // 700 kPa is the top; 701 is not, and neither is the FFFF sentinel - the temperature stays.
        assertEquals(700, Jpf20lSignals.tyre(bytes(0x00, 0x02, 0xBC, 0x50, 0x00))?.kPa)
        assertNull(Jpf20lSignals.tyre(bytes(0x00, 0x02, 0xBD, 0x50, 0x00))!!.kPa)
        assertEquals(40, Jpf20lSignals.tyre(bytes(0x00, 0xFF, 0xFF, 0x50, 0x00))!!.celsius)
        // 120 degC is the top (160 on the wire); 161 is not, nor FF. The pressure stays.
        assertEquals(120, Jpf20lSignals.tyre(bytes(0x00, 0x00, 0xE6, 0xA0, 0x00))?.celsius)
        assertNull(Jpf20lSignals.tyre(bytes(0x00, 0x00, 0xE6, 0xA1, 0x00))!!.celsius)
        assertEquals(230, Jpf20lSignals.tyre(bytes(0x00, 0x00, 0xE6, 0xFF, 0x00))!!.kPa)
        // Short payloads, one byte at a time.
        val good = bytes(0x02, 0x00, 0xE6, 0x50, 0x00)
        for (n in 0 until good.size) assertNull("cut to $n bytes", Jpf20lSignals.tyre(good.copyOf(n)))
        assertNull(Jpf20lSignals.tyre(null))
    }

    // ------------------------------------------------------------- byte readers

    @Test
    fun theByteReaders_guardTheirBounds() {
        val b = bytes(0x12, 0x34, 0xFF, 0x80)
        assertEquals(0x12, CanBytes.u8(b, 0))
        assertEquals(0xFF, CanBytes.u8(b, 2))
        assertNull(CanBytes.u8(b, 4))
        assertNull(CanBytes.u8(b, -1))
        assertEquals(0x1234, CanBytes.u16(b, 0))
        assertEquals(0xFF80, CanBytes.u16(b, 2))
        assertNull(CanBytes.u16(b, 3))
        assertNull(CanBytes.u16(b, -1))
        assertEquals(0x1234, CanBytes.s16(b, 0))
        assertEquals(-128, CanBytes.s16(b, 2))
        assertEquals(-1, CanBytes.s16(bytes(0xFF, 0xFF), 0))
        assertEquals(32_767, CanBytes.s16(bytes(0x7F, 0xFF), 0))
        assertEquals(-32_768, CanBytes.s16(bytes(0x80, 0x00), 0))
        assertNull(CanBytes.s16(null, 0))
    }

    @Test
    fun fuelLitresAndRange_areRead() {
        // 412 decilitres = 41.2 L, 530 km to empty.
        val payload = byteArrayOf(0x01, 0x9C.toByte(), 0x02, 0x12)
        assertEquals(41.2, Jpf20lSignals.fuelLitres(payload)!!, 0.001)
        assertEquals(530, Jpf20lSignals.rangeKm(payload))
    }

    @Test
    fun fuelAndRange_rejectTheNotAvailableFillers() {
        val filler = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertNull(Jpf20lSignals.fuelLitres(filler))
        assertNull(Jpf20lSignals.rangeKm(filler))
    }

    @Test
    fun fuelAndRange_rejectTheImplausible() {
        // 300.0 L, past any production tank.
        assertNull(Jpf20lSignals.fuelLitres(byteArrayOf(0x0B, 0xB8.toByte(), 0x00, 0x00)))
        // 2500 km, past what the dashboard will show.
        assertNull(Jpf20lSignals.rangeKm(byteArrayOf(0x00, 0x00, 0x09, 0xC4.toByte())))
    }

    @Test
    fun fuel_isNullOnAShortPayload() {
        assertNull(Jpf20lSignals.fuelLitres(byteArrayOf(0x01)))
        assertNull(Jpf20lSignals.rangeKm(byteArrayOf(0x01, 0x9C.toByte())))
    }

    // ------------------------------------------------- the decoder over frames

    private fun decode(cmd: Int, vararg payload: Int) =
        Jpf20lDecoder().decode(CanFrame(cmd, bytes(*payload)))

    @Test
    fun ignition_saysReverseTheParkingBrakeAndTheBoxesRunState() {
        // bit1 engine running, bit2 reverse, bit3 parking brake; bit0 (ACC) is not read.
        assertEquals(
            listOf(CanboxValue.Reverse(true), CanboxValue.ParkingBrake(true), CanboxValue.EngineRunning(true)),
            decode(Jpf20lCmd.IGNITION, 0x0F)
        )
        assertEquals(
            listOf(CanboxValue.Reverse(false), CanboxValue.ParkingBrake(false), CanboxValue.EngineRunning(false)),
            decode(Jpf20lCmd.IGNITION, 0x01)
        )
        assertTrue(decode(Jpf20lCmd.IGNITION).isEmpty())
    }

    @Test
    fun aWholeFrame_becomesItsValues() {
        assertEquals(
            listOf(CanboxValue.SpeedKmh(57), CanboxValue.Rpm(2450), CanboxValue.EngineLoadPct(45), CanboxValue.ThrottlePct(17)),
            decode(Jpf20lCmd.DRIVETRAIN, 0x00, 0x39, 0x09, 0x92, 0x2D, 0x11)
        )
        assertEquals(
            listOf(CanboxValue.BatteryVolts(12.34), CanboxValue.CoolantC(90), CanboxValue.IntakeAirC(20), CanboxValue.FuelLevelPct(75)),
            decode(Jpf20lCmd.ENGINE, 0x82, 0x3C, 0x4B, 0x04, 0xD2)
        )
        // A dead coolant sender (FF) costs only the coolant.
        assertEquals(
            listOf(CanboxValue.BatteryVolts(14.3), CanboxValue.FuelLevelPct(92)),
            decode(Jpf20lCmd.ENGINE, 0xFF, 0xFF, 0x5C, 0x05, 0x96)
        )
        assertEquals(listOf(CanboxValue.OutsideTempC(-12.3)), decode(Jpf20lCmd.CLIMATE, 0xFF, 0x85))
        assertEquals(
            listOf(CanboxValue.FuelLitres(41.2), CanboxValue.RangeKm(530)),
            decode(Jpf20lCmd.FUEL, 0x01, 0x9C, 0x02, 0x12)
        )
        assertEquals(listOf(CanboxValue.OdometerKm(123_456.7)), decode(Jpf20lCmd.ODOMETER, 0x12, 0xD6, 0x87))
        assertEquals(listOf(CanboxValue.Compass(CompassReading(6, 266))), decode(Jpf20lCmd.COMPASS, 0x06, 0x01, 0x0A))
        assertTrue(decode(Jpf20lCmd.COMPASS, 0xFF, 0xFF, 0xFF).isEmpty())
        assertTrue(decode(0x7F, 0x01).isEmpty())
    }

    @Test
    fun theHeartbeat_withdrawsWhatTheBoxHasLost() {
        // Only ENGINE lost (bit 2 clear): everything it carries is withdrawn, nothing else.
        assertEquals(
            listOf(
                CanboxValue.CoolantC(null), CanboxValue.IntakeAirC(null),
                CanboxValue.FuelLevelPct(null), CanboxValue.BatteryVolts(null)
            ),
            decode(Jpf20lCmd.HEARTBEAT, 0x01, 0xFB)
        )
        // Everything live: nothing to say.
        assertTrue(decode(Jpf20lCmd.HEARTBEAT, 0x01, 0xFF).isEmpty())
        // Everything lost, the odometer excepted (it is kept, not live).
        val all = decode(Jpf20lCmd.HEARTBEAT, 0x01, 0x00)
        assertTrue(CanboxValue.SpeedKmh(null) in all && CanboxValue.Rpm(null) in all)
        assertTrue(CanboxValue.EngineRunning(null) in all)
        assertTrue(CanboxValue.FuelLitres(null) in all && CanboxValue.RangeKm(null) in all)
        assertTrue(CanboxValue.OutsideTempC(null) in all && CanboxValue.Compass(null) in all)
        assertTrue(all.none { it is CanboxValue.OdometerKm })
        // A short heartbeat has no opinion.
        assertTrue(decode(Jpf20lCmd.HEARTBEAT, 0x01).isEmpty())
    }

    @Test
    fun theStockStreamIsStillRead_exceptWhatTheBlockCarriesItself() {
        // Doors, lights, dimmer and the lever come through unchanged...
        assertEquals(listOf(CanboxValue.Gear(GearPosition.DRIVE)), decode(HiworldJpf2Cmd.DRIVE, 0x00, 0x04, 0x0A, 0x8D))
        assertEquals(
            listOf(CanboxValue.Lights(true), CanboxValue.Dimmer(51)),
            decode(HiworldJpf2Cmd.STATE, 0x07, 0x00, 0x00, 0x33)
        )
        // ...but 0x32's revs and 0x72's reverse bit are not: a heartbeat withdrawal of
        // the block's own would be undone by the next stock frame.
        assertTrue(decode(HiworldJpf2Cmd.DRIVE, 0x00, 0x04, 0x0A, 0x8D).none { it is CanboxValue.Rpm })
        assertTrue(decode(HiworldJpf2Cmd.STATE, 0x07, 0x00, 0x00, 0x33).none { it is CanboxValue.Reverse })
    }
}
