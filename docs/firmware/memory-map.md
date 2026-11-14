# Block 0 memory map and analysis setup

**Runtime address = load address + 0x3d691020.** Block 0 is staged at 0x40300000 but runs relocated: file offset 0 = runtime `0x7d991020` (= `runtime_base` in the community profile). VERIFIED: with this base 2315 of 3000 sampled strings have matching absolute pointers in code; with the load address 0 do.
Relative `jmp`/`call` are unaffected. Community patch offsets and addresses in the `STATUS.md` hook table are in load space; add 0x3d691020 for Ghidra.

Ghidra project (`research/ghidra_proj`, program `blk00_40300000.bin`) now imported at base `0x7d991020`: 72,580 functions, 10,763 reference strings.

Absolute-address regions seen in code operands (instruction count per 1 MB bucket, `tools/ghidra/ImmHist.java`):
| range | likely |
|---|---|
| 0x7cc00000-0x7e900000 | this image (code, rodata) |
| 0x60400000-0x60a00000 | RAM variables/bss (cached) |
| 0xa0900000-0xa0b00000 | RAM alias (uncached?) e.g. pointer_cell 0xa0a5c970 |
| 0xbd9b.... | **RAM scratch** (was tentatively "I/O registers"): the community ring payload uses 0xbd9b8810/0xbd9b8814 as its own read/write state cells, so this window is writable RAM, not memory-mapped I/O. Correction to the earlier guess. |
| 0x00008000-0x000080xx | **on-chip I/O (interrupt controller)** via AM33 abs16 window — 0x8050 = active interrupt channel reg, 0x8070 = latched status/enable, 0x8000-0x8018 = 7 boot-programmed channels. See `docs/hardware/boot-and-io.md` |
| 0x98001000 | memory-mapped peripheral block (boot writes 16 to 0x98001008) |
| 0x8ff00000, 0x99400000, 0x9a000000-0x9e000000 | unidentified (peripherals/DMA?) |

## Known RAM state cells (catalog, 2026-11-02)

Specific cells identified by disassembly across the Wi-Fi CGI, camera-mode, and ring-payload RE.
Addresses are in the uncached RAM alias (`0xa0_9xxxxx` / `0xa0_ax_xxxx`) or the `0x608e____` cached
bss unless noted. VERIFIED = read/written by disassembled code this session.

| cell | role | source |
|---|---|---|
| 0x608e9210 | Wi-Fi CGI **connection index** — selects the per-connection context/CGI-table; every CGI handler reads it and `asl2`-indexes 0xa0a92720 | wifi-cgi (get_commandlist/caminfo/connectmode/activate) |
| 0xa0a92720 | per-connection **context pointer table** (indexed by 0x608e9210 × 4) | same |
| 0xa0a925c0 | camprop **command block** word0 = property sub-id; +0xc4 = word1 (0 or arg); +0xc8 = encoded value (set path); +0xd0 = status/count out; +0xd4 = result-buffer pointer out | wifi-cgi camprop get/desc/set |
| 0xa0a9257e | **current cammode** (0=play/browse, 1=rec, 2=shutter); accessed only through critical-section helper 0x7e70f076 | wifi-cgi mode state machine |
| 0xa0a9258a | cammode **event-ack / last-posted** flag (compared in default handler 0x7e70c298 before posting event 0x41b0004) | wifi-cgi (inferred) |
| 0xa0a9258c | **lvqty** live-view quality: 1 = 0640x0480, else 0 (0320x0240) | wifi-cgi switch_cammode leaf |
| 0xa0a92590 | **take-phase** state (0/1/2/3); selects exec_takemotion phase handler | wifi-cgi exec_takemotion |
| 0xa0a5c970 | **lens-descriptor pointer** cell (ring descriptor_gate pointer_cell); null/aligned/RAM-range checked before use | ring-cave |
| 0xa0a5c6ec, 0xa0a5c974 | neighbour cells in the lens-descriptor struct | ring-cave |
| 0xbd9b8810 | ring **cached aperture value** (u16) — community payload scratch | ring-cave |
| 0xbd9b8814 | ring **ready flag** — community payload scratch | ring-cave |
| 0xa0937fd8 | **TSK_PTPCOM node pool** (USB/PTP mailbox) | usb-ptp |
| 0xa0a912fc | cached **DCF file number** (next image seq), read via getter 0x7db93fb8(key 23) | wifi-cgi get_dcffilenum |

