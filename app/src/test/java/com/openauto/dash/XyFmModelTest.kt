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

    @Test
    fun rename_setsAndClearsTheName() {
        val l = FmStations().add(8820).add(9650, "old")
        assertEquals("Radio Budva", l.rename(8820, "  Radio Budva ").nameOf(8820))
        assertEquals("", l.rename(9650, "").nameOf(9650))
        assertEquals(listOf(8820, 9650), l.rename(9650, "x").entries.map { it.freq })
    }

    @Test
    fun rename_roundTripKeepsSpacesAndCyrillic() {
        val l = FmStations().add(8820).rename(8820, "Авто радио 2")
        val back = FmStations.parse(l.serialize())
        assertEquals("Авто радио 2", back.nameOf(8820))
    }

    @Test
    fun rename_stripsTabsAndNewlinesAndCapsTheLength() {
        val l = FmStations().add(8820).rename(8820, "a" + Char(9) + "b" + Char(10) + "c")
        assertEquals("a b c", l.nameOf(8820))
        assertEquals(1, FmStations.parse(l.serialize()).entries.size)
        assertEquals(FmStations.MAX_NAME, FmStations().add(8820).rename(8820, "x".repeat(60)).nameOf(8820).length)
    }

    @Test
    fun rename_unknownFrequencyIsNothing() {
        val l = FmStations().add(8820, "A")
        assertEquals(l.serialize(), l.rename(9650, "B").serialize())
    }

    @Test
    fun mediaId_parsesFmFrequency() {
        assertEquals(8820, parseFmMediaId("fm:8820"))
        assertEquals(10_460, parseFmMediaId(" FM:10460 "))
        assertNull(parseFmMediaId("fm:abc"))
        assertNull(parseFmMediaId("fm:99999"))
        assertNull(parseFmMediaId("am:1000"))
        assertNull(parseFmMediaId(null))
    }

    @Test
    fun broadcastName_ignoresBlankAndFrequencyTitles() {
        assertNull(fmBroadcastName(null))
        assertNull(fmBroadcastName("  "))
        assertNull(fmBroadcastName("88.2 MHz"))
        assertNull(fmBroadcastName("88.20"))
        assertEquals("PLAY", fmBroadcastName(" PLAY "))
        assertEquals("106", fmBroadcastName("106"))
    }

    @Test
    fun learned_keepsRealNamesPerFrequency() {
        val l = FmLearned().learn(8820, "88.2 MHz").learn(8820, "PLAY").learn(9870, "RADIO BUDVA")
        assertEquals("PLAY", l.nameOf(8820))
        assertEquals("", l.nameOf(10_000))
        assertEquals("PLAY", l.learn(8820, "").nameOf(8820))
        assertEquals("Авто", FmLearned.parse(FmLearned().learn(8820, "Авто").serialize()).nameOf(8820))
        assertEquals("", FmLearned().learn(1008, "X").nameOf(1008))
    }

    @Test
    fun displayName_ownBeatsBroadcastBeatsFrequency() {
        assertEquals("Mine", fmDisplayName("Mine", "PLAY"))
        assertEquals("PLAY", fmDisplayName("", "PLAY"))
        assertEquals("", fmDisplayName("", ""))
        val l = FmStations().add(8820).rename(8820, "Mine")
        assertEquals("PLAY", fmDisplayName(l.rename(8820, "").nameOf(8820), "PLAY"))
    }

    @Test
    fun sessionState_nameWithTextOrFrequency() {
        val a = fmSessionState("PLAY", "www.playradio.me", 8820, true)
        assertEquals("PLAY", a.title)
        assertEquals("www.playradio.me", a.artist)
        val b = fmSessionState("PLAY", null, 8820, true)
        assertEquals("88.2 MHz · FM", b.artist)
        val c = fmSessionState("88.2 MHz", "", 8820, false)
        assertEquals("88.2 MHz", c.title)
        assertEquals("88.2 MHz · FM", c.artist)
        assertFalse(c.isPlaying)
    }

    @Test
    fun pick_radioSessionStandsInForTheRadio() {
        val fm = SessionView("com.android.fmradio", true)
        val bt = SessionView("com.android.bluetooth", false)
        assertEquals(SourcePick.Session(1), pickSource(listOf(bt, fm), radioAlive = true, radioFocus = true, radioPackage = "com.android.fmradio"))
        assertEquals(SourcePick.Session(1), pickSource(listOf(bt, SessionView("com.android.fmradio", false)), radioAlive = true, radioFocus = false, radioPackage = "com.android.fmradio"))
    }

    @Test
    fun pick_noRadioSessionFallsBackToTheBroadcastPath() {
        val bt = SessionView("com.android.bluetooth", false)
        assertEquals(SourcePick.Radio, pickSource(listOf(bt), radioAlive = true, radioFocus = true, radioPackage = "com.android.fmradio"))
        assertEquals(SourcePick.Radio, pickSource(listOf(bt), radioAlive = true, radioFocus = false, radioPackage = "com.android.fmradio"))
    }

    @Test
    fun pi_parsesHexAbsentAndGarbage() {
        assertEquals("3033", parseFmPi("3033"))
        assertEquals("C1A0", parseFmPi(" c1a0 "))
        assertNull(parseFmPi(null))
        assertNull(parseFmPi(""))
        assertNull(parseFmPi("303"))
        assertNull(parseFmPi("30333"))
        assertNull(parseFmPi("zz33"))
        assertNull(parseFmPi("0000"))
    }

    @Test
    fun pi_savedPresetKeepsItAndOldLinesStillParse() {
        val l = FmStations().add(9940, "PLAY", pi = "3033").add(8820, "x")
        assertEquals("3033", l.piOf(9940))
        // The stations text stays the old 2-field one; the PIs have their own.
        assertEquals("9940\tPLAY\n8820\tx", l.serialize())
        val back = FmStations.parse(l.serialize(), pis = l.serializePis())
        assertEquals(l.entries, back.entries)
        // Old text without any PI text.
        val old = FmStations.parse("9940\tPLAY\n8820\tx")
        assertEquals("", old.piOf(9940))
        assertEquals("PLAY", old.nameOf(9940))
        // A 3-field line is read too.
        assertEquals("3033", FmStations.parse("9940\tPLAY\t3033").piOf(9940))
    }

    @Test
    fun pi_fillInIsOnlyForTheMatchingFrequencyAndOnlyOnce() {
        val l = FmStations().add(9940, "A").add(8820, "B")
        val f = l.fillPi(9940, "3033")
        assertEquals("3033", f.piOf(9940))
        assertEquals("", f.piOf(8820))
        assertEquals("3033", f.fillPi(9940, "AAAA").piOf(9940))
        assertEquals(l.serializePis(), l.fillPi(10_000, "3033").serializePis())
        assertEquals("", l.fillPi(9940, "garbage").piOf(9940))
    }

    @Test
    fun pi_heardIsKeptPerFrequency() {
        val h = FmPis().learn(9940, "3033").learn(8820, "zz").learn(1008, "3033")
        assertEquals("3033", h.piOf(9940))
        assertEquals("", h.piOf(8820))
        assertEquals("3033", FmPis.parse(h.serialize()).piOf(9940))
    }

    @Test
    fun pi_renameKeepsThePi() {
        assertEquals("3033", FmStations().add(9940, "A", pi = "3033").rename(9940, "B").piOf(9940))
    }

    // --- Stations of several frequencies, joined by PI ---------------------------------------------------

    private fun freqsOf(l: FmStations) = l.stations.map { it.freqs }

    @Test
    fun group_plusJoinsTheStationWithTheSamePi() {
        val l = FmStations().add(9940, "PLAY", pi = "3033").add(10_460, "PLAY", pi = "3033")
        assertEquals(listOf(listOf(9940, 10_460)), freqsOf(l))
        assertEquals(10_460, l.stations[0].last)
        assertEquals("PLAY", l.nameOf(10_460))
        assertEquals(10_460, l.shown()[0].freq)
        assertEquals(listOf(9940, 10_460), l.shown()[0].freqs)
    }

    @Test
    fun group_noPiOrADifferentPiMakesANewStation() {
        val l = FmStations().add(9940, "A", pi = "3033").add(10_460, "A").add(10_700, "A", pi = "3034")
        assertEquals(listOf(listOf(9940), listOf(10_460), listOf(10_700)), freqsOf(l))
        assertEquals(listOf(listOf(9940)), freqsOf(FmStations().add(9940, "A", pi = "3033").add(9940, "B", pi = "3033")))
    }

    @Test
    fun group_aPiThatFillsInLaterMakesAJoinTargetButNeverMergesByItself() {
        val l = FmStations().add(9940, "A").add(10_460, "A", pi = "3033")
        val f = l.fillPi(9940, "3033")
        assertEquals(listOf(listOf(9940), listOf(10_460)), freqsOf(f))
        val j = f.add(10_700, "A", pi = "3033")
        assertEquals(listOf(listOf(9940, 10_700), listOf(10_460)), freqsOf(j))
    }

    @Test
    fun group_canAddJoinsEvenWhenTheTileIsFull() {
        val full = (0 until StockRadio.SHOWN).fold(FmStations()) { l, i -> l.add(9000 + i * 10, "", pi = "30%02d".format(i)) }
        assertFalse(full.canAdd(10_700, pi = "9999"))
        assertTrue(full.canAdd(10_700, pi = "3003"))
        assertTrue(canSaveStation(full.shown(), RadioStation(10_700, "", "3003")))
        assertFalse(canSaveStation(full.shown(), RadioStation(10_700, "", "9999")))
        assertFalse(canSaveStation(full.shown(), RadioStation(9000, "", "3000")))
    }

    @Test
    fun tap_firstTunesTheLastUsedThenRotatesWithWrapAround() {
        val l = FmStations().add(9940, "A", pi = "3033").add(10_460, "A", pi = "3033").add(10_700, "A", pi = "3033").played(10_460)
        assertEquals(10_460, l.tapTarget(9940, null))
        assertEquals(10_460, l.tapTarget(10_700, 8820))
        assertEquals(10_700, l.tapTarget(10_460, 10_460))
        assertEquals(9940, l.tapTarget(10_460, 10_700))
        assertEquals(10_460, l.tapTarget(10_460, 9940))
        assertEquals(8820, l.tapTarget(8820, null))
        assertEquals(9940, FmStations().add(9940, "A").tapTarget(9940, 9940))
    }

    @Test
    fun tap_lastUsedFollowsWhatPlaysAndOnlyInItsStation() {
        val l = FmStations().add(9940, "A", pi = "3033").add(10_460, "A", pi = "3033").add(8820, "B")
        assertEquals(10_460, l.stations[0].last)
        val p = l.played(9940)
        assertEquals(9940, p.stations[0].last)
        assertEquals(8820, p.stations[1].last)
        assertTrue(p.played(9940) === p)
        assertTrue(p.played(10_000) === p)
    }

    @Test
    fun group_frequenciesDialogRemovesOneThenTheStationWithTheLast() {
        val l = FmStations().add(9940, "A", pi = "3033").add(10_460, "A", pi = "3033").add(8820, "B")
        val one = l.removeFreq(10_460)
        assertEquals(listOf(listOf(9940), listOf(8820)), freqsOf(one))
        assertEquals(9940, one.stations[0].last)
        val none = one.removeFreq(9940)
        assertEquals(listOf(listOf(8820)), freqsOf(none))
        assertTrue(one.removeFreq(1234) === one)
        assertEquals(9940, l.played(9940).removeFreq(10_460).stations[0].last)
    }

    @Test
    fun group_renameAndRemoveActOnTheWholeStation() {
        val l = FmStations().add(9940, "A", pi = "3033").add(10_460, "A", pi = "3033").add(8820, "B")
        val r = l.rename(10_460, "Radio")
        assertEquals("Radio", r.nameOf(9940))
        assertEquals("B", r.nameOf(8820))
        assertEquals(listOf(listOf(8820)), freqsOf(r.remove(9940)))
    }

    @Test
    fun group_storageRoundTripIsLossless() {
        val l = FmStations().add(9940, "PLAY", pi = "3033").add(8820, "Old").add(10_460, "PLAY", pi = "3033").add(10_700, "").played(9940)
        val back = FmStations.parse(l.serialize(), pis = l.serializePis(), groups = l.serializeGroups())
        assertEquals(l.stations, back.stations)
        assertEquals("9940\t10460\t9940", l.serializeGroups().replace(",", "\t"))
    }

    @Test
    fun group_anOldBuildSeesEveryFrequencyAsAPresetAndLosesNothing() {
        val l = FmStations().add(9940, "PLAY", pi = "3033").add(8820, "Old").add(10_460, "PLAY", pi = "3033")
        // What the stations text holds is what an old build reads: 2-field lines, one per frequency.
        assertEquals("9940\tPLAY\n10460\tPLAY\n8820\tOld", l.serialize())
        val old = FmStations.parse(l.serialize())
        assertEquals(listOf(9940, 10_460, 8820), old.entries.map { it.freq })
        assertEquals(3, old.stations.size)
        // And without the groups text nothing merges, even when the PIs are the same.
        assertEquals(3, FmStations.parse(l.serialize(), pis = l.serializePis()).stations.size)
    }

    @Test
    fun group_damagedGroupLinesAreIgnored() {
        val text = "9940\tA\n10460\tA\n8820\tB"
        assertEquals(3, FmStations.parse(text, groups = "9940,7777\t9940\nzz\t1\n8820,7777\t8820").stations.size)
        val g = FmStations.parse(text, groups = "9940,10460\t5555")
        assertEquals(listOf(listOf(9940, 10_460), listOf(8820)), freqsOf(g))
        assertEquals(9940, g.stations[0].last)
    }
}
