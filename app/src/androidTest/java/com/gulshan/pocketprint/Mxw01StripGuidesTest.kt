package com.gulshan.pocketprint

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gulshan.pocketprint.model.Mxw01Dither
import com.gulshan.pocketprint.model.Mxw01Settings
import com.gulshan.pocketprint.model.Mxw01StripDirection
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.mxw01.Mxw01Spool
import com.gulshan.pocketprint.render.Mxw01RasterEncoder
import com.gulshan.pocketprint.render.PdfRasterizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class Mxw01StripGuidesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = Mxw01Settings(
        splitIntoStrips = true, marginDots = 0, pageSpacingDots = 7, dither = Mxw01Dither.THRESHOLD,
    )

    private fun pdf(width: Int = 384, height: Int = 300, pages: Int = 1, marks: Boolean = false, colors: Boolean = false): File {
        val file = File.createTempFile("mxw01_guides_", ".pdf", context.cacheDir)
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint().apply { isAntiAlias = false }
                if (colors) {
                    val corners = listOf(
                        Triple(0, 0, Color.RED), Triple(width - 20, 0, Color.GREEN),
                        Triple(0, height - 20, Color.BLUE), Triple(width - 20, height - 20, Color.BLACK),
                    )
                    for ((x, y, color) in corners) {
                        paint.color = color
                        page.canvas.drawRect(x.toFloat(), y.toFloat(), x + 20f, y + 20f, paint)
                    }
                } else if (marks) {
                    paint.color = Color.BLACK
                    val left = if (index % 2 == 0) 0f else width - 20f
                    val right = if (index % 2 == 0) width - 20f else 0f
                    page.canvas.drawRect(left, 0f, left + 20, 20f, paint)
                    page.canvas.drawRect(right, height - 20f, right + 20, height.toFloat(), paint)
                }
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        return file
    }

    private fun strips(file: File): List<ByteArray> = DataInputStream(file.inputStream().buffered()).use { input ->
        assertEquals(Mxw01Spool.MAGIC, input.readInt())
        buildList {
            var rows = Mxw01Spool.readRows(input)
            while (rows != 0) {
                add(ByteArray(rows * 48).also { input.readFully(it) })
                rows = Mxw01Spool.readRows(input)
            }
        }
    }

    private fun ink(strip: ByteArray, x: Int, y: Int): Boolean =
        strip[y * 48 + x / 8].toInt() and (1 shl (x % 8)) != 0

    @Test fun counterclockwiseRenderingKeepsAllFourCornersAcrossManyBands() {
        val source = pdf(colors = true)
        var output: Bitmap? = null
        var bandCount = 0
        try {
            PdfRasterizer.forEachPageBanded(
                source, targetWidthPx = 300, maxBandPixels = 300 * 17, rotateCounterClockwise = true,
                onPageStart = { _, width, height ->
                    assertEquals(300, width)
                    assertEquals(384, height)
                    output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                },
                onBand = { bitmap, offset, rows ->
                    bandCount++
                    val pixels = IntArray(bitmap.width * rows)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, rows)
                    output!!.setPixels(pixels, 0, bitmap.width, 0, offset, bitmap.width, rows)
                },
            )
            assertTrue(bandCount > 20)
            assertEquals(Color.GREEN, output!!.getPixel(10, 10))
            assertEquals(Color.BLACK, output!!.getPixel(290, 10))
            assertEquals(Color.RED, output!!.getPixel(10, 374))
            assertEquals(Color.BLUE, output!!.getPixel(290, 374))
            assertEquals(Color.WHITE, output!!.getPixel(150, 192))
        } finally { output?.recycle(); source.delete() }
    }

    @Test fun horizontalSectionsPrintTopToBottomForEveryPageAndCopy() = runBlocking {
        val source = pdf(width = 300, height = 384, pages = 2, marks = true)
        val config = settings.copy(stripDirection = Mxw01StripDirection.HORIZONTAL, stripGuides = false, stripOverlap = false)
        val rendered = Mxw01RasterEncoder.fromPdf(context, source, config, PrintOptions(copies = 2))
        try {
            val output = strips(rendered.file)
            assertEquals(12, rendered.pageCount)
            assertEquals(900 * 48, output.first().size)
            assertTrue(output.drop(1).all { it.size == 907 * 48 })
            repeat(4) { page ->
                val first = page * 3
                val firstPadding = if (first == 0) 0 else 7
                assertTrue(output[first + 1].all { it == 0.toByte() })
                if (page % 2 == 0) {
                    assertTrue(ink(output[first], 30, 870 + firstPadding))
                    assertTrue(ink(output[first + 2], 354, 30 + 7))
                    assertFalse(ink(output[first], 30, 30 + firstPadding))
                } else {
                    assertTrue(ink(output[first], 30, 30 + firstPadding))
                    assertTrue(ink(output[first + 2], 354, 870 + 7))
                    assertFalse(ink(output[first], 30, 870 + firstPadding))
                }
            }
        } finally { rendered.file.delete(); source.delete() }
    }

    @Test fun originalPageFrameHasNoExtraBordersAtInternalCutsInEitherDirection() = runBlocking {
        val source = pdf()
        try {
            for (direction in Mxw01StripDirection.entries) {
                val config = settings.copy(stripDirection = direction)
                val rendered = Mxw01RasterEncoder.fromPdf(context, source, config, PrintOptions())
                try {
                    val output = strips(rendered.file)
                    val height = if (direction == Mxw01StripDirection.VERTICAL) 880 else 1427
                    assertEquals(listOf(height, height + 7, height + 7), output.map { it.size / 48 })
                    for (strip in 0..2) {
                        val padding = if (strip == 0) 0 else 7
                        for (x in 0 until 384) {
                            assertEquals("extra internal border in $direction strip $strip at $x",
                                strip == 0 && x == 11 || strip == 2 && x == 372,
                                ink(output[strip], x, height / 2 + padding))
                            val frame = (strip != 0 || x >= 11) && (strip != 2 || x <= 372)
                            assertEquals(frame, ink(output[strip], x, 11 + padding))
                            assertEquals(frame, ink(output[strip], x, height - 12 + padding))
                        }
                    }
                    // Both overlap edges are visible on both pieces, above the frame.
                    for (strip in 1..2) {
                        val before = if (strip == 1) 0 else 7
                        assertTrue(ink(output[strip - 1], 368, 3 + before))
                        assertTrue(ink(output[strip - 1], 383, 3 + before))
                        assertTrue(ink(output[strip], 0, 3 + 7))
                        assertTrue(ink(output[strip], 15, 3 + 7))
                        for (y in 0 until height) for (x in 0 until 16) {
                            assertEquals(ink(output[strip - 1], 368 + x, y + before), ink(output[strip], x, y + 7))
                        }
                    }
                } finally { rendered.file.delete() }
            }
        } finally { source.delete() }
    }

    @Test fun guidesKeepOriginalBlankEdgesEvenWhenTrimmingWasPreviouslyEnabled() = runBlocking {
        val source = pdf()
        try {
            for (direction in Mxw01StripDirection.entries) {
                val config = settings.copy(stripDirection = direction)
                val full = Mxw01RasterEncoder.fromPdf(context, source, config, PrintOptions())
                val trim = Mxw01RasterEncoder.fromPdf(context, source, config.copy(trimBlank = true), PrintOptions())
                try { assertArrayEquals(full.file.readBytes(), trim.file.readBytes()) }
                finally { full.file.delete(); trim.file.delete() }
            }
        } finally { source.delete() }
    }

    @Test fun previewMatchesEveryPrintedPixelIncludingContentGuidesAndOverlaps() = runBlocking {
        val source = pdf(marks = true)
        try {
            for (direction in Mxw01StripDirection.entries) {
                val config = settings.copy(stripDirection = direction, dither = Mxw01Dither.FLOYD_STEINBERG)
                val rendered = Mxw01RasterEncoder.fromPdf(context, source, config, PrintOptions())
                val preview = Mxw01RasterEncoder.preview(context, source, config, PrintOptions())
                try {
                    assertNotNull(preview)
                    val output = strips(rendered.file)
                    assertEquals(output.sumOf { it.size / 48 }, preview!!.height)
                    var offset = 0
                    for (strip in output) {
                        for (y in 0 until strip.size / 48) for (x in 0 until 384) {
                            if (ink(strip, x, y) != (preview.getPixel(x, offset + y) == Color.BLACK)) {
                                fail("preview differs in $direction at $x,${offset + y}")
                            }
                        }
                        offset += strip.size / 48
                    }
                    File(context.filesDir, "mxw01-guides-${direction.name.lowercase()}.png").outputStream().use {
                        assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    // Corner content lies just inside, rather than underneath, the frame.
                    assertTrue(ink(output.first(), 20, previewCornerY(direction)))
                } finally { preview?.recycle(); rendered.file.delete() }
            }
        } finally { source.delete() }
    }

    private fun previewCornerY(direction: Mxw01StripDirection): Int =
        if (direction == Mxw01StripDirection.VERTICAL) 20 else 1400

    @Test fun wholePageOutputIgnoresRememberedHorizontalModeAndGuides() = runBlocking {
        val source = pdf(marks = true)
        val normal = Mxw01RasterEncoder.fromPdf(context, source, settings.copy(splitIntoStrips = false, stripGuides = false), PrintOptions())
        val remembered = Mxw01RasterEncoder.fromPdf(context, source, settings.copy(splitIntoStrips = false, stripDirection = Mxw01StripDirection.HORIZONTAL), PrintOptions())
        try { assertArrayEquals(normal.file.readBytes(), remembered.file.readBytes()) }
        finally { normal.file.delete(); remembered.file.delete(); source.delete() }
    }
}
