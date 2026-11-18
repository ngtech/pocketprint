package com.gulshan.pocketprint.mxw01

/** One original-page frame, overlaid before slicing; internal cuts are not framed. */
class Mxw01PageGuides(private val layout: Mxw01Layout, contentHeight: Int) {
    init {
        require(layout.hasGuides)
        require(contentHeight > 0 && contentHeight <= Int.MAX_VALUE - 2 * layout.guidePadding)
    }

    val height = contentHeight + 2 * layout.guidePadding
    val left = layout.guidePadding - 1
    val right = layout.guidePadding + layout.contentWidth
    val top = layout.guidePadding - 1
    val bottom = layout.guidePadding + contentHeight

    // Mark both edges of each repeated overlap. Each mark has the same source
    // coordinate on both neighbors, so the marks coincide when they are aligned.
    val ticks: List<Int> = (if (layout.overlap > 0) 1 until layout.strips else IntRange.EMPTY).flatMap { strip ->
        listOf(layout.stripStart(strip), layout.stripStart(strip - 1) + layout.width - 1)
    }.distinct().sorted()

    fun drawRow(packed: ByteArray, y: Int) {
        require(packed.size == layout.pageRowBytes && y in 0 until height)
        if (y in top..bottom) {
            black(packed, left)
            black(packed, right)
        }
        if (y == top || y == bottom) {
            for (x in left..right) black(packed, x)
        }
        // On a horizontal layout these are the original page's left/right edges.
        if (y in top - TICK_LENGTH_DOTS..top || y in bottom..bottom + TICK_LENGTH_DOTS) {
            for (x in ticks) black(packed, x)
        }
    }

    private fun black(packed: ByteArray, x: Int) {
        packed[x / 8] = (packed[x / 8].toInt() or (1 shl (x % 8))).toByte()
    }

    companion object {
        const val TICK_LENGTH_DOTS = 8
    }
}
