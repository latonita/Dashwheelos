package com.openauto.dash

/*
 * The pure parts of the XYAuto FM radio ([XyFmRadio], package com.android.fmradio): what its broadcasts say, the
 * frequency string it takes back, the list of stations the driver saved, and which source the media tile
 * shows. No Android here, so all of it is unit-tested.
 */

private val FREQ_TEXT = Regex("""(\d{2,3}(?:[.,]\d{1,2})?)\s*M""", RegexOption.IGNORE_CASE)

/**
 * The FM frequency (10 kHz units, 8820 = 88.20 MHz) a `com.android.radio.widget.freq_volue` broadcast names. [freq]
 * is FM in 10 kHz units and AM in kHz; [band] "FM"/"AM" (maybe "FM1", "am"); [freqStr] "88.2 MHZ". FM only, so an
 * AM broadcast, a frequency out of the FM band or nonsense is null. When [freq] is missing the text gives it.
 */
internal fun parseFreqVolue(freq: Int, band: String?, freqStr: String?): Int? {
    if (band?.trim()?.startsWith("AM", ignoreCase = true) == true) return null
    val fromText = freqStr?.let { FREQ_TEXT.find(it) }
        ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
    val f = freq.takeIf { it > 0 } ?: fromText ?: return null
    return f.takeIf(::isFm)
}

/**
 * The `fm` extra the radio tunes: tenths of a MHz as digits only ("882"; "88.2" is rejected), for a frequency in
 * 10 kHz units. The radio's range is 87.5 to 108 MHz, so null outside it (a 65 MHz station can't be asked for).
 */
internal fun fmExtra(freq: Int): String? = ((freq + 5) / 10).takeIf { it in FM_EXTRA_MIN..FM_EXTRA_MAX }?.toString()

private const val FM_EXTRA_MIN = 875
private const val FM_EXTRA_MAX = 1080

/** What the media tile shows for the radio on [station] (frequency in 10 kHz units; its name empty when unknown). */
internal fun fmMediaState(station: RadioStation, playing: Boolean): MediaState {
    val frequency = radioLabel(HeadUnitMedia.RadioStation(station.freq, 0, null))
    val named = station.name.isNotBlank()
    return MediaState(
        title = if (named) station.name else frequency,
        artist = if (named) "$frequency · FM" else "FM",
        isPlaying = playing,
        hasMedia = true
    )
}

/** How long a tune counts as proof of sound whatever the audio focus says (the focus is taken a moment later). */
internal const val FM_FRESH_MS = 3_000L

/**
 * Whether the radio is sounding: its screen is up, the last thing we sent wasn't pause, and it holds the audio
 * ([focus]) or has only just tuned ([sinceFreqMs] since its last broadcast).
 */
internal fun fmIsPlaying(alive: Boolean, paused: Boolean, focus: Boolean, sinceFreqMs: Long): Boolean =
    alive && !paused && (focus || sinceFreqMs in 0 until FM_FRESH_MS)

/**
 * Whether the radio holds the audio, from `AudioManager.getCurrentAudioFocusContentName()` (a vendor API: [apiOk]
 * false when it isn't there, then only [musicActive] is left to go by). A name that doesn't mention the radio's
 * package is somebody else's focus; no name at all is nobody's, and [musicActive] decides.
 */
internal fun fmHasFocus(name: String?, apiOk: Boolean, musicActive: Boolean): Boolean = when {
    !apiOk || name.isNullOrBlank() -> musicActive
    else -> name.contains("fmradio", ignoreCase = true)
}

/** The radio is the unit's source: it has the audio, or it is up and no media session is playing. */
internal fun fmIsCurrentSource(alive: Boolean, focus: Boolean, otherSessionPlaying: Boolean): Boolean =
    focus || (alive && !otherSessionPlaying)

/** A media session as far as the choice of source cares. */
internal data class SessionView(val packageName: String?, val playing: Boolean)

/** What the media tile follows. */
internal sealed class SourcePick {
    /** The radio. */
    object Radio : SourcePick()

    /** The session at [index]. */
    data class Session(val index: Int) : SourcePick()

    /** Nothing. */
    object None : SourcePick()
}

/**
 * The media tile's source on a unit whose radio tells for itself ([StockRadio.judgesSource]). A session that is
 * really PLAYING wins, unless the radio holds the audio; then a radio that is up wins over sessions that only idle,
 * and over the empty one the unit always keeps (a Bluetooth session with no state); then the first session.
 */
