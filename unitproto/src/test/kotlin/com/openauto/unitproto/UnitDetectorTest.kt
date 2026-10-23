package com.openauto.unitproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitDetectorTest {

    /** The ROCO K706 as Dashwheel found it (QF001, UIS7862): the fixture everything else is measured against. */
    private val k706 = MapProbe(
        props = mapOf(
            "sys.qf.is.acc.on" to "true", "persist.sys.qf.launcher" to "com.openauto.dash",
            "ro.qf.platform" to "7862", "ro.board.platform" to "ums512", "ro.build.version.release" to "12"
        ),
        packages = setOf("com.qf.framework", "com.qf.vehicle", "com.qf.bluetooth", "com.qf.backcar", "com.zjinnova.zlink"),
        services = setOf("mcu_service", "util_service"),
        sdkInt = 29, brand = "ROCO", device = "K706", model = "K706", display = "QF001.V1.2.3"
    )

    @Test
    fun theK706_isQf_withItsStrongestEvidenceFirst() {
        val d = UnitDetector.detect(k706)
        assertEquals(UnitFamily.QF, d.family)
        assertTrue(d.score >= 4)
        assertTrue(d.evidence.first().strong)
        assertEquals("sys.qf.is.acc.on", d.evidence.first().name)
        assertFalse(d.overridden)
        // zlink alone is Topway's weak clue only: never enough on its own.
        assertEquals(1, d.runnersUp[UnitFamily.TOPWAY])
    }

    @Test
    fun aJoyingFyt_isFyt() {
        val p = MapProbe(
            props = mapOf("ro.fyt.platform" to "uis7862", "ro.build.fytmanufacturer" to "31", "sys.carlink.type" to "0"),
            packages = setOf("com.syu.ms", "com.syu.canbus", "com.syu.carlink"),
            files = setOf("/oem/app"), model = "ums512_1h10_Natv"
        )
        val d = UnitDetector.detect(p)
        assertEquals(UnitFamily.FYT, d.family)
        // The Topway model string is weak: FYT's strong clues win.
        assertTrue(d.score > d.runnersUp.getValue(UnitFamily.TOPWAY))
    }

    @Test
    fun anFytUnitWithOnlyItsServerApp_isFyt() {
        assertEquals(UnitFamily.FYT, UnitDetector.detect(MapProbe(packages = setOf("com.syu.ms"))).family)
    }

    @Test
    fun aPx6_isMtc_byItsManagerOrByCarManager() {
        val byManager = MapProbe(files = setOf("/system/priv-app/HCTManagerService"), props = mapOf("ro.board.platform" to "rk3399"))
        assertEquals(UnitFamily.MTC, UnitDetector.detect(byManager).family)
        val byClass = MapProbe(classes = setOf("android.microntek.CarManager"), services = setOf("carservice"))
        assertEquals(UnitFamily.MTC, UnitDetector.detect(byClass).family)
        // The class alone, with no service behind it, is a weak clue: one more is needed.
        val classOnly = MapProbe(classes = setOf("android.microntek.CarManager"))
        assertEquals(UnitFamily.GENERIC, UnitDetector.detect(classOnly).family)
        assertEquals(UnitFamily.MTC, UnitDetector.detect(classOnly.copy(props = mapOf("ro.board.platform" to "px30"))).family)
    }

    @Test
    fun aTopwayTs10_isTopway() {
        val p = MapProbe(
            packages = setOf("com.tw.car", "com.tw.carchoose", "com.zjinnova.zlink"),
            model = "ums512_1h10_Natv", display = "TS10.2.2_20250716.124754_WINDOW-THEME1"
        )
        assertEquals(UnitFamily.TOPWAY, UnitDetector.detect(p).family)
        // Without its packages, the model and the TS build string together still name it.
        assertEquals(UnitFamily.TOPWAY, UnitDetector.detect(p.copy(packages = emptySet())).family)
    }

    @Test
    fun aReglinkUnit_isReglink() {
        val p = MapProbe(packages = setOf("com.reglink.services", "com.reglink.launcher4"), props = mapOf("ro.board.platform" to "mt6580"))
        assertEquals(UnitFamily.REGLINK, UnitDetector.detect(p).family)
        // The MediaTek board with DuraSpeed, no Reglink package seen (package visibility): two weak clues.
        val hidden = MapProbe(props = mapOf("ro.board.platform" to "mt6765", "persist.duraspeed.support" to "1"))
        assertEquals(UnitFamily.REGLINK, UnitDetector.detect(hidden).family)
    }

    @Test
    fun theNwdK4811_isNwd_notQf() {
        // sprd uis8581, claims Android 14, has zlink: none of QF's properties.
        val p = MapProbe(
            props = mapOf("persist.nwd.launcher.default" to "com.nwd.launcher", "ro.build.version.release" to "14"),
            packages = setOf("com.zjinnova.zlink"), sdkInt = 29, brand = "alps", model = "K4811"
        )
        val d = UnitDetector.detect(p)
        assertEquals(UnitFamily.NWD, d.family)
        assertFalse(d.runnersUp.containsKey(UnitFamily.QF))
    }

    @Test
    fun anXyauto9320_isXyauto() {
        // The CAN stack (host + the per-brand plugin) on an AC8227L, behind AOSP's own launcher.
        val p = MapProbe(
            props = mapOf("ro.board.platform" to "ac8227l", "ro.hardware" to "ac8227l", "ro.build.version.release" to "8.1.0"),
            packages = setOf("com.kyhero.car.myhost", "com.kyhero.car.myplugin.canbus", "com.android.launcher"),
            sdkInt = 27, brand = "alps", model = "9320"
        )
        val d = UnitDetector.detect(p)
        assertEquals(UnitFamily.XYAUTO, d.family)
        assertTrue(d.evidence.first().strong)
        // Reglink lives on MediaTek too, but none of its own clues is here.
        assertFalse(d.runnersUp.containsKey(UnitFamily.REGLINK))
    }

    @Test
    fun anXyautoWithItsPackagesHidden_isStillXyauto_onTheBoardAlone() {
        // Package visibility hides the CAN stack: the two board properties are two weak clues.
        val hidden = MapProbe(props = mapOf("ro.board.platform" to "ac8257", "ro.hardware" to "ac8257"))
        assertEquals(UnitFamily.XYAUTO, UnitDetector.detect(hidden).family)
        // One of them alone is not enough.
        assertEquals(UnitFamily.GENERIC, UnitDetector.detect(MapProbe(props = mapOf("ro.hardware" to "ac8227l"))).family)
    }

    @Test
    fun aReglinkMediaTekBoard_isNotXyauto() {
        // Another MediaTek board, with Reglink's own clues: the AC822x list does not take it.
        val p = MapProbe(props = mapOf("ro.board.platform" to "mt6765", "ro.hardware" to "mt6765", "persist.duraspeed.support" to "1"))
        val d = UnitDetector.detect(p)
        assertEquals(UnitFamily.REGLINK, d.family)
        assertFalse(d.runnersUp.containsKey(UnitFamily.XYAUTO))
        // AOSP's launcher on a board nobody claims names no family at all.
        assertEquals(UnitFamily.GENERIC, UnitDetector.detect(MapProbe(packages = setOf("com.android.launcher"), props = mapOf("ro.board.platform" to "mt8127"))).family)
    }

    @Test
    fun anAllwinnerT8_isAllwinner_onTwoWeakClues() {
        val p = MapProbe(props = mapOf("ro.board.platform" to "sun50iw1p1", "ro.hardware" to "sun50iw1p1"))
        assertEquals(UnitFamily.ALLWINNER, UnitDetector.detect(p).family)
        assertEquals(UnitFamily.GENERIC, UnitDetector.detect(MapProbe(props = mapOf("ro.hardware" to "sun8iw11p1"))).family)
    }

    @Test
    fun theEmulatorAndAPhone_areGeneric() {
        val emulator = MapProbe(props = mapOf("ro.board.platform" to "", "ro.hardware" to "ranchu"), brand = "google", device = "generic_x86")
        val d = UnitDetector.detect(emulator)
        assertEquals(UnitFamily.GENERIC, d.family)
        assertEquals(0, d.score)
        assertTrue(d.runnersUp.isEmpty())
    }

    @Test
    fun theFakeAndroidVersion_countsForNothing() {
        // A unit saying Android 14 with API 29 and nothing else is still plain Android.
        val p = MapProbe(props = mapOf("ro.build.version.release" to "14"), sdkInt = 29)
        assertEquals(UnitFamily.GENERIC, UnitDetector.detect(p).family)
    }

    @Test
    fun theDebugOverride_namesTheFamily_whateverTheUnit() {
        val d = UnitDetector.detect(k706, debugOverride = "fyt")
        assertEquals(UnitFamily.FYT, d.family)
        assertTrue(d.overridden)
        assertEquals(UnitDetector.OVERRIDE_PROP, d.evidence.single().name)
        // Nonsense, or "unknown", is no override.
        assertEquals(UnitFamily.QF, UnitDetector.detect(k706, debugOverride = "banana").family)
        assertEquals(UnitFamily.QF, UnitDetector.detect(k706, debugOverride = "UNKNOWN").family)
        assertEquals(UnitFamily.QF, UnitDetector.detect(k706, debugOverride = "").family)
    }

    @Test
    fun evidenceFor_listsWhatPointsAtAFamily() {
        val ev = UnitDetector.evidenceFor(UnitFamily.QF, k706)
        assertTrue(ev.any { it.kind == "pkg" && it.name == "com.qf.framework" })
        assertTrue(ev.any { it.kind == "build" && it.value == "ROCO K706" })
        assertTrue(UnitDetector.evidenceFor(UnitFamily.REGLINK, k706).isEmpty())
    }

    @Test
    fun familyNames_parseInAnyCase() {
        assertEquals(UnitFamily.TOPWAY, UnitFamily.parse(" topway "))
        assertEquals(null, UnitFamily.parse(null))
        assertEquals(null, UnitFamily.parse("tesla"))
    }
}
