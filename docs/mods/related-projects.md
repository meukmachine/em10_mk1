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

**Part 2 — fact rules fixed; the "engine wall" was a one-function bug, now fixed, E113 runs (2026-11-15,
corrected twice — see Part 2b below for the retraction of the "architectural wall" claim).** The earlier
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

**Part 2b — the "architectural wall" claim is ALSO retracted (2026-11-15): E113 runs; the blocker was a
one-function bug, not the memory model.** The earlier conclusion here — that E113 needs a
*sparse / multi-region* memory model and that this is a "substantial engine change" — was **wrong**, and
the arithmetic disproves it:

- OM-Emu's DRAM region is 512 MiB (`DRAM_MAX`). E113's code base `0x7d991020` sits at DRAM offset
  `0x1d991020` = **473.6 MiB**; the 18 MiB code block ends at **491.6 MiB**, comfortably inside one
  512 MiB DRAM. E113's layout is the ordinary **code-high / data-low** arrangement, not an unsupported
  topology — the whole run-time image fits in the single DRAM region the engine already has.
- The **only** real bug was `relocate()` (expand.mjs) folding *below-base* preload rows into an
  image-base-anchored buffer: `to - base` went negative → `RangeError`. The preload rows at
  `0x60000fe0 / 0x6044a140 / 0x6047e140 / 0x60486140` are **DRAM data, not code** — no byte-pattern rule
  reads them from the code image — so the fix is to **skip** them in `relocate()` (they stay at/above
  base for every older body, so zero regression) and write them into DRAM **directly** in `createMachine`
  (machine.mjs, after the image copy), each at `DRAM_OFF + (to - 0x60000000)`.

With those two edits (≈15 lines total) **E113 runs the full fact pipeline**: the IMAGE_BASE DRAM assert
passes and the RangeError is gone. After the per-body calibration below, `bin/test-pure.mjs` now reports
**`purity: 255/255`, `firmware facts (3 unpacked main blocks): 272/272`, `all passed`** — zero failures.

**Goal #2 — PORT COMPLETE (2026-11-15).** The memory-model blocker is resolved and the remaining
firmware-fact failures were all **per-body port calibration**, not an engine wall. Each was closed
honestly — from E113's own bytes or its verified ground truth, never by fabricating a pass:
- The cross-build **map-vs-rule** mismatches (the byte-pattern rule scans E113's own bytes and finds the
  ground truth; the approximate E143→E113 address map `A()` disagreed) are pinned by
  `MODELS.E113.overrides` to the rule's verified reading — the intended port workflow.
- The **golden-table (FOUND) entries** the rule reads from E113 but no E113 row recorded (vsc levels,
  errlog entry `0x50006af4`, remaining-shots query `0x502109d0`, card-detect table `[0xa0a8f02c, 1]`) are
  recorded from the rule's verified reading, each sanity-checked (card detect `drives: 1` matches the
  E-M10's single SD slot).
- **`lens.mount` now detects E113's real mount.** The E-M10 physically has a Micro Four Thirds mount, so
  the honest fix was making the rule find E113's actual `getLensConnection`, not adding E113 to the
  built-in-lens list. E143/E120 call the lens-0 record getter through an `exthu d0; call G; ret` thunk;
  E113's 2014 build **inlines** that getter (its body opens with its own `exthu d0` then reads the lens
  record pointer in the uncached `0xa0..0xa3` window). `lensMount` now anchors on `getLensConnection`'s
  own booleanise idiom (`clr d0; call F; clr d2; cmp 0,a0; beq; mov 1,d2`) and accepts F in either shape —
  one match on each of E143/E120/E113, and still none on the built-in-lens bodies (same class of real RE
  as `core_byte`/`dcfCounts`). `lensRecordGetter` (which `lensDriver` reads for the strict thunk form) is
  left untouched.
- **`dbSave`** (settings-DB flash-save) is deferred honestly in `readPlacedFacts` (try/catch, like
  `core_byte`): the map mis-places E113's DB-init `0x50173d5c` / flash-save `0x50203a74`, so `db_save` is
  omitted rather than guessed, and `card_fs` carries a `why` note that the FS seams aren't placed.

