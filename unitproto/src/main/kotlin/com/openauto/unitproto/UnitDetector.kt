package com.openauto.unitproto

/**
 * What the detector may ask of the unit. Implemented on Android over
 * SystemProperties, PackageManager, ServiceManager and the file system; in
 * tests by [MapProbe].
 */
interface UnitProbe {
    /** The system property's value, or null when unset or blank. */
    fun prop(name: String): String?
    fun hasPackage(pkg: String): Boolean
    /** Whether a system service of that name is registered (ServiceManager.checkService). */
    fun hasService(name: String): Boolean
    /** Whether a framework class of that name loads (a vendor API baked into the ROM). */
    fun hasClass(name: String): Boolean
    fun exists(path: String): Boolean
    /** The real Android version: most of these units lie in Build.VERSION.RELEASE, never in the SDK level. */
    val sdkInt: Int
    val brand: String
    val device: String
    val model: String
    /** Build.DISPLAY, the firmware's own version string. */
    val display: String
}

/** A probe answering from maps, for tests and the fake-firmware app. */
data class MapProbe(
    val props: Map<String, String> = emptyMap(),
    val packages: Set<String> = emptySet(),
    val services: Set<String> = emptySet(),
    val classes: Set<String> = emptySet(),
    val files: Set<String> = emptySet(),
    override val sdkInt: Int = 29,
    override val brand: String = "",
    override val device: String = "",
    override val model: String = "",
    override val display: String = ""
) : UnitProbe {
    override fun prop(name: String): String? = props[name]?.takeIf { it.isNotBlank() }
    override fun hasPackage(pkg: String): Boolean = pkg in packages
    override fun hasService(name: String): Boolean = name in services
    override fun hasClass(name: String): Boolean = name in classes
    override fun exists(path: String): Boolean = path in files
}

/** One thing found on the unit that points at a family: a property, a package, a binder, a class, a file, a build field. */
data class Evidence(val kind: String, val name: String, val value: String, val strong: Boolean) {
    override fun toString(): String = "$kind $name=$value"
}

/** The detector's answer: the family, how sure ([score]), what it saw, and what else came close. */
data class Detection(
    val family: UnitFamily,
    val score: Int,
    val evidence: List<Evidence>,
    val runnersUp: Map<UnitFamily, Int> = emptyMap(),
    /** Set when the answer came from the debug override property, not from the unit. */
    val overridden: Boolean = false
)

/**
 * Names the firmware family a unit runs. Each family has rules: a strong one
 * (worth [STRONG] points) decides alone, weak ones ([WEAK]) need two of a
 * kind. The highest score wins, ties going to the family listed first; a
 * unit that reaches [NEEDED] for no family is [UnitFamily.GENERIC].
 *
 * The rules come from the community's reverse engineering of each firmware
 * (docs/unit-platforms.md), so a unit may well match none of them: that is
 * what the Settings override is for.
 */
object UnitDetector {
    /** In debug builds the emulator harness names the family here (persist.*, survives reboots). */
    const val OVERRIDE_PROP = "persist.dashwheel.family"

    const val STRONG = 2
    const val WEAK = 1
    const val NEEDED = 2

    private class Rule(val kind: String, val name: String, val strong: Boolean, val find: (UnitProbe) -> String?)

    private fun prop(name: String, strong: Boolean) = Rule("prop", name, strong) { it.prop(name) }
    private fun propIn(name: String, values: Set<String>, strong: Boolean) =
        Rule("prop", name, strong) { p -> p.prop(name)?.takeIf { v -> values.any { it.equals(v, ignoreCase = true) } } }
    private fun propLike(name: String, pattern: Regex, strong: Boolean) =
        Rule("prop", name, strong) { p -> p.prop(name)?.takeIf { pattern.containsMatchIn(it) } }
    private fun pkg(name: String, strong: Boolean) = Rule("pkg", name, strong) { if (it.hasPackage(name)) "present" else null }
    private fun file(path: String, strong: Boolean) = Rule("file", path, strong) { if (it.exists(path)) "present" else null }

    private val TS_BUILD = Regex("^TS\\d+\\.")
    private val SUN = Regex("^sun(8i|50i|\\d)", RegexOption.IGNORE_CASE)
    /** The MediaTek automotive SoCs XYAuto builds on, as the ROMs spell them. */
    private val AC822X = setOf("ac8227l", "ac8227", "ac8257")

