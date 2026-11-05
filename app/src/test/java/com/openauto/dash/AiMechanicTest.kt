package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The AI mechanic's pure parts: the question, reading the answer, and the live-reading rules. */
class AiMechanicTest {

    @Test
    fun promptNamesTheCarEngineCodesLanguageAndReadings() {
        val prompt = MechanicPrompt.build(
            listOf("P0128", "P0480"),
            CarProfile.PRESET.promptDescription(),
            AiLanguage.FRENCH,
            ObdData(rpm = 850, coolantTempC = 72, voltage = 14.2)
        )
        assertTrue(prompt.contains("C4 Picasso 1.6 HDi 110"))
        assertTrue(prompt.contains("BMP6"))
        assertTrue(prompt.contains("P0128, P0480"))
        assertTrue(prompt.contains("Answer in French"))
        assertTrue(prompt.contains("engine running at 850 rpm"))
        assertTrue(prompt.contains("coolant 72 °C"))
        assertTrue(prompt.contains("battery 14.2 V"))
    }

    @Test
    fun promptLeavesOutReadingsTheCarDidNotReport() {
        val prompt = MechanicPrompt.build(listOf("P0128"), CarProfile.PRESET.promptDescription(), AiLanguage.ENGLISH, ObdData())
        assertTrue(prompt.contains("engine not running"))
        assertFalse(prompt.contains("coolant"))
        assertFalse(prompt.contains("battery"))
    }

    @Test
    fun answerIsReadIncludingCodeFences() {
        val answer = """
            ```json
            {"severity":"soon","summary":"Le thermostat reste ouvert.",
             "codes":[{"code":"p0128","meaning":"Moteur trop froid","causes":["Thermostat bloqué ouvert"," Sonde de température "],"check_first":"Thermostat"}]}
            ```
        """.trimIndent()
        val d = MechanicPrompt.parse(answer)!!
        assertEquals(Severity.SOON, d.severity)
        assertEquals("Le thermostat reste ouvert.", d.summary)
        assertEquals(1, d.codes.size)
        assertEquals("P0128", d.codes[0].code)
        assertEquals(listOf("Thermostat bloqué ouvert", "Sonde de température"), d.codes[0].causes)
        assertEquals("Thermostat", d.codes[0].checkFirst)
    }

    @Test
    fun oddSeverityCountsAsSoonAndMissingFieldsAreTolerated() {
        val d = MechanicPrompt.parse("""{"severity":"maybe","summary":"Check it.","codes":[{"code":"P0101"},{}]}""")!!
        assertEquals(Severity.SOON, d.severity)
        assertEquals(listOf("P0101"), d.codes.map { it.code })
        assertTrue(d.codes[0].causes.isEmpty())
    }

    @Test
    fun unusableAnswersAreRejected() {
        assertNull(MechanicPrompt.parse("Sorry, I can't help."))
        assertNull(MechanicPrompt.parse("""{"severity":"ok","codes":[]}"""))
        assertNull(MechanicPrompt.parse("{not json"))
    }

