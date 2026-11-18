# OM-Emu E113 — menu-data port: findings and scope (2026-11-17)

After the boot walls A–H were ground native to the kernel idle (see
`e113-boot-walls.md`), the session pivoted — on the user's "switch to task modelling"
decision — to making the **menu usable**, measured by `checks/check_menu.mjs`. This
documents what that path actually hits, so the next session does not re-walk it.

## The menu test fails *before boot*, in the offline menu-data parser

`check_menu.mjs` step 0 is **offline**: it builds `new M.Image(loadImage())` and calls
`src/lab/menudata.mjs` `M.check(img)` / `groups(img)` / `tab_entry(img, SETUP_TAB)` —
pure reads of static menu-structure tables in the firmware image, no emulation. For
E113 it throws immediately:

```
ValueError: address 0xfe52d6b8 outside the image
  at Image.off (src/lab/menudata.mjs:32)
  at groups (src/lab/menudata.mjs:77)   // img.u16(GROUP_LIST)
```

`0xfe52d6b8` is `UNMAPPED(0x6152D6B8)` (`src/fw/model.mjs` `UNMAPPED` = `0xFE000000 |
low24`). So `A(0x6152D6B8)` returned the sentinel: **`PORTS.E113[0x6152D6B8] === null`**
— the address is in the port map but marked *unresolved*.

So the boot walls were never the thing standing between E113 and a usable menu. The
menu is **static data** that `menudata.mjs` reads, and that data is not ported.

## The 7 unresolved menu-structure tables

`menudata.mjs` hardcodes ~25 E143 run-time table addresses through `A()`. Seven are
`null` (UNMAPPED) in `PORTS.E113` — these are what block the parse:

| name          | E143 addr   | role                                   |
|---------------|-------------|----------------------------------------|
| `GROUP_LIST`  | 0x6152D6B8  | page-group master table                |
| `CUSTOM_VALUE`| 0x6152D6C0  | custom value-handler table             |
| `TAB_IDS`     | 0x61527174  | tab id list                            |
| `TAB_TABLE`   | 0x6152722C  | tab → page table                       |
| `NULL_DESC`   | 0x615273E4  | the "no literal" descriptor sentinel   |
| `ROW_ORDER`   | 0x614DA31C  | row-order table                        |
| `TAB_THEME`   | 0x613A0CA8  | per-tab theme table                    |

(`PORTS.E113`: 2208 entries, 1125 null. Only 20 of the nulls are in the DRAM data
region ≥0x60300000; the 7 above are the menu ones.)

## Why `bin/port.mjs` left them null — E113's menu subsystem differs structurally

The port tool resolves E143→E113 addresses by matching code/data between the two
builds. It could not place the menu tables because **the surrounding menu code did not
match**. Evidence:

- `PORTS.E113` has essentially **no mappings for the E143 menu-function region**
  `[0x511bc000, 0x511c0000]`: one key (`0x511bf0a6`, itself null).
- Region coverage is very uneven (resolved/total per ~1 MB of E143 code): 0x50000000
  58 %, but 0x50500000 21 %, 0x50600000 27 %, 0x50700000 12 %, 0x50800000 26 %. The
  GUI/app/menu-heavy regions are the least ported. E113 (E-M10 **Mark I**, 2014, body
  fw 1.4) is an older, structurally different firmware than the E143 golden.

## Two blocks, and the data layout differs

- E113 firmware is two blocks: `blk00000000` (18.9 MB, RT 0x7d991020) and
  `blk01200000` (25.2 MB, RT 0x7eb91020). `loadImage()` returns only blk00. E143's
  menu data sits ~22 MB into its *single* 33.5 MB blk00; E113's blk00 is smaller, so
  some data spilled into block 2. (The menu tables that *are* reachable, like the
  GROUP_LIST candidate below, are in blk00.)
- E143 GROUP_LIST is `{u16 key, u16 count, u32 ptr}` records; `[0]` is the key=0
  master with count=42 and page_ids in a consecutive run 0xCB..0xF4, each 16-byte
  entry `{page_id, type=4, lit=NULL_DESC, rec=base+28·g}`. **E113 has none of this**: no
  0xCB..0xF4 page-id run and no 28-stride record run in either block. The Mark I menu
  uses different page ids and a different record layout.

## Methods tried to locate E113's tables, and why each failed

1. **Masked-immediate byte signature** (mask the 4 address bytes in the E143
   instruction window, find the match in E113, read E113's immediate): false positives
   — relative branches and other refs in the window differ between builds. Gave
   `GROUP_LIST→0x7e5b13c8`, `CUSTOM_VALUE→0x7e5b5d90`, which are 18 KB apart though the
   E143 originals are 8 bytes apart.
2. **Structural scan** (find the 42-entry / 28-stride array): nothing in either block —
   E113's layout differs (see above).
3. **Code signature** (`asl 3,d0; mov d0,a0; add imm,a0; movhu (a0),d0; cmp d0,d3` —
   the GROUP_LIST search loop): one hit, `0x5095c6f9 → 0x7e5b13c8`, *agreeing with
   method 1*. But parsing 0x7e5b13c8 gives records whose entries have `type=0xDA07`
   (not a 0–12 descriptor) — it is a **different** 8-byte-keyed search table; that
   instruction pattern is common. False positive confirmed by PORTS: the E143 menu
   function region does not map near 0x5095c6f9.

Lesson: short byte/code signatures are not selective enough across these two builds,
and the E143-format structural scan is invalid for E113. Locating E113's menu tables
needs real disassembly of E113's own menu functions (bottom-up from a reliable anchor),
not signature matching against E143.

## Scope conclusion

"Menu fully usable" for E113 is a **standalone RE sub-project**, not an address port:
E113's menu subsystem (code *and* data tables) differs from the E143 golden and was not
carried by `bin/port.mjs`. Reaching it means reverse-engineering E113's own menu-data
format and tables — likely finding E113's menu functions from a dependable anchor (the
SETUP-tab dispatch, or a known menu string / resource-id path), then teaching
`menudata.mjs` E113's layout (model-parametric, or an E113 decoder). The boot-wall
work remains valid and needed, but it is not what gates the menu.

## Breakthrough (2026-11-17, cont.) — tables located; earlier "differs structurally" was an overstatement

Switched from signature matching to the reliable anchor: **the code that reads the
tables**. Disassembled all of E113 (`scratchpad/e113_full.asm`, 11.4M lines) and
collected every code immediate pointing into the menu-data cluster, then cross-checked
each against the exact E143 table signatures. Results:

- **E113's menu code region is `0x5095xxxx`** (the counterpart of E143's unported
  `0x511bxxxx`), reading a data cluster spanning **`0x7e5b0000`–`0x7e5d0000`**. Menu
  entry points (from the model config): `menu.task 0x5089a153`, `menu.take 0x5089a36a`,
  `menu.caller` object at `0x60454240`.
- **The descriptor format MATCHES E143**: 8-byte `{u16 type, u16 id, u32 lit}`. I had
  mis-read it as 16-byte earlier; `0xe30004` is `{type=4, id=0xe3}`, `lit=NULL_DESC`.
  So this is an **address + entry-layout** problem, not a parser rewrite. The earlier
  "menu subsystem differs structurally (code AND data)" is corrected: the *code* is at a
  different address and the *group-entry* layout differs, but the descriptor/table
  grammar is E143's.

Findings, graded after verification:

| item          | E113 addr    | status      | evidence                                       |
|---------------|--------------|-------------|------------------------------------------------|
| descriptor fmt| —            | **SOLID**   | 8-byte `{u16 type,u16 id,u32 lit}`, = E143      |
| `NULL_DESC`   | 0x7e5c6308   | **SOLID**   | repeating `lit` sentinel `00 00 00 00 03 00 00 00` |
| descriptor pool| 0x7e5cafa8  | **SOLID**   | 241 contiguous type-4 descriptors, lit=NULL_DESC|
| `TAB_TABLE`   | 0x7e5cda48   | likely      | parses as 8 tabs, ids 0–7, sane page counts    |
| `GROUP_LIST`  | 0x7e5c533c   | **DUBIOUS** | see below — probably a false positive          |

**Correction to the commit `095da7e` message**: it listed GROUP_LIST/CUSTOM_VALUE as
"pinned". Verification knocked that down:

1. **The "code immediate" filter was invalid.** `objdump -D` disassembles the *data*
   region too, so table addresses appeared as immediates inside garbage instructions
   decoded from data bytes. Checking for a **real** code load (a `0x50xxxxxx`
   instruction line) of each address finds **none** — not even for TAB_TABLE. So E113's
   menu code reaches its tables by **base-register + displacement** (load a section base
   once, access `base+off`) or via pointers held in the menu object at `0x60454240`,
   **not** absolute immediates. Immediate-scanning therefore cannot locate the readers.
2. **GROUP_LIST 0x7e5c533c doesn't parse.** Its `{key=0, count=20, ptr=0x7e5c50f4}` has
   `count=20`, but `0x7e5c50f4` is an array of 8-byte `{u32 page_id, u32 ptr}` pairs with
   **~48+** entries, nearly all pointing at the same self-referential `0x7e5c50e4`
   (which overlaps the array head). That is neither E143's 16-byte `{page_id,desc,rec}`
   nor a coherent 20-entry list. Treat 0x7e5c533c as **not** the group list until the
   reader proves otherwise.

So the genuinely solid results are: the descriptor grammar matches E143, NULL_DESC and a
real descriptor pool are located, and TAB_TABLE is a strong structural candidate. The
**group/page organization differs from E143 and is not recoverable by structural
pattern-matching** — it needs reader-function disassembly.

Still open (next session) — now with the right method:
- **Decode the readers by base-register tracing.** Disassemble from `menu.task
  0x5089a153` / `menu.take 0x5089a36a` and the `0x5095xxxx` menu region, follow the
  section-base loads and the `0x60454240` object fields to the actual group/tab/row
  tables. Absolute-immediate and structural scans are both exhausted.
- `ROW_ORDER`, `TAB_THEME`, `TAB_IDS` remain unpinned (TAB_IDS is `[0..7]`, trivially
  derivable). Earlier ROW_ORDER candidates (0x7e5b13c8/0x7e5b3788/0x7e5b4a60) did **not**
  match `page_id+4`, consistent with the group id-space being different/unknown.
- Then resolve in `PORTS.E113` and make `menudata.mjs` model-parametric.

## Anchor correction: "menu.task 0x5089a153" is GUI-App WinMain, not a menu reader

Dumping exactly what `0x5089a153` references (`scratchpad/mtask.mjs`, all addresses
script-computed to avoid hand-hex slips) shows its immediates are **GUI task startup**,
not menu tables:

| addr        | content                                                              |
|-------------|----------------------------------------------------------------------|
| 0x7e45dfbc  | `"[GUI App]:Start Gui Task"` (log string)                            |
| 0x7e45dfd8  | `"[GUI App]:WinMain called"` (log string)                           |
| 0x7e45dff8  | task-config record `{0x10101, 0x60454244, 0x30007, …}`               |
| 0x7e45e044  | task record `{1, 0x60786df4, 0x8000, …}` (DRAM ptrs)                 |
| 0x7e58cb18  | display geometry `{0, 0x2cf, 0x1df0000, 0x1df02cf, …}` = 719×479     |

