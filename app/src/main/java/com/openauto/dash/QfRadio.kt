package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The K706's FM radio (com.android.fmradio.ext) from a tile: its stored
 * stations (its open station list), the one playing (its broadcast), and a
 * tap to tune. The radio only takes orders while its screen is alive, so
 * when it isn't, it is started first and the dashboard brought back.
 */
internal object QfRadio : StockRadio {
    private const val TAG = "QfRadio"
    const val PACKAGE = "com.android.fmradio.ext"
    private val ACTIVITY = ComponentName(PACKAGE, "com.android.fmradio.FmMainActivity")
    private val STATIONS = Uri.parse("content://$PACKAGE/station")
    private const val UPDATE = "com.qf.radio.update_action"
    // Tunes to the MHz string in TUNE_EXTRA on the band the radio is on, with no region
    // check. The voice command ("ailit.set.radio.frequency") checks the frequency against
    // band FM1 of the unit's radio region and says "Invalid frequency, please reset" for
    // good stations (Eastern Europe FM1 is 65-74 MHz, the US region only takes odd tenths).
    private const val TUNE = "/customize/radio/station"
    /** Steps the radio to its next band (FM1, FM2, FM3, AM1, AM2, then FM1 again). */
    private const val BAND = "/customize/radio/band"
    /** At most this many steps from AM back to FM, each awaited this long. */
    private const val BAND_STEPS = 4
    private const val BAND_WAIT_MS = 1_500L
    private const val TUNE_EXTRA = "com.qf.radio.update_action_key"
    private const val AM_BAND = 3
    private const val NEXT = "/customize/radio/next"
    private const val PREVIOUS = "/customize/radio/pre"
    private const val START_WAIT_MS = 1_800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var users = 0

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    override val stations: StateFlow<List<RadioStation>> = _stations
    private val _playing = MutableStateFlow<RadioStation?>(null)
    override val playing: StateFlow<RadioStation?> = _playing
    /** The radio's band from its last broadcast: 0..2 FM1..FM3, 3 AM, -1 not heard yet. */
    @Volatile private var band = -1

    override fun installed(context: Context): Boolean = isPackageInstalled(context, PACKAGE)

