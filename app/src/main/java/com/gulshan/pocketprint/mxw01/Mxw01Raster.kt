package com.gulshan.pocketprint.mxw01

import com.gulshan.pocketprint.model.Mxw01Dither
import com.gulshan.pocketprint.model.Mxw01Settings

/** Fit to printable width, then apply a user-selected reduction; never crop. */
class Mxw01Layout(settings: Mxw01Settings) {
    private val config = settings.validated()
    val width = ((Mxw01Protocol.WIDTH - 2 * config.marginDots) * config.scalePercent / 100).coerceAtLeast(1)
    val left = (Mxw01Protocol.WIDTH - width) / 2

}

/** A row at a time; error diffusion persists across PDF rendering bands. */
class Mxw01Raster(private val layout: Mxw01Layout, private val mode: Mxw01Dither) {
    private var errors = FloatArray(layout.width + 2)
    private var following = FloatArray(layout.width + 2)

    fun row(argb: IntArray): ByteArray {
        require(argb.size == layout.width)
        val packed = ByteArray(Mxw01Protocol.ROW_BYTES)
        argb.forEachIndexed { x, pixel ->
            val alpha = (pixel ushr 24 and 255) / 255f
            val luminance = alpha * (
                .299f * (pixel ushr 16 and 255) + .587f * (pixel ushr 8 and 255) + .114f * (pixel and 255)
            ) + (1 - alpha) * 255
            val value = luminance + if (mode == Mxw01Dither.FLOYD_STEINBERG) errors[x + 1] else 0f
            val black = value < 128
            val headX = layout.left + x
            if (black) packed[headX / 8] = (packed[headX / 8].toInt() or (1 shl (headX % 8))).toByte()
            if (mode == Mxw01Dither.FLOYD_STEINBERG) {
                val error = value - if (black) 0f else 255f
                errors[x + 2] += error * 7 / 16
                following[x] += error * 3 / 16
                following[x + 1] += error * 5 / 16
                following[x + 2] += error / 16
            }
        }
        val old = errors
        errors = following
        following = old
        following.fill(0f)
        return packed
    }
}
