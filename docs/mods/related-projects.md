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

Why it mattered: it looked like the missing piece for testing a patch without the camera. A partial
port now exists — see below. The address map is done; the device-fact layer is not.

### E113 port — 2026-11-15 (address map ported; device facts pending)
Cloned to gitignored `research/om-emu-src/`, ran on a locally-installed Node 26 (system Node is 18).
Baseline is healthy: `node emu/bin/test-pure.mjs` passes 255/255 purity (CPU vs QEMU vectors, device
models) with the E113 table in place — no regression.

A port has **two separable parts**, which the first look conflated:
1. the **cross-build address map** — translating the OM-1 (E143) reference build's addresses to E113's,
   built purely by matching code, no device knowledge; and
2. the **~50 board-fact rules** — byte-pattern scans that read device facts (core-id getter, panel,
   lens mount, card FS, resource loader…) out of the image.

**Part 1 is done.** First the map was measured against a known-good port to see if the two builds pair
at all. They do — E113's raw E143→E113 map is in the *same league* as E120, a fully-ported body that
passes modelcheck:

| E143 → target map | instructions paired | seed anchor rate |
|---|---|---|
| **E120** (known-good port) | 770,332 (2.8%) | 29.5% |
| **E113** (our target) | 890,871 (3.2%) | 24.0% |

So the earlier "below the supported generation" verdict was wrong at the map level. A generator
(`research/om-emu-src/e113-port/portlite.mjs`) then materialised the map as E113's `PORTS` table and
wrote it into `emu/src/fw/ports.mjs`: **2,169 entries (1,031 translated to a real E113 address, 1,138
explicit nulls); 536 of the 731 `A()` address-reference sites the emulator source actually uses now
resolve — 73.3%.** `A()` is live and consuming the table (e.g. the resource loader resolves concrete
E113 addresses).

**Part 2 — fact rules now fixed; a deeper engine wall remains (2026-11-15, corrected).** The earlier
"9 device facts fail / `coreByte` finds zero because 2014 codegen uses a register-held base" writeup was
**wrong on both counts** and is retracted:

- **E113 does use abs32 SRAM immediates** (`fc a8 …`), the same encoding the rules expect. It simply
  **inlines the core-id read** rather than wrapping it in a `movbu (abs32),d0; retf` getter function, so
  `coreByte`'s getter scan finds no *getter* — not because the immediate form is absent.
- **Only 2 of ~50 rules actually fail, not 9.** `readFacts` (facts.mjs:5645) is monolithic — one rule
  throwing aborts the whole function — so `coreByte`'s single throw *looked* like a cascade of failures.
  The two genuinely-affected rules are `coreByte` and `dcfCounts`; every other rule reads E113 cleanly.
- **`dcfCounts` is solved: E113 = `0xa0a8eef0`.** E113 inlines the DCF-count getter too, so the full
  `fc c0 <abs32> f1 e0 f8 00 28` pattern finds nothing. Added a constrained-tail fallback (match the
  `add T,d0; mov d0,a0; mov (40,a0),d0` tail with the abs32 high byte pinned to the DCF-count RAM window
  `0xa0..0xa3`) → unique and correct on E113, **zero regression** on the 18 working bodies (it only runs
  when the full pattern finds nothing). E143 → `0xa33aa5b4`, unchanged.
- **`coreByte` is made to defer honestly, not guessed.** It now returns `null` on zero getters (the
  engine-idiomatic UNKNOWN deferral) instead of throwing, and `readFacts` omits `core_byte` from
  `facts.board` when null, so the board layer can supply it by hand/family without a merge conflict. The
  **actual E113 core-id value is left unpinned** — the sibling E118/E120/E122 value `0x8ff00188` does
  **not** transfer (E113's image reads that address zero times), and inventing one would violate the
  no-fabrication rule. Pinning it needs deeper scheduler RE.

With those two edits `readFacts` completes end-to-end on E113 (31 top-level fact keys) with no
regression on E120 (34 keys, `core_byte=0x8ff00188`) or E143.

**The real blocker is architectural, below the fact layer: E113 runs its main code in place from a high
window, which OM-Emu's memory model does not represent.** OM-Emu assumes the run-time code image is
**relocated into DRAM**: `createMachine` (machine.mjs:205) hard-asserts `IMAGE_BASE` lies in
`0x60000000 .. 0x60000000+dramBytes`, and `expandImage`/`relocate` (expand.mjs:38) builds **one flat
buffer growing upward from `base`**, placing each preload row at `to - base`.

- E143/E120 satisfy this — their code base is `0x60021020` and their preload/DRAM data sits just above
  it (E120 data at `0x61xxxxxx`), all inside the one window.
- **E113 does not.** Its verified code base is `0x7d991020` (reset entry `0x7d991114`; 2315/3000 string
  pointers resolve against it), but its preload DRAM destinations are `0x60000fe0`, `0x6044a140`,
  `0x6047e140`, `0x60486140` and its boot-copied library window is `0x47000000` — all **far below** the
  code base. `to - base` goes negative → `RangeError` in `relocate`, and `IMAGE_BASE` fails the DRAM
  bounds assert. Both symptoms are the same root fact: **E113 (a 2014 body) executes code in place from
  `0x7d99xxxx`; the newer bodies OM-Emu supports relocate code down into `0x60xxxxxx` DRAM.**

So the honest state of Goal #2: **the fact-rule layer is fixed and verified, but E113 cannot actually
run in OM-Emu without a sparse / multi-region memory model** (code high at `0x7d99xxxx`, data low at
`0x6000xxxx`/`0x4700xxxx`). That is a substantial engine change, not a port-table gap — and it is why
E113 was shipped as "surveyed, not tried." We did **not** fabricate a passing modelcheck.

Scope note: even a finished port **cannot validate a real flash-to-SD dump** — the emulator's NAND and
card are stand-ins. Its value here is tracing a patch's hook/read/write *logic*, not the dump itself.
The fixed `facts.mjs` (vendored, gitignored copy), the generated table, the generator, the calibration
diagnostic (`mapdiag.mjs`) and a pre-port backup of `ports.mjs` are kept in gitignored
`research/om-emu-src/`.

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