So the model-config anchor named **`menu.task` is really GUI-App `WinMain`** — the
top-level GUI task that *spawns* the menu among everything else, not the settings-menu
table reader. (`menu.take 0x5089a36a` is almost certainly the same task's take/dispatch.)
The actual menu-data reader is **deeper in the call graph below WinMain**, reached
through the `0x60454240` GUI-app object. The two usable anchors we have are both too
high-level.

**Methodology, now settled:** absolute-immediate scanning and E143-format structural
scanning are both exhausted (above), and the named anchors are GUI-App-level, not menu
tables. The only remaining path is **call-graph tracing from WinMain down to the menu
component, tracking the base register(s) that address the menu data** — which hand-parsed
`objdump`+`awk` makes error-prone (two hex transcription slips this session: 16-byte vs
8-byte entries, and `0x7e5b`/`0x7e45` cluster mix-up). This is exactly the job for a
decompiler (the `ghidra-reverse-engineering` skill): reliable xrefs, call graph, and
register dataflow instead of eyeballing disassembly.

## Negative result: the 0x7e5cxxxx cluster is NOT confirmed to be menu data

A script-driven xref (`scratchpad/xref.mjs`, all address math in-script) scanned the whole
disassembly for any **real code line** (text area, runtime < 0x7e000000) loading an
absolute pointer into the `[0x7e5c0000, 0x7e5d0000)` cluster. Result: **0 hits.** Nothing
in the code directly loads a pointer into that cluster.

Consequences, stated honestly:
- The cluster was originally reached by collecting "code immediates into it" — but those
  were all **data-disassembled-as-garbage** (objdump `-D` decodes the data region as
  instructions). So the trail that led to 0x7e5cxxxx was a dead end.
- Therefore the earlier **"SOLID"** grades on NULL_DESC / descriptor-pool / TAB_TABLE are
  **overstated**. Those byte patterns *are* real descriptor-table-shaped data, but there
  is **no evidence they are the menu's** — the firmware has many descriptor tables
  (rec-param, devparam, imaging recipes), and any of them can show a NULL-literal sentinel
  and runs of type-4 descriptors. Downgrade to: *descriptor-shaped data of unknown owner.*

**All three static location methods are now exhausted** for E113's menu tables:
absolute-immediate scan (nothing), E143-format structural scan (nothing), and code-xref
into the candidate cluster (nothing). The menu tables are reached neither by absolute
immediate nor — as far as any direct pointer load shows — from the text area at all,
consistent with a data-section base held in a global/object and populated at init.

### Realistic remaining approaches (all are larger sub-efforts)
1. **Dynamic**: let the emulator execute the menu-init path so it populates the GUI-app
   object's menu pointers, then read them from emulated RAM. Blocked today — the E113
   model grounds at kernel idle (Wall H) before GUI init runs; would need the menu-init
   task driven far enough.
2. **Decompiler call-graph trace**: Ghidra-style xref/dataflow from GUI WinMain down to
   the menu reader. Ghidra ships **no** MN10300/AM33 processor by default → needs a custom
   SLEIGH spec (itself a sub-project), or another MN10300-aware decompiler.
3. **Different static anchor**: find a *menu-specific* localized UI string (a settings
   label unique to the menu) and trace what code consumes it — a string-driven anchor
   instead of the GUI-task anchor, which turned out too high-level.

## Dynamic path (chosen 2026-11-17): first obstacle pinpointed — E113 SCPU driver unported

Decision: drive the emulator through menu-init so the firmware GUI task reads its *own*
menu tables, then hook those reads to discover the table addresses (instead of locating
them statically, which is exhausted). Progress this session:

- **E113 reaches kernel idle with 145 firmware tasks created** (not the "9" lab stand-in
  count): at idle, 136 DMT (created-but-never-started), 8 WAI (kernel/system tasks,
  entries `0x5002xxxx`), 1 RDY (lab stand-in `0x8A2E0000`). The **GUI task is dormant** —
  `GUI_CREATOR` hasn't run, so no task has entry `GUI_TASK`.
- **The GUI-task ports are mostly correct**: `GUI_TASK` E113 = `0x5089a153` (= WinMain,
  confirming the anchor), `GUI_CREATOR` = `0x50892cd4`, `GENIFA_TIMER_TASK` = `0x50893f78`,
  `EYEFI_TASK` = `0x5063483b`. **`EMPTY_TASK` is NULL.**
- **`lab.bring_up('tft')` grounds at kernel idle `0x7d9a730b`** (the Wall-H ground). It
  calls `GUI_CREATOR` directly (`menu.mjs:510`), and that call spins to idle. The console
  shows the cause: the firmware issues **SCPU command `0x20041001`** (sub-CPU / coprocessor
  request) and waits for a reply, plus an **OSD-engine guard** trip, neither completed by
  the E113 device model.
### CORRECTION 2026-11-17 — "SCPU port gap" root-cause DISPROVEN

The prior "root cause = SCPU port gap" was wrong. Verified by disassembly + live probe:

1. **SCPU command `0x20041001` is NOT the blocker.** At disasm `0x5004a49a` the firmware
   writes the command word to port `0x98e00064` then polls `mov (a1),d0; cmp 0,d0; lne`
   (spin while non-zero). The lab's SCPU device (`src/dev/scpu.mjs`) returns **0** on a
   COMMAND read (`if (off === COMMAND) return 0`, label A-SCPU-1), so the poll reads 0 and
   **exits immediately**. The bring-up log shows the command fired **4× at four different
   PCs** (`0x7d9db4ba`, `0x7d996653`, `0x4700164d` ×2) — proof the firmware advanced past
   each poll. The E143 `SCPU_REQUEST`/reply-handler/reply-reader ports are the lab's
   *backup-words* path, gated by `SCPU_BACKUP = fact('scpu.backup', hasString(img,
   'ScpuBkupW'))`; E113 lacks that string, so that whole block (`menu.mjs:724`) is
   correctly **skipped** (no `refuseUnmapped` throw — which is why those nulls never fire).
   The SCPU-init MMIO is already backed by the E113 board fact
   `scpu: { storage: [[0x00,0x94],[0xa0,0xa0]] }`.

2. **The OSD-engine GUARD error is NOT the blocker either.** `cmdFill` rejects a fill whose
   `dst` isn't inside a registered guard window (`engine.mjs:110`, `fail(E.GUARD, dst,
   stride)`), but on the START register (`0x24`) the engine **still** completes the list and
   sets `regs[1] |= 1` (STATUS done) → `updateLine()` fires `IRQ_OSD_ENGINE`
   (`engine.mjs:342-351`). A GUARD-failed command is a *recorded* error (pixels skipped),
   not a stall. The firmware sees "done" and proceeds.

3. **The true wall = scheduler idle / event-wait.** Bring-up grounds at the firmware's own
   idle `0x7d9a730b` (fault 7, nothing READY). `GUI_CREATOR` is called synchronously by the
   lab (`menu.mjs:510`) and **blocks inside** — it has not returned and has **not created
   the GUI task** (`0x5089a153` is absent from the task list). At idle, 8 tasks are `WAI`,
   all low kernel/SCPU/timer bodies (entries `0x5004a30c` tid1, `0x5005c96a` tid9,
   `0x5005c755` tid10, `0x5006b0db` tid44, `0x5006c358` tid45, `0x50075341` tid46,
   `0x5004b5d8` tid138, `0x5005156f` tid139), parked on event-flag wait queues (doubly
   linked: tid9↔tid10 on `0x60694b8c`; tid45↔tid46 on `0x60695a8c`; TCB `+120/+124` =
   queue links, `+80` = priority). `GUI_CREATOR` is parked on one of these waits; it needs
   an **interrupt the lab does not present** (vsync / frame / SCPU event line) to set the
   event flag it waits on.

### Concrete next target (bounded)
Find exactly which event flag `GUI_CREATOR` waits on, then present the matching interrupt.
Hook E113's event-flag-wait syscall (E143 `OlyEvWt 0x5002bdd8` → E113 counterpart) for the
duration of the `GUI_CREATOR` call and capture the flag id + wait pattern at the point it
blocks. That names the interrupt source the lab must raise. This is the "needs scheduler/
task modelling" cost the dynamic path was flagged with — an interrupt/event-injection
effort, not a static-address port. Once `GUI_CREATOR` returns and creates the GUI task, the
bring-up advances and the GUI task's menu-table reads become hookable.

Probe: `scratchpad/wai_probe.mjs` (dumps WAI TCBs at idle), `scratchpad/bringup.mjs`,
`scratchpad/idle_probe.mjs`.

### REFINEMENT 2026-11-17b — wall is `settle('GUI bring-up')`, not GUI_CREATOR itself

Wrapping the MenuLab `call`/`native`/`settle`/`step` helpers (`scratchpad/steptrace.mjs`)
shows bring-up gets *much* further than the earlier note implied:

- Succeeds: H-SWTIMERPOOL, software-timer init `0x50058959`, display-task init `0x501c2f13`,
  **DISPLAY cmd 2 OPEN tft**, registry bootstrap `0x50040aeb`, **property store init**
  (E113 `0x50202eaf`), **settings-DB init** `0x503c5473`, then several `call()`s.
- Grounds: inside `settle('GUI bring-up')` — a `call()` runs the firmware into its own idle
  task `0x7d9a730b` (fault 7), which the HLE flags because it expected the GUI work to
  return or hit a known wait.

So store init is **not** the blocker (it completes). `GUI_CREATOR` is reached and the GUI
tasks are created; they run, then all block. `settle` (`kernel.mjs:331`) normally finishes
by signalling its own semaphore even when tasks block — but here the firmware reaches its
idle *inside* a `call()` the settle issued, i.e. the GUI tasks are waiting on **interrupts
the lab does not present** (vsync / SCPU-event / frame-timer). Note: hooking the GUI_CREATOR
*address* `0x50892cd4` directly perturbs the idle loop (it then spins 200000× from a constant
garbage caller) — an instrumentation artifact; use the helper-wrap trace, not an address hook,
to locate the step.

**Next target (superseded below by the 2026-11-17c finding — the block is a flash-controller
poll, not a vsync/IRQ gap).** Vsync flag-id words were checked and DO map in E113 (non-null:
0x6004305E→0x7d9b0572, 0x600430DE→0x7dc365dc, 0x60043178→0x7dc36676), so vsync delivery is
not the blocker.

### FINDING 2026-11-17c — the GUI task IS created; it aborts on an unmodeled flash-controller poll

Decisive sequence (probes `scratchpad/{creatorout,idletasks2,prints,flashwrite}.mjs`):

1. `GUI_CREATOR` (0x50892cd4) **does create the GUI task.** Captured right after its `call()`
   returns (stubbing `settle` to a sentinel): `creator_out = [0x1, 0x0]`, and the task list
   now has **tid146 entry 0x5089a153 (WinMain) state WAI** — total 146 tasks. (Corrects the
   earlier "GUI_CREATOR blocks / never creates the task" claim — it returns and the task exists.)
2. After `settle('GUI bring-up')` grounds at idle, the task list is back to **145 tasks with
   NO task in the GUI region 0x5088–0x508a** (not even DMT). So during settle the GUI task
   **ran and self-deleted** (146→145) — WinMain hit an abort path in its startup, it did not
   enter its event loop.
3. The firmware's own debug prints over bring-up (kernel `events` print log) end with a
   repeated **`[FLASH]intr status0 BIT` / `[FLASH]read before writing`** loop (~35× of the
   last 40 prints), then a `K.R. / 10/23 16:31 / !!` stamp (`!!` = abort marker). The `[FLASH]`
   driver is at disasm **0x5000174b** (intr status) and **0x50003d39** (read before writing) —
   E113's own low-level serial-flash / NAND driver, which programs the **flash controller at
   0x98d0xxxx** (board.mjs:388-390: register page 0x98d00000; the lab does **not** model it).
4. The lab's flash stand-ins are keyed to **E143** addresses that are **UNMAPPED in
   PORTS.E113**: hooking `NAND_WRITE` (0x5003D9DA) and `NAND_POST` (0x5003D95C) both
   **HOOKFAIL**. `FLASH_SAVE` (0x503EEE94) maps and its S-FLASHSAVE hook works for the
   store-init saves (which succeed), but it is **never hit during GUI bring-up** — WinMain's
   bring-up flash access takes E113's own driver path straight into the unmodeled controller.

**Mechanism:** WinMain's startup does a flash read/write; the flash controller's interrupt /
status bit never signals "done" (unmodeled MMIO stub returns 0), so the driver retries, prints
`!!`, and WinMain aborts and deletes its own task → nothing GUI remains → scheduler idle.

**Concrete next step (bounded device-model work):** model the flash controller (page
0x98d00000) so its status/interrupt bit reads "done" and its ops complete — like `src/dev/scpu.mjs`
is a stub for the SCPU. Identify, from the E113 flash driver at disasm 0x5000174b / 0x50003d39
/ 0x500093ec, which register offset is the status/interrupt-status word and which bit value the
poll waits for. Add a small flash-controller device (or extend the bus stub for 0x98d0xxxx) that
returns that bit set after a write/read is issued. Alternative: map E113's flash-driver entry
addresses in PORTS.E113 and hook a return-success stand-in there. Then re-run bring-up: WinMain
should stay resident (WAI) and `settle('GUI bring-up')` complete, exposing the GUI task's menu
reads for the original table-discovery goal.

**Instrumentation caveats (do not re-chase):** (a) hooking a *hot* address (GUI_CREATOR
0x50892cd4, or the flag syscalls set_flg/wai_flg) perturbs the HLE idle short-circuit — the run
then spins to a "200000/300000 stub calls" fault from a constant garbage caller (0x5c76efe7)
instead of grounding cleanly; the `set_flg id=0x8a` spin seen that way is an **artifact** of the
hook breaking set_flg's wake semantics, not real firmware behavior. Use helper-wrapping
(`steptrace.mjs`) or the single terminal-idle hook (`idletasks2.mjs`), not hot-address hooks.
(b) `twai_flg` E113 = A(0x50017E85) still looks mismapped (0x5104a625, suspiciously high).

Probes this session: `scratchpad/{idle_probe,labprobe,bringup,portchk,scpuchk,xref,mtask}.mjs`.

## FINDING 2026-11-17d — flash done-poll wall CLEARED; next wall = WinMain event-wait (WAI)

**Flash controller status register + mask identified and fixed.** The E113 flash driver's
done-poll helper is at disasm **0x50001726** (status register pointer `a2` = **0x98d01104**,
loaded by `mov -1731194620,a2` at 0x50001729). It loops up to 65535 times waiting for
`(0x98d01104 & mask) == mask`; on timeout it prints `[FLASH]intr status0 BIT` (string ptr
2107611520 = 0x7d99a180) and aborts. The GUI bring-up calls it from **0x50003d18** (the
"read before writing" path 0x50003d39) with **mask d0 = 0x9004** (bits 15, 12, 2) — captured
live by `scratchpad/pollmask.mjs`. The fs-mount (0x5000121b) uses the *same* register but polls
only bit 13 (0x2000), for which `board.mjs` already preset `[0x98D01104, 0x2000]`.

**Fix (one value):** `src/fw/board.mjs` E118-family `stub_presets` entry widened
`[0x98D01104, 0x2000]` → `[0x98D01104, 0xB004]` (= 0x2000 | 0x9004). The lab packs presets with
flags=1 (`display.mjs:575`, enable-only: a read *always* returns the value regardless of the
mount's write-back), so 0xB004 satisfies BOTH the bit-13 mount poll and the 0x9004 flash
done-poll, setting only the bits the two known polls sample — no extra/error bits. Inert for
E120/E122 (stub their whole flash path) and E118 (mount reads bit 13 only). **Result:**
`[FLASH]intr status0 BIT` no longer prints in clean bring-up → the 0x9004 done-poll passes. This
flash wall is cleared. (E143 golden unaffected — change is in the E118 board entry; E143/E144 use
a different entry.)

**CORRECTION (same day): WinMain is NOT kept alive by the flash fix — it still self-deletes.**
A brief WAI reading (state 0x04 at TCB 0x6068b580) was **stale freed-TCB memory**: that TCB's
id word (offset 16) reads 0, and a scan of the live tid→TCB table at idle
(`scratchpad/winfate.mjs`) finds **0 live slots with entry 0x5089a153** and no tid mapping TCB
0x6068b580. WinMain is deleted; the "new wall = WAI event-wait" claim above was wrong — do not
rely on it. The flash preset fix is a **necessary but not sufficient** sub-fix.

**Real post-fix state (working print capture `lab.kernel.events` filtered to 'print',
`scratchpad/prints.mjs`).** After the fix, clean `bring_up('tft')` still grounds at idle
0x7d9a730b (fault=7), 145 tasks, WinMain gone, and the firmware print tail is:
- `[FLASH]intr status0 BIT` — **GONE** (the 0x9004 done-poll now passes: the fix worked for that).
- `[FLASH]read before writing` (disasm 0x50003d39) — **still prints ~34×**: the flash *data*
  read in that function still returns unexpected bytes (the lab backs the controller status
  register 0x98d01104 but not the flash **data**, so reads come back garbage/0xff).
- `[DebugBlock]:WriteQueue ERROR ID = 0x0000` (disasm 0x50005ebf) — a DebugBlock subsystem
  write-queue failure; **ID = 0x0000 ⇒ the block was never registered/opened** (an init the lab
  skips), the likely abort trigger.
- `K.R.` / `10/23 16:31` / `!!` — a boot/crash record being written to flash, then `!!` (abort).

**Refined diagnosis:** WinMain's bring-up writes a record to internal flash through a
"DebugBlock" write-queue (the `[FLASH]read before writing` path 0x50003d18→poll 0x50001726, and
the DebugBlock WriteQueue at 0x50005ebf). The lab models the flash **controller status** but not
the flash **data** nor the DebugBlock registration, so the read returns garbage and the write
queue errors (ID 0), WinMain hits `!!` and del_tsk's itself. This is a flash-backed
debug/event-log subsystem, not the menu proper — but WinMain aborts on it during bring-up.

**Concrete next step:** (a) find what `[FLASH]read before writing` (0x50003d39's function) reads
— which address/window the flash DATA lives in — and back it with coherent RAM so reads/writes
succeed; and/or (b) find where DebugBlock registers its block id (why it is 0x0000 at
WriteQueue time — an init the lab's bring-up skips) and either run or stub it so the write queue
accepts. Goal: WinMain survives bring-up instead of aborting on the flash debug-log write.

**Instrumentation caveat (reconfirmed):** hooking the task-lifecycle syscalls (ext_tsk 0x500107E8,
ter_tsk 0x50015E87) perturbs the HLE idle short-circuit — it *replays* the same `ext_tsk` call
(constant curTCB 0x6068aef0 = tid129 entry 0x5003400f, caller 0x500309c6, d0=0x81) and spins to
the 200000-stub-call fault, even when removed after N fires; tid129's ext_tsk is NOT WinMain. Use
the working **`lab.kernel.events` 'print' capture** (`prints.mjs`) and the **live tid→TCB scan at
idle** (`winfate.mjs`) — both non-perturbing — for ground truth, NOT syscall hooks or env.stderr
(firmware prints route to the kernel event log, not stderr).

Probes added this session: `scratchpad/{pollmask,bringclean,prints2,fwprints,catchexit,firstexit,idledump}.mjs`.

## State

- Committed checkpoints: om-emu `c58aad0` (9 tasks + 4 HW stand-ins); docs `14580b7`,
  `f25c4e6`, + this.
- `check_menu` (E113) status: still fails at offline `menudata.groups()` (addresses not
  yet wired into PORTS.E113 / parser not yet model-parametric). No test faked. E143
  golden 95/95 unaffected.
- Scratch probes: `scratchpad/{scan_nulldesc,scan8,verify_group,climb,find_grouplist,
  classify,tree}.mjs`, `e113_full.asm` (full E113 disassembly).

---

## FINDING 2026-11-17e — the real post-flash wall: E113 per-CPU idle-dispatch block is uninitialized (scheduler/idle structure differs from E143)

Chasing the firmware GUI bring-up (the "drive the emulator" path), the flash/DebugBlock/`!!`
prints turned out to be **benign bring-up markers**, not the wall. Full event-stream capture
(`scratchpad/alltail.mjs`, all kernel event types) shows that *after* those prints the firmware
attaches **~52 device IRQs** (irq 24-27, 66-79, 114-119 = imaging/lens/sensor drivers) plus 42
clock ops — i.e. bring-up gets far into real device init. It then grounds, and the ground is a
genuine panic.

**Ground = a deliberate panic-hang.** `bring_up('tft')` grounds at runtime `0x7d9a730b`
(disasm `0x500162eb`), which is literally `bra 0x7d9a730b` — an infinite self-loop. Its single
entry is `call 0x7d9a730b` from `0x7d9a2bd5`, the "not ready" arm of the kernel's idle-start gate:

```
; fn 0x7d9a2b65 (disasm 0x50011b45), "start scheduler / go idle",
; called from 0x7d9a3a82 / 0x7d9a3b3a. Runs ~16 sub-inits, then:
0x7d9a2bba  mov (0x8050),d0            ; CPU index (0 for CPU0)
            asl2 d0
            mov 0x8ff00004,a0          ; per-CPU control-block table base
            mov (d0,a0),a0             ; a0 = this CPU's control block
0x7d9a2bc6  mov (360,a0),d0            ; field[360] = idle-ready flag
            cmp 1,d0
            bne 0x7d9a2bd5             ; != 1  -> call 0x7d9a730b  (panic: bra .)
            call 0x7d9a7310            ; == 1  -> enter idle dispatcher (never returns)
