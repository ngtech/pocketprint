# MXW01 native Android printing (experimental)

PocketPrint can register a **384-dot MXW01 BLE thermal printer** with Android's
Print Framework. Chrome, Gmail and PDF viewers that expose Android's Print menu
can submit PDFs to the same queue and rendering pipeline as other PocketPrint
printers. This backend is for the exact advertised name `MXW01`; AE30 alone does
not identify a model. MXW01 is a proprietary protocol, not ESC/POS, TSPL or ZPL.

## Hardware validation

On 2026-11-18, the user reported successful physical printing of PocketPrint's
test page and a print from Gmail on their MXW01 using the supplied debug APK.
The tested implementation is commit `75880e41846f62b27593feb8dac2b2e96a0d8791`.
This confirms those two reported print cases; Chrome, PDF viewers, long rolls,
cancellation and the other hardware checks still need individual validation.
See the [hardware checklist](MXW01_HARDWARE_TEST.md) for the APK identity and scope.

## Install and set up

Build with JDK 17, Android SDK platform 36 and the project's Gradle wrapper:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug application ID is `com.gulshan.pocketprint.debug`; it can be installed
alongside the official release, which has a different application ID. Debug
signing is for development, not a published release authenticity guarantee.

1. Turn the MXW01 on and close any browser/app currently connected to it.
2. Open PocketPrint and use its Bluetooth device picker to select **MXW01**.
   Android 8+ devices with Companion Device Setup discover BLE advertisements
   through Android's picker. Allow Bluetooth access when requested on Android 12+.
   The device is saved as MXW01 without sending generic printer-language probes.
3. If the picker is unavailable (including Android 7.0/7.1), use **Add printer →
   MXW01 (BLE)** and enter the printer's Bluetooth MAC address. An address is
   required for this fallback; there is no in-app advertisement scan on those
   systems. A previously paired printer named MXW01 is also recognised in the
   Bluetooth list. Earlier Android versions use the existing install-time
   Bluetooth permissions; no new scanning/location permission is introduced.
4. Open the printer's settings, adjust intensity/dithering/margins if desired,
   and press **Print test page**. Inspect the paper yourself.
5. Use the app's printing-settings button and enable the PocketPrint print
   service. Android settings paths vary by device, usually Connected devices →
   Printing. Select the saved MXW01 from another app's system Print dialog.

Connections open for a job and close afterwards. Settings show Disconnected,
Connecting or Connected for the active connection; disconnected between jobs is
normal. Android's print dialog remains selectable while Bluetooth is on and the
permission is available; it does not claim that an idle printer is in range.

## Continuous-roll output

- Width is always **384 dots**, monochrome, simplex. The reported nominal
  resolution is 203 dpi. The default layout medium represents approximately
  48 mm printable width on a continuous roll with a finite 297 mm layout page.
  Android needs finite media dimensions; the roll itself has no fixed length.
- A4, Letter and the existing conventional PDF media are available for app
  layouts. With strip splitting off, every received PDF page is fitted to the
  printable width, preserving its aspect ratio, regardless of the layout size.
- Scale is 25–100% of the width inside the chosen margins. It shrinks and centres
  the whole page; it does not crop it. Margins are 0–64 dots on each edge.
- Dithering is hard threshold or Floyd–Steinberg. Error diffusion continues
  across render bands. The main print screen's Dither-off setting forces hard
  threshold; the per-printer setting otherwise selects the algorithm.
- Optional trimming removes only leading/trailing completely white raster
  rows, preserving blank rows between content. It is off by default. It does
  not remove horizontal PDF margins; dark scanned backgrounds are not blank.
- Page spacing is 0–203 white rows between successive pages/copies. Copies and
  selected page ranges follow the existing print-job options.
- Firmware requires at least 90 rows (4320 bytes) per transfer. Short pages and
  completely blank pages are padded white and announce that padded height.
- A9's row count is 16-bit. Longer pages are split at 65,535 rows, with every row
  retained. A final segment shorter than 90 rows receives white padding. This
  can introduce a small additional feed at that boundary; it is not cropping.
  Multi-segment continuous feeding still needs hardware validation.
- PDF rendering uses bounded bitmap bands and a disk-backed page/spool file;
  page height does not dictate bitmap memory. Storage exhaustion is reported as
  an error, not handled by silently discarding content.
- The preview uses the same raster spool, including padding and margins. It
  shows the first selected PDF page, including all its strips in print order.
  If that output exceeds four million preview
  pixels, preview is declined rather than silently cropped. Printing still
  streams the complete page.

There are no cutter, gap-sensing, calibration, feed/retract, speed or battery
percentage controls for MXW01. Intensity is a protocol byte exposed only over
the documented/tested application range **0–150**, default 93 (`0x5D`).

