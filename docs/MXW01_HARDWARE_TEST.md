# MXW01 hardware acceptance checklist

Status: **limited hardware validation reported by the user on 2026-11-18**.
The user confirmed that the supplied PocketPrint APK physically printed both
its test page and a print from Gmail on their MXW01. This records the user's
report; automated/emulator tests remain separate evidence.

Supplied implementation commit: `75880e41846f62b27593feb8dac2b2e96a0d8791`.
Supplied APK SHA-256: `9bd3e23e3fde4c828d20dbbe181510088cab4a56a3821ba7ec7eef609e5d222f`.
Phone model, Android version, firmware, discovery route, negotiated MTU and
printer-setting values were not recorded. Only the confirmed items are checked.

Strip mode, both directions and page guides were added after these reported tests and remain physically
unvalidated. The implementation/APK identity above records the earlier tested build.

Record Android version/device, printer label and advertised name, firmware if
known, APK SHA-256, git commit, negotiated MTU, paper width, intensity, dithering,
scale, margins, trimming and spacing. Close any other BLE connection first.

- [x] Install the supplied PocketPrint debug APK (user-reported).
- [ ] Discover MXW01 through Android's device picker, or exercise manual address entry.
- [ ] Save it, reopen PocketPrint, and confirm settings and printer persist.
- [ ] Enable the PocketPrint print service and find MXW01 in Android's Print dialog.
- [x] Physically print PocketPrint's test page (user-reported).
- [ ] Inspect both borders, fine rules and all text for completeness.
- [x] Physically print from Gmail (user-reported).
- [ ] Print from Chrome using its native Print action.
- [ ] Print from a PDF viewer using its native Print action.
- [ ] Enable vertical-strip mode with three strips and print an A5/A4 document; check left-to-right order and complete edge content.
- [ ] Select horizontal strips; check top-to-bottom order and turn each piece clockwise to assemble.
- [ ] Enable original-page guides in both directions; check the assembled frame follows only the four original page edges, with no borders at internal cuts.
- [ ] Align matching ticks at both edges of every overlap; check that guides do not cover document content.
- [ ] Toggle overlap on and off; check matching join content and assembly alignment.
- [ ] Turn guides off and enable blank-edge trimming on a page whose columns start/end at different heights; check that all strips still align.
- [ ] Reopen printer settings and confirm the split/direction/count/overlap/guides choices persist.
- [ ] Turn strip mode off and confirm normal whole-page printing resumes.
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