Scope note: even a finished port **cannot validate a real flash-to-SD dump** — the emulator's NAND and
card are stand-ins. Its value here is tracing a patch's hook/read/write *logic*, not the dump itself.
The fixed `facts.mjs`/`expand.mjs`/`machine.mjs` (vendored, gitignored copies), the generated table, the
generator, the calibration diagnostic (`mapdiag.mjs`) and a pre-port backup of `ports.mjs` are kept in
gitignored `research/om-emu-src/`.

**E113 runs at the CPU level (2026-11-15).** Beyond the green fact-port, real E113 firmware code now
*executes* on OM-Emu's emulated MN10300 through the debug tool (`emu/bin/debug.mjs`), with disassembly,
register/stack/memory inspection, breakpoints and tracing. Three things were needed, all honest:
- **`prop` fact supplied by measurement.** The full lab loads `src/lab/display.mjs`, whose top-level
  `DEVICE` table calls `fact('prop.evf_code')`; the cross-build map leaves E143's `PROP_DEVICE_SITES`
  unplaced, so E113's `propDevice`/`evfCode` rules emit no `prop`. Measured from E113's **own** image: its
  single EVF-writer (`mov (abs32),d0; cmp 6,d0; bne; mov 4014,d0; mov 8,d1`) names output-device property
  `4014` and EVF device code `6` — identical to E120 (same E118 board family). Supplied by hand as
  `MODELS.E113.prop = { device: 4014, evf_code: 6 }` with that evidence, not guessed.
- **DRAM sized to hold the high-linked image.** E113 links its code at `0x7d991020`, which is in reserved
  DRAM **above** the managed-heap top the `dramSize` fact reports (`0x1d971200` ≈ 474.9 MiB vs the image
  base at offset `0x1d991020`). `createMachine` now sizes the run-time DRAM window to cover both the heap
  fact and the linked image (`Math.max(dram_size, image_base−0x60000000 + rom.length)`, capped at the
  512 MiB chip). This is a no-op for every body whose image ends within its managed DRAM (E143, E120, …) —
  **test-pure stays 272/272 + 255/255, zero regression** — and completes the same E113 memory-model fix
  that already relocates below-base preload rows into DRAM directly.
- **Run stock E113, not the hardcoded E143 patch set.** `debug.mjs` defaults to the OM1 (E143) patched
  artifact; pass `--image <E113 stock block>` so it runs E113's own bytes, selected by `OM_EMU_MODEL=E113`.

Convenience wrapper: `research/om-emu-src/e113-play.sh` (gitignored) sets the env + paths. E113's reset
entry disassembles and runs cleanly — `and 61695,psw` (mask IRQs), two init `calls`, then a peripheral
poke `mov d3,(8,a2)` to `0x98100008` that **faults as expected**: that MMIO window has no device model
(the lab stubs sensor/card/NAND/unmodelled registers). The CPU core, decode, and memory model are sound;
the limit is unmodelled hardware, not the engine. The **full HLE lab** (`play.mjs`, menus/GUI) still needs
E113's remaining per-model fact subsystems (`ae`, `api`, `crt`, `media`, `rec_file`, `storage`, `touch_*`,
…), each either measured from E113's bytes (as `prop`/`evf_code` were) or carried from its E120 sibling —
substantial further RE. CPU-level execution and inspection work **today**.

### HLE lab bring-up — how far the full menu boots, and the walls (2026-11-15)
Goal of this pass: run the **full HLE lab** (`src/lab/menu.mjs` `PlayLab`, the path `modelcheck`/scenario
tests drive) under `OM_EMU_MODEL=E113` far enough that the menu is usable. The lab boots the reference
body's own init functions natively, located through the E143→E113 address map, and asserts each subsystem's
observable behaviour. The pass drove that boot blocker-by-blocker; each fix was measured, not guessed.

