# OM-Emu E113 boot walls (EVF live-view and menu)

Status as of 2026-11-15. Goal: E-M10 Mk I (E113) menu fully usable in the OM-Emu lab, all
tests green. Two distinct boot walls block it. Neither is fabricated past; both are real
porting gaps. Golden body stays E143 (95/95 checks, 273/273 facts).

## Summary: two separate walls, two separate paths

| Path | Device | Wall | Where it stops |
|------|--------|------|----------------|
| Menu / nav | tft (default) | **H-TIMER** | `software_timer_init` (display.mjs:784), before any UI |
| EVF live-view | evf | **divu + GUARD** | `play_bring_up` H-UIIN (play.mjs:1264), after a clean native_init |

The menu path (the actual goal) fails **earlier** than the EVF path and for an unrelated
reason. The EVF wall is downstream and only reachable with a workaround applied.

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

### Why it is not a one-line fix

Two layers assume the ptr form:

1. **The ptr guard** (display.mjs:784-791) — reads `HW_TIMER_PTR`. For a table-form body there
   is no pointer; the low-RAM channel check (`6*ch+2`, actual hardware register state) still
   applies, but the ptr read must be skipped. Easy, fact-driven.
2. **The guard-function apparatus** (display.mjs:809-836) — hooks `HW_TIMER_GUARD_RETURNS`
   (`0x50064323/52/7D`) and `HW_TIMER_PROGRAM` (`0x50064478/9B/FA`), all **E143 code
   addresses that are UNMAPPED for E113** (confirmed: `A(0x50064323) = 0xfe064323`). The log
   must be non-empty (line 833) or it throws. E113's SW-timer init (`0x50052d0e`) calls
   `0x50052dbc / 0x5005306b / 0x50052cfb` — **none of the E143 HW-program functions** — so the
   "program" guards would never fire, and the "returns" guards can never populate (their
   addresses don't exist on E113).

So porting Wall A means either (a) finding E113's equivalents of up to 6 HW-timer
sub-functions and mapping them, or (b) making `software_timer_init` fact-aware: for the
table form, skip the ptr guard and relax the guard-log assertion (losing the "did not program
HW" verification that the ptr-form bodies keep). (b) touches shared code the golden bodies
run, so it needs the author's buy-in before committing.

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
per-step S-DISPSYNC release and not native_init itself. The `FACTS.E113.display.sync` null and
the third unported sync form (see related-projects.md and `memory/e113-boot-wall3.md`) remain
the leading suspect for the init that sets these, but the link to the `0x6055f944` write is
still UNPROVEN.

## Honest bottom line

The "menu fully usable" goal is **not met**. It is blocked first by Wall A (a generic
timer-init port that no fixed-table body currently clears, including the reference E120), then
by Wall B (the EVF display-geometry init). Both are genuine, bounded reverse-engineering tasks;
neither is a config tweak. No test has been made to pass by faking it.

See also: `memory/e113-boot-wall3.md`, `docs/om-emu/e113-touch-read-const.md`,
`docs/mods/related-projects.md` (Wall-B EVF analysis).
