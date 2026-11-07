package com.openauto.dash

import com.openauto.dash.link.ClusterFace
import com.openauto.dash.link.ClusterState
import com.openauto.dash.link.DISPLAY_PROTOCOL
import com.openauto.dash.link.DisplayStats

/*
 * The second screen: a Raspberry Pi wired to a monitor, on the phone's hotspot
 * (see DisplayLink and tools/pi/README.md). What it should show and how, as
 * plain values and rules, so they are unit-tested without Android.
 */

enum class SecondScreenMode {
    OFF,

    /** A read-only instrument cluster: speed, media, directions, readings. */
    CLUSTER,

    /** A chosen app (Google Maps, say), running on the head unit and shown there. */
    APP
}

/** The cluster's pages, cycled from Settings, the tile or a steering-wheel key. */
enum class ClusterPage { DRIVE, MEDIA, NAV, OBD }

enum class ClusterBackground { BLACK, DARK, LIGHT }

/** How the display draws its own pages ("Simple display"); the names go over the link as they are. */
enum class ClusterDesign { CARDS, DIALS, LARGE, RETRO }

/** How far the display's tilted map looks toward the horizon; the names go over the link as they are (ClusterState.MapView). */
enum class MapTilt { LOW, MEDIUM, HIGH }

/** How much road around the car the display's tilted map takes in; the names go over the link as they are. */
enum class MapDistance { CLOSE, NORMAL, FAR }

data class SecondScreenConfig(
    val mode: SecondScreenMode = SecondScreenMode.CLUSTER,
    /** The app shown in [SecondScreenMode.APP]. */
    val appPackage: String? = null,
    /** The pages cycled through, in order; never empty. */
    val pages: List<ClusterPage> = ClusterPage.entries,
    val page: ClusterPage = ClusterPage.DRIVE,
    /** Stream the cluster as video (as on the head unit) rather than let the display draw it from data. */
    val video: Boolean = true,
    /** Tallest picture to stream, whatever the monitor's size: less is lighter on the Wi-Fi. */
    val maxHeight: Int = 720,
    val bitrateKbps: Int = 2_500,
    /** The monitor faces the back seats: video apps may play while driving. */
    val rearSeat: Boolean = false,
    /** Key codes learnt as "next page" (steering-wheel buttons). */
    val pageKeys: Set<Int> = emptySet(),
    /** A long press on media next / previous turns the page, while the cluster shows. */
    val mediaKeysTurnPages: Boolean = false,
    /** A sideways swipe on the launcher's bar turns the cluster's page instead of the launcher's. */
    val barSwipeTurnsPages: Boolean = false,
    /** Pages the driver laid out on the board for the full display (streamed); the others keep [ClusterLayouts.default]. */
    val layouts: Map<ClusterPage, ClusterLayout> = emptyMap(),
    /** The same for the simple display (drawn by the screen itself): its own pages, apart from the full display's. */
    val simpleLayouts: Map<ClusterPage, ClusterLayout> = emptyMap(),
    /** The cluster's background; it stays as chosen, day and night, unless [followDayNight]. */
    val background: ClusterBackground = ClusterBackground.BLACK,
    /** Light by day, [background] (or dark) by night, as the dashboard's automatic look. */
    val followDayNight: Boolean = false,
    /** An alert up on the dashboard also covers the whole second screen ([SecondScreenAlerts]). */
    val alertsFullScreen: Boolean = true,
    /** The look of the pages the display draws itself, when it isn't sent video. */
    val design: ClusterDesign = ClusterDesign.CARDS,
    /** The display's own map seen from behind the car, tilted and turning with it; false: flat, north up. */
    val mapTilted: Boolean = true,
    val mapTilt: MapTilt = MapTilt.MEDIUM,
    val mapDistance: MapDistance = MapDistance.CLOSE,
    /** On the tilted map, the buildings stand up in 3D. */
    val mapBuildings: Boolean = true,
    /** The monitor is mounted upside down: the display turns every picture 180°. */
    val upsideDown: Boolean = false,
    /** The monitor's backlight by day and by night, 1..100, for a display wired to its buttons. */
    val brightnessDay: Int = 100,
    val brightnessNight: Int = 30,
    /** The last display linked could set its brightness: the levels stay offered while it is away. */
    val brightnessWired: Boolean = false
) {
    /**
     * What [page] shows on the full display or, with [simple], on the simple
     * one (by default the one chosen, [video]): the driver's board, or the page as it comes.
     */
    fun layoutFor(page: ClusterPage, simple: Boolean = !video): ClusterLayout =
        layoutsFor(simple)[page] ?: ClusterLayouts.default(page)

    /** The pages laid out for the simple display, or the full one. */
    fun layoutsFor(simple: Boolean): Map<ClusterPage, ClusterLayout> = if (simple) simpleLayouts else layouts

    /** This config with [pages] as the simple display's layouts, or the full one's. */
    fun withLayouts(simple: Boolean, pages: Map<ClusterPage, ClusterLayout>): SecondScreenConfig =
        if (simple) copy(simpleLayouts = pages) else copy(layouts = pages)
}

