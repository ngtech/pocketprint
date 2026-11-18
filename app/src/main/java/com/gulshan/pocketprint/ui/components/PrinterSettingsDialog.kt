package com.gulshan.pocketprint.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.model.BtLink
import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.ui.MediaSizeSaver
import com.gulshan.pocketprint.ui.enumSaver
import com.gulshan.pocketprint.ipp.PrinterTrust
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.linkAfterSettings
import com.gulshan.pocketprint.transport.TscSdk
import com.gulshan.pocketprint.model.Mxw01Dither
import com.gulshan.pocketprint.model.Mxw01StripDirection
import com.gulshan.pocketprint.model.PrinterCapabilities
import com.gulshan.pocketprint.transport.Mxw01Connection
import com.gulshan.pocketprint.transport.Mxw01Connections

/**
 * Lets the user correct what the app guessed about a printer.
 *
 * The command dialect for a Bluetooth printer is inferred from its advertised
 * name, which is a heuristic that will always be wrong for some models. Without
 * a way to override it, a mis-guessed printer is permanently unusable: it will
 * happily accept the bytes and print pages of literal command text.
 */
@Composable
fun PrinterSettingsDialog(
    printer: Printer,
    onDismiss: () -> Unit,
    onSave: (Printer) -> Unit,
    onTestPage: (Printer) -> Unit,
    onCopyReport: () -> Unit,
    onCalibrate: (Printer) -> Unit,
    onCheckCertificate: () -> Unit = {},
) {
    // Needed for the ChipRow labels, which are plain lambdas rather than
    // composable ones and so cannot call stringResource.
    val context = LocalContext.current

    var name by rememberSaveable { mutableStateOf(printer.displayName) }
    var language by rememberSaveable(stateSaver = enumSaver(PrintLanguage.entries.toList())) {
        mutableStateOf(printer.capabilities.languages.firstOrNull() ?: PrintLanguage.ESC_POS)
    }
    var media by rememberSaveable(stateSaver = MediaSizeSaver) {
        mutableStateOf(printer.capabilities.mediaSizes.firstOrNull() ?: MediaSize.LABEL_4X6)
    }
    var dpi by rememberSaveable {
        mutableStateOf(printer.capabilities.resolutionsDpi.firstOrNull() ?: 203)
    }
    var widthDots by rememberSaveable {
        mutableStateOf((printer.capabilities.rasterWidthDots ?: 576).toString())
    }
    var exposeToSystem by rememberSaveable { mutableStateOf(printer.exposeToSystem) }
    val bluetoothAddress = printer.address as? PrinterAddress.Bluetooth
    var viaTscLibrary by rememberSaveable { mutableStateOf(bluetoothAddress?.link == BtLink.TSC_SDK) }
    var compressedBitmaps by rememberSaveable { mutableStateOf(printer.capabilities.compressedBitmaps) }

    // Sizes the user has typed in, kept alongside the built-in list. 50x30 and
    // 60x40 are two of the most common rolls on the market and neither is one
    // of the five sizes this app shipped with.
    var extraSizes by remember {
        mutableStateOf(printer.capabilities.mediaSizes.filter { it.isCustom })
    }
    var customWidth by rememberSaveable { mutableStateOf("") }
    var customHeight by rememberSaveable { mutableStateOf("") }

    var sensing by rememberSaveable(stateSaver = enumSaver(MediaSensing.entries.toList())) {
        mutableStateOf(printer.stock.sensing)
    }
    var gapMm by rememberSaveable { mutableStateOf(printer.stock.gapMm.toString()) }
    var darkness by rememberSaveable { mutableStateOf(printer.stock.darkness) }
    var speed by rememberSaveable { mutableStateOf(printer.stock.speedIps) }
    var trimBlank by rememberSaveable { mutableStateOf(printer.stock.trimBlank) }
    var mxwIntensity by rememberSaveable { mutableStateOf(printer.mxw01.intensity) }
    var mxwDither by rememberSaveable(stateSaver = enumSaver(Mxw01Dither.entries.toList())) {
        mutableStateOf(printer.mxw01.dither)
    }
    var mxwScale by rememberSaveable { mutableStateOf(printer.mxw01.scalePercent) }
    var mxwMargin by rememberSaveable { mutableStateOf(printer.mxw01.marginDots) }
    var mxwTrim by rememberSaveable { mutableStateOf(printer.mxw01.trimBlank) }
    var mxwSpacing by rememberSaveable { mutableStateOf(printer.mxw01.pageSpacingDots) }
    var mxwSplit by rememberSaveable { mutableStateOf(printer.mxw01.splitIntoStrips) }
    var mxwStripCount by rememberSaveable { mutableStateOf(printer.mxw01.stripCount) }
    var mxwStripOverlap by rememberSaveable { mutableStateOf(printer.mxw01.stripOverlap) }
    var mxwStripDirection by rememberSaveable(stateSaver = enumSaver(Mxw01StripDirection.entries.toList())) {
        mutableStateOf(printer.mxw01.stripDirection)
    }
    var mxwStripGuides by rememberSaveable { mutableStateOf(printer.mxw01.stripGuides) }
    val isMxw01 = language == PrintLanguage.MXW01

    val isLabelPrinter = language == PrintLanguage.TSPL || language == PrintLanguage.ZPL
    val sizes = (MediaSize.ALL + extraSizes).distinctBy { it.id }

    // A copy, not a new LabelStock: a setting this dialog does not know about
    // would otherwise be put back to its default every time it is saved.
    fun stock() = printer.stock.copy(
        sensing = sensing,
        gapMm = gapMm.toFloatOrNull()?.coerceIn(0f, 50f) ?: printer.stock.gapMm,
        darkness = darkness,
        speedIps = speed,
        trimBlank = trimBlank,
    )

    fun edited(): Printer = printer.copy(
        displayName = name.ifBlank { printer.displayName },
        exposeToSystem = exposeToSystem,
        address = if (bluetoothAddress == null) {
            printer.address
        } else {
            bluetoothAddress.copy(
                link = if (isMxw01) BtLink.BLE else linkAfterSettings(bluetoothAddress.link, viaTscLibrary, TscSdk.isBundled),
            )
        },
        stock = if (isMxw01) stock().copy(sensing = MediaSensing.CONTINUOUS, gapMm = 0f) else stock(),
        mxw01 = printer.mxw01.copy(
            intensity = mxwIntensity, dither = mxwDither, scalePercent = mxwScale,
            marginDots = mxwMargin, trimBlank = mxwTrim, pageSpacingDots = mxwSpacing,
            splitIntoStrips = mxwSplit, stripCount = mxwStripCount, stripOverlap = mxwStripOverlap,
            stripDirection = mxwStripDirection, stripGuides = mxwStripGuides,
        ).validated(),
        capabilities = if (isMxw01) PrinterCapabilities.MXW01 else printer.capabilities.copy(
            languages = listOf(language),
            mediaSizes = (listOf(media) + extraSizes + printer.capabilities.mediaSizes)
                .distinctBy { it.id },
            resolutionsDpi = listOf(dpi),
            rasterWidthDots = widthDots.toIntOrNull()?.takeIf { it > 0 },
            // Only meaningful for TSPL; cleared when the language is changed
            // away from it so it cannot come back on by surprise.
            compressedBitmaps = compressedBitmaps && language == PrintLanguage.TSPL,
        ),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.printer_settings_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.printer_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(stringResource(R.string.printer_language), style = MaterialTheme.typography.labelMedium)
                ChipRow(
                    items = listOfNotNull(
                        PrintLanguage.TSPL,
                        PrintLanguage.ZPL,
                        PrintLanguage.ESC_POS,
                        PrintLanguage.PCL,
                        PrintLanguage.PDF,
                        PrintLanguage.MXW01.takeIf { bluetoothAddress != null },
                    ),
                    selected = language,
                    label = {
                        when (it) {
                            PrintLanguage.TSPL -> "TSPL"
                            PrintLanguage.ZPL -> "ZPL"
                            PrintLanguage.ESC_POS -> "ESC/POS"
                            PrintLanguage.PCL -> "PCL"
                            PrintLanguage.MXW01 -> "MXW01"
                            else -> "PDF"
                        }
                    },
                    onSelect = { language = it },
                )
                Text(
                    stringResource(R.string.printer_language_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (isMxw01) {
                    val connection by Mxw01Connections.observe(bluetoothAddress?.mac.orEmpty()).collectAsState()
                    Text(stringResource(when (connection) {
                        Mxw01Connection.DISCONNECTED -> R.string.mxw01_disconnected
                        Mxw01Connection.CONNECTING -> R.string.mxw01_connecting
                        Mxw01Connection.CONNECTED -> R.string.mxw01_connected
                    }))
                    Text(stringResource(R.string.mxw01_roll_help), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.mxw01_split_strips), modifier = Modifier.weight(1f))
                        Switch(checked = mxwSplit, onCheckedChange = { mxwSplit = it })
                    }
                    if (mxwSplit) {
                        Text(stringResource(R.string.mxw01_strip_direction), style = MaterialTheme.typography.labelMedium)
                        ChipRow(
                            items = Mxw01StripDirection.entries.toList(), selected = mxwStripDirection,
                            label = { context.getString(if (it == Mxw01StripDirection.VERTICAL) R.string.mxw01_vertical else R.string.mxw01_horizontal) },
                            onSelect = { mxwStripDirection = it },
                        )
                        Text(
                            stringResource(if (mxwStripDirection == Mxw01StripDirection.VERTICAL) R.string.mxw01_strip_help else R.string.mxw01_horizontal_help),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Stepper(stringResource(R.string.mxw01_strip_count), mxwStripCount, 2..8) { mxwStripCount = it }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.mxw01_strip_overlap), modifier = Modifier.weight(1f))
                            Switch(checked = mxwStripOverlap, onCheckedChange = { mxwStripOverlap = it })
                        }
                        if (mxwStripOverlap) Text(stringResource(R.string.mxw01_strip_overlap_help), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.mxw01_strip_guides), modifier = Modifier.weight(1f))
                            Switch(checked = mxwStripGuides, onCheckedChange = { mxwStripGuides = it })
                        }
                        if (mxwStripGuides) Text(stringResource(R.string.mxw01_strip_guides_help), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.mxw01_intensity, mxwIntensity))
                    Slider(value = mxwIntensity.toFloat(), onValueChange = { mxwIntensity = it.toInt() }, valueRange = 0f..150f, steps = 149)
                    Text(stringResource(R.string.mxw01_dithering), style = MaterialTheme.typography.labelMedium)
                    ChipRow(
                        items = Mxw01Dither.entries.toList(), selected = mxwDither,
                        label = { context.getString(if (it == Mxw01Dither.THRESHOLD) R.string.mxw01_threshold else R.string.mxw01_floyd) },
                        onSelect = { mxwDither = it },
                    )
                    Text(stringResource(R.string.mxw01_scale, mxwScale))
                    Slider(value = mxwScale.toFloat(), onValueChange = { mxwScale = it.toInt() }, valueRange = 25f..100f, steps = 74)
                    Stepper(stringResource(R.string.mxw01_margin), mxwMargin, 0..64) { mxwMargin = it }
                    Stepper(stringResource(if (mxwSplit) R.string.mxw01_strip_spacing else R.string.mxw01_spacing), mxwSpacing, 0..203) { mxwSpacing = it }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.mxw01_trim), modifier = Modifier.weight(1f))
                        Switch(
                            checked = mxwTrim && !(mxwSplit && mxwStripGuides),
                            onCheckedChange = { mxwTrim = it }, enabled = !(mxwSplit && mxwStripGuides),
                        )
                    }
                    if (mxwSplit && mxwStripGuides) Text(stringResource(R.string.mxw01_guides_keep_edges), style = MaterialTheme.typography.bodySmall)
                }
                if (!isMxw01) {
                Text(stringResource(R.string.printer_default_stock), style = MaterialTheme.typography.labelMedium)
                ChipRow(
                    items = sizes,
                    selected = media,
                    label = { it.label },
                    onSelect = { media = it },
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = customWidth,
                        onValueChange = { customWidth = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text(stringResource(R.string.printer_custom_width)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = customHeight,
                        onValueChange = {
                            customHeight = it.filter { c -> c.isDigit() || c == '.' }
                        },
                        label = { Text(stringResource(R.string.printer_custom_height)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            val w = customWidth.toFloatOrNull()
                            val h = customHeight.toFloatOrNull()
                            if (w != null && h != null && w > 0f && h > 0f) {
                                val size = MediaSize.custom(w, h)
                                extraSizes = (extraSizes + size).distinctBy { it.id }
                                media = size
                                customWidth = ""
                                customHeight = ""
                            }
                        },
                    ) { Text(stringResource(R.string.printer_custom_add)) }
                }

                }
                if (isLabelPrinter) {
                    Text(stringResource(R.string.printer_stock_section), style = MaterialTheme.typography.labelMedium)
                    ChipRow(
                        items = MediaSensing.entries.toList(),
                        selected = sensing,
                        label = {
                            context.getString(
                                when (it) {
                                    MediaSensing.GAP -> R.string.printer_sensing_gap
                                    MediaSensing.BLACK_MARK -> R.string.printer_sensing_black_mark
                                    MediaSensing.CONTINUOUS -> R.string.printer_sensing_continuous
                                },
                            )
                        },
                        onSelect = { sensing = it },
                    )
                    Text(
                        stringResource(R.string.printer_sensing_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (sensing != MediaSensing.CONTINUOUS) {
                        OutlinedTextField(
                            value = gapMm,
                            onValueChange = { gapMm = it.filter { c -> c.isDigit() || c == '.' } },
                            label = {
                                Text(
                                    stringResource(
                                        if (sensing == MediaSensing.GAP) {
                                            R.string.printer_gap_height
                                        } else {
                                            R.string.printer_mark_height
                                        },
                                    ),
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Stepper(stringResource(R.string.printer_darkness), darkness, 0..15) { darkness = it }
                    Text(
                        stringResource(R.string.printer_darkness_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Stepper(stringResource(R.string.printer_speed), speed, 1..12) { speed = it }

                    // Trimming is done for TSPL only, and only continuous paper
                    // can be trimmed: gap and black-mark rolls are cut to a label.
                    if (language == PrintLanguage.TSPL && sensing == MediaSensing.CONTINUOUS) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                stringResource(R.string.printer_trim_blank),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = trimBlank, onCheckedChange = { trimBlank = it })
                        }
                        Text(
                            stringResource(R.string.printer_trim_blank_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (language == PrintLanguage.TSPL) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                stringResource(R.string.printer_compress_bitmaps),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = compressedBitmaps, onCheckedChange = { compressedBitmaps = it })
                        }
                        Text(
                            stringResource(R.string.printer_compress_bitmaps_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Offered by a build that has the library, and also to a printer
                        // already saved on it in one that does not, so it can be freed.
                        if (bluetoothAddress != null &&
                            (TscSdk.isBundled || bluetoothAddress.link == BtLink.TSC_SDK)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.printer_tsc_library),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(checked = viaTscLibrary, onCheckedChange = { viaTscLibrary = it })
                            }
                            Text(
                                stringResource(R.string.printer_tsc_library_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    TextButton(onClick = { onCalibrate(edited()) }) {
                        Text(stringResource(R.string.printer_calibrate))
                    }
                    Text(
                        stringResource(R.string.printer_calibrate_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!isMxw01) {
                Text(stringResource(R.string.printer_resolution), style = MaterialTheme.typography.labelMedium)
                ChipRow(
                    items = listOf(180, 203, 300, 600),
                    selected = dpi,
                    label = { context.getString(R.string.settings_dpi, it) },
                    onSelect = { dpi = it },
                )

                OutlinedTextField(
                    value = widthDots,
                    onValueChange = { widthDots = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.printer_head_width)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.printer_head_width_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Only an IPPS printer has a certificate to talk about. The pin
                // is shown so it can be checked against the printer's own
                // status page, which is the whole basis for trusting it.
                }
                (printer.address as? PrinterAddress.Ipp)?.takeIf { it.secure }?.let { ipps ->
                    Text(
                        stringResource(R.string.printer_certificate_section),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        ipps.certificateSha256?.let {
                            stringResource(R.string.printer_certificate_pinned, PrinterTrust.display(it))
                        } ?: stringResource(R.string.printer_certificate_not_pinned),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onCheckCertificate) {
                        Text(stringResource(R.string.printer_certificate_check))
                    }
                }

                TextButton(onClick = { onTestPage(edited()) }) {
                    Text(stringResource(R.string.printer_test_page))
                }

                // "It doesn't print" is not something anyone can act on. This
                // is: the dialect, the head width, the ink the rasteriser put
                // on the page, and the bytes that reached the printer.
                TextButton(onClick = onCopyReport) {
                    Text(stringResource(R.string.printer_copy_report))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(edited()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** A bounded integer with two buttons, for the handful of small numeric knobs. */
@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.printer_stepper, label, value),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { onChange((value - 1).coerceIn(range)) },
            enabled = value > range.first,
        ) { Text("-") }
        OutlinedButton(
            onClick = { onChange((value + 1).coerceIn(range)) },
            enabled = value < range.last,
        ) { Text("+") }
    }
}
