package com.gulshan.pocketprint.mxw01

/** Independently implemented from the published wire format; see docs/MXW01.md. */
object Mxw01Protocol {
    const val WIDTH = 384
    const val ROW_BYTES = WIDTH / 8
    const val MIN_ROWS = 90 // Firmware's minimum raster buffer: 4320 bytes.
    const val MAX_ROWS = 65535 // A9 carries an unsigned 16-bit row count.
    const val STATUS = 0xa1
    const val INTENSITY = 0xa2
    const val START = 0xa9
    const val DONE = 0xaa
    const val FLUSH = 0xad

    fun packet(opcode: Int, data: ByteArray = byteArrayOf(0)): ByteArray {
        require(opcode in 0..255 && data.size <= 65535)
        return byteArrayOf(
            0x22, 0x21, opcode.toByte(), 0,
            data.size.toByte(), (data.size ushr 8).toByte(),
        ) + data + byteArrayOf(checksum(data).toByte(), 0xff.toByte())
    }

    fun start(rows: Int): ByteArray {
        require(rows in MIN_ROWS..MAX_ROWS)
        // 0x30 = 48 bytes per row; mode zero is the documented 1-bit mode.
        return packet(START, byteArrayOf(rows.toByte(), (rows ushr 8).toByte(), ROW_BYTES.toByte(), 0))
    }

    /** CRC-8, polynomial 0x07, initial 0, unreflected, over payload only. */
    fun checksum(data: ByteArray): Int {
        var result = 0
        data.forEach { value ->
            result = result xor (value.toInt() and 255)
            for (bit in 0 until 8) {
                val feedback = if (result and 128 == 0) 0 else 7
                result = ((result shl 1) xor feedback) and 255
            }
        }
        return result
    }

    data class Reply(val opcode: Int, val data: ByteArray)

    /**
     * Reassembles fragmented/coalesced AE02 notifications. Firmware references
     * disagree on trailers; only the header and complete declared payload are
     * interpreted. Unknown trailer bytes are skipped before the next preamble.
     */
    class Decoder {
        private var buffered = byteArrayOf()

        fun accept(bytes: ByteArray): List<Reply> {
            buffered += bytes
            val replies = mutableListOf<Reply>()
            while (buffered.size >= 2) {
                if (buffered[0] != 0x22.toByte() || buffered[1] != 0x21.toByte()) {
                    buffered = buffered.copyOfRange(1, buffered.size)
                    continue
                }
                if (buffered.size < 6) break
                val length = (buffered[4].toInt() and 255) or ((buffered[5].toInt() and 255) shl 8)
                // Known status/acceptance/completion replies are small. A corrupt
                // length must not accumulate an unbounded notification buffer.
                if (length > 256) {
                    buffered = buffered.copyOfRange(2, buffered.size)
                    continue
                }
                if (buffered.size < 6 + length) break
                replies += Reply(buffered[2].toInt() and 255, buffered.copyOfRange(6, 6 + length))
                buffered = buffered.copyOfRange(6 + length, buffered.size)
            }
            return replies
        }
    }
}
