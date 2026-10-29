# Purist — bit-perfect ASIO music player (design spec)

Date: 2026-10-19 · Status: approved in brainstorming, awaiting written-spec review

## 1. Goal

A Java desktop music player for Windows that sends audio **bit-perfect** from the file to the
user's Topping USB DAC through the Topping ASIO driver. It bypasses the Windows audio stack (mixer,
resampler, volume, "enhancements") and uses no Windows codecs (Media Foundation, DirectShow). The
library and playlists follow foobar2000's model.

### Success criteria

1. WAV, AIFF, FLAC, DSF, DFF and plain-DSD SACD ISO files from the user's library play through the
   Topping ASIO driver. The DAC's own indicator shows the file's native rate, and DSD shows as
   **native DSD**, not DoP and not PCM.
2. The pipeline bit-identity tests pass (section 11). Every FLAC in the user's library decodes to
   the MD5 stored in its STREAMINFO block.
3. Consecutive tracks with the same format play gaplessly.
4. The ~7.8k-track library scans in the background, rescans touch only changed files, and search
   returns results interactively.
5. One hour of playback while using the UI finishes with a dropout counter of 0.

## 2. Background: how foobar2000 reaches the DAC (findings from the user's install)

This is a read-only inspection of foobar2000 2.25.7 (`C:\Program Files\foobar2000`, profile
`%APPDATA%\foobar2000-v2`). It uses import tables, strings and data files only. No code was
decompiled, and none was needed because every mechanism is a public Windows or ASIO API.

- **No Windows codecs.** `foobar2000.exe` imports no mfplat, DirectShow or DirectSound. It decodes
  with its own code plus a bundled FFmpeg (`avcodec-fb2k-62.dll`).
- **WASAPI exclusive** is built into the core (class `WASAPI_Exclusive`, a "WASAPI Exclusive Worker"
  thread, event or push mode, `hardwareBufferMS`/`prerollMS` overrides). It uses `CoCreateInstance`,
  `CreateEvent` and `AvSetMmThreadCharacteristicsW` (MMCSS "Pro Audio" priority).
  `foo_out_wasapis` is a separate **shared**-mode output and is not bit-perfect.
- **ASIO** (`foo_out_asio`) imports no audio APIs. It spawns `ASIOhost64.exe`/`ASIOhost32.exe`, which
  read the driver list from `HKLM\SOFTWARE\ASIO` and `CoCreateInstance` the driver CLSID out of
  process. This isolates the player from driver crashes and lets a 64-bit player use 32-bit drivers.
- **DSD** comes from `foo_input_sacd` (DSF/DFF/SACD ISO), output as DoP or as native DSD via ASIO.
- **Library.** `metadb.sqlite` table `metadb(name TEXT PK "subsong+file://path", info BLOB of
  NUL-separated tag pairs, size, lastModified, …)`. Watched folders live in `library-v2.0\folders`
  and playlists are plain-text `.fplite` files, one `file://` URL per line, named in
  `playlists-v2.0\index.txt`.

Purist follows the ASIO path, in-process (section 6).

## 3. Scope

**In v1**
- **Any DAC** (user requirement, 2026-10-23, superseding "ASIO only"). Two bit-perfect output paths
  sit behind `AudioSink`:
  - **ASIO**, for PCM and native DSD, through any installed ASIO driver. The user's DAC is a
    Topping D50 (driver v5.72); the Volt 176 is a PCM test device.
  - **WASAPI exclusive mode**, for every DAC Windows sees, including class-compliant USB DACs with
    no ASIO driver:
    - PCM is sent untouched, at the source rate and bit depth, in event-driven exclusive mode. The
      Windows mixer and APOs are bypassed.
    - DSD is sent as **DoP** (DSD over PCM, v1.1 markers 0x05/0xFA, 24 valid bits at rate/16) in a
      24-bit, 24-in-32 or, last, 32/32 container (the same 24 bits sit left-justified in the 32, so
      the wire carries identical bits). WASAPI cannot tell whether a DAC decodes DoP, and one that
      does not plays it as full-scale noise, so DoP is **opt-in per device**: Settings → Output has
      "This DAC supports DoP (DSD over PCM)", off by default and saved per endpoint
      (`wasapi.dop.<endpoint id>`). With it off, DSD over WASAPI is refused with a reason.
  - The output list in Settings shows ASIO drivers and WASAPI devices together, and defaults to the
    previously used one. The same bit-perfect-or-refuse rule (§5) applies to both.
- Formats: WAV/RF64, AIFF/AIFF-C, FLAC, DSF, DFF (uncompressed), SACD ISO (plain-DSD stereo area).
- Library: watched folders with incremental rescans, Volumio-style browsing (album-art grid,
  artists, folders), foobar-style search, playlist tabs (the Queue view), a Playback view with
  signal-path and bit-perfect indicator, and a one-time read-only import of foobar's folders and
  playlists.
