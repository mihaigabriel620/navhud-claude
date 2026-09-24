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
 * Each file is named by the grid cell and radius bucket it was asked for (so a
 * refetch of the same window replaces it) *and* by the exact circle it covers.
 * The exact circle is what a lookup is judged against, and what the caller
 * labels the [Area] with. Labelling a neighbour's body with the centre that was
 * asked for claimed coverage the data did not have: a window up to 1.5 km off
 * was treated as centred on the car, and the car ran off its edge before
 * anything thought to refetch.
 *
 * [dir] is set once, by the service, from the app's private files directory.
 * Until it is, every call here is a no-op -- the cache is an optimisation, and
 * a unit test or an early caller must not have to know about it.
 */
object AreaCache {

    /**
     * Grid pitch, degrees. About 1.1 km of latitude. Only the file key: two
     * fetches in the same cell at the same radius replace each other.
     */
    const val CELL_DEG = 0.01

    /** A week. Speed limits and camera sites do not move faster than that. */
    const val FRESH_MS = 604_800_000L

    /** Cap on cached files; the oldest go first. */
    const val MAX_FILES = 400

    /**
     * Cap on the whole cache. The route prefetch keeps ~100 km ahead of the
     * car, a few MB per window, so a 2000 km trip would otherwise leave a
     * gigabyte of Europe behind it on the head unit. Oldest first, so what
     * survives is what is ahead and the commute.
     */
    const val MAX_BYTES = 120L * 1024 * 1024

    /** [MAX_BYTES], lowered by tests that cannot write 120 MB. */
    @Volatile internal var maxBytes: Long = MAX_BYTES

    /** Rounding in the file name is 1e-5 degrees, about a metre. */
    private const val SLACK_M = 5.0

    /**
     * An old-style file only knows its cell and radius bucket: the centre can
     * be ~700 m from the cell's, the radius 500 m off. Trusted that much less.
     */
    private const val LEGACY_SHRINK_M = 1200.0

    /** Set by the service. Written on the main thread, read on the fetch one. */
    @Volatile var dir: File? = null

    /** A cached window: its body, and the circle it actually covers. */
    class Hit(val body: String, val lat: Double, val lon: Double, val radiusM: Double)

    private class Entry(val file: File, val lat: Double, val lon: Double, val radiusM: Double)

    private fun key(lat: Double, lon: Double, radiusM: Double): String =
        "a_${(lat / CELL_DEG).roundToLong()}_${(lon / CELL_DEG).roundToLong()}" +
            "_${(radiusM / 1000.0).roundToLong()}"

    private fun name(lat: Double, lon: Double, radiusM: Double): String =
        key(lat, lon, radiusM) + "_${(lat * 1e5).roundToLong()}_${(lon * 1e5).roundToLong()}" +
            "_${radiusM.roundToLong()}.json"

    fun put(lat: Double, lon: Double, radiusM: Double, body: String) {
        val d = dir ?: return
        // A full disk, a revoked directory, a killed process mid-write: none of
        // them are worth taking down a fetch that has already succeeded.
        runCatching {
            if (!d.exists()) d.mkdirs()
            val k = key(lat, lon, radiusM)
            d.listFiles()?.forEach {
                if (it.name == "$k.json" || it.name.startsWith(k + "_")) it.delete()
            }
            File(d, name(lat, lon, radiusM)).writeText(body)
            prune(d)
        }
    }

    private fun entry(f: File): Entry? {
        val p = f.name.removePrefix("a_").removeSuffix(".json").split('_')
        return when (p.size) {
            6 -> Entry(f, (p[3].toLongOrNull() ?: return null) / 1e5,
                          (p[4].toLongOrNull() ?: return null) / 1e5,
                          (p[5].toLongOrNull() ?: return null).toDouble())
            3 -> Entry(f, (p[0].toLongOrNull() ?: return null) * CELL_DEG,
                          (p[1].toLongOrNull() ?: return null) * CELL_DEG,
                          (p[2].toLongOrNull() ?: return null) * 1000.0 - LEGACY_SHRINK_M)
            else -> null
        }
    }

    private fun entries(maxAgeMs: Long, nowMs: Long): List<Entry> {
        val d = dir ?: return emptyList()
        if (!d.isDirectory) return emptyList()
        val files = d.listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            if (nowMs - f.lastModified() > maxAgeMs) null
            else entry(f)?.takeIf { it.radiusM > 0.0 }
        }
    }

    /** Equirectangular: at this scale the error is centimetres. */
    private fun dist(e: Entry, lat: Double, lon: Double): Double = hypot(
        (e.lon - lon) * 111320.0 * cos(Math.toRadians(lat)),
        (e.lat - lat) * 111320.0
    )

    private fun read(e: Entry): Hit? =
        runCatching { Hit(e.file.readText(), e.lat, e.lon, e.radiusM) }.getOrNull()

    /**
     * A cached window that contains the whole circle asked for, or null.
     *
     * Any radius bucket will do, as long as it covers the circle and is no
     * more than twice its size -- a bigger one would make
     * [AreaRoads.needsRefetch] ask again straight away. The smallest such is
     * read, because it is the cheapest to parse.
     *
     * @param maxAgeMs how stale is acceptable: [FRESH_MS] normally,
     *   [Long.MAX_VALUE] after a network failure.
     * @param nowMs wall clock, to compare against file modification times.
     */
    fun get(lat: Double, lon: Double, radiusM: Double, maxAgeMs: Long, nowMs: Long): Hit? =
        covering(lat, lon, radiusM, maxAgeMs, nowMs)?.let { read(it) }

    /** [get] without reading the body: is this window already on disk? */
    fun covers(lat: Double, lon: Double, radiusM: Double, maxAgeMs: Long, nowMs: Long): Boolean =
        covering(lat, lon, radiusM, maxAgeMs, nowMs) != null

    private fun covering(lat: Double, lon: Double, radiusM: Double, maxAgeMs: Long, nowMs: Long) =
        entries(maxAgeMs, nowMs)
            .filter { it.radiusM <= radiusM * 2 && dist(it, lat, lon) + radiusM <= it.radiusM + SLACK_M }
            .minByOrNull { it.radiusM }

    /**
     * Offline: any cached window with this point inside it, the one with the
     * most room around the point. Worse than [get] -- it may not reach as far
     * ahead as was asked -- and better than nothing, which is the alternative
     * with no signal. Labelled with its own circle, so the caller asks again
     * as soon as the car leaves it.
     */
    fun nearest(lat: Double, lon: Double, maxAgeMs: Long, nowMs: Long): Hit? =
        entries(maxAgeMs, nowMs)
            .filter { dist(it, lat, lon) < it.radiusM }
            .maxByOrNull { it.radiusM - dist(it, lat, lon) }
            ?.let { read(it) }

    /** Oldest first, down to [MAX_FILES] and [MAX_BYTES]; the newest always stays. */
    private fun prune(d: File) {
        val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
        var count = files.size
        var bytes = files.sumOf { it.length() }
        for (f in files.dropLast(1)) {
            if (count <= MAX_FILES && bytes <= maxBytes) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                count--
                bytes -= len
            }
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
