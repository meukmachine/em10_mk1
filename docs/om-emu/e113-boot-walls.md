# OM-Emu E113 boot walls (EVF live-view and menu)

Status as of 2026-11-15. Goal: E-M10 Mk I (E113) menu fully usable in the OM-Emu lab, all
tests green. Walls are tackled in boot order; each is a real porting gap, none is faked past.
Golden body stays E143 (95/95 checks, 273/273 facts).

## Summary: boot-order walls on the menu/nav path (device tft)

| Wall | Where it stops | Status |
|------|----------------|--------|
| **A — H-TIMER** | `software_timer_init` (display.mjs), before any UI | **CLEARED** (commits this session) |
| **C — CameraCommon singleton** | app bring-up, `0x509af730` (disasm), fault=2 reading `0xbfd2a000` (an object the registry put just under the 512 MiB chip top) | **CLEARED** — physical DRAM chip is 512 MiB, not the gui-managed top; `dram_chip` board fact |
| **B — divu-by-zero** | H-UIIN `0x500052a0`, divisor `0x6055f944 = 0` (menu + EVF paths) | **SOLVED (divisor)** — stand-in `0x6055f944 = 0x800` clears it and settles the OSD guards; rest of the serial-flash block still 0 (awaits SPI dump) |
| **D — GUI_CREATOR display-init** | SRAM-stack init `0x5003402d`: denied read `0x98700000` (`0x5003407e`), then completion poll `0x50034232` (fault=7) | **CHARACTERIZED** — standard lab HLE classes (stub page + completion-flag), need E113 addresses: stub `0x98700000`; satisfy poll word `table[0]` @ disasm `0x5005F6AC` |

Clearing Wall A moved the E113 menu boot from "fails before any UI" to deep inside the app
bring-up (Wall C, in CameraCommon bank 0x7e34). Clearing Wall C (E113's physical DRAM chip is
512 MiB) then advances the menu boot to the same divu-by-zero as Wall B. Wall B is on the separate
EVF live-view path (device evf) but the menu path now reaches the identical `0x500052a0` divu.

## Wall A — the menu wall: `software_timer_init` (H-TIMER)

`bin/nav.mjs --model E113 'MENU'` throws:

```
H-TIMER: the E143 address 0xa32ca47c has no place in E113's build (out/port/E113/port.json)
  at NavLab.software_timer_init (src/lab/display.mjs:784)
```

`display.mjs:784` runs `refuseUnmapped(HW_TIMER_PTR, 'H-TIMER')`, where
`HW_TIMER_PTR = A(0xA32CA47C)`. On E143 that is a RAM pointer the hardware-timer init keeps
the timer table in; the SW-timer-init guard reads it to assert the HW timer is not yet
programmed (ptr == 0 and low-RAM channels == 0).

**E113 has no such pointer.** E113's HW-timer init (`0x5005bb48`, mapped from E143
`HW_TIMER_INIT 0x500642dd`) walks a **fixed table in flash rodata**:

```
5005bb55: mov d2,a0
5005bb55: add 0x7DA62870,a0     ; table base, indexed by ch*8
5005bb5b: movhu (a0),d0         ; entry fields at +0/+2/+4/+6
```

This is the "fixed table" HW-timer form the lab already knows about
(`display.mjs:137-139`, `hwTimerTable = t.table ?? sim.u32(t.ptr)`). The porter already
derives it correctly: `FACTS.E113.hw_timer = {table: 0x7DA62870, entry: 8, enable: 4}`
(disasm-confirmed). Because the fixed form has no `ptr`, `bin/port.mjs` places
`HW_TIMER_PTR (0xA32CA47C)` as **unmapped** (ports.mjs:5892,
`{ to: facts.hw_timer.ptr ?? null }`).

**This is a generic lab gap, not an E113 quirk.** `software_timer_init` is hardcoded to the
ptr form and to E143's guard sub-function addresses. **E120 hits the identical wall** — so no
fixed-table body currently boots its menu in the lab. Likely also E144/E146/E147.

### Resolution (CLEARED this session)

`software_timer_init` now branches on the hardware-timer form (`fact('hw_timer').ptr === undefined`):

1. **Pointer form (E143 golden and kin)** keeps the full guard apparatus verbatim — the ptr
   pre-check, the `HW_TIMER_GUARD_RETURNS` return guards, the `HW_TIMER_PROGRAM` "would program
   HW" guards, and the stub/log checks. E143 menu boot is unchanged (verified: panel 329, 229
   engine cmds).
2. **Fixed-table / pool form (E113/E120)** runs the init plainly and judges it only by its
   firmware error log. Its call graph reads the clock and second timer-unit **stub** pages
   (`0x98800000` / `0x9b800000`, intentional stubs) and calls none of E143's HW-timer guard
   functions, so the E143 stub/guard/log checks do not apply to it.