```

A sibling at `0x7d9a2db2` wires the dispatch pointer to the **panic address `0x7d9a730b`** as the
explicit "not ready" fallback (vs `0x7d9a730d` idle), confirming this is a readiness gate.

**Empirically confirmed (`scratchpad/gate360.mjs`, live hook_rt at the gate):** CPU0 control
block = `0x7d9b0930` (an *image* address, not RAM), and **field[360] = 0x0** (needs 1) → panic.
Neighbors: field[308] = `0x7d9edcc9`, field[260] = `0x6069a7f4`, field[104] = `0x60686fb0`,
field[108] = 0x200. So the block is only **partially** initialized (260/104 are real RAM ptrs;
360 and 308 are not).

**Forcing it proves the structure mismatch (`scratchpad/redir.mjs`).** Hooking the panic entry
`0x7d9a730b`, setting field[360]=1, and `finish_after`-ing into the normal idle path `0x7d9a7310`
(which does `mov (308,a0),a0; calls (a0)` — call the idle dispatcher) does **not** reach the lab's
hooked IDLE addresses (`kernel.mjs` `IDLE = [A(0x500159F3), A(0x500159F8)]`). It grounds again at
`0x7d9a7323` (another `bra .`). Reason: **field[308] = `0x7d9edcc9` points into a `retf [],0`
sled** (disasm `0x5005dca9`, a table of bare returns) — not a real idle dispatcher. Calling it
returns immediately and the firmware hangs at the post-call `bra .`.

### Conclusion (reframes the wall)

The wall is **not** flash, DebugBlock, or WinMain self-deletion (WinMain deleting is a downstream
symptom). It is that **E113's kernel scheduler/idle structure differs from E143's**, and the lab's
`kernel.mjs` idle model is E143-shaped:

- The per-CPU control block (CPU0 @ `0x7d9b0930`, via `*0x8ff00004`) is **not fully populated** in
  the E113 lab: the idle-ready flag (field[360]) is 0 and the idle-dispatcher pointer (field[308])
  points to a `retf` sled instead of real code.
- The lab hooks E143's idle loop (`IDLE = [A(0x500159F3), A(0x500159F8)]`); forcing E113 down the
  firmware's own idle path does not reach those addresses, so even past the gate there is no clean
  settle.

### Next step (next session)

Find the kernel init that **populates the per-CPU control block** — specifically what should set
field[360]=1 and field[308]=(real idle dispatcher) for CPU0 — and why the E113 lab skips or
mis-maps it. Candidates: one of the ~16 sub-inits inside `0x7d9a2b65`; the multi-CPU barrier /
IPI handshake stand-in (`XCPU_WAIT`, `kernel.mjs` xcpu_standin, E113 barrier `0x500125a3`); or a
data-section init/copy the lab does not perform. Then decide whether the lab must (a) run that
init, (b) populate the block directly as a stand-in, or (c) learn E113's true idle address and add
it to the `IDLE` hook set. This is an **E113 kernel-port** task, deeper than a single missing
setter — the earlier "flash done-poll" and "WinMain WAI" framings were both wrong.

### Verified artifacts this session

- Flash preset fix (`board.mjs`, om-emu 282f210) stands as a **real sub-fix**: it clears the
  `[FLASH]intr status0 BIT` done-poll (that print is gone). It is just not the bring-up wall.
- Probes: `scratchpad/{alltail,prints,winfate,gate360,force360,redir}.mjs`. E143 golden
  unaffected (the E118-family change can't touch E143; E143 is a different board entry).
- `check_menu` (E113) still fails offline at `menudata.groups()` — unchanged; the live-drive path
  to discover menu tables is blocked behind this kernel idle wall.

---

## FINDING 2026-11-17f — idle-block fix shape FOUND + verified; next wall = IRQ level ordering

Correcting 2026-11-17e's framing: the lab's IDLE hooks (`kernel.mjs:88`,
`IDLE = [A(0x500159F3), A(0x500159F8)]` → E113 runtime 0x7d9a730b / 0x7d9a7310) are
**temporary** — installed only around `hle.call(KENTRY)` (kernel init) and restored away
after (`saved = new Map(hle.hooks)` at :81). During kernel init they catch the firmware's
idle-dispatch entry and throw `Idle` (clean exit). On E113 field[360]=0 at that moment, so
kernel init actually reaches the **panic** arm 0x7d9a730b — and the temporary IDLE hook there
*masks the panic as idle*. Kernel init "succeeds" via that masking. Fragile but functional.

The wall is in the **later device bring-up** run (`MenuLab.bring_up`), where those temporary
hooks are gone. Bring-up re-reaches the idle gate (fn 0x7d9a2b65): field[360]=0 → panic
0x7d9a730b → ground (no hook now).

### The fix shape (verified to clear the wall)

E143's idle architecture (`kernel.mjs` :109 `intc.set_gate(GATE_PC, IDLE_DONE)`, :98 kcb idle
TCB, :245 `hook_rt(IDLE_DONE, _idle)`) routes the idle point to the harness trampoline
**IDLE_DONE = A(0xA2300080) = runtime 0x8a300080**. E113 does **not** reach IDLE_DONE that way:
it idles through a **per-CPU control-block indirection** E143 lacks — `0x7d9a7310` does
`mov (308,a0),a0; calls (a0)`, i.e. jumps through block field[308] (the idle-dispatcher ptr).

Presetting the CPU0 block (`*0x8ff00004` = 0x7d9b0930) **before** bring_up:
- field[360] (0x168, idle-ready) = 1  → gate takes the idle arm (call 0x7d9a7310), not panic
- field[308] (0x134, idle-dispatcher ptr) = IDLE_DONE (0x8a300080)  → the `calls (a0)` lands on
  the lab's IDLE_DONE trampoline → `_idle` hook fires

…gets the firmware **cleanly to the idle point** (verified: `scratchpad/preset2.mjs`). Without
field[308] (only field[360]=1) it grounds at 0x7d9a7323, the `bra .` after `calls (sled)`,
because the static image value of field[308] is 0x7d9edcc9 — a `retf` sled, never overwritten in
the lab. So **both** fields must be set.

This is the real E113 kernel-port step: a model-gated per-CPU-block init (field[360]=1,
field[308]=IDLE_DONE) that bridges E113's idle-dispatch indirection to the lab's IDLE_DONE home.
Candidate home for the fix: `kernel.mjs` after `hle.call(KENTRY)`, gated on E113 (E143 unaffected
— it has no such block / reaches IDLE_DONE via the INTC gate).

### Next wall (deeper, independent — IRQ model data)

With the block preset, bring_up now reaches the idle point and throws:

```
two interrupt levels presented together at the idle point: {3: 31, 5: 30}
(A-INTC-ORDER and the level priority would decide)
```

(`kernel.mjs:287`, `strict_levels=true`.) Two IRQs present simultaneously — level 3 IRQ 31 and
level 5 IRQ 30 — and the lab has no E113 **A-INTC-ORDER** (interrupt-controller priority order)
to decide which fires first. This is INTC model data, a separate E113 port gap from the idle
block. Next: find/port E113's INTC level-priority order (vs E143's) into the IRQ model.

### Artifacts

Probes `scratchpad/{clean,preset,preset2,hookmap,idlehooks,amap}.mjs`. No lab source changed yet
(all findings via pre-bring_up memory presets, non-destructive). E143 golden untouched.

---

## FINDING 2026-11-17g — two more walls peeled past the idle block (verified chain)

Continuing from 2026-11-17f with the CPU0 block preset in place, two further walls fall in
sequence (each verified by advancing to the next):

**Wall 2 — IRQ two-level ordering (NOT a fundamental gap).** With the block preset, bring_up
throws `two interrupt levels presented together at the idle point: {3: 31, 5: 30}`
(`kernel.mjs:287`, `strict_levels=true`). But `display.mjs:888` sets `lt.strict_levels = false`
on the normal display-bring-up path — the throw is reached only because the preset shortcut hits
an idle point before that clears. Setting `lt.strict_levels = false` (as the real path does) lets
it proceed (`scratchpad/preset3.mjs`). So this is a sequencing artifact of the preset probe, not
a missing E113 INTC order — supersedes 2026-11-17f's "next wall = IRQ ordering" framing.

**Wall 3 — firmware task-start guard (the task-modelling frontier).** Next throw:
`kernel would start firmware task entry 0x500442dc (exinf 0x0): not modelled`
(`kernel.mjs:262`). The guard allows a firmware task to start only if `hle.patch.code(entry)`
models it; otherwise it refuses. Task entry disasm 0x500442dc = runtime **0x7d9d52fc** — a system
task that reads global 0x7d9b068c (adjacent to the per-CPU block base 0x7d9b0930), calls OlyQe
primitive 0x7d9c12bf, and logs via 0x7d997b14. It is created via `acre_tsk` with a computed
entry (no static immediate xref), so naming it needs dynamic capture.

This is the **"drive the emulator" multi-session work** the user flagged: each firmware task the
GUI bring-up starts must be either allowed to run natively or given a lab stand-in/patch model.
The next step is to understand how E143 gets past these task starts (are its tasks modelled via
`hle.patch` entries, or run natively?) and port/author the E113 equivalents — ultimately so the
GUI task runs and reads its own menu tables (the hook-the-reads plan).

### Verified chain this session (all via non-destructive pre-bring_up presets, E143 untouched)

1. Idle gate — FIX FOUND: CPU0 block field[360]=1 + field[308]=IDLE_DONE (0x8a300080).
2. IRQ two-level at idle — artifact; `strict_levels=false` (normal display path) clears it.
3. Task-start guard at entry 0x7d9d52fc — the task-modelling frontier; open.

Probes: `scratchpad/{preset2,preset3}.mjs`.

---

## FINDING 2026-11-17h — task chain reaches the GUI task; terrain fully mapped

Allowing all task starts (overriding the `allow` set) with the block preset
(`scratchpad/enum.mjs`) boots **18 firmware tasks** and reaches a time wall:

```
settle: task(s) [46, 10, 74, 1, 57] in a timed wait
(0x5005cb01, 0x50043fd5, 0x500f5b75, 0x50031c0c, 0x500387f7): time model needed
```

The started entries (disasm) include **0x5089a153 — WinMain, the GUI App "Start Gui Task"**
(per [[guiapp-core-7e33]] / memory), plus 0x50893f78 beside it. So the "drive the emulator" path
**reaches the GUI task**: the firmware boots its full task set, which then sits in normal timer
waits (tslp/dly), exactly what a live system does at idle.

### Key clarification — the blanket preset desyncs from the display choreography

`bring_up('tft')` is not just "reach idle"; `display.mjs` runs a sequence that itself sets
`lt.strict_levels = false` (:888), `lt.time_mode = true` (:889) and `boot_time()` (:981), and
`menu.mjs:524` expects a **curated** task set to start — `GUI_TASK`, `EMPTY_TASK`,
`GENIFA_TIMER_TASK`, optionally `CHANGE_TASK` / `DEFER_TASK` / `PER_SCPU_TASK`. The pre-bring_up
block preset makes the firmware idle **earlier** than that sequence expects (hence preset2 hit the
two-level IRQ throw with strict_levels still true, and enum hit "time model needed" with
time_mode still false). So the presets prove the *mechanism* but are the wrong *placement*.

### The implementation plan (next session)

1. Put the E113 per-CPU-block idle init (**field[360]=1, field[308]=IDLE_DONE**, CPU0 block
   `*0x8ff00004`=0x7d9b0930) into the lab at the correct choreography point — in `kernel.mjs`
   after `hle.call(KENTRY)` / before device bring-up, **model-gated to E113** so E143 (which
   reaches IDLE_DONE via the INTC gate, no such block) is untouched.
2. Let `bring_up('tft')`'s own `display.mjs` sequence then set strict_levels/time_mode/boot_time
   as it does for E143 — don't preset those by hand.
3. Reconcile the started-task set with `menu.mjs:524`'s curated `wantStarted`: identify which of
   the 18 E113 tasks map to GUI_TASK / EMPTY_TASK / GENIFA_TIMER_TASK (E113 entry addresses) and
   populate `MODEL_INFO.extra_tasks` / the task-id constants for E113.
4. With the GUI task running under the time model, hook its menu-table reads to discover the E113
   menu-table addresses — feeding the offline `menudata.mjs` port (the original check_menu failure).

### Full verified wall chain this session

1. Idle gate — FIX FOUND: block field[360]=1 + field[308]=IDLE_DONE.
2. IRQ two-level at idle — `strict_levels=false` (display.mjs does this normally).
3. Task-start guard — allow the firmware's tasks (curated set per menu.mjs:524).
4. Timed-wait — `time_mode=true` + the lab clock (display.mjs does this normally).
   → firmware boots 18 tasks incl. WinMain (GUI task) and sits in normal timer waits.

Probes: `scratchpad/enum.mjs` (+ clean/preset/preset2/preset3/hookmap/idlehooks/amap).

---

## FINDING 2026-11-17i — idle-block fix IMPLEMENTED + verified in the real path; next wall = TFT-open activates the QPWRSTANDBY power-task cluster

Turned FINDING 17f's verified fix shape into real lab code (not a hand preset), and drove the
**actual** `bring_up('tft', time:true)` path with it — confirming the fix clears the idle gate
through the lab's own choreography, and surfacing the genuine next wall.

### The fix (committed — om-emu `1e9177c`, branch e113-port)

- **`src/fw/models.mjs`** — new E113-only fact, sibling to `xcpu_standin`:
  ```js
  idle_block: { table: 0x8ff00004, ready: 360, dispatch: 308 },
  ```
- **`src/lab/kernel.mjs`** `LabTask.boot()` (after `hle.hooks = saved; install_guards()`):
  ```js
  if (MODEL_INFO.idle_block) {
    const ib = MODEL_INFO.idle_block, block = sim.u32(ib.table) >>> 0;
    sim.word((block + ib.ready) >>> 0, 1);          // field[360] = idle-ready
    sim.word((block + ib.dispatch) >>> 0, IDLE_DONE); // field[308] = idle-dispatcher -> lab home
  }
  ```
  CPU0 block = `*(0x8ff00004)` = 0x7d9b0930; field[360]=1 takes the idle arm (not the panic
  self-loop 0x7d9a730b), field[308]=IDLE_DONE lands the `calls (a0)` on the lab trampoline.

### Verified

- `OM_EMU_MODEL=E113` clean `bring_up('tft', time:true)` (NO hand presets) now clears the idle
  gate AND the two-level IRQ point (boot_time sets strict_levels=false) — advancing to the
  task-start guard. The blanket-preset desync of 17h is gone: the lab's own display.mjs sequence
  does strict_levels/time_mode/boot_time at the right point.
- E143 golden `check_kernel` still passes all groups (0.9 s). The fact is E113-only, so E143's
  `if (MODEL_INFO.idle_block)` is skipped (E143 reaches IDLE_DONE via the INTC gate, no block).

### Next wall — the QPWRSTANDBY power-task cluster (E113 TFT-open only)

With the idle gate cleared, `bring_up('tft', time:true)` stops at:

```
kernel would start firmware task entry 0x500442dc (exinf 0x0): not modelled
```

- Entry 0x500442dc = runtime **0x7d9d52fc**; its name ptr (0x7da61104) reads **"QPWRSTANDBY"** —
  a power-standby service task. Body: acquires an OlyQe resource (id 16, via 0x7d9c12bf), then
  loops on 0x7d9c132d with d1=-1 (TMO_FEVR) — a mailbox/flag service task.
- It is activated during the step **"DISPLAY cmd 2 OPEN tft (code 1)"** (the display-open
  doorbell handler), caller chain through the kernel act_tsk SVC (0x7d9a2a59).
- Allowing it does NOT settle: it cascades to 4 more tasks
  (disasm 0x500f5b75, 0x5003ca91, 0x500f7722, 0x500387f7) then fails at
  **`settle: isig_sem = -42`** — the lab's OWN `settle_sem` (kernel.mjs:350), i.e. the power
  cluster corrupted the lab task choreography. So these tasks are NOT meant to run in the lab;
  this is not a missing firmware sem.

### The divergence (clean A/B)

Same step on each model, `bring_up('tft', time:true)`:

- **E143** (golden, SETTLES): "DISPLAY cmd 2 OPEN tft (mask 1)" starts **exactly one** task.
- **E113**: "DISPLAY cmd 2 OPEN tft (code 1)" starts **QPWRSTANDBY** (+ cascade).

So E113's display-open handler takes a path E143's does not — reaching an act_tsk of the
power-standby task cluster during TFT-open. Resolving it means tracing the E113 display-open
doorbell handler to find what gates the power-standby branch (a mode/flag precondition the
partial lab boot leaves in the "do power init" state), OR teaching the lab to stand in for the
power subsystem the way it already stands in for the sub-CPU (`xcpu_standin`). This is the next
sub-investigation — a fresh RE job on the display-open path, not a one-line fact.

Probes: `scratchpad/{clean2,clean3,tasks2,caller,step,e143step,allow1}.mjs`.

### FINDING 2026-11-17i addendum — the power cluster is allowable; the real blocker is the settle_sem the display-open path deletes

Deeper probing of the QPWRSTANDBY wall reframes it. Two separable issues:

**1. The power-task cluster is a known family the lab already permits.** E113's
`MODEL_INFO.extra_tasks` (models.mjs:1574) already lists 10 service tasks, including
**0x500441ea and 0x50043fd5 — adjacent to QPWRSTANDBY (0x500442dc)**. So QPWRSTANDBY and its
cascade (0x500f5b75, 0x5003ca91, 0x500f7722, 0x500387f7) are the same power/service family;
they park on `TMO_FEVR` waits like the others. Pre-allowing the 5 (clean, no monkeypatch) lets
bring-up run much further — `started[]` then includes WinMain (0x5089a153, GUI_TASK),
GENIFA_TIMER (0x50893f78), all 10 existing extra_tasks, and the 5 new ones — reaching the GUI
settle. (Static creation site not an immediate in blk00; likely a T_CTSK table in blk01 or a
computed entry — the name "QPWRSTANDBY" at 0x7da61104 is the *mailbox* name the task registers
itself via 0x7d9c12bf id 16, not a static task-table field.)

**2. The genuine blocker: E113's display-open deletes the lab's settle_sem.** With the cluster
allowed, bring-up stops at **`settle: isig_sem = -42`**. -42 is **E_NOEXS** (object does not
exist; kernel.mjs defines E_OK=0, E_QOVR=-43, E_CTX=-25 — no -42, so it's the µITRON standard).
The lab's `settle_sem` is created at **construction** (menu.mjs:290, `make_settle_sem`) and gets
id **112**. By the GUI settle it no longer exists: `isig_sem(112)` → E_NOEXS. Recreating it after
the failure returns **id 112 again** — i.e. slot 112 is now *free*, so the firmware's display-open
path **deleted/reinitialized the semaphore pool** (clearing the lab's 112), which E143's does not.

So the E113 display-open doorbell handler, beyond activating the power family, reinitializes the
kernel semaphore pool — invalidating the lab's early settle_sem. **Next-session fix directions:**
(a) create `settle_sem` *after* the firmware's sem-pool init (lazily at first settle rather than
at construction) so it lands above the firmware's range; (b) reserve a settle_sem id the firmware
won't reclaim; or (c) model/understand the display-open sem-reinit so it leaves lab objects alone.
Plus: add the 5 power-cluster entries to E113 `extra_tasks`, then reconcile `started[]` with
`menu.mjs:524`'s `wantStarted`. Probes: `scratchpad/{allow2,semfate,semrecreate}.mjs`.

#### Sharpening the settle_sem diagnosis (A/B + mechanism)

- **E143**: `settle_sem` = id **146**, created at construction, **survives** bring_up → SETTLES.
- **E113**: `settle_sem` = id **112**, created at construction, **deleted** during the display-open
  step → `isig_sem` E_NOEXS.
- The ids differ because **fewer firmware semaphores exist at lab-construction time on E113**, so
  the lab's construction-time `acre_sem` (menu.mjs:290) is handed id **112** — which falls *inside*
  the range E113's display-open semaphore init later populates/clears. On E143 the lab gets id 146,
  above the firmware's range, so it is never touched.
- **No `del_sem` API call** deletes 112 (hooking SVC del_sem A(0x50010279) across the whole
  bring-up catches nothing). So the pool is reinitialized by a **direct memory write** (the firmware
  zeroing/re-creating its sem control blocks during display-open), or the lab created settle_sem
  *before* the firmware's own kernel sem-table init, which then clobbered the premature id.

**Concrete fix for next session:** the lab's settle_sem must be given an id outside the firmware's
range on E113 — e.g. allocate it *after* the firmware's sem subsystem stabilizes (not at
construction), or reserve a high id. The constraint is that the display-open settle itself needs
settle_sem, so "after display-open" is too late; the target is the window between the firmware's
sem-table init and the first settle. This is the precise, bounded next step.

#### DEFINITIVE: the firmware reinitializes the whole kernel semaphore pool during display-open

Deeper probing (scratchpad semwhen/semstate/semfate2) nails the mechanism, and rules out the
"give settle_sem an id above the firmware range" fix:

- Deferring settle_sem creation to the first settle (lazy, E113-gated) moves its id from 112 to
  **115** — but `isig_sem` still returns E_NOEXS. So id placement alone does **not** help.
- The first settle happens *inside* the `DISPLAY cmd 2 OPEN tft` step. Sequence inside `settle()`:
  (1) `make_settle_sem` → acre_sem → id 115 (sem exists); (2) `twai_sem(115)` blocks the lab task,
  firmware runs the display-open work; (3) firmware **reinitializes the kernel semaphore pool**
  while the lab task is blocked; (4) idle reached → onIdle → `isig_sem(115)` → **E_NOEXS**.
- Proof of a pool reinit (not a targeted delete): a fresh `acre_sem` *after* the failure returns
  id **112** — i.e. the dynamic allocator's high-water has been reset back down to 112. Static sems
  1..~111 survive; everything from 112 up (including the lab's 115) is wiped. No `del_sem` API call
  fires (SVC del_sem hook catches nothing) → it is a direct pool/table reinit in the firmware's
  display-open init path.
- At the failure point there are also 5 firmware tasks in timed waits (`timed_waiters
  [46,10,74,1,57]`), tolerated under time_mode — not the blocker; the dead settle_sem is.

**Interpretation:** E113's display-open drives the firmware's own kernel/OS semaphore-table init
(cre_sem of the static sems 1..~111 and a reset of the dynamic allocator). The lab created its
settle_sem in the volatile region above that, and the firmware's init wipes it. E143 survives
(id 146) because its display-open does not run this pool reinit.

**Why id placement can't win:** the reinit resets the allocator and clears the dynamic region
*while the lab task is parked on twai_sem inside the same step*; any lab sem id in the dynamic
range dies regardless of when it was allocated.

**Next-session fix is architectural, not a fact-file knob.** Candidate directions:
1. Make the display-open settle not depend on a firmware kernel semaphore — wake the blocked lab
   task through a mechanism the firmware's pool reinit can't touch (e.g. drive the lab task's own
   scheduler state / a dedicated lab-owned wake path), E113-gated.
2. Run the firmware's sem-pool reinit to completion *before* the lab's settle needs a sem — i.e.
   split the display-open step so the firmware finishes its kernel sem-init (pool reset) first,
   then the lab allocates settle_sem and settles. Requires finding the exact reinit call in the
   display-open path (trace which firmware routine resets the allocator to 112).
3. In time_mode, since 5 tasks are in timed waits here, consider letting bring-up *elapse* at this
   point (fire the timeouts, let the firmware complete display-open) rather than isig-settling,
   then settle with a fresh sem afterward.

Direction 2 needs a firmware trace (which display-open routine reinits the pool); directions 1/3
are lab-architecture changes. All three are multi-step and belong to the next session.

#### PIVOTAL open question for next session (decides the fix family)

Before building any settle workaround, answer: **is the kernel sem-pool reinit genuine E113
display-open firmware behavior, or an artifact of the idle-block fix** (models.mjs `idle_block`,
kernel.mjs boot) steering the firmware's idle dispatch into a kernel re-init path?

- If **genuine**: the lab must survive it — fix is architectural on the lab side (wake the parked
  lab task without a firmware sem: find firmware `rel_wai` and fall back to it in settle()'s onIdle
  on isig_sem E_NOEXS; or restructure so the display-open settle runs after the reinit completes).
- If **artifact**: the idle-block fix is sending the firmware somewhere wrong (re-running kernel
  init) and needs refinement, not a settle patch.

How to decide (next session): find the firmware routine that resets the sem allocator (acre_sem
returns 112 after the reinit, so the allocator high-water is reset). acre_sem body at runtime
0x7d9a0635 reaches the per-CPU KCB via global 0x8050 (cpu index) into table base ~0x8FEFFEC4,
KCB+380 = max-sem count, then the sem control-block table. Watch that table for the mass write
during `bring_up('tft', time:true)` and capture the writing PC; disassemble it and see whether it
sits in the display-open call tree (genuine) or is reached from the idle-dispatch indirection the
idle-block fix installs (artifact). The NativeKernel's sim is the place to add the write-watch.

Note: the lab's NativeKernel exposes tcb(tid)/state(tid) but no clean force-ready (scheduler
ready-queue insertion is firmware code), so "just poke the TCB ready" is not viable without
modelling the ready-queue — another reason to resolve the genuine-vs-artifact fork first.

---

## Settle wall RESOLVED (2026-11-17): slp_tsk/iwup_tsk park + TCB re-register

**The fork is answered: GENUINE.** The sem-pool reinit during display-open is real E113
firmware. The restore is reached entirely through kernel task-dispatch frames
(0x50011xxx/0x50012xxx) with no idle-trampoline (0x8a3xxxxx) frame on the stack, so the
idle-block fix only *enabled reaching* display-open; it is not in the restore's call path.

**What the restore actually does (wider than first thought):** it is a GENERIC kernel
object-pool restore that re-registers only the firmware's *static* objects. It de-registers
every dynamically-created object — not just the lab's settle semaphore but the lab's
dynamically `acre_tsk`'d **task** as well. Measured: during `bring_up('tft', time:true)` the
lab task (tid 0x91, TCB at 0x6068b508) has its registration field **TCB+16 zeroed** by the
restore (at rest TCB+16 == tid). A sem-based park cannot survive this (the sem CB *and* its
wait queue are gone), and re-creating the sem won't relink the orphaned waiter.

**The fix (E113-gated, models.mjs `settle_slp`/`settle_iwup`):** park the lab task on
`slp_tsk` instead of a semaphore, and wake it with the interrupt-callable `iwup_tsk`.

- `slp_tsk` = runtime 0x7d9a6a16 (disasm 0x500159f6): no id arg; checks the running task-node's
  wake-up count at node+21, else `knl_sleep_enter(kind=1)`; returns the wake code at node+64.
  Requires task context (node+28 ctx byte == 0).
- `iwup_tsk` = runtime 0x7d9a24d4 (disasm 0x500114b4): `knl_iwup_tsk`, interrupt-context wake of
  a slp_tsk sleeper, deferred dispatch. Validates the target: id domain vs KCB+380, then
  `TCB[id-1]` with **TCB+16 != 0** required — else **E_NOEXS (-42)**.

**Why slp beats sem here:** the slp_tsk sleep is *queue-less* — the wait state lives entirely in
the parked TCB (state bits + wake-up count), which the restore leaves intact. Only the
registration field (TCB+16) is cleared. So the recovery is just: on iwup_tsk → E_NOEXS, write
`TCB+16 = tid` (re-assert registration) and retry iwup_tsk. No object to re-create, no wait
queue to rebuild. Implemented in kernel.mjs `_settle_wake` (slp branch).

**Dead end (recorded):** `rel_wai` (knl_rel_wai 0x7d9a58d8 / disasm 0x500148b8) was tried first
to release the orphaned sem-waiter. It fails with **E_CTX (-25)**: rel_wai needs task context but
settle's onIdle wake runs in ISR context (`in_interrupt`). rel_wai is not ISR-callable; from task
context it dispatches immediately, incompatible with the onIdle deferred-dispatch run loop. The
`settle_rel_wai` knob was removed and replaced by `settle_slp`/`settle_iwup`.

**Verified:** `bring_up('tft', time:true)` now passes the settle wall. E143 golden stays green
(check_kernel all groups, check_menu all groups) — the mechanism is E113-conditional
(`MODEL_INFO.settle_slp` unset on E143, which keeps the acre_sem/isig_sem park).

### Next walls (post-settle, in order reached)

1. **Firmware worker task 0x500f95cc** (runtime 0x7da8a5ec) is `act_tsk`'d during display-open.
   Body = phase state machine over global 0x606b7f94 (0→1→2→3), parking on OlyQe event-flag
   0x7d9b0510 (twai 0x7d9c1138, pattern 3). Added to E113 `extra_tasks` so it starts natively.
2. **Lens-SSC device MMIO** (CURRENT wall): once the worker runs, it reaches lens-command code
   (bank 0x7dac, dispatch 0x50130235 / runtime 0x7dac1255) that issues lens API command
   **0x20041001** ("is lens 0's record present", facts.mjs ~2004) by writing it to a hardware
   command register: at runtime 0x7d9dbbf7 `mov d0,(a1)` with d0 = 0x20041001, a1 = an unmapped
   MMIO register (near 0x98e00068, the 0x98e00000 peripheral block). The lab faults (fault=2) on
   the unmapped register. Open question mirroring task #1's: on E143 this path either models the
   register or the worker parks before reaching it — needs the per-task stack/trace at the fault
   to confirm which task is executing the lens command, then decide model-the-register vs
   keep-the-worker-parked. Instrument the fault with a task/stack dump next.

### Session update (2026-11-17): worker is not the fault; root is the OSD engine

**Worker 0x500f95cc re-classified from `extra_tasks` to `stub_tasks` (H-STUBTASK).** Running it
natively was wrong: its own init loop (poll 0x7da8a5fb → detect 0x7d9d8915, 16 ms retry 0x7d9c1982
until reg0 == 0, then twai-park on 0x7d9b0510) never concludes without a lens — reg0 stays 0
forever. It is fire-and-forget (its only reader 0x7da8c596 just logs phase global 0x606b7f94, no
block), so the lab now pre-writes `ret [],0` at its entry (new `stub_tasks` list, consumed by
`kernel.mjs start()` via `runtime(d)` → `_bare_return` path). It starts and goes dormant. Correct,
model-scoped, E113-only. **First attempt wrote `A(d)`** — wrong, `A()` is a port-map LOOKUP that
returns identity for unmapped addresses, so it patched the disasm address, not the runtime entry;
fixed to `runtime(d)`.

**The fault (0x7dac1255 lens-unit write) belongs to a different task: tid 1, entry 0x50031c0c**
(runtime 0x7d9c2c2c), the core-init task — not the worker. Stubbing the worker left the fault in
place. tid 1's init reaches lens-unit cleanup 0x7dac122c, which zeroes 0x9bf00000 / 0x9bf00068 /
0x9bb00000 / 0x9bb00004 — pages not in the E118 board stub list — so the lab faults on the write.

**Dead end (recorded, reverted): stubbing the lens-unit pages.** Added 0x9BF00000 + 0x9BB00000 to
the E118 `stub_pages` so the writes land. Result: the fault is *replaced by an unbounded loop*, not
a settle. With `__DBGLOG` synchronous flushing (temporary machine.mjs hook, since reverted) the log
showed the lens-present query **SCPU 0x20041001** issued forever (reg0 == 0 every time) from a
rotating set of PCs (0x7d9db4ba, 0x7d996653, 0x4700164d, 0x7d9dbbf7), ~9 per cycle, interleaved
with repeating **osd-engine ERROR** lines. Stubbing hardware pages only unmasks the real
non-termination. Reverted the page additions.

**Decisive E143-vs-E113 comparison (same `bring_up('tft')`, same dbg log):**

| | osd-engine DEPTH err | osd-engine GUARD err | SCPU 0x20041001 | outcome |
|---|---|---|---|---|
| **E143** (golden) | 0 | 0 | 1 | **SETTLED** |
| **E113** | 6 | 20 | 36+ (climbing) | unbounded loop |

E143's display-open draws cleanly and queries the lens **once**; E113's OSD engine rejects its
blits and the bring-up never quiesces. **The osd-engine errors are the E113-specific upstream root**
(they appear first in the log, before the lens loop), and are a *modelled* device (`src/dev/engine.mjs`),
so this is tractable, not unknown hardware:

- **DEPTH** (6×, pc 0x7e6a5655, arg = 3): a depth-**3** blit, but E113's engine is configured
  `depth: 5` (`DEPTH_OK`), so `cmdFill`/`cmdCopy` reject it. E113 evidently issues **both** depth-3
  and depth-5 blits; the strict single-`DEPTH_OK` check can't admit both. (Comment at engine.mjs:11
  says E113 fills at depth 5 — but these depth-3 commands are real and separate.)
- **GUARD** (20×, pc 0xbd99xxxx, dst 0x80xxxxxx): blits whose destination/stride fall outside every
  registered guard slot (`guarded()` returns false). Either E113 registers guards differently, or an
  earlier display-plane init step the lab skips never populates them.

**Current wall (next target): E113 OSD-engine (engine.mjs) DEPTH + GUARD config.** The lens-detect
loop is downstream/concurrent; fix the OSD engine first and re-measure. No board/machine/engine code
changed this session beyond the reverts — committed state keeps only the `stub_tasks` mechanism
(kernel.mjs) + the E113 `stub_tasks`/`extra_tasks` edit (models.mjs). E143 golden stays green
(check_kernel + check_menu all groups).

### Session update (2026-11-17, cont.): the blocker is **tid1 alone**, and the guards are lab-seeded

Measured engine totals after `bring_up('tft')` (probe reads the engine debug window 0x8E000000:
EXEC_TOTAL +0x2C, ERR_TOTAL +0x08):

| | engine EXEC | engine ERR | task-starts | nk.tasks RUN | outcome |
|---|---|---|---|---|---|
| **E143** | 0 | 0 | 7 | 0 (settled) | reaches DRAM GUI tasks 0x510c1d01 / 0x5115ba0c |
| **E113** | 16 | 16 (100%) | 49 | **tid1 only** | faults at lens-unit write; tid1 never parks |

**Task-start divergence.** E143 starts 7 tasks and settles. E113 starts 49; the block
`0x5005148d 0x500f7722 0x500387f7 0x50031c0c 0x50032ed8 0x50038e2f 0x50053c58 0x5003400f
0x5005cb01 0x500529db 0x500441ea 0x50043fd5 0x500442dc 0x500f5b75` repeats **3×** — almost
certainly the 3-core SoC (CPU0/1/2) each booting ~16 tasks, not a retry loop. Of 145 TCBs after
bring-up, **only tid1 is RUN** (tids 9/10/23 WAI, the rest DMT). So the non-settle reduces to one
task: **tid1 = core-init 0x50031c0c** never reaches a park. Its init runs the OSD draws (all 16
fail) and 23 lens queries, then faults at the lens-unit cleanup write 0x7dac1255. Fix tid1's init
to complete/park and the lab settles.

**The 16 OSD errors, decoded from the engine error ring (0x8E000100, 32 B/slot):**
- `#0 DEPTH` pc 0x7e6a5655 (ROM), w0=0x20000310 → depth **3**. E113's engine is configured
  `depth: 5`, so the single-depth check rejects it. The other 15 fills are w0=0x…0510 → depth **5**
  (pass depth, fail guard). **E113 uses both depth 3 and depth 5**; the strict single-`DEPTH_OK`
  can't admit both.
