package com.mihai.navhud.nav

import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * The last Overpass responses, on disk, so the app still knows the roads when
 * the phone has no signal.
 *
 * Everything free drive shows away from a route -- the speed limit, the road
 * name, cameras, level crossings, humps -- comes out of a single Overpass
 * query, and without an answer to it the screen goes blank. That is precisely
 * the wrong moment to go quiet: the places with no mobile coverage are the
 * rural roads and the tunnels and the border valleys, which are also the
 * places where you have never driven before.
 *
 * Raw JSON is cached rather than parsed [Area]s. It is what came off the wire,
 * so nothing can be lost in a round trip, and a cached body is fed through
 * exactly the same parser as a fresh one -- there is no second code path that
 * only runs offline and therefore only breaks offline.
 *
 * The grid is the whole trick. Keying on the exact query centre would give a
 * hit rate of nearly zero, because the centre is computed from the car's
 * position and heading and is never twice the same. Rounding to a cell means
 * a road driven last week is found again today, and the nearest-cell fallback
 * means it is found even when the cell boundary happens to fall between the
 * two drives.
 *
 * [dir] is set once, by the service, from the app's private files directory.
 * Until it is, every call here is a no-op -- the cache is an optimisation, and
 * a unit test or an early caller must not have to know about it.
 */
object AreaCache {

    /**
     * Grid pitch, degrees. About 1.1 km of latitude.
     *
     * Comfortably finer than the smallest fetch radius, so a hit is always
     * genuinely local rather than a window from the next town over, and coarse
     * enough that a commute settles onto a handful of repeated cells.
     */
    const val CELL_DEG = 0.01

    /** A week. Speed limits and camera sites do not move faster than that. */
    const val FRESH_MS = 604_800_000L

    /**
     * How far off a neighbouring cell may be and still be worth reading.
     *
     * Roughly a cell and a half, so the drive that ran along a cell boundary
     * last time still gets an answer. Beyond it the cached window would not
     * contain the road under the car and stale-but-wrong is worse than empty.
     */
    const val NEAR_M = 1500.0

    /**
     * Cap on cached files. Around 30 MB on a big-city grid.
     *
     * Enough for a country's worth of the roads you actually drive; the
     * oldest go first, which over time keeps the commute and drops the
     * holiday.
     */
    const val MAX_FILES = 400

    /** Set by the service. Written on the main thread, read on the fetch one. */
    @Volatile var dir: File? = null

    private fun key(lat: Double, lon: Double, radiusM: Double): String =
        "a_${(lat / CELL_DEG).roundToLong()}_${(lon / CELL_DEG).roundToLong()}" +
            "_${(radiusM / 1000.0).roundToLong()}.json"

    fun put(lat: Double, lon: Double, radiusM: Double, body: String) {
        val d = dir ?: return
        // A full disk, a revoked directory, a killed process mid-write: none of
        // them are worth taking down a fetch that has already succeeded.
        runCatching {
            if (!d.exists()) d.mkdirs()
            File(d, key(lat, lon, radiusM)).writeText(body)
            prune(d)
        }
    }

    /**
     * The best cached body for this window, or null.
     *
     * @param maxAgeMs how stale is acceptable. [FRESH_MS] on the normal path;
     *   callers falling back after a network failure pass [Long.MAX_VALUE],
     *   because week-old roads beat no roads at all.
     * @param nowMs wall clock, to compare against file modification times.
     */
    fun get(lat: Double, lon: Double, radiusM: Double, maxAgeMs: Long, nowMs: Long): String? {
        val d = dir ?: return null
        if (!d.isDirectory) return null

        val exact = File(d, key(lat, lon, radiusM))
        if (exact.isFile && nowMs - exact.lastModified() <= maxAgeMs) {
            return runCatching { exact.readText() }.getOrNull()
        }

        // No hit on this cell. A neighbour's window is a circle kilometres
        // across centred a few hundred metres away, so it still covers the car
        // -- and a drive along a cell boundary would otherwise miss every time.
        // Only the same radius bucket: a window fetched at 1.2 km does not
        // contain what a 6 km one was asked for.
        val bucket = (radiusM / 1000.0).roundToLong()
        val files = d.listFiles() ?: return null

        var best: File? = null
        var bestM = NEAR_M
        for (f in files) {
            val parts = f.name.removePrefix("a_").removeSuffix(".json").split('_')
            if (parts.size != 3) continue
            if (parts[2].toLongOrNull() != bucket) continue
            if (nowMs - f.lastModified() > maxAgeMs) continue
            val cellLat = (parts[0].toLongOrNull() ?: continue) * CELL_DEG
            val cellLon = (parts[1].toLongOrNull() ?: continue) * CELL_DEG
            // Equirectangular: at this scale the error is centimetres, and it
            // costs one cosine rather than a haversine per file.
            val m = hypot(
                (cellLon - lon) * 111320.0 * cos(Math.toRadians(lat)),
                (cellLat - lat) * 111320.0
            )
            if (m < bestM) {
                bestM = m
                best = f
            }
        }

        return best?.let { runCatching { it.readText() }.getOrNull() }
    }

    /** Oldest first, down to [MAX_FILES]. */
    private fun prune(d: File) {
        val files = d.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        for (f in files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES)) {
            runCatching { f.delete() }
        }
    }

    fun clear() {
        val d = dir ?: return
        runCatching { d.listFiles()?.forEach { it.delete() } }
    }

    fun sizeBytes(): Long {
        val d = dir ?: return 0L
        return runCatching { d.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
    }

    fun fileCount(): Int =
        runCatching { dir?.listFiles()?.size ?: 0 }.getOrDefault(0)
}