/** How a cluster page shares the screen between its widgets; [slots] is how many it holds. */
enum class ClusterArrangement(val slots: Int) {
    /** One widget, the whole screen. */
    ONE(1),

    /** A big widget, a smaller one beside it. */
    BIG_SIDE(2),

    /** A big widget, two stacked beside it. */
    BIG_STACK(3),

    /** Two widgets, half each. */
    HALVES(2),

    /** Three widgets side by side. */
    THREE(3),

    /** Four widgets, two by two. */
    GRID(4)
}

/**
 * One widget on a cluster page, in the design it is drawn in. The dashboard's
 * 3D map ([BuiltinKind.NAVMAP]) is drawn afresh, and [BuiltinKind.MAPS_INSIDE]
 * is a copy of [app]'s picture from its tile on the dashboard: both have no
 * designs ([WidgetDesign.STANDARD]).
 */
data class ClusterSlot(val kind: BuiltinKind, val design: WidgetDesign, val app: String? = null)

/** A cluster page: its [arrangement] and as many [slots] as that holds, the big one first. */
data class ClusterLayout(val arrangement: ClusterArrangement, val slots: List<ClusterSlot>)

/** The cluster's pages as they come, and the rules for laying one out on the board. */
object ClusterLayouts {

    /**
     * The widgets the cluster can show: every widget the dashboard redraws
     * from its readings. Not the live views (map, windows), the bar, the
     * favourites or the screen light: they are things to touch, and the cluster can't be touched.
     */
    val KINDS: List<BuiltinKind> = BuiltinKind.entries - setOf(
        BuiltinKind.PIP_ANCHOR, BuiltinKind.MY_CAR, BuiltinKind.DASH_BAR, BuiltinKind.QUICK_DIAL, BuiltinKind.SCREEN_LIGHT,
        BuiltinKind.RADIOS, BuiltinKind.UNIT_LIGHT, BuiltinKind.RADIO_PRESETS, BuiltinKind.HOME_WORK,
        BuiltinKind.SHARE_ETA, BuiltinKind.VOICE_NOTES, BuiltinKind.QUICK_SWITCHES,
        BuiltinKind.WIFI_NETWORKS, BuiltinKind.BT_DEVICES, BuiltinKind.PARKING_TIMER, BuiltinKind.FUEL_LOG,
        // Its signal is chosen per tile; a board slot has nowhere to keep it.
        BuiltinKind.CUSTOM_GAUGE,
        // Lamps drawn by their own tile, which a board slot (a designed face) cannot show.
        BuiltinKind.INDICATORS
    )

    /** The live pictures: the 3D map and an app's copy. Drawn as they are, without designs. */
    val PICTURES = setOf(BuiltinKind.NAVMAP, BuiltinKind.MAPS_INSIDE)

    /** The app whose picture a new [BuiltinKind.MAPS_INSIDE] slot copies. */
    const val DEFAULT_APP = "com.google.android.apps.maps"

