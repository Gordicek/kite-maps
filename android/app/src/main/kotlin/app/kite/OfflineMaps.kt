package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kite.core.Box
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** MapLibre uses its ordinary tile database for these retained, explicitly downloaded areas. */
internal object OfflineMaps {
    data class Area(
        val id: Long, val name: String, val bounds: LatLngBounds, val maxZoom: Double,
        val active: Boolean = false, val complete: Boolean = false,
        val bytes: Long = 0, val progress: Float? = null, val resources: Long = 0,
        val error: String? = null, val regionId: String? = null,
    )

    val areas = mutableStateMapOf<Long, Area>()
    var message by mutableStateOf<String?>(null)
        private set
    var pendingName by mutableStateOf<String?>(null)
        private set
    val creating: Boolean get() = pendingName != null
    private var loaded = false
    private val regions = mutableMapOf<Long, OfflineRegion>()
    private val manager get() = OfflineManager.getInstance(KiteApp.app)

    fun load() {
        if (loaded) return
        loaded = true
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(list: Array<OfflineRegion>?) { list.orEmpty().forEach(::register) }
            override fun onError(error: String) { loaded = false; message = error }
        })
    }

    private fun register(region: OfflineRegion) {
        val def = region.definition as? OfflineTilePyramidRegionDefinition ?: return
        if (def.styleURL != KiteMap.STYLE_URL) return
        val bounds = def.bounds ?: return
        val id = region.id
        val metadata = region.metadata.toString(Charsets.UTF_8)
        val idAndName = metadata.split('\n', limit = 2)
        val regionId = idAndName.first().takeIf { idAndName.size == 2 }?.ifBlank { null }
        val name = idAndName.last().trim().ifBlank { "Offline area" }
        regions[id] = region
        areas[id] = Area(id, name, bounds, def.maxZoom, regionId = regionId)
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) { update(region, status) }
            override fun onError(error: OfflineRegionError) {
                val area = areas[id] ?: return
                areas[id] = area.copy(error = error.message)
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) { stop(id); message = "Offline tile limit reached." }
        })
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) { status?.let { update(region, it) } }
            override fun onError(error: String?) { message = error }
        })
    }

    private fun update(region: OfflineRegion, status: OfflineRegionStatus) {
        val current = areas[region.id] ?: return
        val progress = if (status.isRequiredResourceCountPrecise && status.requiredResourceCount > 0) {
            (status.completedResourceCount.toFloat() / status.requiredResourceCount).coerceIn(0f, 1f)
        } else null
        areas[region.id] = current.copy(complete = status.isComplete, bytes = status.completedResourceSize,
            progress = progress, resources = status.completedResourceCount,
            error = if (status.completedResourceCount > current.resources || status.isComplete) null else current.error)
        if (status.isComplete && current.active) stop(region.id)
    }

    fun download(region: OfflineRegions.Region) = download(region.name, region.box, region.maxZoom, region.id)

    fun downloadViewport(name: String, box: Box) =
        download(name, box, OfflineRegions.VIEWPORT_ZOOM, null)

    private fun download(name: String, box: Box, zoom: Int, regionId: String?) {
        if (creating || areas.values.any { it.active || regionId != null && it.regionId == regionId }) return
        // The SDK expects an ordinary non-wrapping geographic rectangle.
        if (box.w >= box.e || box.s >= box.n || box.s !in -85.0..85.0 || box.n !in -85.0..85.0) {
            message = "Choose a map area away from the poles."
            return
        }
        pendingName = name
        message = null
        val bounds = LatLngBounds.from(box.n, box.e, box.s, box.w)
        val definition = OfflineTilePyramidRegionDefinition(KiteMap.STYLE_URL, bounds, 0.0, zoom.toDouble(),
            KiteApp.app.resources.displayMetrics.density, false)
        manager.createOfflineRegion(definition, (regionId.orEmpty() + "\n" + name.trim().ifBlank { "Offline area" }.take(70)).toByteArray(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    register(region)
                    resume(region.id)
                    pendingName = null
                }
                override fun onError(error: String) { pendingName = null; message = error }
            })
    }

    fun resume(id: Long) {
        if (areas.values.any { it.active }) return
        val area = areas[id]?.takeUnless { it.complete } ?: return
        val region = regions[id] ?: return
        message = null
        areas[id] = area.copy(active = true, error = null)
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    fun stop(id: Long) {
        val area = areas[id] ?: return
        areas[id] = area.copy(active = false)
        regions[id]?.setDownloadState(OfflineRegion.STATE_INACTIVE)
    }

    fun delete(id: Long) {
        stop(id)
        regions[id]?.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { regions.remove(id); areas.remove(id) }
            override fun onError(error: String) { message = error }
        })
    }
}
