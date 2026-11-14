# em10 app

Replacement for the vendor's desktop software for the Olympus OM-D E-M10 Mark I. Kotlin Multiplatform;
the shared code is in `src/commonMain`, and for now there is one target, a JVM command line. Plan:
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).

## Milestone 1 (done): firmware file handling
- `em10 check` asks the vendor's update server for the latest firmware. The request contains the
  model code and placeholder identifiers only.
- `em10 download [DIR]` downloads it and verifies size, container structure, block checksums and model.
- `em10 verify FILE` verifies a file you already have.
- `em10 card FILE CARD_ROOT` copies the official 1.4 file to `DCOLYMP/E1139999.BIN` on a mounted card
  and reads it back. It refuses any file that is not the unmodified vendor firmware.

None of this talks to the camera. Installing from the card happens on the camera and has not been
tested by this project; see `docs/firmware/camera-fw-update.md`.

## Milestone 4 (started): the camera's Wi-Fi interface
`em10 cam ...` talks to the camera over its own Wi-Fi network (join it first; the camera is at
192.168.0.10). Read-only: `info`, `ls [DIR] [-r]`, `get PATH [DEST]`, `thumb PATH [DEST]`, `props`,
`lens`. Changing the camera's state: `mode play|rec|shutter [--lv SIZE]`, `set NAME VALUE`,
`shoot [--save DIR] [--raw] [--no-full]`. `shoot --save` finds the new picture on the card and saves
its thumbnail at once, then the full JPEG (about 28 s over Wi-Fi), with `--raw` also the ORF.
Protocol: [`../docs/usb-protocol/wifi-api.md`](../docs/usb-protocol/wifi-api.md). Tested against a
fake camera and `tools/emcam_mock.py`; not yet run on the camera.

## Build and run
```
./gradlew build                 # compile and run the tests
./gradlew installJvmDist        # build/install/em10-jvm/bin/em10
build/install/em10-jvm/bin/em10 check
```
The wrapper downloads Gradle 9.8.2 (checksum pinned) on first use; Kotlin 2.4.20 comes with the plugin.
Built and tested on Java 27, where Kotlin compiles for a Java 26 target and says so in a warning.

## Layout
- `src/commonMain/kotlin/em10/core/` — `Container.kt` (container reader, same format as
  `tools/omfw.py`), `UpdateServer.kt` (server client over an `HttpTransport` interface),
  `Firmware.kt` (checks, card path).
- `src/commonMain/kotlin/em10/camera/` — `WifiCamera.kt` (the camera's commands), `CameraData.kt`
  (reply parsers), `Tethering.kt` (shoot and save, issue C5), `CameraHttp.kt` (transport interface).
- `src/jvmMain/` — `JvmHttp.kt` (java.net.http transport), `JvmCameraHttp.kt` (camera transport on
  HttpURLConnection, folder store), SHA-256, and the command line (`Main.kt`, `CameraCli.kt`).
- `src/commonTest/` — tests that need no network and no firmware file.
