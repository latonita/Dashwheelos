package com.openauto.dash

import android.content.Context
import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Records the path of the current ride for the map tile's trail ([RideTrack]). [LocationFeed] feeds it the
 * same GPS steps the trip computer counts, so it records whether or not the tile is shown or switched on.
 * A ride is the trip computer's trip ([DriveLog]): the track is wiped by the first step of the next one, so a
 * finished ride stays on the map until then.
 *
 * The track is a file in filesDir (`ride_trail.txt`, written at most every [SAVE_MS] while moving and when the
 * drive ends; it carries a ride over a restart the way [DriveLog] resumes its trip). Each logged drive also leaves
 * a reduced copy in `ride_trails/<startedAt>.txt`, the newest [DriveLogRules.MAX_ITEMS] of them, for a later
 * "show past ride".
 */
internal object RideTrail {
    private val _revision = MutableStateFlow(0)

    /** Counts changes of the track (a point kept, a ride wiped), for the map to redraw. */
    val revision: StateFlow<Int> = _revision

    private var track = RideTrack()
    private var dir: File? = null
    private var dirty = false
    /** The drive whose track is already in the archive. */
    private var archivedId = 0L
    private var savedAt = 0L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ride-trail").apply { isDaemon = true } }

    /** Loads the saved track, once. Main thread. */
    fun restore(context: Context) {
        if (dir != null) return
        val d = context.applicationContext.filesDir
        dir = d
        track = runCatching { RideTrack.decode(File(d, FILE).takeIf { it.isFile }?.readText()) }.getOrDefault(RideTrack())
        _revision.value++
    }

    /** The kept points, oldest first. */
    fun snapshot(): List<RidePoint> = track.points

    /** A GPS step [l] that counted towards trip [driveId] at [speedKmh]. Main thread. */
    fun onFix(driveId: Long, l: Location, speedKmh: Float) {
        val p = RideTrack.pointOf(l.latitude, l.longitude, speedKmh, System.currentTimeMillis(),
            if (l.hasAltitude()) l.altitude else null)
        // A new drive wipes the track: the finished one goes to the archive first (a user's trip reset
        // can reach here before [DriveLog] has logged the drive).
        if (track.driveId != driveId) archiveHeld()
        if (track.add(driveId, p)) {
            dirty = true
            _revision.value++
        }
        flush(force = false)
    }

    /** Writes the track when it changed: at most every [SAVE_MS] unless [force]. */
    fun flush(force: Boolean) {
        val d = dir ?: return
        val now = SystemClock.elapsedRealtime()
        if (!dirty || (!force && now - savedAt < SAVE_MS)) return
        savedAt = now
        dirty = false
        val points = track.points
        val id = track.driveId
        io.execute { runCatching { write(File(d, FILE), RideTrack(points, id).encode()) } }
    }

    /** The drive [startedAt] was logged: its track, if that is the one held, is kept in the archive. */
    fun archive(startedAt: Long) {
        if (RideTrack.isDriveOf(track, startedAt)) archiveHeld()
    }

    /** Puts the held track in the archive, unless it is already there or too short to be a logged drive. */
    private fun archiveHeld() {
        val d = dir ?: return
        if (!RideTrack.worthArchiving(track, archivedId)) return
        archivedId = track.driveId
        flush(force = true)
        val id = track.driveId
        val reduced = RideTrack(track.points, id).reduced(RideTrack.ARCHIVE_POINTS)
        io.execute {
            runCatching {
                val a = File(d, ARCHIVE)
                a.mkdirs()
                write(File(a, "$id.txt"), reduced.encode())
                val starts = a.listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".txt").toLongOrNull() }
                RideTrack.archiveVictims(starts, DriveLogRules.MAX_ITEMS).forEach { File(a, "$it.txt").delete() }
            }
        }
    }

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private const val FILE = "ride_trail.txt"
    private const val ARCHIVE = "ride_trails"
    private const val SAVE_MS = 30_000L
}
