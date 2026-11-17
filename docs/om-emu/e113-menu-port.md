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

## State

- Committed checkpoints: om-emu `c58aad0` (9 tasks + 4 HW stand-ins); docs `14580b7`,
  `f25c4e6`, + this.
- `check_menu` (E113) status: still fails at offline `menudata.groups()` (addresses not
  yet wired into PORTS.E113 / parser not yet model-parametric). No test faked. E143
  golden 95/95 unaffected.
- Scratch probes: `scratchpad/{scan_nulldesc,scan8,verify_group,climb,find_grouplist,
  classify,tree}.mjs`, `e113_full.asm` (full E113 disassembly).
