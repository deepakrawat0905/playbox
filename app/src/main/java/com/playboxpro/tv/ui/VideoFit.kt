package com.playboxpro.tv.ui

import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Fits every stream to the screen it is shown on.
 *
 * When the picture is close to the screen's own shape (most 16:9 channels,
 * SD streams flagged as widescreen, 1920x1088 encodes) it is stretched to
 * fill edge to edge - the difference is too small to see, and it removes
 * the thin black bars. A clearly different shape (4:3, 2.39:1 cinema) is
 * shown whole instead, so nothing is ever cut off or visibly distorted.
 */
object VideoFit {

    /** Shapes within this fraction of the screen's are filled. */
    private const val FILL_TOLERANCE = 0.2f

    fun attach(view: PlayerView, player: Player, enabled: () -> Boolean = { true }) {
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (enabled()) apply(view, videoSize)
            }
        })
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (enabled()) apply(view, player.videoSize)
        }
    }

    fun apply(view: PlayerView, size: VideoSize) {
        if (size.width <= 0 || size.height <= 0 || view.width <= 0 || view.height <= 0) return
        val videoAspect = size.width * size.pixelWidthHeightRatio / size.height
        val viewAspect = view.width.toFloat() / view.height
        val mode = if (kotlin.math.abs(videoAspect - viewAspect) / viewAspect <= FILL_TOLERANCE) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        if (view.resizeMode != mode) view.resizeMode = mode
    }
}
