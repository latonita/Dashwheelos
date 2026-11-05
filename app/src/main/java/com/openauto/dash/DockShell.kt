package com.openauto.dash

import android.content.Context
import android.util.Log
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The privileged shell the docking system talks to the window manager with:
 * Magisk root when granted, else Shizuku once the driver allowed Dashwheel in
 * it ([ShizukuShell]), else the head unit's internal ADB socket. One command
 * at a time, bounded so a stuck prompt or a hung adbd cannot freeze the
 * dashboard. Also the two window-manager commands built on it (resize, swipe).
 * The Play edition has neither ([Edition.play]): every command fails at once,
 * without a process or a socket.
 */
object DockShell {

    private const val TAG = "DockShell"

    /** A connection to the unit's adbd ([AdbInstaller.openShell]); GitHub edition only. */
    interface AdbShell : Closeable {
        /** Runs [cmd] and returns all it printed (both streams). */
        fun run(cmd: String): String
    }

    private val io = Mutex()

    private var adb: AdbShell? = null

    /** Drags the window by its centre onto the target centre (SystemUI handles PiP drags itself). */
    suspend fun swipeTo(context: Context, from: ScreenRect, to: ScreenRect): String {
        val x1 = (from.left + from.right) / 2; val y1 = (from.top + from.bottom) / 2
        val x2 = (to.left + to.right) / 2; val y2 = (to.top + to.bottom) / 2
        val cmd = "input swipe $x1 $y1 $x2 $y2 600"
        val out = shell(context, cmd)
        if (looksLikeError(out)) error(out.trim().lines().first())
        Log.d(TAG, "swiped window: $cmd")
        return "swipe ($x1,$y1)->($x2,$y2): ok"
    }

    /**
     * Moves and sizes [win] to [rect] (screen pixels): onto its tile, or out of
     * the way into the bottom-right corner when the tile left the screen.
     */
    suspend fun resize(context: Context, win: FloatingWindow, rect: ScreenRect): String {
        // `am stack resize` / `am task resize` read LEFT TOP RIGHT BOTTOM as four
        // separate arguments (the help text's "L,T,R,B" is wrong: a comma-joined
        // value fails with NumberFormatException, confirmed on the head unit).
        val bounds = "${rect.left} ${rect.top} ${rect.right} ${rect.bottom}"
        val attempts = if (win.mode == "pinned") {
            // Android 10/11 accept both; the animated form is nicer when present. Android 12 and
            // later have neither: the task's own resize is the one left to try there.
            listOfNotNull(
                "am stack resize-animated ${win.stackId} $bounds", "am stack resize ${win.stackId} $bounds",
                win.taskId?.let { "am task resize $it $bounds" }
            )
        } else {
            listOfNotNull(
                win.taskId?.let { "am task resize $it $bounds" },
                "am stack resize ${win.stackId} $bounds"
            )
        }
        var last = ""
        for (cmd in attempts) {
            last = shellOrRefusal(context, cmd)
            if (!looksLikeError(last)) {
                Log.d(TAG, "${win.mode} ${win.packageName} -> $bounds via `$cmd`")
                return "${cmd.substringBefore(" $bounds")}: ok"
            }
            Log.w(TAG, "`$cmd` failed: ${last.trim()}")
        }
        error(last.trim().lines().firstOrNull().orEmpty().ifBlank { "resize refused" })
    }

    /**
     * Moves a floating task into [stackId], a fullscreen stack, at its bottom
     * (or top): the app then shows full screen instead of in a window, and keeps
     * running (a navigation or a song goes on), unlike `am stack remove`.
     */
    suspend fun moveTask(context: Context, win: FloatingWindow, stackId: Int, toTop: Boolean = false): String {
        val taskId = win.taskId ?: error("no task id for ${win.packageName}")
        val cmd = "am stack move-task $taskId $stackId $toTop"
        val out = shell(context, cmd)
        if (looksLikeError(out)) error(out.trim().lines().firstOrNull().orEmpty().ifBlank { "move refused" })
        Log.d(TAG, "${win.mode} ${win.packageName} task $taskId -> stack $stackId via `$cmd`")
        return "$cmd: ok"
    }

    /**
     * Moves the window's whole stack onto [displayId]: onto the launcher's
     * hidden display to park it out of sight, back onto the screen (display 0)
     * to dock it again. The window keeps its size, its place and its state;
     * only the display it is composed on changes. Needs INTERNAL_SYSTEM_WINDOW,
     * which root has and the ADB shell is granted.
     */
    suspend fun moveToDisplay(context: Context, win: FloatingWindow, displayId: Int): String {
        val cmd = "am display move-stack ${win.stackId} $displayId"
        val out = shellOrRefusal(context, cmd)
        if (out.contains(ALREADY_ON_DISPLAY)) {
            // Refused because the stack is on that display already (a listing a
            // moment old said otherwise): what was asked for is the case.
            Log.d(TAG, "${win.mode} ${win.packageName} already on display $displayId")
            return "$cmd: already there"
        }
        if (looksLikeError(out)) error(out.trim().lines().firstOrNull().orEmpty().ifBlank { "move refused" })
        Log.d(TAG, "${win.mode} ${win.packageName} -> display $displayId via `$cmd`")
        return "$cmd: ok"
    }

    /** What `am display move-stack` answers for a stack already on the display asked for (an IllegalArgumentException). */
    private const val ALREADY_ON_DISPLAY = "to its current displayId"

    private fun looksLikeError(out: String): Boolean =
        out.contains("Error", ignoreCase = true) || out.contains("Exception") || out.contains("Unknown")

    /**
     * A command the root shell ran and that ended with an error code, with all
     * it printed. The ADB shell has no such code: there a refusal is only read
     * in the output ([looksLikeError]).
     */
    class Refused(val exit: Int, val output: String, why: String) : IllegalStateException("su exit $exit: $why".trim())

