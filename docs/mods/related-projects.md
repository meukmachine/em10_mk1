# Related projects (checked 2026-11-12)

Other people's work on Olympus / OM System cameras that bears on this project. Read as references; what
each licence allows is noted. Nothing from them is copied into this repository.

## Caduceus for OM-3 — `github.com/androidfanman/caduceus-om3` (Apache-2.0)
Firmware patches for the OM SYSTEM OM-3 (firmware 1.1): 20 "film" picture modes, an on-camera colour
editor, and a browser tool (the "Control Panel" / Look Lab at
`androidfanman.github.io/caduceus-om3/looklab/`) that designs looks and saves them as a file for the card.
Camera-tested releases, v0.27.1 on 2026-11-11. Built on the sister project below.

Why it matters here:
- **Same processor family and same update mechanism as the E-M10 Mark I.** The OM-3 (2025) still runs
  MN10300 / AM33 code, its update file is the same block container, and it is installed the same way:
  `DCOLYMP/E<model>9999.BIN` on the card, **hold OK while switching on**. Their notes list this as proven
  on the camera, in both directions (patched firmware and back to the official file).
- **What the update looks like on the camera (OM-3):** the light stays on steadily for about two minutes
  while writing, then blinks when finished. If it blinks straight away with no steady phase, the camera
  did not accept the file and nothing changed.
- **A working method for patch code:** MN10300 assembly built with binutils for `mn10300-elf` and a linker
  script, placed in unused space at the end of code sections, entered through short hooks; a user-side
  patcher in plain Python that applies only the project's own bytes to the official file the user
  downloads, refuses any other input, and checks the result against an expected checksum. Extra code and
  data are loaded from files on the card at run time, so most changes need no new flash.
- **A safety practice:** every change is run in an emulator (below) before it goes on a camera, and the
  installer keeps a restore copy of the official firmware.
- **A debugging aid:** an optional file on the card switches on a log that the patched firmware writes back
  to the card.

Limits for us: their addresses, hooks and features are for the OM-3's much newer firmware. The picture
pipeline they edit (colour profiles, grain, split toning) may not exist in the same form on a 2014 body.
The methods transfer; the patches do not.

## Caduceus for E-P7 — `github.com/cakelesscoder/caduceus-ep7` (Apache-2.0)
The original project: firmware mods for the PEN E-P7 (film simulations, HSL, grain, bloom, clarity, log
video, extra aspect ratios). Not examined in detail yet. Closer in age to the E-M10 than the OM-3 is, so
its porting notes may be the better starting point.

## OM-Emu — `github.com/flatpanda/om-emu` (**MIT licence**, 2026-11-14)
An emulator "lab" in JavaScript (Node 26 or a browser) that runs Olympus / OM System MN10300 firmware
outside the camera: it emulates the CPU and the devices the user interface needs, runs the firmware's own
kernel, GUI and camera application, and shows the camera's screens. It does not boot from reset, and the
sensor, autofocus, release and movie modules are stand-ins. It can apply a patch overlay in memory, and has
a debug interface (registers, memory, breakpoints, traces).

- Reference body OM-1 (E143); ports for about 20 others, including the **E-M10 II (E120), III and IV** and the E-M5 II.
- **The original E-M10 (E113) is listed among "14 older bodies … surveyed, not tried."**
- Now carries an **MIT licence** (it had none on the 2026-11-02 look), so its code could be reused with
  attribution — but we have not needed to.

Why it mattered: it looked like the missing piece for testing a patch without the camera. But see the
port attempt below — the E-M10 Mark I is below its supported generation.

### E113 port attempt — 2026-11-15 (does not work without emulator-engine changes)
Cloned to gitignored `research/om-emu-src/`, ran on a locally-installed Node 26 (system Node is 18).
Baseline is healthy: `node emu/bin/test-pure.mjs` passes (CPU vs QEMU vectors, device models, 255/255
purity). Steps taken:
- Unpacked our stock E113 BIN with the emulator's own unpacker (`src/fw/olyfw.mjs labBlocks`): main
  code block `0x0100` (load `0x40300000`, 18,874,304 B), resources `0x0101` (`blk01200000.OE.0101.bin`,
  25,165,760 B), plus a third `0x0106` block. Wrote them to `unpacked/E113/`.
- Added a minimal `E113` entry to `emu/src/fw/models.mjs` (board family `E118` — its SRAM base
  `0x8FF00000` matches ours; `image_base 0x7d991020`; panels 720×480 / 800×600). E113 is now accepted
  by `emu/bin/port.mjs`.
