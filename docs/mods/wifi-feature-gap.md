# Wi-Fi features: what the E-M10 firmware holds, compared with the E-M1 (issue M0)

Question (ISSUES M0): does the E-M10 firmware contain Wi-Fi features that are present but switched
off, so that the parked ideas M1–M3 would be small switches? Answered on paper on 2026-11-14.

**Short answer: no.** The E-M10 firmware holds no switched-off Wi-Fi commands. The E-M1's extra
features are separate code that the E-M10 lacks. That code is small for previews and properties and
larger for movies, which also needs work on the camera side. Three small read-only things turned up
that the client can use without any change to the firmware:

- `checklens`, a command the camera answers but does not advertise;
- the list of information items carried by the live-view stream;
- a built-in web gallery for a phone browser.

## Sources

| source | what it gives |
|---|---|
| E-M10 firmware 1.4, block 0 (`firmware/stock/dec/blk00_40300000.bin`, runtime base `0x7d991020`) | the body in question |
| E-M1 firmware 4.6 (`OLY_E_109_4600_0000_0000.BIN`, fetched from the official update server with `tools/fetch-firmware.sh E109 --download` on 2026-11-14, sha256 `0fa4b14c…b21339`; decoded to the ignored `firmware/stock/dec-em1/`) | the sister model with the larger Wi-Fi API (oishare 2.60). Same container and CPU. Block 0 runs at **`0x6daf1020`**; found by matching code constants against the offsets of the CGI-name strings (44 hits, next best 21). `tools/dis.py … --blk firmware/stock/dec-em1/blk00_40300000.bin --base 6daf1020` disassembles it |
| OI.Share 4.6.0 command strings (`../usb-protocol/wifi-cgi-endpoints-oishare-4.6.0.txt`) | every command the phone app knows, across all camera generations |

Method: every CGI name, `com=` sub-command, `mode=` value and property name from the app was searched
for as a whole string in both firmwares. Every hit was followed to the table or code that refers
to it. The Wi-Fi tables of both bodies were then dumped and compared entry by entry.

## Result

| feature | E-M10 1.4 | E-M1 4.6 | what an E-M10 modification would need |
|---|---|---|---|
| The 23 advertised CGIs | present, all reachable | present | – |
| Other app commands (settings backup `*myset*`, camera log, firmware update `fwup_*`, movie streaming, `exec_erase`, `get_exif`, `set_timeout`, `exec_reboot`, `switch_cammode` `maintenance`/`cameralog`, …; about 60 names) | **absent**: no string, no handler | absent too | entirely new code. Not "switched off" |
| `exec_takemisc` `checklens` | **present and reachable, not advertised** | present, also not advertised | nothing: usable today (below) |
| `get_screennail.cgi` (screen-size preview) | absent | handler `0x6e5dad25`, in two connect-mode tables | a new Wi-Fi handler on the model of `get_thumbnail`. The E-M10's file layer already knows screen-size preview images (`IMAGE_DATA_SCREENNAIL`, `AvmSetScreennailInfo`), so no camera-side work is expected |
| Movie start and stop (`exec_takemotion` `startmovietake` / `stopmovietake`) | absent | keywords 5 and 6 of a 7-entry table; a fifth take phase "movie" in the state table at `0x6e7772e8`; posts the new app events **`0x41b002d`** (start) and **`0x41b002e`** (stop), received in the camera task near `0x6e3426xx` | new code on **both** sides: the Wi-Fi handler and table entries, plus a receiver in the camera task. No code in E-M10 block 0 uses any event id from `0x41b002a` to `0x41b003f`. The largest of the three |
| More remote properties | 14 | 34 (below) | Wi-Fi-side code only: a table entry, get/desc (and set) handlers and a value formatter per property. See "Properties" |
| Service and debug CGIs | absent | four extra names in the E-M1's connect-mode tables, not in its command list | not examined, and left out on purpose |

### Properties

The E-M10 has one hand-written handler per property and operation (table at `0x7e7816b0`, 16-byte
records `{name, get, desc, set}`). The property number used inside the camera is a constant in each
handler. The E-M1 replaced this with a table of 24-byte records at `0x6e776d4c`:
`{name, property number, get, desc, set, check}`. The sixth column is `get_camprop` `com=check`; the
E-M1's command list gives `check` for exactly those properties. Most rows point at one shared
get/desc/set handler that works for any property number.

The property numbers that both bodies share are identical:

| property | number |
|---|---|
| expcomp | 2 |
| focalvalue | 3 |
| shutspeedvalue | 4 |
| isospeedvalue | 6 |
| wbvalue | 9 |
| artfilter | `0x118` |
| drivemode | `0x81b` |

So both bodies use one numbering of camera settings.

The E-M10's camera-side receiver does not limit which numbers it accepts. It is `0x7e0982cc` for
set and `0x7e098331` for the value list. Both take the number from the command block at `0xa0a925c0`
and pass it to generic property functions (`0x7db67d13` count, `0x7db67cda`/`0x7db67cf8` value at an
index). Only aperture (3) and shutter speed (4) get special handling. So adding a property to the
E-M10's Wi-Fi API is work on the Wi-Fi side only.

The open question is whether each E-M1 number means the same setting on the E-M10. The shared seven
suggest it does, but that is not proven, and some E-M1 settings (movie formats, silent shutter) have
no counterpart on the E-M10.

