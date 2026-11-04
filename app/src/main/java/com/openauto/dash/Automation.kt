package com.openauto.dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.openauto.dash.vehicle.ignitionFlow
import com.openauto.dash.vehicle.reversingFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Tasker, MacroDroid, Automate and the like: they tell Dashwheel what to do
 * with a broadcast ("com.openauto.dash.action.…", see [Command]), and hear
 * what the car does ("com.openauto.dash.event.…", see [Event]). Off until the
 * driver turns it on in Settings: any app on the unit could send these. The
 * commands are the voice assistant's own ([AssistantTools]), so they do what
 * the assistant does and nothing more. The events carry no place.
 */
object Automation {
    private const val TAG = "Automation"
    private const val PREFS = "automation"
    private const val KEY_ON = "on"

    const val ACTION_PREFIX = "com.openauto.dash.action."
    const val EVENT_PREFIX = "com.openauto.dash.event."

    /** What other apps may ask, by the last part of the action. */
    enum class Command {
        /** extra "action": a steering-wheel action's name (MEDIA_NEXT, SAY_STATUS, QUIET…). */
        RUN_ACTION,
        /** extra "name": a dashboard's name. */
        SHOW_DASHBOARD,
        /** extra "destination": "home", "work", a saved place, or an address. */
        NAVIGATE,
        /** extra "text": said in the launcher's voice. */
        SAY,
        /** extra "appearance" (AUTO, DARK, LIGHT) and / or "skin" (a skin's name). */
        SET_LOOK,
        /** extra "level": the music volume. */
        SET_VOLUME,
        /** extra "name": an app's name as on its icon. */
        OPEN_APP,
        /** extra "minutes"; optional boolean "add". */
        START_PARKING_TIMER,
        STOP_PARKING_TIMER,
        /** extra "code": a shared dashboard ("DW1:…"), shown before it replaces anything. */
        IMPORT_DASHBOARD
    }

    /** What Dashwheel tells other apps, by the last part of the action. */
    enum class Event { IGNITION_ON, IGNITION_OFF, REVERSE_ON, REVERSE_OFF, OBD_CONNECTED, OBD_DISCONNECTED, FAULT_CODES }

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        _on.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
        // Registered while running, so the senders need no package or class:
        // the launcher runs for as long as the unit is on.
        val filter = IntentFilter().apply { Command.entries.forEach { addAction(ACTION_PREFIX + it.name) } }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        watchEvents(app)
    }

    fun setOn(context: Context, value: Boolean) {
        _on.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, value).apply()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val command = intent.action?.removePrefix(ACTION_PREFIX)?.let { a -> Command.entries.firstOrNull { it.name == a } } ?: return
            if (!_on.value) {
                Log.i(TAG, "$command ignored: automation is off in Settings")
                return
            }
            val app = context.applicationContext
            scope.launch {
                val said = runCatching { run(app, command, intent) }.getOrElse { "failed: ${it.message}" }
                Log.i(TAG, "$command: $said")
            }
        }
    }

    /** Runs [command] with [intent]'s extras, as the voice assistant would; what it answers goes to the log. */
    internal suspend fun run(context: Context, command: Command, intent: Intent): Any = when (command) {
        Command.RUN_ACTION -> AssistantTools.run(context, "do_action", JSONObject().put("action", intent.text("action").uppercase()))
        Command.SHOW_DASHBOARD -> AssistantTools.run(context, "show_dashboard", JSONObject().put("name", intent.text("name")))
        Command.NAVIGATE -> AssistantTools.run(context, "navigate_to", JSONObject().put("destination", intent.text("destination")))
        Command.SET_VOLUME -> intent.number("level")?.let { AssistantTools.run(context, "set_volume", JSONObject().put("level", it.toInt())) } ?: "no level"
        Command.OPEN_APP -> AssistantTools.run(context, "open_app", JSONObject().put("name", intent.text("name")))
        Command.START_PARKING_TIMER -> intent.number("minutes")?.let {
            AssistantTools.run(context, "start_parking_timer", JSONObject().put("minutes", it).put("add_to_running", intent.flag("add")))
        } ?: "no minutes"
        Command.STOP_PARKING_TIMER -> AssistantTools.run(context, "stop_parking_timer", JSONObject())
        Command.SAY -> {
            val text = intent.text("text").take(MAX_SAY)
            if (text.isBlank()) "no text" else { CarVoice.speak(text, InAppNav.locale(context)); "said" }
        }
        Command.SET_LOOK -> {
            val appearance = DashAppearance.entries.firstOrNull { it.name.equals(intent.text("appearance"), ignoreCase = true) }
            val skin = DashThemeMode.entries.firstOrNull { it.name.equals(intent.text("skin").replace(' ', '_'), ignoreCase = true) }
            appearance?.let { DashThemeStore.askAppearance(context, it) }
            skin?.let { DashThemeStore.askMode(context, it) }
            if (appearance == null && skin == null) "no appearance or skin" else "look set"
        }
        Command.IMPORT_DASHBOARD -> {
            val code = intent.text("code")
            if (DashShare.decode(code) == null) "no dashboard code" else { DashShare.receive(code); "shown" }
        }
    }

    /** The car's events, each told once as it changes (never the state at start-up). */
    private fun watchEvents(context: Context) {
        scope.launch {
            ignitionFlow().filterNotNull().distinctUntilChanged().drop(1)
                .collect { tell(context, if (it) Event.IGNITION_ON else Event.IGNITION_OFF) }
        }
        scope.launch {
            reversingFlow().drop(1)
                .collect { tell(context, if (it) Event.REVERSE_ON else Event.REVERSE_OFF) }
        }
        scope.launch {
            ObdBluetoothManager.connectionState.map { it == ObdConnectionState.CONNECTED }.distinctUntilChanged().drop(1)
                .collect { tell(context, if (it) Event.OBD_CONNECTED else Event.OBD_DISCONNECTED) }
        }
        scope.launch {
            // Told when a module's faults change: a new code, a cleared one, or one turned active.
            AiMechanic.state.map { it.faultKeys to it.codes.orEmpty() }.distinctUntilChanged { a, b -> a.first == b.first }.drop(1)
                .map { it.second }
                .collect { codes -> if (codes.isNotEmpty()) tell(context, Event.FAULT_CODES) { putExtra("codes", codes.joinToString(",")) } }
        }
    }

    private fun tell(context: Context, event: Event, extras: Intent.() -> Unit = {}) {
        if (!_on.value || DemoMode.isOn) return
        context.sendBroadcast(Intent(EVENT_PREFIX + event.name).apply(extras))
    }

    private const val MAX_SAY = 500

    // Tasker sends every extra as text; other apps may send numbers or booleans.
    @Suppress("DEPRECATION")
    private fun Intent.text(name: String): String = extras?.get(name)?.toString()?.trim().orEmpty()

    private fun Intent.number(name: String): Double? = text(name).replace(',', '.').toDoubleOrNull()

    private fun Intent.flag(name: String): Boolean = text(name).lowercase() in setOf("true", "1", "yes", "on")
}