    /**
     * [shell], a refusal by the root shell given back as its output instead of
     * thrown: for the callers that read what a command answered (the next
     * command to try, "already there"), which the root shell's error code used
     * to cut short while the ADB shell let them through.
     */
    private suspend fun shellOrRefusal(context: Context, cmd: String): String = try {
        shell(context, cmd)
    } catch (e: Refused) {
        if (looksLikeError(e.output)) e.output else "Error (exit ${e.exit}): ${e.output}"
    }

    /** How shell commands reach the system: root via Magisk, Shizuku's service, or the ADB socket. */
    private enum class Backend { SU, SHIZUKU, ADB }

    private var backend: Backend? = null

    suspend fun shell(context: Context, cmd: String): String = withContext(Dispatchers.IO) {
        io.withLock {
            // Any other command may move, raise or close a window.
            listing = null
            execute(context, cmd)
        }
    }

    /** How long an `am stack list` result stands in for a fresh one when nothing was moved meanwhile. */
    private const val LISTING_FRESH_MS = 500L

    /** Only from inside [io]'s lock. */
    private var listing: String? = null
    private var listingAt = 0L

    /**
     * `am stack list`. During a page swipe every tile, the pager and the
     * disposed page each ask for the listing within a few hundred milliseconds;
     * one round trip serves them all, as long as no command in between could
     * have changed what the system would answer.
     */
    suspend fun listStacks(context: Context): String = withContext(Dispatchers.IO) {
        io.withLock {
            val now = android.os.SystemClock.elapsedRealtime()
            listing?.takeIf { now - listingAt <= LISTING_FRESH_MS }
                ?: execute(context, "am stack list").also { listing = it; listingAt = now }
        }
    }

    /** The windows changed behind the shell's back (a task was raised): the next listing must be fresh. */
    suspend fun forgetListing() = io.withLock { listing = null }

    /** Only from inside [io]'s lock. */
    private fun execute(context: Context, cmd: String): String {
        // No root, no ADB in the Play edition: nothing is tried.
        if (Edition.play) throw IllegalStateException("no shell in the Play edition")
        // su can run out of time while the unit boots: ADB was taken then, for the
        // life of the process, on a unit whose ADB may be off. Root found since is used.
        if (backend != null && backend != Backend.SU && PrivilegedShell.access.value.root) {
            backend = Backend.SU
            Log.i(TAG, "shell backend: SU (root answered after all)")
        }
        // Shizuku allowed since ADB was taken, or stopped since it was taken: choose again.
        if (backend == Backend.ADB && ShizukuShell.ready || backend == Backend.SHIZUKU && !ShizukuShell.ready) {
            backend = null
        }
        val chosen = backend ?: when {
            SystemInstaller.isRootAvailable() -> Backend.SU
            ShizukuShell.ready -> Backend.SHIZUKU
            else -> Backend.ADB
        }.also { backend = it; Log.i(TAG, "shell backend: $it") }
        return when (chosen) {
            Backend.SU -> suShell(cmd)
            Backend.SHIZUKU -> shizukuShell(cmd)
            Backend.ADB -> adbShell(context, cmd)
        }
    }

    /** The root shell kept open between commands; null until the first one, or after one failed. */
    private var su: RootShell.Session? = null

    /**
     * [cmd] through the root shell kept open ([RootShell.Session]), bounded so
     * a stuck command can't pin the poller. Only from inside [io]'s lock.
     */
    private fun suShell(cmd: String): String {
        val session = su?.takeIf { it.alive } ?: RootShell.Session().also { su = it }
        val res = try {
            session.run(cmd, SU_TIMEOUT_S)
        } catch (e: IllegalStateException) {
            // The session closed itself; the next command opens a new shell.
            su = null
            throw e
        }
        if (res.exit != 0) {
            // "Permission denied", "not found"...: a failure, whatever it printed.
            val why = (res.err.ifBlank { res.out }).trim().lines().firstOrNull().orEmpty()
            throw Refused(res.exit, res.all, why)
        }
        return res.all
    }

    private const val SU_TIMEOUT_S = 8L

    /**
     * [cmd] through Shizuku's shell service ([ShizukuShell.run]), which starts
     * on the first command. Read like the root shell: an error code is a
     * refusal, whatever it printed. Only from inside [io]'s lock.
     */
    private fun shizukuShell(cmd: String): String {
        val res = ShizukuShell.run(cmd, SU_TIMEOUT_S)
        if (res.exit != 0) {
            val why = res.all.trim().lines().firstOrNull().orEmpty()
            throw Refused(res.exit, res.all, why)
        }
        return res.all
    }

    private fun adbShell(context: Context, cmd: String): String {
        // The port adbd really answers on: a unit that announces none (service.adb.tcp.port empty) may listen on 5555.
        val conn = adb ?: AdbInstaller.openShell(context, AdbInstaller.listeningPort() ?: AdbInstaller.announcedPort(), ADB_TIMEOUT_MS)
            .also { adb = it }
        try {
            return conn.run(cmd)
        } catch (e: Exception) {
            closeConnection()
            throw e
        }
    }

    /** A hung adbd must not hold the shell lock forever. */
    private const val ADB_TIMEOUT_MS = 5_000

    /** Only from inside [shell]'s lock. */
    private fun closeConnection() {
        runCatching { adb?.close() }
        adb = null
    }

    /**
     * Drops the cached ADB connection. The root shell stays open: an idle `su`
     * costs nothing, while each new one makes Magisk show its toast, and this
     * runs every time another app goes full screen. Safe from any coroutine.
     */
    suspend fun release() = io.withLock { closeConnection() }
}
