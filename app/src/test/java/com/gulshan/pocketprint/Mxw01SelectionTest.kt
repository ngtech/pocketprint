package com.gulshan.pocketprint

import com.gulshan.pocketprint.discovery.BluetoothDiscovery
import com.gulshan.pocketprint.model.*
import com.gulshan.pocketprint.print.supportsAutoSetup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class Mxw01SelectionTest {
    @Test fun manualAddressUsesBleAndContinuousStockWithoutProbing() {
        val printer = mxw01Printer("aa:bb:cc:dd:ee:ff")
        assertEquals("bt:AA:BB:CC:DD:EE:FF", printer.id)
        assertEquals(BtLink.BLE, (printer.address as PrinterAddress.Bluetooth).link)
        assertEquals(MediaSensing.CONTINUOUS, printer.stock.sensing)
        assertEquals(PrinterCapabilities.MXW01, printer.capabilities)
        assertTrue(printer.saved)
        for (mac in listOf("", "AA:BB", "zz:bb:cc:dd:ee:ff", "aa:bb:cc:dd:ee:ff:00")) {
            assertFalse(isBluetoothMac(mac))
            assertThrows(IllegalArgumentException::class.java) { mxw01Printer(mac) }
        }
    }
    @Test fun onlyExactAdvertisedNameSelectsMxw01() {
        for (name in listOf("MXW01", "mxw01", " MXW01 ")) {
            assertEquals(PrinterCapabilities.MXW01, BluetoothDiscovery.guessCapabilities(name))
            assertTrue(BluetoothDiscovery.looksLikePrinter(name))
        }
        for (name in listOf("MXW01-Pro", "MXW02", "V5X", "Cat")) {
            assertNotEquals(PrintLanguage.MXW01, BluetoothDiscovery.guessCapabilities(name).preferredLanguage())
        }
    }

    @Test fun existingDialectSelectionIsPreserved() {
        assertEquals(PrintLanguage.TSPL, BluetoothDiscovery.guessCapabilities("TSC TE200").preferredLanguage())
        assertEquals(PrintLanguage.ZPL, BluetoothDiscovery.guessCapabilities("Zebra ZD").preferredLanguage())
        assertEquals(PrintLanguage.ESC_POS, BluetoothDiscovery.guessCapabilities("Printer 58").preferredLanguage())
        assertEquals(384, BluetoothDiscovery.guessCapabilities("Printer 58").rasterWidthDots)
        assertEquals(PrintLanguage.PDF, PrinterCapabilities.UNKNOWN_NETWORK.preferredLanguage())
    }

    @Test fun capabilitiesDescribeFixedMonochromeRollAndSkipAlienProbes() {
        val printer = Printer("bt:test", "MXW01", PrinterAddress.Bluetooth("00:11:22:33:44:55"), capabilities = PrinterCapabilities.MXW01)
        assertEquals(listOf(PrintLanguage.MXW01), printer.capabilities.languages)
        assertEquals(384, printer.capabilities.rasterWidthDots)
        assertFalse(printer.capabilities.supportsColor)
        assertFalse(printer.capabilities.supportsDuplex)
        assertEquals(MediaSize.MXW01_ROLL, printer.capabilities.mediaSizes.first())
        assertTrue(printer.capabilities.mediaSizes.contains(MediaSize.A4))
        assertFalse(printer.supportsAutoSetup())
    }

    @Test fun settingsPersistAndOldRecordsAcquireSafeDefaults() {
        val printer = Printer("test", "MXW01", PrinterAddress.Bluetooth("00:11:22:33:44:55"), mxw01 = Mxw01Settings(intensity = 120, scalePercent = 75, trimBlank = true))
        assertEquals(printer, Json.decodeFromString<Printer>(Json.encodeToString(printer)))
        val old = """{"id":"old","displayName":"Printer","address":{"type":"bluetooth","mac":"00:11:22:33:44:55"}}"""
        assertEquals(Mxw01Settings(), Json.decodeFromString<Printer>(old).mxw01)
        assertEquals(printer.mxw01, printer.copy(mxw01 = Mxw01Settings()).keepingSettingsOf(printer).mxw01)
    }
}
