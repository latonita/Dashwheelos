package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Head unit widget's readings from toybox and sysfs, and the Gear widget's gear from revs and speed. */
class HeadUnitAndGearTest {

    private val top = """
        Tasks: 542 total,   1 running, 541 sleeping,   0 stopped,   0 zombie
          Mem:  3874196K total,  3701280K used,   172916K free,    36864K buffers
         Swap:  2015228K total,   409600K used,  1605628K free,  1633848K cached
        800%cpu  23%user   0%nice  28%sys 464%idle   0%iow   0%irq   0%sirq   0%host
          PID %CPU   RES ARGS
         3120 38.0 412M com.google.android.apps.maps
         2011 22.5 286M com.openauto.dash
          512 20.0  98M surfaceflinger
         3300  4.0  60M com.google.android.apps.maps:location
         4410  1.0 5120 top -b -n 1
    """.trimIndent()

    @Test
    fun cpuComesFromTheHeader() {
        assertEquals(42, HeadUnitMonitor.parseCpuPct(top))
        assertNull(HeadUnitMonitor.parseCpuPct("no header"))
    }

    @Test
    fun processesAreReadWithTheirMemory() {
        val p = HeadUnitMonitor.parseTopProcesses(top)
        assertEquals(5, p.size)
        assertEquals(HeadUnitMonitor.TopProcess("com.google.android.apps.maps", 38f, 412), p[0])
        assertEquals(5, p.last().ramMb)
        assertEquals(1229, HeadUnitMonitor.parseMb("1.2G"))
        assertEquals(0, HeadUnitMonitor.parseMb("98K"))
    }

    @Test
    fun appsAreGroupedAndTheRestIsTheSystem() {
        val names = mapOf("com.google.android.apps.maps" to "Google Maps", "com.openauto.dash" to "Dashwheel")
        val loads = HeadUnitMonitor.appLoads(HeadUnitMonitor.parseTopProcesses(top), { names[it] }, "System")
        assertEquals(listOf("Google Maps", "Dashwheel", "System"), loads.map { it.label })
        // Maps' :location process counts with Maps; top itself is left out.
        assertEquals(42f, loads[0].cpuPct, 0.01f)
        assertEquals(472, loads[0].ramMb)
        assertEquals(20f, loads[2].cpuPct, 0.01f)
        assertNull(loads[2].packageName)
    }

    @Test
    fun theHottestCpuZoneWins() {
        assertEquals(61, HeadUnitMonitor.parseThermal("board-thmzone 45000\ncpu0-thmzone 58300\nsoc-thmzone 61200\nbattery 30000"))
        // Nothing named for the CPU: any plausible zone.
        assertEquals(47, HeadUnitMonitor.parseThermal("zone0 47000\nzone1 -40\nbroken"))
        assertNull(HeadUnitMonitor.parseThermal(""))
    }

    @Test
    fun theBmp6TableAndOtherBoxes() {
        assertEquals(6, GearEstimator.seeds(6).size)
        assertEquals(118.0, GearEstimator.seeds(6).first(), 0.0)
        val five = GearEstimator.seeds(5)
        assertEquals(5, five.size)
        assertTrue(five.zipWithNext().all { (a, b) -> a > b })
    }

    @Test
    fun revsAgainstSpeedGiveTheGear() {
        val t = GearEstimator.seeds(6)
        assertEquals(3, GearEstimator.classify(2050, 50, t))   // 41 rpm per km/h
        assertEquals(6, GearEstimator.classify(1900, 100, t))
        assertNull(GearEstimator.classify(2550, 50, t))       // 51: between 2nd and 3rd, mid-shift
        assertNull(GearEstimator.classify(2000, 2, t))        // standing
    }

    @Test
    fun whatTheTileShows() {
        val t = GearEstimator.seeds(6)
        assertEquals(Gear.Reverse, GearEstimator.read(true, 900, 3, t))
        assertEquals(Gear.Stopped, GearEstimator.read(false, 800, 0, t))
        assertEquals(Gear.Forward(4), GearEstimator.read(false, 2100, 70, t))
        assertEquals(Gear.Free, GearEstimator.read(false, 820, 70, t))      // idle revs at 70 km/h: no gear that tall
        assertEquals(Gear.Unknown, GearEstimator.read(false, 2550, 50, t))  // mid-shift, clutch slipping
        assertEquals(Gear.Unknown, GearEstimator.read(false, null, 50, t))
        assertEquals(Gear.Unknown, GearEstimator.read(false, 900, null, t))
    }

    @Test
    fun theTableLearnsTheCarsOwnRatios() {
        var t = GearEstimator.seeds(6)
        repeat(300) { t = GearEstimator.learn(t, 3, 2150, 50) }   // 43 rpm per km/h in 3rd
        assertEquals(43.0, t[2], 0.3)
        // A reading far from the gear teaches nothing, nor one standing still.
        assertEquals(t, GearEstimator.learn(t, 3, 3000, 50))
        assertEquals(t, GearEstimator.learn(t, 3, 430, 10))
    }

    @Test
    fun glyphs() {
        assertEquals("R", gearGlyph(Gear.Reverse))
        assertEquals("N", gearGlyph(Gear.Free))
        assertEquals("5", gearGlyph(Gear.Forward(5)))
        assertEquals("–", gearGlyph(Gear.Stopped))
        // An automatic's lever from the canbox: P and D, not a dash.
        assertEquals("P", gearGlyph(Gear.Park))
        assertEquals("D", gearGlyph(Gear.Drive))
    }

    @Test
    fun theOutputIsCutAtLinesThatAreTheMarkAlone() {
        // The shell running the command lists itself, marks included.
        val out = """
            800%cpu 700%idle
             4410  1.0 5M sh -c top ...; echo ---DASH-PS---; ps; echo ---DASH-THERMAL---; for z in ...
            ---DASH-PS---
            com.google.android.apps.maps
            sh -c top ...; echo ---DASH-PS---; ps; echo ---DASH-THERMAL---
            ---DASH-THERMAL---
            cpu0 51000
        """.trimIndent()
        val (top, ps, thermal) = HeadUnitMonitor.splitSections(out)
        assertTrue(top.contains("4410"))
        assertTrue(ps.contains("com.google.android.apps.maps"))
        assertEquals(51, HeadUnitMonitor.parseThermal(thermal))
    }

    @Test
    fun theSecondFrameOfTopIsRead() {
        val two = "400%cpu 0%user 400%idle\n 1 0.0 1M a\n400%cpu 100%user 300%idle\n 1 25.0 1M a"
        val last = HeadUnitMonitor.lastFrame(two)
        assertEquals(25, HeadUnitMonitor.parseCpuPct(last))
        assertEquals(25f, HeadUnitMonitor.parseTopProcesses(last).single().cpuPct, 0.01f)
    }

    @Test
    fun theBatteryIsNotTheChip() {
        assertNull(HeadUnitMonitor.parseThermal("battery 25000"))
        assertEquals(40, HeadUnitMonitor.parseThermal("battery 25000\nzone3 40000"))
    }
}
