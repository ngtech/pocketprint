package com.gulshan.pocketprint.mxw01

import com.gulshan.pocketprint.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** The protocol is independent of Android GATT and can run against a mocked link. */
interface Mxw01Link : Closeable {
    val dataChunkBytes: Int
    suspend fun open()
    suspend fun control(bytes: ByteArray)
    suspend fun raster(bytes: ByteArray)
    suspend fun receive(): ByteArray
}

class Mxw01Session(
    private val link: Mxw01Link,
    private val intensity: Int,
    private val requestTimeoutMs: Long = 10_000,
    private val completionTimeoutMs: Long = 60_000,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val gate = Mutex()
    private var used = false

    suspend fun transmit(source: InputStream, onProgress: (Long) -> Unit = {}): Long = gate.withLock {
        check(!used) { "Reconnect MXW01 before another transfer" }
        used = true
        try {
            source.use { stream ->
                val input = DataInputStream(stream)
                require(input.readInt() == Mxw01Spool.MAGIC) { "MXW01 requires a raster spool, not raw printer commands" }
                var rows = Mxw01Spool.readRows(input)
                require(rows != 0) { "The document has no selected pages" }
                coroutineScope {
                    val waiting = ConcurrentHashMap<Int, CompletableDeferred<Mxw01Protocol.Reply>>()
                    val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                        val decoder = Mxw01Protocol.Decoder()
                        while (true) {
                            decoder.accept(link.receive()).forEach { reply ->
                                if (reply.opcode == Mxw01Protocol.DONE || reply.data.isNotEmpty()) {
                                    waiting.remove(reply.opcode)?.complete(reply)
                                }
                            }
                        }
                    }
                    suspend fun request(opcode: Int, bytes: ByteArray): Mxw01Protocol.Reply {
                        val response = CompletableDeferred<Mxw01Protocol.Reply>()
                        waiting[opcode] = response // Register before the write: a reply can be immediate.
                        return try {
                            withTimeoutOrNull(requestTimeoutMs) {
                                link.control(bytes)
                                response.await()
                            } ?: throw TransportException("MXW01 timed out waiting for %02X".format(opcode))
                        } finally {
                            waiting.remove(opcode, response)
                        }
                    }
                    var sent = 0L
                    try {
                        withTimeoutOrNull(requestTimeoutMs) {
                            link.control(Mxw01Protocol.packet(Mxw01Protocol.INTENSITY, byteArrayOf(intensity.coerceIn(0, 150).toByte())))
                            true
                        } ?: throw TransportException("MXW01 intensity write timed out")
                        pause(50)
                        while (rows != 0) {
                            // A1's firmware-dependent fields are diagnostic, not invented paper/battery states.
                            request(Mxw01Protocol.STATUS, Mxw01Protocol.packet(Mxw01Protocol.STATUS))
                            val done = CompletableDeferred<Mxw01Protocol.Reply>()
                            waiting[Mxw01Protocol.DONE] = done
                            try {
                                val accepted = request(Mxw01Protocol.START, Mxw01Protocol.start(rows))
                                if (accepted.data[0] != 0.toByte()) throw TransportException("MXW01 rejected the print request: ${accepted.data[0].toInt() and 255}")
                                var remaining = rows * Mxw01Protocol.ROW_BYTES
                                val chunkSize = link.dataChunkBytes.coerceIn(1, 244)
                                while (remaining > 0) {
                                    coroutineContext.ensureActive()
                                    val chunk = ByteArray(minOf(remaining, chunkSize))
                                    input.readFully(chunk)
                                    withTimeoutOrNull(requestTimeoutMs) {
                                        link.raster(chunk)
                                        true
                                    } ?: throw TransportException("MXW01 raster write timed out")
                                    sent += chunk.size
                                    remaining -= chunk.size
                                    onProgress(sent)
                                    pause(25) // Balanced pacing from the documented MTU-bounded transfer observations.
                                }
                                withTimeoutOrNull(requestTimeoutMs) {
                                    link.control(Mxw01Protocol.packet(Mxw01Protocol.FLUSH))
                                    true
                                } ?: throw TransportException("MXW01 flush write timed out")
                                withTimeoutOrNull(completionTimeoutMs) {
                                    while (!done.isCompleted) {
                                        withTimeoutOrNull(500) { done.await() }
                                        onProgress(sent) // Keep the existing stall guard alive while awaiting AA.
                                    }
                                    done.await()
                                } ?: throw TransportException("MXW01 did not signal completion; inspect the paper before retrying")
                            } finally {
                                waiting.remove(Mxw01Protocol.DONE, done)
                            }
                            rows = Mxw01Spool.readRows(input)
                        }
                        require(input.read() == -1) { "Unexpected data after MXW01 spool end" }
                        sent
                    } finally {
                        reader.cancelAndJoin()
                    }
                }
            }
        } catch (failure: Throwable) {
            link.close() // Includes cancellation. Never replay a possibly printed transaction.
            throw failure
        }
    }
}