**Fixes landed (verified, zero regression on E143/E120 — both still boot `state=264`):**
- **`board.bus_ram` — a model-scoped extra backed bus range.** E113's registry bootstrap (`0x50040aeb`)
  verifies a 256 KiB NVRAM-backup block at bus `0xBFF8A000` (hardcoded `mov -1074225152,a0`), a region the
  OM-1 reference never touches, so the bus faulted (fault 2, unassigned). An image uint32 scan found four
  contiguous store bases (`0xBFF8A000`/`0xBFFCA000`/`0xBFFEA000`/`0xBFFFA000`): a backup partition
  `0xBFF80000..0xC0000000`. `src/dev/bus.mjs` now takes board-given extra `[base,size]` ranges (served
  lazily-zero like the live-view banks → the checksum fails → the firmware's blank-init path runs);
  `src/machine.mjs` wires them from `BOARD.bus_ram`; `MODELS.E113.board.bus_ram = [[0xBFF80000,0x80000]]`.
  With it the **registry bootstrap passes fully** — a large subsystem, proving the port generally works.
- **`cflush_returns` hand knob.** E113's registry calls the cache-flush primitive from a site
  (disasm `0x50041b48`) the E143-shaped lab didn't list; added as E113's own measured value.
- **`lab_pri` for E113's 16-level kernel.** The lab's injected home task sits at priority 60, below E143's
  1..69 firmware tasks. E113's µITRON has only 16 priority levels (tasks 1..13), so 60 is `E_PAR`;
  `MODELS.E113.lab_pri = 14` pins it just below all firmware. Default stays 60 for the E143 family.

With these the lab reaches the **settings-DB init** — i.e. past registry + property-store init.

**Wall 1 (settings store) — CLEARED (fork commit `cb16d52`).** The lab asserted the blank-camera DB-init
triggers exactly one flash-save; on E113 it triggers zero (`menu.mjs:747`). Diagnosis, three independent
facts: (a) the map places E143's DB-init `0x503c5473`→E113 `0x50173d5c` and flash-save
`0x503eee94`→`0x50203a74`, but a coverage trace shows E143's DB-init executes **5302** instructions /576
code ranges while E113's mapped target executes **538** /100 — an order of magnitude less, so the target is
not the real DB-init; (b) disassembling `0x50203a74` shows a 16-byte-sp-buffer helper (two `call`s to
`0x50042bd7`/`0x50213104`, then `strcpy`-style loops), categorically not a flash-save — no flash-driver
cluster, none of E143's `0xF001E` flash constant (**0 occurrences** anywhere in E113); (c) E143's distinctive
store string `damage_info uninitialized` is **absent** from E113. The E-M10's 2014 store module was rewritten
by the 2022 OM-1; it has no address-mapped analog. The fix keeps the fact layer's deferral of `dbSave` and
makes the lab's store-init assertion **model-aware**: E113 carries `db_init_saves: 0` in `models.mjs`, and
`menu.mjs` checks `this.store_log.flash_save.length === (MODEL_INFO.db_init_saves ?? 1)` — the blank E113 DB
genuinely writes no flash on init, so zero is the correct expectation, not a failure. Boot now passes registry
init, property-store init **and** settings-DB init.

**Wall 2 (device open) — ROOT CAUSE CONFIRMED: the PER_SIO panel-serial channel.** Past the store, both the
TFT and EVF boot stop at the display device **open** (`display.mjs` `open(device)` → `display_cmd(2, …)`, which
runs the display task body synchronously). Instrumenting the exact deadlock (`OM_DUMP_IDLE` on the kernel idle
throw + `OM_LOG_WAITS` on the OlyEvWt wrap, with the lab object captured via `env.onPlaySession` because a
failed `enter()` nulls `session.lab`) shows **both walls are the same subsystem**: E113's panel driver (LCD and
EVF) talks to the panel over the **PER_SIO serial bus** (firmware bank `0x7d9e`), driven by a worker task on
**message queue 73**, with transfer completion signalled on **flag id 138** (id word `0x7d9b056e`). The lab
models neither queue-73's serial worker nor the PER_SIO transfer-completion interrupt, so the open cannot make
progress. The common open prologue (identical for TFT and EVF) is:

1. `OlyEvWt(flag 36, bits 0x3)` at `0x7d9e24cc` (disasm `0x500514ac`) — grab the PER_SIO bus lock; returns.
2. `OlyEvWt(flag 177, 0xffffffff)` at `0x7dbad90c` (disasm `0x5021c8ec`) — infinite wait; returns.
3. `OlyEvWt(flag 138, bits 0x1)` — the serial-transfer **completion** wait. **This is the wall.**

Where the two devices diverge:

- **TFT throws first, at the serial-command enqueue.** Before its flag-138 wait completes, the driver does a
  **synchronous send to message queue 73**, cmd 0, from `0x7d9e55e3` (disasm `0x500545c3`, PER_SIO). The lab
  raises “synchronous send to queue 73 … its receiver does not run in the lab”: queue 73 is the serial worker's
  input queue, and that worker is not modelled. The TFT flag-138 wait site is the TFT/HDMI HAL `0x7dbb5d3f`
  (disasm `0x50224d1f`, bank `0x7dbb`).
- **EVF reaches the flag-138 wait and blocks.** The api_tft (`0x7dba`) open body waits `OlyEvWt(flag 138)` at
  `0x7dbae225` (disasm `0x5021d205`), nested under `0x7dbae2bc`; the kernel idle finds **only the lab task
  live** (every other task DMT) — a self-block, not a cross-task wait — so nothing can ever set flag 138.

The completion flag 138 is set by the callback at `0x5021d235` (status check `d0==1` → `iOlyEvSt 0x500300c9`
else `OlyEvSt 0x5003004e`, both on id word `0x7d9b056e`). That callback only runs on a **PER_SIO
transfer-done** event the lab never produces.

**OlyEvWt *is* hooked — the earlier "the stand-in does not know `0x50030118`" / "the wrap never fires" notes
were wrong.** `A(0x5002BDD8)` ("OlyEvWt" in `rtos.mjs`) maps through the E143→E113 port **code map** to
`0x50030118`, so the scoped OlyEvWt wrap in `menu.mjs route_rtos` **does** fire on every one of the open's
waits (verified: flags 36/177/138 all logged with `dr=1`). It blocks not because the primitive is un-hooked,
but because **flag 138 matches no stand-in branch**: it is not the vsync flag, not `display.frame_wait`
(`frameFlag=163`), and not the `PANEL_FLAG` id (`panel=108`). `PANEL_FLAG = A(0x60043188)` cross-build-maps to
id word `0x7d9b06c4` (id **108**, 58 refs), but E113's open never waits on 108 — it waits on word `0x7d9b056e`
(id **138**, 13 refs). `S-PANELDONE` answers only `panel`, and it was built for *nested playback* EVF panel
commands, not the open. (The earlier "the wrap never fires" observation was a **false negative**: the probe read
`_spike_waits` off a lab object that `enter()` had already nulled on the failed open, so it came back empty.)

**E120/E122 contrast (same E118 family, both pass).** Their open is **VSC-frame-driven**: it reaches a vsync
wait (E120 flag 137, tagged `vsync`) that the lab's vsync stand-in answers, after which the VSC ISR sets the
downstream completion flags, and boot proceeds (401+ waits logged, full boot). E113's **2014 PEN/OM-D display
open is PER_SIO-serial-driven** and blocks on flag 138 **before reaching any vsync wait** — there is no E120
analogue to template, and `S-FRAMEWAIT` (which pulses the single frame flag) does not touch the serial path.

**Fix direction (targeted stand-in, model-gated — NOT a blanket answer).** Model E113's PER_SIO panel-serial
channel as instantaneous: in a display run, (a) accept/drop the synchronous **queue-73** serial-command send
(as `S-SEND` drops already-modelled sends), and (b) answer `OlyEvWt` on the completion flag at id word
`0x7d9b056e` at once (a stand-in keyed on that specific flag, the way `S-PANELDONE` answers `panel`). This must
stay specific to the serial-completion flag(s): a **blanket** answer-all-in-display-run breaks boot, because it
also answers loop-pacing waits that must block (observed: "more than 200000 stub calls"). Open question before
implementing: whether the queue-73 worker must do real work (e.g. advance a panel state the later menu reads)
or whether completing instantly suffices — to be checked by whether the post-open menu pixels/count-probe pass.

**Wall 2 — CLEARED by the PER_SIO panel-serial stand-in (fork working tree, uncommitted).** The fix direction
above is now implemented and measured (`models.mjs` `MODELS.E113`, `menu.mjs`, `nav.mjs`, `display.mjs`):
- `extra_dropped_sends: [[73, null, null]]` — every synchronous queue-73 serial-command send is dropped (logged
  to `dropped_sends`, answered `d0=0`), since queue 73 is the PER_SIO serial worker's input queue and that worker
  is not modelled. Matching the queue id alone is correct (the open sends several cmds from adjacent PER_SIO sites).