internal fun pickSource(sessions: List<SessionView>, radioAlive: Boolean, radioFocus: Boolean, radioPackage: String? = null): SourcePick {
    // The radio's own session (where it has one) speaks for the radio: title, radio text and buttons come from it.
    val own = if (radioPackage == null) -1 else sessions.indexOfFirst { it.packageName == radioPackage }
    val radio = if (own >= 0) SourcePick.Session(own) else SourcePick.Radio
    if (radioFocus) return radio
    val playing = sessions.indexOfFirst { it.playing }
    if (playing >= 0) return SourcePick.Session(playing)
    if (radioAlive) return radio
    return if (sessions.isNotEmpty()) SourcePick.Session(0) else SourcePick.None
}

/** One station the driver saved: [freq] in 10 kHz units, [name] empty when none is known. */
internal data class FmEntry(val freq: Int, val name: String = "", val pi: String = "")

/** The most frequencies one station keeps. */
internal const val FM_MAX_FREQS = 8

/**
 * A preset: a station with a [name] (empty when none), the RDS [pi] that tells it from others (empty when unknown), and
 * the [freqs] it is heard on, in the order they were added; [last] is the one used last (always one of [freqs]).
 */
internal data class FmStation(val name: String, val pi: String, val freqs: List<Int>, val last: Int)

/**
 * The stations the driver saved for the presets tile, in the order they were added (never reordered), at most
 * [StockRadio.SHOWN] and with no frequency twice. Nothing is learned from what the radio tunes: only [add] puts a
 * frequency in, and it joins the station with the same PI when there is one. The radio keeps its own presets private,
 * so this is Dashwheel's own list. Immutable: every change gives a new list.
 *
 * Stored so that an older build loses nothing: [serialize] writes every frequency as a line of its own (it shows
 * them as separate presets), [serializePis] the PI of each, and [serializeGroups] which lines are one station.
 */
internal class FmStations(val stations: List<FmStation> = emptyList()) {

    /** One entry per frequency, station by station: how the old lines look. */
    val entries: List<FmEntry> get() = stations.flatMap { s -> s.freqs.map { FmEntry(it, s.name, s.pi) } }

    private fun indexOf(freq: Int): Int = stations.indexOfFirst { freq in it.freqs }

    fun contains(freq: Int): Boolean = indexOf(freq) >= 0

    /** The station with [pi] (a valid one), or -1. */
    private fun matching(pi: String): Int {
        val p = parseFmPi(pi) ?: return -1
        return stations.indexOfFirst { it.pi == p }
    }

    /** Whether [freq] could be [add]ed: FM, not saved yet, and there is room (or a station with its [pi] to join). */
    fun canAdd(freq: Int, max: Int = StockRadio.SHOWN, pi: String = ""): Boolean {
        if (!isFm(freq) || contains(freq)) return false
        val m = matching(pi)
        return if (m >= 0) stations[m].freqs.size < FM_MAX_FREQS else stations.size < max
    }

    /**
     * [freq] saved: after the frequencies of the station with the same [pi] when there is one, else as a station of
     * its own, after the others. The same list when it can't be ([canAdd]).
     */
    fun add(freq: Int, name: String = "", max: Int = StockRadio.SHOWN, pi: String = ""): FmStations {
        if (!canAdd(freq, max, pi)) return this
        val m = matching(pi)
        if (m >= 0) return FmStations(stations.mapIndexed { i, s -> if (i == m) s.copy(freqs = s.freqs + freq, last = freq) else s })
        return FmStations(stations + FmStation(clean(name), parseFmPi(pi).orEmpty(), listOf(freq), freq))
    }

    /** [pi] kept for the station of the saved [freq] when it has none yet; never over a PI it has, never for others. */
    fun fillPi(freq: Int, pi: String?): FmStations {
        val p = parseFmPi(pi) ?: return this
        val at = indexOf(freq)
        if (at < 0 || stations[at].pi.isNotEmpty()) return this
        return FmStations(stations.mapIndexed { i, s -> if (i == at) s.copy(pi = p) else s })
    }

    fun piOf(freq: Int): String = stations.getOrNull(indexOf(freq))?.pi.orEmpty()

    /** The station of [freq] named [name] (blank clears it); the same list when [freq] isn't saved. */
    fun rename(freq: Int, name: String): FmStations {
        val at = indexOf(freq)
        if (at < 0) return this
        return FmStations(stations.mapIndexed { i, s -> if (i == at) s.copy(name = clean(name).take(MAX_NAME)) else s })
    }

    /** The whole station of [freq] gone. */
    fun remove(freq: Int): FmStations = FmStations(stations.filterNot { freq in it.freqs })

    /** [freq] gone from its station; the station too when it was the last one. */
    fun removeFreq(freq: Int): FmStations {
        val at = indexOf(freq)
        if (at < 0) return this
        val s = stations[at]
        val left = s.freqs - freq
        if (left.isEmpty()) return remove(freq)
        return FmStations(stations.mapIndexed { i, t -> if (i == at) s.copy(freqs = left, last = if (s.last == freq) left.first() else s.last) else t })
    }