    /** The designs [kind] can wear on the cluster, the ones made for it first. Not Standard: that is the dashboard's own tile, which the cluster doesn't draw. */
    fun designsFor(kind: BuiltinKind): List<WidgetDesign> =
        if (kind in PICTURES) emptyList() else WidgetDesign.offeredFor(kind, framed = false).filter { it != WidgetDesign.STANDARD }

    /** What a new slot of [kind] wears: the first design made for it, else Hero; Standard for the pictures. */
    fun defaultDesign(kind: BuiltinKind): WidgetDesign =
        if (kind in PICTURES) WidgetDesign.STANDARD else designsFor(kind).firstOrNull { it.isSignature } ?: WidgetDesign.HERO

    /** Whether [design] is one [kind] can wear here. */
    private fun suits(kind: BuiltinKind, design: WidgetDesign): Boolean =
        if (kind in PICTURES) design == WidgetDesign.STANDARD else design in designsFor(kind)

    fun default(page: ClusterPage): ClusterLayout = when (page) {
        ClusterPage.DRIVE -> ClusterLayout(
            ClusterArrangement.BIG_STACK,
            listOf(
                ClusterSlot(BuiltinKind.SPEED_HUD, WidgetDesign.SPEED_TAPE),
                ClusterSlot(BuiltinKind.CLOCK, WidgetDesign.HERO),
                ClusterSlot(BuiltinKind.RANGE, WidgetDesign.FUEL_TANK)
            )
        )
        ClusterPage.MEDIA -> ClusterLayout(
            ClusterArrangement.BIG_STACK,
            listOf(
                ClusterSlot(BuiltinKind.MEDIA, WidgetDesign.COVER_ART),
                ClusterSlot(BuiltinKind.SPEED_HUD, WidgetDesign.HERO),
                ClusterSlot(BuiltinKind.CLOCK, WidgetDesign.HERO)
            )
        )
        ClusterPage.NAV -> ClusterLayout(
            ClusterArrangement.BIG_STACK,
            listOf(
                ClusterSlot(BuiltinKind.NAVIGATION, WidgetDesign.TURN_CARD),
                ClusterSlot(BuiltinKind.SPEED_HUD, WidgetDesign.HERO),
                ClusterSlot(BuiltinKind.CLOCK, WidgetDesign.HERO)
            )
        )
        ClusterPage.OBD -> ClusterLayout(
            ClusterArrangement.BIG_SIDE,
            listOf(
                ClusterSlot(BuiltinKind.TELEMETRY, WidgetDesign.TWIN_DIALS),
                ClusterSlot(BuiltinKind.OBD_ALL, WidgetDesign.PAPER)
            )
        )
    }

    /** Widgets for a slot a bigger arrangement adds, in order, skipping those already on the page. */
    private val FILLERS = listOf(BuiltinKind.SPEED_HUD, BuiltinKind.CLOCK, BuiltinKind.RANGE, BuiltinKind.MEDIA, BuiltinKind.NAVIGATION, BuiltinKind.TELEMETRY)

    /**
     * [layout] in [arrangement]: the widgets it had keep their place, the
     * ones that no longer fit are dropped, new slots get the page's own
     * widgets first, then common ones not on the page yet.
     */
    fun arrange(layout: ClusterLayout, arrangement: ClusterArrangement, page: ClusterPage): ClusterLayout {
        val slots = layout.slots.take(arrangement.slots).toMutableList()
        val candidates = default(page).slots + FILLERS.map { ClusterSlot(it, defaultDesign(it)) }
        for (c in candidates) {
            if (slots.size >= arrangement.slots) break
            if (slots.none { it.kind == c.kind }) slots += c
        }
        while (slots.size < arrangement.slots) slots += ClusterSlot(BuiltinKind.CLOCK, WidgetDesign.HERO)
        return ClusterLayout(arrangement, slots)
    }

