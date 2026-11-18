package com.gulshan.pocketprint

import com.gulshan.pocketprint.mxw01.Mxw01Link
import com.gulshan.pocketprint.mxw01.Mxw01Protocol as P
import com.gulshan.pocketprint.mxw01.Mxw01Session
import com.gulshan.pocketprint.mxw01.Mxw01Spool
import com.gulshan.pocketprint.transport.TransportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class Mxw01SessionTest {
    private class Link : Mxw01Link {
        override var dataChunkBytes = 20
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val events = mutableListOf<String>()
        val data = mutableListOf<ByteArray>()
        var closed = false
        var replyStatus = true
        var accept = true
        var complete = true
        var stallData = false
        var disconnectAfterFlush = false
        var unsolicitedDone = false
        var immediateDone = false
        override suspend fun open() = Unit
        override suspend fun receive() = inbox.receive()
        override fun close() { closed = true; inbox.close(TransportException("link lost")) }
        override suspend fun control(bytes: ByteArray) {
            val opcode = bytes[2].toInt() and 255
            events += "C:%02x".format(opcode)
            when (opcode) {
                P.STATUS -> {
                    if (unsolicitedDone) inbox.send(P.packet(P.DONE, byteArrayOf()))
                    if (replyStatus) inbox.send(P.packet(P.STATUS, byteArrayOf(0)))
                }
                P.START -> {
                    if (immediateDone) inbox.send(P.packet(P.DONE, byteArrayOf()))
                    inbox.send(P.packet(P.START, byteArrayOf(if (accept) 0 else 1)))
                }
                P.FLUSH -> {
                    if (disconnectAfterFlush) inbox.close(TransportException("link lost"))
                    else if (complete) inbox.send(P.packet(P.DONE, byteArrayOf()))
                }
            }
        }
        override suspend fun raster(bytes: ByteArray) {
            if (stallData) awaitCancellation()
            events += "D"
            data += bytes.copyOf()
        }
    }

    private fun spool(empty: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            Mxw01Spool.begin(it)
            if (!empty) Mxw01Spool.appendPage(it, ByteArray(90 * 48) { 1 }.inputStream(), 90)
            Mxw01Spool.end(it)
        }
        return bytes.toByteArray()
    }

    private fun session(link: Link) = Mxw01Session(link, 93, requestTimeoutMs = 100, completionTimeoutMs = 1000)

    @Test fun gatesDataOnAcceptanceAndUsesNegotiatedChunksThenFlushes() = runTest {
        val link = Link()
        val progress = mutableListOf<Long>()
        val sent = session(link).transmit(spool().inputStream()) { progress += it }
        assertEquals(4320L, sent)
        assertEquals(listOf("C:a2", "C:a1", "C:a9"), link.events.take(3))
        assertEquals("C:ad", link.events.last())
        assertTrue(link.events.drop(3).dropLast(1).all { it == "D" })
        assertTrue(link.data.all { it.size in 1..20 })
        assertEquals(4320, link.data.sumOf { it.size })
        assertEquals(4320L, progress.last())
    }

    @Test fun rejectedStartSendsNoRasterAndCloses() = runTest {
        val link = Link().apply { accept = false }
        try { session(link).transmit(spool().inputStream()); fail("accepted rejection") }
        catch (expected: TransportException) { assertTrue(expected.message!!.contains("rejected")) }
        assertTrue(link.data.isEmpty())
        assertTrue(link.closed)
    }

    @Test fun completionWaiterIsReadyBeforeStartCanReply() = runTest {
        val link = Link().apply { immediateDone = true; complete = false }
        assertEquals(4320L, session(link).transmit(spool().inputStream()))
        assertEquals("C:ad", link.events.last())
    }

    @Test fun statusTimeoutIsFailureAndDoesNotStartPrinting() = runTest {
        val link = Link().apply { replyStatus = false }
        try { session(link).transmit(spool().inputStream()); fail("accepted timeout") }
        catch (expected: TransportException) { assertTrue(expected.message!!.contains("timed out")) }
        assertEquals(listOf("C:a2", "C:a1"), link.events)
        assertTrue(link.closed)
    }

    @Test fun rasterWriteTimeoutClosesAndNeverFlushesPartialImage() = runTest {
        val link = Link().apply { stallData = true }
        try { session(link).transmit(spool().inputStream()); fail("accepted stalled write") }
        catch (expected: TransportException) { assertTrue(expected.message!!.contains("write timed out")) }
        assertFalse(link.events.contains("C:ad"))
        assertTrue(link.closed)
    }

    @Test fun disconnectWhileWaitingForCompletionFails() = runTest {
        val link = Link().apply { disconnectAfterFlush = true }
        try { session(link).transmit(spool().inputStream()); fail("accepted disconnect") }
        catch (expected: TransportException) { assertEquals("link lost", expected.message) }
        assertTrue(link.closed)
    }

    @Test fun unsolicitedCompletionCannotCompleteTheNextPrint() = runTest {
        val link = Link().apply { unsolicitedDone = true; complete = false }
        try { session(link).transmit(spool().inputStream()); fail("accepted stale completion") }
        catch (expected: TransportException) { assertTrue(expected.message!!.contains("did not signal completion")) }
        assertTrue(link.closed)
    }

    @Test fun cancellationDuringResponseWaitClosesWithoutSendingData() = runTest {
        val link = Link().apply { replyStatus = false }
        val job = launch { session(link).transmit(spool().inputStream()) }
        testScheduler.advanceTimeBy(50)
        runCurrent()
        assertEquals("C:a1", link.events.last())
        job.cancelAndJoin()
        assertTrue(link.closed)
        assertTrue(link.data.isEmpty())
    }

    @Test fun cancellationDuringRasterTransferClosesTheLink() = runTest {
        val link = Link().apply { stallData = true }
        val job = launch { session(link).transmit(spool().inputStream()) }
        testScheduler.advanceTimeBy(50)
        runCurrent()
        assertEquals("C:a9", link.events.last())
        job.cancelAndJoin()
        assertTrue(link.closed)
        assertFalse(link.events.contains("C:ad"))
    }

    @Test fun parallelCallCannotInterleaveOrReplayTheSameConnection() = runTest {
        val link = Link()
        val session = session(link)
        val first = launch { session.transmit(spool().inputStream()) }
        val second = launch {
            try { session.transmit(spool().inputStream()); fail("replayed connection") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Reconnect")) }
        }
        first.join(); second.join()
        assertEquals(1, link.events.count { it == "C:a9" })
    }

    @Test fun emptyDocumentAndRawCommandsNeverReachControlPipe() = runTest {
        for (bytes in listOf(spool(empty = true), "^XA^XZ".toByteArray())) {
            val link = Link()
            try { session(link).transmit(bytes.inputStream()); fail("accepted invalid document") }
            catch (expected: IllegalArgumentException) { /* expected */ }
            assertTrue(link.events.isEmpty())
            assertTrue(link.closed)
        }
    }
}