- Look and layout: Volumio-style (section 9).

**Deferred (user's choice, 2026-10-19)**
- WMA Lossless, ALAC, APE, WavPack. **WMA was in the original request** and was deferred during
  scoping.
- Lossy formats: MP3, AAC, M4B. The user's 894 MP3s are listed as "unsupported" in v1.
- A DST decoder. The user's one SACD ISO is plain DSD, so DST discs and DST DFF files are refused
  with a message.
- Multichannel playback (stereo area only) and CUE sheets (the user's foobar library excludes `*.CUE`).
- Running the driver out of process, as foobar's ASIOhost does. This stays possible behind `AudioSink`.

Future decoders plug in behind the `Decoder` interface (for example, an FFmpeg-backed decoder for the
deferred formats).

## 4. Architecture

```
 Swing UI ──commands──▶ Player (queue, play/pause/seek, gapless, repeat/shuffle)
                              │ opens next track
                              ▼
 [decoder thread]  Decoder ─▶ Packer ─▶ off-heap RingBuffer (~2 s ahead)
                                                     │
 [ASIO driver thread]  bufferSwitch upcall ─▶ copy ring → driver buffers ─▶ outputReady
```

Threads:
- The Swing EDT runs the UI.
- One **decoder thread** fills the ring buffer.
- One **ASIO control thread** owns the driver. It initialises COM in apartment-threaded mode
  (`COINIT_APARTMENTTHREADED`), and every IASIO call except the callbacks runs on it.
- The **driver's real-time thread** invokes the callbacks.
- A background **library scanner** thread does the scanning.

Java 25 is the runtime, launched with `-XX:+UseZGC` (generational, sub-millisecond pauses) and
`--enable-native-access=ALL-UNNAMED`. Native calls go through the FFM API (`java.lang.foreign`),
with no JNI and no C/C++ code.

### Packages (`purist.*`)

| Package | Responsibility |
|---|---|
| `win` | FFM bindings for ole32 (`CoInitializeEx`, `CoCreateInstance`, `CLSIDFromString`), advapi32 (registry), kernel32 (`GetFileAttributesW`), user32 (`GetDesktopWindow`) |
| `asio` | IASIO vtable binding, constants, struct layouts, `AsioDriverList`, `AsioDriver`, `AsioSink` |
| `audio` | `StreamFormat`, `AudioSink`, `CaptureSink`, `RingBuffer`, `Packer`, DSD helpers |
| `decode` | `Decoder`, `DecoderRegistry`; sub-packages `wav`, `aiff`, `flac`, `dsd` (DSF/DFF), `sacd` |
| `tags` | Vorbis comments, ID3v2.3/2.4, RIFF INFO, cover-art lookup |
| `library` | `LibraryDb` (SQLite), `Scanner`, `FolderWatcher`, `query` (search parser and evaluator) |
| `playlist` | `Playlist`, `PlaylistStore`, `FoobarImport` |
| `player` | `Player` state machine, `PlaybackQueue` |
| `ui` | `MainWindow` and its panels |

## 5. The bit-perfect rule: bit-perfect or refuse

The only transforms allowed between file and DAC are those that preserve every sample bit:

1. **Container placement.** An integer sample of depth *b* goes into the driver's integer container
   unchanged: left-justified into `Int32LSB` (16-bit `<<16`, 24-bit `<<8`), or right-aligned into
   `Int32LSB16/18/20/24` and `Int16LSB`/`Int24LSB` when *b* ≤ the container's width. A source
   deeper than the container is refused.
2. **De-interleaving** into ASIO's per-channel buffers, plus a mono source duplicated to two
   channels (samples unchanged).
3. **DSD bit order.** Each byte is bit-reversed (a 256-entry table) when the source bit order
   differs from the driver's (DSF is LSB-first; DFF and SACD are MSB-first).

Forbidden everywhere: resampling, dithering, gain or software volume, EQ/DSP, format conversion
to float, and fades. Volume is the DAC's knob. Pause and underrun output digital silence: PCM
zeros, or the DSD idle pattern `0x69` (MSB-first) / `0x96` (LSB-first).

A track that cannot be played bit-perfect is **skipped with a stated reason** and never converted.
Reasons include: the rate is not supported by the driver (`canSampleRate`), a float-only driver,
float WAV, DST, a deferred format, or more channels than the device has.

## 6. ASIO output engine

### Driver discovery and loading
- Enumerate `HKLM\SOFTWARE\ASIO\<name>` and read the `CLSID` (and `Description`) values. The last
  driver used is remembered in settings.
- On the control thread:
  1. `CoInitializeEx(null, COINIT_APARTMENTTHREADED)`.
  2. `CoCreateInstance(clsid, null, CLSCTX_INPROC_SERVER, clsid, &p)`. The ASIO convention is
     IID == CLSID.
  3. `init(GetDesktopWindow())`. If it returns false, call `getErrorMessage`, show the text, and
     release the driver.

### Binding facts (from the GPLv3 ASIO SDK headers)
- On x64 Windows, `thiscall` is the standard x64 convention with `this` as the first argument.
  `long` = 4 bytes, `ASIOBool` = `long`, `ASIOSampleRate` = `double`. Structs are
  `#pragma pack(push,4)`.
- IASIO vtable slots after IUnknown (0–2):

  | Slot | Method | Slot | Method | Slot | Method |
  |---|---|---|---|---|---|
  | 3 | init | 10 | getLatencies | 17 | getSamplePosition |
  | 4 | getDriverName | 11 | getBufferSize | 18 | getChannelInfo |
  | 5 | getDriverVersion | 12 | canSampleRate | 19 | createBuffers |
  | 6 | getErrorMessage | 13 | getSampleRate | 20 | disposeBuffers |
  | 7 | start | 14 | setSampleRate | 21 | controlPanel |
  | 8 | stop | 15 | getClockSources | 22 | future |
  | 9 | getChannels | 16 | setClockSource | 23 | outputReady |

- `ASIOBufferInfo {long isInput; long channelNum; void* buffers[2];}` (24 bytes).
- `ASIOChannelInfo {long channel; long isInput; long isActive; long channelGroup; long type; char name[32];}`.
- `ASIOCallbacks {bufferSwitch; sampleRateDidChange; asioMessage; bufferSwitchTimeInfo;}`
  (4 function pointers).
- Sample types used: Int16LSB=16, Int24LSB=17, Int32LSB=18, Float32LSB=19 (refused), Float64LSB=20
  (refused), Int32LSB16/18/20/24=24–27, DSDInt8LSB1=32, DSDInt8MSB1=33. DSDInt8NER8=40 is refused.
- `future` selectors: kAsioCanDoIoFormat=0x23112004, kAsioSetIoFormat=0x23111961,
  kAsioGetIoFormat=0x23111983, taking an `ASIOIoFormat {long FormatType; char future[508];}` with
  kASIOPCMFormat=0 and kASIODSDFormat=1. `future` reports success as `ASE_SUCCESS` (0x3f4847a0);
  `ASE_OK` (0) is also accepted.
- `asioMessage` selectors: 1 SelectorSupported, 2 EngineVersion, 3 ResetRequest, 4 BufferSizeChange,
  5 ResyncRequest, 6 LatenciesChanged, 7 SupportsTimeInfo, 8 SupportsTimeCode, 15 Overload.
- DSD: the "sample rate" is the DSD bit rate (DSD64 = 2,822,400 … DSD512 = 22,579,200). The DSD word
  size is 1 bit, so a buffer of *N* samples is *N*/8 bytes per channel for Int8LSB1/MSB1.

### Opening a stream for a `StreamFormat`
1. Pick the I/O format. A **DSD source** needs `canDoIoFormat(DSD)` to succeed, then
   `setIoFormat(DSD)`; otherwise the track is refused (it is never converted to PCM). A **PCM source**
   calls `setIoFormat(PCM)`. A driver without `future` support is treated as PCM-only, and that call
   failing is ignored.
2. Call `canSampleRate(rate)`, refusing if unsupported, then `setSampleRate(rate)`.
3. Check `getChannels` (stereo outputs 0 and 1 by default; a channel-pair setting is stored), then
   read each output channel's `type` with `getChannelInfo`.
4. Validate the type against section 5, refusing if it doesn't fit. **A DSD source additionally
   requires the type read back after `setIoFormat(DSD)` to be 32 or 33.** This was verified on
   2026-10-19: the UA Volt's driver answers yes to `canDoIoFormat(DSD)` and
   `canSampleRate(2822400)` even though the hardware has no DSD, so the `can*` answers alone are
   not trusted.
5. Call `getBufferSize` and use **preferredSize** (set in the Topping control panel).
6. Create the callbacks as FFM upcall stubs in an arena owned by the stream, then call
   `createBuffers` for the output channels and `start`.
7. Read back `getSampleRate` and the channel types for the now-playing panel.

### Callbacks (on the driver thread; no allocation, no locks other than the ring buffer's atomics)
- `bufferSwitch(index, direct)`: copy `bufferBytes` per channel from the ring buffer into
  `buffers[index]`. If the ring buffer is short, fill the rest with silence and increment the
  dropout counter. Then call `outputReady()`.
- `bufferSwitchTimeInfo(time, index, direct)`: same work, then return `time`. `SupportsTimeInfo`
  answers 0 so drivers prefer plain `bufferSwitch`.
- `asioMessage`:
  - SelectorSupported → 1 for selectors 1–7, 0 otherwise.
  - EngineVersion → 2.
  - ResetRequest / BufferSizeChange → post a "rebuild stream" task to the control thread and return 1.
  - ResyncRequest / LatenciesChanged → log, return 1.
  - Everything else → 0.
- `sampleRateDidChange` → post a rebuild.

### Format switches and teardown
- On a same-format track change, nothing happens: the decoder keeps writing the next track into
  the same ring buffer (gapless).
- On a format change: `stop` → `disposeBuffers` → steps 1–7 above. The upcall arena is closed only
  after `disposeBuffers` returns.
- On exit: `stop` → `disposeBuffers` → `Release` → `CoUninitialize`.

### Device loss
An error return from `start`, a reset request that fails to rebuild, or `ASE_NotPresent` (-1000)
stops playback and shows a banner. Re-selecting the device, or an automatic retry every 3 s while
the banner is up, re-opens it.

## 7. Decoders and formats

```java
interface Decoder extends AutoCloseable {
  StreamFormat format();        // kind PCM|DSD, rate, bits (PCM), channels, lengthFrames, dsdBitOrder
  int read(MemorySegment dst, int maxFrames); // interleaved; PCM = native-endian int samples
                                              // of format.bits; DSD = byte-interleaved per channel
  void seek(long frame);        // DSD frames are bytes-per-channel units
}
```

`DecoderRegistry` chooses a decoder by extension and magic bytes. A container exposes one or more
**subsongs** (WAV/FLAC/DSF/DFF have one; an SACD ISO has one per track).

| Format | Key details |
|---|---|
| WAV / RF64 | `RIFF`/`RF64`+`ds64`; `fmt ` PCM (1) or EXTENSIBLE with the PCM subformat GUID; 8–32-bit integer. IEEE float (3) is refused. 8-bit unsigned is re-centred losslessly (`x-128`), which keeps every bit. |
| AIFF / AIFF-C | `FORM AIFF/AIFC`, `COMM` (80-bit extended rate), `SSND`; compression `NONE` (big-endian) or `sowt` (little-endian). |
| FLAC | Own decoder written from RFC 9639: STREAMINFO, the CONSTANT/VERBATIM/FIXED/LPC subframes, Rice and Rice2 partitions, stereo decorrelation modes, 4–32-bit, frame CRC-8/CRC-16 checked, and seeking through SEEKTABLE or a bisecting frame-sync search. |
| DSF | `DSD ` / `fmt ` / `data` chunks. Block-interleaved (4096 bytes per channel per block), bits-per-sample 1 = LSB-first. The final block is trimmed to the sample count. ID3v2 comes from the metadata pointer. |
| DFF | `FRM8`/`DSD `; `PROP`→`SND ` (`FS  `, `CHNL`, `CMPR`='DSD ' required, 'DST ' refused); `DSD ` data is byte-interleaved and MSB-first. ID3 comes from an `ID3 ` chunk if present. |
| SACD ISO | See "SACD ISO layout" below. All offsets were verified on the user's disc. |

### SACD ISO layout (big-endian; verified 2026-10-19 on *Analogue Productions – Ted Nugent.iso*)
- **Sectors** are 2048 bytes. A 2064-byte raw image is detected by `SACDMTOC` at 510×2064+12 and
  read with a 12-byte header skip per sector.
- **Master TOC** (sector 510): `"SACDMTOC"`; `u32` area-1 (stereo) TOC start at 0x40; area-2
  (multichannel) at 0x48; disc year `u16` at 0x78, month 0x7A, day 0x7B.
- **Master text** (sector 511): `"SACDText"`; `u16` byte offsets within the sector to
  NUL-terminated strings: album title 0x10, album artist 0x12.
- **Area TOC** (stereo, e.g. sector 544):
  - `"TWOCHTOC"`; `u16` size in sectors at 0x0A (= 8);
  - `fs_code` at 0x14 (4 = DSD64);
  - frame format at 0x15, low nibble: 0 = DST (refused), 2 = 3-in-14, 3 = 3-in-16;
  - channel count at 0x20;
  - total play time (min, sec, frame) at 0x40;
  - track offset at 0x44, track count at 0x45;
  - `u32` track area start at 0x48 and end at 0x4C (inclusive sectors).
- **Area sub-sectors**, found by scanning the area's `size` sectors for their 8-byte IDs:
  - `"SACDTRL1"`: `u32 start_lsn[255]` at +8 and `u32 length_sectors[255]` at +8+4×255. LSNs are
    absolute sectors; track 1 starts after a 2 s lead-in.
  - `"SACDTRL2"`: start and length time codes as 4-byte (min, sec, frame, flags) at +8 and
    +8+4×255. Track length in frames = (min×60 + sec)×75 + frame.
  - `"SACDTTxt"`: `u16` per-track offsets at +8, relative to the `SACDTTxt` sector start, which may
    point into the following sectors. Each entry is `u8 item_count`, 3 pad bytes, then per item
    `u8 type` (1 title, 2 performer, 3 songwriter, 4 composer, 5 arranger, 6 message), a `u8`
    0x20, a NUL-terminated string, padded to a 4-byte boundary.
- **Audio sectors** (from the track area start):
  - A 1-byte header: `packet_count = b>>5`, `frame_info_count = (b>>2)&7`, `dst = b&1`.
  - Then `packet_count` × `u16` packet infos: `frame_start = v>>15`, `type = (v>>11)&7`
    (2 audio, 3 supplementary, 7 padding), `length = v&0x7FF`.
  - Then `frame_info_count` × 3-byte time codes (4 bytes when `dst=1`), then the packet payloads
    in order.
  - Audio packets are concatenated; a packet with `frame_start=1` begins a new frame. A plain-DSD
    frame is exactly `4704 × channels` bytes (1/75 s at DSD64), byte-interleaved and MSB-first.
    Observed: frame 0 = 2016+2016+2016+2016+1344 bytes across sectors 624–628.
- **Seeking:** time → frame index → sector = `start_lsn + floor(frame × length_sectors /
  length_frames)`. From there, scan forward to the next `frame_start` packet and correct by its
  time code.

### Tags and art
- Vorbis comments (FLAC), ID3v2.3/2.4 with unsynchronisation and UTF-16/UTF-8 text (WAV `id3 `/`ID3 `
  chunk, AIFF `ID3 `, DSF metadata, DFF `ID3 `), RIFF `LIST/INFO`, and SACD text. Missing tags fall
  back to the filename and folder.
- Cover art comes from an embedded FLAC PICTURE or ID3 APIC (front cover preferred). Failing that,
  the first match of `cover|folder|front` with extension `.jpg|.jpeg|.png` in the track's folder.
  Full-size art is loaded lazily for the Album page and Playback view and is not stored in the
  database. Grid thumbnails are cached on disk (section 9).

## 8. Library, search, playlists

### Profile
`C:\Users\me\.purist\` (resolved as `%USERPROFILE%\.purist`). It is deliberately not in
`%APPDATA%`: when the app is launched by a Claude-spawned process, the MSIX package virtualises new
AppData folders, so the user's own launches would not see that data. It holds `library.db`,
`settings.properties`, `playlists\`, `purist.log` and `scan-errors.log`.

### Database (SQLite through `org.xerial:sqlite-jdbc`)
- `tracks(id INTEGER PK, path TEXT, subsong INT, size INT, mtime INT, format TEXT, kind TEXT,
  rate INT, bits INT, channels INT, length_frames INT, supported INT, error TEXT, UNIQUE(path, subsong))`
- `tags(track_id INT, field TEXT, value TEXT)` with an index on `(field, value COLLATE NOCASE)` and
  on `track_id`.
- `folders(path TEXT PK)`.

### Scanning
- Full walk on first run and when a folder is added. Afterwards, a file is re-read only when
  `(size, mtime)` differs from the database, as foobar does. Missing files are deleted and new files
  inserted, in batched transactions.
- Unsupported extensions (mp3, m4b, …) are stored with `supported=0` and minimal tags so they
  show greyed out.
- A file with `FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS` (0x400000) or `OFFLINE` (0x1000) is cloud-only.
  It is indexed from its filename only, with no content read, and its tags are read on first play.
- Live updates use `WatchService` with `ExtendedWatchEventModifier.FILE_TREE` (verified present in
  JDK 25.0.4), one recursive registration per folder, debounced 2 s per path. An `OVERFLOW` event
  triggers an incremental rescan of that folder.
- Parse failures go to `scan-errors.log` and the file is kept with `error` set.

### Whole-drive discovery through Everything (added 2026-10-23 at the user's request)
- Settings → Library lists every fixed NTFS drive with a checkbox. The user picks drives, and each
  picked drive is scanned **one at a time**.
- **Discovery** (finding the audio files) asks the user's running **Everything** (voidtools)
  instead of walking directories:
  - The call is made through the Everything SDK (`Everything64.dll`, bundled, called via FFM), for
    example `Everything_SetSearchW("E:\ ext:flac;wav;aif;aiff;aifc;dsf;dff;iso;mp3;m4a;…")`, then
    `Everything_QueryW` and `Everything_GetResultFullPathNameW`.
  - Everything already keeps an index of every NTFS drive, built from the master file table and
    kept current from the USN change journal. A whole drive answers in about a second, with no
    admin rights and no disk walk.
  - This is the user's choice (2026-10-23) over writing our own MFT/USN reader, which would need
    admin rights.
- **Fallback:** if Everything isn't running (`Everything_GetLastError` is `EVERYTHING_ERROR_IPC`), the
  drive is walked with `Files.walk`. Settings says so.
- The paths found go through the same pipeline as a folder scan: the `(size, mtime)` check, the
  cloud-only rule, the handlers and `scan-errors.log`.
- A picked drive is stored as a watched root (`E:\`). Live updates use Everything's own index on
  rescan; the `WatchService` per root still applies.

### Album browser
A tree of Album Artist (falling back to Artist), then `Album (Year)`, then tracks, sorted
case-insensitively and rebuilt incrementally after scans. Double-click plays the selection; the
context menu offers "Send to current playlist" and "Send to new playlist".

### Search
A recursive-descent parser over a foobar-style subset:
- Bare terms: case-insensitive substring match on any tag.
- `field HAS text`, `field IS text`, `field GREATER n`, `field LESS n`.
- `NOT`, `AND`, `OR` and parentheses, with implicit `AND` between terms. Precedence:
  NOT > AND > OR.
- Pseudo-fields: `format`, `samplerate`, `bitdepth`, `channels`, `kind` (pcm/dsd), `path`.
- Evaluated as SQL over `tags`/`tracks`, with parameter binding only.

### Playlists
- Tabs. Each playlist is `playlists\<uuid>.txt`: the first line is `#name:<name>`, then one
  `file://<path>` per track, with ISO tracks as `file://<path>#<subsong>`. An index file keeps tab
  order.
- Autosave runs 1 s after a change. Edits: add, remove, reorder by drag, and clear.
- Playback options: repeat off/track/playlist, shuffle, and "cursor follows playback".

### foobar import (menu: File → Import from foobar2000…)
- Read-only. Folders come from `%APPDATA%\foobar2000-v2\library-v2.0\folders` (extract
  `file://…` strings). Playlists come from `playlists-v2.0\index.txt` (`<guid>:<name>`) plus
  `playlist-<guid>.fplite` (one URL per line, UTF-8 BOM tolerated).
- `subsong+file://` prefixes, where present, map to `#subsong`.

## 9. UI: Volumio-style layout (Swing + FlatLaf with a custom theme)

The user chose (2026-10-19) to adopt Volumio's layout and look, with foobar's features living
inside it. The style tokens were taken from volumio.com and from the Volumio player shown on its
Get Started page.

### Visual language (revised 2026-10-22: Winamp-classic deck, three skins)

The user replaced the Volumio light-monotone look (2026-10-22): "super clean, nothing like you have done
before", taking ideas from the Winamp skin museum (skins.webamp.org). The UI is a flat, clean take on the
classic Winamp main window plus playlist.

| Token | Value |
|---|---|
| Fonts | **Share Tech Mono** for the LCD (time digits 52 px, title marquee 17 px, readouts 12 px); **IBM Plex Mono** Regular/Medium for everything else (13 px default). Both SIL OFL 1.1, bundled with their licences. Non-Latin text falls back to the logical `Monospaced` font. |
| Skins (`purist.ui.theme.Skin`) | **Paper LCD** (default: chassis `#F2F1EC`, reflective LCD `#D7DCC8` with `#1E2318` segments), **Phosphor** (dark chassis, `#8BE36B` on `#0A0E09`), **Ink** (black and white). Picked in Settings, saved as `ui.skin`. Light skins use the FlatLaf light base, dark skins the dark base. |
| Shapes | flat, 1 px edges, 3 px radius, no shadows, no gradients, no pills |
| Buttons | square transport buttons with Java2D line icons; text buttons UPPERCASE Plex Mono Medium 11 px, tracking 0.12; pressed/selected inverts to the highlight colours |
| Playlist | dense Plex Mono rows: `#.`, title, format, length; the playing row is inverted (highlight fill) |
| Bit-perfect | `● BIT-PERFECT` / `○ NOT BIT-PERFECT` (reason in the tooltip) in the LCD's segment colour; no extra colour |

### Layout
```
┌──────────────────────────────────────────────────────────────────────┐
│ PURIST                                ASIO · TOPPING USB AUDIO · 512 │
│ ┌──────────────────────────────────────────────────────────────────┐ │
│ │ 02:13   BABY BE MINE — MICHAEL JACKSON — THRILLER                │ │  LCD
│ │ ▶ PLAY  FLAC  24 BIT  96.0 kHz  STEREO  ● BIT-PERFECT            │ │
│ │         24/96 → INT32 LSB @ 96000 · 0 DROPOUTS                   │ │
│ └──────────────────────────────────────────────────────────────────┘ │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ │  seek
│ [⏮][▶][⏸][⏹][⏭] [⏏]                      [⇄][↻]  [QUEUE][SETTINGS]  │
│ ┌──────────────────────────────────────────────────────────────────┐ │
│ │ 1. Wanna Be Startin' Somethin'              FLAC 24/96     6:03  │ │  playlist /
│ │ 4. Thriller  (inverted = playing)           FLAC 24/96     5:57  │ │  settings
│ └──────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```
- The **deck** (LCD, seek line, transport) is always visible and replaces the now-playing bar and the
  separate Playback view.
- Below it, one content area switches between **Queue** (the playlist) and **Settings**; M3 adds
  **Browse** (library grid, search) to the same switch.

### Views
1. **Browse.** A search field (the section 8 foobar query syntax), with results shown as a track
   table, and pill tabs:
   - **Albums** (default): a virtualised art grid, 160 px covers with title and artist beneath,
     sorted by album artist then album.
   - **Artists**: a list; clicking an artist shows a grid of their albums.
   - **Folders**: a tree of the watched folders, for untagged material such as `E:\Music`.

   Clicking an album opens the Album page.
2. **Album page.** Ambient background, a 240 px cover, the `ALBUM` label, title, artist,
   `year · N tracks · format`, and pill buttons **PLAY** (primary) and **ADD TO QUEUE**
   (secondary), above a track table (# · Title · Length · Format). Double-clicking a track plays
   from it.
3. **Queue.** The playlist tabs from section 8, styled as pill tabs with a `+` button. A dense track
   table: playing marker, #, Title, Artist, Album, Length, Format, with greyed rows for unsupported
   tracks. Drag to reorder, Delete to remove, plus repeat and shuffle toggles. "Play" on an album
   replaces the current playlist's contents; "Add to queue" appends.
4. **Playback.** Ambient background, a cover up to 480 px, title, artist and album. The **signal
   path** card shows the source format, then the driver's sample type and rate read back from the
   driver (e.g. `FLAC 24-bit 96 kHz → ASIO Int32LSB @ 96000 Hz`, `DSF DSD128 → ASIO native DSD
   5.6448 MHz`), the ASIO buffer size and the driver name. Below it are a large BIT-PERFECT badge,
   big transport buttons and the seek bar.
5. **Settings.**
   - **Output:** ASIO driver drop-down, an "OPEN DRIVER PANEL" button (`controlPanel`), and the
     output channel pair.
   - **Library:** watched folders (add/remove), RESCAN, IMPORT FROM FOOBAR2000, scan progress and
     the last scan errors.
   - **About:** version and licence.

**The BIT-PERFECT rule.** `● BIT-PERFECT` requires the driver rate to equal the source rate, the
transform to be pass-through, zero-pad or bit-reversal only, and zero dropouts since the track
started. Otherwise the indicator shows `○ NOT BIT-PERFECT` with the reason.

**Notifications.**
- Scan progress is a thin accent line across the top of Browse.
- Toasts appear bottom-right above the now-playing bar.
- Device loss shows a full-width warning banner under the top edge.

**Keys:** Space play/pause, Enter play selection, Ctrl+F focus search, ←/→ seek ±5 s,
Ctrl+←/→ previous/next, Ctrl+1–4 switch views.

**Saved state:** the window bounds, the last view, the column widths and the last playlist are
saved in `settings.properties`.

### Cover thumbnails
The art grid needs a cover per album without re-reading files on the HDD every time:
- A thumbnail is extracted once, on a background executor, from the album's first track (embedded
  art) or its folder image (section 7).
- It is scaled to 320 px (so it stays sharp on 2× displays) and stored as
  `.purist\thumbs\<sha1(album folder path, album, album artist)>.jpg`. The three-part key keeps albums that
  share a folder (a downloads folder) from sharing one picture. Files named by an earlier, folder-only key
  are deleted the first time the cache opens a folder without the `key-scheme-2` marker.
- It is held in an in-memory LRU of 600 entries.
- A thumbnail is regenerated when the source file's modified time is newer than the thumbnail.
- Albums without art get a generated placeholder: a `#DADADA` square with the album's initials in
  `#6B6B6B` Nunito.

## 10. Error handling

| Situation | Behaviour |
|---|---|
| Track not playable bit-perfect / deferred format / DST | Skip to next, toast with the reason, row marked |
| Corrupt data mid-stream (FLAC CRC error, truncated file) | Log, finish the current buffer, advance to next track |
| Driver fails `init` / `CoCreateInstance` | Show the driver's error message plus the hint "close other ASIO apps — e.g. foobar2000 may be holding the device" |
| Reset request / buffer-size change | Rebuild the stream on the control thread and resume at the current position |
| Device unplugged / `ASE_NotPresent` | Stop, show a banner, retry every 3 s until re-opened or the user picks another driver |
| Ring-buffer underrun | Silence for the missing part, dropout counter++, indicator goes amber |
| Scanner I/O error | Log to `scan-errors.log`, continue |
| Uncaught exception on any thread | Logged to `purist.log`; the UI shows a non-blocking error toast |

## 11. Testing and verification

1. **Unit tests (JUnit Jupiter)** using generated fixtures: tiny WAV/RF64/AIFF/AIFC/DSF/DFF files and
   a synthetic SACD ISO image written by test helpers. They cover:
   - each parser's format and length;
   - `Packer` for every allowed (bits → container) pair, including exact bit preservation and
     refusal of a deeper source;
   - the DSD bit-reversal table;
   - `RingBuffer` wrap-around and concurrency;
   - the search parser (precedence, quoting) and SQL generation;
   - playlist save and load round-trip;
   - foobar import from a sample `folders` file and `.fplite`;
   - scanner change detection on a temp directory.
2. **FLAC conformance:** a test-only FLAC *writer* (a bit writer that emits chosen subframe types:
   CONSTANT, VERBATIM, FIXED orders 0–4, LPC with given coefficients, Rice and Rice2 residuals, all
   four stereo modes, 8/16/24/32-bit, plus a correct STREAMINFO MD5 and frame CRCs) generates
   fixtures, and the decoder must reproduce the source samples exactly. Real-encoder coverage comes
   from check 4. No external test vectors are downloaded.
3. **Pipeline bit-identity:** decoder → Packer → RingBuffer → `CaptureSink` (which emulates ASIO
   double-buffer callbacks with configurable buffer sizes and sample types). The captured
   per-channel bytes must equal the expected transform of the source samples, for PCM 16/24/32 and
   DSD in both bit orders, including gapless joins across two tracks.
4. **Real-library check:** a read-only tool or test (`-Dpurist.libraryCheck=…`) decodes every FLAC
   under the given roots and compares it to the STREAMINFO MD5. It must reach 1,204 out of 1,204.
   Files whose MD5 field is zero are reported as "no MD5".
5. **Live ASIO:**
   - PCM on the **UA Volt 176** ASIO driver (connected now): 44.1/48/96/192 kHz, gapless album,
     pause/seek, rate switch.
   - Native DSD64 and DSD128 on the **Topping**: the DSF *Thriller* (DSD128) and the Ted Nugent
     SACD ISO (DSD64). This needs the user and the DAC's display.
   - Dropout counter after 1 h.

## 12. Build, run, licensing

- Maven (`C:\Maven`, 3.10.0) and JDK 25 (`release 25`). Dependencies: `com.formdev:flatlaf`,
  `org.xerial:sqlite-jdbc`, JUnit Jupiter (test). Versions are pinned in the plan.
- `mvn package` produces `purist.jar`.
- **Final deliverable: a Windows `.msi` installer** (user requirement, 2026-10-23), built once the
  app is complete. `jpackage --type msi` (WiX underneath; WiX 7 is installed as a .NET tool, so
  check jpackage's supported WiX versions and fall back to `--type app-image` plus our own WiX
  `.wxs` if needed) produces `Purist-<version>.msi` with:
  - a bundled runtime and JVM options (`-XX:+UseZGC --enable-native-access=ALL-UNNAMED`);
  - a per-user install into `%LOCALAPPDATA%\Programs\Purist` (no admin needed);
  - a Start-menu shortcut;
  - an upgrade GUID so newer versions replace older ones;
  - `.flac`/`.dsf`/`.iso` "Open with Purist" entries that pass files on the command line.
- **License: GPL-3.0-or-later.** The IASIO/struct definitions are derived from the Steinberg ASIO SDK,
  which has been available under GPLv3 since October 2025. No ASIO SDK source files are vendored;
  only the interface layout is reproduced in Java.

## 13. Delivery milestones

Each milestone is usable and tested on its own:

1. **M1: PCM core.** FFM/COM/registry bindings, the ASIO engine, `Packer`/`RingBuffer`/`CaptureSink`,
   the WAV/AIFF/FLAC decoders, `Player`, and the first UI: the Volumio theme (fonts, palette,
   pills), the nav rail, the now-playing bar, the Playback view, Settings → Output, and an "Open
   files/folder…" action that fills a single queue. Exit: the bit-identity tests pass, the
   real-library FLAC check is 1,204/1,204, and gapless PCM plays on the Volt 176 over ASIO.
2. **M2: DSD.** The DSF, DFF and SACD ISO decoders and the native-DSD I/O format switch. Exit: the
   DSD bit-identity tests pass, and *Thriller* (DSD128) and the Ted Nugent ISO (DSD64) play as
   native DSD on the Topping (verified with the user).
3. **M3: Library and full UI.** Tags and art, `LibraryDb`, the scanner and watcher, thumbnails,
   the Browse/Album/Queue views, search, playlist tabs, Settings → Library with foobar import, the
   ambient background, and `jpackage` packaging. Exit: the success criteria in section 1.

## 14. Risks and open items

| Risk | Mitigation |
|---|---|
| A GC or safepoint pause during `bufferSwitch` causes a dropout | ZGC, zero-allocation callback, a ~2 s ring buffer, use of the driver's preferred (large) buffer; the dropout counter makes any issue visible |
| A driver crash kills the JVM (no out-of-process host) | Accepted for v1 (user's choice); `AudioSink` keeps an out-of-process host possible later |
| Driver-specific DSD quirks (buffer units, sample type offered) | Read back and log the actual channel types and buffer bytes on open; verify on the Topping in the live test |
| A sample type the Topping offers that isn't handled | All PCM integer LSB types and both DSD 1-bit types are handled; anything else is refused with the type number logged |
| foobar2000 holding the Topping's ASIO driver at the same time | Clear error hint; the user closes foobar |
| Topping model unknown (max PCM/DSD rate) | Irrelevant to the design: rates are queried with `canSampleRate` at runtime |