    /**
     * [layout] with slot [index] showing [kind] in [design], or in its first
     * design when [design] doesn't suit it; [app] is the app an app picture
     * copies ([DEFAULT_APP] when not given).
     */
    fun withSlot(layout: ClusterLayout, index: Int, kind: BuiltinKind, design: WidgetDesign? = null, app: String? = null): ClusterLayout {
        if (index !in layout.slots.indices) return layout
        val wear = design?.takeIf { suits(kind, it) } ?: defaultDesign(kind)
        val copied = if (kind == BuiltinKind.MAPS_INSIDE) app ?: DEFAULT_APP else null
        return layout.copy(slots = layout.slots.toMutableList().also { it[index] = ClusterSlot(kind, wear, copied) })
    }

    /** [layout] made whole: as many slots as its arrangement holds, widgets the cluster shows, designs that suit them. */
    fun sanitize(layout: ClusterLayout, page: ClusterPage): ClusterLayout {
        val slots = layout.slots.filter { it.kind in KINDS }.map {
            val app = if (it.kind == BuiltinKind.MAPS_INSIDE) it.app?.takeIf(String::isNotBlank) ?: DEFAULT_APP else null
            (if (suits(it.kind, it.design)) it else it.copy(design = defaultDesign(it.kind))).copy(app = app)
        }
        return arrange(ClusterLayout(layout.arrangement, slots), layout.arrangement, page)
    }
}

/** What the head unit actually sends the display. */
enum class SecondScreenOutput {
    /** Nothing: off, or no display connected. */
    NONE,

    /** The cluster, drawn here and streamed as H.264. */
    VIDEO_CLUSTER,

    /** The chosen app, running on the streamed screen. */
    VIDEO_APP,

    /** Readings only; the display draws its own cluster. */
    DATA
}

/** Why the app asked for can't be shown (the display then shows the cluster from data). */
enum class SecondScreenBlock { NONE, NO_APP_CHOSEN, NO_VIDEO, VIDEO_WHILE_MOVING, APPS_NOT_SUPPORTED }

/** How the display's board is doing, from its own report ([DisplayStats.throttled], [DisplayStats.tempC]). */
enum class DisplayHealth {
    /** No report yet, or a display that doesn't send one. */
    UNKNOWN,
    OK,
    /** Its supply sags: it slows down and may crash. The usual cause of a stutter. */
    UNDER_POWERED,
    /** Too hot: it slows itself down. */
    HOT,
    /** Slowed down by its firmware for another reason. */
    THROTTLED
}

/** Whether the display's program and this app speak the same protocol ([DISPLAY_PROTOCOL]). */
enum class SecondScreenVersionGap { NONE, DISPLAY_OLDER, DISPLAY_NEWER }

object SecondScreenRules {

    /** The background the cluster shows now: the chosen one, or light by [day] when it follows day and night. */
    fun background(config: SecondScreenConfig, day: Boolean): ClusterBackground = when {
        !config.followDayNight -> config.background
        day -> ClusterBackground.LIGHT
        config.background == ClusterBackground.LIGHT -> ClusterBackground.DARK
        else -> config.background
    }

    /** The monitor's backlight now: the day level or the night one. */
    fun brightness(config: SecondScreenConfig, day: Boolean): Int = if (day) config.brightnessDay else config.brightnessNight

    val STREAM_HEIGHTS = listOf(480, 720, 1080)

    /**
     * An app goes onto the second screen with `am display move-stack`, through the
     * head unit's root shell. Android 12 dropped stacks and that command with them.
     */
    fun appsMovable(sdk: Int): Boolean = sdk <= 30

