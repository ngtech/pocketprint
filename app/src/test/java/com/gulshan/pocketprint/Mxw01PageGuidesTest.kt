package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.Mxw01Settings
import com.gulshan.pocketprint.model.Mxw01StripDirection
import com.gulshan.pocketprint.mxw01.Mxw01Layout
import com.gulshan.pocketprint.mxw01.Mxw01PageGuides
import com.gulshan.pocketprint.mxw01.Mxw01Raster
import org.junit.Assert.*
import org.junit.Test

class Mxw01PageGuidesTest {
    private val settings = Mxw01Settings(splitIntoStrips = true, marginDots = 0)

    @Test fun rememberedDirectionAndGuidesDoNotChangeWholePageLayout() {
        val layout = Mxw01Layout(settings.copy(splitIntoStrips = false, stripDirection = Mxw01StripDirection.HORIZONTAL))
        assertFalse(layout.rotatePage)
        assertFalse(layout.hasGuides)
        assertEquals(0, layout.guidePadding)
        assertEquals(384, layout.contentWidth)
    }

    @Test fun onlyHorizontalStripModeRotatesTheSourcePage() {
        assertFalse(Mxw01Layout(settings).rotatePage)
        assertTrue(Mxw01Layout(settings.copy(stripDirection = Mxw01StripDirection.HORIZONTAL)).rotatePage)
        val borderless = Mxw01Layout(settings.copy(stripGuides = false))
        assertEquals(borderless.pageWidth, borderless.contentWidth)
    }

    @Test fun frameBelongsOnlyToOriginalPageEdgesAndLeavesInternalCutsUnframed() {
        val layout = Mxw01Layout(settings)
        val guides = Mxw01PageGuides(layout, 300)
        val middleRow = ByteArray(layout.pageRowBytes)
        guides.drawRow(middleRow, 150)
        assertEquals(listOf(11, 1108), (0 until layout.pageWidth).filter { ink(middleRow, it) })
        val strips = (0 until 3).map { strip ->
            ByteArray(48).also { Mxw01Raster.copyStrip(middleRow, layout.stripStart(strip), 384, 0, it) }
        }
        assertTrue(ink(strips[0], 11))
        assertTrue(strips[1].all { it == 0.toByte() })
        assertTrue(ink(strips[2], 372))
        assertFalse(ink(strips[0], 383))
        assertFalse(ink(strips[2], 0))
        for (y in listOf(11, 312)) {
            val row = ByteArray(layout.pageRowBytes)
            guides.drawRow(row, y)
            assertEquals((11..1108).toList(), (0 until layout.pageWidth).filter { ink(row, it) })
        }
    }

    @Test fun frameAndTicksNeverOverwriteAnyOriginalContentPixel() {
        for (direction in Mxw01StripDirection.entries) {
            val layout = Mxw01Layout(settings.copy(stripDirection = direction))
            val guides = Mxw01PageGuides(layout, 41)
            for (y in 12 until 53) {
                val before = ByteArray(layout.pageRowBytes) { index -> (index * 31 + y).toByte() }
                val after = before.copyOf()
                guides.drawRow(after, y)
                for (x in 12 until 1108) assertEquals(ink(before, x), ink(after, x))
            }
        }
    }

    @Test fun bothEdgesOfEveryOverlapHaveMatchingTicksOnBothNeighbors() {
        val layout = Mxw01Layout(settings)
        val guides = Mxw01PageGuides(layout, 300)
        assertEquals(listOf(368, 383, 736, 751), guides.ticks)
        for (y in listOf(3, 319)) {
            val wide = ByteArray(layout.pageRowBytes)
            guides.drawRow(wide, y)
            assertEquals(guides.ticks, (0 until layout.pageWidth).filter { ink(wide, it) })
            for (strip in 1 until 3) {
                val previous = ByteArray(48)
                val next = ByteArray(48)
                Mxw01Raster.copyStrip(wide, layout.stripStart(strip - 1), 384, 0, previous)
                Mxw01Raster.copyStrip(wide, layout.stripStart(strip), 384, 0, next)
                assertTrue(ink(previous, 368) && ink(previous, 383))
                assertTrue(ink(next, 0) && ink(next, 15))
            }
        }
    }

    @Test fun noOverlapNeedsNoOverlapTicks() {
        val layout = Mxw01Layout(settings.copy(stripOverlap = false))
        val guides = Mxw01PageGuides(layout, 300)
        assertTrue(guides.ticks.isEmpty())
        val row = ByteArray(layout.pageRowBytes)
        guides.drawRow(row, 3)
        assertTrue(row.all { it == 0.toByte() })
    }

    @Test fun extremeSettingsStillLeaveRoomForContentAndAllGuides() {
        val layout = Mxw01Layout(settings.copy(stripCount = 2, scalePercent = 25, marginDots = 64))
        val guides = Mxw01PageGuides(layout, 1)
        assertTrue(layout.contentWidth > 0)
        assertTrue(guides.ticks.all { it in guides.left..guides.right })
        repeat(guides.height) { y -> guides.drawRow(ByteArray(layout.pageRowBytes), y) }
    }

    private fun ink(row: ByteArray, x: Int): Boolean = row[x / 8].toInt() and (1 shl (x % 8)) != 0
}