Plus a second gap on the same path: E113's software-timer init allocates its 16 timer objects
from a **slot pool** (free-list bitmap at `0xa0a55e20`), which must be seeded first or the
`acre_tmr` create finds no free slot and logs firmware error `0x19001802`. The seed
(`MODEL_INFO.timer_pool_init.fn = 0x50005a7b`) was run only in the time-mode `boot_time()`
path; it is now also run in the menu/nav `software_timer_init`, guarded by
`this.timer_pool_seeded` so neither path double-seeds. (The seed is reached in firmware only
through an init-fn table the HLE boot never iterates, so the lab must run it natively.)

Result: the E113 menu boot clears H-TIMER and advances to **Wall C** below.

## Wall C — CameraCommon singleton returns a garbage object (current menu blocker)

After H-TIMER, `bin/nav.mjs --model E113 'MENU'` stops in app bring-up (before any nav step is
recorded) with:

```
stopped at 0x7e340750 (disasm 0x509af730) fault=2  address=0xbfd2a000
```

The faulting function (`0x509af647`, bank 0x7e34 CameraCommon) queries a lazily-built singleton
(getter `0x5069dc3a`, instance pointer `0x6044eb38`), then calls its **vtable+48** method with
arg `2`:

```
509af6f3: call 0x5069dc3a       ; a0 = singleton
509af6fa: mov (a0),a1           ; a1 = vtable
509af6ff: mov (48,a1),a1        ; a1 = vtable method at +0x30
509af702: mov 2,d1
509af704: calls (a1)            ; → d0
509af706: mov d0,a2             ; a2 = returned object = 0xbfd2a000 (garbage)
...
509af719: cmp 0,a2
509af71b: bne 0x509af730        ; non-null → dereference
509af730: mov (a2),d0           ; FAULT: read *0xbfd2a000
```

Had the method returned **null**, the `0x509af71d` path logs and returns 0 cleanly. But the
method does **not** return null — it returns a valid object, at `0xbfd2a000`. The fault is not a
garbage pointer; it is a **real object the registry placed in physical DRAM the lab never mapped**.

### Root cause (CLEARED): the physical DRAM chip is 512 MiB, not the gui-managed top

`0xbfd2a000` sits just below `0xC0000000` — the top of a **512 MiB** DRAM chip. The faulting
address is not garbage; it is where E113's CameraCommon registry (method `0x5069e3dd`, bank 0x7e02,
**kind=2 get-or-create** per arg `d1=2`) allocates/returns its app objects: in physical DRAM
**above** the firmware's gui-managed heap top.

