package com.mihai.navhud.map

import android.content.Context
import android.util.Log
import com.mihai.navhud.R
import com.mihai.navhud.nav.Route
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/**
 * Keeps the next stretch of the route downloaded, so a dead spot or a border
 * without roaming does not blank the map.
 *
 * One MapLibre offline region per [OfflineCorridor] chunk: the next 80 km of
 * route and a kilometre either side, zoom 10-14 (the tile source stops at 14;
 * everything closer is drawn from those). A new chunk is made when the car is
 * halfway through the current one, and only the newest two are kept, so a
 * cross-continent drive holds a few tens of megabytes at any time. Region
 * tiles are exempt from the ambient cache's eviction, which is the point:
 * they are still there when the network is not.
 *
 * ## The style URL
 *
 * A region needs a style URL, and ours is a raw resource loaded with
 * fromJson. The download looks every resource up in the offline database
 * before touching the network, so the style JSON is put there under a URL
 * that cannot resolve ([STYLE_URL]) right before each region is created. The
 * download then finds it, reads the real tile source and glyph URLs out of it,
 * and fetches exactly what the map itself will ask for.
 *
 * Every MapLibre call is guarded: a failure here costs a prefetch, never the
 * map. Main thread only; OfflineManager delivers its callbacks there too.
 */
class OfflineRoutes(context: Context) {

    companion object {
        private const val TAG = "OfflineRoutes"

        /** Never fetched: the `.invalid` TLD is reserved. See the class note. */
        const val STYLE_URL = "https://navhud.invalid/style/e60.json"

        /** Marks the regions this class owns, followed by the creation time. */
        const val META_PREFIX = "navhud-route-chunk:"

        const val MIN_ZOOM = 10.0
        const val MAX_ZOOM = 14.0

        /** Older chunks kept besides the new one: the one the car is still in. */
        const val KEEP_OLDER = 1

        /** Not more often than this, so a burst of reroutes is one download. */
        const val MIN_INTERVAL_MS = 20_000L
    }

    private val app = context.applicationContext
    private val manager: OfflineManager? =
        runCatching { OfflineManager.getInstance(app) }.getOrNull()

    private var route: Route? = null
    /** Start of this route's current chunk; null = none made yet. */
    private var chunkStart: Double? = null
    private var busy = false
    private var lastAttemptMs = Long.MIN_VALUE / 2

    /** From the UI tick, with the route being driven and the car's distance along it. */
    fun update(current: Route?, alongM: Double, nowMs: Long) {
        if (current !== route) {
            // Arrived or cancelled: the chunk still downloading is for a drive
            // that is over. Stopped, not deleted -- what it has stays usable.
            if (current == null) stopDownloads()
            route = current; chunkStart = null
        }
        val r = current ?: return
        val m = manager ?: return
        // A callback that never came back must not stop prefetching for good.
        if (nowMs - lastAttemptMs < (if (busy) 120_000L else MIN_INTERVAL_MS)) return
        if (r.pts.size < 2) return
        val start = OfflineCorridor.nextChunkStart(chunkStart, alongM, r.cum.last()) ?: return
        val lines = OfflineCorridor.corridor(r.pts, r.cum, start, start + OfflineCorridor.CHUNK_M)
        if (lines.isEmpty()) return
        lastAttemptMs = nowMs
        busy = true
        val previous = chunkStart
        chunkStart = start
        val failed = { what: String ->
            Log.w(TAG, "offline chunk not created: $what")
            busy = false
            // Try this stretch again after MIN_INTERVAL_MS.
            if (route === r) chunkStart = previous
        }
        runCatching {
            m.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    runCatching {
                        dropOld(offlineRegions?.toList().orEmpty())
                        create(m, lines, failed)
                    }.onFailure { failed(it.toString()) }
                }
                override fun onError(error: String) {
                    // Cannot see the old ones; still worth fetching the new.
                    runCatching { create(m, lines, failed) }.onFailure { failed(it.toString()) }
                }
            })
        }.onFailure { failed(it.toString()) }
    }

    private fun stopDownloads() {
        val m = manager ?: return
        runCatching {
            m.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    offlineRegions.orEmpty().filter { createdAt(it) != null }.forEach {
                        runCatching { it.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                    }
                }
                override fun onError(error: String) {
                    Log.w(TAG, "could not stop offline chunks: $error")
                }
            })
        }
    }

    /** Delete all our regions but the newest [KEEP_OLDER]. */
    private fun dropOld(regions: List<OfflineRegion>) {
        val ours = regions.mapNotNull { r -> createdAt(r)?.let { it to r } }
            .sortedByDescending { it.first }
        for ((_, region) in ours.drop(KEEP_OLDER)) {
            runCatching {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() {}
                    override fun onError(error: String) {
                        Log.w(TAG, "could not delete offline chunk ${region.id}: $error")
                    }
                })
            }
        }
    }

    private fun createdAt(r: OfflineRegion): Long? = runCatching {
        String(r.metadata, Charsets.UTF_8).takeIf { it.startsWith(META_PREFIX) }
            ?.removePrefix(META_PREFIX)?.toLongOrNull()
    }.getOrNull()

    private fun create(m: OfflineManager, lines: List<List<DoubleArray>>, failed: (String) -> Unit) {
        val style = app.resources.openRawResource(R.raw.style_e60).use { it.readBytes() }
        // Ambient-cache insert, on the same database thread as the create
        // below, so it is there by the time the download asks for it.
        m.putResourceWithUrl(STYLE_URL, style, System.currentTimeMillis() / 1000, 0L, null, false)
        val geometry = MultiLineString.fromLngLats(lines.map { line ->
            line.map { Point.fromLngLat(it[1], it[0]) }
        })
        val definition = OfflineGeometryRegionDefinition(STYLE_URL, geometry,
            MIN_ZOOM, MAX_ZOOM, app.resources.displayMetrics.density, false)
        val meta = (META_PREFIX + System.currentTimeMillis()).toByteArray(Charsets.UTF_8)
        m.createOfflineRegion(definition, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                busy = false
                // The route ended while this was being created.
                if (route == null) return
                runCatching {
                    offlineRegion.setObserver(Observer(offlineRegion))
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }.onFailure { Log.w(TAG, "could not start offline chunk", it) }
            }
            override fun onError(error: String) = failed(error)
        })
    }

    /**
     * Stops the download once it is complete. Errors are only logged: MapLibre
     * retries on its own when the network comes back, and until then the map
     * draws from whatever is already stored.
     */
    private class Observer(private val region: OfflineRegion) : OfflineRegion.OfflineRegionObserver {
        private var errors = 0
        override fun onStatusChanged(status: OfflineRegionStatus) {
            if (status.isComplete) {
                runCatching { region.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                Log.i(TAG, "offline chunk ${region.id}: ${status.completedTileCount} tiles, " +
                    "${status.completedResourceSize / 1024} kB")
            }
        }
        override fun onError(error: OfflineRegionError) {
            if (errors++ < 3) Log.w(TAG, "offline chunk ${region.id}: ${error.reason} ${error.message}")
        }
        override fun mapboxTileCountLimitExceeded(limit: Long) {}
    }
}