    /**
     * What to send, and why the choice isn't what [config] asks for, if it isn't.
     * [canStream]: the display decodes H.264 and the encoder here is available.
     * [appIsVideo]: the chosen app plays video (films, YouTube), which the
     * driver must not see while [moving] unless the monitor is for the back seats.
     * [canMoveApps]: this Android can move an app's window onto the streamed display ([appsMovable]).
     * [rearView]: the car is in reverse and the rear view goes to this screen
     * ([ReverseView.secondScreenShown]): the cluster is streamed for it, even
     * on a display that otherwise draws its own (its map), and back after.
     */
    fun output(
        config: SecondScreenConfig,
        connected: Boolean,
        canStream: Boolean,
        moving: Boolean,
        appIsVideo: Boolean,
        canMoveApps: Boolean = true,
        rearView: Boolean = false
    ): Pair<SecondScreenOutput, SecondScreenBlock> {
        if (config.mode == SecondScreenMode.OFF || !connected) return SecondScreenOutput.NONE to SecondScreenBlock.NONE
        if (rearView && canStream) return SecondScreenOutput.VIDEO_CLUSTER to SecondScreenBlock.NONE
        val cluster = if (config.video && canStream) SecondScreenOutput.VIDEO_CLUSTER else SecondScreenOutput.DATA
        if (config.mode == SecondScreenMode.CLUSTER) return cluster to SecondScreenBlock.NONE
        return when {
            !canMoveApps -> cluster to SecondScreenBlock.APPS_NOT_SUPPORTED
            config.appPackage == null -> cluster to SecondScreenBlock.NO_APP_CHOSEN
            !canStream -> SecondScreenOutput.DATA to SecondScreenBlock.NO_VIDEO
            appIsVideo && moving && !config.rearSeat -> cluster to SecondScreenBlock.VIDEO_WHILE_MOVING
            else -> SecondScreenOutput.VIDEO_APP to SecondScreenBlock.NONE
        }
    }

    /** The page [step] places after [current] in [pages] (wrapping); the first page when [current] isn't one of them. */
    fun nextPage(pages: List<ClusterPage>, current: ClusterPage, step: Int = 1): ClusterPage {
        if (pages.isEmpty()) return current
        val at = pages.indexOf(current)
        if (at < 0) return pages.first()
        return pages[Math.floorMod(at + step, pages.size)]
    }

    /**
     * The size to encode for a [screenWidth] × [screenHeight] monitor: its
     * shape, no taller than [maxHeight] nor bigger than the display's decoder
     * takes, in multiples of 16 (what hardware encoders work in; the display
     * scales the picture to the screen anyway).
     */
    fun streamSize(screenWidth: Int, screenHeight: Int, maxHeight: Int, decoderWidth: Int = 1920, decoderHeight: Int = 1080): Pair<Int, Int> {
        val w0 = screenWidth.coerceAtLeast(16)
        val h0 = screenHeight.coerceAtLeast(16)
        val scale = minOf(1.0, maxHeight.toDouble() / h0, decoderWidth.toDouble() / w0, decoderHeight.toDouble() / h0)
        fun align(v: Double) = (Math.round(v / 16.0) * 16).toInt().coerceAtLeast(16)
        return align(w0 * scale) to align(h0 * scale)
    }

    /** Lowest video bitrate adapted down to, kbit/s: still a legible cluster at 720p. */
    const val MIN_BITRATE_KBPS = 600

    /** How long the stream must play clean at its held rate before it tries a little more, ms. */
    const val BITRATE_PROBE_AFTER_MS = 60_000L

    /** How long a new stream's reports are ignored, ms: the display drops frames until its first key frame. */
    const val BITRATE_SETTLE_MS = 8_000L

    /** Fewest dropped frames in a report that count as the Wi-Fi failing: one lost frame of a still page isn't. */
    const val MIN_DROPS_TO_CUT = 3

    /**
     * Where the stream's bitrate stands: [kbps] the encoder runs at, [ceilingKbps]
     * the rate that last lost frames (null when none has, or it was lifted past
     * the target), [cleanSinceMs] since when every report came back clean,
     * [settleUntilMs] until when a new stream's reports are ignored.
     */
    data class BitrateState(
        val kbps: Int,
        val ceilingKbps: Int? = null,
        val cleanSinceMs: Long? = null,
        val settleUntilMs: Long = 0
    ) {
        /** Where a new stream started at [nowMs] begins: the target, or just under a known ceiling, which is kept (same Wi-Fi). */
        fun restart(targetKbps: Int, nowMs: Long): BitrateState = BitrateState(
            minOf(targetKbps, holdKbps(ceilingKbps, targetKbps)),
            ceilingKbps.takeIf { holdKbps(it, targetKbps) < targetKbps },
            settleUntilMs = nowMs + BITRATE_SETTLE_MS
        )
    }

