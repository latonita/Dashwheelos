package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The XYAuto firmware's FM radio (com.android.fmradio). It tells by broadcast what it tunes (`freq_volue`, on every
 * tune, preset step and seek, not on pause) and when its screen is created or destroyed (`xy.radio.onstart` /
 * `xy.radio.onfinish`); there is no play broadcast, no property, no setting. It takes its orders as broadcasts too,
 * but only while its screen is alive (a receiver registered in onCreate): when it isn't, it is started with the
 * frequency as an extra and the dashboard brought back. Its presets are in a private provider, so the presets are a
 * list of the driver's own ([FmStations]): the driver adds the station that is playing and removes them, and nothing
 * is ever added from what the radio tunes.
 *
 * Whether it plays is not announced: it is up, the last order wasn't pause, and it holds the audio focus (read from
 * the vendor's `AudioManager.getCurrentAudioFocusContentName()` where there is one, else "music is active").
 */
internal object XyFmRadio : StockRadio {
    private const val TAG = "XyFmRadio"
    const val PACKAGE = "com.android.fmradio"
    private val ACTIVITY = ComponentName(PACKAGE, "com.android.fmradio.FmMainActivity")

    private const val ACTION_FREQ = "com.android.radio.widget.freq_volue"
    private const val ACTION_ON_START = "xy.radio.onstart"
    private const val ACTION_ON_FINISH = "xy.radio.onfinish"
    private const val EXTRA_FREQ = "freq"
    private const val EXTRA_BAND = "fmoram"
    private const val EXTRA_FREQ_TEXT = "freqstr"

    /** Tunes the digits in [EXTRA_FM] (tenths of a MHz: "882"). */
    private const val ACTION_TUNE = "xy.setfm.freq"
    private const val EXTRA_FM = "fm"
    private const val ACTION_NEXT = "xy.android.nextmedia"
    private const val ACTION_PREVIOUS = "xy.android.previousmedia"
    private const val ACTION_PLAY = "xy.android.forceplay"
    private const val ACTION_PAUSE = "xy.android.forcepause"

    private const val START_WAIT_MS = 1_800L
    private const val PREFS = "xy_fm_radio"
    private const val KEY_LAST = "last_freq"
    private const val KEY_STATIONS = "stations"
    private const val KEY_RDS_NAMES = "rds_names"
    private const val KEY_RDS_PI = "rds_pi"
    private const val KEY_STATION_PI = "station_pi"
    private const val KEY_STATION_GROUPS = "station_groups"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var users = 0
    private var app: Context? = null

    @Volatile private var alive = false
    /** The last order we sent was pause (a tune or any broadcast of the radio's own clears it). */
    @Volatile private var paused = false
    @Volatile private var lastFreqAt = 0L
    private var saved = FmStations()
    private var loggedFocus: String? = null

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    private val _playing = MutableStateFlow<RadioStation?>(null)

    override val packageName: String = PACKAGE
    override val judgesSource = true
    override val emptyTextRes = R.string.widgets_radio_empty_add
    override val editable = true
    override val stations: StateFlow<List<RadioStation>> = _stations
    override val playing: StateFlow<RadioStation?> = _playing
    override val running: Boolean get() = alive

    override fun installed(context: Context): Boolean = isPackageInstalled(context, PACKAGE)