The conflation that caused the fault: the lab used `dram_size` (the derived `dramSize` fact —
E113's value `0x1d971200` ≈ 474 MiB) as the size of DRAM to **map**. But `dram_size` is the
firmware's **managed-heap top**, a software placement, not the chip size. On E143 the two coincide
(heap top == 384 MiB chip), so the lab never had to distinguish them. On E113 the GUI is placed
lower than the chip top: the managed heap ends at ~474 MiB (the image extends the mapping to
~491 MiB, top ~`0xBEB91020`), but the **physical chip is 512 MiB** (top `0xC0000000`). The registry
hands back an object at `0xbfd2a000` — inside the chip, above the mapped region — and
`0x509af730 mov (a2),d0` faults (fault 2, unbacked) reading it.

The E118-family chip is 512 MiB: the `dramSize` comment in `src/fw/facts.mjs` lists E118 and E120
with GUI banks ending at `0xc0000000`. E120/E122 (same board) place their GUI at the chip top, so
their *derived* `dram_size` is already 512 MiB and they map the whole chip; only E113 places the
GUI lower and so undersized its map. This is the **documented E130 precedent** verbatim (same
comment): E130 "with 384 MiB mapped, faulted in a word copy at `0x50b0c026` whose last destination
was `0xbfc59440`, and runs on with 512 MiB" — `0xbfc59440`, like E113's `0xbfd2a000`, is an
allocation just under the 512 MiB top.

**The fix** (`src/fw/models.mjs` E113 board, `src/machine.mjs`): a new optional board fact
`dram_chip = 512 << 20`, the **physical** chip size, floored into the mapped `dramBytes` in
`createMachine` right after the image-end floor. `dram_size` (the gui-top fact) is left untouched,
so the 273/273 fact-check still holds; only the lab's physical DRAM window grows to the real chip.
For bodies without the fact (E143, …) the floor is a no-op.

**Verified** (scratchpad/wallc_run.mjs, no hooks, real fix in place):

| mapped DRAM | result | wall-time |
|---|---|---|
| ~491 MiB (image-end, baseline) | `stopped at (disasm 0x509af730) fault=2 address=0xbfd2a000` | 0.63 s |
| 512 MiB (`dram_chip` fix) | `stopped at (disasm 0x500052a0) fault=6` (the divu-by-zero) | 17 s |

Mapping the full chip backs `0xbfd2a000`, clears the fault, and advances the menu boot ~30× deeper
to the same divu-by-zero as Wall B (`0x500052a0`, below). Wall C is cleared.

### Dead end (recorded): the display-driver "command 12" / `0x501d59fc` / `0xa0a665a8` theory

An earlier revision of this doc root-caused Wall C to a **skipped display-driver table init**:
the registry lookup supposedly bottomed out in display driver `0x501d59fc` (bank 0x7db6) reading
an uninitialized base-pointer table at `0xa0a665a8`, fixable by issuing the driver's **command 12**
to populate it. **That is empirically false.** A one-shot probe arming hooks at the CameraCommon
entry (scratchpad/wallc_order.mjs) showed `0x502140cc` and `0x501d59fc` are **never reached on the
faulting path** — the fault at `0x509af730` happens before that chain runs. The `0xa0a665a8 = 0`
reading was real but irrelevant to this fault. The "command 12" next-task is withdrawn.

(An even earlier revision blamed the missing `display.sync` API form; that was already retired —
`display.sync` drives the per-step S-DISPSYNC commit, which runs *after* bring-up, whereas Wall C
faults during initial bring-up. `FACTS.E113.display.sync` is still a genuine separate porter gap
for per-step display commits — E113 uses a third form `api_disp_Disp_Sync_Update_Osd` /
`api_disp_Disp_Sync_Force_Update`, request body `0x501fdcb1`, image-sync body `0x501fdf8c` — but it
is not the fix for any boot wall.)

Probe notes: a lab hook is a **pre-instruction** breakpoint — reaching a hooked PC does **not**
mean that instruction faulted. The decisive test (wallc_run.mjs) uses **no** hooks and simply runs
`NavSession.enter()` with the real fix, comparing the stop address. Hooking `0x509af730` and
throwing before it executes (earlier wallc_512.mjs) only proves the PC was reached, and misled an
earlier analysis into "fault still here" under 512 MiB — a false positive now corrected.

## Wall B — divu-by-zero (root-caused: a serial-flash config block the lab cannot read)

**ROOT CAUSE FOUND (2026-11-16): `0x6055f944` is a 36-byte config block read from the camera's
serial flash at boot — data that is NOT in the firmware image and NOT modeled by the lab.**

The divu helper `0x50005283` (called from H-UIIN dispatch `0x50004061`/`0x5000406d`) computes
`quotient = (size << 9) / mem16[0x6055f944]`, twice, with no zero guard. `0x6055f944` is 0, so
`divu` at `0x500052a0` faults (fault=6, divide-by-zero). At `0x500052d7` the helper later compares
the divisor against **2048 (0x800)**, the natural/expected geometry value — confirming `0x6055f944`
is a display/OSD pitch, not garbage.

**Who writes `0x6055f944`:** exactly one init, `0x50003cb2` (populate loop `setlb` at `0x50003d79`).
It is a **serial-flash read**:
- zeroes 36 bytes at `0x6055f944`, then calls `0x500016ee`; if that returns −1 it bails (flash not
  ready) and the block stays 0 — so even the real camera needs flash present here;
- sets the flash-controller enable bit, then in the loop writes read-commands `0x838 + d1`
  (`d1` = 0,4,…,32, nine words) to the **flash command port `0x98d01800`** and reads each word
  back from the **flash data port `0x98d01804`**, storing them into `0x6055f944 + d1`;
- a sibling read (command `0x800`) folds a word into `0x6055f904`;
- on success sets the damage/config-initialized mark `0x97f00264`.

**Why the lab has `0x6055f944 = 0`:**
1. The lab's `store_init` comment (`src/lab/menu.mjs:752-756`) **already names this init a known
   HLE gap**: *"the boot's init `0x50003cf9` (not run by the lab) has not set (`0x97f00264`)"* —
   `0x50003cf9` is inside `0x50003cb2`. The lab deliberately does not run it.
2. Probe `scratchpad/wallb_gate.mjs` (no-seed, hooks on `0x50003946`/`0x5000395f`/`0x50003cb2`
   before the divu) returns `seen = {}` — the init and its caller are **never invoked at all** in
   the lab bring-up; it is skipped, not gated-off.
3. `src/fw/board.mjs:388-394` + `src/fw/models.mjs:729` confirm `0x98d0xxxx` is the **flash
   controller** register/program page and *"the lab does not model the flash controller"* — its
   stub page returns 0, so even native-running `0x50003cb2` would read zeros.
4. The firmware image has only two unpacked blocks — blk00 (loaded) and blk01 (the 25 MiB resource
   block). **Neither is the serial flash.** The nine config words live in the camera's SPI flash,
   per-camera, and are not in anything the emulator holds.

H-UIIN genuinely *consumes* this block: the function around `0x50003fc0` copies fields out of it
(`0x6055f94a`, `f94c`, `f956`, `f958`, …) via `0x5000161d` into a UI object. So the block must be
populated; this is **not** a lab bring-up bug reaching an init it shouldn't.

**Downstream GUARD (same root):** seeding `0x6055f944 = 0x300` (old `scratchpad/seedprobe.mjs`)
clears the divu but then stops at an OSD fill-stride GUARD (`stride 5632 = 0x1600`, dst
`0x805602dc`). That is the same block being inconsistent — the other config words are still 0 — not
a second independent wall. A correct fix must supply the **whole** consistent block, which is why a
single-field guess cascades.

**This ties Wall B to the [[bootloader-dump-goal]]:** the authoritative `0x6055f944` values come
from the camera's SPI flash. Until that flash is dumped, the only ways forward are (a) a documented
*stand-in* seed derived from the lab's known E113 display geometry (the same HLE-gap pattern as
`boot_stage`/`sections_loaded`, which write stand-in boot state the lab does not run) — accepting it
may not byte-match hardware; or (b) modeling the flash controller to serve a constructed config
image. Both need the field layout worked out so every H-UIIN/OSD consumer stays consistent.
`display.sync` is **not** involved (retired dead end — it is a post-bring-up per-step commit).