### Split a large page into strips

Open the MXW01's **Printer settings**, turn on **Split pages into strips**,
choose **Vertical** or **Horizontal**, set **Strips per page** (2–8; default 3),
and save. For example,
an A5 page can become three strips instead of being reduced to a single narrow
image. The mode is off by default, including for previously saved printers.
In Android's print dialog, choose **A5** (or **A4**) as the document layout size
when printing a sheet of that size. PocketPrint splits the PDF layout it receives.

The separate **Overlap adjoining strips** toggle repeats approximately 2 mm
(16 dots) at each join to help align the pieces. Turn it off for adjoining
sections without repeated content. The complete page is resized proportionally
to the combined width. Strip count, scaling and margins determine its final
assembled size. Each strip then fits the printer's 384-dot head.

Vertical strips print **left to right**, one complete vertical section at a time.
Horizontal strips print **top to bottom**, with each section rotated
counterclockwise onto the narrow roll. Turn each printed piece **clockwise**
to assemble the original page. The complete page is fitted to the combined
strip width (horizontal mode uses the rotated page's width), preserving its
proportions. The next PDF page follows all sections of the previous one.
Copies repeat the document in that order. Page / strip
spacing inserts the selected number of white rows between sections; cut them
manually. No cutter commands are sent.
Trim the blank paper edges as needed when assembling the strips.

**Original page border and alignment ticks** is on by default in strip mode.
It draws one frame around the original A4/A5/PDF page before splitting, not a
box around each strip. Vertical sections share the original top/bottom lines;
only the outermost sections carry the original left/right lines. Horizontal
sections share the original left/right lines; only the first/last sections
carry the original top/bottom lines after rotating the pieces for assembly.

Ticks extend outward at both edges of every repeated overlap. Match those
ticks on neighboring pieces; the repeated image pixels also match exactly.
With overlap off, there are no overlap ticks. A 12-dot gutter (about 1.5 mm)
is reserved around the content for the one-dot frame and eight-dot ticks, so
guides never cover document content. Original blank page edges are preserved
while guides are on; blank-edge trimming is disabled in settings for that mode.
Turn guides off to use the previous borderless strips and optional trimming.

All strips share the same vertical scale and trim bounds, even if one is blank
or contains text at a different height. Blank strips are retained so the page
can be assembled correctly. Dithering is done once across the complete page
before extraction, so repeated overlap pixels match exactly. The existing
bounded render bands and disk spool are used for the wider page.

The built-in test page remains a single head-width diagnostic. Exercise strip
mode with an actual document and its preview. Physical strip assembly and
overlap alignment, page guides and horizontal strips still need testing on the printer; the earlier user-reported
test-page and Gmail results predate this mode.

## Protocol and architecture

`PrinterCapabilities.MXW01` and `PrintLanguage.MXW01` select this backend.
`BluetoothDiscovery` recognises only the exact name (case-insensitive, trimmed).
Explicit manual configuration is also possible. Existing ESC/POS, TSPL, ZPL,
IPP, raw-socket and USB routing remains in place.

`RenderPipeline → Mxw01RasterEncoder` uses the existing banded `PdfRasterizer`.
`Mxw01RowPacker` dithers one complete page row at a time. `Mxw01Raster` extracts
head-width rows: black = 1, leftmost pixel = bit 0, 48 bytes per row. Overlap
shares the same packed source pixels. The versioned **internal** spool stores a magic value and
height-prefixed raster blocks; these container headers never go to the printer.
`Mxw01Spool` pads/splits blocks. These classes do not depend on GATT.

`TransportFactory → Mxw01Transport → Mxw01Session` runs inside the existing
`PrinterQueue` and serial delivery/stall guard. It adapts the existing
`BleTransport`, adding explicit endpoints instead of guessing a serial pipe:

| Purpose | UUID (Bluetooth base UUID suffix omitted) | Operation |
|---|---|---|
| Service | `0000ae30` | Required service |
| Control | `0000ae01` | Write without response |
| Notifications | `0000ae02` | Notification with completed CCCD subscription |
| Raster | `0000ae03` | Write without response |

The full UUID suffix is `-0000-1000-8000-00805f9b34fb`. No AF30 alias is claimed
as verified. A service/characteristic mismatch fails without trying another
printer's command language.

Control framing is `22 21`, opcode, `00`, payload length u16 little-endian,
payload, CRC, `FF`. CRC covers only payload: polynomial `0x07`, initial zero,
no reflection, no xorout. The older reference's Dallas/Maxim label conflicts
with these numeric parameters; the implementation follows the parameters,
including the standard `123456789 → F4` check vector.

Session order: A2 intensity → A1 response → A9 acceptance (`00`) → AE03 raster →
AD flush → AA end notification, repeated per block. A9 carries padded row count,
`30 00` (48 bytes/row, 1bpp). Waiters are registered before commands/data that
can answer immediately. A9 and AA use separate waiters. Chunks are at most
`min(negotiated MTU - 3, 244)` bytes, with 25 ms pacing. Commands and data writes
share the GATT operation mutex. The session's transaction mutex prevents
interleaving. CCCD subscription completes before the first command.

Each request/write has a 10 s protocol timeout (the GATT operation timeout is
5 s); AA has 60 s after flush. The existing connect/retry logic is retained.
GATT failures, disconnects, timeouts and cancellation close the connection.
The app does not automatically replay a partially transferred job. Reprint only
after checking the paper. Cancellation stops further transmission and releases
the queue; it cannot retract paper already printed. The unverified AC command
is not sent.

The response decoder accepts complete declared payloads, including fragmented
or concatenated notifications, and ignores unknown trailer bytes. Public
references disagree about response CRC/footer details. A1/A9 must have nonempty
payloads; AA may be empty. Unsolicited replies are not retained for later
requests. A1 paper/temperature/battery offsets vary between firmware reports,
so they are not presented as reliable status. There are no wire transaction
IDs; reconnecting after ambiguity avoids reusing stale responses across jobs,
but arbitrary same-ID duplicates within a healthy connection cannot be fully
distinguished by the protocol.

AA is required before another block is sent. It is not an optical confirmation
of the paper. The existing `PrintResult.Sent` outcome is retained: BLE transfer
and a firmware notification do not claim a physically verified print. The
Android framework job can finish while PocketPrint's history still says Sent.

## Validation

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
# When an Android device/emulator is connected:
./gradlew :app:connectedDebugAndroidTest
```

The existing media-format tests assume English decimal separators. On a host
whose default JVM locale uses decimal commas, run the test invocation with
`JAVA_TOOL_OPTIONS='-Duser.language=en -Duser.country=US'`. This controls the
test JVM, not the installed app's language. The same four locale failures were
reproduced on unchanged upstream. The shared stall guard also starts its
watcher undispatched, fixing an upstream cancellation race in which a blocked
write could outlive cancellation before the watcher had started. The original
serial-delivery and stall-guard tests exercise that path.

Unit tests cover wire vectors, notification parsing, bit packing, transparency,
dithering, scaling geometry, minimum padding, multiple pages, 70,000-row output,
mock BLE sequencing, rejection, timeouts, disconnects, cancellation, transaction
serialization, saved settings and existing protocol selection. Instrumented
tests exercise actual PDF pages, page ranges/copies, blank-edge trimming, long
pages, preview pixels and empty selections. See [the hardware checklist](MXW01_HARDWARE_TEST.md).

## Revisions, references and licensing

- PocketPrint base: `0d9a03760e5f292fb5309bc7e5526870cf08ab45`, Apache-2.0.
- Primary wire-format reference: [MXW01-catprinter PROTOCOL.md](https://github.com/jeremy46231/MXW01-catprinter/blob/0744587459fbe9644b4a2295c9a3ecb06b12d5cc/PROTOCOL.md),
  revision `0744587459fbe9644b4a2295c9a3ecb06b12d5cc`, MIT.
- Comparison: [MeowPrinter PR 28](https://github.com/thiagokokada/MeowPrinter/pull/28),
  contributor branch `davidms5/MeowPrinter:feature/mxw01-support`, head
  `10ededdbe1e3cce8d752d3ff7fc8a14e6ed58461`. Actual protocol, GATT and session
  source and hardware notes were inspected. This project is GPL-3.0; none of its
  implementation source has been copied, translated or vendored into PocketPrint.
- Independent comparison: [mxw01-thermal-printer](https://github.com/clementvp/mxw01-thermal-printer/tree/ead9a022f0de96b96844ea0e8737bbfdda0518ef),
  including its protocol/printer source, revision
  `ead9a022f0de96b96844ea0e8737bbfdda0518ef`. It is a reference, not a dependency.
- User-provided hardware evidence: [Brutal Print](https://print.nathanredblur.dev/)
  printed successfully on the user's unit over Web Bluetooth. That is not a
  PocketPrint Android hardware test.

The new implementation was independently written against the documented wire
format, with reference implementations used to compare protocol facts and
sequencing. This is not a claim that the author never read GPL source or a
legal determination about derivative works. PocketPrint's Apache-2.0 licence
and existing notices remain intact; no GPL library or source is included.
The MIT protocol reference's notice is preserved in [MXW01_REFERENCE_LICENSE.txt](MXW01_REFERENCE_LICENSE.txt).
