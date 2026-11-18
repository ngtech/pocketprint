package com.gulshan.pocketprint.model

import kotlinx.serialization.Serializable

@Serializable
enum class Mxw01Dither { THRESHOLD, FLOYD_STEINBERG }

@Serializable
enum class Mxw01StripDirection { VERTICAL, HORIZONTAL }

/** MXW01 uses a continuous roll and a fixed 384-dot head. Values are wire units. */
@Serializable
data class Mxw01Settings(
    val intensity: Int = 93,
    val dither: Mxw01Dither = Mxw01Dither.FLOYD_STEINBERG,
    val scalePercent: Int = 100,
    val marginDots: Int = 8,
    val trimBlank: Boolean = false,
    val pageSpacingDots: Int = 16,
    val splitIntoStrips: Boolean = false,
    val stripCount: Int = 3,
    val stripOverlap: Boolean = true,
    val stripDirection: Mxw01StripDirection = Mxw01StripDirection.VERTICAL,
    val stripGuides: Boolean = true,
) {
    fun validated() = copy(
        intensity = intensity.coerceIn(0, 150),
        scalePercent = scalePercent.coerceIn(25, 100),
        marginDots = marginDots.coerceIn(0, 64),
        pageSpacingDots = pageSpacingDots.coerceIn(0, 203),
        stripCount = stripCount.coerceIn(2, 8),
    )
}

fun isBluetoothMac(value: String): Boolean = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(value.trim())

/** Explicit model selection for devices without Android's companion-device picker. */
fun mxw01Printer(mac: String, name: String = "MXW01"): Printer {
    require(isBluetoothMac(mac)) { "Enter a Bluetooth address such as AA:BB:CC:DD:EE:FF" }
    val address = mac.trim().uppercase()
    return Printer(
        id = "bt:$address",
        displayName = name.ifBlank { "MXW01" },
        address = PrinterAddress.Bluetooth(address, link = BtLink.BLE),
        makeAndModel = "MXW01",
        capabilities = PrinterCapabilities.MXW01,
        stock = LabelStock(sensing = MediaSensing.CONTINUOUS, gapMm = 0f),
        saved = true,
    )
}