    /** Just under [ceilingKbps] (a tenth below), never under the floor nor over [targetKbps]. */
    fun holdKbps(ceilingKbps: Int?, targetKbps: Int): Int =
        if (ceilingKbps == null) targetKbps else (ceilingKbps * 9 / 10).coerceIn(MIN_BITRATE_KBPS, maxOf(MIN_BITRATE_KBPS, targetKbps))

    /**
     * The next bitrate from the display's report of the last few seconds, so a
     * weak 2.4 GHz link settles instead of sawing. More than one frame in twenty
     * dropped: a quarter down, and the rate that failed becomes the ceiling. A
     * clean report: a tenth of [targetKbps] back up, but only to just under the
     * ceiling. Only after [BITRATE_PROBE_AFTER_MS] of clean play there does the
     * ceiling lift, by a twentieth of the target, until it clears the target.
     * A frame or two lost holds the rate and starts the clean stretch over, and
     * the first seconds of a new stream (frames dropped until a key frame) don't count.
     */
    fun adaptBitrate(state: BitrateState, targetKbps: Int, shown: Int, dropped: Int, nowMs: Long): BitrateState {
        val total = shown + dropped
        val current = state.kbps.coerceAtMost(targetKbps)
        return when {
            total == 0 || nowMs < state.settleUntilMs -> state.copy(kbps = current)
            dropped >= MIN_DROPS_TO_CUT && dropped * 20 > total -> BitrateState(maxOf(MIN_BITRATE_KBPS, current * 3 / 4), ceilingKbps = current)
            dropped > 0 -> state.copy(kbps = current, cleanSinceMs = null)
            else -> {
                val since = state.cleanSinceMs ?: nowMs
                var ceiling = state.ceilingKbps
                var cleanSince: Long? = since
                val hold = holdKbps(ceiling, targetKbps)
                if (ceiling != null && current >= hold && nowMs - since >= BITRATE_PROBE_AFTER_MS) {
                    ceiling += maxOf(50, targetKbps / 20)
                    if (holdKbps(ceiling, targetKbps) >= targetKbps) ceiling = null
                    cleanSince = nowMs
                }
                val top = holdKbps(ceiling, targetKbps)
                val next = if (current < top) minOf(top, current + maxOf(100, targetKbps / 10)) else current
                BitrateState(next, ceiling, cleanSince, state.settleUntilMs)
            }
        }
    }

    /** The display's temperature from which it is called hot, °C: a Pi 3 slows itself from 80 and crashes past 85. */
    const val HOT_C = 80

    /** How far the picture is lightened for a display that can't decode it in time: none, half the frames, then a size down as well. */
    const val MAX_RELIEF = 2

    fun health(report: DisplayStats?): DisplayHealth {
        if (report == null) return DisplayHealth.UNKNOWN
        val now = report.throttled and DisplayStats.THROTTLE_NOW
        return when {
            now and DisplayStats.THROTTLE_UNDER_VOLTAGE != 0 -> DisplayHealth.UNDER_POWERED
            now and DisplayStats.THROTTLE_SOFT_TEMP != 0 || (report.tempC ?: 0) >= HOT_C -> DisplayHealth.HOT
            now != 0 -> DisplayHealth.THROTTLED
            else -> DisplayHealth.OK
        }
    }

    /**
     * The frames the link lost in [report]: those dropped on the display for
     * any reason but a full decoder, plus the [refused] here. Only these say
     * anything about the Wi-Fi; the display's own slowness is [relief]'s business.
     */
    fun networkDrops(report: DisplayStats, refused: Int): Int = (report.framesDropped - report.droppedLate).coerceAtLeast(0) + refused

    /**
     * The next relief step from [current]: one more when the display dropped
     * more than one frame in twenty for want of decoder room (it is the
     * bottleneck, not the link), never past [MAX_RELIEF], and never while a
     * new stream settles ([settled] false: its decoder is still starting).
     */
    fun relief(current: Int, report: DisplayStats, settled: Boolean): Int {
        if (!settled || current >= MAX_RELIEF) return current
        val total = report.framesShown + report.framesDropped
        return if (report.droppedLate >= MIN_DROPS_TO_CUT && report.droppedLate * 20 > total) current + 1 else current
    }