    override fun addListener(listener: () -> Unit) { listeners.add(listener) }
    override fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun tell() = listeners.forEach { runCatching { it() }.onFailure { e -> Log.w(TAG, "listener failed", e) } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_FREQ -> {
                    val freq = parseFreqVolue(
                        intent.getIntExtra(EXTRA_FREQ, 0), intent.getStringExtra(EXTRA_BAND), intent.getStringExtra(EXTRA_FREQ_TEXT)
                    ) ?: return
                    // A tune means the radio is up and sounding.
                    alive = true
                    paused = false
                    lastFreqAt = SystemClock.elapsedRealtime()
                    note(freq)
                }
                ACTION_ON_START -> { alive = true; paused = false }
                ACTION_ON_FINISH -> alive = false
                else -> return
            }
            tell()
        }
    }

    override fun start(context: Context) {
        val a = context.applicationContext
        if (users++ > 0) return
        app = a
        val prefs = prefs(a)
        saved = FmStations.parse(prefs.getString(KEY_STATIONS, null), pis = prefs.getString(KEY_STATION_PI, null), groups = prefs.getString(KEY_STATION_GROUPS, null))
        heardPi = FmPis.parse(prefs.getString(KEY_RDS_PI, null))
        learned = FmLearned.parse(prefs.getString(KEY_RDS_NAMES, null))
        _stations.value = shownStations()
        // What it was on at the last run, so a restart shows something before the radio says anything.
        prefs.getInt(KEY_LAST, 0).takeIf(::isFm)?.let { _playing.value = named(it) }
        // The radio's screen can't be asked about; holding the audio is the one sign of a radio that is up already.
        alive = focusName()?.contains("fmradio", ignoreCase = true) == true
        val filter = IntentFilter().apply { listOf(ACTION_FREQ, ACTION_ON_START, ACTION_ON_FINISH).forEach(::addAction) }
        ContextCompat.registerReceiver(a, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun stop(context: Context) {
        if (users == 0 || --users > 0) return
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The names stations broadcast, as the radio's session told them. */
    private var learned = FmLearned()

    private fun shownStations(): List<RadioStation> = saved.stations.map {
        RadioStation(it.last, fmDisplayName(it.name, learned.nameOf(it.last)), it.pi.ifEmpty { heardPi.piOf(it.last) }, it.freqs)
    }

    /** Writes the driver's list: the lines, the PI of each, and which lines are one station. */
    private fun persist() {
        app?.let {
            prefs(it).edit().putString(KEY_STATIONS, saved.serialize()).putString(KEY_STATION_PI, saved.serializePis())
                .putString(KEY_STATION_GROUPS, saved.serializeGroups()).apply()
        }
    }

    /** [freq] plays: it is the last one of its station. */
    private fun touch(freq: Int) {
        val next = saved.played(freq)
        if (next === saved) return
        saved = next
        persist()
        _stations.value = shownStations()
    }

    override fun ownName(station: RadioStation): String = saved.nameOf(station.freq)

    /** The PI codes heard, by frequency. */
    private var heardPi = FmPis()

    override fun onSession(mediaId: String?, title: String?, pi: String?) {
        // Only what the session says about the frequency in its own MEDIA_ID: a PI from before a retune isn't kept.
        val freq = parseFmMediaId(mediaId) ?: return
        val names = learned.learn(freq, title)
        val pis = heardPi.learn(freq, pi)
        val filled = saved.fillPi(freq, pi).played(freq)
        if (names === learned && pis === heardPi && filled === saved) return
        learned = names
        heardPi = pis
        saved = filled
        persist()
        app?.let { prefs(it).edit().putString(KEY_RDS_NAMES, learned.serialize()).putString(KEY_RDS_PI, heardPi.serialize()).apply() }
        _stations.value = shownStations()
        _playing.value = _playing.value?.let { named(it.freq) }
        tell()
    }

    /** A station with the name we know for it (the driver's, else the broadcast one, else the QF-era `radio_names`). */
    private fun named(freq: Int): RadioStation {
        val name = fmDisplayName(saved.nameOf(freq), learned.nameOf(freq)).ifEmpty {
            app?.getSharedPreferences("radio_names", Context.MODE_PRIVATE)?.getString(freq.toString(), null).orEmpty()
        }
        return RadioStation(freq, name, saved.piOf(freq).ifEmpty { heardPi.piOf(freq) })
    }

    /** The radio tuned [freq]: what is playing, and what it was on last. The list of presets is left alone. */
    private fun note(freq: Int) {
        _playing.value = named(freq)
        app?.let { prefs(it).edit().putInt(KEY_LAST, freq).apply() }
        touch(freq)
    }

    private fun edit(change: (FmStations) -> FmStations) {
        saved = change(saved)
        _stations.value = shownStations()
        _playing.value = _playing.value?.let { named(it.freq) }
        persist()
    }

    override fun add(context: Context, station: RadioStation) = edit { it.add(station.freq, station.name, pi = station.pi.ifEmpty { heardPi.piOf(station.freq) }) }

    override fun remove(context: Context, station: RadioStation) = edit { it.remove(station.freq) }

    override fun rename(context: Context, station: RadioStation, name: String) = edit { it.rename(station.freq, name) }

    override fun removeFrequency(context: Context, station: RadioStation, freq: Int) = edit { it.removeFreq(freq) }

    // --- Is it playing -----------------------------------------------------

    /** The vendor's name for whoever holds the audio focus, or null where the call isn't there (or fails). */
    private fun focusName(): String? {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return runCatching {
            val name = AudioManager::class.java.getMethod("getCurrentAudioFocusContentName").invoke(am) as? String
            if (name != loggedFocus) { loggedFocus = name; Log.i(TAG, "audio focus held by: $name") }
            name
        }.getOrNull()
    }

    /** True once the reflected call exists, whatever it answers. */
    private val focusApi: Boolean by lazy {
        runCatching { AudioManager::class.java.getMethod("getCurrentAudioFocusContentName"); true }.getOrDefault(false)
    }

    override fun hasAudioFocus(): Boolean {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return fmHasFocus(focusName(), focusApi, runCatching { am.isMusicActive }.getOrDefault(false))
    }

    override fun isPlaying(): Boolean =
        fmIsPlaying(alive, paused, hasAudioFocus(), if (lastFreqAt == 0L) -1L else SystemClock.elapsedRealtime() - lastFreqAt)

    override fun isCurrentSource(otherSessionPlaying: Boolean): Boolean =
        fmIsCurrentSource(alive, hasAudioFocus(), otherSessionPlaying)

    override fun mediaState(): MediaState? = _playing.value?.let { fmMediaState(it, isPlaying()) }

    // --- Orders --------------------------------------------------------------

    /**
     * A tap on a preset: its last used frequency, or, while the preset is what plays, the next of its frequencies.
     * A station with no list (the media tile's) is tuned as it is.
     */
    override fun tune(context: Context, station: RadioStation) {
        val freq = if (station.freqs.size > 1) saved.tapTarget(station.freq, _playing.value?.freq) else station.freq
        val fm = fmExtra(freq) ?: return
        val a = context.applicationContext
        if (alive) a.sendBroadcast(command(ACTION_TUNE).putExtra(EXTRA_FM, fm)) else open(a, fm)
        paused = false
        lastFreqAt = SystemClock.elapsedRealtime()
        _playing.value = named(freq)
        touch(freq)
        tell()
    }

    override fun next(context: Context) = order(context, ACTION_NEXT)

    override fun previous(context: Context) = order(context, ACTION_PREVIOUS)

    override fun playPause(context: Context) {
        val a = context.applicationContext
        if (!alive) return open(a, _playing.value?.freq?.let(::fmExtra))
        if (isPlaying()) {
            a.sendBroadcast(command(ACTION_PAUSE))
            paused = true
        } else {
            a.sendBroadcast(command(ACTION_PLAY))
            paused = false
            lastFreqAt = SystemClock.elapsedRealtime()
        }
        tell()
    }

    /**
     * An order for the open radio only. The radio's manifest receiver also takes `xy.setfm.freq` and
     * opens its screen over Dashwheel; the receiver its screen registers tunes in place.
     */
    private fun command(action: String) = Intent(action).addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)

    private fun order(context: Context, action: String) {
        val a = context.applicationContext
        if (alive) a.sendBroadcast(command(action)) else open(a, _playing.value?.freq?.let(::fmExtra))
    }

    /**
     * Starts the radio's screen (it powers up on [fm], tenths of a MHz, when given) and brings the dashboard back
     * over it. Its extras are ignored by a screen that is up already, which is why a live radio gets broadcasts.
     */
    private fun open(app: Context, fm: String?) {
        scope.launch {
            runCatching {
                app.startActivity(Intent().setComponent(ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { fm?.let { putExtra(EXTRA_FM, it) } })
            }.onFailure { Log.w(TAG, "can't start the radio", it); return@launch }
            delay(START_WAIT_MS)
            runCatching {
                app.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }
    }
}