- `#1..#15 GUARD`: a 5-fill cycle (dst/stride 0x804ee2dc/0x2000, 0x805602dc/0x1600,
  0x809f1640/0x2000, 0x80a91640/0x1580, 0x80aa42c0/0x880) repeated 3×, all color-0 `cmdFill`s
  (ENC2 bus addrs, bit31 set). No guard slot covers them.

**Why GUARD: the guards are lab-seeded, not firmware-registered.** engine.mjs has no guard-write
register path; `guarded()` reads debug-window slots that only `engine.set_guard(slot,base,stride,
height)` fills. The lab seeds them from **`GUI_GUARDS` + `STRIDE` (constants in src/lab/osd.mjs)**
at `menu.mjs:295` / `text.mjs:142`, and check_menu seeds from `lab.surfaces` (check_menu.mjs:234).
These carry **E143 framebuffer geometry** (one STRIDE, E143 bases). E113's surfaces have *multiple*
strides (0x2000/0x1600/0x1580/0x880) at different bases (~0x004e_xxxx cached), so none match →
every E113 fill fails GUARD. board.mjs:361 even notes "the guards are the lab's" for this E118/E144
engine form.

**Consequence for the fix:** making E113 settle needs tid1's display/lens init to complete, which
needs the OSD engine to *accept* E113's fills — i.e. **model-parametric guards + depth**:
1. `GUI_GUARDS`/`STRIDE`/`surfaces` must become per-model (E113's real surface bases/strides/heights),
   or the lab must seed E113 guards covering its actual fill rects.
2. The engine DEPTH check must admit E113's depth-3 **and** depth-5 fills (a depth set, or a board
   `depths: [3,5]` knob), not a single `DEPTH_OK`.
Then re-measure: with fills accepted, does tid1's lens init conclude "no lens" and park, or does the
SCPU 0x20041001 loop still spin? (The lens query is bounded per cycle — 3 units × call sites — so
the outer repeat may be gated on the display coming up; fixing OSD first is the right order.)

**Open RE task:** find E113's OSD surface geometry. The fill destinations observed
(base ≤ dst, matching stride) bound each surface, but the authoritative source is the firmware's
display-surface registration near the ROM engine-setup 0x7e6a5600 (writes engine base 0x9c100000
regs 0x04/0x20/0x24/0x38/0x3c). That code sets surface config the lab can read to seed guards
honestly rather than back-fitting observed blits.

### Session update (2026-11-17, final): two walls disentangled; OSD cosmetic CONFIRMED; lens pages fixed

Three corrections/advances this session, each measured:

**1. OSD-engine errors are COSMETIC — definitively.** `src/dev/engine.mjs` `read(off)`
(line 316) returns `this.regs[off>>2]` — the firmware-visible register. GUARD/DEPTH errors go
ONLY to the lab debug window via `recordError` (0x8E000000, which the firmware never reads — it
polls engine base 0x9C100000). After a GUARD-failed `cmdFill`, the engine's status regs still
report done/success, so the firmware proceeds exactly as on real hardware. The 16 OSD errors in
E113's `bring_up('tft')` do NOT gate settle. **Dropped as a lead.** (They remain a lab-diagnostic
mismatch: guards are seeded from E143 geometry — see prior section — but that is cosmetic.)
Corollary: memory "Wall B" (OSD-geometry global 0x6055f944=0) is RESOLVED — it now reads
0x800 (2048 = main surface width; stride 0x2000 = 2048*4 at depth 5). OSD-geometry init runs.

**2. Lens-comm HW pages added (board.mjs, E118 family) — removes the bring_up fault.**
`bring_up('tft')` for E113 faulted (fault=2) at `mov d0,(0x9bf00000)` pc 0x7dac1255 — the
lens-unit reset `fn 0x7dac122c` ("reset 3 lens units", no-lens path, called once from lens-module
init 0x7dac54e3) clears lens-SSC HW regs 0x9bf00000/+0x68 and 0x9bb00000/+0x04 that the lab did
not map. Added `0x9BF00000, 0x9BB00000` to the family `stub_pages` (plain reset targets: absorb,
read 0). Verified: E143 `check_kernel` green (0.8 s, these pages are E118-family, E143 unaffected);
E113 `check_menu`/`check_kernel` unaffected (they fail earlier — see below — never reaching this).

**3. The two genuine live walls, now cleanly separated:**

- **WALL A — bring_up('tft') settle (lens-detect / SCPU sub-CPU response).** With the lens
  pages mapped, the fault is gone but `bring_up` now SPINS (>120 s): tid1 (start-up task
  0x50031c0c, in `extra_tasks`) re-issues SCPU lens-present query **0x20041001** (pc 0x7d9dbbf7),
  count climbing 23→32→36+. The SCPU model (`src/dev/scpu.mjs`) is **passive storage** — on a
  command write it stores/counts/prints; it does NOT simulate the sub-CPU computing a lens-detect
  result. So every result word the firmware reads back stays 0 = "no lens / not ready", and tid1's
  detect retry never concludes → the task never reaches its block (park), so the kernel never
  quiesces. (The per-command poll `mov (a1),d0; cmp 0; lne` at 0x7d9dbbf8 terminates fine — COMMAND
  reads 0 immediately; the non-termination is the OUTER detect retry.) Stubbing the lens/FCS WORKER
  0x500f95cc was not enough because tid1 runs its own lens detect inline. E143 never starts this
  detect in its tft bring-up (7 tasks, settles), so a real no-lens camera concludes "no lens" via
  a real SCPU answer the lab does not yet model. **Next:** model the SCPU `scpulink` response for
  0x20041001 to return "detect complete, no lens" (find the result word/encoding tid1 branches on),
  OR seed that result so tid1 proceeds to park. This is a sub-CPU-modelling job, not a logic bug.

- **WALL B — menu usable (menudata table port).** This is the test's FIRST failure and the true
  "menu usable" blocker, independent of Wall A. `check_menu.mjs` step 0 (offline) calls
  `menudata.groups(img)`, which reads the group-list header at `GROUP_LIST = A(0x6152D6B8)`.
  `PORTS.E113[0x6152D6B8] = null` (explicitly UNPLACED) → `A()` returns the UNMAPPED sentinel
  0xfe52d6b8 → `img.u16()` throws "address 0xfe52d6b8 outside the image". `groups()` (unlike
  `tabs()`, which guards with `refuseUnmapped`) reads it unguarded, so the crash is a wild-address
  ValueError rather than the clean "no place in E113's build" message. The E113 menu-data tables
  — GROUP_LIST (0x6152D6B8), CUSTOM_VALUE (0x6152D6C0), TAB_TABLE/TAB_IDS, ROW_ORDER, etc. — are
  all unported: their E113 addresses must be found and placed in the port map
  (`emu/out/port/E113/port.json` / `src/fw/ports.mjs`). E113's menu subsystem differs structurally
  from E143 (per prior finding), so this is a standalone RE job: locate each table in E113's image
  by structure/xref. **Next:** find E113's group-list + tab tables (structure: u16 key=0, u16
  count, u32 ptr → count×16-byte {u32 page_id, 8-byte descriptor, u32 record}); the valid parse is
  42 groups, ids 0xCB..0xF4.

**State committed:** board.mjs lens pages (Wall A fault fix) + this doc. No settle faked; E143
golden green. Walls A and B both remain open multi-session RE; neither has a shortcut.

---

## RESULT 2026-11-18 — E113 passes `bin/modelcheck.mjs` on TFT and EVF; menu draws and navigates

om-emu commit `ef87f63` (branch e113-port, not pushed). This supersedes the "Wall A / Wall B" framing
above: neither turned out to be the gate in the form described.

### Acceptance

```
E113 (E-M10) tft: ok — start 0x108 720x480, s1 0x108, s2 0x108 1, play 0x608, info 0x60b, play 0x108,
                       menu 0x1102, down 0x1102, menu 0x108, count at 660,444,710,469 (redrawn by 3 x INFO)
E113 (E-M10) evf: ok — start 0x108 800x600, s1, s2, menu 0x1102, down 0x1102, menu 0x108,
                       count at 726,556,789,588 (redrawn by 4 x INFO)
```

Run with port regeneration (`bin/modelcheck.mjs E113`, rc 0). Screens checked by eye from frame
snapshots: Super Control Panel, Shooting Menu 1, cursor move, Image Stabilizer submenu via OK, MENU
back out.

Regressions: E120 `modelcheck` ok on both panels, `ports.mjs` byte-identical after `port.mjs E120`;
E143 `check_kernel` and `check_menu --quick` pass.

**Not covered / honest limits**

- `checks/*` (check_menu, check_kernel, check_display …) are **E143-only** by construction (they pin
  E143 notes and addresses). The acceptance test for a ported body is `bin/modelcheck.mjs`. "All tests
  run" for E113 therefore means modelcheck, not the checks suite.
- `modelcheck E143` fails (FEATURE_UNOBSERVED ch 2 off 0x48c / PLANC_UNOBSERVED ch 1 off 0x62c). It
  fails **identically on the parent commit** with all E113 work stashed, so it is pre-existing in this
  checkout and was not touched.
- `menudata.mjs` static tables (GROUP_LIST etc., the old "Wall B") are still unplaced for E113. They
  are only needed by E143's `check_menu` offline step; the firmware's own menu runs without them.