### Update 2026-11-16 (b): stand-in value confirmed `0x6055f944 = 0x800`, and Wall D behind it

Per the user's "documented stand-in seed" decision, convergence harness `scratchpad/wallb_seed.mjs`
seeds the block in `NavSession.lab_created` (before `bring_up`) and runs the full menu bring-up.
Result: **`0x6055f944 = 0x800` (2048) is the correct divisor stand-in.** It clears the divu *and*
drops the OSD fill-stride GUARDs from fatal to non-fatal warnings (the `0x300` guess above left them
fatal). 2048 matches the helper's own compare-against-2048 at `0x500052d7` — the expected geometry
pitch. This is a confident, derivable single-field value; the rest of the block stays 0 (documented
unknown, awaiting the SPI dump).

**Behind the divu is a new wall ("Wall D"): GUI_CREATOR's native display/CPU init hits HLE gaps the
lab has standard machinery for but has not mapped for E113.** With `f944 = 0x800`, menu bring-up
(`menu.mjs:498`, `this.call(GUI_CREATOR, …, 200000)`) proceeds deep into a display-controller init
that runs on an **SRAM scratch stack** (`sp = 0x47000fd8`, a0 = `0x99200084` = CPU0 context). Call
chain to the faults: `0x50006cbc → 0x500002fc → 0x5003402d` (function writes per-CPU control bytes
`0x8ff0007c/7d/7e`). Two sequential blockers, both **known lab HLE classes**:

1. **Denied read `0x98700000`** at `0x5003407e` (`mov (a2),d0`; RMW `or 256`; writes 25 to
   `0x98700004`, plus `0x9b801328/133c`) — an unmodeled peripheral page. Fix class = a **stub page**
   (like the flash controller's `0x98d00000`, board.mjs stub_pages: "keeps what is written, no HW
   transfer"). Adding `0x98700000` to the stub pages clears it (harness `STUBS=98700000`).

2. **Infinite completion poll** at `0x50034232` (fault=7, the lab's loop/budget guard): busy-waits
   `*(table[*(0x8ff0007c)]) > 0`, table at disasm `0x5005F6AC`, index 0. This is a native
   HW/inter-CPU completion the lab never drives — exactly the class the lab satisfies elsewhere via
   completion-flag machinery (`menu.mjs` `PANEL_FLAG`/`S-PANELDONE`, `SCPU_WAITS`, and the
   `this.observe(poll, …)` pattern at `menu.mjs:401-407` that "stores 1 in the word"). E113 needs the
   equivalent poll word mapped + satisfied.

A related **gate** (not on this path, recorded for later): `0x50031dcd` runs a *different* panel init
`0x500340d3` only when `*(0x98E00038) | *(0x98E0003C) != 0` (getter `0x5004abfa`, table base
`0x98E00004`). The region `0x98E00000` is currently mapped non-zero in the lab (it did not fault),
so that panel init would also run; forcing those gate words to 0 would skip it. Not reached before
the Wall-D faults, so deferred.

**Net:** Wall B's divisor is solved (`0x800`). "Menu fully usable" now depends on finishing the E113
display-init port behind it (Wall D): add the `0x98700000` stub page and satisfy the `0x5005F6AC`
completion poll, both standard lab mechanisms needing E113 addresses. Not a fabricated pass — the
menu does not yet reach a usable state. Probes: `scratchpad/wallb_seed.mjs` (seed+stubs harness),
`wallb_gate2.mjs` (gate regs), `wallb_bt.mjs` (SRAM-stack backtrace).

## Update 2026-11-16 (c) — Wall D cleared at the poll; Wall E = DISPLAY OPEN fatal

Wall D's two sub-blockers are now handled as standard lab mechanisms (committed in `src/fw/board.mjs`,
E118 family):

- **`0x98700000` stub page added.** E113's display/CPU init writes/polls a 6th serial channel at
  `0x98700000` (RMW `or 256`; writes `25 → 0x98700004`, plus `0x9b801328/133c`), denied by default.
  It is now an E118-family `stub_pages` entry. Safe for the siblings (E118/E120/E122/E-M5II): the
  page is denied by default, so any body that already boots never reads it — the stub is inert unless
  accessed. (E143 is a different family entirely.)
- **`[0x9870001C, 64]` stub_preset added.** The completion poll at disasm `0x50034232`
  (`bls` loops while `*(table[*(0x8ff0007c)]) ≤ 0`, table at disasm `0x5005F6AC`, idx0 = channel
  status reg `0x9870001C`) needs a `> 0` value. `64` matches the already-stubbed paired sibling
  channel `0x98400000` (`[0x9840001C, 64]`). Verified: boot advances past the poll.

**Wall E (new frontier): the "DISPLAY cmd 2 OPEN tft" bring-up step fatals.** With `f944 = 0x800`
seeded and the `0x98700000` stub in place, the menu bring-up reaches the display-task OPEN-tft step
(lab step label `DISPLAY cmd 2 OPEN tft (code 1)`) and hits an **infinite-loop panic**: disasm
`0x500d675a` (`ca 00  bra 0x500d675a`, self-loop) → lab reports `fault=7` (budget guard). The hang is
the tail of a fatal/assert handler whose entry is `0x500d65d2` (same function, +0x188).

Call path into the fatal (SRAM scratch stack `sp≈0x47000xxx`, so the stack scan is noisy — these are
the reliable anchors): display-open `0x5000f050 → 0x50006cbc → 0x5003a44f → … → error-logger
0x500012c0 → call 0x500d65d2` (fatal). Confirmed the caller via **MDR** at the fatal entry =
`0x7d99232e` = disasm `0x5000130e` = the error-logger's `call 0x500d65d2` return site (the `sp+0`
stack slot `0x50001ea0` was stale — do not trust raw SRAM stack slots here). The error record fields
at the hang: `d2 = a3 = 0x1300`, `a2 = 0x20000`, fatal arg `d0 = 0x20000`, `d3 = 0x60000038` (a DRAM
object pointer). The error-logger (`0x500012c0`) logs `a2` + `(4,sp)` via `0x50008a33`, stores a
record into a table keyed off counter `0xa0a433d0` (capped at 100 entries) using base `0x2c000000`,
then calls the fatal handler.