    /** The frame rate at [relief]: halved from the first step on, never under 10. */
    fun reliefFps(fps: Int, relief: Int): Int = if (relief >= 1) maxOf(10, fps / 2) else fps

    /** The tallest picture at [relief]: one step of [STREAM_HEIGHTS] down from the second step on. */
    fun reliefHeight(maxHeight: Int, relief: Int): Int {
        if (relief < 2) return maxHeight
        val at = STREAM_HEIGHTS.indexOfFirst { it >= maxHeight }.takeIf { it > 0 } ?: return maxHeight.coerceAtMost(STREAM_HEIGHTS.first())
        return STREAM_HEIGHTS[at - 1]
    }

    fun versionGap(displayProtocol: Int): SecondScreenVersionGap = when {
        displayProtocol < DISPLAY_PROTOCOL -> SecondScreenVersionGap.DISPLAY_OLDER
        displayProtocol > DISPLAY_PROTOCOL -> SecondScreenVersionGap.DISPLAY_NEWER
        else -> SecondScreenVersionGap.NONE
    }

    /** A display that reads the words sent once ([com.openauto.dash.link.DisplayWords.labels]) rather than with each reading. */
    fun readsWordsOnce(displayProtocol: Int): Boolean = displayProtocol >= 2

    /** What the display draws for a board widget, when it has the readings for it; null for the others. */
    fun faceOf(kind: BuiltinKind): String? = when (kind) {
        BuiltinKind.SPEED_HUD -> ClusterFace.SPEED
        BuiltinKind.CLOCK -> ClusterFace.CLOCK
        BuiltinKind.RANGE -> ClusterFace.RANGE
        BuiltinKind.MEDIA -> ClusterFace.MEDIA
        BuiltinKind.NAVIGATION -> ClusterFace.NAV
        BuiltinKind.TELEMETRY -> ClusterFace.TELEMETRY
        BuiltinKind.OBD_ALL -> ClusterFace.MEASURES
        BuiltinKind.DOORS -> ClusterFace.DOORS
        BuiltinKind.ENGINE_TEMPS -> ClusterFace.COOLANT
        // The streamed 3D map's slot, and an app copy's (Waze, Maps): on a display that
        // draws itself, its own flat map stands in for both.
        BuiltinKind.NAVMAP, BuiltinKind.MAPS_INSIDE -> ClusterFace.MAP
        else -> null
    }

    /**
     * [layout] for the display to draw itself: its arrangement and a face per
     * slot, empty where the display has nothing for that widget. Null when it
     * has nothing for any of them: the display then draws its own page.
     */
    fun layoutMessage(layout: ClusterLayout): ClusterState.Layout? {
        val faces = layout.slots.map { faceOf(it.kind).orEmpty() }
        if (faces.all { it.isEmpty() }) return null
        return ClusterState.Layout(layout.arrangement.name, faces)
    }

    /** Density for the streamed screen: what a head unit of that height would use, so apps lay out alike. */
    fun streamDensity(height: Int): Int = when {
        height <= 480 -> 120
        height <= 600 -> 140
        height <= 720 -> 160
        else -> 240
    }

    /**
     * Addresses to try on the hotspot when the display didn't announce itself:
     * every other host of [address]'s network ([prefix] bits), limited to its
     * own /24 on a bigger network, skipping [skip] (this unit, the phone).
     * IPv4 as ints, most significant byte first.
     */
    fun hostsToProbe(address: Int, prefix: Int, skip: Set<Int> = emptySet()): List<Int> {
        val bits = prefix.coerceIn(24, 30)
        val mask = -1 shl (32 - bits)
        val network = address and mask
        val size = 1 shl (32 - bits)
        return (1 until size - 1).map { network or it }.filter { it != address && it !in skip }
    }
}

/** How lists are kept in preferences: comma-separated; unknown or broken entries are dropped. */
object SecondScreenCodec {
    fun encodePages(pages: List<ClusterPage>): String = pages.joinToString(",") { it.name }

