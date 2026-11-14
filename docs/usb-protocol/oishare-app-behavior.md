# OI.Share Android app — features and how it drives the Wi-Fi API

How Olympus's own app ("OLYMPUS Image Share" / OI.Share, pkg `jp.olympusimaging.oishare`,
v4.6.0) uses the camera's Wi-Fi HTTP CGI (`http://192.168.0.10/`). Reverse-engineered from a jadx
decompile of the APK (kept local in gitignored `research/oishare/`, interop RE of Olympus app code).
This is the reference the KMP replacement follows. Endpoint list: `wifi-cgi-endpoints-oishare-4.6.0.txt`.
Firmware side of the same API: `../firmware/wifi-cgi.md`. Live-view wire format: `liveview-udp.md`.

## What the app does (features)

- **Remote control** — two modes:
  - *Live View* (control): on-screen live view + shoot, touch AF, bulb / live-time / live-composite,
    movie record. UI label `IDS_REMOCON_MODE_CONTROL` = "Live View".
  - *Remote Shutter* (release): emulates a remote cable release, no live view, BLE variant too.
    `IDS_REMOCON_MODE_RELEASE` = "Remote Shutter". Not on all cameras.
  Shooting scenes exposed: Interval, Bulb, Live Composite, Focus Bracketing/Stacking, Movie, C1–C4.
- **Import / transfer** — browse card, thumbnails, resized or full download, movie trim-on-import.
  "Share Order" flow: camera marks files, app pulls the marked set then clears the marks.
  Automatic import via BLE wake (foreground service).
- **Camera settings backup ("Myset")** — save/edit/restore camera setting blocks to/from the phone;
  gated by camera model + firmware version.
- **Firmware update** — download body/lens/accessory firmware over the Internet, back up settings,
  push firmware to the camera over Wi-Fi, restore settings, reboot. (We stay out of this path.)
- **Geotag / GPS log** — records a GPS track locally, writes EXIF GPS into imported photos; sets the
  camera clock for photo↔track matching. Continuous logging delegated to the OI.Track app.
- **Connection setup** — QR easy-setup, Wi-Fi join, Bluetooth pairing, FlashAir SD.
- **Self-timer voice**, **survey/questionnaire**, **help/guide**. Photo editing (Art Filter, Color
  Creator, Photo Story) has been moved out to the OI.Palette app.
- Permissions: fine + background location, Wi-Fi/network change, Bluetooth/BLE, camera (QR scan),
  record-audio (timer voice), external storage. minSdk 26, targetSdk 30.

## The HTTP client all features use (`b.java` = AsyncHttpClient)

- **Single global serial queue.** Queue + active task are `static`; one HTTP request runs at a time
  across the whole app. Imports download **sequentially, never in parallel**.
- **Timeout is per call** (connect == read == the passed value), default **30000 ms**. A watchdog
  force-disconnects at timeout + 1000 ms.
- User-Agent `OI.Share v2`, set only for host `192.168.0.10`.
- **No client-side retry or back-off.** On failure the error callback fires; any retry/poll is a
  caller re-issuing in its callback. (One exception: a `RejectedExecutionException` re-posts once
  after 1000 ms.)