**Exact trigger found.** The error-logger entry is `0x5000128c`, signature
`fatal_error(d0 = code, a0 = subject, d1 = extra)` — it logs, stores a record (table keyed off
counter `0xa0a433d0`, base `0x2c000000`), then unconditionally calls the fatal handler. Its caller
(MDR at entry = `0x7d9cb46f` = disasm `0x5003a44f`) is the display-OPEN sub-function at
**`0x5003a448`**:

```
5003a43c: mov d3,a0            ; d3 = 0x60000038  (the display-buffer descriptor object)
5003a43e: mov (8,a0),a0        ; a0 = *(0x60000038 + 8)  -> the framebuffer pointer == NULL
5003a441: clr d0              ; code  = 0
5003a442: mov 131072,d1        ; extra = 0x20000  (128 KiB)
5003a448: call 0x5000128c      ; fatal_error(code=0, subject=*(obj+8)=NULL, size=0x20000)
```

So E113's display OPEN-tft requires a **128 KiB (`0x20000`) framebuffer** whose pointer is stored at
`*(0x60000038 + 8)`; in the lab that field is **NULL** (verified: `a0 = 0` at the error-logger entry)
because the allocation step that fills it never ran. The fatal is an allocation/null-buffer assert,
**same class as Wall D** (a display bring-up step the lab does not drive for E113) but one layer deeper.

**Structure of the descriptor.** `0x60000038` is a hardcoded global: `0x5003a280` does
`mov 1610612792, d3` (`1610612792 = 0x60000038`). Grepping the disasm for that immediate (objdump
prints immediates in **decimal** — a hex grep misses them) finds exactly two sites, and both *read*
it: the open path `0x5003a280`, and `0x50039fa9` which does `udfu01 28, d0; mov d0, a0;
add 0x60000038, a0` — i.e. **`0x60000038` is the base of a 28-byte-entry array of display-buffer
descriptors** (`entry[i] = 0x60000038 + 28·i`), each with `+4` and `+8` (the buffer pointer) fields.
There is **no absolute write** to the array anywhere in the image, so it is filled register-indirect
by a display-buffer init (passed the base as a pointer) that the lab does not run for E113 — leaving
every `+8` buffer pointer NULL. The required buffer size is `0x20000` (128 KiB) per the assert.

**Not a missing `display.init_2nd` fact.** That fact is derived from the trace string
`[DISP] <COM> :display_init_2nd` (facts.mjs `displayInit2nd`), which **only E125/E129/E132 carry** —
E113 does not, so the fact is correctly null and no `H-DISPINIT2` step runs. E113's display 2nd-part
init uses a different mechanism.

