package com.gulshan.pocketprint.mxw01

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream

/** Internal spool format, never written directly to a printer: magic, (rows, raster)*, zero. */
object Mxw01Spool {
    const val MAGIC = 0x4d585731 // MXW1, version 1, big-endian JVM Data streams.

    fun begin(out: DataOutputStream) = out.writeInt(MAGIC)
    fun end(out: DataOutputStream) = out.writeInt(0)

    fun appendPage(
        out: DataOutputStream,
        input: InputStream,
        rows: Int,
        before: Int = 0,
        after: Int = 0,
        checkActive: () -> Unit = {},
    ) {
        val source = DataInputStream(input)
        appendRows(out, rows, before, after, checkActive) { source.readFully(it) }
    }

    /** Reads only content rows; callers can extract a strip from a disk-backed full-page raster. */
    fun appendRows(
        out: DataOutputStream,
        rows: Int,
        before: Int = 0,
        after: Int = 0,
        checkActive: () -> Unit = {},
        readRow: (ByteArray) -> Unit,
    ) {
        require(rows >= 0 && before >= 0 && after >= 0)
        val total = (rows.toLong() + before + after).coerceAtLeast(1)
        val row = ByteArray(Mxw01Protocol.ROW_BYTES)
        var position = 0L
        while (position < total) {
            val contentRows = minOf(total - position, Mxw01Protocol.MAX_ROWS.toLong()).toInt()
            val sentRows = maxOf(contentRows, Mxw01Protocol.MIN_ROWS)
            out.writeInt(sentRows)
            for (i in 0 until sentRows) {
                checkActive()
                val y = position + i
                if (i < contentRows && y >= before && y < before.toLong() + rows) readRow(row)
                else row.fill(0)
                out.write(row)
            }
            position += contentRows
        }
    }

    fun readRows(input: DataInputStream): Int = input.readInt().also {
        require(it == 0 || it in Mxw01Protocol.MIN_ROWS..Mxw01Protocol.MAX_ROWS) { "Invalid MXW01 spool row count" }
    }
}
