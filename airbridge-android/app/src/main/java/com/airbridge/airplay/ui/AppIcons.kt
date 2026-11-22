package com.airbridge.airplay.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few icons AirBridge needs beyond material-icons-core, as 24dp vectors (Material Design
 * icon geometry, Apache 2.0). Kept local to avoid the very large material-icons-extended library.
 */
object AppIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (p in paths) b.addPath(pathData = addPathNodes(p), fill = SolidColor(Color.Black))
        return b.build()
    }

    val Speaker: ImageVector by lazy {
        icon(
            "Speaker",
            "M17,2H7C5.9,2 5,2.9 5,4v16c0,1.1 0.9,1.99 2,1.99L17,22c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,4c1.1,0 2,0.9 2,2s-0.9,2 -2,2c-1.11,0 -2,-0.9 -2,-2s0.89,-2 2,-2zM12,20c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,12c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
        )
    }

    val VolumeUp: ImageVector by lazy {
        icon(
            "VolumeUp",
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
        )
    }

    val Stop: ImageVector by lazy { icon("Stop", "M6,6h12v12H6z") }

    /** Concentric waves with a dot: "broadcasting" mark used in the hero card. */
    val Broadcast: ImageVector by lazy {
        icon(
            "Broadcast",
            "M12,11c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,13c0,-3.31 -2.69,-6 -6,-6s-6,2.69 -6,6c0,2.22 1.21,4.15 3,5.19l1,-1.74c-1.19,-0.7 -2,-1.97 -2,-3.45 0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,1.48 -0.81,2.75 -2,3.45l1,1.74c1.79,-1.04 3,-2.97 3,-5.19zM12,3C6.48,3 2,7.48 2,13c0,3.7 2.01,6.92 4.99,8.65l1,-1.73C5.61,19.53 4,16.96 4,13c0,-4.42 3.58,-8 8,-8s8,3.58 8,8c0,2.96 -1.61,5.53 -4,6.92l1,1.73c2.99,-1.73 5,-4.95 5,-8.65 0,-5.52 -4.48,-10 -10,-10z",
        )
    }

    val Tune: ImageVector by lazy {
        icon(
            "Tune",
            "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z",
        )
    }

    val BatteryAlert: ImageVector by lazy {
        icon(
            "BatteryAlert",
            "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4zM13,18h-2v-2h2v2zM13,14h-2V9h2v5z",
        )
    }
}
