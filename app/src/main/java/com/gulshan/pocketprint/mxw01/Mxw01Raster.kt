package com.gulshan.pocketprint.mxw01

import com.gulshan.pocketprint.model.Mxw01Dither
import com.gulshan.pocketprint.model.Mxw01Settings
import com.gulshan.pocketprint.model.Mxw01StripDirection

/** Fit each strip to printable width; the union of strips covers the whole page. */
class Mxw01Layout(settings: Mxw01Settings) {
    private val config = settings.validated()
    val width = ((Mxw01Protocol.WIDTH - 2 * config.marginDots) * config.scalePercent / 100).coerceAtLeast(1)
    val left = (Mxw01Protocol.WIDTH - width) / 2
    val strips = if (config.splitIntoStrips) config.stripCount else 1
    val overlap = if (strips > 1 && config.stripOverlap) minOf(OVERLAP_DOTS, width - 1) else 0
    val pageWidth = width + (strips - 1) * (width - overlap)
    val pageRowBytes = (pageWidth + 7) / 8
    // Rotate horizontal sections onto the narrow head: normalized left-to-right
    // is original top-to-bottom. Turn each printed piece clockwise to assemble.
    val rotatePage = strips > 1 && config.stripDirection == Mxw01StripDirection.HORIZONTAL
    val hasGuides = strips > 1 && config.stripGuides
    val guidePadding = if (hasGuides) GUIDE_PADDING_DOTS else 0
    val contentWidth = pageWidth - 2 * guidePadding

    fun stripStart(index: Int): Int {
        require(index in 0 until strips)
        return index * (width - overlap)
    }

    companion object {
        const val OVERLAP_DOTS = 16 // Approximately 2 mm at the MXW01's nominal 203 dpi.
        const val GUIDE_PADDING_DOTS = 12 // Keeps guides outside the original PDF content.
    }
}

/** One full-page row at a time; shared diffusion keeps overlapping strips identical. */
class Mxw01RowPacker(private val width: Int, private val mode: Mxw01Dither) {
    init { require(width > 0) }
    private var errors = FloatArray(width + 2)
    private var following = FloatArray(width + 2)

    fun row(argb: IntArray): ByteArray {
        require(argb.size == width)
        val packed = ByteArray((width + 7) / 8)
        argb.forEachIndexed { x, pixel ->
            val alpha = (pixel ushr 24 and 255) / 255f
            val luminance = alpha * (
                .299f * (pixel ushr 16 and 255) + .587f * (pixel ushr 8 and 255) + .114f * (pixel and 255)
            ) + (1 - alpha) * 255
            val value = luminance + if (mode == Mxw01Dither.FLOYD_STEINBERG) errors[x + 1] else 0f
            val black = value < 128
            if (black) packed[x / 8] = (packed[x / 8].toInt() or (1 shl (x % 8))).toByte()
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

/** Places one selected content row on the physical 384-dot head, including margins. */
class Mxw01Raster(private val layout: Mxw01Layout, mode: Mxw01Dither) {
    private val packer = Mxw01RowPacker(layout.width, mode)

    fun row(argb: IntArray): ByteArray = ByteArray(Mxw01Protocol.ROW_BYTES).also {
        copyStrip(packer.row(argb), 0, layout.width, layout.left, it)
    }

    companion object {
        /** Handles non-byte-aligned strip boundaries and margins without dropping pixels. */
        fun copyStrip(source: ByteArray, sourceLeft: Int, width: Int, headLeft: Int, target: ByteArray) {
            require(sourceLeft >= 0 && width > 0 && sourceLeft.toLong() + width <= source.size.toLong() * 8)
            require(headLeft >= 0 && headLeft + width <= Mxw01Protocol.WIDTH)
            require(target.size == Mxw01Protocol.ROW_BYTES)
            target.fill(0)
            for (x in 0 until width) {
                val sourceX = sourceLeft + x
                if (source[sourceX / 8].toInt() and (1 shl (sourceX % 8)) != 0) {
                    val headX = headLeft + x
                    target[headX / 8] = (target[headX / 8].toInt() or (1 shl (headX % 8))).toByte()
                }
            }
        }
    }
}