- **Error convention:** non-200 → read body, parse `<errorcode>NNNN</errorcode>`; that int is the
  "vendor code" handed to the failure callback. HTTP **520** + code is the camera's refusal.
  e.g. 520/**2004** = "cannot resize" → app falls back to the original file.
- PUT uploads (myset/firmware) add `X-OISHARE-CLIENT: IsOIShareClient` + Basic auth,
  `Content-Type: application/octet-stream`.

## Remote control — the shooting sequences

**Which code runs for which camera** (`RemoconActivity.Z0()`): commver `2.00`→V20, `2.10`→V21,
**`2.20`→V22 (control) / RemoconRelease (shutter)**. V22–V26 are no-op subclasses of **V21**, so a
**2.20 camera like the E-M10 Mark I runs the V21 path**. (V20 is the *old* sequence and is the only
one that uses `takeready` for a normal shot — see below.)

### Normal still, 2.20 control (V21) — the authentic path

```
exec_takemotion.cgi?com=starttake[&point=<x>,<y>]
    held open as ONE HTTP request (timeout ≈ i21+25000; normal ≈ 30000 ms)
    → camera returns when the exposure is done
stop live view (id(true))
wait 100 ms
getrecview.cgi            (timeout 15000)   — the review image
wait 300 ms
restart live view
```

- **No `takeready`, no `stoptake` for a normal still.** `starttake` alone; the open socket *is* the
  exposure wait. There is no inter-call sleep in the dispatch and **no "image ready" poll loop** —
  `getrecview` is fetched once.
- Touch AF: the tap is sent with the shot, as `&point=<x>,<y>` appended to `starttake` (V21). (V20 instead
  issued a separate `exec_takemisc.cgi?com=assignafframe&point=` before the shot, with a release
  after.) **Point format in the app is `x,y` (comma).** Our firmware notes elsewhere say `XXXXxYYYY`
  — unresolved; verify on the camera before relying on either.

### Long exposure (bulb / live-time / live-composite), V21

```
exec_takemotion.cgi?com=starttake&upperlimit=200[&exposuremin=<N>]
    single request held open up to ~i21+25000 ms (bulb base 1,800,000 → ≈30 min)
user releases:
exec_takemotion.cgi?com=stoptake      (timeout 30000; live-comp: 30000 + comp_seconds*1000)
```
`exposuremin` default 180 for live-composite. The developing image keeps arriving on the live-view
UDP stream during the hold; there is no polling loop.

### Movie, V21

```
exec_takemotion.cgi?com=startmovietake[&limitter=<n>&liveview=on|off]   (timeout 25000)
...
exec_takemotion.cgi?com=stopmovietake                                   (timeout 30000)
```

### Remote Shutter (2.20 non-control, RemoconReleaseActivity)

```
switch_cammode.cgi?mode=shutter
press:   400 ms vibrate → exec_shutter.cgi?com=2ndpush   (or 1st2ndpush; timeout 90000 = bulb hold)
release: exec_shutter.cgi?com=2ndrelease                 (or 2nd1strelease)
half:    exec_shutter.cgi?com=1stpush / 1strelease
busy response → re-post same command after 100 ms
exit:    switch_cammode.cgi?mode=play
```
Bulb in this mode = hold `2ndpush` open (≤90 s) until release. This is the path our `shoot()` models.

### Live view start/stop (V21)

```
switch_cammode.cgi?mode=rec&lvqty=<size>        (timeout 10000)   sizes: 0320x0240, 0640x0480, 0800x0600
get_camprop touchactiveframe etc.
startliveview.cgi?port=<ephemeral UDP port>     (timeout 10000)
```
UDP socket is `DatagramSocket(0)` → OS-chosen ephemeral port, 65536-byte buffer, blocking receive, no
socket read-timeout; a **3000 ms no-frame watchdog** flags a stalled stream. Matches `liveview-udp.md`.

## Import / transfer — approach

- List `get_imglist.cgi?DIR=`; thumbnails `get_thumbnail.cgi?DIR=`, `get_screennail.cgi?DIR=`.
- Download: resized `get_resizeimg.cgi?DIR=&size=<width>` (or `get_resizeimg_witherr.cgi`, which on
  520/2004 falls back to the original); full/RAW/movie served from the raw path
  `http://192.168.0.10<path>`. All **serialized** through the one queue — next file starts in the
  previous file's callback.
- Server-side trim/resize: `get_trimresizeprocstatus.cgi` polled every **1000 ms** until
  `result=complete`, then download `<filegenerated>`.
- Share Order: `get_rsvimglist.cgi` → download → `clear_resvflg.cgi`.
- Browse keep-alive: `set_timeout.cgi?timeoutsec=1800`, rescheduled 300/150/60 s.

## Myset backup / firmware — approach (brief)

- Chunk size for PUT = caminfo `MaxPostBodySize` (fallback 1 MiB).
- Myset: `request_getmysetdata` → poll `get_mysetbackupstate` (busy loop) → `get_mysetdatasize` →
  `get_partialmysetdata`. Restore: `set_mysetdatasize` → PUT `send_partialmysetdata` chunks →
  `request_restoremysetdata?action=restore` → poll `get_mysetrestorestate`. Firmware-flow myset polls
  with explicit 1000 ms delays; standalone myset loops busy-states back-to-back.
- Firmware upload polls `fwup_getfirmstatus` / `fwup_check` at 1000 ms; ends `exec_reboot.cgi`.
- Time-sync on connect: `set_utctimediff.cgi?utctime=<yyyyMMdd'T'HHmmss>&diff=<±ZZZZ>`.
- Power: `exec_pwoff.cgi` (`?mode=withble` when BLE), `exec_reboot.cgi`. Connect handshake order:
  `get_caminfo` → `get_connectmode` → `switch_cammode?mode=play` → `get_commandlist` → `set_utctimediff`.
- `command.cgi` is **FlashAir SD only** (`http://flashair/command.cgi`), never the camera.

## Implications for our client

- Our `WifiCamera.captureInRec` (takeready → starttake → sleep → stoptake) **works on the camera**
  but is not how OI.Share shoots a normal 2.20 still. The authentic path is simpler: **`starttake`
  alone, held open until the camera responds**, then stop live view → `getrecview` → restart. We can
  drop the `takeready` and `stoptake` for a normal frame and let the single request block. Keep the
  `stoptake` only for bulb/live-time/live-composite ending.
- The app never downloads in parallel — matches our one-at-a-time HTTP use.
- Touch AF for the GUI (A4): send `&point=x,y` on `starttake`; confirm the coordinate format on the
  camera (comma vs `x`-separated) since our firmware and app notes disagree.