    override val packageName: String = PACKAGE
    override val judgesSource = false
    override val emptyTextRes = R.string.widgets_radio_empty
    override val running: Boolean get() = systemProperty("sys.qf.radio.status") == "true"
    override fun isPlaying(): Boolean = running
    override fun isCurrentSource(otherSessionPlaying: Boolean): Boolean = HeadUnitMedia.source() == PACKAGE
    /** What the media tile shows of the radio comes from [HeadUnitMedia] (the MCU's tuner and RDS, the play state). */
    override fun mediaState(): MediaState? = HeadUnitMedia.state(PACKAGE)
    override fun addListener(listener: () -> Unit) { HeadUnitMedia.addListener(listener) }
    override fun removeListener(listener: () -> Unit) { HeadUnitMedia.removeListener(listener) }
    override fun playPause(context: Context) = HeadUnitMedia.playPause(context, PACKAGE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val freq = intent.getIntExtra("${UPDATE}_freq_key", 0).takeIf { it > 0 } ?: return
            band = intent.getIntExtra("${UPDATE}_band_key", -1)
            val name = intent.getStringExtra("${UPDATE}_name_key").orEmpty().trim()
            _playing.value = RadioStation(freq, name.ifEmpty { names(context).getString(freq.toString(), null).orEmpty() })
            // The radio keeps its station names to itself: remember the one it announces.
            if (name.isNotEmpty() && band in 0 until AM_BAND && isFm(freq) && !intent.getBooleanExtra("${UPDATE}_searching_key", false)) learn(context, freq, name)
        }
    }

    override fun start(context: Context) {
        val app = context.applicationContext
        if (users++ == 0) ContextCompat.registerReceiver(app, receiver, IntentFilter(UPDATE), ContextCompat.RECEIVER_EXPORTED)
        scope.launch { _stations.value = withContext(Dispatchers.IO) { read(app) } }
        if (_playing.value == null) {
            systemProperty("sys.qf.station_freq")?.toIntOrNull()?.takeIf { it > 0 }?.let { _playing.value = RadioStation(it, "") }
        }
    }

    override fun stop(context: Context) {
        if (users == 0 || --users > 0) return
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }

    private fun names(context: Context) = context.applicationContext.getSharedPreferences("radio_names", Context.MODE_PRIVATE)

    private fun learn(context: Context, freq: Int, name: String) {
        val prefs = names(context)
        if (prefs.getString(freq.toString(), null) == name) return
        prefs.edit().putString(freq.toString(), name).apply()
        _stations.value = _stations.value.map { if (it.freq == freq) it.copy(name = name) else it }
    }

    /** The stored stations, favourites and presets first, at most [StockRadio.SHOWN]. */
    private fun read(context: Context): List<RadioStation> = runCatching {
        val learned = names(context)
        val out = ArrayList<Triple<RadioStation, Int, Boolean>>()
        context.contentResolver.query(STATIONS, null, null, null, null)?.use { c ->
            val f = c.getColumnIndex("frequency")
            val n = c.getColumnIndex("station_name")
            val ps = c.getColumnIndex("program_service")
            val p = c.getColumnIndex("preset")
            val fav = c.getColumnIndex("is_favorite")
            while (c.moveToNext()) {
                if (f < 0) break
                // FM only, told by its frequency: the table's band numbers are not the broadcast's,
                // and the AM presets (522 to 1620 kHz) showed as "5.2" to "16.2" and tuned AM.
                val freq = c.getInt(f).takeIf { isFm(it) } ?: continue
                val name = listOf(n, ps).firstNotNullOfOrNull { i -> if (i >= 0) c.getString(i)?.trim()?.takeIf { it.isNotEmpty() } else null }
                    ?: learned.getString(freq.toString(), null).orEmpty()
                out += Triple(RadioStation(freq, name), if (p >= 0) c.getInt(p) else 0, fav >= 0 && c.getInt(fav) != 0)
            }
        }
        out.sortedWith(compareByDescending<Triple<RadioStation, Int, Boolean>> { it.third }.thenBy { if (it.second > 0) it.second else Int.MAX_VALUE })
            .map { it.first }.distinctBy { it.freq }.take(StockRadio.SHOWN)
    }.onFailure { Log.w(TAG, "stations unread: ${it.message}") }.getOrDefault(emptyList())

    override fun tune(context: Context, station: RadioStation) = command(context) { app ->
        // The radio tunes the string on the band it is on ("10.0" became 999 kHz on AM):
        // stepped to FM first, each step awaited in its own broadcast.
        var steps = 0
        while (band >= AM_BAND && steps++ < BAND_STEPS) {
            val before = band
            app.sendBroadcast(Intent(BAND))
            withTimeoutOrNull(BAND_WAIT_MS) { while (band == before) delay(100) }
        }
        app.sendBroadcast(Intent(TUNE).putExtra(TUNE_EXTRA, station.mhz))
        _playing.value = station
    }

    override fun next(context: Context) = command(context) { it.sendBroadcast(Intent(NEXT)) }
    override fun previous(context: Context) = command(context) { it.sendBroadcast(Intent(PREVIOUS)) }

    /** [send] once the radio's screen is alive: started first when it isn't, and the dashboard brought back over it. */
    private fun command(context: Context, send: suspend (Context) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            if (systemProperty("sys.qf.radio.status") != "true") {
                runCatching { app.startActivity(Intent().setComponent(ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Log.w(TAG, "can't start the radio", it); return@launch }
                delay(START_WAIT_MS)
                send(app)
                runCatching {
                    app.startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            } else {
                send(app)
            }
        }
    }

    private fun systemProperty(name: String): String? = SysProps.get(name)
}
