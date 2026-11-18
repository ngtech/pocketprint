package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.Mxw01Dither
import com.gulshan.pocketprint.model.Mxw01Settings
import com.gulshan.pocketprint.mxw01.Mxw01Layout
import com.gulshan.pocketprint.mxw01.Mxw01Raster
import com.gulshan.pocketprint.mxw01.Mxw01RowPacker
import com.gulshan.pocketprint.mxw01.Mxw01Spool
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class Mxw01StripsTest {
    @Test fun disabledModeKeepsOriginalWidthRegardlessOfStripOptions() {
        val layout = Mxw01Layout(Mxw01Settings(stripCount = 8, stripOverlap = true))
        assertEquals(1, layout.strips)
        assertEquals(0, layout.overlap)
        assertEquals(368, layout.pageWidth)
        assertEquals(0, layout.stripStart(0))
    }

    @Test fun stripsWithoutOverlapCoverEveryColumnExactlyOnce() {
        val layout = Mxw01Layout(Mxw01Settings(splitIntoStrips = true, stripCount = 3, stripOverlap = false))
        assertEquals(1104, layout.pageWidth)
        assertEquals(listOf(0, 368, 736), (0 until layout.strips).map(layout::stripStart))
        val coverage = IntArray(layout.pageWidth)
        repeat(layout.strips) { strip ->
            for (x in 0 until layout.width) coverage[layout.stripStart(strip) + x]++
        }
        assertTrue(coverage.all { it == 1 })
    }

    @Test fun overlapRetainsFullPageCoverageAtBothEdges() {
        val layout = Mxw01Layout(Mxw01Settings(splitIntoStrips = true, stripCount = 3, stripOverlap = true))
        assertEquals(16, layout.overlap)
        assertEquals(1072, layout.pageWidth)
        assertEquals(listOf(0, 352, 704), (0 until layout.strips).map(layout::stripStart))
        assertEquals(layout.pageWidth, layout.stripStart(2) + layout.width)
        val coverage = IntArray(layout.pageWidth)
        repeat(layout.strips) { strip ->
            for (x in 0 until layout.width) coverage[layout.stripStart(strip) + x]++
        }
        assertTrue(coverage.all { it in 1..2 })
        assertEquals(32, coverage.count { it == 2 })
    }

    @Test fun nonByteAlignedStripsReassembleTheOriginalRowAndKeepMarginsWhite() {
        for (overlap in listOf(false, true)) {
            val layout = Mxw01Layout(Mxw01Settings(splitIntoStrips = true, scalePercent = 75, stripOverlap = overlap))
            assertEquals(276, layout.width)
            val pixels = IntArray(layout.pageWidth) { x -> if (x % 5 < 2) 0xff000000.toInt() else -1 }
            val packed = Mxw01RowPacker(layout.pageWidth, Mxw01Dither.THRESHOLD).row(pixels)
            val assembled = mutableListOf<Boolean>()
            repeat(layout.strips) { strip ->
                val row = ByteArray(48) { -1 }
                Mxw01Raster.copyStrip(packed, layout.stripStart(strip), layout.width, layout.left, row)
                assertTrue((0 until layout.left).none { ink(row, it) })
                assertTrue((layout.left + layout.width until 384).none { ink(row, it) })
                val skip = if (strip == 0) 0 else layout.overlap
                for (x in skip until layout.width) assembled += ink(row, layout.left + x)
            }
            assertEquals(pixels.map { it == 0xff000000.toInt() }, assembled)
        }
    }

    @Test fun sharedErrorDiffusionProducesIdenticalPixelsInEveryOverlap() {
        val layout = Mxw01Layout(Mxw01Settings(splitIntoStrips = true, scalePercent = 75))
        val packer = Mxw01RowPacker(layout.pageWidth, Mxw01Dither.FLOYD_STEINBERG)
        repeat(40) { y ->
            val pixels = IntArray(layout.pageWidth) { x ->
                val gray = (x * 17 + y * 11) % 256
                0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
            }
            val wide = packer.row(pixels)
            val rows = (0 until layout.strips).map { strip ->
                ByteArray(48).also { Mxw01Raster.copyStrip(wide, layout.stripStart(strip), layout.width, layout.left, it) }
            }
            for (strip in 1 until layout.strips) for (x in 0 until layout.overlap) {
                assertEquals(ink(rows[strip - 1], layout.left + layout.width - layout.overlap + x), ink(rows[strip], layout.left + x))
            }
        }
    }

    @Test fun rowCallbackReadsOnlyContentAndDoesNotConsumePadding() {
        var reads = 0
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            Mxw01Spool.begin(out)
            Mxw01Spool.appendRows(out, rows = 3, before = 2, after = 4) { row ->
                row.fill((++reads).toByte())
            }
            Mxw01Spool.end(out)
        }
        assertEquals(3, reads)
        DataInputStream(bytes.toByteArray().inputStream()).use { input ->
            input.readInt()
            assertEquals(90, input.readInt())
            val raster = ByteArray(90 * 48).also { input.readFully(it) }
            assertTrue(raster.take(2 * 48).all { it == 0.toByte() })
            for (row in 0 until 3) assertTrue(raster.copyOfRange((row + 2) * 48, (row + 3) * 48).all { it == (row + 1).toByte() })
            assertTrue(raster.drop(5 * 48).all { it == 0.toByte() })
        }
    }

    @Test fun stripSettingsRoundTripAndLegacySettingsStayDisabled() {
        val settings = Mxw01Settings(splitIntoStrips = true, stripCount = 5, stripOverlap = false)
        assertEquals(settings, Json.decodeFromString<Mxw01Settings>(Json.encodeToString(settings)))
        val legacy = Json.decodeFromString<Mxw01Settings>("""{"intensity":111,"marginDots":4}""")
        assertFalse(legacy.splitIntoStrips)
        assertEquals(111, legacy.intensity)
        assertEquals(1, Mxw01Layout(legacy).strips)
    }

    @Test fun importedStripCountsStayWithinTheBoundedRenderWidth() {
        val low = Mxw01Layout(Mxw01Settings(splitIntoStrips = true, stripCount = -50, marginDots = 0))
        val high = Mxw01Layout(Mxw01Settings(splitIntoStrips = true, stripCount = Int.MAX_VALUE, marginDots = 0, stripOverlap = false))
        assertEquals(2, low.strips)
        assertEquals(8, high.strips)
        assertEquals(3072, high.pageWidth)
    }

    private fun ink(row: ByteArray, x: Int): Boolean = row[x / 8].toInt() and (1 shl (x % 8)) != 0
}