### Property-registry keys (via getters 0x7d9d3769 / 0x7d9d36c4)
| key | meaning |
|---|---|
| 0x62F1 (25329) | model-name string (get_caminfo) |
| 0x8CF4 (36084) | UTC time-diff "timegap" / activation flag (get_activate / set_utctimediff) |

### Camera-task app-event ids (posted via 0x7e70e0c3 / 0x7d9b12a2)
| event | meaning |
|---|---|
| 0x41b0001 / 0002 / 0020 / 0022 / 0023 | cammode transitions (switch_cammode; full matrix only partly traced) |
| 0x41b0004 (68878340) | cammode event in default-handler path |
| 0x41b0011 | set_camprop value (all settable props except takemode) |
| 0x41b0012 | get_camprop current-value **and** desc query (differentiated by the 0xa0a925c0 block) |
| 0x41b0013 | get_camprop supported-value list (phase 2 of `com=get`) |
| 0x41b0014 / 0x41b0015 | takemode set / desc |
| 0x41b0024…0x41b0029 | exec_shutter release sub-events (1stpush … 2nd1strelease) |

### Boot-copied helper region 0x4700xxxx (NOT mask ROM — in our firmware file)
Calls into `0x47001xxx` recur as library primitives, used by every response builder. **Corrected
by call-site arg analysis** (prior note called both "byte-copy" — only one is):
- `0x47001000` = **memcpy**(dst=`a0`, src=`a1`, len=`d0`) — 1394 xrefs.
- `0x4700110c` = **memset**(dst=`a0`, val=`d0`, len=`d1`) — 3612 xrefs (call sites pass a fill
  value `0`/`0xff`/`255` in `d0`, not a source pointer, so it is memset not memcpy).

**Correction 2026-11-15 — this is NOT mask ROM; it is copied into place at boot from the firmware
file we already hold.** The prior "dead end: no ROM image" was wrong. E113's reset head copies a
`0x1000`-byte page to the `0x47000000` window at startup. Verified from E113's own boot head with the
OM-Emu reset-head parser (`resetHead`, cross-checked against the E118/E120/E122 facts, which do the
identical copy):
- library window `base 0x47000000, size 0x2000`; the primitives' page `image = [file 0x10497e0,
  len 0x1000, at 0x1000]` → runtime **`0x47001000`** is copied from **file offset `0x10497e0`** of
  code block `0x0100` (`…blk00000000.OE.0100.bin`, flash source `0x41349800`).
- Those 4096 bytes were extracted at that offset and decode as MN10300/AM33 code (store/branch copy
  idiom), not padding. So **both primitives are in our dump and are disassemblable** — point
  `mn10300-elf-objdump` at that file offset (runtime vma `0x47001000`). This removes the
  `0x4700xxxx` library from the list of things the bootloader-dump goal must recover.

### Boot region genuinely NOT in the update file (dump target)
The same reset-head walk finds one copy the emulator marks **`outside`**: `flash 0x40060000 →
0x7ff8a000, len 0x40000` (256 KiB). Its source `0x40060000` lies **below** the code block's flash
base (`0x40300020`), so **no shipped `.BIN` block holds it** — the E118/E120/E122 bodies have the
same `0x40060000` outside-copy. This 256 KiB at flash `0x40060000` is exactly the pre-boot/first-stage
content the bootloader-dump goal needs to read off the live flash: known flash address, known size,
not in any firmware file we have.

## Subsystems by log tag (`tools/subsystems.py`)
GUI, APPEVENT_STATE/CAMST/CAMON/PWR/TIMER (camera state machine), iseq_ps_* (image sequencer: still/through/recview/update/mng), exp (exposure), api_* (layered API: disp, rec, play, picfile, dps, network_Wifi, histogram, tft, oldb, adj, cube), rec_ImgPro*, RecView, CapThr, Cap_PlayMovie, movplay_com, app_panorama, app_background, GuiTouch, DevBlu_Bluetooth, WIFI_REMOCON_CGI/UTL, WIFI_RELEASE, do_cgi_get, AVM_FILE_MAIN/AVM_API, API_MEDIA(_SD), aud_dev_wm8946 (audio codec), PER_ADCONV, FLASH.

## Auto-naming
`tools/ghidra/NameFuncs.java` renamed ~9.3k functions (10k+ with C++ pass) from referenced strings; see `classes.md`. Re-runnable (touches only `FUN_*` and `*_<8hex>` names). Output TSVs in `research/decomp/names_pass*.tsv` (gitignored). The Ghidra project itself is not committed; recreate with the steps in `STATUS.md` + this file.
