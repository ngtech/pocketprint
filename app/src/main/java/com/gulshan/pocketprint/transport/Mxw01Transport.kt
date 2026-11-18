package com.gulshan.pocketprint.transport

import android.content.Context
import com.gulshan.pocketprint.model.Mxw01Settings
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.mxw01.Mxw01Link
import com.gulshan.pocketprint.mxw01.Mxw01Session
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Mxw01Connection { DISCONNECTED, CONNECTING, CONNECTED }

object Mxw01Connections {
    private val states = ConcurrentHashMap<String, MutableStateFlow<Mxw01Connection>>()
    private fun state(mac: String) = states.getOrPut(mac.uppercase()) { MutableStateFlow(Mxw01Connection.DISCONNECTED) }
    fun observe(mac: String): StateFlow<Mxw01Connection> = state(mac)
    internal fun set(mac: String, value: Mxw01Connection) { state(mac).value = value }
}

/** Wraps the existing GATT driver; does not duplicate connection/retry logic. */
private class AndroidMxw01Link(context: Context, private val address: PrinterAddress.Bluetooth) : Mxw01Link {
    private val inbox = Channel<ByteArray>(32)
    private val ble = BleTransport(
        context.applicationContext, address,
        maxChunkBytes = 244,
        endpointIds = BleEndpointIds(uuid("ae30"), uuid("ae01"), uuid("ae02"), uuid("ae03")),
        onNotification = { bytes ->
            if (!inbox.trySend(bytes).isSuccess) {
                inbox.close(TransportException("MXW01 notification queue overflow"))
            }
        },
        onDisconnected = {
            Mxw01Connections.set(address.mac, Mxw01Connection.DISCONNECTED)
            inbox.close(TransportException("MXW01 disconnected; reconnect before retrying"))
        },
    )
    override val dataChunkBytes get() = ble.payloadBytes
    override suspend fun open() {
        Mxw01Connections.set(address.mac, Mxw01Connection.CONNECTING)
        try {
            ble.open()
            Mxw01Connections.set(address.mac, Mxw01Connection.CONNECTED)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    override suspend fun control(bytes: ByteArray) = ble.writePacket(bytes)
    override suspend fun raster(bytes: ByteArray) = ble.writePacket(bytes, raster = true)
    override suspend fun receive(): ByteArray = inbox.receive()
    override fun close() = ble.close()

    companion object {
        private fun uuid(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    }
}

/** Accepts only RenderPipeline's versioned raster spool, never raw ESC/POS/TSPL. */
class Mxw01Transport private constructor(
    private val link: Mxw01Link,
    settings: Mxw01Settings,
    override val description: String,
) : PrinterTransport {
    constructor(context: Context, address: PrinterAddress.Bluetooth, settings: Mxw01Settings) : this(
        AndroidMxw01Link(context, address), settings, "mxw01-ble://${address.mac}",
    )
    private val session = Mxw01Session(link, settings.validated().intensity)
    override suspend fun open() = link.open()
    override suspend fun write(source: InputStream, onProgress: (Long) -> Unit): Long = session.transmit(source, onProgress)
    override suspend fun finish() = Unit // AA was awaited for every transfer, including the final one.
    override fun close() = link.close()
}
