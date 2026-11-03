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
internal data class FmEntry(val freq: Int, val name: String = "")

/**
 * The stations the driver saved for the presets tile, in the order they were added (never reordered), at most
 * [StockRadio.SHOWN] and without duplicates. Nothing is learned from what the radio tunes: only [add] puts a station
 * in. The radio keeps its own presets private, so this is Dashwheel's own list.
 * Immutable: every change gives a new list, which [serialize]s to one line per station for the preferences.
 */
internal class FmStations(val entries: List<FmEntry> = emptyList()) {

    fun contains(freq: Int): Boolean = entries.any { it.freq == freq }

    /** Whether [freq] could be [add]ed: FM, not saved yet, and there is room. */
    fun canAdd(freq: Int, max: Int = StockRadio.SHOWN): Boolean = isFm(freq) && !contains(freq) && entries.size < max

    /** [freq] saved after the others; the same list when it can't be ([canAdd]). */
    fun add(freq: Int, name: String = "", max: Int = StockRadio.SHOWN): FmStations =
        if (canAdd(freq, max)) FmStations(entries + FmEntry(freq, clean(name))) else this

    /** [freq] saved under [name] (blank clears it); the same list when [freq] isn't saved. */
    fun rename(freq: Int, name: String): FmStations =
        if (!contains(freq)) this
        else FmStations(entries.map { if (it.freq == freq) FmEntry(freq, clean(name).take(MAX_NAME)) else it })

    fun remove(freq: Int): FmStations = FmStations(entries.filter { it.freq != freq })

    /** The stations in the order shown. */
    fun shown(): List<RadioStation> = entries.map { RadioStation(it.freq, it.name) }

    fun nameOf(freq: Int): String = entries.firstOrNull { it.freq == freq }?.name.orEmpty()

    fun serialize(): String = entries.joinToString("\n") { "${it.freq}\t${it.name}" }

    companion object {
        /** The longest name kept, in characters. */
        const val MAX_NAME = 24

        private val BLANKS = Regex("[\t\r\n]")

        private fun clean(name: String) = name.replace(BLANKS, " ").trim()

        /**
         * The list [serialize]d, as far as it makes sense (a damaged line is dropped, so is anything beyond the cap).
         * Also reads the older format, which learned every station heard ("freq, name, pin, heard, at" per line): only
         * the pinned ones (the driver's own choice) are kept, in the order they were pinned.
         */
        fun parse(text: String?, max: Int = StockRadio.SHOWN): FmStations {
            val pinned = ArrayList<Pair<Int, FmEntry>>()
            val saved = ArrayList<FmEntry>()
            for (line in text.orEmpty().lineSequence()) {
                val p = line.split('\t')
                val freq = p[0].toIntOrNull()?.takeIf(::isFm) ?: continue
                when (p.size) {
                    2 -> saved += FmEntry(freq, clean(p[1]))
                    5 -> p[2].toIntOrNull()?.takeIf { it > 0 }?.let { pinned += it to FmEntry(freq, clean(p[1])) }
                }
            }
            val all = pinned.sortedBy { it.first }.map { it.second } + saved
            return FmStations(all.distinctBy { it.freq }.take(max))
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
