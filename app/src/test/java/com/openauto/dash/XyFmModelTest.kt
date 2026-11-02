package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XYAuto FM radio's pure parts: its broadcasts, the learned list, the media tile's labels and source choice. */
class XyFmModelTest {

    // --- freq_volue -------------------------------------------------------------

    @Test
    fun freqVolue_fmInTensOfKilohertz() {
        assertEquals(8820, parseFreqVolue(8820, "FM", "88.2 MHZ"))
        assertEquals(8820, parseFreqVolue(8820, "FM1", null))
        assertEquals(10_790, parseFreqVolue(10_790, null, null))
    }

    @Test
    fun freqVolue_amAndBadInputAreNothing() {
        assertNull(parseFreqVolue(1008, "AM", "1008 KHZ"))
        assertNull(parseFreqVolue(999, "am", null))
        assertNull(parseFreqVolue(1008, "FM", null)) // an AM frequency under an FM label
        assertNull(parseFreqVolue(0, "FM", null))
        assertNull(parseFreqVolue(-5, "FM", "garbage"))
        assertNull(parseFreqVolue(0, null, ""))
    }

    @Test
    fun freqVolue_textGivesTheFrequencyWhenTheNumberIsMissing() {
        assertEquals(8820, parseFreqVolue(0, "FM", "88.2 MHZ"))
        assertEquals(10_075, parseFreqVolue(0, "FM", "100.75 MHz"))
        assertNull(parseFreqVolue(0, "FM", "1008 KHZ"))
    }

    // --- the fm extra ------------------------------------------------------------

    @Test
    fun fmExtra_isDigitsInTenthsOfMegahertz() {
        assertEquals("882", fmExtra(8820))
        assertEquals("1080", fmExtra(10_800))
        assertEquals("875", fmExtra(8750))
        assertEquals("1004", fmExtra(10_040))
    }

    @Test
    fun fmExtra_outsideTheRadiosRangeIsNull() {
        assertNull(fmExtra(8740))
        assertNull(fmExtra(10_810))
        assertNull(fmExtra(6590))
        assertNull(fmExtra(1008))
        assertNull(fmExtra(0))
    }

    // --- the media tile ------------------------------------------------------------

    @Test
    fun mediaState_unnamedStationShowsTheFrequency() {
        val s = fmMediaState(RadioStation(8820, ""), playing = true)
        assertEquals("88.2 MHz", s.title)
        assertEquals("FM", s.artist)
        assertTrue(s.isPlaying)
        assertTrue(s.hasMedia)
    }

    @Test
    fun mediaState_namedStationShowsTheNameAndTheFrequencyUnderIt() {
        val s = fmMediaState(RadioStation(10_040, "Radio Swiss Pop"), playing = false)
        assertEquals("Radio Swiss Pop", s.title)
        assertEquals("100.4 MHz · FM", s.artist)
        assertFalse(s.isPlaying)
    }

    // --- playing / focus ------------------------------------------------------------

    @Test
    fun playing_needsAliveNotPausedAndFocusOrAFreshTune() {
        assertTrue(fmIsPlaying(alive = true, paused = false, focus = true, sinceFreqMs = 60_000))
        assertFalse(fmIsPlaying(alive = false, paused = false, focus = true, sinceFreqMs = 0))
        assertFalse(fmIsPlaying(alive = true, paused = true, focus = true, sinceFreqMs = 60_000))
        assertFalse(fmIsPlaying(alive = true, paused = false, focus = false, sinceFreqMs = 60_000))
        // Focus lags the tune by a moment.
        assertTrue(fmIsPlaying(alive = true, paused = false, focus = false, sinceFreqMs = 500))
        assertFalse(fmIsPlaying(alive = true, paused = false, focus = false, sinceFreqMs = -1))
    }

    @Test
    fun focus_fromTheVendorNameOrMusicActive() {
        assertTrue(fmHasFocus("com.android.fmradio", apiOk = true, musicActive = false))
        assertFalse(fmHasFocus("com.spotify.music", apiOk = true, musicActive = true))
        assertTrue(fmHasFocus("", apiOk = true, musicActive = true))
        assertFalse(fmHasFocus(null, apiOk = true, musicActive = false))
        // No such call on this firmware: music being active is what is left.
        assertTrue(fmHasFocus(null, apiOk = false, musicActive = true))
        assertFalse(fmHasFocus(null, apiOk = false, musicActive = false))
    }

    @Test
    fun currentSource_hasFocusOrIsUpWithNothingElsePlaying() {
        assertTrue(fmIsCurrentSource(alive = true, focus = false, otherSessionPlaying = false))
        assertFalse(fmIsCurrentSource(alive = true, focus = false, otherSessionPlaying = true))
        assertTrue(fmIsCurrentSource(alive = true, focus = true, otherSessionPlaying = true))
        assertFalse(fmIsCurrentSource(alive = false, focus = false, otherSessionPlaying = false))
    }

    // --- the source the media tile follows ------------------------------------------------

    private val emptyBluetooth = SessionView("com.android.bluetooth", playing = false)

    @Test
    fun source_theEmptyBluetoothSessionDoesNotBeatALiveRadio() {
        assertEquals(SourcePick.Radio, pickSource(listOf(emptyBluetooth), radioAlive = true, radioFocus = false))
    }

