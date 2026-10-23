package com.openauto.unitproto

/**
 * The firmware families Chinese Android head units ship with, as far as an
 * app can tell them apart. Each has its own way of telling apps about the car
 * (ignition, doors, speed), its own key service, its own sleep manager and
 * its own launcher choice; [UnitDetector] names the one a unit runs, and
 * Dashwheel picks the adapter that speaks to it.
 */
enum class UnitFamily {
    /** QF (Hizpo / ROCO K706, QF001 UIS7862, QF005 UIS8581a): Dashwheel's home unit. */
    QF,
    /** FYT / SYU (Joying, Atoto, Mekede, Junsun, Teyes...; UIS7862, UIS8581a, SC9853i; com.syu.ms). */
    FYT,
    /** Topway TS (TS10 UIS7862, TS18, TS7/TS8 Allwinner; com.tw.car). */
    TOPWAY,
    /** Microntek MTCD / MTCE (PX5, PX6, PX30) and HCT's MTCH (QCM6125): android.microntek.CarManager. */
    MTC,
    /** Reglink on MediaTek AC8227L / AC8257: the cheapest units; com.reglink.services. */
    REGLINK,
    /** Allwinner T3 / T8 units with their own firmware, not Topway's. */
    ALLWINNER,
    /** Nowadays (NWD) units: persist.nwd.launcher.default. */
    NWD,
    /** XYAuto "9320"-class units on MediaTek AC8227L / AC8257: com.kyhero.car.myhost. */
    XYAUTO,
    /** Nothing recognised: plain Android. */
    GENERIC,
    /** Not probed yet. */
    UNKNOWN;

    companion object {
        /** The family named by [text] (its name, any case), or null. */
        fun parse(text: String?): UnitFamily? = text?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { t -> entries.firstOrNull { it.name.equals(t, ignoreCase = true) } }
    }
}