E-M1 properties that the E-M10 lacks, with their numbers:

| property | number | on the E-M1 |
|---|---|---|
| `colortone` | `0x6f` | get, desc, set |
| `exposemovie` | `0xa2` | get, desc, set |
| `colorphase` | `0x297` | get, desc, set |
| `cameradrivemode` | own handlers | get, desc |
| `QualityMovie2` | `0x2bf` | get, desc |
| `PixelSet1`–`4` | `0x2c1`–`0x2c4` | get, desc |
| `CompSet1`–`4` | `0x2c9`–`0x2cc` | get, desc, check |
| `FrameRateSet1`–`4` | `0x2d9`–`0x2dc` | get, desc, check |
| `NoiseReductionExposureTime` | `0x288` | get, desc |
| `SilentNoiseReduction` | `0x302` | get, desc |
| `SilentTime` | own handlers | get, desc |

The E-M1 also gives property numbers for three of the E-M10's read-only properties:
`noisereduction` `0x23`, `digitaltelecon` `0x22` and `bulbtimelimit` `0xad`.

Correction to an earlier note in `../firmware/wifi-cgi.md`: the E-M10's value-formatter switch has
a case for property number 10032 (`0x2730`). That case is not a hidden property. Its formatter
`0x7e7124e9` only returns an empty string.

## Usable today, without changing the firmware

### `exec_takemisc.cgi?com=checklens`

The E-M10's `exec_takemisc` table at `0x7e7815d8` has six entries. The command list advertises five:
`startliveview`, `stopliveview`, `getrecview`, `getlastjpg` and `ctrlzoom`. The sixth, `checklens`,
has the handler `0x7e70c758`. Like the other rec-mode commands, it works only in rec mode.

The handler does two things:

- reads whether a lens is attached (`api_rec_Get_LensConnection` `0x7db9eb52`);
- posts app event `0x41b001a`, the event the `ctrlzoom` handler uses for the power zoom.

It then replies (builder `0x7e70ee0e`):

```
<?xml version="1.0"?><response><lensmount>ok|ng</lensmount><electrically-driven>ok|ng</electrically-driven></response>
```

So the client can tell whether a lens is attached and whether it has a power zoom, which is useful
before offering `ctrlzoom` (C16). The command is read-only. Confirmed on the camera on 2026-11-14
(`emcam.py livetest` step 2): `lensmount` `ok`, `electrically-driven` `ng` with a lens without power zoom.

### Live-view information list (`startliveview` reply)

The `startliveview` handlers reply with a fixed XML list naming the information items that the
live-view stream carries.

In rec mode (template `0x7e781790`, sent from `0x7e70ecab`):

```
framesize afframeinfo mntmediainfo rotation maxtakenum temperror battempty shutspeedvalue focalvalue
expcomp wbvalue isospeedvalue contnotstop aspectratio liveaddinfo expphotowarning focusinfo zoominfo
```

In shutter mode (`0x7e780c4c`, from `0x7e70a595`): `framesize mntmediainfo rotation`.

The E-M1 4.6 list adds `maxmovietime nrimage recmovietime`.

Consequences:

- The stream carries a **battery-empty flag** (`battempty`), a temperature warning and the number of
  shots left (`maxtakenum`). It carries no battery level, on either body. M4 therefore stays
  new code, but a "battery empty" warning needs no modification.
- The position in the list is the extension-entry type in the stream: the live test on 2026-11-14
  saw types 1–18 except 13 and 15, with word counts that fit (table in
  `../usb-protocol/liveview-udp.md`). `battempty` is type 7.

### Built-in web gallery

The camera's web server also serves plain HTML pages for a phone browser, without the app. The
pages are in block 0 at `0x7e774994`, `0x7e774b8b`, `0x7e775192` and `0x7e775b42`. The server code
around `0x7e6fe41d`–`0x7e6feb54` builds them, and three embedded GIF images sit in the Wi-Fi data
area under the folder name `WEB_PARTS`.

The pages offer:

- a `/DCIM` folder list;
- a thumbnail grid per folder (thumbnails through `get_thumbnail.cgi`);
- a single-picture page `FILE.html?<path>`.

They use the same `dir,name,size,attr,date,time` records as `get_imglist`. The script variable is
named `wlansd`, after the FlashAir card's web interface. Which connect modes serve the pages is not
checked. Opening `http://192.168.0.10/DCIM` in a browser while joined to the camera answers that,
and it is read-only.

## Not part of the camera's Wi-Fi API

The API classes `InternalWiFi`, `FlashAir` and `CameraCommon::getEyeFiStatus` appeared in the trace
catalog (`../firmware/trace-catalog.md`). `InternalWiFi` is the on-camera Wi-Fi state interface:

- connect, disconnect and password change;
- notifications for remote shooting.

`FlashAir` (12 methods with code) and the `eyefi_*` routines support Wi-Fi SD cards in the card
slot. None of them adds HTTP commands. Whether the E-M10's menus offer FlashAir settings was not
checked.

## Effect on the issue list

- M0 is done: there are no switch-only modifications.
- M2 (`get_screennail`) and M3 (more properties) are Wi-Fi-side code of moderate size, with the E-M1
  as a model.
- M1 (movie start and stop) also needs a camera-task receiver, so it is the larger job.
- The three read-only findings become client issues C21–C23.
