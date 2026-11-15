# OM-Emu E113 — the touch-panel read length is a constant, not register `a1`

Scope: OM-Emu HLE lab, the touch-panel (I2C, slave `0x48`) read stand-in. This documents a
measurement trap in the auto-port's `touch_args` fact and the `{ const: N }` escape hatch added
to handle it. It is **off the current boot/menu-draw critical path** (touch is exercised only on
a touch input, not during boot or the first UI draw), and — see **Current status** — the fact is
**not applied in `ports.mjs` right now**. It is recorded here so the mechanism and the reason are
not lost across re-ports.

## Background — how the lab models a touch read

`src/lab/display.mjs` stands in for the touch driver's I2C transport (the real transfer's
completion IRQ is not modelled on the E118-family board). On a hooked touch read it needs two
things from the caller's registers/stack:

- **the destination buffer** — register `a0` on every body, and
- **the byte count to copy into it** — the `touch_args.read_len` fact.

`tpLength(lab, r, which)` reads that length. The fact is produced per-model by the auto-port
(`src/fw/facts.mjs` `touchArgs`), which *measures* where the firmware's touch-read helper takes
its length argument. Known good shapes:

- `{ sp: N }` — the length is at `sp+N` (E143's read; the E144 driver's read at `sp+40`).
- `{ reg: "a1" }` — the length is in register `a1`.
- `{ const: N }` — a **fixed** byte count, no argument. (The escape hatch; see below.)

## The trap

Some E118-family bodies implement the touch-register read with a helper that copies a **constant**
number of bytes into the caller's destination — the count is baked into the helper, not passed in.
`touchArgs()`'s structural measure cannot see a constant, and for that shape it mis-measures the
length as `{ reg: "a1" }`.

That is wrong, and dangerously so: on this helper `a1` holds the **internal I2C bus transfer
count**, not the destination length. The bus count is larger than the caller's small destination
buffer, so the stand-in copies too many bytes and **overruns the caller's dest** — corrupting
whatever sits after it on the stack/heap, with knock-on faults far from the touch read.

## The fix — `{ const: N }`

`tpLength()` honours a `{ const: N }` fact by returning `N` directly, before the `reg`/`sp`
branches:

```js
// src/lab/display.mjs, tpLength()
if (at.const != null) return at.const & 0xFFFF;
return at.reg ? r[at.reg] & 0xFFFF : u16(lab.sim.read(r.sp + at.sp, 2), 0);
```

For a body whose touch-read helper copies a fixed count, the model carries
`touch_args: { …, read_len: { const: <bytes> }, … }` by hand, overriding the auto-port's wrong
`{ reg: "a1" }`. This is a measured, model-scoped override, not a blanket change — bodies that
genuinely pass the length in `a1` or on the stack keep their measured shape.

## Current status (2026-11-15)

- The `{ const: N }` branch **is** in `src/lab/display.mjs` (fork working tree).
- The **E113 entry in `src/fw/ports.mjs` currently carries no `touch_args` / `touch_panel` block
  at all** — the latest re-port dropped it. So the `{ const: 4 }` override is **not applied**, and
  the `{ const: N }` branch is presently dormant for every model.
- This is acceptable for now because touch is **not** on the boot/first-UI-draw path, where E113
  currently stops (Wall 3 — the OSD depth-5 draw and the TFT wild-jump; see
  `../mods/related-projects.md`). The touch read-length only matters once the menu is driven by
  touch input.

**When touch comes back onto the exercised path**, re-add E113's touch block to `ports.mjs` and
**re-measure** `read_len` against the current re-ported address map rather than pasting the old
value — confirm the touch-read helper and its fixed byte count in E113's own image before trusting
`{ const: 4 }`. A re-port silently reverting a hand `touch_args` override has already happened once;
treat it as expected and re-verify, do not assume.