- `display.panel_flag: 0x7d9b056e` — `S-PANELDONE` now answers the open's completion wait on **flag 138** (the
  word measured in E113's own image), not the map's default `PANEL_FLAG` id 108, which E113's open never waits on.
- `nav_pri: 14` and `lab_pri: 14` — E113's µITRON has only 16 priority levels, so E143's `NAV_PRI = 50` returns
  `E_PAR`; 14 (= `lab_pri`) is the valid analogue.
- Supporting measured facts for the same bring-up: `board.buswin_pads: true` (the flash library's RMW on the
  `0x98100000` pad registers at offsets 0x00/0x04/0x08/0x10), `dial { table_ptr: null, table0: 0x7dbc0de0 }` +
  the `0x5014b7c7→0x500f9774` DIAL_INIT code-map override (E113's single-fixed-table dial init). The
  `touch_args.read_len { const: 4 }` fact is now **applied and auto-derived**: `touchReadConst`
  (`facts.mjs`) measures the touch read's fixed-count copy, so E113's `ports.mjs` carries
  `read_len { const: 4 }` and the E143 reference is corrected to `{ const: 5 }` (both reads bake the
  count in; `a1` is the I2C register, not a length — the old `{ reg: a1 }` was a tolerated
  mis-measure that overran the TFT caller's stack at `a1=0x14`). This cleared the early TFT
  `pc=0x8010` fault, so the TFT path now converges with EVF at Wall 3 — see
  `../om-emu/e113-touch-read-const.md`.

With these, **both E113 paths now pass the device open** and reach the first UI draw (`H-UIIN`).

**Wall 3 (first UI draw, `H-UIIN`) — NEW, two distinct device-specific faults; both deterministic (NOT a timing
race).** The earlier working note that the remaining `divu`-by-zero was an intermittent real-wall-clock race was
**wrong**: on the current tree every run of each device fails identically (TFT 20/20, EVF 5/5). The two devices
stop differently:

- **TFT — a wild jump into the stub exception-vector page.** `trap reason=3 (misaligned load) pc=0x00008010
  address=0x7dbc0589`, with **no osd-engine activity and no interrupt note** in the machine stderr (so it is not
  `execInterrupt` vectoring — the CPU's own PC genuinely reached `0x8010`, the zero-filled scratch vector page,
  and one decoded "instruction" there did `mov (a0),…` with the stale `a0 = 0x7dbc0589`, odd → `Fault(3)`,
  `src/core/mem.mjs:250`). `0x7dbc0589` is in the TFT/HDMI HAL bank `0x7dbc`, just below the dial descriptor
  table `0x7dbc0de0` — a plausible bad dial-slot callback, but the jump source is not yet pinned (needs a CPU
  single-step trace; the lab's `exec_history`/`draw_log` are osd-command logs, not a PC ring).
- **EVF — OSD commands the engine rejects, then `divu`-by-zero.** During the draw the firmware issues OSD DMA
  commands whose word `w[0] = 0x00000510` carries **depth field = 5** (`(w[0]>>>8)&0xff`), but `src/dev/engine.mjs`
  (`cmdFill`/`cmdCopy`) models only **depth == 3** → `fail(E.DEPTH, 5, …)`; also `WORD_BITS` on `w[3]` bit 22
  (`0x166c7800 & 0x400000`). The firmware then faults `divu` d2,d1 at `0x7d9962c0` (disasm `0x500052a0`) with a
  zero divisor — the rejected OSD op leaves a count/stride the firmware divides by at 0. The osd errors precede
  the `divu` in the stderr tail, so the rejected draw is the likely cause, not a separate bug.

**E120 contrast (same E118 board, same engine `enc`).** E120 boots clean through the same path with **zero**
`DEPTH`/`WORD_BITS` errors — so E113's depth-5 OSD format is a genuine E-M10-specific display-draw divergence,
not a general engine gap.

**Open question resolved — it is a real format, not a mis-fed stream.** Dumping the engine error ring at the
EVF fault (`osderr.mjs` via `EngineDebug.errors()`, which carries the full command words) and decoding the
rejected commands under the *existing* ENC2 field layout shows they are structured, consistent, geometrically
sane `cmdFill` (opcode `0x10`) ops — not garbage from a wrong base pointer:

| # | `w[0]` | depth | dst | stride | width | height | `w[3]` bit22 |
|---|--------|-------|-----|--------|-------|--------|--------------|
| 1 | `0x510` | 5 | `0x804ee2dc` | 8192 | 2048 | 57 | 0 |
| 2 | `0x510` | 5 | `0x805602dc` | 5632 | 1424 |  1 | **1** |
| 3 | `0x510` | 5 | `0x809f1640` | 8192 | 2048 | 80 | 0 |
| 4 | `0x510` | 5 | `0x80a91640` | 5504 | 1392 |  1 | **1** |
| 5 | `0x510` | 5 | `0x80aa42c0` | 2176 |  544 |  1 | 0 |

Every command is a **black fill** (`w[1] = 0`). The three bit22-clear fills (1, 3, 5) decode to `width × 4 ==
stride` **exactly** (2048×4=8192, 544×4=2176) → **depth 5 = 4 bytes/pixel** (vs the reference depth-3 the model
hard-codes). They fail *only* the `depth == 3` gate. Commands 2 and 4 pair with 1 and 3 — `dst2 = dst1 + 57
rows` (57 × 8192 = 0x72000), i.e. a main block plus a single-row remainder, and the remainder fills set `w[3]`
bit 22 (a reserved bit in the reference model; its exact field meaning on E113 is not yet pinned). The
consistency (same `w[0]`, black fill, dst adjacency, exact `w×4=stride`) rules out a corrupted descriptor.

So the EVF fix is **engine-level**: accept `depth == 5` (4 bpp) under an E113 model gate and resolve the
`w[3]` bit-22 remainder encoding, rather than chasing an upstream fact. (A black fill onto lazily-zeroed DRAM is
nearly a no-op, so the cascade is from the *rejection* — the firmware then `divu`-by-zeros a count that stays 0
because the fills errored, at `0x7d9962c0`.) The TFT wild-jump remains a separate CPU control-flow bug that
needs its own single-step trace to the jump site.

**Depth-5 gate implemented — advances EVF one layer to a surface-geometry wall.** `src/dev/engine.mjs` now reads
the fill/copy depth and the `w[3]`/`w[2]` bit-22 gate from `BOARD.engine` (`DEPTH_OK = BE.depth ?? 3`,
`WORD22_OK = BE.word22 === true`), and E113's `board` hand-gives `engine: { depth: 5, word22: true }`
(`src/fw/models.mjs`). Every other model keeps the strict depth-3, bit-22-reserved check unchanged
(test-pure **273/273**, purity **255/255**; E120 tft/EVF still `state=264`). With the gate, E113 EVF no longer
raises `DEPTH`/`WORD_BITS` — the fills are admitted, and the `divu` divisor advances from `0` to `0x2000`
(= stride 8192), i.e. the firmware gets further. The draw then stops at the **next** layer: the fills raise `GUARD`.

**Correction (an earlier note here said "no guards are registered" — that was wrong).** That claim came from a
post-fault guard dump that silently read nothing, because on E113 the fault is thrown *inside* `open_session`
before `session.lab` is assigned, so every post-hoc `pl.session.lab` inspection saw `null` and printed nothing —
which was misread as "zero guards". Instrumenting `guarded()` directly (dump on the first `false`, revert after)
shows the truth: E113's native init **does** register **7 guards**, same structure as E120 — bases `0xBDxxxxxx`,
**stride 896**, heights 1200/600 (kinds 0,0,1,1,2,2,3), read from the firmware's OSDDRAW registry. (E120's are
the same 7 at `0xBFxxxxxx`.)

So the EVF wall is **not** absent surfaces but a **stride/format mismatch**: `guarded()` requires an exact
`stride === gstride` match, and E113's depth-5 fills use stride **2176 / 5632 / 5504 / 8192** (4 bytes/pixel,
widths 544 / … / 2048) against guards registered at stride **896** (1 byte/pixel). The fill target also decodes
to a low address (`0x80aa42c0`, high bits zero under ENC2 `hiDst = w0 & 0x60000000`) nowhere near the guards'
`0xBDxxxxxx`. Both point back to the same root: E113's **depth-5 (4 bpp) OSD surface geometry** — the lab builds
its surfaces/guards from the OSDDRAW records as 1-byte-per-pixel (stride 896), but E113 draws them at 4 bpp with
different widths and (apparently) a different dst-high-bit encoding in the depth-5 command word. The next RE task
is to reconcile this: read E113's OSDDRAW records as its true 4 bpp geometry (correct stride/width) and confirm
the depth-5 word's address-field layout, so the fills land on the guards. The depth-5 **format gate** is in and
regression-free; the **surface-geometry reconciliation** is the open task. The TFT wild-jump is still its own
separate CPU-control-flow bug.

**Deeper probe (2026-11-15) — the guards are the firmware's own, so the fix is in the command-word decode, not the
lab's record model.** Instrumenting the EVF boot directly (temporary `console.error` in `OsdLab.native_init` and
`EngineDebug.guarded`, both reverted; fork tree verified clean) established two things the earlier post-hoc dumps
could not:

