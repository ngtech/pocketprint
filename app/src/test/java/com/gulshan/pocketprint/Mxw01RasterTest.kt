package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.Mxw01Dither
import com.gulshan.pocketprint.model.Mxw01Settings
import com.gulshan.pocketprint.mxw01.Mxw01Layout
import com.gulshan.pocketprint.mxw01.Mxw01Raster
import com.gulshan.pocketprint.mxw01.Mxw01Spool
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class Mxw01RasterTest {
    @Test fun fittingHonoursScaleAndMargins() {
        val full = Mxw01Layout(Mxw01Settings())
        assertEquals(368, full.width)
        assertEquals(8, full.left)
        val half = Mxw01Layout(Mxw01Settings(scalePercent = 50))
        assertEquals(184, half.width)
        assertEquals(100, half.left)
    }

    @Test fun packingUsesBlackOneAndLeftPixelInLeastSignificantBit() {
        val raster = Mxw01Raster(Mxw01Layout(Mxw01Settings(marginDots = 0)), Mxw01Dither.THRESHOLD)
        val pixels = IntArray(384) { -1 }
        pixels[0] = 0xff000000.toInt()
        pixels[7] = 0xff000000.toInt()
        pixels[8] = 0xff000000.toInt()
        pixels[383] = 0xff000000.toInt()
        val row = raster.row(pixels)
        assertEquals(48, row.size)
        assertEquals(0x81, row[0].toInt() and 255)
        assertEquals(1, row[1].toInt())
        assertEquals(0x80, row[47].toInt() and 255)
    }

    @Test fun transparentPixelsAreWhiteAndMarginsNeverCarryInk() {
        val layout = Mxw01Layout(Mxw01Settings(marginDots = 8))
        val raster = Mxw01Raster(layout, Mxw01Dither.THRESHOLD)
        assertTrue(raster.row(IntArray(layout.width)).all { it == 0.toByte() })
        val black = raster.row(IntArray(layout.width) { 0xff000000.toInt() })
        assertEquals(0, black.first().toInt())
        assertEquals(0, black.last().toInt())
        assertTrue(black.copyOfRange(1, 47).all { it == (-1).toByte() })
    }

    @Test fun errorDiffusionKeepsMidGrayCoverageAcrossManyRows() {
        val layout = Mxw01Layout(Mxw01Settings(marginDots = 0))
        val gray = IntArray(384) { 0xff808080.toInt() }
        val diffusion = Mxw01Raster(layout, Mxw01Dither.FLOYD_STEINBERG)
        val threshold = Mxw01Raster(layout, Mxw01Dither.THRESHOLD)
        var dark = 0
        repeat(64) {
            dark += diffusion.row(gray).sumOf { Integer.bitCount(it.toInt() and 255) }
            assertTrue(threshold.row(gray).all { it == 0.toByte() })
        }
        assertTrue(dark in (384 * 64 * 45 / 100)..(384 * 64 * 55 / 100))
    }

    @Test fun shortRasterAnnouncesAndSendsNinetyRowsWithWhitePadding() {
        val result = spool { Mxw01Spool.appendPage(it, ByteArray(48) { -1 }.inputStream(), 1) }
        DataInputStream(result.inputStream()).use { input ->
            assertEquals(Mxw01Spool.MAGIC, input.readInt())
            assertEquals(90, input.readInt())
            val data = ByteArray(90 * 48).also { input.readFully(it) }
            assertTrue(data.take(48).all { it == (-1).toByte() })
            assertTrue(data.drop(48).all { it == 0.toByte() })
            assertEquals(0, input.readInt())
            assertEquals(-1, input.read())
        }
    }

    @Test fun longRasterIsSplitWithoutDroppingOrRepeatingRows() {
        val pixels = ByteArray(70_000 * 48) { (it / 48 % 251).toByte() }
        val result = spool { Mxw01Spool.appendPage(it, pixels.inputStream(), 70_000) }
        val recovered = ByteArrayOutputStream()
        DataInputStream(result.inputStream()).use { input ->
            input.readInt()
            val heights = mutableListOf<Int>()
            var rows = Mxw01Spool.readRows(input)
            while (rows != 0) {
                heights += rows
                val bytes = ByteArray(rows * 48).also { input.readFully(it) }
                recovered.write(bytes)
                rows = Mxw01Spool.readRows(input)
            }
            assertEquals(listOf(65535, 4465), heights)
        }
        assertArrayEquals(pixels, recovered.toByteArray())
    }

    @Test fun pageSpacingAddsOnlyRequestedWhiteRows() {
        val result = spool {
            Mxw01Spool.appendPage(it, ByteArray(90 * 48) { 1 }.inputStream(), 90)
            Mxw01Spool.appendPage(it, ByteArray(90 * 48) { 2 }.inputStream(), 90, before = 10, after = 4)
        }
        DataInputStream(result.inputStream()).use { input ->
            input.readInt()
            assertEquals(90, input.readInt())
            input.skipBytes(90 * 48)
            assertEquals(104, input.readInt())
            val rows = ByteArray(104 * 48).also { input.readFully(it) }
            assertTrue(rows.take(10 * 48).all { it == 0.toByte() })
            assertTrue(rows.drop(10 * 48).take(90 * 48).all { it == 2.toByte() })
            assertTrue(rows.takeLast(4 * 48).all { it == 0.toByte() })
        }
    }

    @Test fun emptyPageHasSafeWhiteMinimumAndInvalidSpoolHeightsFail() {
        val result = spool { Mxw01Spool.appendPage(it, byteArrayOf().inputStream(), 0) }
        val input = DataInputStream(result.inputStream())
        input.readInt()
        assertEquals(90, input.readInt())
        assertTrue(ByteArray(90 * 48).also { input.readFully(it) }.all { it == 0.toByte() })
        assertThrows(IllegalArgumentException::class.java) {
            Mxw01Spool.readRows(DataInputStream(byteArrayOf(0, 1, 0, 0).inputStream()))
        }
    }

    private fun spool(body: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> Mxw01Spool.begin(out); body(out); Mxw01Spool.end(out) }
        return bytes.toByteArray()
    }
}
