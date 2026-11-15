# OM-Emu E113 boot walls (EVF live-view and menu)

Status as of 2026-11-15. Goal: E-M10 Mk I (E113) menu fully usable in the OM-Emu lab, all
tests green. Walls are tackled in boot order; each is a real porting gap, none is faked past.
Golden body stays E143 (95/95 checks, 273/273 facts).

## Summary: boot-order walls on the menu/nav path (device tft)

| Wall | Where it stops | Status |
|------|----------------|--------|
| **A — H-TIMER** | `software_timer_init` (display.mjs), before any UI | **CLEARED** (commits this session) |
| **C — CameraCommon singleton** | app bring-up, `0x509af730` (disasm), fault=2 on a garbage vtable return | **OPEN** — current blocker |
| **B — EVF divu + GUARD** | EVF-only `play_bring_up` H-UIIN (play.mjs:1264) | OPEN, downstream, EVF path only |

Clearing Wall A moved the E113 menu boot from "fails before any UI" to deep inside the app
bring-up (Wall C, in CameraCommon bank 0x7e34). Wall B is on the separate EVF live-view path
(device evf) and only reachable with a seed workaround; the menu path does not hit it.

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

Had the method returned **null**, the `0x509af71d` path logs and returns 0 cleanly — so the bug
is that the vtable+48 lookup (component index 2 off the CameraCommon singleton) returns an
uninitialized/garbage pointer instead of null.

Instrumented (scratchpad/wallc.mjs, observe-hooks via `lab_created`): the singleton lives at
`0x6000d904`, vtable `0x7e3a472c` (runtime), vtable+48 method = runtime `0x7e02f3fd` = disasm
**`0x5069e3dd`** (bank 0x7e02, sdk-api). The method is **not** a simple array index — it runs a
multi-step type-registry / component-factory lookup (calls `0x50636252`, `0x5062f19f`, map ops
`0x50633718` / `0x50633902` / `0x50633a28`, and `0x502140cc`) keyed by type-id `0x1020b` and the
index arg. From the **very first** call it returns `0xbfd2a000` (a non-heap garbage pointer; a
valid lookup would be in `0x60000000-0x60800000`). So the registry those map ops consult is
empty/uninitialized in the lab, and the lookup falls through to a garbage slot instead of null.

Following the call chain down (the `0x1020b` sites are an sdk-api trace channel, not the
registry key — the lookup is the un-traced path):

```
CameraCommon 0x509af647 (0x7e34)
  -> sdk-api method   0x5069e3dd (0x7e02)   [0x1020b trace-wrapped]
    -> api_tft lookup 0x502140cc (0x7dba)   [0x7dc8… trace-wrapped]
      -> display-drv  0x501d59fc (0x7db6)   returns a0; caller does `mov (a0),a0; ret`
         => *(a0) = 0xbfd2a000  (the garbage that propagates up and is dereffed)
```

So the lookup bottoms out in the **display driver** (bank 0x7db6). The dispatch is `0x501d59fc`,
a 19-entry jump table at runtime `0x7dbfc33c` keyed by the component code (0..18). Its cases read
a **table base pointer held in RAM at `0xa0a665a8`** (a sibling region, `0xa0a665d4` etc.), then
index it by the instance `<< 4`. That `0xa0a66…` region is **uninitialized in the lab**, so the
base pointer (or the entry it points at) is garbage — the `0xbfd2a000` that propagates up and is
dereferenced. **Confirmed live** (scratchpad/wallc_tbl.mjs, a hook reading sim memory at the
garbage return): at the faulting return `0xa0a665a8 = 0x00000000` and `0xa0a665d4 = 0x00000000` —
both base pointers unset, exactly as the disassembly predicts.

**The init is a display-driver command, and the lab never issues it.** The driver's command
handler (dispatch `0x501d6a70`, cmp of codes 2/3/17/11/12/18) has a **command 12** case
(`0x501d6ada` → `0x501d6ae7`) that loads the base-pointer table address `0xa0a665a8` with size
512 and jumps to the fill/clear routine `0x501d6b87` — i.e. command 12 is the routine that sets
up the `0xa0a66…` driver tables. E113's lab bring-up never issues it, so the tables stay garbage.
This is **exactly the shape of Wall A**: a skipped bring-up init, reached in firmware only through
a path the HLE boot does not walk, that the lab must run natively (as it now does for the
timer-pool seed).

### Correction — this is NOT the display-sync (`display.sync`) gap

An earlier revision of this doc claimed porting E113's third `display.sync` API form would clear
**both** Wall C and Wall B. **That was wrong for Wall C** (and is unproven for Wall B). The lab's
`display.sync` consumer is **S-DISPSYNC** (src/lab/camapp.mjs), a **per-step release** run *after*
each nav/idle step to commit the GUI's pending display buffers — see its own comment, "After each
step the lab releases each device". **Wall C faults during initial bring-up, before any step and
before any S-DISPSYNC release runs**, so S-DISPSYNC cannot populate the table Wall C needs. Porting
the `display.sync` form remains a real porter gap (`FACTS.E113.display.sync` is null; E113 uses a
third form, `api_disp_Disp_Sync_Update_Osd` / `api_disp_Disp_Sync_Force_Update`, whose request
body is `0x501fdcb1` and image-sync body `0x501fdf8c`, structurally a near-record form with 15/16/18
code-compares but non-`_Multi_Ext` names and a type-in-d0 convention), but it is a **per-step
display-commit** feature, not the fix for either boot wall.

**Highest-leverage next task (corrected):** issue display-driver **command 12** (populate the
`0xa0a665a8` tables) during E113 bring-up — the direct analogue of the Wall A timer-pool seed.

Probe notes: scratchpad/wallc.mjs uses `NavSession.lab_created` observe-hooks. Observe-hooking
the `calls` site itself perturbs control flow (the lab's pass-through hook on a `calls` loops),
so inspect on straight-line instructions after the return (e.g. `0x509af719 cmp 0,a2`).

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
bring-up-time init, not the per-step release. Given Wall C turned out to be a skipped
bring-up driver-table init (display-driver command 12, above), the more likely root for Wall B is
**a similarly skipped display/OSD-geometry bring-up init** — to be identified. The link from any
`display.sync` port to the `0x6055f944` write is UNPROVEN and now looks unlikely.

## Honest bottom line

The "menu fully usable" goal is **not met**. Wall A (H-TIMER) is now **cleared** — the generic
timer-init port that no fixed-table body cleared before (including the reference E120) now
works, and the E113 menu boot reaches deep app bring-up. The current blocker is **Wall C** (the
CameraCommon singleton returning a garbage component object), now root-caused to a **skipped
display-driver table init (command 12, populating the `0xa0a665a8` region)** — the direct
analogue of the Wall A timer-pool seed, and the corrected next task. **Wall B** (EVF) is a
separate skipped bring-up init still to be identified. Each is a genuine, bounded
reverse-engineering task; none is a config tweak. No test has been made to pass by faking it.

**Course correction recorded this session:** the previously-planned "port E113's third
`display.sync` form" was shown **not** to be the fix for either wall — `display.sync` drives the
per-step S-DISPSYNC commit, which runs after bring-up, while both walls are bring-up-time inits.
The `display.sync` form is still a real (separate) porter gap for per-step display commits, but it
was retired as the wall fix before any time was sunk into the large deriver change.

See also: `memory/e113-boot-wall3.md`, `docs/om-emu/e113-touch-read-const.md`,
`docs/mods/related-projects.md` (Wall-B EVF analysis).
