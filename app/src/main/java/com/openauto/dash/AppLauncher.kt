package com.openauto.dash

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * A launchable app installed on the device.
 *
 * [icon] is the app's launcher icon; [packageName] identifies it for launching.
 */
@Immutable
data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

/**
 * Reads the list of launchable apps from [PackageManager] and launches them.
 *
 * Enumerating other apps requires the `<queries>` element declared in the
 * manifest (Android 11+ package visibility). Apps are launched fullscreen via
 * their launch [Intent] — Android does not allow a normal app to embed another
 * app's UI inside a view, so "opening" an app hands the whole screen to it.
 */
object AppLauncher {
    // WindowConfiguration.WINDOWING_MODE_FULLSCREEN (@hide).
    private const val WINDOWING_MODE_FULLSCREEN = 1


    /**
     * All launchable apps except this launcher, sorted alphabetically by label.
     * An app with more than one entry in the app list gets one per entry: the
     * one its package opens keyed by the package, the others by
     * "package/activity" ([launchIntent]), as the car app's Front Camera
     * beside its AUX.
     */
    fun loadApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val opens = HashMap<String, String?>()

        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val info = resolveInfo.activityInfo ?: return@mapNotNull null
                val pkg = info.packageName
                if (pkg == context.packageName) return@mapNotNull null // hide ourselves
                if (pkg == ROM_CAMERA_PACKAGE && info.name == ROM_FRONT_CAMERA && !hasFrontCamera()) return@mapNotNull null
                val main = if (pkg in opens) opens[pkg] else pm.getLaunchIntentForPackage(pkg)?.component?.className.also { opens[pkg] = it }
                runCatching {
                    AppEntry(
                        label = resolveInfo.loadLabel(pm).toString(),
                        packageName = if (main == null || main == info.name) pkg else "$pkg/${info.name}",
                        icon = resolveInfo.loadIcon(pm)
                    )
                }.getOrNull()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** The car app's camera screens: its AUX input, and the front camera beside it. */
    private const val ROM_CAMERA_PACKAGE = "com.qf.backcar"
    private const val ROM_FRONT_CAMERA = "com.qf.backcar.FrontVdoActivity"

    /** The unit is set up with a front camera: the car app's own launcher lists it only then (persist.sys.foresight_time above 0). */
    @android.annotation.SuppressLint("PrivateApi")
    private fun hasFrontCamera(): Boolean = runCatching {
        val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, RomAfterReverse.FRONT_TIME) as String
        (v.trim().toIntOrNull() ?: 0) > 0
    }.getOrDefault(false)

    /** The package of an app list key ([loadApps]): the key itself, or the part before "/". */
    fun packageOf(key: String): String = key.substringBefore('/')

    /** What opens the app list entry [key] ([loadApps]); null when it is gone. */
    fun launchIntent(context: Context, key: String): Intent? {
        val activity = key.substringAfter('/', "")
        if (activity.isEmpty()) return context.packageManager.getLaunchIntentForPackage(key)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(packageOf(key), activity)
        return intent.takeIf { context.packageManager.resolveActivity(it, 0) != null }
    }

    /**
     * Calls [onChange] (on the main thread) whenever an app is installed,
     * removed or updated, so a launcher that stays up for days still lists
     * what is really there. Returns the call that stops watching.
     */
    fun watchPackages(context: Context, onChange: () -> Unit): () -> Unit {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return {}
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = onChange()
            override fun onPackageAdded(packageName: String, user: UserHandle) = onChange()
            override fun onPackageChanged(packageName: String, user: UserHandle) = onChange()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = onChange()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = onChange()
        }
        launcherApps.registerCallback(callback)
        return { launcherApps.unregisterCallback(callback) }
    }

    /**
     * Launches [packageName] fullscreen. Returns false if it has no launch
     * intent. Full screen is asked for: on a unit with freeform windows,
     * Android otherwise brings an app back in the small window it last had
     * (the K706's TPMS app came up 220 dp wide in a corner).
     */
    fun launch(context: Context, packageName: String): Boolean {
        val launchIntent = launchIntent(context, packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        val options = ActivityOptions.makeBasic()
        LaunchModeCompat.apply(options, WINDOWING_MODE_FULLSCREEN)
        val launched = runCatching { context.startActivity(launchIntent, options.toBundle()); true }
            .recoverCatching { context.startActivity(launchIntent); true }.getOrDefault(false)
        if (launched) AppUsage.opened(context, packageName)
        return launched
    }
}

/**
 * How often each app was opened from the launcher, so the drawer can put the
 * ones the driver really uses first instead of an alphabet to read through.
 */
object AppUsage {
    private const val PREFS = "app_usage"
    private const val KEY = "counts"
    /** Past this, every count is halved: what was used a lot long ago makes room for what is used now. */
    private const val HALVE_AT = 200

    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        _counts.value = runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optInt(it) }.filterValues { it > 0 }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun opened(context: Context, packageName: String) {
        load(context)
        val next = counted(_counts.value, packageName)
        _counts.value = next
        val json = JSONObject().apply { next.forEach { (pkg, n) -> put(pkg, n) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    /** [counts] with one more opening of [packageName]. */
    internal fun counted(counts: Map<String, Int>, packageName: String): Map<String, Int> {
        val next = counts + (packageName to (counts[packageName] ?: 0) + 1)
        return if (next.getValue(packageName) < HALVE_AT) next else next.mapValues { it.value / 2 }.filterValues { it > 0 }
    }

    /**
     * The apps opened most among [installed], most used first, [max] at most.
     * Only those opened at least [MIN_OPENINGS] times: one try is not a habit.
     * Empty until at least two qualify, a row of one being no help.
     */
    internal fun mostUsed(installed: List<String>, counts: Map<String, Int>, max: Int = 6): List<String> {
        val used = installed
            .filter { (counts[it] ?: 0) >= MIN_OPENINGS }
            .sortedByDescending { counts.getValue(it) }
            .take(max)
        return if (used.size < 2) emptyList() else used
    }

    const val MIN_OPENINGS = 2
}

/** How big the app drawer draws its apps: the driver's pick, from the drawer's own header. */
object DrawerIconSize {
    private const val PREFS = "app_drawer"
    private const val KEY = "icon_scale"
    /** Normal, large, larger; a tap on the header's button goes to the next. */
    internal val SCALES = listOf(1f, 1.3f, 1.6f)

    private val _scale = MutableStateFlow(SCALES.first())
    val scale: StateFlow<Float> = _scale.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, SCALES.first())
        _scale.value = SCALES.firstOrNull { it == saved } ?: SCALES.first()
    }

    @Synchronized
    fun next(context: Context) {
        load(context)
        val next = SCALES[(SCALES.indexOf(_scale.value) + 1) % SCALES.size]
        _scale.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, next).apply()
    }
}
