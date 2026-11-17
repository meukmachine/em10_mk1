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