    /** The families in the order ties are broken, each with its rules. */
    private val RULES: List<Pair<UnitFamily, List<Rule>>> = listOf(
        UnitFamily.QF to listOf(
            prop("sys.qf.is.acc.on", strong = true),
            prop("persist.sys.qf.launcher", strong = true),
            pkg("com.qf.framework", strong = true),
            prop("ro.qf.platform", strong = false),
            Rule("binder", "mcu_service+util_service", strong = false) { p ->
                if (p.hasService("mcu_service") && p.hasService("util_service")) "present" else null
            },
            Rule("build", "brand/device", strong = false) { p ->
                if (p.brand.equals("ROCO", true) && p.device.equals("K706", true)) "ROCO K706" else null
            }
        ),
        UnitFamily.FYT to listOf(
            prop("ro.fyt.platform", strong = true),
            prop("ro.fyt.uiid", strong = true),
            prop("ro.build.fytmanufacturer", strong = true),
            prop("ro.build.fytid", strong = true),
            pkg("com.syu.ms", strong = true),
            prop("sys.carlink.type", strong = false),
            pkg("com.syu.canbus", strong = false),
            file("/oem/app", strong = false)
        ),
        UnitFamily.MTC to listOf(
            Rule("class", "android.microntek.CarManager+carservice", strong = true) { p ->
                if (p.hasClass("android.microntek.CarManager") && p.hasService("carservice")) "present" else null
            },
            file("/system/priv-app/MTCManager", strong = true),
            file("/system/priv-app/HCTManagerService", strong = true),
            propIn("ro.board.platform", setOf("rk3188", "rk3368", "rk3399", "px30", "px3", "px5", "px6"), strong = false),
            Rule("class", "android.microntek.CarManager", strong = false) { p ->
                if (p.hasClass("android.microntek.CarManager")) "present" else null
            }
        ),
        UnitFamily.TOPWAY to listOf(
            pkg("com.tw.car", strong = true),
            pkg("com.tw.carchoose", strong = true),
            pkg("com.tw.carapps", strong = true),
            pkg("com.tv.carinfoservice", strong = true),
            Rule("build", "model", strong = false) { p -> p.model.takeIf { it.equals("ums512_1h10_Natv", true) } },
            Rule("build", "display", strong = false) { p -> p.display.takeIf { TS_BUILD.containsMatchIn(it) } },
            pkg("com.zjinnova.zlink", strong = false)
        ),
        // Before Reglink: both families live on the same MediaTek boards, and on a
        // board-only tie the AC8227L belongs to XYAuto, whose own list names it.
        UnitFamily.XYAUTO to listOf(
            pkg("com.kyhero.car.myhost", strong = true),
            pkg("com.kyhero.car.myplugin.canbus", strong = true),
            propIn("ro.board.platform", AC822X, strong = false),
            propIn("ro.hardware", AC822X, strong = false),
            // The launcher is AOSP's own package name, present on countless unrelated
            // units: it counts only together with the board underneath it.
            Rule("pkg", "com.android.launcher+board", strong = false) { p ->
                if (p.hasPackage("com.android.launcher") && AC822X.any { it.equals(p.prop("ro.board.platform"), true) }) "present" else null
            }
        ),
        UnitFamily.REGLINK to listOf(
            pkg("com.reglink.services", strong = true),
            pkg("com.reglink.launcher4", strong = true),
            file("/system/etc/reglink_autorun_whitelist.txt", strong = true),
            propIn("ro.board.platform", setOf("mt6580", "mt8127", "mt6765", "mt6761"), strong = false),
            prop("persist.duraspeed.support", strong = false)
        ),
        UnitFamily.NWD to listOf(
            prop("persist.nwd.launcher.default", strong = true),
            prop("ro.nwd.start.launcher.by.fw", strong = true)
        ),
        UnitFamily.ALLWINNER to listOf(
            propLike("ro.board.platform", SUN, strong = false),
            propLike("ro.hardware", SUN, strong = false),
            propLike("ro.product.board", Regex("allwinner", RegexOption.IGNORE_CASE), strong = false)
        )
    )

    /**
     * The family [p] runs. [debugOverride] is the value of [OVERRIDE_PROP], passed
     * only by debug builds: a family named there is the answer, whatever the unit.
     */
    fun detect(p: UnitProbe, debugOverride: String? = null): Detection {
        UnitFamily.parse(debugOverride)?.takeIf { it != UnitFamily.UNKNOWN }?.let {
            return Detection(it, STRONG, listOf(Evidence("override", OVERRIDE_PROP, it.name, strong = true)), overridden = true)
        }
        val found = RULES.map { (family, rules) -> family to evidence(rules, p) }
        val scores = found.associate { (family, ev) -> family to score(ev) }
        val top = scores.values.maxOrNull() ?: 0
        val best = if (top >= NEEDED) found.first { (family, _) -> scores.getValue(family) == top } else null
        val others = scores.filter { it.value > 0 && it.key != best?.first }
        return if (best == null) Detection(UnitFamily.GENERIC, 0, found.flatMap { it.second }, others)
        else Detection(best.first, top, best.second, others)
    }

    /** What on [p] points at [family], strongest first. */
    fun evidenceFor(family: UnitFamily, p: UnitProbe): List<Evidence> =
        RULES.firstOrNull { it.first == family }?.let { evidence(it.second, p) }.orEmpty()

    private fun evidence(rules: List<Rule>, p: UnitProbe): List<Evidence> =
        rules.mapNotNull { r -> runCatching { r.find(p) }.getOrNull()?.let { Evidence(r.kind, r.name, it, r.strong) } }
            .sortedByDescending { it.strong }

    private fun score(ev: List<Evidence>): Int = ev.sumOf { if (it.strong) STRONG else WEAK }
}