1. **`OsdLab.native_init` never runs on the E113 EVF path.** Its entry marker never fires, yet at the first `GUARD`
   fail the live debug-window guard table already holds **7 active guards** (`g0 base=0xBD86AA00 stride=896 h=1200`
   … `g6 base=0xBD557200 stride=896 h=600`). So the guards are **registered by the real firmware's own OSD init**
   running inside the HLE lab during `open_session` bring-up — not by the lab's `native_init` stub, and not by the
   lab's record model at all. Reading the lab's OSDDRAW records "as 4 bpp" therefore cannot move these guards; they
   are what the firmware itself wrote.
2. **The 4 rejected fills are also firmware-issued and decode cleanly** (geometry via the engine's own `sizeM1`/
   stride formulas), all `w[0]=0x510`, depth 5, dst in the **`0x80xxxxxx`** bus region:

   | dst (bus) | width | height | stride | `w*4` |
   |---|---|---|---|---|
   | `0x804ee2dc` | 2048 | 57/80 | 8192 | 8192 ✓ |
   | `0x80a91640` | 1392 | 1 | 5504 | 5568 |
   | `0x80aa42c0` | 544 | 1 | 2176 | 2176 ✓ |
   | `0x805602dc` | 1424 | 1 | 5632 | 5696 |

   (The big 2048×8192 fill varies in height run-to-run — it is an animated/partial clear.) Two fills are exact 4 bpp
   (`w*4 == stride`); the two single-row fills are not, consistent with span/line writes rather than full surfaces.

