package com.gulshan.pocketprint.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.gulshan.pocketprint.model.Mxw01Settings
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.mxw01.Mxw01Layout
import com.gulshan.pocketprint.mxw01.Mxw01Protocol
import com.gulshan.pocketprint.mxw01.Mxw01Raster
import com.gulshan.pocketprint.mxw01.Mxw01Spool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

object Mxw01RasterEncoder {
    suspend fun fromPdf(
        context: Context,
        pdf: File,
        settings: Mxw01Settings,
        options: PrintOptions,
    ): RenderedDocument = withContext(Dispatchers.IO) {
        val config = settings.validated().let {
            if (options.dither) it else it.copy(dither = com.gulshan.pocketprint.model.Mxw01Dither.THRESHOLD)
        }
        val layout = Mxw01Layout(config)
        val job = currentCoroutineContext()
        val output = File.createTempFile("mxw01_", ".mxw", context.cacheDir)
        val pageFile = File.createTempFile("mxw01_page_", ".mono", context.cacheDir)
        var pages = 0
        try {
            DataOutputStream(output.outputStream().buffered()).use { spool ->
                Mxw01Spool.begin(spool)
                RandomAccessFile(pageFile, "rw").use { pageRaster ->
                    var packer = Mxw01Raster(layout, config.dither)
                    var height = 0
                    var firstInk = -1
                    var endInk = 0
                    val pixels = IntArray(layout.width)
                    repeat(options.copies.coerceAtLeast(1)) {
                        PdfRasterizer.forEachPageBanded(
                            pdf,
                            targetWidthPx = layout.width,
                            pageRange = options.pageRange,
                            maxBandPixels = Mxw01Protocol.WIDTH * 128,
                            onPageStart = { _, _, pageHeight ->
                                job.ensureActive()
                                pageRaster.setLength(0)
                                pageRaster.seek(0)
                                height = pageHeight
                                firstInk = -1
                                endInk = 0
                                packer = Mxw01Raster(layout, config.dither)
                            },
                            onBand = { bitmap, offset, rows ->
                                for (y in 0 until rows) {
                                    job.ensureActive()
                                    bitmap.getPixels(pixels, 0, layout.width, 0, y, layout.width, 1)
                                    val row = packer.row(pixels)
                                    pageRaster.write(row)
                                    if (row.any { it != 0.toByte() }) {
                                        if (firstInk < 0) firstInk = offset + y
                                        endInk = offset + y + 1
                                    }
                                }
                            },
                            onPageEnd = {
                                val start = if (config.trimBlank) firstInk.coerceAtLeast(0) else 0
                                val count = if (config.trimBlank) (endInk - start).coerceAtLeast(0) else height
                                pageFile.inputStream().use { input ->
                                    input.channel.position(start.toLong() * Mxw01Protocol.ROW_BYTES)
                                    Mxw01Spool.appendPage(
                                        spool, input, count,
                                        before = config.marginDots + if (pages == 0) 0 else config.pageSpacingDots,
                                        after = config.marginDots,
                                        checkActive = { job.ensureActive() },
                                    )
                                }
                                pages++
                            },
                        )
                    }
                }
                require(pages > 0) { "The document has no selected pages" }
                Mxw01Spool.end(spool)
            }
            RenderedDocument(output, PrintLanguage.MXW01, pages)
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            pageFile.delete()
        }
    }

    /** Same raster path as printing, including margins, trimming, bit order and firmware padding. */
    suspend fun preview(context: Context, pdf: File, settings: Mxw01Settings, options: PrintOptions): Bitmap? {
        val first = options.pageRange?.first ?: 1
        val rendered = fromPdf(context, pdf, settings, options.copy(copies = 1, pageFrom = first, pageTo = first))
        return try {
            var height = 0L
            DataInputStream(rendered.file.inputStream().buffered()).use { input ->
                check(input.readInt() == Mxw01Spool.MAGIC)
                var rows = Mxw01Spool.readRows(input)
                val row = ByteArray(Mxw01Protocol.ROW_BYTES)
                while (rows != 0) {
                    height += rows
                    repeat(rows) { input.readFully(row) }
                    rows = Mxw01Spool.readRows(input)
                }
            }
            // Decline an enormous preview instead of cropping it or allocating a full long roll.
            if (height * Mxw01Protocol.WIDTH > 4_000_000) return null
            val bitmap = Bitmap.createBitmap(Mxw01Protocol.WIDTH, height.toInt(), Bitmap.Config.ARGB_8888)
            DataInputStream(rendered.file.inputStream().buffered()).use { input ->
                input.readInt()
                var rows = Mxw01Spool.readRows(input)
                var y = 0
                val packed = ByteArray(Mxw01Protocol.ROW_BYTES)
                val pixels = IntArray(Mxw01Protocol.WIDTH)
                while (rows != 0) {
                    repeat(rows) {
                        input.readFully(packed)
                        for (x in pixels.indices) {
                            pixels[x] = if (packed[x / 8].toInt() and (1 shl (x % 8)) != 0) Color.BLACK else Color.WHITE
                        }
                        bitmap.setPixels(pixels, 0, pixels.size, 0, y++, pixels.size, 1)
                    }
                    rows = Mxw01Spool.readRows(input)
                }
            }
            bitmap
        } finally {
            rendered.file.delete()
        }
    }

    fun testPage(settings: Mxw01Settings, title: String, detail: String): ByteArray {
        val config = settings.validated()
        val layout = Mxw01Layout(config)
        val scale = layout.width / Mxw01Protocol.WIDTH.toFloat()
        val bitmap = Bitmap.createBitmap(layout.width, Math.round(220 * scale).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            val paint = Paint().apply { color = Color.BLACK; textSize = 20f }
            canvas.drawText(title, 4f, 32f, paint)
            paint.textSize = 15f
            canvas.drawText(detail, 4f, 60f, paint)
            paint.style = Paint.Style.STROKE
            canvas.drawRect(1f, 1f, Mxw01Protocol.WIDTH - 2f, 218f, paint)
            for (x in 8 until Mxw01Protocol.WIDTH - 8 step 8) {
                canvas.drawLine(x.toFloat(), 90f, x.toFloat(), 190f, paint)
            }
            val packed = ByteArrayOutputStream()
            val raster = Mxw01Raster(layout, config.dither)
            val pixels = IntArray(layout.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(pixels, 0, pixels.size, 0, y, pixels.size, 1)
                packed.write(raster.row(pixels))
            }
            val result = ByteArrayOutputStream()
            DataOutputStream(result).use { out ->
                Mxw01Spool.begin(out)
                Mxw01Spool.appendPage(out, packed.toByteArray().inputStream(), bitmap.height, config.marginDots, config.marginDots)
                Mxw01Spool.end(out)
            }
            return result.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
