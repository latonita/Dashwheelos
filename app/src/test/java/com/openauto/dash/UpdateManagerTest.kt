package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release tag -> build number, and which release asset is the launcher's own APK. */
class UpdateManagerTest {

    @Test
    fun theLauncherApkIsTheOneThatIsNeitherCompanionNorPlay() {
        val assets = listOf(
            "dashwheel-companion.apk" to "https://github.com/deviloufr-ai/Dashwheel/releases/download/v1.0.42/dashwheel-companion.apk",
            "Dashwheel-1.0.42-play.aab" to "https://github.com/deviloufr-ai/Dashwheel/releases/download/v1.0.42/Dashwheel-1.0.42-play.aab",
            "Dashwheel-1.0.42-PLAY.apk" to "https://github.com/deviloufr-ai/Dashwheel/releases/download/v1.0.42/Dashwheel-1.0.42-PLAY.apk",
            "Dashwheel-1.0.42.apk" to "https://github.com/deviloufr-ai/Dashwheel/releases/download/v1.0.42/Dashwheel-1.0.42.apk"
        )
        assertEquals(assets.last().second, UpdateManager.pickLauncherApk(assets))
    }

    @Test
    fun noLauncherApkAmongPlayAndCompanionAssets() {
        val assets = listOf(
            "dashwheel-companion.apk" to "https://github.com/x/y/releases/download/v1/dashwheel-companion.apk",
            "Dashwheel-1.0.42-play.apk" to "https://github.com/x/y/releases/download/v1/Dashwheel-1.0.42-play.apk",
            "Dashwheel-1.0.42.aab" to "https://github.com/x/y/releases/download/v1/Dashwheel-1.0.42.aab"
        )
        assertNull(UpdateManager.pickLauncherApk(assets))
    }

    @Test
    fun onlyGitHubHostsOverHttpsAreTrusted() {
        assertNull(UpdateManager.pickLauncherApk(listOf("Dashwheel.apk" to "http://github.com/a/b.apk")))
        assertNull(UpdateManager.pickLauncherApk(listOf("Dashwheel.apk" to "https://evil.example.com/Dashwheel.apk")))
        assertNull(UpdateManager.pickLauncherApk(listOf("Dashwheel.apk" to "")))
        val ok = "https://objects.githubusercontent.com/some/path/Dashwheel.apk"
        assertEquals(ok, UpdateManager.pickLauncherApk(listOf("Dashwheel.apk" to ok)))
    }

    @Test
    fun assetNamesTellTheEditionsApart() {
        assertTrue(UpdateManager.isLauncherApk("Dashwheel-1.0.42.apk"))
        assertTrue(UpdateManager.isLauncherApk("DASHWHEEL.APK"))
        assertFalse(UpdateManager.isLauncherApk("Dashwheel-1.0.42-play.apk"))
        assertFalse(UpdateManager.isLauncherApk("Dashwheel-1.0.42-Play.apk"))
        assertFalse(UpdateManager.isLauncherApk("Dashwheel-1.0.42.aab"))
        assertFalse(UpdateManager.isLauncherApk("dashwheel-companion.apk"))
        assertFalse(UpdateManager.isLauncherApk("Dashwheel-1.0.42.apk.sha256"))
    }

    @Test
    fun lastNumberInTheTagIsTheBuild() {
        assertEquals(42L, UpdateManager.parseBuildNumber("v1.0.42"))
        assertEquals(119L, UpdateManager.parseBuildNumber("Dashwheel v1.0.119"))
        assertEquals(7L, UpdateManager.parseBuildNumber("7"))
    }

    @Test
    fun tagWithoutBuildSuffixReadsAsZero() {
        // "v1.0" means build 0, which is older than every CI build, so the
        // updater offers the real release rather than staying silent.
        assertEquals(0L, UpdateManager.parseBuildNumber("v1.0"))
    }

    @Test
    fun noDigitsMeansUnknown() {
        assertNull(UpdateManager.parseBuildNumber("nightly"))
        assertNull(UpdateManager.parseBuildNumber(""))
    }

    @Test
    fun onlyBuildsOfMainAreUpdates() {
        assertTrue(UpdateManager.isMainTag("v1.0.712"))
        assertFalse(UpdateManager.isMainTag("my-branch-v1.0.700"))
        assertFalse(UpdateManager.isMainTag("second-screen-image"))
        assertFalse(UpdateManager.isMainTag("v1.0.712-rc"))
    }

    @Test
    fun datedTagsAreReleasesAndCountByDate() {
        assertTrue(UpdateManager.isMainTag("v2026.11.06"))
        assertTrue(UpdateManager.isMainTag("v2026.11.06.2"))
        assertFalse(UpdateManager.isMainTag("v2026.11.06-test"))
        assertEquals(2026110600L, UpdateManager.parseBuildNumber("v2026.11.06"))
        assertEquals(2026110602L, UpdateManager.parseBuildNumber("v2026.11.06.2"))
        assertTrue(UpdateManager.parseBuildNumber("v2026.11.07")!! > UpdateManager.parseBuildNumber("v2026.11.06.2")!!)
        // Every dated build is newer than any upstream-style "v1.0.n" one.
        assertTrue(UpdateManager.parseBuildNumber("v2026.11.06")!! > 100_000L)
    }
}