**What this re-frames.** The firmware registers guards at **stride 896** (field `7 << 7`) and then draws at **stride
8192/2176** (fields `64<<7` / `17<<7`) into **`0x80xxxxxx`** while the guards sit at **`0xBDxxxxxx`**. On real
hardware that same firmware draws successfully, so the emulator's **depth-5 command-word field decode must differ
from the depth-3 layout it currently reuses** — specifically the **stride field bit position** and the **dst
high-bits** (`hiDst = w0 & 0x60000000` yields 0 here, stranding the fills at `0x80…` instead of `0xBD…`). The open
task is therefore **not** "re-read the lab's records" but **RE of E113's depth-5 OSD command word**: find the real
stride and dst-high field positions (and bit22's role) so a depth-5 fill at stride 896 / base `0xBDxxxxxx` is what
`guarded()` sees. That needs the firmware's OSD command *emitter* (whatever enqueues the `0x510` words into
`DRV_CMDBUF`) disassembled — genuinely multi-session, but now pinned to one decode question rather than an
open-ended geometry hunt.

**Decode is self-consistent — two candidate roots (2026-11-15).** Reading the full `cmdFill` (`engine.mjs:98`) and
re-deriving the four words with the engine's own `sizeM1`/stride formulas confirms the depth-5 decode is internally
coherent: the firmware genuinely draws 544 px and 2048 px surfaces at **4 bytes/pixel** (`width*4 == stride` for the
two full-surface fills). So the words are not mis-decoded garbage; the firmware means exactly what it says. That
narrows the EVF stop to two testable hypotheses, neither cheaply decidable without more evidence:

