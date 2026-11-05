package com.openauto.dash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Two ways a driver tells the project what happened on their unit, each as a
 * GitHub issue filled in for them (a QR code for the phone, or the unit's own
 * browser): a crash, saved when it happens and offered on the next start, and
 * a compatibility report (the unit, its firmware, and what works on it).
 * Neither holds anything personal: no place, no name, no account.
 */

/** The app itself: installs the crash catcher before anything else can run. */
class DashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
        // Before anything can open a socket: the free routing and Overpass
        // servers no longer answer the TLS this Android speaks (ModernTls).
        ModernTls.install()
        // Which head unit firmware this is, before anything asks its adapter.
        UnitPlatform.init(this)
    }
}

/** Crashes kept on the unit (the log is wiped at every restart), offered once on the next start. */
object CrashReports {
    private const val TAG = "CrashReports"
    private const val DIR = "crashes"
    private const val KEEP = 5
    private const val PREFS = "crash_reports"
    private const val KEY_OFFERED = "offered"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written at once, on the dying thread: nothing else is left to do it.
            runCatching { save(app, thread, error) }.onFailure { Log.e(TAG, "could not keep the crash", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun save(context: Context, thread: Thread, error: Throwable) {
        val d = dir(context).apply { mkdirs() }
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val at = System.currentTimeMillis()
        File(d, "crash-$at.txt").writeText(
            "time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(at))}\n" +
                "thread: ${thread.name}\n\n$trace"
        )
        d.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** The newest crash's text, if there is one. */
    fun latest(context: Context): File? = dir(context).listFiles()?.filter { it.name.startsWith("crash-") }?.maxByOrNull { it.name }

    /** The newest crash, if it was never offered yet. */
    fun unoffered(context: Context): File? {
        val f = latest(context) ?: return null
        val offered = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OFFERED, null)
        return f.takeIf { it.name != offered }
    }

    fun markOffered(context: Context, file: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OFFERED, file.name).apply()
    }

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}

/** What a compatibility report asks the driver about. Names go into the issue as they are. */
internal enum class ReportItem(@StringRes val label: Int) {
    HOME(R.string.report_item_home),
    WHEEL(R.string.report_item_wheel),
    TILES(R.string.report_item_tiles),
    MAPS(R.string.report_item_maps),
    OBD(R.string.report_item_obd),
    CAN(R.string.report_item_can),
    CAMERA(R.string.report_item_camera),
    SOUND(R.string.report_item_sound),
    SLEEP(R.string.report_item_sleep)
}

internal enum class ReportAnswer { UNTRIED, WORKS, FAILS }

/** The issue's text and its address. Plain, so it is unit-tested. */
internal object IssueLink {
    /** QR codes past this many characters get too dense to scan off a car screen. */
    const val MAX_URL = 2_300

    fun url(owner: String, repo: String, title: String, body: String, label: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val head = "https://github.com/$owner/$repo/issues/new?labels=${enc(label)}&title=${enc(title)}&body="
        // The body is cut, never the address: GitHub opens it all the same.
        var b = body
        while (b.isNotEmpty() && head.length + enc(b).length > MAX_URL) b = b.dropLast(maxOf(40, b.length / 10))
        return head + enc(if (b.length < body.length) "$b\n…" else b)
    }

    fun compatibilityBody(facts: List<Pair<String, String>>, answers: Map<ReportItem, ReportAnswer>, note: String?): String = buildString {
        appendLine("### Unit")
        facts.forEach { (k, v) -> appendLine("- $k: $v") }
        appendLine()
        appendLine("### What works")
        ReportItem.entries.forEach { item ->
            val a = answers[item] ?: ReportAnswer.UNTRIED
            val mark = when (a) { ReportAnswer.WORKS -> "works"; ReportAnswer.FAILS -> "does NOT work"; ReportAnswer.UNTRIED -> "not tried" }
            appendLine("- ${item.name.lowercase()}: $mark")
        }
        if (!note.isNullOrBlank()) { appendLine(); appendLine("### Notes"); appendLine(note.trim()) }
    }