    /** [freq] is what plays: it becomes the last one of its station. The same list when it already is, or isn't saved. */
    fun played(freq: Int): FmStations {
        val at = indexOf(freq)
        if (at < 0 || stations[at].last == freq) return this
        return FmStations(stations.mapIndexed { i, s -> if (i == at) s.copy(last = freq) else s })
    }

    /**
     * What a tap on the station of [freq] tunes, while [playing] is what plays: the last one used when the station
     * isn't playing; the next of its frequencies (the first after the end) when it is.
     */
    fun tapTarget(freq: Int, playing: Int?): Int {
        val s = stations.getOrNull(indexOf(freq)) ?: return freq
        val at = if (playing == null) -1 else s.freqs.indexOf(playing)
        return if (at >= 0) s.freqs[(at + 1) % s.freqs.size] else s.last
    }

    /** The stations in the order shown, each at its last used frequency. */
    fun shown(): List<RadioStation> = stations.map { RadioStation(it.last, it.name, it.pi, it.freqs) }

    fun nameOf(freq: Int): String = stations.getOrNull(indexOf(freq))?.name.orEmpty()

    /**
     * One "freq TAB name" line per frequency. The PI codes and the grouping are NOT in these lines: an older build reads
     * only 2-field (or 5-field) lines and would drop a longer one with the preset, so they have their own text.
     */
    fun serialize(): String = entries.joinToString("\n") { "${it.freq}\t${it.name}" }

    /** One "freq TAB PI" line per frequency whose station has a PI; read back by [parse]'s `pis`. */
    fun serializePis(): String = entries.filter { it.pi.isNotEmpty() }.joinToString("\n") { "${it.freq}\t${it.pi}" }

    /** One "f1,f2,f3 TAB last" line per station with more than one frequency; read back by [parse]'s `groups`. */
    fun serializeGroups(): String =
        stations.filter { it.freqs.size > 1 }.joinToString("\n") { "${it.freqs.joinToString(",")}\t${it.last}" }

    companion object {
        /** The longest name kept, in characters. */
        const val MAX_NAME = 24

        private val BLANKS = Regex("[\t\r\n]")

        private fun clean(name: String) = name.replace(BLANKS, " ").trim()

        /**
         * The lists [serialize]d, as far as they make sense (a damaged line is dropped, so is anything beyond the cap).
         * Also reads the older format, which learned every station heard ("freq, name, pin, heard, at" per line): only
         * the pinned ones (the driver's own choice) are kept, in the order they were pinned. A line of [groups] joins
         * frequencies that are all saved (the others are dropped from it); a frequency in two lines stays in the first.
         * Without [groups] every line is a station: old data never merges by itself.
         */
        fun parse(text: String?, max: Int = StockRadio.SHOWN, pis: String? = null, groups: String? = null): FmStations {
            val pinned = ArrayList<Pair<Int, FmEntry>>()
            val saved = ArrayList<FmEntry>()
            for (line in text.orEmpty().lineSequence()) {
                val p = line.split('\t')
                val freq = p[0].toIntOrNull()?.takeIf(::isFm) ?: continue
                when (p.size) {
                    2 -> saved += FmEntry(freq, clean(p[1]))
                    3 -> saved += FmEntry(freq, clean(p[1]), parseFmPi(p[2]).orEmpty())
                    5 -> p[2].toIntOrNull()?.takeIf { it > 0 }?.let { pinned += it to FmEntry(freq, clean(p[1])) }
                }
            }
            val byFreq = FmPis.parse(pis).pis
            val flat = (pinned.sortedBy { it.first }.map { it.second } + saved).distinctBy { it.freq }
                .map { if (it.pi.isEmpty()) it.copy(pi = byFreq[it.freq].orEmpty()) else it }
            val known = flat.associateBy { it.freq }
            // The group of each frequency, from the lines whose frequencies are saved.
            val groupOf = HashMap<Int, Pair<List<Int>, Int>>()
            for (line in groups.orEmpty().lineSequence()) {
                val p = line.split('\t')
                if (p.size != 2) continue
                val freqs = p[0].split(',').mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in known && it !in groupOf }.distinct().take(FM_MAX_FREQS)
                if (freqs.size < 2) continue
                val last = p[1].trim().toIntOrNull()?.takeIf { it in freqs } ?: freqs.first()
                freqs.forEach { groupOf[it] = freqs to last }
            }
            val out = ArrayList<FmStation>()
            val done = HashSet<Int>()
            for (e in flat) {
                if (e.freq in done) continue
                val g = groupOf[e.freq]
                val members = g?.first ?: listOf(e.freq)
                done += members
                val named = members.map { known.getValue(it) }
                out += FmStation(
                    name = named.first().name,
                    pi = named.firstOrNull { it.pi.isNotEmpty() }?.pi.orEmpty(),
                    freqs = members,
                    last = g?.second ?: e.freq
                )
            }
            return FmStations(out.take(max))
        }
    }
}