- Fetched the E143 (OM-1) reference firmware (the whole cross-build map is built from it).
- `node emu/bin/port.mjs E113` runs and **stops at the first of ~59 board-fact rules**: `coreByte`
  scans the image for the core-id getter encoded `fc a8 <abs32 in 0x8ff00000..+0x1000> de 00 00`
  (`movbu (abs32),d0; retf`) and finds **zero**. In our disassembly `0x8ff00000`/`0x8ff00004` never
  appear as an immediate at all: E113's 2014 toolchain reaches that SRAM through a **register-held
  base**, where the 2015-2022 bodies the rules were written for use **abs32 immediates**.

Conclusion: porting E113 is not "fill a model table" (as E120 was, ~6 overrides via `port_via: E122`).
E113 predates all three board families (E143/E144/E118 are 2015-2022) and has no `port_via` sibling,
and its codegen does not match the fact-extraction rules. A port would mean **extending the emulator's
own fact engine** (59 rules) to E113's encodings, then absorbing a large address-map override list
across the 8-year gap from E143 — an emulator-development project of many sessions, success uncertain.
And even a finished port could not validate a real flash-to-SD dump, because the NAND and card are
stand-ins. Scaffolding (unpacked blocks, the `E113` MODELS entry) is left in `research/om-emu-src/` for
any future attempt.

## omcap — `github.com/HolmHaensel/omcap` (GPL-3)
Wi-Fi and USB tethering for the E-M1 Mark I. Covered in `../usb-protocol/wifi-api.md`.

## Related camera: PEN E-PL3 (owner's second body, checked 2026-11-13)
Not a project but a second camera on the same platform. Its official firmware 1.6
(`OLY_E_086_1600_0000_0000.BIN`, 46,596,160 bytes, sha256 `89b70dd6…eb4b1`) was fetched with
`tools/fetch-firmware.sh E086 --download` and checked with our own tools. Nothing was run on the camera.

| | E-PL3 (2011) | E-M10 Mark I (2014) |
|---|---|---|
| Update-server code | E086 | E113 |
| Container | same "OE" blocks, same scrambling and footer checksum; `omfw.py` reads it unchanged | – |
| Blocks | 4: code `0x402c0000` (14.2 MB), `0x42700000` (23.1 MB), `0x43d00000` (2.5 MB), `0x43f60000` (6.8 MB), plus a 64 kB trailing section with a different header (not examined) | 3: code `0x40300000` (18.9 MB), `0x41500000`, `0x42d00000` |
| CPU code | MN103 family: disassembles cleanly with `mn10300-elf-objdump -m am33-2`; same opening instruction sequence | MN103 / AM33 |
| System | uITRON, same Olympus code base: 68% of its text strings of 8+ characters also occur in the E-M10 code block (task-list debug texts, PTP task names, `DCOLYMP`) | uITRON |
| Wi-Fi | none: no `.cgi` strings in the code | built in |

What follows from it:
- The disassembler, the Ghidra setup and the labelling method apply to the E-PL3 as they are. Many symbol
  names could probably be carried over by matching strings; that has not been tried. The run-time base of
  its code block has not been determined.
- `tools/emcam.py` and the Wi-Fi parts of the app do not apply. USB import (standard PTP) should; whether
  its vendor operations match the E-M10's is unknown.
- How this body takes an update (USB only, or also from a card) has not been looked at.
- It is not a test body for E-M10 firmware: a different model with a different code block. The no-flash
  rule covers it too.

Update-server codes seen while looking for it (E080–E112, others returned no file): E081 E-PL2,
E085 E-P3, E086 E-PL3, E087 E-PM1, E088 E-PL1s, E097 E-M5, E100 E-PL5, E101 E-PM2, E108 E-P5, E109 E-M1,
E112 E-PL6, E113 E-M10; compacts E082 TG-310, E095 TG-1, E106 TG-2, E107 TG-630, E110 TG-2 Limited,
E111 STYLUS 1. The E-P3, E-PL3 and E-PM1 files have the same size and version, as do the E-PL5, E-PM2
and (by size) E-PL6, so those are probably near-identical builds.

## What this changes for our open questions
- "Is hold-OK-at-power-on a real card update path?" On the OM-3 and E-P7 it is, and publicly documented
  with the same folder and file naming our firmware scans for. Together with the owner's observation that
  only OK delays start-up on the E-M10, the path very likely exists on this body too. It is still untested
  here, and what selects it inside our firmware is still not located.
- "Is recovery possible after a bad patch?" Their experience is that a misbehaving patch is undone by
  flashing the official file the same way, and that an interrupted write can leave a camera unable to start.
  That matches our own analysis and does not remove the risk for a single body.
- The no-flash rule in `STATUS.md` stands. These projects make the research items in `ISSUES.md` (M0, M10,
  M11) more tractable; they do not make a first flash safe.