- EVF path has no playback steps in modelcheck for E113 (same as the family list in modelcheck).

### What was actually wrong (in order found)

1. **UI_IN task 0x500f95cc was wrongly stubbed.** It is the UI input task, not a lens/FCS worker; with
   it stubbed no key ever reached the GUI. Stub removed. (The "lens-detect SCPU retry" theory of Wall A
   was a misread of this task.)
2. **Settings-DB init**: the real init is 0x501d8d19, the indexed getter 0x50202f98; both needed hand
   overrides. `db_init_saves: 0` was wrong and removed.
3. **Shoot-count divide-by-zero**: the shots query (rule `shots-method`) is 0x502109d0 =
   `api_rec_Calculate_MediaRemain_Ext`, called from getAvailableShootCount 0x50648485 at 0x506484b2.
4. **Movie time record is 14 bytes on E113** (`movie_time_size: 14`); writing E143's 24 overwrote the
   caller's return address.
5. **Props 4013/4014** needed `any_site` (read from more than one site on E113).
6. **Object pools**: E113 allocates GUI objects from fixed-block pools (0x60001FC0, 0x6043F400), not
   the heap the lab scans for the shooting reactive → `object_pools` fact.
7. **Display sync never released → blank OSD.** E113 has a third, one-channel form of the display-sync
   API:
   - request `api_disp_Disp_Sync_Update_Osd` 0x501fdcb1 (d0 low u16 = type; synced type 5)
   - image sync `api_disp_Disp_Sync_Force_Update` 0x501fdf8c (compares code with 15/16/18 by
     `cmp n,d2`, opcode 0xaa, at 0x501fdfaa..b2)
   - sync-word setter 0x506308e9 (`mov d0,(0x6044cfc4)`)
   - `Get_Wait_State` 0x501fdc99 sits 0x18 before the request (two "entries" near the name string →
     the rule now takes the nearest).
   Release = setter(0) + Force_Update(18). After it the VSC gets R030=7, R034=1 and OSD bases
   0xbd86aa00 / 0xbd764200 / 0xbd65da00 (stride 896, height 600).
8. **`engine.depth: 5` was a dead end** (see below).
9. **Count probe**: E113 redraws the shooting screen on INFO like the E118 family → `PROBE_REDRAW`.
10. **EVF event storm at POWER_ON**: getOutputDisplay 0x5087a17b enumerates TFT=4, **EVF=5** (E120:
    5/6). The lab answered 6 → `output_evf: 5`.

GUI start events on TFT: 0x9601 → 0x9b01 → 0xf001 → 0x1100.

### Dead ends (do not repeat)

- **Depth 5 OSD engine.** Read off fills seen while bring-up was still broken (4 bytes/pixel, 2048x57,
  stride 8192). Once display sync is released every command is depth 3 on the 896 canvas; depth 5
  refused all 87 start-screen commands (DEPTH x87, planes all zero, one-colour frame). `word22: true`
  is kept (seen before the correction, not re-tested without it).
- **"OSD errors are cosmetic"** (earlier entry) was only half right: they do not gate settle, but they
  were exactly why nothing drew.
- **Lens-detect / SCPU response modelling** as the settle wall: not needed.
- **Static menu-table hunt** (immediate scan, structural scan, code xref): not needed for a working
  menu; only for E143-style offline table checks.
- Override format gotcha: an absent key makes `A()` return identity, silently hooking an E143 address
  in an E113 image. Keys are always E143 0x50… addresses.
- `$S/e143.asm` is in runtime addresses, `e113_full.asm` / `e120.asm` in 0x50… — mixing them cost time.

### Menu walk 2026-11-18 (TFT, 16 keys, no engine/lab errors)

Keys: MENU, (LEFT, DOWN) x4, RIGHT, DOWN, OK, DOWN, OK, MENU, MENU. Frames inspected by eye:

- All five tabs draw with their own rows: Shooting Menu 1, Shooting Menu 2, Playback Menu, Custom Menu
  (A AF/MF … G Color/WB, scroll bar), Setup Menu (clock `--.--.-- --:--`, language, brightness, Rec View
  0.5sec, Wi-Fi Settings, Menu Display, Firmware).
- RIGHT enters the row list; OK on the language row opens the 3-column language grid.
- DOWN + OK selects 中文(繁体): the value is written and the whole UI re-renders in Chinese (Setup Menu,
  Super Control Panel, Shooting Menu 1 on re-open) — a setting change goes through the firmware's own
  store and resource path end to end.
- MENU leaves to the shooting screen (0x108), MENU again re-opens (0x1102).

Not walked: Custom Menu sub-pages, Card Setup / Reset, date entry, Wi-Fi, firmware screen, playback-menu
actions (greyed without pictures). No reason found to expect failure, but untested.

## FINDING 2026-11-18b — value-popup commit hang = unmodelled second timer unit (Timer2); fixed

### Symptom
Custom Menu (and every other) value popup: OK on a value never closed the popup. The emulator ran an event storm
instead and MENU no longer backed out. Same storm on E120 (walk `xE120`) — family-wide.

### Root cause (D on E113, verified by fix)
- OK on a value sets the GUI's awaited event word (E143 `0x603710f8` → E113 `0x6045e348`) to `0x9ac6`
  (EVT_10MS_WIZARD_MENU) and starts Genifa timer 522 for 100 units. While a word is awaited, GuiCallerImp re-posts
  every other message. If the timer never fires, the re-post loop is the storm.
- Genifa timer task `0x50893f78`; its 10 ms tick callback `0x50893f46` (ms clock `0x60452d7c`) hangs off OS cyclic
  id 10 (handle `0x60452d74`) → software-timer driver → **second hardware timer unit at `0x9b800000`**, which the
  emulator did not model (board.mjs had `soft_tick: null` for the E118 family; docs/model-survey.md finding 155).
- So every software timer of the body stood still; anything that waits for a GUI timer hung.

### Timer2 register facts (E113, disassembly addresses)
| Fact | Value | Source |
|---|---|---|
| channel table | `0x7d998830`, 15 × 12 B: reg ptr, IRQ, handler | D |
| mask table | `0x7da64808`, 15 × 12 B: status ptr, IE ptr, bit | D |
| channel n regs | `0x9b800010 + 12n`: ctl +0, reload +4, +8 | D |
| ch 0–9 | status `0x9b8000c4` / IE `0x9b8000dc` bits 0–9, IRQ 24 (handler `0x7d9e23be`) | D |
| ch 10–14 | own status `0x9b8000c8..` / IE `0x9b8000e0..` bit 0, IRQs 25–29 | D |
| start | `0x50051072`: status=bit, IE\|=bit, ctl=1 | D |
| stop | `0x500510a1`: IE&=~bit, ctl=0, status=bit | D |
| ISR | `0x500511d4`; allocator `0x500510d2` | D |
| count conversion | `0x5005b37c`, rate from `0x5005affb` (32768 Hz / 24 MHz sources); max reload 1023 | D |
| after boot | ch0 ctl 1, reload 0x21 (2 ms service) | X (emulator) |
| count rate | 16384 Hz (33 counts ≈ 2 ms) | **I — inferred, not measured on camera** |

### Fix (om-emu `0febbf6`, branch e113-port)
- new `emu/src/dev/timer2.mjs` (class `Timer2`): MMIO block, per-channel ctl/reload, status write-1-to-clear,
  IRQ line = status & IE per group. Unknown widths / ctl values > 1 throw BUS (no silent guesses).
- `emu/src/machine.mjs`: built only when the board gives `timer2`.
- `emu/src/fw/models.mjs`: `timer2` board fact on **E113 only**. E120 and siblings likely share the unit but their
  IRQ numbers were not read — left unmodelled, so E120 still has the popup storm (known limit).

### Verified after fix
- `bin/modelcheck.mjs E113`: tft ok, evf ok, exit 0.
- Walks (all rc 0, no engine/lab error), TFT contact sheets viewed:
  - `wR` 21 keys: Custom C, Rls Priority C On→Off commits, popup closes, MENU backs out to shooting screen.
  - `y1` Shooting Menu 1: Reset/Myset list, Picture Mode Natural→Muted, record-mode page, Image Aspect 4:3→16:9,
    Digital Tele-converter Off→On; exit shows Muted/16:9 on the SCP; re-entry shows the values persisted.
  - `y2` Custom E (Noise Reduct. Auto→Off), F (X-Sync/Slow Limit popup), G (quality Set page, pixel count), second
    custom page H/I/J/K listed.
  - `y3` Setup: brightness/colour-temperature screen, Rec View 0.5→0.3 sec, Wi-Fi Settings page, Menu Display
    popup, Firmware screen ("Body : 0.0" — version word not seeded), exit.
  - `y4` EVF (800×600): same commit walk, 21 frames viewed, popup opens/commits/closes, exit to EVF shooting view.
- Side effect: menus show more/correct rows (Shooting Menu 2 drive/self-timer, Image Stabilizer, Time Lapse;
  fps 3.5/8) — rows that depended on a timer-driven refresh.

### Not walked / open
- Custom J/K sub-pages, D deeper pages; playback-menu actions with pictures on the card; Card Setup → format Yes;
  Reset execution; date/time commit.
- `sheet.py` assumes 720×480; EVF needs the 800×600 variant.

### Regressions after Timer2 (2026-11-18)
- `bin/modelcheck.mjs E120 --no-port`: tft ok, evf ok (rc 0) — E120 unaffected (no `timer2` board fact).
- E143 `checks/check_kernel.mjs`: all groups passed. `checks/check_menu.mjs --quick`: all groups passed, 0 problems.
- `bin/test-pure.mjs`: all passed (firmware facts 277/277, purity 256/256).

## Session 2026-11-18c — card FS/Format, Reset, flash-mark palette, firmware version, more walks

All om-emu work is on branch `e113-port` of `research/om-emu-src` (gitignored here, not pushed).

