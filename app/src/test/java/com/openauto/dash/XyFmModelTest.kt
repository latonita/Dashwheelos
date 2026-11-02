package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XYAuto FM radio's pure parts: its broadcasts, the saved list, the media tile's labels and source choice. */
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

    // --- the driver's list ---------------------------------------------------------------------

    @Test
    fun saved_addKeepsTheOrderTheyWereAddedIn() {
        val l = FmStations().add(10_040, "C").add(8820).add(9650)
        assertEquals(listOf(10_040, 8820, 9650), l.shown().map { it.freq })
        assertEquals("C", l.nameOf(10_040))
    }

    @Test
    fun saved_noDuplicatesAndOnlyFm() {
        val l = FmStations().add(8820).add(8820, "again").add(1008).add(0)
        assertEquals(listOf(8820), l.entries.map { it.freq })
        assertEquals("", l.nameOf(8820))
    }

    @Test
    fun saved_isCappedAtWhatTheTileShows() {
        var l = FmStations()
        for (i in 0 until StockRadio.SHOWN + 3) l = l.add(9000 + i * 10)
        assertEquals(StockRadio.SHOWN, l.entries.size)
        assertEquals(9000, l.entries.first().freq)
        assertFalse(l.canAdd(10_000))
    }

    @Test
    fun saved_removeLeavesTheOthersInOrderAndAllowsAddingAgain() {
        var l = FmStations().add(8820).add(9650).add(10_040)
        l = l.remove(9650)
        assertEquals(listOf(8820, 10_040), l.shown().map { it.freq })
        l = l.add(9650)
        assertEquals(listOf(8820, 10_040, 9650), l.shown().map { it.freq })
        assertEquals(l.entries, l.remove(1234).entries)
    }

    @Test
    fun saved_nothingIsLearnedFromWhatTheRadioTunes() {
        // The radio's broadcast only names what plays; the list has no way to take it in but add().
        assertEquals(8820, parseFreqVolue(8820, "FM", "88.2 MHZ"))
        val l = FmStations().add(9650)
        assertEquals(listOf(9650), l.shown().map { it.freq })
        assertFalse(l.contains(8820))
        assertTrue(l.canAdd(8820))
    }

    @Test
    fun saved_survivesTheRoundTripThroughThePreferences() {
        val l = FmStations().add(8820, "A\tB\nC").add(9650)
        val back = FmStations.parse(l.serialize())
        assertEquals(l.entries, back.entries)
        assertEquals("A B C", back.nameOf(8820))
    }

    @Test
    fun saved_oldFormatKeepsOnlyThePinnedOnesInPinOrder() {
        // "freq, name, pin, heard, at": 9650 pinned 1st, 8820 pinned 2nd, the rest were learned.
        val old = listOf(
            "8820\tX\t2\t5\t7", "10040\t\t0\t9\t9", "9650\tY\t1\t1\t1", "9000\t\t0\t3\t3", "9650\tdup\t4\t1\t1"
        ).joinToString("\n")
        val l = FmStations.parse(old)
        assertEquals(listOf(9650, 8820), l.entries.map { it.freq })
        assertEquals("Y", l.nameOf(9650))
    }

    @Test
    fun saved_oldFormatWithNothingPinnedIsEmpty() {
        assertTrue(FmStations.parse("8820\t\t0\t4\t1\n9650\t\t0\t1\t2").entries.isEmpty())
    }

    @Test
    fun saved_garbledPreferencesGiveWhatIsLeft() {
        val text = "8820\tX\nnot a line\n99999\tZ\n9650\n\t\t\t\t\n10040\tW\t\t\n9650\tK"
        val l = FmStations.parse(text)
        assertEquals(listOf(8820, 9650), l.entries.map { it.freq })
        assertTrue(FmStations.parse(null).entries.isEmpty())
        assertTrue(FmStations.parse("").entries.isEmpty())
        assertTrue(FmStations.parse("\u0000￿\t\t\t\t\t\t").entries.isEmpty())
    }

    @Test
    fun saved_aLongStoredListIsCut() {
        val text = (0 until 10).joinToString("\n") { "${9000 + it * 10}\t" }
        assertEquals(StockRadio.SHOWN, FmStations.parse(text).entries.size)
    }

    @Test
    fun plusCell_needsAPlayingStationNotSavedAndRoom() {
        val a = RadioStation(8820, "")
        assertFalse(canSaveStation(emptyList(), null))
        assertTrue(canSaveStation(emptyList(), a))
        assertFalse(canSaveStation(listOf(RadioStation(8820, "x")), a))
        assertTrue(canSaveStation(List(StockRadio.SHOWN - 1) { RadioStation(9000 + it * 10, "") }, a))
        assertFalse(canSaveStation(List(StockRadio.SHOWN) { RadioStation(9000 + it * 10, "") }, a))
    }
}
