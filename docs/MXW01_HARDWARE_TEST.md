# MXW01 hardware acceptance checklist

Status: **not yet physically validated with this PocketPrint APK**.
Fill this in after installing the build; passing automated tests does not tick
any hardware item below.

Record Android version/device, printer label and advertised name, firmware if
known, APK SHA-256, git commit, negotiated MTU, paper width, intensity, dithering,
scale, margins, trimming and spacing. Close any other BLE connection first.

- [ ] Install debug APK; open the app and grant Bluetooth access when requested.
- [ ] Discover MXW01 through Android's device picker, or exercise manual address entry.
- [ ] Save it, reopen PocketPrint, and confirm settings and printer persist.
- [ ] Enable the PocketPrint print service and find MXW01 in Android's Print dialog.
- [ ] Print the test page; confirm both borders, fine rules and text are complete.
- [ ] Print from Chrome, Gmail and a PDF viewer using their native Print actions.
- [ ] Print A4 and Letter PDFs with content at all four edges; compare against preview.
- [ ] Check grayscale/photo dithering and hard threshold independently.
- [ ] Compare intensity 73, 93, 111 and 150; record results rather than assuming equal thermal response.
- [ ] Test 25%, 50%, 100% scaling and nonzero margins; confirm nothing is silently cropped.
- [ ] Print two pages and copies; check order and configured white spacing.
- [ ] Compare trimming on/off on pages with blank edges and a large internal blank region.
- [ ] Print an empty/short page near 90 rows; check minimum white padding/feed.
- [ ] Print a long image/PDF; watch for buffer overrun, missing/repeated rows or excess feed.
- [ ] Test a page across the 65,535-row segment boundary if practical; capture its result separately.
- [ ] Cancel while waiting, transferring, and after flush; check that later jobs reconnect and work.
- [ ] Turn the printer off during transfer; check for a failure and no automatic duplicate retry.
- [ ] Try out-of-paper/overheated conditions; record actual firmware behaviour and notifications.
- [ ] Start two jobs quickly; verify serial order and that their raster streams do not interleave.
- [ ] Check connection status while connecting/printing and after disconnecting.
- [ ] Confirm history says Sent rather than asserting an unobserved physical print.
- [ ] Regression-check an existing ESC/POS, TSPL/ZPL, USB or IPP printer available to you.

Capture diagnostic logs while reproducing a failure:

```sh
adb logcat -c
adb logcat -s BleTransport PrintEngine PocketPrintService
```

Also copy the printer report from settings. It includes document names: review
it before sharing. Record what physically came out, the expected output and the
last successful step. Inspect the roll before pressing Reprint to avoid duplicates.
