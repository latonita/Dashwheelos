package com.openauto.dash

/*
 * The pure parts of the XYAuto FM radio ([XyFmRadio], package com.android.fmradio): what its broadcasts say, the
 * frequency string it takes back, the list of stations Dashwheel learns from them, and which source the media tile
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
internal fun pickSource(sessions: List<SessionView>, radioAlive: Boolean, radioFocus: Boolean): SourcePick {
    if (radioFocus) return SourcePick.Radio
    val playing = sessions.indexOfFirst { it.playing }
    if (playing >= 0) return SourcePick.Session(playing)
    if (radioAlive) return SourcePick.Radio
    return if (sessions.isNotEmpty()) SourcePick.Session(0) else SourcePick.None
}

/**
 * One station the XYAuto radio was heard on: how often ([heard]) and when last ([at], any monotonic millis), and
 * [pin], the order it was pinned in (0 = not pinned). The radio keeps its own presets private, so this is the list
 * Dashwheel learns for itself.
 */
internal data class FmEntry(val freq: Int, val name: String = "", val pin: Int = 0, val heard: Int = 1, val at: Long = 0L)

/**
 * The learned stations. Pinned ones first, in the order they were pinned; then the ones heard most often (the latest
 * first among equals). Those not pinned are kept to [LEARNED_CAP]: the least heard, longest ago, go first.
 * Immutable: every change gives a new list, which [serialize]s to one line per station for the preferences.
 */
internal class FmStations(val entries: List<FmEntry> = emptyList()) {

    /** The radio was heard on [freq] at [now]; [name] when something told it. */
    fun heard(freq: Int, now: Long, name: String = ""): FmStations {
        if (!isFm(freq)) return this
        val cleanName = clean(name)
        val old = entries.firstOrNull { it.freq == freq }
        val next = if (old == null) entries + FmEntry(freq, cleanName, 0, 1, now)
        else entries.map { if (it === old) it.copy(heard = it.heard + 1, at = now, name = cleanName.ifEmpty { it.name }) else it }
        return FmStations(trim(next, keep = freq))
    }

    /** Pins [freq] when it isn't (and there is room: [max] pins), else unpins it. */
    fun togglePin(freq: Int, max: Int = StockRadio.SHOWN): FmStations {
        val e = entries.firstOrNull { it.freq == freq } ?: return this
        val pin = when {
            e.pin > 0 -> 0
            entries.count { it.pin > 0 } >= max -> return this
            else -> (entries.maxOfOrNull { it.pin } ?: 0) + 1
        }
        return FmStations(entries.map { if (it === e) it.copy(pin = pin) else it })
    }

    fun remove(freq: Int): FmStations = FmStations(entries.filter { it.freq != freq })

    /** The stations in the order shown, at most [n]. */
    fun shown(n: Int = StockRadio.SHOWN): List<RadioStation> =
        entries.sortedWith(compareBy<FmEntry> { if (it.pin > 0) 0 else 1 }.thenBy { it.pin }.thenByDescending { it.heard }.thenByDescending { it.at })
            .take(n).map { RadioStation(it.freq, it.name, pinned = it.pin > 0) }

    fun nameOf(freq: Int): String = entries.firstOrNull { it.freq == freq }?.name.orEmpty()

    fun serialize(): String = entries.joinToString("\n") { "${it.freq}\t${it.name}\t${it.pin}\t${it.heard}\t${it.at}" }

    /** Drops the unpinned stations beyond the cap, never [keep] (the one just heard). */
    private fun trim(list: List<FmEntry>, keep: Int): List<FmEntry> {
        val loose = list.filter { it.pin == 0 && it.freq != keep }
        val keepsRoom = if (list.any { it.freq == keep && it.pin == 0 }) 1 else 0
        val spare = loose.size - (LEARNED_CAP - keepsRoom)
        if (spare <= 0) return list
        val drop = loose.sortedWith(compareBy<FmEntry> { it.heard }.thenBy { it.at }).take(spare).map { it.freq }.toSet()
        return list.filter { it.freq !in drop }
    }

    companion object {
        /** Stations kept that are not pinned. */
        const val LEARNED_CAP = 12

        private val BLANKS = Regex("[\\t\\r\\n]")

        private fun clean(name: String) = name.replace(BLANKS, " ").trim()

        /** The list [serialize]d, as far as it makes sense (a damaged line is dropped). */
        fun parse(text: String?): FmStations = FmStations(
            text.orEmpty().lineSequence().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size != 5) return@mapNotNull null
                val freq = p[0].toIntOrNull()?.takeIf(::isFm) ?: return@mapNotNull null
                FmEntry(freq, p[1], p[2].toIntOrNull()?.coerceAtLeast(0) ?: 0, p[3].toIntOrNull()?.coerceAtLeast(1) ?: 1, p[4].toLongOrNull() ?: 0L)
            }.distinctBy { it.freq }.toList()
        )
    }
}
