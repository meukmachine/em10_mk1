# Flash storage layer + firmware write path (2026-11-02)

Static RE of `firmware/stock/dec/blk00_40300000.bin`, runtime base `0x7d991020` (code/data in block 0
are addressed at `fileoff + 0x7d991020`; the container's stated load `0x40300000` is the packaging
load, not the runtime exec base — xrefs only resolve against `0x7d991020`).

Goal: understand the real flash-commit primitive to reason about **safe flashing / brick risk**.

## Storage = managed NAND (Samsung FSR/XSR) + a SPI serial flash

Log-tag strings in block 0 identify the stack:
- **NAND FTL**: `BML_Erase`, `STL_ERASE`, `ifPhysicalErase`, `[FLASH]BBM Tbable FULL`,
  `[FLASH]Check BBM Table NG`, `[FLASH]Bad Block Hassei WRITE/ERASE`, `[FLASH]Read Disturb Hassei`,
  `[FLASH]read before writing`. BML = Block Management Layer (physical erase/program + **bad-block
  management**), STL = Sector Translation Layer above it. This is Samsung's FSR/XSR flash stack.
- **SPI serial flash**: `ACC_SPI`, `PER_ACC_SPI`, `PER_ACC_SPI2`, `FLASH_MULTI` — a second, smaller
  serial flash (likely boot / calibration / settings; not the main-OS NAND).
- **Guard**: `FLASH_ACCESS_GARD` / `[FLSH]Flash Access Guard Error!!!` — an access gate around the
  flash region; `flash don't call me!` (×9 sites) is a guard-rail asserting calls that must not reach
  the raw layer in the wrong state.

## Who writes: SYSFMEM task + the fmem/BML leaf
- `SYSFMEM` (RTOS task id `0x41`, entry `0x7db87ef8`, prio 4; see `docs/firmware/rtos-tasks.md`) is the
  flash-memory **manager task**. The path is **queue-based** (`[flashmem]acre_queue_error`): a client
  (e.g. `FIRMUP_SRV`) posts a write/erase request; SYSFMEM serialises it to the physical layer.
- **Physical flash leaf = runtime `0x7d9ca000`–`0x7d9cd900`** (the fmem/BML read/erase/program block).
  Pinned by xref of the tag strings (base `0x7d991020`):

  | tag | xref (caller / use site) |
  |---|---|
  | `ifPhysicalErase` | `0x7d9cc92c` |
  | `erase_error_status` | `0x7d9caebd` |
  | `Write Flash Error` | `0x7d9cc8e3`, `0x7d9cd288`, `0x7d9cd870` |
  | `[fmem]can't exchange sdram->flashrom` | `0x7d9cb1f3` |
  | `flash don't call me!` (guard) | `0x7d9cae17`…`0x7d9cb024` (9×) |
  | `FLASH_ACCESS_GARD` | `0x7d9c2df6` |
  | `[FLSH]Flash Access Guard Error` | `0x7da67715` |

## Firmup apply/flash dispatcher `0x7d9cc860+`
The firmware-apply routine (reached from the SD `ld_img_st` / `0x7dc9ccc0` path and expected to be the
USB-vtable path's convergence too) iterates the **9-slot component descriptor** (`docs/firmware/
camera-fw-update.md`): per slot it loads the slot's `(12,a0)`/`(16,a0)`/`(20,a0)` fields and calls a
per-slot helper `0x7da82530` (tag arg `0x7d9f8f35`), then commits via fmem calls `0x7d9929ce`
(40-arg) and `0x7d9928ee`. Scratch RAM is `0x6000xxxx` (`mov (0x60001130),d0`), matching the firmup
RAM pointers `0x6044cbc4`+.

**Safety-relevant structure (confirmed in disasm):**
- **Every flash call is return-checked.** Each `call` is followed by `cmp 0,d0; blt/bne` → a common
  error handler (`jmp 0x7d9cdaaa`) that logs (`Write Flash Error` / `erase_error_status`) and bails.
  Write failures are detected and surfaced, not ignored.
- **Managed FTL, not raw writes.** Bad-block management (`BBM`), read-disturb handling, and
  erase/verify error reporting mean a worn/bad block is remapped rather than silently corrupting the
  image — the physical layer is defensive.
- **Access guard** (`FLASH_ACCESS_GARD`) gates the raw layer, so stray callers can't write flash out
  of the managed sequence.

## What this means for safe flashing
- The write path is **checked and managed** (FTL + bad-block + per-call error gates), so a flash that
  completes reports success/failure honestly; a mid-write *detected* error aborts rather than writing
  garbage onward.
- It does **not** remove the two real brick vectors: (1) **power loss / link loss mid-erase-or-program**
  (the FTL can't finish an interrupted block), and (2) a **corrupt-but-checksum-valid image** passing
  the container gate (only an additive checksum, no signature — `docs/firmware/container-format.md`).
- Recovery is still the SD `FIRMUP_SRV` task (`docs/firmware/rtos-tasks.md`), intact unless the payload
  kills the kernel/scheduler/flash stack or corrupts cold-boot. The bootloader/1st-stage is **not in
  the update file**, so a body update cannot overwrite it — reinforcing that a bad body flash is usually
  SD-recoverable. (2026-11-15 correction: this is the flash region **below** the code block's flash base
  `0x40300020` — the reset head copies 256 KiB from flash `0x40060000` → `0x7ff8a000` that no shipped
  `.BIN` block holds — plus the NAND boot region. The `0x47001xxx` library is **not** part of it; that
  one IS in the update file, boot-copied from code-block offset `0x10497e0`. See
  `docs/firmware/memory-map.md`.)
  (2026-11-14: the SD route itself is proven live with a stock image and intact block 0; recovery
  after a block-0 patch that breaks startup is still unproven, see `camera-fw-update.md`.)

## Dead ends / limits
- `0x7da82530` (per-slot helper) and `0x7d9929ce`/`0x7d9928ee` (fmem commit) not fully decoded — the
  exact NAND-controller MMIO register sequence (command/address/data regs) is one more hop down; not
  yet pinned to a register block.
- Whether the firmup apply **verifies by read-back** after programming the whole image (vs only
  per-call status) is not yet confirmed — `read before writing` suggests pre-write checks exist, but a
  full post-write image verify was not located.
- SPI serial-flash contents (boot/cal) not mapped; no raw flash dump available (non-invasive only).

## Dump strategy — key open question (2026-11-15)
The reset head reads the boot region with a **plain memcpy** whose source is a flash address in the
`0x40000000`–`0x48000000` window (the 'outside' copy: `memcpy(a1=RAM, a0=flash 0x40060000, d0=0x40000)`;
the code block itself loads from `0x40300000` the same way). So that flash window is **CPU-addressable
(memory-mapped) at boot** — a dumper would not necessarily need the NAND/BML MMIO sequence; it could
`memcpy` straight from `0x40060000`.
- **Unknown:** whether the `0x40xxxxxx` flash window stays mapped/readable **at runtime**, or is only a
  cold-boot shadow that the SoC remaps once the OS is up. This decides the whole dumper design.
- **OM-Emu cannot answer it** — it does not model the flash window as a live runtime region (it resolves
  the reset head's flash reads at fact-build time only).
- **Two ways to resolve:** (a) static — look in block 0 for any *runtime* (non-reset-head) code that
  reads an address in `0x40000000`–`0x48000000` as a data source; if the running OS ever does, the
  window is live. (b) runtime — a one-off read of a few bytes at `0x40060000` on the camera (user-only,
  camera-state change) would confirm it directly and non-destructively.