**The framebuffer is fixed hardware display-RAM, not a heap allocation.** The OPEN's address check at
`0x5003a200` compares the buffer address against a table of **fixed plane addresses** —
`0x400e0000`, `0x40100000`, `0x7fa0fc00`, `0xbffc0a00`, … (the `0x40000000` display-RAM region). So
`0x60000038[28·i].+8` is meant to hold one of these fixed plane addresses, written by a plane-setup
init — **not** a 128 KiB `malloc` (the `0x20000` is the plane's size/stride, used by the copy at
`0x500013f0`, not an allocation request). In the lab the field is NULL because that plane-setup init
never ran.

### Update 2026-11-16 (d) — Wall E fully root-caused: whole `0x60000000` display-buffer struct is unpopulated

**Correction to the earlier "fatal_error" framing.** `0x5000128c` is **not** a fatal logger — it is a
**buffer-register / range-validate** routine `reg_buffer(a0 = buffer addr, d0 = flags, d1 = size)`. It
is called *normally* throughout the plane setup (e.g. `0x500d6a07`'s body calls it with real planes
`a0 = 0x400e0000, d1 = 0x20000`). It unconditionally tail-calls `0x500d65d2`, which is the actual
validator and **normally returns**. `0x500d65d2` classifies the buffer (via `0x500d65c9`) and
**range-checks** the address against the valid framebuffer apertures:

- `0x40060000 .. 0x40690000`  (and buffer+size end in range)
- `0x80060000 .. 0x80690000`
- `0x47360000 .. 0x47980000`

A buffer **in range returns OK**; an out-of-range (or NULL) buffer falls through to `0x500d66c8`,
logs, and **hangs at `0x500d675a`** (`bra .`), which the lab reports as fault 7. `0x500d65d2` itself
does **not** dereference the buffer — it only range-compares — so a valid in-range pointer satisfies
it without the region being mapped.

**The real gap: the entire `0x60000000` display-buffer struct is NULL.** The DISPLAY-OPEN-tft sub
(`0x5003a4xx`) reads **many** plane pointers out of a struct based at `0x60000000` and hands each to
`reg_buffer`:

```
0x5003a43e: mov (8,a0),a0     ; a0 = *(0x60000038 + 8)   = entry[0].+8   -> reg_buffer   (plane A)
0x5003a456: mov (36,a0),a0    ; a0 = *(0x60000038 + 36)  = entry[0].+36  -> reg_buffer   (plane B)
0x5003a472: mov (0x60000014),a2 ; a2 = *(0x60000014)                      (deref'd below)
0x5003a478: mov (0x60000018),a0 ; a0 = *(0x60000018)                     -> fill/compare
0x5003a4b8: mov (4,a2),d0      ; **reads buffer memory** at a2+4  (compared, 0x5003a4be)
0x5003a4d8: mov (0x60000018),a0 ; a0 = *(0x60000018)     = NULL          -> reg_buffer   (2nd hang)
```

Verified with `scratchpad/walle_seed.mjs` + `walle_trace.mjs`: seeding only `0x60000040`
(`= entry[0].+8`) with `0x400e0000` clears the **first** hang, and boot advances to a **second**
`reg_buffer` call whose `a0 = *(0x60000018) = NULL` → same `0x500d675a` hang. So it is whack-a-mole at
word granularity: `0x60000014`, `0x60000018`, the `0x60000038` descriptor array (`+8`, `+36` per
28-byte entry), `0x60000030/34`, etc. are **all** unpopulated.

**None of these fields is absolute-written in the image** (grep of `,(0x600000xx)` finds only status
fields `+0x74/78/7c/84`, never the pointer fields). They are filled **register-indirect** (base
`mov 0x60000000, aN` then `mov <plane>,(off,aN)`) by a display-buffer allocator that the lab never
runs for E113 — the same class of gap as every wall before it, but here it is a whole subsystem
(multi-plane display-buffer allocation across three physical apertures), not a single stand-in word.
Also note `0x5003a4b8` **dereferences** a plane (`mov (4,a2),d0`), so a pure range-valid stand-in
pointer is not enough for every path unless that aperture is also mapped (or the two compared reads
coincide).

