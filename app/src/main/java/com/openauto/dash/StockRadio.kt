package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * A station: [freq] in the radio's 10 kHz units (9850 = 98.5 MHz); [name] empty when none is known.
 */
data class RadioStation(val freq: Int, val name: String, val pi: String = "") {
    val mhz: String get() = String.format(Locale.ROOT, "%.1f", freq / 100.0)
}

/**
 * The head unit's own FM radio app, as the media tile and the radio presets tile see it. One per firmware:
 * [QfRadio] (the K706's com.android.fmradio.ext) and [XyFmRadio] (XYAuto's com.android.fmradio); the unit's adapter
 * names which one the unit has ([UnitAdapter.radio]). Both tiles talk to this and nothing else.
 */
interface StockRadio {
    /** The radio app's package, which is also the media tile's name for it as a source. */
    val packageName: String

    fun installed(context: Context): Boolean

    /** The stations the presets tile shows, at most [SHOWN]. */
    val stations: StateFlow<List<RadioStation>>

    /** The station the radio is on, as far as it has said. */
    val playing: StateFlow<RadioStation?>

    /** The radio app is up (its screen exists), playing or not. */
    val running: Boolean

    /** The radio is sounding now. */
    fun isPlaying(): Boolean

    /** The radio holds the unit's audio now, as far as can be told. */
    fun hasAudioFocus(): Boolean = false

    /**
     * Whether the radio is what the unit plays: [otherSessionPlaying] is true while a media session is playing.
     * Only meaningful where [judgesSource] is true.
     */
    fun isCurrentSource(otherSessionPlaying: Boolean = false): Boolean

    /**
     * True when the radio itself tells whether it is the source ([isCurrentSource]), and the media tile chooses between
     * it and the media sessions with that. False where the unit's firmware records its audio source in a property
     * (QF): the tile reads that, as it always did.
     */
    val judgesSource: Boolean

    /** What the media tile shows while the radio is the source; null while it has said nothing. */
    fun mediaState(): MediaState?

    /** Told (main thread) whenever the station, or the radio's being up, changes. */
    fun addListener(listener: () -> Unit)
    fun removeListener(listener: () -> Unit)

    /** One more user of the radio's state; it is followed while any is there. */
    fun start(context: Context)
    fun stop(context: Context)

    fun tune(context: Context, station: RadioStation)
    fun next(context: Context)
    fun previous(context: Context)
    fun playPause(context: Context)

    /** Whether the presets are a list the driver keeps in Dashwheel ([add], [remove]). */
    val editable: Boolean get() = false
    fun add(context: Context, station: RadioStation) = Unit
    fun remove(context: Context, station: RadioStation) = Unit
    /** Gives [station] the driver's own [name] (blank clears it). */
    fun rename(context: Context, station: RadioStation, name: String) = Unit

    /** The name the driver gave [station] (not the one it broadcasts); what a rename starts from. */
    fun ownName(station: RadioStation): String = station.name

    /** The radio's own media session says it plays [mediaId] ("fm:8820") under [title] and RDS PI [pi]: what is heard is kept. */
    fun onSession(mediaId: String?, title: String?, pi: String? = null) = Unit

    /** The presets tile's text while there is nothing to list. */
    @get:StringRes val emptyTextRes: Int

    companion object {
        /** Presets shown in the tile (two rows of three). */
        const val SHOWN = 6
    }
}
