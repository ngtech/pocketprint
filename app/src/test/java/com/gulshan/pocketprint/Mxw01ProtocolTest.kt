package com.gulshan.pocketprint

import com.gulshan.pocketprint.mxw01.Mxw01Protocol as P
import org.junit.Assert.*
import org.junit.Test

class Mxw01ProtocolTest {
    @Test fun crcUsesPublishedNumericParameters() {
        assertEquals(0xf4, P.checksum("123456789".toByteArray()))
        assertEquals(0, P.checksum(byteArrayOf(0)))
    }

    @Test fun controlPacketMatchesWireVector() {
        assertArrayEquals(
            byteArrayOf(0x22, 0x21, 0xa2.toByte(), 0, 1, 0, 0x5d, 0x94.toByte(), 0xff.toByte()),
            P.packet(P.INTENSITY, byteArrayOf(93)),
        )
    }

    @Test fun maximumHeightIsLittleEndianAndMonochrome() {
        assertArrayEquals(byteArrayOf(-1, -1, 0x30, 0), P.start(65535).copyOfRange(6, 10))
    }

    @Test fun heightCannotOverflowOrDropBelowFirmwareMinimum() {
        for (height in listOf(-1, 0, 89, 65536)) {
            assertThrows(IllegalArgumentException::class.java) { P.start(height) }
        }
    }

    @Test fun fragmentedAndCombinedNotificationsKeepUnknownHeaderByte() {
        val decoder = P.Decoder()
        val response = byteArrayOf(0x22, 0x21, 0xa9.toByte(), 3, 1, 0, 0, -1)
        assertTrue(decoder.accept(response.copyOfRange(0, 4)).isEmpty())
        val replies = decoder.accept(response.copyOfRange(4, 8) + P.packet(P.DONE, byteArrayOf()))
        assertEquals(listOf(P.START, P.DONE), replies.map { it.opcode })
        assertArrayEquals(byteArrayOf(0), replies[0].data)
    }

    @Test fun incompletePayloadAndCorruptLengthAreNotResponses() {
        val decoder = P.Decoder()
        assertTrue(decoder.accept(byteArrayOf(0x22, 0x21, 0xa1.toByte(), 0, 10, 0, 1)).isEmpty())
        val corrupt = P.Decoder()
        val replies = corrupt.accept(byteArrayOf(0x22, 0x21, 0xa1.toByte(), 0, -1, -1) + P.packet(P.START))
        assertEquals(P.START, replies.single().opcode)
    }
}