**Fork (next decision).** (a) **Struct stand-in** — seed the whole `0x60000000` display-buffer struct
with a self-consistent set of in-range plane addresses (and, where a path derefs, back them with lab
pages), as a documented stand-in (the user's chosen pattern for display gaps); or (b) **run the real
allocator** — locate the register-indirect display-buffer init that fills `0x60000000` and run it as
an E113 bring-up step (more faithful, needs the three apertures modelled). Either way the field map of
the `0x60000000` struct must be worked out so every OPEN consumer stays consistent. Keep E143 golden
95/95 + facts 273/273 + pure 255/255 after any lab change. Probes: `scratchpad/walle_seed.mjs`,
`walle_trace.mjs` (reg_buffer + hang-block backtrace), `wallb_scpu.mjs`, `wallb_bt.mjs`.

## Update 2026-11-16 (e) — Wall E cleared (committed stand-in), Wall F reached

Per the user's decision ("Struct stand-in seed"), Wall E is closed with a documented stand-in,
wired through the normal board/model/lab path (no probe-only hack):

- **`src/machine.mjs`** — maps a RAM window for `BOARD.display_planes` (a plane aperture backing).
- **`src/fw/models.mjs`** (E113 only) — `board.display_planes = { base: 0x40060000, size: 0x40000 }`
  (inside the aperture `0x500d65d2` validates) and a `boot_seeds` list (`[addr, value, width]`).
- **`src/lab/menu.mjs`** — `seed_boot()` writes `MODEL_INFO.boot_seeds` to DRAM in `kernel_display`
  **after** `start()` (which zeroes the `0x60000000` struct) and **before** `open(device)` reads the
  plane pointers. No-op on models without `boot_seeds` (E143 golden untouched).

`boot_seeds` folds in both open stand-ins: Wall B (`0x6055f944 = 0x800`, the serial-flash divisor)
and Wall E (`0x60000014/18/30/34/40/5c = 0x40060000`, the display-buffer plane pointers). Each is
labelled a stand-in in the source, not a HW byte-match. With this committed, an E113 `tft` nav boot
runs ~18.6 s and the DISPLAY-OPEN hang is **gone**; facts 273/273 + pure 255/255 stay green.

Boot now stops at **Wall F — inter-CPU handshake** (`kernel.mjs:119` throws rather than hang):

```
inter-CPU handshake with CPU 1 (0x5001258b) from 0x7d9a7344 (disasm 0x50016324): only one CPU in the lab
```

`0x5001258b` is a **multi-core rendezvous / IPI barrier**. It reads this core's index from `(0x8050)`,
walks the per-CPU TCB table at `0x8ff00004[cpu]`, then **spins on the IPI flag register `0x8ff00000`**
until it reads `(1<<d2)-1` — i.e. every participating core has set its ready bit (`movhu (0x8ff00000)`
… `cmp d1,d0` … `setlb`/`lne` spin at `0x500125fb`). The dispatch side is `0x500127cb` (posts to the
target CPU indexed from the per-CPU struct `+324`). The flag reg `0x8ff00000` is referenced 27× across
the image — this is a pervasive core-sync primitive, not a one-off. In a one-CPU lab the other cores'
bits never set, so the real firmware would spin forever; the lab throws at the primitive instead.

This is the **first architecturally new wall** (multi-core), distinct from the memory-seed walls. The
lab already has partial second-core machinery — `src/lab/cpu2.mjs` (a `--cpu2 native` path that runs
core 2's code, with its own `xcpu_wait` park) and `src/lab/play.mjs`'s XCPU reply-flag model
(`XCPU_FLAG`/`_xcpu_wait_return`, wired for the E143 **playback** path, not the E113 nav/menu path).
Two broad fixes: **(a) barrier stand-in** — at each rendezvous, set the awaited `0x8ff00000` ready
bits so the barrier passes immediately (fast, same stand-in spirit as the seeds; the work the other
core would have done is skipped, surfacing the next wall); or **(b) run the other core(s) natively** —
extend the `cpu2.mjs` / `--cpu2 native` machinery to the E113 nav path so the rendezvous completes for
real (faithful, heavier). Probe: `scratchpad/wf_check.mjs` (clean E113 boot via the committed seeds).

## Update 2026-11-16 (f) — Walls F/G cleared (inter-CPU rendezvous stand-in), Wall H reached

Per the user's **"Barrier stand-in"** decision, the inter-CPU rendezvous is stood in rather than run
on a second core. It turned out to be **two** primitives on the menu bring-up path, cleared together:

- **Wall F — the doorbell post.** `XCPU_WAIT` (runtime `0x7d9b3fbb`, disasm `0x500127cb` =
  `A(0x5001258b)` for E113), an inter-core doorbell post/wait to a core the one-CPU lab does not run.
  Direct-called (the `call 0x500127cb` at disasm `0x5001631f`). Stood in by returning as its own
  `ret [d2,d3,a2],20` would — `hle.finish()`, which recovers the saved-reg mask + frame size from the
  entering `CALL` at `mdr` and returns to the caller with the stack/registers consistent.
- **Wall G — the IPI barrier.** `0x500125a3` (identity-mapped: E143 canonical == E113). Reached
  **indirectly** through a per-CPU vtable (`calls (a0)`, pointer at `perCPU+304..`), so `finish()`'s
  `CALL`-decode does not apply — `finish_calls()` handles the `calls`-reached case. The barrier reads
  the core count from `perCPU+380` (`d2`), sets its own ready bit in the `0x8ff00000` halfword
  (`movhu (0x8ff00000),d1; asl d0,d3; or d3,d1; movhu d1,(0x8ff00000)` at `0x500125db..ea`), then
  spins at `0x50012605`/`0x5001260b` until the halfword reads `(1<<d2)-1` — the other cores' bits,
  which never arrive in a one-CPU lab. Native, that spin is a `fault=7` deadman (`dc value 0x00002000`).

**Implementation.** `MODEL_INFO.xcpu_standin` carries `{ barriers: [0x500125a3] }`. In
`kernel.mjs install_guards`, when the fact is set, the lab hooks `XCPU_WAIT` **and** each barrier
address with one handler (`xcpuReturn`) that does `try { finish() } catch { finish_calls() }` — the
same return pair the lab's own `skip()` uses for the `IPI`/`SELFTEST`/`BOOTSYNC` barriers. Models
without the fact keep the hard stop. The earlier bespoke `hle.return_from_call` helper was removed:
`finish()` is byte-identical to it for the direct-call case, so it was redundant. The other cores'
side effects are skipped — a documented E113 stand-in that surfaces the next wall rather than hanging.

**Result.** Boot advances from ~18.6 s (the Wall F throw) past the whole multi-core rendezvous to
**~23.8 s**, where the kernel reaches its **task-startup sequence** and tries to start firmware task
entry `0x50031c0c` — *"not modelled"* (**Wall H**). That entry is E113's **CPU-connection init task**
(its log strings are `CPUCONN0`, `CPU1!`, `CPU2!`, `SCS`, `SCE`, `OIE`; it zeroes `0x8ff0018c`, next
to the board's `core_byte 0x8ff00188`). Allowing it to run natively (`extra_tasks`) lets it return and
exposes the **next** task start (`0x50032ed8`) — i.e. Wall H is a **chain** of kernel init-task starts,
the same shape E143 models with its `extra_tasks` lists + task modules (`cpu2.mjs` H-CPUCONN0,
`camapp.mjs`, `take.mjs`). This is a new, larger category of work than the single-seed walls A–G.

Verified green with the stand-in: E143 golden **95/95**, facts **273/273**, pure **255/255** (the fact
is E113-gated, so the golden model is untouched). Committed in `om-emu-src` (branch `e113-port`). Probe:
`scratchpad/wf_check.mjs` lands cleanly at the `0x50031c0c` task wall.

## Honest bottom line

The "menu fully usable" goal is **not met**, but two of the three walls are down. Wall A
(H-TIMER) is **cleared** (generic timer-init port). Wall C (CameraCommon singleton) is **cleared**:
root-caused not to a skipped init but to the lab undersizing E113's DRAM — the physical chip is
512 MiB, while the lab mapped only the gui-managed heap top (~491 MiB), so a registry allocation at
`0xbfd2a000` (just under the 512 MiB top) was unbacked. Fixed with a `dram_chip` board fact that
floors the mapped DRAM to the real chip, leaving the `dram_size` heap-top fact untouched. The menu
boot now advances to **Wall B** (`0x500052a0` divu-by-zero, global `0x6055f944 = 0`), now
**root-caused**: `0x6055f944` is a 36-byte config block the boot init `0x50003cb2` reads from the
camera's **serial flash** (command port `0x98d01800`, data port `0x98d01804`). That data is not in
the firmware image (only blk00 + the resource block exist) and the lab does not model the flash
controller, so the block stays 0 and the divisor is 0. The lab already lists this init as a known
HLE gap (`menu.mjs:752`). Per the user's decision, Wall B is closed with a **documented stand-in**:
`0x6055f944 = 0x800` (confirmed — clears the divu, settles the OSD guards, matches the helper's
compare-against-2048). Behind it sat **Wall D** (GUI_CREATOR display/CPU init — `0x98700000` stub
page, cleared) and **Wall E** (DISPLAY-OPEN reading an unpopulated `0x60000000` display-buffer struct
— cleared with the committed `boot_seeds` + `display_planes` stand-in, see Update (e) above). The boot
reached **Walls F/G**, the first multi-core walls (inter-CPU doorbell post `0x500127cb` + IPI barrier
`0x500125a3` spinning on `0x8ff00000` for the other cores' ready bits), now **cleared** with the
user-approved rendezvous stand-in (see Update (f) above). The boot now reaches **Wall H**: the kernel's
**task-startup sequence**, starting with the CPU-connection init task `0x50031c0c` — a chain of
kernel init tasks (a new, larger category of work than the single-seed walls A–G). The menu is **not
yet usable**; no test has been made to pass by faking it.

**Course corrections recorded (two dead ends retired):** (1) the "port E113's third `display.sync`
form" theory — `display.sync` drives the per-step S-DISPSYNC commit, which runs after bring-up,
while the walls are bring-up-time. (2) the "display-driver command 12 / `0x501d59fc` / `0xa0a665a8`
table init" theory for Wall C — the probe (wallc_order.mjs) proved that chain is never reached on
the faulting path; the real cause was DRAM size. Both are kept above as documented dead ends.

See also: `memory/e113-boot-wall3.md`, `docs/om-emu/e113-touch-read-const.md`,
`docs/mods/related-projects.md` (Wall-B EVF analysis).