### Native card file system + Format (om-emu `db7cd28`)
- E113 `card_fs` is now native (firmware's own FAT driver on the emulated card) with HLS media side by `'rule'`.
- `fileSystem` fact: the cache-flush seam is optional — E113's free-count path has none (`cflush` null);
  `storage.mjs` wraps it only where read.
- `mediaHls`: E113's build names no source file, so the named-form rule found nothing. Added nameless forms; read on
  E120 with its names ignored they give the same addresses as its named fact (cross-check).
- `test_storage`: a press's waits now end where the media control starts — E113 formats a blank card inside one
  600 ms press, so the old fixed wait missed it. `test_storage --hls` 21/21 on E113 and E120.
- Card Setup → Format → Yes ends, card formatted, file read back.
- **Dead end / trap:** `bin/port.mjs` scans every `0x[8 hex]` literal in `src/` (except `src/util`, `src/core`,
  `src/machine.mjs`, `src/fw/`) as an **E143** address to port. A foreign-body address in `src/dev` or `src/lab`
  silently becomes a bogus port entry. Timer2's E113 evidence therefore moved into the board fact in
  `src/fw/models.mjs`. Never put E113/E120 addresses in `src/dev` / `src/lab` comments or code.
- **Limit:** `bin/port.mjs E120` fails ENOENT (it needs E144's block; only E113/E120/E143 are unpacked). E120 facts
  added since are hand-edited into `src/fw/ports.mjs` exactly as the rule reads them (`$S/bv.mjs`-style rule runs).

### Reset → Yes hang (om-emu `f85842d`, S-STBREQ)
- Symptom: Shooting Menu 1 → Reset/Myset → Reset → Yes held the GUI task forever, E113 **and** E120.
- Cause (D): the reset writes each flash setting through the strobe module's synchronous request; its receive of the
  strobe task's reply has no timeout; the lab never starts that task (E113 task 62, dormant). Call chain ends in
  E113 `0x501f2cd4`.
- **Dead end:** starting the strobe task natively (`act_tsk`) — it needs strobe hardware the lab does not model and
  drags a new cluster in. Dropped.
- Fix: rule `strobeRequest` (fact `strobe`) finds the request and the task-loop site by the module's two error
  codes; stand-in answers a callback-less request 0 while the task is dormant. Task-entry search span widened
  0x400 → 0x1000 (`STB_TASK_SPAN`): loop site is 0x1f5 past the entry on E113 but 0x7c5 on E120.
- Verified: Reset walk strict (`c5`) on E113, `d1` on E120, no errors; settings return to defaults.

### Flash mark after Reset — PAL_FIELD (om-emu `e4820eb`)
- After Reset the SCP draws a flash mark into GUI layer 0 (OSD1) with pixel values 33, 35, 38; the kind-3 compositor
  refused indices ≥ 32 (PAL_FIELD, model-survey finding 97).
- Entries 1, 3, 6 = opaque black, orange, shadow → the mark's outline, body, shadow. So a pixel 32..63 now takes the
  entry of its low 5 bits (`fold: 64`); ≥ 64 still refused. `test_vsc3` 54/54.
- **This is an inference**, not a measured panel fact: what bit 5 does on hardware is unknown (blink? priority?).
- Tooling note: raw frame bytes are Y,Cb,Cr,X. Old `sheet.py` showed green/purple; `sheet2.py` converts correctly.

### Firmware page "Body : 0.0" → "Body : 1.4" (om-emu, S-BODYVER)
- Page code E113 `0x5094a449..0x5094a48f`: singleton getter `0x50666295` (global `0x6044e954`, vtable
  `0x7e3861c8`), vtable+16 → `0x5066688b` → impl `0x506c2019`, jump table at runtime `0x7e3b2ab0`:
  item 0 (API_VERSION_BOOT) reads sysinfo struct +96; item 1 (API_VERSION_USER) reads +104 & 0xffff.
  Page prints `%X.%X` of bits 12..15 and 8..11.
- Struct filled by `0x50212ed1`: +96 = data object `0xC0A2`, +104 = data object `0xC082` (| d2<<16), read through
  `0x50203bb5` → `0x500426a4`.
- Boot (not run by the lab) writes `0xC082` at E113 `0x500f6858`:
  `mov (0x7d991010),a1; mov 0xc082,d0; mov 0,a0; clr d1; call 0x5004277b`. E120: `0x5016c4f5`, source
  `(0x6113f810)`, setter `0x50055c3b`.
- **Source of the word:** runtime `0x7d991010` = image base − 0x10 = the **update container's block-0 header +0x10**
  (`tools/omfw.py`: header `@16 ver u32`). Headers: E113 `…00140000…` = 0x1400, E120 0x1400, E143 0x1900 — the number
  in the file name. So the 32-byte container header is mapped just below the image in flash; the unpacked block does
  not hold it, hence 0 in the lab.
- **Dead end:** first S-BODYVER wrote `0xC0A2`. That is the BOOT version (filled at E113 `0x500f69b8` from the last
  word of the 1024-byte loader header at `0xa0a9cdb0`), not what the page shows. Page stayed 0.0.
- Fix: rule `bodyVersionWrite` (fact `body_version {id, set, word}`) matches the `mov (abs),a1` + load of `0xC082` +
  setter call; lab bring-up (`menu.mjs body_version()`, native store only) calls the firmware's setter with
  `N<<12 | M<<8` from the model's version. E143 (lab model) untouched. BOOT version left 0.
- Verified: walk `v2` (`menu, up, right, up, ok, ok`) shows **"Body : 1.4"**.

### Walks this session (E113 TFT, rc 0, no engine/lab errors, contact sheets viewed)
- `a1`–`a3`: Card Setup → Format, playback after format.
- `c5`: Reset → Yes, back to SCP with defaults + flash mark.
- `e1`: Setup menu navigation; Wi-Fi Settings submenu (Wi-Fi Connect Settings = Private, Private Password, Reset
  share Order greyed, Reset Wi-Fi Settings); Shooting Menu 2 (drive, Image Stabilizer: Still S-IS AUTO / Movie M-IS
  On, Bracketing popup Off/On); exit to SCP.
- `e2`: Firmware page; date/time entry — Y/M/D/h/m/format fields all edit, OK stores, Setup shows '14.01.01 00:00.
- `v2`: Firmware page with version.

### Still not walked
- Playback-menu actions with pictures (erase, protect, rotate, edit), Custom Menu J/K and deeper D pages, Myset
  save/recall, Wi-Fi password screen and Reset Wi-Fi → Yes, EVF variants of this session's walks.

### Test status (honest)
- `checks/*` is E143-only by construction (pins E143 addresses/notes); it never ran against E113.
- Full `bin/test.mjs` was started once and killed by the host for low memory (machine ~3.4 GB); not restarted.
- `bin/modelcheck.mjs E143` fails FEATURE_UNOBSERVED / PLANC_UNOBSERVED — pre-existing upstream, not E113-related.

### Regression after S-BODYVER (om-emu `89b0c55`, 2026-11-18)
- `test:pure`: all passed (SCPU 15/15, firmware facts 278/278, purity 256/256).
- `bin/modelcheck.mjs E113`: tft ok, evf ok. `bin/modelcheck.mjs E120 --no-port`: tft ok, evf ok.

## Session 2026-11-18d — Connection to Smartphone (Wi-Fi wizard), S-WIFIMGR

### Walks before the fix
- `p1` (clean): Playback Menu opens; rotate On/Off popup works; Edit / Print Order / Reset Protect greyed with an
  empty card; Shooting Menu 2 drive list and Image Stabilizer page fine.
- `p2`: `s1,s2` stores a picture (playback shows "001-0000", frame 1, 2014.01.01); OK in playback opens the JPEG
  popup. **Open issue A:** Playback Menu's Edit / Print Order / Reset Protect stay greyed even with that picture on
  the card. Not investigated.
- `p2` **Wall:** Playback Menu → Connection to Smartphone: wizard pages 1/3 and 2/3 fine, third OK →
  `unserviced inter-CPU doorbell: pending [0,1,0,0]`.

### Analysis (E113 disassembly addresses)
- Doorbell write `mov d2,(0x99300308)` at `0x5003350a`, inside the common cross-CPU request `0x500334c8`
  (d0 object id, a0 request slot; id bits `0x7000` pick the CPU: `0x1000` CPU1, `0x2000` CPU2, 0 CPU0). Seven
  wrappers call it; here OlyQeWrCpu `0x5003302a` (via the generic queue write `0x50030409`) and OlyEvStCpu
  `0x500330ec` (via `0x5003004e`).
- Sender = `wifi_mngr_start` `0x50d76631` (logs "Error : wifi_mngr_start"), called from
  `api_network_Start_In_Wlan_Communication`. It sends a 16-byte command `{0,0,1,ptr config}` to queue `0x1005`
  (CPU1) and sets pattern 1 of the manager's flag. Config holds SSID and password. `wifi_mngr_stop` `0x50d766f9`
  sets pattern 2. So the Wi-Fi manager task lives on CPU1, which the lab does not run; upstream models no radio on
  any body (`docs/a53-spec.md`: "stub as absent").
- api_network init (`0x502063c9`) registers eight callbacks at `0xa0a67c90..ac`; each posts one GUI event
  `0x041c0001..8` through `0x5002028f(event, arg, 0)`:
  `0x502062f0` (sets started word `0xa0a67cb0`=1, event 1), `0x5020630f` (2), `0x50206328` (3),
  `0x50206341` (clears started word, event 4), `0x5020635f` (5), `0x50206378` (6),
  `0x50206391` (stores Last_Err `0x6044acf4`, event 7), `0x502063b0` (8).

### Experiments (probe hooks, one walk each)
| run | what | result |
|---|---|---|
| p4 | both CPU1 requests answered 0, nothing else | "Wi-Fi Starting" forever, MENU/OK ignored — **dead end** |
| p5 | requests answered `0xffffffff` | same; return value is ignored — **dead end** |
| p7 | + error callback `0x50206391(1)` | still "Wi-Fi Starting" — **dead end** |
| p8 | + callback 0 `0x502062f0(0)` | page 3/3: SSID `E-M10-P-00000001`, password, "End Wi-Fi MENU"; MENU → "Wi-Fi Stopping" forever |
| p9 | p8 + callback 1 `0x5020630f(0)` after stop | still "Wi-Fi Stopping" — **dead end** |
| p10 | p8 + callback 3 `0x50206341(0)` after stop | wizard leaves, shooting screen, menu usable again |

Harness note: pointer-called functions have no call-site frame; `lab.call(fn, regs, null, [0, 0], limit)`.

### Fix — S-WIFIMGR (om-emu, E113 + E120)
- Fact `wifi_manager {send, start, stop, started, stopped}` (`src/fw/facts.mjs wifiManager`): start function found
  from the "Error : wifi_mngr_start" load; the flag-set call sites by `mov 1|2,d1; exth d0; call` (stop = function
  after start); send = call before start's flag set (`mov -1,d1`); callbacks by event loads `0x041c0001` /
  `0x041c0004` with the `add -4,sp; mov d0,d1; mov 1,d0 | clr d0; movhu` head. E113
  `{0x50d766c4, 0x50d766d4, 0x50d76721, 0x502062f0, 0x50206341}`, E120
  `{0x50e1685e, 0x50e1686e, 0x50e168bb, 0x502ad502, 0x502ad553}` (E120 hand-edited in ports.mjs, same reason as
  before). E143: callbacks have another shape → no fact, untouched.
- Lab (`camapp.mjs _install_wifi`, `pump()`): the three cross-CPU calls are stepped over with 0; pump then calls
  api_network's own callback with 0 — started after a start, stopped after a stop — and settles.
- **This is a stand-in, not a radio.** No AP exists, nobody can connect, the other six callbacks never come. That a
  real manager answers start with exactly callback 0 / arg 0 is inference; the pairing is from the walks above.
  SSID shown is the firmware's own (serial-less lab NVRAM), password from firmware.
- Verified: walk `q1` on the plain build — wizard 1/3, 2/3, 3/3, End Wi-Fi → shooting screen; menu; wizard again,
  Cancel on 1/3; wizard again to 3/3, End Wi-Fi; playback ("No Picture"). rc 0, no errors.

### Observation: mode becomes iAUTO after the wizard (open issue B)
After End Wi-Fi the shooting screen shows iAUTO (was P), and the menu greys what iAUTO greys (Picture Mode,
Bracketing, HDR, …). Likely not Wi-Fi-specific: E113 has no `mode_dial` name table (fact `mode_dial {table: null}`),
so the lab's idle dial image sits at position 0 while the start screen is P; upstream documents the same mismatch
on E143 (application reconciles to the dial at a later event). Hypothesis only — not verified which E113 dial
position is P.

### Regression after S-WIFIMGR (om-emu `54f2550`, 2026-11-18)
- `test:pure`: all passed (firmware facts 278/278, purity 256/256).
- `bin/modelcheck.mjs E113`: tft ok, evf ok. `bin/modelcheck.mjs E120 --no-port`: tft ok, evf ok.
- E120's wizard itself not walked (fact + modelcheck only).

### Walk `r1` — Custom Menu K / J (E113 TFT, om-emu `54f2550`, rc 0, sheet viewed)
- Custom Menu list scrolls A..K (up from A wraps to K). K Utility page: Pixel Mapping + Eye-Fi greyed, Level Adjust
  popup ("Reset" / "Reset to default."), back.
- J Built-In EVF: Info Settings checkbox page — Highlight&Shadow toggles on with OK; Built-in EVF Style popup with
  preview bitmap, Style 3 → Style 1 stored and shown in list. Exit to SCP (P mode), menu reopens.

### Walk `r2` — Setup Menu, Wi-Fi Settings, direct buttons (om-emu `54f2550`, rc 0, sheet viewed)
- Setup Menu: Wi-Fi Settings page (Wi-Fi Connect Settings Private, Private Password, Reset share Order greyed,
  Reset Wi-Fi Settings) opens and backs out. Menu Display On → Off popup with preview; after it the Custom Menu
  tab is gone from the tab column (as on the camera).
- Shooting screen: DOWN = drive strip (Single → Sequential H stored, SCP shows it), RIGHT = flash strip, OK = SCP
  cursor (ISO "Recommended ISO").
- Not reached in this walk (key sequence drifted): Private Password screen, Reset Wi-Fi Settings → Yes, Myset.
- Date line shows `'--.--.-- --:--` (lab RTC unset) — not investigated.

### Open issue B solved — mode dial (om-emu `c834828`)
- Cause confirmed. E113's dial query `0x500fa591` is E143's walk (position byte `0x606b7fe8`, count the group's
  0x1901 entries) but compares in d1 and takes the code through leaf `0x500fc8c5`
  (`extbu d0; asl2 d0; mov 0x7dbc1558,a0; mov (d0,a0),d0; retf`) — the fact rule only knew E143's inline load.
- Table `0x7dbc1558`: 49, 1, 2, 3, 4, 10, 57, 42, 51 = iAUTO, P, A, S, M, MOVIE, PHOTO_SESSION (Photo Story), SCN,
  ART — nine positions, the E-M10's dial. Names from the build's `modedial_to_code` table at `0x7dd17064`
  (NON 0, P 1, …). No closing 0 after the table (next words belong to another table); lab reads as many words as
  the dial has positions.
- Fix: second reader form in `facts.mjs modeDial`; E113 fact regenerated, E120 hand-edited (table `0x601a8fd8`,
  same codes). Lab dial now starts at position 1 (P).
- Verified walk `r3`: wizard to 3/3, End Wi-Fi → screen stays **P**, nothing greyed; s1/s2 stores picture,
  playback shows it; Picture Mode list opens. rc 0.
- Regression: test:pure all passed (278/278, 256/256); modelcheck E113 tft+evf ok; E120 --no-port tft+evf ok.
- Still open (issue A): Playback Menu Edit / Print Order / Reset Protect greyed with a picture on the card (`r3`
  tiles 20+ did not revisit that tab; `p2` did).

### Walks `r4`, `r5` — date entry, language, playback menu with pictures (om-emu mode-dial commit, rc 0, sheets viewed)
- Setup → date/time: field editor works (Y 2014, M 01, D up/down wraps 01 → 31 → 30 → 29, hour, minute, format
  field); MENU cancels (`r4`); OK on the last field stores — Setup row shows `'14.01.01 00:00` and the shooting
  screen's "set the date" banner (Y/M/D) is gone (`r5`).
- Language list (two pages) → 日本語: every menu, footer and the SCP banner switch to Japanese (`r4`).
- Two shots in one session: playback shows frame 2 (`002-0000`, counter 2).
- **Issue A still open:** Playback Menu Edit / Print Order / Reset Protect greyed with 1 picture on the card, also
  when the menu is opened from playback (`r5`). Cursor skips them (down from rotate → Connection to Smartphone).
  Playback frame is black with file number `001-0000` (a camera names `100-0001`) — suspicion: the lab-stored
  picture is not a DCF object the edit predicates accept. Unverified.
- Issue A, cause by code read (om-emu `src/lab/camapp.mjs dcf_db()`, `storage.mjs write_picture`): the lab registers
  a picture in the firmware's own DCF database only on bodies with the whole-file recorder fact (`recorder`: E143,
  E146, E147, E144, E148) or native REC_FILE. E113 (`recorder: null`, like E120 and 11 others) takes the
  `fileif_picture` path: file written, playback list is S-CARD's stand-in, DCF database stays empty. That the three
  greyed rows key on the DCF count is inference (not probed). Upstream lab limit shared by 13 bodies, not an E113
  port fault; lifting it = porting a recorder rule for the E118 family. Not done.

### Walks `r6`–`r9` — Myset, Wi-Fi Settings sub-pages (all rc 0, sheets viewed)
- `r6`/`r7` Reset/Myset: Myset1 popup Set / Reset with help text; **Set stores** (row "No Data" → "Set").
  RC Mode Off/On inline popup. Flash strip, drive strip, SCP cursor fine.
- `r8` Wi-Fi Connect Settings (RIGHT opens): Private → One-Time with help text, stored in the row.
- `r9` Private Password (RIGHT): help text + `Password : 72521432`, "Confirming OK" returns. Reset Wi-Fi Settings
  (RIGHT): orange caution page, Yes/No; **Yes + OK** returns to the list, no fault.
- Note for walkers: on these pages OK = "Set" (leave page), RIGHT opens the row. Several earlier sequences drifted
  on that.

### Walk `r10` — EVF (800x600), 32 keys, rc 0, EV [] LOG []
Sheet built with an 800x600 variant of the sheet script (`sheet3.py`; `sheet2.py` only takes 720x480 frames).
- Tab column: Shooting 1 → Shooting 2 → Playback → Custom → (up) Setup, all drawn on the EVF.
- Custom Menu A–G list; D. Disp/PC page; Video Out NTSC → **PAL stored**; F. Flash Custom page (X-Sync 1/250, Slow Limit
  1/60).
- Setup Menu → Firmware page: `Body : 1.4`.
- MENU exits to the EVF shooting screen (P, 4000, exposure bar, 2099 shots).
- Oddity, not chased: after the second menu exit the EVF shooting screen shows an extra "ISO-A LOW" mark that was
  not there after the first exit.
- Playback Menu greying (issue A) is the same on the EVF.

### Issue A experiment `q1` — DCF re-list after a shot: DEAD END
Experiment (not kept, `storage.mjs` reverted): after `fileif_picture` closes the file, send the DCF process command 3
("dcf lists", the one the insertion sends) again and read the count table (fact `dcf.counts`) before and after.
Result: `DCFX counts 0,0,0,0,0 -> 0,0,0,0,0`, rc 0, Playback Menu rows Edit / Print Order / Reset Protect still grey.
So a bare "dcf lists" does not make the database learn of the new file. Not tried: card-in (command 1) again, a full
remove + insert (S-MEDIA) after the shot, or whether the greying reads this count table at all.
Caution: the `q1` sheet mixes frames of an older run with the same snap prefix; only the counts line is evidence.

### Issue A solved — `q2` remount after each fileif picture (om-emu `dcf_remount`)
Experiment `q2`: after `fileif_picture` closes the file, send the removal's and the insertion's requests again
(`storage.remove` + `storage.insert`). Count table (fact `dcf.counts`) `0,0,0,0,0` before, `1,0,1,0,0` after; the
Playback Menu rows Edit / Print Order / Reset Protect are **enabled**. Confirms both inferences: the greying keys on
the firmware DCF count, and the DCF database learns of a fileif file only at a mount.
Kept as a model flag: `models.mjs` E113 `dcf_remount: true`, acted on at the end of `storage.mjs fileif_picture`.
`npm run test:pure` 278/278 with the change.

### `q3` — `rec_native: true` on E113: DEAD END
Tried making the picture go through the native REC_FILE path (as E120 does). Session stops at
`E113: neither src/fw/ports.mjs FACTS nor src/fw/models.mjs gives rec_file` (`rec.mjs recFacts`). E113 has no
`rec_file` fact and no whole-file recorder; porting that rule is a separate job. Reverted. (Correction of an earlier
note: E120 does NOT share issue A, it has `rec_native: true`.)

### Walks `q4`–`q6` — Playback Menu pages with a picture on the card
- `q4` (two shots): Edit page opens (Sel. Image; Image Overlay grey), Print Order page (single / ALL), Reset Protect
  Yes/No page. Reset Protect → Yes → OK stopped at
  `S-MDAEND: the media control's command 5 (media 0): only the format's (1) end is stood in`.
- `q5`: Print Order → ALL → OK opens the date page (No / Date / Time); MENU backs out cleanly, PLAY shows frame
  `001-0000`, `2014.01.01 00:00`.
- `q6` (experiment, command 5 let through, its end ignored): the end arrives from a site the lab lists as "another
  command" (return address 0x7dfcfff4 runtime, 0x5063efd4 in the disassembly), event ids 59,46. With the end
  dropped the screen stays on **Busy** forever (every later key shows Busy) — so the end's closure must run, as for
  format, but without format's bookkeeping (renumber, format count, `card_formatted`).

### Reset Protect → Yes solved — `q7` dead end, `q8` fix (om-emu S-MDAEND, command 5)
- `q7` (experiment): treated the command-5 end like format's — read event +28 as a closure object and ran it through
  its vtable. Fault `GDB mfef820cf,4: E14`. DEAD END: the event is a different class.
- Disassembly (E113 0x50… addresses): the "other" site at 0x5063efcd posts an event built by ctor 0x5063278f:
  id 11835, `(28,ev)` = plain function pointer (0x5063ec68), `(32,ev)` = its argument (an event object from
  0x50633487). The event's destructor 0x506327b1 does `calls fn` with `a0 = (32,ev)` when the pointer is non-zero —
  a run-on-delete callback. On the body the camera base (Cab_CameraBase, HLS component) takes and deletes the event;
  in the lab that component does not run, so nothing ever calls it → Busy forever (`q6`).
- Fix: `facts.mjs MEDIA_LIB.cab` gains `unprotect: 5, plain: { fn: 28, arg: 32 }`; `media.mjs` accepts start command
  5, records the other-site end as a plain end and in `_end` calls `fn(arg)` itself (and zeroes the event's pointer so
  a later destructor does not repeat it). Format's bookkeeping (renumber, format count, `card_formatted`) is not run
  for it. Any other command still throws.
- `q8` walk, rc 0, EV [] LOG []: Reset Protect → Yes → OK returns to the Playback Menu list, cursor moves, MENU
  exits, PLAY shows `001-0000`, MENU opens again.
- Limits: verified on E113 only; the offsets 28/32 and the command number 5 are E113 observations placed in the
  shared library fact, other bodies of the family not checked.

### Walks `c1`, `c2` — Custom Menu A, B, C, E, G, H, I (rc 0, EV [] LOG [], sheets viewed)
- A. AF/MF: page of 7 rows; Full-time AF Off → **On stored**.
- B. Button/Dial: Button Function page (Fn1 AEL/AFL, Fn2 Multi Function, REC, arrow pad, L-Fn AF Stop); Mode Dial
  Function greyed.
- C. Release: Rls Priority S Off → **On stored**; L fps 3.5, H fps 8.
- E. Exp/ISO: EV Step 1/3EV → **1/2EV stored**.
- G. Color/WB: Set page (image-quality slots 1–4, Pixel Count Large → Middle on the cursor slot).
- H. Record/Erase: Quick Erase Off → **On stored**.
- I. Movie: Mode P → **A stored**.
- Also seen in `c1`: outside the menu DOWN opens the drive strip (Single / Sequential H / L / 12 sec / 2 sec), OK
  commits (SCP shows the 2s self-timer).
With D, F (r10), J, K (earlier walks) every Custom Menu letter A–K has now been opened and a value changed where
the row has one.

### EVF "ISO-A LOW" oddity chased — walks `v1`, `v2`: firmware blink, not a fault
- `v2` (EVF, keys `s1,s1,s1,menu,menu,s1×5`, rc 0): with nothing but repeated S1 the `ISO-A LOW` mark is absent,
  present, absent, …, i.e. it depends on time, not on the key history → the firmware blinks the auto-ISO value
  (reading: exposure warning; no lens, aperture `-.-`, lab AE value below the ISO range, hence "LOW". The reason
  for the blink is inference; that it is time-dependent is observed).
- Same walks: right after a menu exit the EVF shows the full icon set (flash, metering, S-AF, WB, drive, aspect,
  flash comp) for two snapshots, then falls back to the reduced set. Timed display, same family of behaviour.
- So the earlier "extra mark after the second exit" was a snapshot landing on the other blink phase.

### Walk `e1` — Edit → Sel. Image, Myset Reset (28 keys, rc 0, EV [] LOG [])
- Playback Menu → Edit → Sel. Image: image selector opens (frame `001-0000`, `2014.01.01 00:00`, left/right arrows);
  OK → popup `JPEG`: RAW Data Edit (grey, the lab picture is a JPEG), **JPEG Edit**, voice-memo mark. MENU backs out
  step by step (selector → Edit page → Playback Menu → shooting screen).
- Reset/Myset → Myset1 → popup Set / Reset with help text; Reset on an empty slot leaves "No Data".
- Not walked: JPEG Edit's own pages (Shadow Adj, Redeye Fix, crop, …) — the lab's picture is a stand-in file with a
  black frame, so image-processing results there would say nothing about the port.

### State of the session goal after these walks (honest)
- Menu: every tab (Shooting 1/2, Playback, Custom A–K, Setup) opened on TFT, tabs + D/F/Setup on EVF; values change and
  store; Format, Reset, Reset Protect, Wi-Fi wizard, date entry, Myset set all complete. No known menu row that
  faults. Not exhaustively walked: every single row's popup, JPEG Edit, Print Order confirm, Image Overlay (grey).
- Tests: `npm run test:pure` 278/278; `modelcheck E113` ok tft+evf; `modelcheck E120 --no-port` ok tft+evf.
  `checks/*` are E143-only by construction and do not run for E113. `modelcheck E143` fails on the parent commit
  too (pre-existing). Full `bin/test.mjs` was OOM-reaped on this 3.4 GB machine and was not rerun.

## Session 2026-11-18d — per-body tests on E113, remaining menu walks

### Per-body tests on E113 (om-emu `2edea9d`)
`bin/test.mjs` lists bodies per test; E113 was in none of the per-body lists. Added and run one at a time
(`OM_EMU_MODEL=E113 node test/…`):

| test | result | what it needed |
|---|---|---|
| `test_storage.mjs --session` | 43/43 | E113 in `MOVIE_ZERO` (see below) |
| `test_storage.mjs --hls` | 21/21 | nothing |
| `test_playinfo.mjs` | 21/21 | nothing |
| `test_erase.mjs` | 3/3 | E113 in `ERASE_DIALOG` |
| `test_touch.mjs` | 11/11 | touch read facts, `post_preempts`, `START_PANEL` |

- **Touch read length (fact was wrong).** Controller slave 0x48, read fn `0x5022ba06`. The read length is the
  32-bit word at callee `sp+0x24` (caller's `(4,sp)`), the register address is two bytes `[d1, a1]`; the
  `mov 4,d0` in the tail counts bytes *sent*. Old fact `{ const: 4 }`/`["d1"]` → reads truncated to 4 bytes, HFW
  saw 1320,3840 for a tap at 40,333, test 6/11. `facts.mjs` `touchLength` now returns `{ sp: N }` when the read
  helper has exactly one 32-bit stack load above its frame; `touchAddr` returns `['d1','a1']` when the third
  instruction is `mov a1,a2`. `e113-touch-read-const.md` carries a correction note.
- **`post_preempts` (models.mjs, E113).** E113's kernel has 16 priority levels, lab task = nav task = 14, the GUI
  task (1..13) preempts the lab during `sendEvent`. `nav.mjs` threw "GUI ran during post"; the guard is now
  skipped on `post_preempts` bodies and the post stub budget is the settle budget (140 was exceeded:
  "more than 140 stub calls").
- **`START_PANEL` (test_touch).** The E113 lab starts on the Super Control Panel, there is no touch-mode icon at
  the tapped pixel, so the tap must leave the frame unchanged; the test checks that instead.
- **Erase.** Delete opens the erase dialog; Yes sends eraseCurrent (play request 21), which the lab refuses: E113
  has no native erase fact (not in `ERASE_NATIVE`). The dialog is tested, the erase itself is **not emulated**.
  Dead end: putting E113 in `ERASE_YES_UNTIMED` (claims Yes sends nothing) → playinfo 20/21, reverted.
- **Movie remaining time.** `0x504cd2c6` is a record getter of the movie module (`0x504e20fd` copies 3 words + a
  halfword), all zero with no movie recorded → `MOVIE_ZERO`. Where the screen's `00:43:15` comes from is not read.
- Regression after these changes: `test:pure` 278/278; `modelcheck E113` ok tft+evf; `modelcheck E120 --no-port`
  ok tft+evf; `test_touch` E120 27/27; E143 `checks/check_kernel.mjs` all groups passed; E143
  `checks/check_menu.mjs --quick` all groups passed (0 problems).
- `modelcheck E143`: `bin/modelcheck.mjs` excludes E143 by design (filter `k !== 'E143'`; its own comment calls the
  PLANC 0xb2 / highlight-block registers unobserved "on E143 too"). Forced with `--no-port` it reports
  FEATURE_UNOBSERVED ch2 0x48c / PLANC_UNOBSERVED ch1 0x62c. Not an upstream test, not an E113 regression.

### Walks `f1`–`f5`, `g5` (TFT, rc 0, EV [] LOG [])
- `f1` JPEG Edit: popup `JPEG` → **JPEG Edit** → list Shadow Adj / Redeye Fix / crop / Aspect / Black & White /
  Sepia (scroll bar; more below) → Redeye Fix → Yes/No. **Yes stops**: `S-PLAYSEQ: play request 164
  (EditRedEyeAdjust::save) saves an edit's file; the lab runs no edit` (om-emu model-survey finding 141). That
  refusal is lab-wide (no body runs an edit), not an E113 gap; the menu path up to the commit works.
- `f2` Print Order: page (single / ALL) → ALL → No / Date / Time → Set / Cancel → **Set** returns to the Playback
  Menu; Print Order opens again afterwards; MENU exits.
- `f3`/`f4` Myset: Myset1 → Set → row shows **Set**; OK on a stored Myset → Yes/No → **Yes** returns to
  Shooting Menu 1; MENU exits to the SCP.
- `f5` Myset recall with a changed value: Myset1 Set (aspect 4:3), Image Aspect → 16:9 (popup shows pixel counts
  4608x2592 …, stored, SCP shows 16:9), Myset1 recall Yes → Image Aspect **still 16:9**.
  `g5`: identical key list on **E120** → identical result (still 16:9). So this is not an E113 port fault; whether
  the firmware excludes Image Aspect from Myset or the lab's settings store drops the recall is **not determined**
  (the lab has no Myset code at all; no refusal is logged). Open.

### State of the session goal (honest, end of 2026-11-18d)
- Menu: all tabs and Custom A–K walked, plus JPEG Edit list, Print Order to its final Set, Myset set + recall.
  No menu row found that faults on E113. Limits: edit save (lab-wide refusal), erase (not emulated on E113), Myset
  recall effect unproven (same on E120), Image Overlay / RAW Data Edit grey (JPEG stand-in picture), not every
  row's popup opened individually.
- Tests: everything that can run for E113 runs and passes (table above + pure + modelcheck). `checks/*` pin E143
  and cannot run for E113. Full `bin/test.mjs` (all ~19 bodies; only E113/E120/E143 firmware is on disk, and it
  was memory-reaped on this 3.4 GB machine) was **not** rerun.

## Session 2026-11-18e — Myset recall fixed (S-MYSETLOAD)

Supersedes the earlier "Myset recall does not restore Image Aspect, cause not determined" note.

**Symptom.** Reset/Myset → Myset1 → Yes returned to the menu without fault but restored nothing
(Image Aspect, Digital Tele-converter stayed at their changed values). Same on E120.

**Narrowing walks.**
- f6: Reset (Basic) → Yes restores 16:9 → 4:3, so settings restore as such works.
- f7: Digital Tele-converter Off → On is also not restored by recall → recall had no effect at all.
- f8: 16 MB RAM-dump diff before/after recall — DEAD END (≈10k differing bytes of heap/frame churn).
- f9: observers at the recall key: none of 0x508626f6 / 0x501d704a / 0x501dbeba / 0x501db306 hit.
  0x501d6eb4 hit only at Set (d0 = 0x35). → the request is lost before the firmware load.
- f10: per-key stand-in hit deltas: recall key has 5 × S-TRAACK, no dropped sends.
- f11: dump of those transactions. Recall (Yes) sends TRA 12971 (0x32ab) with +32 = 0x35 (53), +36 = 0,
  then 0x4050, 0x4023 (arg 0x39), 0x4074, 0x32a8. (Set sends 0x4023 arg 0x258, 0x4074, 0x32a8.)

**Firmware chain (E113, disasm 0x50… notation).**
- TRA 12971 builder 0x50637871 (d0 → +32, d1 → +36); callers 0x506c2976, 0x507902b9, 0x50792aa1.
- Component method 0x5071c1c0 (a0 = component, d0 = data id, d1 = kind): kind 0 → settings device
  (component +284) vtable slot +0x30 = 0x508626f6, which calls the settings API load 0x501d704a(id) and logs
  `loadCustom:MyMode`. Kind 1 → slot +0x38 (gated by word 0x6044ff68). Sibling slots 0x50862714 / 0x50862732
  call 0x501d6fb6 (shortcut restores).
- 0x501d704a: gate 0x50203a68 (byte 0x606fcc10 == 1 skips), 0x501d6eb4(id, 1, &out) looks the id up in the
  6-byte-entry table at 0x6044ab54, then loader 0x501dbeba.
- Data id 53 = Myset1: the same id Set saved under.
- 0x508626f6 is entered by `calls` (own `add -8,sp` / `ret [],8`), NOT `call` — calling it with a `call`
  frame half-works then returns to a wild pc (0xb62ea4e0) and corrupts the OSD (BMP_LUT error). Dead end noted.

**Cause.** The lab's default mode acks every HLS transaction at the exec (S-TRAACK) without running the
component, so the load never ran. Lab-wide design, not E113-specific.

**Fix (om-emu a8aceae, branch e113-port).** Fact `myset_load` (src/fw/facts.mjs `mysetLoad`):
derived from the image — the one function of the load's form that logs `loadCustom:MyMode`, plus the
12971 builder; E113 load 0x508626f6, E120 load 0x508d7b64. Stand-in S-MYSETLOAD (src/lab/camapp.mjs): on TRA
12971 with kind 0, calls the load after the step with the request's data id; other kinds are refused. Not
active under camapp 3 (components native).

**Verification.** Walk f13 (stock walk script): Set Myset1 (tele-converter Off), set tele-converter On,
recall Yes, leave menu, reopen → Digital Tele-converter reads Off; shooting screen loses the tele-converter
icon. The menu list drawn immediately after Yes still shows the old value until the menu is reopened
(not checked against a real camera).
Regression: test:pure all passed (facts 278/278), modelcheck E113 tft+evf ok, modelcheck E120 --no-port
tft+evf ok, E143 check_kernel all groups passed, check_menu --quick 0 problems.

**Still open / honest state.** Full `bin/test.mjs` not rerun (memory limit); `checks/*` are E143-only;
edit save and erase remain lab-refused; not every row popup opened; Myset recall verified on E113 only
(E120 gets the same stand-in, modelcheck passes, no recall walk rerun there).

## Session 2026-11-18f — native erase, menu sweeps, Recording Volume popup (S-MICMON)

### Native erase on E113 (om-emu `eb2a7c5`)

The 2014/2015 builds trace `api_picfile_*` functions by name, not by id, so the lab's `pictureErase`
fact found nothing on E113/E120 and playback's Erase → Yes was refused by the lab. New fact
`erase_named` places the object delete and the attribute getter from their traced name strings; model
flag `erase_by_name` (E113 only) turns the native erase on. The DCF database knows the pictures through
`dcf_remount`. `test_erase` 31/31 and `test_playinfo` 21/21 on E113. E120 unchanged (no flag).
Side fixes: placed-fact key order (`erase_named` must follow the facts it reads), `FILEIF_NUMBERS`.

### Row sweeps (tol-sweep driver: open every row of a page, change, commit, back)

Swept without ERR/DRIFT: Shooting Menu 1 and 2, Playback Menu (with and without pictures), Setup Menu,
Custom A, Custom B, and Custom I rows Mode / Movie mic / Movie Effect / Wind Noise Reduction.
Inside the Custom Menu DOWN crosses page borders (A's last row → B's first), so a page-local sweep
needs per-page key prefixes; the driver's 14-row no-wrap limit is too low for a whole-Custom sweep.
Custom C–K rows were walked earlier (c1, c2 walks) but **not swept row-by-row** in this session.

### udf06 with ±Inf (CPU, inference)

A sweep trapped in the FPU-compare extension (udf06) on a −Inf operand: the emulator treated every
non-normal as unordered. Changed: ±Inf compare in order (`ordered(v)` in `core/cpu.mjs`: normal, zero,
or exponent all-ones with zero mantissa); NaN stays unordered. Marked (I) in om-emu
`docs/om1-udf-spec.md` row 11 — no camera evidence, IEEE semantics assumed.

### Custom I → Recording Volume: event storm, two GUI event locks, S-MICMON

**Symptom.** Opening Recording Volume: `event storm: more than 810 GESetEvent calls` — key-release
event 0x900b requeued forever.

**Lock 1 (awaited-event word).** Object 0x6084999c (getter 0x50945d80), word +4 = awaited event id
(set 0x50945d9c, get 0x50945da2, clear 0x50945da8). The generic GUI event fn 0x50991053 requeues
every other event while the word is nonzero (post at 0x509910bb). The popup sets it to 0x9c01 at
0x50945347 after `singleton 0x506a2d02 (obj *0x6044eb40) vt+48 = 0x506a32f3 (d0=22, a1=callback
0x509a9a3f)` returns 0.

**What 0x9c01 is.** 0x506a32f3 → 0x506be321 registers the callback (0x506330f9, d0=0x60, d1=22) and
executes HLS transaction **13530** (ctor 0x506384d9; 13531 ctor 0x50638504) = microphone level monitor
start / stop. The callback 0x509a9a3f maps module notifications to GUI events through a table at
runtime 0x7e5e0344 (23 × 12-byte {module id, subtable, count}; subentries 20 bytes, event u16 @+16);
module 22: `{0x60, 0x12e (302), type 2, 0x9c01}`, `{0x60, 0x12f (303), type 2, 0x9c01}`.
Mic-monitor component: TRA 13530 handler 0x5079091f → 0x506c6ed1; 13531 handler 0x5079082e →
0x506c6ce5; notifier "started" (302) **0x506c7370**, "stopped/failed" (303) **0x506c7356**
(both entered `call fn,[],8`). E120: started 0x506ff68f, stopped 0x506ff675.

**Cause.** S-TRAACK acknowledges 13530 without running the component (audio driver absent), so
notification 302 never comes, 0x9c01 is never posted, lock 1 never clears.

**Fix: S-MICMON** (om-emu commit after `eb2a7c5`; `camapp.mjs`, fact `mic_monitor` in `facts.mjs`/`ports.mjs`). At TRA
13530/13531 the lab queues the component's own notifier (started / stopped) and calls it natively from
the lab task. The fact finder locates the notifiers by code pattern and requires both transaction ids
present; E143 gets no fact (stand-in inert there).

**Lock 2 and the timing (second storm, on OK).** Global 0x6045e348 = second awaited event (setter
0x5093c6c0, clearing variants 0x5093c0ac/0f3/134/174, 0x5093c6ca). In the fn around 0x5093c59d, while
it is set: key events are dropped, all others reposted (0x5093c66c). The popup's OK handler
(0x509458bb, event 0x900c) sets lock 2 = **0x9ac6** and starts a **100 ms timer**
(`0x509a69e0(d0=522, d1=100)`) whose expiry posts 0x9ac6; it also stops the monitor (TRA 13531).
With the notification delivered immediately after the step, its 0x9c01 sat in the queue and was
reposted without end, because lab time stands still inside a step and the 100 ms timer cannot fire.
**Dead end:** delivering the notification directly after the step (storm on OK, tally 807 reposts
from 0x5093c691, WAIT2 = 0x9ac6). **Fix:** the notification is sent `MIC_MONITOR_NS` = 120 ms after
the transaction, from the idle tick (INFERENCE: the real driver's latency is unknown; chosen longer
than the popup's 100 ms hold and shorter than a key press).

**Verification (walk `mv`, TFT, 31 keys, NOERR, sheet viewed).** Open popup → slider 0, L/R dB meter
drawn; UP, UP → +1, +2; OK → list shows Recording Volume +2; reopen → +2; DOWN×3 → −1; MENU → cancelled,
list still +2; reopen/MENU; MENU, MENU → Custom Menu → shooting screen. The level meter stays empty
(no audio samples in the lab).
**Not done:** E120 retest of the same popup with S-MICMON (before the stand-in E120 died there with
`GDB m435f434e,1: E14` after GUI log `part_menu_2nd_mic_level_update`).

**Regression after S-MICMON + udf06 change:** test:pure all passed (facts 278/278), E143 check_isa
(125/125 UDF functions) and check_kernel all groups passed, modelcheck E113 tft+evf ok, modelcheck E120
`--no-port` tft+evf ok.

### Whole Custom Menu sweep `cw` — every row A–K opened and cancelled (om-emu `17859c3`)

Sweep driver with the row limit raised (`OM_ROWS=130`) and started on Custom A row 1: for each row
RIGHT (open), frame hash must change, MENU (cancel), frame hash must return to the list's; DOWN; ends
when the frame equals the first one again (DOWN crosses pages, K's last row wraps to A's first).

Result: **92 rows, 92 opened, 0 ERR, 0 DRIFT, 0 NOOP, wrap reached, EV [] LOG []**. All 92 "open"
frames viewed on contact sheets: A (AF Mode … AF Area Pointer, 11), B (Button Function, Dial Function,
Dial Direction, Mode Dial Function), C (Rls Priority … Release Lag-Time), D (HDMI … Multi Function
Settings incl. Picture Mode Settings, Histogram Settings, Peaking, USB Mode), E (EV Step … Anti-Shock,
ISO-Auto Set, Live BULB/TIME, Composite Settings), F (X-Sync, Slow Limit, exposure-comp+flash),
G (quality Set, Pixel Count, Shading Comp., WB, All WB, Keep Warm Color, flash+WB, Color Space),
H (Quick Erase … Copyright Settings, dpi Settings), I (Mode, Movie mic, Movie Effect, Wind Noise,
Recording Volume, Movie+Photo Mode), J (EVF Style, Info Settings, Displayed Grid, EVF Auto Switch,
EVF Adjust, Half Way Level), K (Exposure Shift, Warning Level, Level Adjust, Touch Screen Settings,
Electronic Zoom Speed).

Two rows are greyed and skipped by the firmware's cursor, so not opened: **K Eye-Fi** (no Eye-Fi card
in the lab — expected) and **K Pixel Mapping** (greyed by the firmware; cause not chased — OPEN).
The sweep opens and cancels; it does not commit a new value in every popup (commit verified by the
earlier walks and sweeps for a sample of rows per popup kind).

### Full `bin/test.mjs` cannot complete on this machine — fact

`bin/test.mjs` spawns per-body tests for about twenty bodies (E146, E147, E144, E142, E134, …). Only
E113, E120 and E143 firmware is unpacked here (`unpacked/`), so the full run cannot pass here
independent of memory. What can be run for E113 is every E113 entry of it, one process at a time:
`test_storage --session`, `test_storage --hls`, `test_playinfo`, `test_erase`, `test_touch`, plus
`test:pure`, `bin/modelcheck.mjs E113`.

### E113 per-body tests rerun on om-emu `17859c3` (serial, one process at a time)

`test_storage --session` 116/116 units + 43/43 in play; `test_storage --hls` rc 0; `test_playinfo`
21/21; `test_erase` 31/31; `test_touch` 11/11. All rc 0.

### E120 Recording Volume popup — still failing (E120-only, OPEN)

Retested with S-MICMON: opening the popup on E120 (Custom I, row 3) still ends
`GDB m435f434e,1: E14` right after GUI log `info->part_menu_2nd_mic_level_update settingValue[%d]`,
inside the opening step, before any notification is due (TRA 13530 issued twice). 0x435f434e is ASCII
("NC_C") read as a pointer — an uninitialised or mis-typed record in the E120 popup's level-update path.
Not an E-M10 Mark I matter; not chased.

### K → Pixel Mapping greyed — chased, cause NOT found (dead end recorded)

Facts found:
- sdk-api console table (file 0x9f97xx, 12-byte {tag, name, fn}): `G01 execute` fn 0x50671452,
  `G02 canPixelMapping` fn 0x50671504; long names `PixelMapping::execute` / `::canPixelMapping`.
- PixelMapping singleton getter 0x50697cc4 (object *0x6044eae8); vt+0 execute, vt+4 canPixelMapping
  (method 0x50697c22 → impl 0x506b319e).
- canPixelMapping = synchronous HLS transaction **13526** (ctor 0x50638425; 13525 ctor 0x506383fb,
  13527 ctor ~0x50638464). Component handler at 0x50793661 answers constant 1.
- Only GUI callers of the singleton: 0x50992c35 (canPixelMapping == 1 → 0x50992c4a execute) — the
  function-dispatch (execution) path, not the menu's enable state.

**Dead end:** answering transaction 13526 with 1 in the lab (experiment, reverted): the row stays grey
and no 13526 is issued while the K page is drawn — the greying is decided elsewhere (menu enable
condition not located). Also not time-based: still grey minutes into the `cw` sweep. Whether a real
E-M10 greys Pixel Mapping in this state (no shot taken, lab lens) is not known; no camera check done.

### K → Pixel Mapping greying SOLVED, Start runs (om-emu 79f2bb8)

Supersedes the "cause NOT found" section above.

**Row greying mechanism (all menu rows, E113, disasm addrs; runtime = +0x2d991020):**
page draw loop 0x509420a5 → singleton 0x506673cb vt+0 (impl 0x50667432, arg = item id) → 0x506c2338 →
restriction evaluator **0x506c3c18**. It looks the item up (lookup fn 0x506c3cd0) in a table at runtime
**0x7e3b4ce0**: 495 records × 12 B (u16 item id @0, pointer to condition-index bytes @4, u8 count @8), then
calls each condition from the fn-pointer table at runtime **0x7e3b3cd4** (u32 each). Any nonzero answer =
restricted = grey row. Context kept in 0x6075d41c..24.

| item | id | conditions |
|---|---|---|
| Pixel Mapping | 0x800 | 1, 2, 47, 95, 118 |
| Eye-Fi | 0x193 | 47, 48, 56, 95, 119 (grey is legit: no Eye-Fi card) |
| Exposure Shift | 0x111 | 2, 15, 16, 17, 18, 23, 27, 28, 42, 95 |
| (0x1aa) | 0x1aa | 2, 95 |

Conditions read: 1 = 0x506c3dac (DB item 1008 == 2); 2 = 0x506c3dc7 (store query 0x501d6df2);
47 = 0x506c4344; 95 = 0x506c4c04 (0x506c5550() & 16); **118 = 0x506c50d9: `api_rec_Get_LensState`
(0x5020bf49) == 0 → restricted**. Its getter 0x501129c5 = `movhu (0x606bad40),d0; extbu; retf`; the word is
written by the lens mount handler: 1 on messages 1/4 (0x5010eaa8), 0 on 2/3 (0x5010eb08).

**Cause:** the lab's S-LENS mounts a lens through the driver record only; the mount handler never ran, the
word stayed 0 = "no lens" → Pixel Mapping greyed (a real body without a lens greys it the same way).
**Fix:** fact `lens.attach_state` (rule `lensAttachState`: the named API's `mov 1,d0; mov name,a0; call;
call G` and G's word), S-LENS writes it 1. E120's Get_LensState has another shape (maps 1→0x20, 0→0x21),
E143 has no such name → fact absent there, nothing written.

**Start (execution path):** OK on Start → 0x50992c35 canPixelMapping (sync TRA 13526, answered 1) →
0x50992c4a execute → component 0x506ba41a → `api_rec_Save_Pixelmaping` 0x5020d8fe → parameter save
0x50042bd7: BKRAM store save 0x50040a41 via descriptor *(0x609d9f08), EEROM store save 0x50040a7c via
*(0x609d9f0c) (error traces DBG_PRM_PROC_BKRAM_SAVE_ERR / _EEROM_SAVE_ERR). A descriptor holds its two flash
sectors at +4/+8 (the save alternates, 0x50040970) and the size at +20 (0x50040844); checksum 0x50040bf0
(16-bit two's-complement sum). The EEROM save issues the file system's NAND access 0x5003afaa: 0x10000
bytes, op 0 (write), flash 0xbffea000. The lab's S-FSNAND refused it (only the FS area is modelled).
**Fix:** fact `param_store` (rule `paramStore`: the two descriptor pointer words, found by the save's
error-trace form) + **S-PRMNAND** in storage.mjs: a descriptor's sector reads erased until written, writes
kept for the session. `api_rec_Calculate_Pixelmaping` (~0x5020d8b0, request 25) not looked at.

**Verified:** frames — row white, Start popup opens, OK returns to the K page with no lab error, second
OK reopens Start, MENU ×2 back to Custom Menu. test:pure all passed (facts 278/278); modelcheck E113
tft+evf ok; modelcheck E120 --no-port tft+evf ok. NOT verified: that the mapping itself does anything (no
sensor model), and no busy bar was seen between OK and the page (frames are per key only).

**Dead ends / traps:**
- `fact(path, null)` THROWS on a ported body that lacks the fact (broke E120 storage bring-up) → new
  `factIf(path)` in model.mjs for facts only some bodies have.
- First lensAttachState rule scanned every 0x80 byte in the image; replaced by string-load anchored form.
- Leads that were NOT the greying: Eye-Fi API 0x5020410d, SettingController console impls, isEnabled vt+16,
  0x509aad72 (Wi-Fi wizard only), TRA 13526 (execution gate only).
- S-LENS on E113 still writes LENS_ATTACHED / LENS_VALID / the lens static default at their E143 addresses
  (absent in PORTS.E113 → identity). Pre-existing, harmless so far, unfixed.

**Still open vs the session goal:** `checks/*` are E143-only; full `bin/test.mjs` needs ~20 bodies' firmware
(only E113/E120/E143 unpacked) and was memory-reaped; JPEG Edit save = lab-wide refusal; row sweeps were
open+cancel, not every value committed; E120 Recording Volume popup E14 and E120 Myset recall unverified.

## Session 2026-11-18g — all standalone tests, regressions fixed, commit sweep, Level Adjust (S-ACCRING)

### Every test file run standalone, one process at a time (om-emu `17859c3` → `1a82faf`)

`bin/test.mjs` in one go is memory-reaped on this machine, so each `test/*.mjs` was run as its own process
(scratch driver, serial). Golden body E143: 76 runs, 72 rc 0. The four others:

| test | result | cause |
|---|---|---|
| test_host (pl8b "200 touch moves: 0 samples") | FAIL → **fixed** | regression from this branch, see below |
| test_nvram | rc 1 first run, rc 0 on rerun (8/8 + 34/34) | first run died without a test line; not reproduced |
| test_take_ready | FAIL | needs the E143 sub-CPU block (blk0e560000), not in the unpack here |
| test_unpack | SKIPPED rc 3 | needs OM_FW_DIR (original containers) |

Per-body runs (rc 0 unless said): E113 storage --session 43/43, --hls, playinfo 21/21, erase 31/31, touch
11/11. E120 storage 70/70, --hls, playinfo 21/21, erase 3/3, touch 27/27. E144 storage 53/53, playinfo
20/20, erase 31/31, touch 27/27. E146 c3take 7/7, scpu 105/105+20/20, hdmi, storage 66/66, slot2 22/22,
nvram, lens, scpum4, playinfo 20/20, erase 31/31; **E146 test_touch rc 1 — also fails on the parent of the
branch (pre-existing, not chased).**

After the fixes (om-emu `1a82faf`): test_touch E143 7/7, E113 11/11, E120 27/27, E144 27/27; test_camapp
E143 all groups; test_regress 5/5; test_lab 23/23+3/3; test_host 11/11; modelcheck E113 ok, E120/E144/E146
`--no-port` ok; test:pure all passed.

### Two regressions this branch had brought to other bodies (om-emu `1a82faf`)

1. **Optional facts read with `fact()`** — strobe / myset_load / mic_monitor / wifi_manager / body_version
   throw on a ported body that lacks them (E146 start crashed). All read through `factIf()` now.
2. **E143 touch read length** — bisected to `0b8febc`: the E113 touch correction had turned E143's read
   length into a fixed count (`touchReadConst`). E143 takes it from register a1. Restored
   (`TOUCH_ARGS_E143 = { write_len: {sp: 20}, read_len: {reg: 'a1'}, read_addr: ['d1'] }`), const rule removed.

### JPEG Edit save — design boundary, not an E113 gap

The lab refuses the edit's save on the golden E143 as well (no image pipeline for the re-encode). Left.

### Commit sweep of the whole Custom Menu (driver `commit-sweep.mjs`)

For each row: RIGHT (open), DOWN (change the value), OK (commit), then MENU until the frame is back near
the page. Rows 0–89: all commit and return, 67 with a visible value change on the page, 9 need one or two
MENU presses (sub-pages). **Row 90, K → Level Adjust → Adjust → OK: menu lost** — a real fault, below.

### K → Level Adjust froze the menu: accelerometer sample ring (S-ACCRING)

Chain (disasm addresses): GUI OK → LevelCtrl::calibration → OlyHfwOpeLevel::calibration 0x506ad822 →
rotation getter 0x50212577 → API 0x501f819d (d0 = 1, d1 = rotate 0xb) → driver 0x5005a253 → worker
0x50045460 → sample loop 0x50045a05: 100 samples through reader 0x5004457a, which polls 0x500447ea and
sleeps 16 ms (0x50030962) while it answers 1 = no new sample. Nothing fills the ring in the lab (the
sampler needs the sensor's SPI transfer, "[acc_spi]"), so the GUI task (152) polled for ever.

Data: client table 0x6055fc54 (12-byte entries, read index byte at entry−3), ring 0x6055fc78 (32 × 16 B,
four words), write-index byte 0x6055fe78. E143/E144's accelerometer (src/lab/acc.mjs) is another, newer
module — not reusable.

**Fix:** fact `acc_ring { poll, index, ring }` (rule `accRing`, byte form of the poll function; matches
E113 **and E120**: poll 0x50057ea9, index 0x616dc3fc, ring 0x616dc1fc) + **S-ACCRING** in camapp.mjs: before
each poll one new all-zero sample is stored and the write index advanced.

**Verified (E113, per-key frames):** Adjust → OK: trace `OlyHfwOpeLevel::calibration rotate=11 result=0`, no
lab error; Reset → OK likewise. After either OK the camera shows the **shooting screen**, not the K page;
MENU then opens Shooting Menu 1 and the menu navigates normally.

**Not known:** whether the real body also leaves the menu after Level Adjust (not chased in the GUI code);
what a level camera's sample is in this module's units (zeros are an inference, the calibration accepts
them); the level gauge on the shooting screen has no model on E113.

**Dead ends / traps:** OM_HOOK takes disasm addresses and only function entries fire reliably (mid-function
hooks never did); without per-key snapshots (OM_EACH) the key timing differs and the OK was not delivered —
walk with OM_EACH=1; the fact test compares key ORDER, so a new fact must sit in ports.mjs where readFacts
emits it.

**Committed:** om-emu `5744328`. Regression on it: test:pure all passed (facts 450/450, purity 256/256);
modelcheck E113 tft+evf ok; modelcheck E120 --no-port tft+evf ok; test_touch E113 11/11, E120 27/27.
Commit sweep of the three K rows after Level Adjust (Touch Screen Settings, Eye-Fi, Electronic Zoom Speed):
all three open, commit and return to the page (only the first shows a changed value on the page).
E120 Level Adjust itself was not walked (fact + stand-in installed, modelcheck clean).

**Still open vs the session goal:** full `bin/test.mjs` in one process (memory; ~20 bodies' firmware absent);
`checks/*` E143-only; JPEG Edit save (lab-wide); E120 Recording Volume popup E14; E120 Myset recall and E120
Level Adjust unwalked; E146 test_touch pre-existing failure; test_take_ready/test_unpack need files not here.

## Session 2026-11-18h — E120: every value popup was frozen (Timer2), Recording Volume popup, remaining tests

om-emu commit `03a0005` (branch e113-port, not pushed). Scratch walks `v5`…`w5`.

### Tests that needed files, now run (E143)
- `OM_FW_DIR=$PWD/../fw node test/test_unpack.mjs` → unpacker **18/18**, rc 0 (the containers are in
  `research/om-emu-src/fw`; the test only needs the variable).
- `test_take_ready` → rc 0 (9/9, 16/16, 24/24) once the E143 sub-CPU block
  `OLY_E_143_1900_0000_0000.BIN.blk0e560000.OE.0107.bin` is in `unpacked/E143` (extracted with
  `tools/olyfw.py --dump`; sha256 matches `models.mjs`). Gitignored, like all firmware.
- E146 `test_touch` fails identically on a clean worktree of the upstream merge base `18a8162`
  (`PlayError composite NONE UNWRITTEN pc 0xa2100400 ch 2 off 0x04e0`) → upstream, not this branch.

### E120 Recording Volume popup — three separate faults
1. **`GDB m435f434e,1: E14` = wrong ported IPI descriptor table.** `PlayLab.ipi_desc` read the ring
   descriptor at an address the automatic port got wrong (E120 map: `0x60092cd4 → 0x6009bbe1`). The true
   table is the immediate in E120's IPI_POST `0x5005077b` (`udfu01 48,d1; add 0x6009d1f8,a0`; 3 CPUs ×
   16 B rows). Fixed in `ports.mjs`: `0x60092cd4 → 0x6009d1f8`, `0x60092cf4 → 0x6009d218`,
   `0x60092dd4 → 0x6009d288`. The last two are **derived** (+0x20, +0x90 as on E143), not read from
   code; the +0x20 one is exercised by S-CPU2's reply and works.
2. **"S-CPU2: not the allow-listed post".** The popup's level meter posts a function to CPU 2. E120's
   is `0x50e5e25f` (sets flag `0x60041300` pattern 4 for the CPU 2 task loop `0x50e5ea1e`); the task's
   pattern-4 arm at `0x50e5ec39` is the same service as E143's command 3: if `(0x61b8c810) == 0` copy
   7 words `0x61b8c830 → 0xa1c4ce5c`, word `0xa1c51ab0 → 0xa1c4ce74`, then IPI-post the reply function
   to CPU 0 (`0x50e5eca2`). So the existing stand-in fits; only the function's map entry was missing
   (`0x50aaa173: null`). Fixed: `0x50aaa173 → 0x50e5e25f`. **Trap:** the block already held a
   `0x50aaa173: null` key later in the same object literal, which silently overrode a first insert.
3. **Storm on OK = no GUI timer at all on E120.** After 1+2 the popup opened and UP moved the slider,
   but OK stormed (`event storm … GESetEvent`, 0x9c01 reposted). Lock 2 (E120 `0x615f3fb4`) = `0x9ac6`
   and stayed so for seconds. The GUI timer table (10 × 28 B at `0x615e…`, set fn `0x5090dbfb`, start
   wrapper `0x50a4d067(d0=522, d1=10)`) held the timer armed, but the tick's ms clock
   `0x615e6b88/8c` was **0 for the whole session**. A control walk showed it was not mic-specific:
   **no value popup on E120 ever closed on OK** (Movie mic On→Off stayed open, later keys dead).
   Cause: `board.timer2` was an E113-only fact ("the family's other bodies have the unit but their
   IRQs are not read"). Read them: E120 channel table run-time `0x60026750` and mask table
   `0x60116b88` (named by the start routine `0x50065990`, the byte-twin of E113's `0x50051072`) are
   **identical in layout to E113's** (0x9b800010 + 12n, IRQ 24 for ch 0–9, 25–29 for 10–14; status
   `0x9b8000c4`, IE `0x9b8000dc`). Fixed: same `timer2` fact on E120's board (`hz` 16384 is the same
   inference as on E113).

**Verification (walk `w2`, TFT, NOERR, sheet viewed):** Movie mic On → Off commits; Recording Volume
popup opens with L/R meter, UP UP → +2, OK → list shows +2; reopen, DOWN → +1, OK → +1; MENU out and
back in keeps it. Walk `w5`: Exposure Shift page, Level Adjust → Reset → OK (goes to the shooting
screen, as on E113), Warning Level −1 commit and back to ±0, all NOERR.

**Dead ends:** varying S-MICMON's delay (120 ms … 1.5 s, and the stop note alone 1 ns … 60 s) — the
storm only moved; hooking for the awaited event with E113's lock addresses on E120 (meaningless, the
words are per body: use the map of E143 `0x603710f8`).

### What live timers changed on E120 (all followed up)
- **Auto-ISO value blinks** on the shooting screen (`ISO-A LOW`, firmware blink, same as noted for
  E113's EVF). `bin/modelcheck.mjs`'s INFO cycle compared whole frames at a press's end and no longer
  found its start frame ("info did not bring the screen back to its frame in 8 presses"). Now it
  compares on the pixels stable through the probe's window (6 frames, 300 ms apart). E120 and E113
  modelcheck: tft + evf ok, cycle 4 × INFO.
- `test_touch` E120 EVF "frame unchanged" check hit the other blink phase (26/27). The check now
  accepts the earlier frame coming back within 1.5 s; a frame a tap really changed does not come
  back. E120 27/27, E113 11/11, E143 7/7.
- **Erase dialog Yes now sends eraseCurrent** on E120 (upstream's `ERASE_YES_UNTIMED`, finding 155,
  was exactly the dead timer). E120 has no erase fact, so S-PLAYSEQ refuses request 21 — the honest
  outcome. E120 removed from the set; `test_playinfo` E120 21/21, `test_erase` E120 3/3.
- Also rerun on `03a0005`: `npm run test:pure` all passed (facts 450/450, purity 256/256);
  `test_storage` E120 70/70 (+ `--hls` rc 0).

### Still open vs the session goal (unchanged, stated plainly)
- Full `bin/test.mjs` as one process: not run. It imports every test into one node process (OOM on
  this 3.4 GB machine, reaped once) and needs ~20 bodies' firmware, of which five are here. Every test
  file was run standalone instead.
- `checks/*` pin E143 notes and addresses: E143-only by design, not runnable for E113.
- JPEG Edit **save** is refused by the lab on every body, E143 included (S-PLAYSEQ refuses the edits'
  saves; the save path is the image pipeline, not menu code).
- E146 `test_touch`: upstream failure (above).
- E120 native erase (no `erase` / `erase_named` fact matches E120).

### Correction (2026-11-18, later): "E146 test_touch fails" was a mis-run, not a failure
`test/test_touch.mjs` covers only the bodies of its `TOUCH_ICON` table; E146 is not in it and
`models.mjs` has `touch: false` for E146. The gate (`bin/test.mjs` line ~580) runs the file for E143
and `TOUCH_BODIES` only. Running it by hand with `OM_EMU_MODEL=E146` composes the start frame of a body
the test was never written for (`composite NONE: UNWRITTEN … ch 2 off 0x04e0` = OSDCP2 not yet written
at that body's start screen). So there is no E146 touch test to pass; strike it from the open list.

JPEG Edit save, looked at again: the refusal is `EDIT_SAVE` in `src/lab/camapp.mjs` (finding 141, 15
request ids: resize, trimming, colour, red-eye, shadow, e-Portrait, aspect, RAW edits, overlay, movie).
The lab runs no edit; finishing the request would report a file never written. Doing it for real needs
the play module native plus the image library's edit kinds (`playmod.mjs` is E143-only and only decodes
a view). That is a feature the emulator lacks on every body, not an E113 port gap.

## Session 2026-11-18i — full gate (`bin/test.mjs`) with all 20 bodies

User: "find the firmware yourself. The laptop has 16GB." JPEG Edit save: declared out of scope by the
user (emulator-wide missing feature).

- **Firmware:** `node emu/bin/fetch-fw.mjs --model <CODE>` downloads from the vendor's updater API,
  checks the pinned hash and unpacks. Fetched E147 E148 E139 E134 E137 E142 E140 E125 E145 E133 E130
  E129 E132 E118 E122 — all rc 0, so all 20 bodies of `models.mjs` are in `unpacked/`. Needs ~2.2 GB;
  the disk was 100 % full, freed by deleting this session's walk snapshots (`*.raw`, 3.6 GB) from the
  scratchpad.
- **Run 1** (`OM_FW_DIR=$PWD/../fw NODE_OPTIONS=--max-old-space-size=5500 node bin/test.mjs`, alone,
  11:59 → 14:55, about 2 h 56 min): ran to its end, no memory kill (available RAM stayed 2–5 GB).
  98 result lines, **`FAILED (8)`, all eight in one line: firmware facts 1590/1598**. Everything else
  passed, among it on E113: playinfo 21/21, erase 31/31, touch 11/11; on E120: 21/21, 3/3, 27/27.
  Three lines `SKIPPED` by the gate itself (they want `OM_CADUCEUS` = a caduceus-om3 checkout, a
  separate project; E147 only).
- **The 8 failures = this branch's fault, invisible until the other bodies were unpacked.** The fact
  rules added for E113 (`myset_load`, `mic_monitor`, `acc_ring`, `body_version`; on E118 also
  `strobe`, `mode_dial`, `erase_named`, `wifi_manager`) also read on E134 E125 E133 E130 E129 E132
  E118 E122, whose stored `FACTS` lines in `ports.mjs` predate them; the facts test compares stored
  with read on every unpacked body. Fixed (om-emu `7d49c55`) by rewriting those eight lines from the
  readers' own output (script checked first that re-emitting the stored value reproduces the old line
  byte for byte). `test_facts` 1598/1598. E118 and E122 rechecked: playinfo 21/21, erase 3/3 and
  35/35, touch 16/16 and 26/26, modelcheck rc 0; E125 touch 23/23.
  **Lesson:** a new fact rule must be checked on every body, not only the three unpacked ones — keep
  all 20 unpacked.
- **Run 2** started after the fix; result below when it ends.