    fun decodePages(raw: String?): List<ClusterPage> {
        if (raw == null) return ClusterPage.entries
        val pages = raw.split(',').mapNotNull { name -> ClusterPage.entries.firstOrNull { it.name == name.trim() } }.distinct()
        return pages.ifEmpty { ClusterPage.entries }
    }

    fun encodeKeys(keys: Set<Int>): String = keys.sorted().joinToString(",")

    fun decodeKeys(raw: String?): Set<Int> =
        raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { code -> code > 0 } }.toSet()

    /** Board pages as "DRIVE=BIG_STACK:SPEED_HUD/SPEED_TAPE,MAPS_INSIDE/STANDARD/com.google.android.apps.maps;MEDIA=...". */
    fun encodeLayouts(layouts: Map<ClusterPage, ClusterLayout>): String =
        layouts.entries.sortedBy { it.key.ordinal }.joinToString(";") { (page, layout) ->
            page.name + "=" + layout.arrangement.name + ":" + layout.slots.joinToString(",") { s ->
                s.kind.name + "/" + s.design.name + (s.app?.let { "/$it" } ?: "")
            }
        }

    /** A page that doesn't read back (unknown page or arrangement) keeps its original look; a widget that doesn't is replaced. */
    fun decodeLayouts(raw: String?): Map<ClusterPage, ClusterLayout> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<ClusterPage, ClusterLayout>()
        for (entry in raw.split(';')) {
            val page = ClusterPage.entries.firstOrNull { it.name == entry.substringBefore('=').trim() } ?: continue
            val body = entry.substringAfter('=', "")
            val arrangement = ClusterArrangement.entries.firstOrNull { it.name == body.substringBefore(':').trim() } ?: continue
            val slots = body.substringAfter(':', "").split(',').mapNotNull { s ->
                val parts = s.split('/').map { it.trim() }
                val kind = BuiltinKind.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
                ClusterSlot(kind, WidgetDesign.fromName(parts.getOrNull(1)), parts.getOrNull(2))
            }
            out[page] = ClusterLayouts.sanitize(ClusterLayout(arrangement, slots), page)
        }
        return out
    }
}

/**
 * When the display's cluster readings ([com.openauto.dash.link.ClusterState])
 * are worth sending: as soon as something shown changes, but no more than
 * every [minGapMs]; and every [heartbeatMs] regardless, which moves the
 * display's clock and tells it the link is alive. [key] is the state without
 * the clock.
 */
class ClusterThrottle(private val minGapMs: Long = 200, private val heartbeatMs: Long = 5_000) {
    private var lastKey: Any? = null
    private var lastSentAt = Long.MIN_VALUE / 2

    fun shouldSend(key: Any, now: Long): Boolean {
        val due = now - lastSentAt >= heartbeatMs || (key != lastKey && now - lastSentAt >= minGapMs)
        if (due) {
            lastKey = key
            lastSentAt = now
        }
        return due
    }

    fun reset() {
        lastKey = null
        lastSentAt = Long.MIN_VALUE / 2
    }
}

/** Apps that play video: the driver mustn't watch them while driving (see [SecondScreenBlock.VIDEO_WHILE_MOVING]). */
object VideoApps {
    /** `ApplicationInfo.CATEGORY_VIDEO`. */
    const val CATEGORY_VIDEO = 2

    /** Package names, or their prefixes: "com.netflix" covers "com.netflix.mediaclient". */
    private val KNOWN = listOf(
        "com.google.android.youtube", "com.google.android.apps.youtube", "app.revanced.android.youtube",
        "com.netflix", "com.amazon.avod", "com.disney.disneyplus", "org.videolan.vlc",
        "com.mxtech.videoplayer", "org.xbmc.kodi", "com.plexapp", "tv.twitch", "com.canal.android",
        "fr.francetv", "com.google.android.videos", "com.dailymotion", "com.hbo", "com.apple.atve"
    )

    fun isVideo(packageName: String, category: Int?): Boolean =
        category == CATEGORY_VIDEO || KNOWN.any { packageName == it || packageName.startsWith("$it.") }
}