    fun crashBody(facts: List<Pair<String, String>>, crash: String): String = buildString {
        appendLine("### Unit")
        facts.forEach { (k, v) -> appendLine("- $k: $v") }
        appendLine()
        appendLine("### Crash")
        appendLine("```")
        // The cause's lines first: the frames below the app's own are rarely the story.
        appendLine(crash.lineSequence().filter { it.isNotBlank() }.take(40).joinToString("\n"))
        appendLine("```")
    }
}

/** The unit as a report tells it: model, firmware, Dashwheel's links to it. Nothing personal. */
internal object UnitFacts {
    fun collect(context: Context): List<Pair<String, String>> {
        val car = CarProfileStore.current
        return listOfNotNull(
            "Dashwheel" to "${BuildConfig.VERSION_NAME} (${if (Edition.play) "Play" else "GitHub"}${if (UpdateManager.beta(context)) ", beta" else ""})",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE})",
            // Most of these units claim a newer Android than they run: the API level is the truth.
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Build" to (prop("ro.build.display.id") ?: Build.DISPLAY),
            prop("ro.board.platform")?.let { "Platform" to it },
            *UnitPlatform.reportLines().toTypedArray(),
            prop("persist.sys.qf.mcu.version")?.let { "MCU" to it },
            prop("ro.qf.ota.version")?.let { "OTA" to it },
            "Screen" to context.resources.displayMetrics.let { "${it.widthPixels}x${it.heightPixels} @${it.densityDpi}dpi" },
            "Shell" to PrivilegedShell.access.value.name.lowercase(),
            "Unit keys service" to yes(HeadUnitKeys.listening),
            "MCU service" to yes(McuFeed.listening.value),
            "CAN box data" to yes(CarBox.available),
            if (car.make.isNotBlank()) "Car" to listOf(car.make, car.model, car.year?.toString()).filter { !it.isNullOrBlank() }.joinToString(" ") else null,
            "Language" to Locale.getDefault().toLanguageTag()
        )
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    private fun prop(name: String): String? = SysProps.getOrNull(name)
}

/** Settings → About → Report how it works: the facts, what works, and the issue to send. */
@Composable
internal fun CompatibilityReportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val answers = remember { mutableStateMapOf<ReportItem, ReportAnswer>() }
    val facts = remember { UnitFacts.collect(context) }
    val title = "Compatibility: ${Build.MANUFACTURER} ${Build.MODEL}"
    val url = IssueLink.url(
        BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO, title,
        IssueLink.compatibilityBody(facts, answers.toMap(), null), "compatibility"
    )
    SettingsSheet(
        title = stringResource(R.string.report_title),
        onDismiss = onDismiss,
        actions = {
            SheetButton(stringResource(R.string.report_open), primary = false) {
                context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            SheetButton(stringResource(R.string.ai_done), onClick = onDismiss)
        }
    ) {
        Text(stringResource(R.string.report_compat_intro), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReportItem.entries.forEach { item ->
                    AnswerRow(stringResource(item.label), answers[item] ?: ReportAnswer.UNTRIED) { answers[item] = it }
                }
            }
            Spacer(Modifier.width(16.dp))
            ScanToSend(url)
        }
    }
}

/** One thing to try: not tried, works, doesn't work. */
@Composable
private fun AnswerRow(label: String, answer: ReportAnswer, onAnswer: (ReportAnswer) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        listOf(ReportAnswer.WORKS to R.string.report_works, ReportAnswer.FAILS to R.string.report_fails).forEach { (a, res) ->
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = answer == a,
                onClick = { onAnswer(if (answer == a) ReportAnswer.UNTRIED else a) },
                label = { Text(stringResource(res)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = if (a == ReportAnswer.WORKS) DashColors.Accent.copy(alpha = 0.3f) else DashColors.Critical.copy(alpha = 0.3f),
                    labelColor = DashColors.TextSecondary,
                    selectedLabelColor = DashColors.TextPrimary
                )
            )
        }
    }
}

@Composable
private fun ScanToSend(url: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 240.dp)) {
        QrCode(url, Modifier.size(220.dp))
        Text(
            stringResource(R.string.report_scan), color = DashColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * On the start after a crash, once parked: what happened, offered as an issue
 * to send. Once per crash; "Delete" forgets every saved crash.
 */
@Composable
internal fun CrashOffer() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf<Pair<File, String>?>(null) }
    LaunchedEffect(Unit) {
        crash = withContext(Dispatchers.IO) {
            CrashReports.unoffered(context)?.let { f -> runCatching { f to f.readText() }.getOrNull() }
        }
    }
    val (file, text) = crash ?: return
    fun done() {
        CrashReports.markOffered(context, file)
        crash = null
    }
    // The unit starts with the engine: the offer waits until the car stands, never dropped for driving.
    if (LocalDriveLock.current.moving) return
    val facts = remember { UnitFacts.collect(context) }
    val firstLine = text.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim()?.take(80) ?: "crash"
    val url = IssueLink.url(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO, "Crash: $firstLine", IssueLink.crashBody(facts, text), "crash")
    SettingsSheet(
        title = stringResource(R.string.report_crash_title),
        onDismiss = ::done,
        actions = {
            SheetButton(stringResource(R.string.report_delete), primary = false) {
                CrashReports.deleteAll(context)
                crash = null
            }
            SheetButton(stringResource(R.string.report_open), primary = false) {
                context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                done()
            }
            SheetButton(stringResource(R.string.report_later), onClick = ::done)
        }
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                stringResource(R.string.report_crash_intro), color = DashColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            ScanToSend(url)
        }
    }
}
