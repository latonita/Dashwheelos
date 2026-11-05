package com.openauto.dash

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What is open on the dashboard ([AutomotiveDashboard]): its sheets, dialogs,
 * pickers and panels, arranging, the setup and the tour, with the ways of
 * closing them together (the Home key, the drive lock). Plain snapshot state,
 * made once per dashboard with remember {}, so it is testable on the JVM.
 *
 * The dashboard reads and writes these through local delegates of the same
 * names (`var editing by ui::editing`), so its code reads as before.
 */
@Stable
internal class DashboardUiState(
    settingsTab: SettingsTab? = null,
    setupDone: Boolean = true,
    setupPillOff: Boolean = false,
) {
    /** The templates dialog. */
    var showTemplates by mutableStateOf(false)
    /** The Moments screen (MomentsScreen.kt). */
    var showMoments by mutableStateOf(false)
    /** An empty page's own Fill: the page the templates dialog lays out alone, while open. */
    var fillPage by mutableStateOf<Int?>(null)
    /** The bar's own settings, from a long press on it. */
    var barSettings by mutableStateOf(false)
    /**
     * The Settings screen, on the tab it was opened to; null while closed. A
     * dashboard rebuilt by a turn of the screen made in Settings opens there again.
     */
    var settingsTab by mutableStateOf(settingsTab)
    /** (page, index) of the launch bar whose apps are being edited. */
    var launchBarEditor by mutableStateOf<Pair<Int, Int>?>(null)
    /** (page, index) of the Custom gauge whose signal is being chosen (a fresh copy opens it at once). */
    var gaugeEditor by mutableStateOf<Pair<Int, Int>?>(null)

    var showAllApps by mutableStateOf(false)
    var showSplitPicker by mutableStateOf(false)
    var showSplitEnable by mutableStateOf(false)
    var editing by mutableStateOf(false)

    // The dashboard bar's sheets: a dashboard's (by index, -1 for a new one), an
    // app's (by index), the rail's "dashboard or app", the app picker.
    var dashSheet by mutableStateOf<Int?>(null)
    var appSheet by mutableStateOf<Int?>(null)
    var showBarChooser by mutableStateOf(false)
    var pickBarApp by mutableStateOf(false)

    var showDevicePicker by mutableStateOf(false)
    /** The "+" sheet that offers tiles (AddSheet.kt). */
    var showAddSheet by mutableStateOf(false)
    /** (page, tile index) whose panel is open while arranging (TileOptions.kt). */
    var tileOptions by mutableStateOf<Pair<Int, Int>?>(null)
    /** "Remove dashboard" asked about a page: empty it, or take it out of the swipes. */
    var removeChoice by mutableStateOf<Int?>(null)
    /** Asked before a template replaces dashboards. */
    var confirmTemplate by mutableStateOf<TemplateChoice?>(null)
    /** A tile moved to a dashboard out of the swipes: asked whether to bring it back first (page, tile index, target). */
    var confirmBringBack by mutableStateOf<Triple<Int, Int, Int>?>(null)

    /** System-app install (root): unlocks embedding the real Google Maps app. */
    var showSystemDialog by mutableStateOf(false)

    // The setup (SetupScreen.kt): its step while open, null while closed. A
    // fresh install starts on it; Settings and the bar's pill reopen it.
    var setupDone by mutableStateOf(setupDone)
    var setupPillOff by mutableStateOf(setupPillOff)
    var setupStep by mutableStateOf(if (setupDone) null else SetupStep.CAR)
    // The step on screen, and the one to come back to once the drive lock
    // has closed a setup that was not done yet.
    var setupShownStep by mutableStateOf(SetupStep.CAR)
    var setupResume by mutableStateOf<SetupStep?>(null)
    /** An adapter was just chosen while no page holds a car tile: asked whether to add them. */
    var offerCarTiles by mutableStateOf(false)

    // The tour of the basics (TourScreen.kt): its step while open. Offered once
    // after the first setup, at most once per start, and only parked.
    var tourStep by mutableStateOf<TourStep?>(null)
    var tourOffered by mutableStateOf(false)
    /** Up by itself after the setup (its look tip is left out, the setup just asked that) rather than replayed from Settings. */
    var tourAfterSetup by mutableStateOf(false)
    /** Arranging was turned on by the tour, for its tips about the tiles. */
    var tourEditing by mutableStateOf(false)

    /**
     * Closes Settings: its open sheet leaves first. A screen direction on trial
     * is still on trial: the dashboard goes on asking (KeepDirectionStrip).
     */
    fun closeSettings() {
        if (settingsTab == null) return
        OpenSheet.dismiss()
        settingsTab = null
    }

    /** The Home key: the drawer, Settings and arranging close (the dashboard then shows Home). */
    fun closeForHome() {
        showAllApps = false
        closeSettings()
        editing = false
    }

    /**
     * The drive lock engaged: arranging, settings and pickers wait until parked.
     * A first setup it closes comes back where it was ([resumeAfterDriving]).
     */
    fun closeForDriving() {
        editing = false
        closeSettings()
        showTemplates = false
        fillPage = null
        showMoments = false
        showSystemDialog = false
        showAddSheet = false
        tileOptions = null
        removeChoice = null
        confirmBringBack = null
        confirmTemplate = null
        // A first setup the lock closes comes back where it was, once parked.
        if (setupStep != null && !setupDone) setupResume = setupShownStep
        setupStep = null
        offerCarTiles = false
        showSplitPicker = false
        showSplitEnable = false
        showDevicePicker = false
        launchBarEditor = null
        gaugeEditor = null
        // Stopped, not seen: offered again at the next start.
        tourStep = null
    }

    /** Parked again: a first setup the lock closed comes back on its step. */
    fun resumeAfterDriving() {
        setupResume?.let { step ->
            setupResume = null
            if (!setupDone) setupStep = step
        }
    }

    /** Something covering the whole screen is open: windows step aside, the bar steps down. */
    val fullScreenSheet: Boolean
        get() = showAllApps || settingsTab != null || showAddSheet || setupStep != null ||
            showTemplates || fillPage != null || showMoments

    /** The tour may come up by itself: the first setup has closed, parked, and nothing a bar button opens is up. */
    fun tourCanStart(moving: Boolean): Boolean =
        setupStep == null && !moving && !showAllApps && settingsTab == null && !editing &&
            !showTemplates && !showMoments && !showSplitPicker && !showSplitEnable
}
