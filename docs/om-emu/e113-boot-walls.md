# OM-Emu E113 boot walls (EVF live-view and menu)

Status as of 2026-11-15. Goal: E-M10 Mk I (E113) menu fully usable in the OM-Emu lab, all
tests green. Walls are tackled in boot order; each is a real porting gap, none is faked past.
Golden body stays E143 (95/95 checks, 273/273 facts).

## Summary: boot-order walls on the menu/nav path (device tft)

| Wall | Where it stops | Status |
|------|----------------|--------|
| **A — H-TIMER** | `software_timer_init` (display.mjs), before any UI | **CLEARED** (commits this session) |
| **C — CameraCommon singleton** | app bring-up, `0x509af730` (disasm), fault=2 reading `0xbfd2a000` (an object the registry put just under the 512 MiB chip top) | **CLEARED** — physical DRAM chip is 512 MiB, not the gui-managed top; `dram_chip` board fact |
| **B — EVF divu + GUARD** | EVF-only `play_bring_up` H-UIIN (play.mjs:1264) | OPEN, downstream, EVF path only |

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

## Wall B — the EVF wall: divu-by-zero then OSD GUARD (downstream)

Reachable only by seeding past the divu. Empirically confirmed this turn
(`scratchpad/seedprobe.mjs`): splitting `PlaySession.enter` into `NavSession.enter` → seed →
`play_bring_up`, writing `0x6055f944 = 0x300` after the lab is brought up:

- Clears the divu at `0x500052a0` (`divu d2,d1`, divisor = global `0x6055f944`, which stays 0).
- Boot then stops at the **next** wall:
  ```
  H-UIIN start: 0x5003407e fault=2  ne ERROR GUARD
    arg 0x805602dc  value 0x00001600  words 00000510 00000000 02b016e0 166c7800
  ```
  Decoded with the engine cmdFill decoder: op=0x10 fill, dst = bus(w2>>>3) = 0x805602dc
  (== arg), stride = ((w3>>>23)&0x1ff)<<7 = 5632 = 0x1600 (== value). The fill's stride does
  not match any registered OSD guard.

So **the divu and the GUARD are two faces of one skipped init**: `0x6055f944` is one geometry
global, the OSD guard geometry is another; both would be set by E113's display-geometry init,
which the lab's E113 bring-up does not run. `native_init` (OSDDRAW_INIT) *does* run clean for
E113 (PlayLab is an OsdLab) — so the missing write is a separate runtime display path, not the
per-step S-DISPSYNC release and not native_init itself. **`display.sync` is no longer the leading
suspect even here:** S-DISPSYNC is a per-step commit that runs *after* bring-up, whereas
`0x6055f944` is read by H-UIIN during `play_bring_up`, so the write that sets it must be a
bring-up-time init, not the per-step release. The link from any `display.sync` port to the
`0x6055f944` write is UNPROVEN and now looks unlikely. **With Wall C cleared, the menu boot now
reaches this same `0x500052a0` divu directly** (no evf seed needed) — so Wall B is now the single
active blocker on the menu path, and the likely root is a skipped display/OSD-geometry bring-up
init that sets `0x6055f944`, to be identified next.

## Honest bottom line

The "menu fully usable" goal is **not met**, but two of the three walls are down. Wall A
(H-TIMER) is **cleared** (generic timer-init port). Wall C (CameraCommon singleton) is **cleared**:
root-caused not to a skipped init but to the lab undersizing E113's DRAM — the physical chip is
512 MiB, while the lab mapped only the gui-managed heap top (~491 MiB), so a registry allocation at
`0xbfd2a000` (just under the 512 MiB top) was unbacked. Fixed with a `dram_chip` board fact that
floors the mapped DRAM to the real chip, leaving the `dram_size` heap-top fact untouched. The menu
boot now advances to **Wall B** (`0x500052a0` divu-by-zero, global `0x6055f944 = 0`), a skipped
display/OSD-geometry bring-up init still to be identified — now the single active menu blocker.
No test has been made to pass by faking it.

**Course corrections recorded (two dead ends retired):** (1) the "port E113's third `display.sync`
form" theory — `display.sync` drives the per-step S-DISPSYNC commit, which runs after bring-up,
while the walls are bring-up-time. (2) the "display-driver command 12 / `0x501d59fc` / `0xa0a665a8`
table init" theory for Wall C — the probe (wallc_order.mjs) proved that chain is never reached on
the faulting path; the real cause was DRAM size. Both are kept above as documented dead ends.

See also: `memory/e113-boot-wall3.md`, `docs/om-emu/e113-touch-read-const.md`,
`docs/mods/related-projects.md` (Wall-B EVF analysis).
