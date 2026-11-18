package com.gulshan.pocketprint

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gulshan.pocketprint.model.Mxw01Dither
import com.gulshan.pocketprint.model.Mxw01Settings
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.mxw01.Mxw01Spool
import com.gulshan.pocketprint.render.Mxw01RasterEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File

/** Real PdfRenderer/Bitmap integration; run on an Android device or emulator. */
@RunWith(AndroidJUnit4::class)
class Mxw01PdfTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = Mxw01Settings(dither = Mxw01Dither.THRESHOLD, marginDots = 0, pageSpacingDots = 7)

    private fun pdf(pages: Int = 2, height: Int = 300): File {
        val file = File.createTempFile("mxw01_input_", ".pdf", context.cacheDir)
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(384, height, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
                page.canvas.drawRect(0f, 10f, 8f, 20f, paint)
                page.canvas.drawRect(376f, height - 20f, 384f, height - 10f, paint)
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return file
    }

    private fun pages(file: File): List<ByteArray> = DataInputStream(file.inputStream().buffered()).use { input ->
        assertEquals(Mxw01Spool.MAGIC, input.readInt())
        val pages = mutableListOf<ByteArray>()
        var rows = Mxw01Spool.readRows(input)
        while (rows != 0) {
            pages += ByteArray(rows * 48).also { input.readFully(it) }
            rows = Mxw01Spool.readRows(input)
        }
        assertEquals(-1, input.read())
        pages
    }

    @Test fun multiplePdfPagesRetainBothEdgesAndConfiguredSpacing() = runBlocking {
        val source = pdf()
        val rendered = Mxw01RasterEncoder.fromPdf(context, source, settings, PrintOptions())
        try {
            val pages = pages(rendered.file)
            assertEquals(2, rendered.pageCount)
            assertEquals(listOf(300 * 48, 307 * 48), pages.map { it.size })
            assertEquals(255, pages[0][10 * 48].toInt() and 255)
            assertEquals(255, pages[0][289 * 48 + 47].toInt() and 255)
            assertEquals(255, pages[1][17 * 48].toInt() and 255)
            assertTrue(pages[1].take(7 * 48).all { it == 0.toByte() })
        } finally { rendered.file.delete(); source.delete() }
    }

    @Test fun trimmingPreservesInteriorWhitespaceAndInkAtBothEnds() = runBlocking {
        val source = pdf(pages = 1)
        val rendered = Mxw01RasterEncoder.fromPdf(context, source, settings.copy(trimBlank = true), PrintOptions())
        try {
            val raster = pages(rendered.file).single()
            assertEquals(280 * 48, raster.size)
            assertEquals(255, raster[0].toInt() and 255)
            assertEquals(255, raster.last().toInt() and 255)
            assertTrue(raster.copyOfRange(20 * 48, 250 * 48).all { it == 0.toByte() })
        } finally { rendered.file.delete(); source.delete() }
    }

    @Test fun scalingCentresTheWholePageAndPreservesItsAspectRatio() = runBlocking {
        val source = pdf(pages = 1)
        val rendered = Mxw01RasterEncoder.fromPdf(context, source, settings.copy(scalePercent = 50), PrintOptions())
        try {
            val raster = pages(rendered.file).single()
            assertEquals(150 * 48, raster.size)
            assertEquals(0x0f, raster[5 * 48 + 12].toInt() and 255)
            assertEquals(0xf0, raster[144 * 48 + 35].toInt() and 255)
            for (y in 0 until 150) {
                assertTrue(raster.copyOfRange(y * 48, y * 48 + 12).all { it == 0.toByte() })
                assertTrue(raster.copyOfRange(y * 48 + 36, (y + 1) * 48).all { it == 0.toByte() })
            }
        } finally { rendered.file.delete(); source.delete() }
    }

    @Test fun longPdfHasNoArbitraryHeightCutoffAndPreviewUsesSameRaster() = runBlocking {
        val source = pdf(pages = 1, height = 2000)
        val rendered = Mxw01RasterEncoder.fromPdf(context, source, settings, PrintOptions())
        val preview = Mxw01RasterEncoder.preview(context, source, settings, PrintOptions())
        try {
            val raster = pages(rendered.file).single()
            assertEquals(2000 * 48, raster.size)
            assertEquals(255, raster[1989 * 48 + 47].toInt() and 255)
            assertNotNull(preview)
            assertEquals(384, preview!!.width)
            assertEquals(2000, preview.height)
            assertEquals(Color.BLACK, preview.getPixel(0, 10))
            assertEquals(Color.BLACK, preview.getPixel(383, 1989))
            assertEquals(Color.WHITE, preview.getPixel(192, 1000))
        } finally { preview?.recycle(); rendered.file.delete(); source.delete() }
    }

    @Test fun pageRangeAndCopiesAreAppliedBeforeTransport() = runBlocking {
        val source = pdf()
        val rendered = Mxw01RasterEncoder.fromPdf(context, source, settings, PrintOptions(copies = 2, pageFrom = 2, pageTo = 2))
        try {
            assertEquals(2, rendered.pageCount)
            assertEquals(listOf(300 * 48, 307 * 48), pages(rendered.file).map { it.size })
        } finally { rendered.file.delete(); source.delete() }
    }

    @Test fun emptySelectionFailsAndRemovesTemporaryRasters() = runBlocking {
        val source = pdf(pages = 1)
        val before = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        try {
            Mxw01RasterEncoder.fromPdf(context, source, settings, PrintOptions(pageFrom = 2, pageTo = 2))
            fail("accepted empty document")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("no selected pages"))
        } finally { source.delete() }
        val remaining = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("mxw01_") && it.name !in before }
        assertTrue(remaining.isEmpty())
    }

    @Test fun nativeCopyCountIsNotSilentlyCappedAtTheUiLimit() = runBlocking {
        val source = pdf(pages = 1, height = 96)
        val rendered = Mxw01RasterEncoder.fromPdf(context, source, settings, PrintOptions(copies = 100))
        try {
            assertEquals(100, rendered.pageCount)
            assertEquals(100, pages(rendered.file).size)
        } finally { rendered.file.delete(); source.delete() }
    }
}
