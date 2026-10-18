package com.bp22intel.edgesentinel.ui.map

import android.graphics.Canvas
import android.graphics.ColorFilter
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.DefaultOverlayManager
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Unique user agent complying with the OpenStreetMap tile usage policy.
 */
const val EDGE_SENTINEL_USER_AGENT = "EdgeSentinel/2.0 (com.bp22intel.edgesentinel)"

/**
 * Standard OpenStreetMap tile source (min zoom 0, max zoom 19, 256px PNGs).
 * No API key required.
 */
val OPEN_STREET_MAP_TILE_SOURCE: OnlineTileSourceBase = XYTileSource(
    "OpenStreetMap",
    0,
    19,
    256,
    ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    "© OpenStreetMap contributors"
)

/**
 * OverlayManager that maintains the OpenStreetMap attribution overlay even if
 * map overlays are dynamically cleared and updated.
 */
class EdgeSentinelOverlayManager(
    tilesOverlay: TilesOverlay,
    val copyrightOverlay: CopyrightOverlay
) : DefaultOverlayManager(tilesOverlay) {
    override fun onDraw(c: Canvas, osmv: MapView) {
        super.onDraw(c, osmv)
        if (!overlays().contains(copyrightOverlay)) {
            copyrightOverlay.draw(c, osmv, false)
        }
    }

    override fun onDraw(c: Canvas, pProjection: Projection) {
        super.onDraw(c, pProjection)
        if (!overlays().contains(copyrightOverlay)) {
            copyrightOverlay.draw(c, pProjection)
        }
    }
}

/**
 * Configures the MapView with OpenStreetMap standard tiles inverted for dark theme,
 * sets the required user agent, and adds the copyright attribution overlay.
 */
fun MapView.applyEdgeSentinelMapStyle() {
    Configuration.getInstance().userAgentValue = EDGE_SENTINEL_USER_AGENT
    setTileSource(OPEN_STREET_MAP_TILE_SOURCE)
    overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)

    val copyright = CopyrightOverlay(context).apply {
        setCopyrightNotice("© OpenStreetMap contributors")
        setTextColor(0xAAFFFFFF.toInt())
        setTextSize(10)
        setAlignBottom(true)
        setAlignRight(true)
    }

    val currentTilesOverlay = overlayManager.tilesOverlay
    if (overlayManager !is EdgeSentinelOverlayManager) {
        val customManager = EdgeSentinelOverlayManager(currentTilesOverlay, copyright)
        for (overlay in overlayManager.overlays()) {
            customManager.add(overlay)
        }
        overlayManager = customManager
    }
    if (!overlays.contains(copyright)) {
        overlays.add(copyright)
    }
}