    @Test
    fun geminiTextSkipsThoughtParts() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"{\"a\":"},{"text":"1}"}]}}]}"""
        assertEquals("{\"a\":1}", GeminiClient.answerText(body))
        assertNull(GeminiClient.answerText("""{"candidates":[]}"""))
        assertEquals(
            "API key not valid.",
            GeminiClient.errorMessage("""{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}""")
        )
    }

    @Test
    fun overheatingIsSaidOnceUntilTheEngineCoolsDown() {
        val w = LiveWatch()
        var t = 0L
        fun sample(c: Int) = w.check(ObdData(rpm = 900, coolantTempC = c, voltage = 14.0), t).also { t += 500 }
        assertNull(sample(95))
        assertEquals(LiveWatch.Alert.OVERHEAT, sample(111))
        assertNull(sample(112))
        assertNull(sample(104)) // still hot: stays quiet
        assertNull(sample(98))  // cooled down: re-armed
        assertEquals(LiveWatch.Alert.OVERHEAT, sample(110))
    }

    @Test
    fun overheatingFollowsTheEnginesOwnTemperature() {
        val w = LiveWatch()
        var t = 0L
        fun sample(c: Int) = w.check(ObdData(rpm = 900, coolantTempC = c, voltage = 14.0), t, hotC = 105).also { t += 500 }
        assertNull(sample(112)) // normal for an engine that runs at 105 °C
        assertEquals(LiveWatch.Alert.OVERHEAT, sample(125))
    }

    @Test
    fun notChargingNeedsTwoMinutesOfLowVoltageWhileRunning() {
        val w = LiveWatch()
        var t = 0L
        while (t < LiveWatch.NOT_CHARGING_MS) {
            assertNull(w.check(ObdData(rpm = 900, voltage = 12.2, voltageFromEcu = true), t))
            t += 500
        }
        assertEquals(LiveWatch.Alert.NOT_CHARGING, w.check(ObdData(rpm = 900, voltage = 12.2, voltageFromEcu = true), t))
        assertNull(w.check(ObdData(rpm = 900, voltage = 12.2, voltageFromEcu = true), t + 500))
    }

    @Test
    fun aGapInReadingsRestartsTheTimer() {
        val w = LiveWatch()
        assertNull(w.check(ObdData(rpm = 900, voltage = 12.2), 0))
        // Adapter dropped for a minute, then two more minutes minus a beat.
        var t = 60_000L
        while (t < 60_000L + LiveWatch.NOT_CHARGING_MS) {
            assertNull(w.check(ObdData(rpm = 900, voltage = 12.2), t))
            t += 500
        }
    }

    @Test
    fun crankingDipIsNotAWeakBattery() {
        val w = LiveWatch()
        assertNull(w.check(ObdData(rpm = 0, voltage = 12.5), 0))
        assertNull(w.check(ObdData(rpm = 0, voltage = 10.5), 500)) // cranking
        assertNull(w.check(ObdData(rpm = 800, voltage = 14.1), 1000))
    }

    @Test
    fun weakBatteryAtRestIsSaidAfterAMinute() {
        val w = LiveWatch()
        var t = 0L
        while (t < LiveWatch.WEAK_BATTERY_MS) {
            assertNull(w.check(ObdData(rpm = 0, voltage = 11.7, voltageFromEcu = true), t))
            t += 500
        }
        assertEquals(LiveWatch.Alert.WEAK_BATTERY, w.check(ObdData(rpm = 0, voltage = 11.7, voltageFromEcu = true), t))
    }

    @Test
    fun glowPlugsAtIgnitionOnAreNotAWeakBattery() {
        val w = LiveWatch()
        var t = 0L
        // Ten seconds of glow plugs and blower, then the engine starts.
        while (t < 10_000L) {
            assertNull(w.check(ObdData(rpm = 0, voltage = 11.6, voltageFromEcu = true), t))
            t += 500
        }
        assertNull(w.check(ObdData(rpm = 850, voltage = 14.2, voltageFromEcu = true), t))
    }

    @Test
    fun aWeakBatteryIsSaidOnceADayNotAtEveryStart() {
        val w = LiveWatch()
        fun restFor(from: Long): LiveWatch.Alert? {
            var t = from
            var said: LiveWatch.Alert? = null
            while (t <= from + LiveWatch.WEAK_BATTERY_MS) {
                said = w.check(ObdData(rpm = 0, voltage = 11.7, voltageFromEcu = true), t) ?: said
                t += 500
            }
            // The engine runs and the alternator recharges: armed again.
            w.check(ObdData(rpm = 850, voltage = 14.2, voltageFromEcu = true), t + 500)
            return said
        }
        assertEquals(LiveWatch.Alert.WEAK_BATTERY, restFor(0))
        // Back from the shop an hour later: the same weak battery, not said again.
        assertNull(restFor(3_600_000L))
        // The next morning: said again.
        assertEquals(LiveWatch.Alert.WEAK_BATTERY, restFor(LiveWatch.WEAK_REPEAT_MS + 3_600_000L))
    }

    @Test
    fun theAdaptersOwnVoltageIsGivenItsDiodesDrop() {
        val w = LiveWatch()
        var t = 0L
        // 11.8 V at the adapter is about 12.2 V at the battery: fine.
        while (t <= LiveWatch.WEAK_BATTERY_MS + 1_000) {
            assertNull(w.check(ObdData(rpm = 0, voltage = 11.8), t))
            t += 500
        }
        // Well under, it is weak whichever way it was read.
        val start = t
        var said: LiveWatch.Alert? = null
        while (t <= start + LiveWatch.WEAK_BATTERY_MS) {
            said = w.check(ObdData(rpm = 0, voltage = 11.4), t) ?: said
            t += 500
        }
        assertEquals(LiveWatch.Alert.WEAK_BATTERY, said)
    }

    @Test
    fun implausibleVoltsAreIgnored() {
        val w = LiveWatch()
        var t = 0L
        while (t <= LiveWatch.NOT_CHARGING_MS + 1000) {
            assertNull(w.check(ObdData(rpm = 900, voltage = 16.9), t))
            assertNull(w.check(ObdData(rpm = 0, voltage = 0.0), t + 1))
            t += 500
        }
    }

    @Test
    fun offlineLinesNameTheFaultsAndCountThemNeverSpellingCodes() {
        assertEquals(
            SpokenLine(R.plurals.ai_say_new_codes, listOf(1, "coolant below thermostat regulating temp"), quantity = 1),
            MechanicLines.newCodes(listOf("P0128"), listOf("coolant below thermostat regulating temp"))
        )
        assertEquals(
            SpokenLine(R.plurals.ai_say_new_codes, listOf(2, "catalyst efficiency below threshold, turbocharger underboost"), quantity = 2),
            MechanicLines.newCodes(listOf("P0420", "P0299"), listOf("catalyst efficiency below threshold", "turbocharger underboost"))
        )
        // No name for any of them: the count alone, and still no letters.
        assertEquals(
            SpokenLine(R.plurals.ai_say_new_codes_untitled, listOf(2), quantity = 2),
            MechanicLines.newCodes(listOf("P1F00", "U0100"), emptyList())
        )
    }

    @Test
    fun faultTitlesAreTrimmedForTheVoice() {
        assertEquals("catalyst efficiency below threshold", spokenFaultTitle("Catalyst efficiency below threshold (bank1)"))
        assertEquals("crankshaft or camshaft correlation", spokenFaultTitle("Crankshaft/camshaft correlation (bank1 sensor A)"))
        assertEquals("turbocharger underboost", spokenFaultTitle("Turbocharger underboost"))
    }

    @Test
    fun alertsPickTheirSentenceAndWriteVoltsTheVoiceLanguagesWay() {
        assertEquals(
            SpokenLine(R.string.ai_say_not_charging, listOf("12,1")),
            MechanicLines.alert(LiveWatch.Alert.NOT_CHARGING, ObdData(voltage = 12.1), AiLanguage.FRENCH)
        )
        assertEquals(
            SpokenLine(R.string.ai_say_weak_battery, listOf("11.7")),
            MechanicLines.alert(LiveWatch.Alert.WEAK_BATTERY, ObdData(voltage = 11.7), AiLanguage.ENGLISH)
        )
        assertEquals(
            SpokenLine(R.string.ai_say_weak_battery, listOf("11,7")),
            MechanicLines.alert(LiveWatch.Alert.WEAK_BATTERY, ObdData(voltage = 11.7), AiLanguage.GERMAN)
        )
        assertEquals(
            SpokenLine(R.string.ai_say_overheat, listOf(112)),
            MechanicLines.alert(LiveWatch.Alert.OVERHEAT, ObdData(coolantTempC = 112), AiLanguage.POLISH)
        )
    }

    @Test
    fun theMechanicFollowsTheLauncherUnlessOneWasPicked() {
        assertEquals(AiLanguage.GERMAN, AiLanguage.of(Locale.GERMANY))
        assertEquals(AiLanguage.PORTUGUESE, AiLanguage.of(Locale("pt", "BR")))
        assertEquals(AiLanguage.ENGLISH, AiLanguage.of(Locale.JAPAN))
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("nl", "BE"))
            assertEquals(AiLanguage.DUTCH, AiConfig().language)
            assertEquals(AiLanguage.FRENCH, AiConfig(languageChoice = AiLanguage.FRENCH).language)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun promptFollowsTheDriversCarAndCurrencyAndChecksAgainstTheTable() {
        val prompt = MechanicPrompt.build(
            listOf("P0300", "P1352"), "Renault Clio IV 1.5 dCi 90 (diesel, 90 hp)", AiLanguage.ENGLISH, null,
            references = mapOf("P0300" to "Random/multiple cylinder misfire"), currency = "CHF"
        )
        assertTrue(prompt.contains("Renault Clio IV 1.5 dCi 90"))
        assertFalse(prompt.contains("Citroën"))
        assertTrue(prompt.contains("P0300 = Random/multiple cylinder misfire"))
        assertFalse(prompt.contains("P1352 ="))
        assertTrue(prompt.contains("currency (CHF)"))
        assertTrue(prompt.contains("say so plainly"))
    }

    @Test
    fun promptAsksForTheChosenLanguageByItsEnglishName() {
        val prompt = MechanicPrompt.build(listOf("P0128"), CarProfile.PRESET.promptDescription(), AiLanguage.POLISH, null)
        assertTrue(prompt.contains("Answer in Polish, with correct spelling and all accents."))
    }

    @Test
    fun promptAsksForTheDetailSheet() {
        val prompt = MechanicPrompt.build(listOf("P1352"), CarProfile.PRESET.promptDescription(), AiLanguage.FRENCH, null)
        listOf("overview:", "explanation:", "symptoms:", "checks:", "repair:", "cost:", "diy:", "driving:").forEach {
            assertTrue(it, prompt.contains(it))
        }
        val required = MechanicPrompt.schema(listOf("P1352")).getJSONObject("properties").getJSONObject("codes")
            .getJSONObject("items").getJSONArray("required")
        assertEquals(11, required.length())
    }

    @Test
    fun theAnswerMayOnlyCoverTheCodesAsked() {
        val codes = MechanicPrompt.schema(listOf("P1352", "P0480")).getJSONObject("properties").getJSONObject("codes")
        assertEquals(2, codes.getInt("minItems"))
        assertEquals(2, codes.getInt("maxItems"))
        val allowed = codes.getJSONObject("items").getJSONObject("properties").getJSONObject("code").getJSONArray("enum")
        assertEquals(listOf("P1352", "P0480"), (0 until allowed.length()).map { allowed.getString(it) })
        assertTrue(MechanicPrompt.build(listOf("P1352"), CarProfile.PRESET.promptDescription(), AiLanguage.FRENCH, null).contains("do not assume or add any other fault"))
    }

    @Test
    fun theDetailSheetIsReadBack() {
        val d = MechanicPrompt.parse(
            """{"severity":"soon","summary":"Préchauffage à vérifier.","overview":"Le circuit de préchauffage est en défaut.",
               "codes":[{"code":"P1352","meaning":"Circuit de préchauffage","explanation":"Les bougies chauffent la chambre.",
               "symptoms":["Démarrage difficile à froid"],"causes":["Bougie grillée","Boîtier défectueux"],
               "check_first":"Le fusible","checks":["Fusible","Résistance des bougies"],"repair":"Remplacer les bougies",
               "cost":"80 à 250 €","diy":"Moyen, clé longue","driving":"On peut rouler"}]}"""
        )!!
        assertEquals("Le circuit de préchauffage est en défaut.", d.overview)
        val c = d.codes.single()
        assertEquals("Les bougies chauffent la chambre.", c.explanation)
        assertEquals(listOf("Démarrage difficile à froid"), c.symptoms)
        assertEquals(listOf("Fusible", "Résistance des bougies"), c.checks)
        assertEquals("80 à 250 €", c.cost)
        assertEquals("Moyen, clé longue", c.diy)
        assertEquals("On peut rouler", c.driving)
    }

    @Test
    fun anOlderShortAnswerStillReads() {
        val d = MechanicPrompt.parse("""{"severity":"ok","summary":"Rien de grave.","codes":[{"code":"P0128","meaning":"Moteur froid","causes":[],"check_first":""}]}""")!!
        assertEquals("", d.overview)
        assertTrue(d.codes.single().checks.isEmpty())
    }

    @Test
    fun adviceFollowsTheCarsCodeEvenWhenTheAiMistypesIt() {
        val advice = CodeAdvice("P1351", "Préchauffage", emptyList(), "")
        val d = Diagnosis(Severity.SOON, "…", listOf(advice))
        // Asked about P1352, answered as P1351: same place, so it's that code's advice.
        assertEquals(advice, adviceFor(d, listOf("P1352"), "P1352", 0))
        // With a different number of answers, position can't be trusted.
        assertNull(adviceFor(d, listOf("P1352", "P0480"), "P0480", 1))
        assertNull(adviceFor(null, listOf("P1352"), "P1352", 0))
    }
}