// --- The radio's own MediaSession ---------------------------------------------------------------------------

/** The frequency (10 kHz units) in a session's `MEDIA_ID` ("fm:8820"); null for anything else. */
internal fun parseFmMediaId(id: String?): Int? =
    id?.trim()?.takeIf { it.startsWith("fm:", ignoreCase = true) }?.substring(3)?.trim()?.toIntOrNull()?.takeIf(::isFm)

private val FREQUENCY_TEXT = Regex("""\s*\d{2,3}([.,]\d{1,2})?\s*(MHz|FM)\s*|\s*\d{2,3}[.,]\d{1,2}\s*""", RegexOption.IGNORE_CASE)

/** The station name a session's title carries, or null: blank, or only the frequency ("88.2 MHz") is not a name. */
internal fun fmBroadcastName(title: String?): String? =
    title?.replace(Regex("[\t\r\n]"), " ")?.trim()?.takeIf { it.isNotEmpty() && !FREQUENCY_TEXT.matches(it) }?.take(FmStations.MAX_NAME)

/** What the media tile shows for the radio's session: its title, the radio text under it, else the frequency. */
internal fun fmSessionState(title: String?, text: String?, freq: Int?, playing: Boolean): MediaState {
    val frequency = freq?.let { radioLabel(HeadUnitMedia.RadioStation(it, 0, null)) }
    val name = fmBroadcastName(title)
    return MediaState(
        title = name ?: frequency ?: title.orEmpty().trim().ifEmpty { "FM" },
        artist = text?.trim().orEmpty().ifEmpty { listOfNotNull(frequency, "FM").joinToString(" · ") },
        isPlaying = playing,
        hasMedia = true
    )
}

/** The name a preset shows: the driver's own, else the one the station broadcast, else none (the frequency shows). */
internal fun fmDisplayName(own: String?, learned: String?): String = own.orEmpty().ifBlank { learned.orEmpty() }

/**
 * The broadcast names heard, by frequency, oldest first and at most [max]; one "freq TAB name" line each when
 * [serialize]d. Immutable.
 */
internal class FmLearned(val names: Map<Int, String> = emptyMap()) {
    fun nameOf(freq: Int): String = names[freq].orEmpty()

    /** [name] kept for [freq]; the same when it isn't a name, or not new. */
    fun learn(freq: Int, name: String?, max: Int = 120): FmLearned {
        val n = fmBroadcastName(name) ?: return this
        if (!isFm(freq) || names[freq] == n) return this
        val next = LinkedHashMap(names)
        next.remove(freq)
        next[freq] = n
        while (next.size > max) next.remove(next.keys.first())
        return FmLearned(next)
    }

    fun serialize(): String = names.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    companion object {
        fun parse(text: String?): FmLearned {
            val m = LinkedHashMap<Int, String>()
            for (line in text.orEmpty().lineSequence()) {
                val p = line.split('\t')
                if (p.size != 2) continue
                val f = p[0].toIntOrNull()?.takeIf(::isFm) ?: continue
                val n = fmBroadcastName(p[1]) ?: continue
                m[f] = n
            }
            return FmLearned(m)
        }
    }
}

/** The RDS PI a session reports ("3033", 4 hex digits) in upper case; null when absent, not hex, or 0000. */
internal fun parseFmPi(text: String?): String? =
    text?.trim()?.takeIf { it.length == 4 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
        ?.uppercase()?.takeIf { it != "0000" }

/** The PI codes heard, by frequency; one "freq TAB PI" line each when [serialize]d. Immutable. */
internal class FmPis(val pis: Map<Int, String> = emptyMap()) {
    fun piOf(freq: Int): String = pis[freq].orEmpty()

    fun learn(freq: Int, pi: String?, max: Int = 120): FmPis {
        val p = parseFmPi(pi) ?: return this
        if (!isFm(freq) || pis[freq] == p) return this
        val next = LinkedHashMap(pis)
        next.remove(freq)
        next[freq] = p
        while (next.size > max) next.remove(next.keys.first())
        return FmPis(next)
    }

    fun serialize(): String = pis.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    companion object {
        fun parse(text: String?): FmPis {
            val m = LinkedHashMap<Int, String>()
            for (line in text.orEmpty().lineSequence()) {
                val p = line.split('\t')
                if (p.size != 2) continue
                val f = p[0].toIntOrNull()?.takeIf(::isFm) ?: continue
                m[f] = parseFmPi(p[1]) ?: continue
            }
            return FmPis(m)
        }
    }
}