    @Test
    fun source_aPlayingSessionBeatsALiveRadio() {
        val sessions = listOf(emptyBluetooth, SessionView("com.spotify.music", playing = true))
        assertEquals(SourcePick.Session(1), pickSource(sessions, radioAlive = true, radioFocus = false))
    }

    @Test
    fun source_theRadioHoldingTheAudioBeatsAStalePlayingSession() {
        val sessions = listOf(SessionView("com.spotify.music", playing = true))
        assertEquals(SourcePick.Radio, pickSource(sessions, radioAlive = true, radioFocus = true))
    }

    @Test
    fun source_noRadioKeepsTheOldChoice() {
        assertEquals(SourcePick.Session(0), pickSource(listOf(emptyBluetooth, SessionView("a", false)), radioAlive = false, radioFocus = false))
        assertEquals(SourcePick.Session(1), pickSource(listOf(emptyBluetooth, SessionView("a", true)), radioAlive = false, radioFocus = false))
        assertEquals(SourcePick.None, pickSource(emptyList(), radioAlive = false, radioFocus = false))
        assertEquals(SourcePick.Radio, pickSource(emptyList(), radioAlive = true, radioFocus = false))
    }

    // --- the learned list ---------------------------------------------------------------------

    @Test
    fun learned_everyFrequencyHeardIsKept() {
        val l = FmStations().heard(8820, 1).heard(9650, 2).heard(8820, 3)
        assertEquals(listOf(8820, 9650), l.shown().map { it.freq }) // heard twice first
        assertEquals(2, l.entries.first { it.freq == 8820 }.heard)
    }

    @Test
    fun learned_ignoresWhatIsNotFm() {
        assertTrue(FmStations().heard(1008, 1).heard(0, 2).entries.isEmpty())
    }

    @Test
    fun learned_isCappedAndTheLeastHeardGoFirst() {
        var l = FmStations()
        // Station 8800 is heard again and again, so it must survive the flood of new ones.
        l = l.heard(8800, 0).heard(8800, 1).heard(8800, 2)
        for (i in 1 until 25) l = l.heard(8800 + i * 10, i.toLong() + 10)
        assertEquals(FmStations.LEARNED_CAP, l.entries.size)
        assertTrue(l.entries.any { it.freq == 8800 })
        // The one just heard is never the one dropped.
        assertTrue(l.entries.any { it.freq == 8800 + 24 * 10 })
    }

    @Test
    fun learned_pinnedComeFirstInPinOrder() {
        var l = FmStations().heard(8820, 1).heard(9650, 2).heard(10_040, 3).heard(10_040, 4)
        l = l.togglePin(9650).togglePin(8820)
        assertEquals(listOf(9650, 8820, 10_040), l.shown().map { it.freq })
        assertEquals(listOf(true, true, false), l.shown().map { it.pinned })
        // Unpinned, it goes back among the heard ones.
        l = l.togglePin(9650)
        assertEquals(listOf(8820, 10_040, 9650), l.shown().map { it.freq })
    }

    @Test
    fun learned_pinsSurviveTheCapAndTheCapDoesNotCountThem() {
        var l = FmStations().heard(8820, 0).togglePin(8820)
        for (i in 0 until 30) l = l.heard(9000 + i * 10, i.toLong() + 1)
        assertTrue(l.entries.any { it.freq == 8820 && it.pin > 0 })
        assertEquals(FmStations.LEARNED_CAP + 1, l.entries.size)
        assertEquals(8820, l.shown().first().freq)
    }

    @Test
    fun learned_pinsAreLimitedToWhatTheTileShows() {
        var l = FmStations()
        for (i in 0 until StockRadio.SHOWN + 1) l = l.heard(9000 + i * 10, i.toLong()).togglePin(9000 + i * 10)
        assertEquals(StockRadio.SHOWN, l.entries.count { it.pin > 0 })
    }

    @Test
    fun learned_removeForgetsItUntilHeardAgain() {
        var l = FmStations().heard(8820, 1).heard(9650, 2).togglePin(8820)
        l = l.remove(8820)
        assertEquals(listOf(9650), l.shown().map { it.freq })
        l = l.heard(8820, 3)
        assertEquals(0, l.entries.first { it.freq == 8820 }.pin)
    }

    @Test
    fun learned_nameIsKeptOnceGiven() {
        val l = FmStations().heard(8820, 1, "Europa Plus").heard(8820, 2)
        assertEquals("Europa Plus", l.nameOf(8820))
        assertEquals("Europa Plus", l.shown().single().name)
    }

    @Test
    fun learned_survivesTheRoundTripThroughThePreferences() {
        val l = FmStations().heard(8820, 1, "A\tB\nC").heard(9650, 2).heard(9650, 3).togglePin(8820)
        val back = FmStations.parse(l.serialize())
        assertEquals(l.entries, back.entries)
        assertEquals("A B C", back.nameOf(8820))
    }

    @Test
    fun learned_damagedPreferencesGiveWhatIsLeft() {
        val text = "8820\tX\t1\t2\t5\nnot a line\n99999\t\t0\t1\t0\n9650\t\tx\t0\t?"
        val l = FmStations.parse(text)
        assertEquals(listOf(8820, 9650), l.entries.map { it.freq })
        assertTrue(FmStations.parse(null).entries.isEmpty())
        assertTrue(FmStations.parse("").entries.isEmpty())
    }
}