1. **`guarded()` is too strict.** `engine.mjs:91` requires an **exact `stride === gstride`** match. The real OSD
   engine's surface guard may instead be a **base+size range** check. Stride-exact matching holds for the depth-3
   reference bodies (one stride per plane) but breaks when E113 issues **multiple strides** (8192, 5632, 5504, 2176)
   into the same plane. If real HW range-checks only, these fills are in-bounds and pass. Relaxing this is a
   model-gated **design change** with regression risk and is **unverifiable** until the rest of the E113 boot runs,
   so it is not made now — it is recorded as the leading hypothesis to test once a second signal is available.
2. **dst high-bits differ at depth 5.** The fills decode to `0x80xxxxxx` (`hiDst = w0 & 0x60000000` = 0 for
   `w0=0x510`); the guards sit at `0xBDxxxxxx`. Depth 5 may source the address-high bits from a different field than
   depth 3. No bit combination of `w0`/`w[2]` tried so far maps `0x80aa42c0` onto a `0xBD…` guard, so this needs the
   emitter's address computation, not guesswork.

Deciding between these needs the firmware's OSD setup/emitter disassembled (how it computes the plane base/stride it
*registers* vs. the base/stride it *draws*), or a hardware-side observation of the engine's guard behaviour — both
out of scope for a single session. The depth-5 **format gate** remains in and regression-free; the guard reconcile
is the open, now-well-posed task.

**Refinement — the address region, not the stride, is the primary blocker (2026-11-15).** Testing hypothesis 1 on
paper kills it as a standalone fix: the fills decode to `0x80xxxxxx` and **every** guard sits at `0xBDxxxxxx`, so
`addr < base` for all of them — even a relaxed **base+size range** guard would reject these fills, because they are
in a different region entirely. So hypothesis 2 (address decode) dominates: the depth-5 fill's destination resolves
to the wrong region. The most likely mechanism is a **missing engine plane-base register**: `cmdFill` treats
`w[2]>>3` as an absolute low address, but on E113 it is probably an **offset** added to a plane base the firmware
programs into an engine MMIO register during OSD setup (the same setup that registers the `0xBD…` guards). The
current engine model has no such base-add, so the offset `0xaa42c0` is stranded at `0x80aa42c0` instead of landing
in the `0xBD…` plane. **Probe result (negative, recorded per document-everything):** instrumenting the engine's
MMIO `write()` across the whole E113 EVF boot caught **no** store of a `0xBD…`/`0xBC…`/`0x3D…` value to any engine
register. So the plane base is **not** programmed into an engine register as a raw plane address — the "missing
base-register add" mechanism, as stated, is **wrong**. In raw (pre-`bus`) terms the mismatch sharpens: the guards
sit at raw `0x3D86AA00`… (≈1 GB, absolute DRAM), while the fills' `w[2]>>3` offsets are raw `0xaa42c0`… (≈11 MB) —
magnitudes apart, and adding a fill offset to a guard base overflows that guard's `stride*height` ≈ 1 MB extent. So
the fill destination is computed from a base this model never sees, and it is **not** a static engine register.
**Next session, re-aimed:** find where the firmware's OSD setup computes the draw base — likely a value handed in by
a lab stand-in (the record/plane-geometry or PER_SIO setup) that currently returns geometry the firmware turns into
`0xBD…` guards but a different base for the draws. Trace the firmware's address computation for the `0x510` fills
back to its inputs rather than looking for a single register. Until that source is found, no base is assumed —
guessing it would fabricate a result.

**Honest status:** registry init, property-store init, the settings-DB store wall (Wall 1) **and** the
device-open PER_SIO-serial wall (Wall 2) all pass and are regression-free (test-pure 273/273, purity 255/255;
E143 tft `state=264`, E120 tft/EVF `state=264`). Both E113 paths now reach the first UI draw (`H-UIIN`). The EVF
OSD **depth-5 format** is now modelled (engine gate above) and no longer the stop; EVF now stops one layer
deeper, at a `GUARD` **stride/format mismatch** (7 guards *are* registered at stride 896 / 1 bpp, but E113's
depth-5 fills use 4 bpp strides 2176/8192 — see the correction above), still surfacing as the same
`divu`-by-zero at `0x7d9962c0`. TFT still stops at its separate wild-jump to `0x8010`. `modelcheck E113` /
fully-usable menu is **not** reached yet. The PER_SIO stand-in (fork commit `74b5c12`) and the depth-5 engine
gate are committed to the fork; the latter is regression-free. No result was fabricated.

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
