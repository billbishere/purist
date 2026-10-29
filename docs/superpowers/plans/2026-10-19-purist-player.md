# Purist Bit-Perfect ASIO Player — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Purist, a Windows Java 25 desktop player. It sends WAV/AIFF/FLAC/DSF/DFF/SACD-ISO audio bit-perfect to the user's Topping DAC through its ASIO driver (PCM and native DSD), with a foobar2000-style library and a Volumio-style light monotone UI.

**Architecture:**
- Pure Java with the FFM API (`java.lang.foreign`) calling COM and the ASIO driver vtable in-process. No JNI and no C/C++.
- A decoder thread packs samples losslessly into an off-heap SPSC ring buffer. The ASIO driver thread's `bufferSwitch` upcall copies from that ring into the driver buffers.
- Library: SQLite with incremental `(size, mtime)` scans and a recursive `WatchService`. UI: Swing with a custom FlatLaf light theme.

**Tech Stack:** JDK 25 (Microsoft OpenJDK 25.0.4), Maven 3.10.0, FlatLaf 3.7.2, sqlite-jdbc 3.53.4.0, JUnit 6.1.3.

**Spec:** `docs/superpowers/specs/2026-10-19-purist-player-design.md` (commits a068bc3 → cf05efb). Executors read the spec alongside this plan. Section references below (§n) point into it.

## Global Constraints

- Windows 11 x64 only. `<maven.compiler.release>25</maven.compiler.release>`. Maven at `C:\Maven\apache-maven-3.10.0` (on PATH).
- Runtime and test JVM flags: `-XX:+UseZGC --enable-native-access=ALL-UNNAMED`.
- Pinned versions: `com.formdev:flatlaf:3.7.2`, `org.xerial:sqlite-jdbc:3.53.4.0`, `org.junit:junit-bom:6.1.3` (`junit-jupiter`, test scope), `maven-compiler-plugin 3.16.0`, `maven-surefire-plugin 3.6.0`, `maven-jar-plugin 3.5.1`, `maven-dependency-plugin 3.11.0`. Add no other runtime dependencies.
- Coordinates `dev.purist:purist:0.1.0-SNAPSHOT`; Java package root `purist`.
- License GPL-3.0-or-later. The ASIO interface layout is reproduced in Java only; no Steinberg SDK files are vendored.
- Profile dir `%USERPROFILE%\.purist`, overridable with `-Dpurist.profile=<dir>` (tests always override it). Never use `%APPDATA%` for Purist data.
- **Bit-perfect or refuse (§5):** no resampling, dithering, gain or volume, EQ/DSP, float conversion or fades anywhere. A track that can't be played bit-perfect is skipped with a stated reason.
- **ASIO threading (§4, §6):** every IASIO call except the callbacks runs on `AsioControlThread`. Callback code allocates nothing, takes no locks and catches all `Throwable`s (an exception escaping an upcall kills the JVM).
- **DSD output** requires the channel type read back after `setIoFormat(DSD)` to be 32 or 33 (§6 step 4; the UA Volt's driver gives false "yes" answers).
- **Test tags:**
  - `hardware`: any ASIO device; uses the UA Volt 176, which is connected and **changes its sample rate**.
  - `topping`: the Topping DAC.
  - `library`: reads the user's real files read-only.

  All are excluded by default via the pom property `test.excluded=hardware,topping,library`. Run one group with `mvn test "-Dtest.excluded=" -Dgroups=hardware`.
- **UI tokens:** exactly §9, light monotone, Nunito, UPPERCASE pill buttons. Album art is the only colour.
- Never write to the user's music folders or to `%APPDATA%\foobar2000-v2`.
- Commit after every task with the message given, ending with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Awkward paths.** Paths containing `#`, apostrophes, non-ASCII or more than 260 characters (e.g. `E:\Music\Laserdance - Trance Space Express (cd album '2018)-(flac)\…`, a file named `Track #1.flac`) must play and round-trip through playlists. A `#n` subsong suffix is honoured only after `.iso`. Tests: Task 13 (TrackRef) and Task 27 (PlaylistStore).
2. **Busy or lost device.** The ASIO driver is busy (foobar2000 holding the Topping), or is unplugged or reset mid-play. The user must get a clear message with a hint, a banner and a retry path, never a hang or crash. Tests: Task 7 (`busyDriverGivesHint`, `deviceLostNotifiesListener`).
3. **Truncated or corrupt files** (a half-copied FLAC, a bad CRC). The good frames play, then the track ends and the next one plays, with an error toast. No crash and no endless dropout loop. Tests: Task 12 (`truncatedFileThrowsAfterGoodFrames`) and Task 14 (`corruptFileSkipsToNext`).
4. **Rates and channel counts the DAC can't take.** Rates the DAC can't do (the Volt rejects 352.8 kHz and up) and more than 2 channels are skipped with a stated reason, and playback continues. Tests: Task 4 (`sixChannelRefused`), Task 7 (`refusesUnsupportedRate`) and Task 14 (`unsupportedRateSkipsWithReason`).
5. **Odd but valid files.** Untagged files fall back to the filename; mono, 8-bit WAV and 32-bit FLAC must play bit-perfect; non-Latin tags fall back to the Dialog font. Tests: Task 8 (`untaggedFallsBackToFilename`, `eightBitUnsignedRecentered`, `monoFileFormat`), Task 11 (`bps32SideChannel`) and Task 15 (`fallsBackForCjk`).

## File structure

```
purist/
  pom.xml  run.cmd  LICENSE  README.md  .gitignore
  scripts/package.ps1  scripts/install.ps1
  docs/verification/M1.md M2.md M3.md
  src/main/resources/fonts/Nunito-{Regular,SemiBold,Bold}.ttf  fonts/OFL.txt
  src/main/resources/purist/version.properties  purist/ui/theme/PuristTheme.properties
  src/main/java/purist/
    app/      Main Profile Settings AppLog
    win/      Native ComObject ComException Ole32 Registry FileAttributes
    asio/     AsioConstants AsioLayouts AsioDriver AsioCallbacks AsioEvent BufferSize AsioException
              ComAsioDriver AsioDriverInfo AsioDriverList AsioControlThread AsioSink AsioSinkStream
    audio/    Kind BitOrder StreamFormat SampleType Transform Packer NotBitPerfectException RingBuffer
              StreamCore AudioSink SinkStream SignalPath AudioDeviceException CaptureSink
    decode/   Decoder DecodeException UnsupportedFormatException TrackInfo FormatHandler DecoderRegistry
    decode/io/FileSource
    decode/wav/ WavHandler WavDecoder          decode/aiff/ AiffHandler AiffDecoder Extended80
    decode/flac/ BitReader Crc FlacStreamInfo FlacFrameHeader FlacFrameDecoder FlacMetadata SeekPoint
                 FlacDecoder FlacHandler FlacVerify
    decode/dsd/ DsfHandler DsfDecoder DffHandler DffDecoder
    decode/sacd/ SacdImage SacdArea SacdTrack SacdFrameReader SacdDecoder SacdHandler
    tags/     TagMap RiffInfo Id3v2 Id3Result VorbisComments
    playlist/ TrackRef Playlist PlaylistStore FoobarImport
    player/   Player PlayerState PlayerStatus PlayerListener PlaybackQueue RepeatMode
    library/  LibraryDb TrackRow AlbumSummary FileStamp Scanner ScanProgress ScanResult ScanErrorLog
              FolderWatcher LibraryService LibraryListener
    library/art/ CoverArt ThumbnailCache AlbumKey
    library/query/ QueryParser QueryNode SqlBuilder QueryException
    ui/       MainWindow NavRail NowPlayingBar PlaybackView SettingsView Toasts Banner OpenFilesAction
              UiPlayerBridge WindowState
    ui/theme/ PuristTheme Palette Fonts
    ui/kit/   Pill SectionLabel PillTabs BitPerfectIndicator CoverView Icons AmbientBackground AmbientPanel
              TrackTableModel
    ui/browse/ BrowseView AlbumGrid ArtistList FolderTree
    ui/album/ AlbumView          ui/queue/ QueueView QueueReorder
  src/test/java/purist/... (mirrors main) + testutil/ WavWriter AiffWriter Id3Writer BitWriter FlacWriter
                                             DsfWriter DffWriter SacdImageBuilder
                         + asio/FakeAsioDriver
```

---

# Milestone M1 — PCM core (exit: bit-identity tests green, 1,204/1,204 FLAC MD5, gapless PCM on the Volt over ASIO)

### Task 1: Project scaffold

**Files:**
- Create: `pom.xml`, `.gitignore`, `run.cmd`, `README.md`, `LICENSE`, `src/main/resources/purist/version.properties`, `src/main/java/purist/app/Main.java`
- Test: `src/test/java/purist/app/MainSmokeTest.java`

**Interfaces:**
- Produces: `purist.app.Main.version() -> String`, read from the filtered `purist/version.properties` (`version=${project.version}`).

- [ ] **Step 1: Write the failing test.** `MainSmokeTest.versionComesFromPom`: `assertEquals("0.1.0-SNAPSHOT", Main.version())`.
- [ ] **Step 2: Write `pom.xml`** with the Global Constraints versions and:
  - properties `test.excluded=hardware,topping,library` and `jvm.flags=-XX:+UseZGC --enable-native-access=ALL-UNNAMED`;
  - surefire `<argLine>${jvm.flags} -Dpurist.profile=${project.build.directory}/test-profile -Djava.awt.headless=true</argLine>` and `<excludedGroups>${test.excluded}</excludedGroups>`;
  - resource filtering on for `purist/version.properties` only;
  - jar plugin manifest `Main-Class: purist.app.Main`, `addClasspath=true`, `classpathPrefix=lib/`;
  - dependency plugin `copy-dependencies` (runtime scope) to `${project.build.directory}/lib` in the `package` phase.
- [ ] **Step 3: Run it and confirm it fails.** `mvn -q test -Dtest=MainSmokeTest`. Expected: compilation failure (no `Main`).
- [ ] **Step 4: Implement the rest of the scaffold.**
  - `Main` with `version()` and a `main` that prints `Purist <version>`.
  - `.gitignore`: `target/`, `*.log`, `.idea/`.
  - `run.cmd`: `@java -XX:+UseZGC --enable-native-access=ALL-UNNAMED -jar "%~dp0target\purist-0.1.0-SNAPSHOT.jar" %*`.
  - `LICENSE`: `curl -sL https://www.gnu.org/licenses/gpl-3.0.txt -o LICENSE` (35,149 bytes, checked 2026-10-19).
  - `README.md`: what Purist is, how to build and run, the licence, and the test tags.
- [ ] **Step 5: Run and confirm it passes.** `mvn -q test` then `mvn -q -DskipTests package`. Expected: tests pass, and `target/purist-0.1.0-SNAPSHOT.jar` and `target/lib/` exist.
- [ ] **Step 6: Commit.** `git add -A && git commit -m "build: Maven scaffold for Purist (JDK 25, pinned deps)"`

### Task 2: Win32 / COM layer over FFM

**Files:**
- Create: `src/main/java/purist/win/{Native,ComObject,ComException,Ole32,Registry,FileAttributes}.java`
- Test: `src/test/java/purist/win/{RegistryTest,Ole32Test,FileAttributesTest}.java`

**Interfaces:**
- Produces:
  - `Native`: `static final Linker LINKER`, `static MethodHandle fn(String lib, String symbol, FunctionDescriptor fd)`, `static MemorySegment wstr(Arena a, String s)` (UTF-16LE, NUL-terminated), `static String readWstr(MemorySegment s, int maxChars)`.
  - `ComObject(MemorySegment iface)`: `MemorySegment ptr()`, `MethodHandle method(int slot, FunctionDescriptor fdWithoutThis)` (cached per slot), `int release()`.
  - `ComException extends Exception`, with `int hresult()`.
  - `Ole32`: `static int coInitializeSta()`, `static void coUninitialize()`, `static MemorySegment clsid(Arena a, String braced)`, `static ComObject coCreateInstance(String clsid, String iid) throws ComException`.
  - `Registry`: `enum Hive { HKLM, HKCU }`, `static List<String> subKeys(Hive h, String path)`, `static Optional<String> stringValue(Hive h, String path, String name)`.
  - `FileAttributes`: `static int get(Path p)` (−1 if missing), `static boolean isCloudOnly(Path p)`.

- [ ] **Step 1: Write the failing tests.**
  - `RegistryTest.readsWindowsProductName`: `stringValue(HKLM, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "ProductName")` is present and starts with `"Windows"`.
  - `missingKeyGivesEmpty`: `subKeys(HKLM, "SOFTWARE\\NoSuchKey123")` is empty.
  - `listsSubKeys`: `subKeys(HKLM, "SOFTWARE\\Microsoft\\Windows NT")` contains `"CurrentVersion"`.
  - `Ole32Test.parsesIUnknownClsid`: `clsid(arena, "{00000000-0000-0000-C000-000000000046}")` gives byte 0 = 0, byte 8 = `(byte)0xC0`, byte 15 = `0x46`.
  - `badClsidThrows`: `IllegalArgumentException`.
  - `FileAttributesTest.tempFileNotCloudOnly`, and `missingFileIsMinusOne`.
- [ ] **Step 2: Run and confirm they fail.** `mvn -q test -Dtest=RegistryTest,Ole32Test,FileAttributesTest`
- [ ] **Step 3: Implement.** Library lookups use `SymbolLookup.libraryLookup(name, Arena.global())`, cached per library. `ComObject.method` binds the vtable entry and `this`. This exact pattern was verified against the Volt driver on 2026-10-19:
  ```java
  MemorySegment vtbl = ptr.get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * 64);
  MemorySegment fnPtr = vtbl.getAtIndex(ADDRESS, slot);
  MemoryLayout[] args = prepend(ADDRESS, fdWithoutThis.argumentLayouts());   // `this` first
  FunctionDescriptor fd = fdWithoutThis.returnLayout().map(r -> FunctionDescriptor.of(r, args)).orElse(FunctionDescriptor.ofVoid(args));
  return MethodHandles.insertArguments(LINKER.downcallHandle(fd), 0, fnPtr, ptr);
  ```
  - Registry calls: `RegOpenKeyExW` with `KEY_READ | KEY_WOW64_64KEY` (`0x20019 | 0x0100`), `RegEnumKeyExW`, `RegQueryValueExW` (`REG_SZ = 1`), then `RegCloseKey`. Hive handles: HKLM `0x80000002`, HKCU `0x80000001`.
  - `coInitializeSta` = `CoInitializeEx(NULL, 2)`. Treat `S_OK` (0) and `S_FALSE` (1) as success.
  - `isCloudOnly` = `(attrs & (0x400000 | 0x1000)) != 0`.
- [ ] **Step 4: Run and confirm they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(win): FFM bindings for COM, registry and file attributes`

### Task 3: ASIO driver binding

**Files:**
- Create: `src/main/java/purist/asio/{AsioConstants,AsioLayouts,AsioDriver,AsioCallbacks,AsioEvent,BufferSize,AsioException,ComAsioDriver,AsioDriverInfo,AsioDriverList}.java`
- Test: `src/test/java/purist/asio/{AsioLayoutsTest,AsioConstantsTest,AsioDriverListTest,ComAsioDriverIT}.java`

**Interfaces:**
- Consumes: Task 2 (`ComObject`, `Ole32`, `Registry`, `Native`).
- Produces:
  ```java
  public interface AsioDriver extends AutoCloseable {
    String name();
    int outputChannels() throws AsioException;
    BufferSize bufferSize() throws AsioException;
    boolean canSampleRate(double rate);
    void setSampleRate(double rate) throws AsioException;
    double sampleRate() throws AsioException;
    boolean canDoDsd();                                   // future(kAsioCanDoIoFormat, DSD) == ASE_SUCCESS or ASE_OK
    void setIoFormat(boolean dsd) throws AsioException;   // future(kAsioSetIoFormat, kASIODSDFormat or kASIOPCMFormat)
    int outputChannelType(int channel) throws AsioException;
    void createBuffers(int[] outputChannels, int bufferFrames, AsioCallbacks cb) throws AsioException;
    MemorySegment buffer(int index, int half, long byteSize); // index into outputChannels; half 0/1
    void start() throws AsioException;  void stop() throws AsioException;
    void disposeBuffers() throws AsioException;
    void outputReady();                                   // callback thread; ignores errors
    void controlPanel() throws AsioException;
    @Override void close();
  }
  public interface AsioCallbacks { void bufferSwitch(int half); void event(AsioEvent e, int value); }
  public enum AsioEvent { RESET_REQUEST, BUFFER_SIZE_CHANGE, RESYNC_REQUEST, LATENCIES_CHANGED, SAMPLE_RATE_CHANGED, OVERLOAD }
  public record BufferSize(int min, int max, int preferred, int granularity) {}
  public final class AsioException extends Exception { AsioException(String op, int code, String driverMessage); int code(); }
  public record AsioDriverInfo(String name, String clsid) {}
  public final class AsioDriverList { static List<AsioDriverInfo> installed(); }   // sorted by name; no CLSID → skipped
  public final class ComAsioDriver implements AsioDriver { static ComAsioDriver load(AsioDriverInfo info) throws AsioException; } // caller must be on an STA thread
  ```
  - `AsioConstants`: the error codes, selectors, sample types, `future` selectors and formats exactly as §6, the vtable slots `INIT=3 … OUTPUT_READY=23` (§6 table), and `static String errorName(int code)`.
  - `AsioLayouts`: `BUFFER_INFO` (24 B: `isInput`@0, `channelNum`@4, `buffers[0]`@8, `buffers[1]`@16), `CHANNEL_INFO` (52 B: `channel`@0, `isInput`@4, `isActive`@8, `channelGroup`@12, `type`@16, `name[32]`@20), `CALLBACKS` (32 B: 4 pointers), `IO_FORMAT` (512 B: `formatType`@0).

- [ ] **Step 1: Write the failing tests.**
  - `AsioLayoutsTest`: the four `byteSize()` values; `BUFFER_INFO.byteOffset(groupElement("buffers"))==8`; the `CHANNEL_INFO` `type` offset is 16 and `name` is 20.
  - `AsioConstantsTest.errorNames`: `errorName(-995)=="ASE_NoClock"`, `errorName(0x3f4847a0)=="ASE_SUCCESS"`, `errorName(-1000)=="ASE_NotPresent"`.
  - `AsioDriverListTest` (`@Tag("hardware")`): `installed()` contains `new AsioDriverInfo("Universal Audio Volt", "{7FA0A3EC-EBB7-4249-9CDC-F5474EE19B74}")`.
  - `ComAsioDriverIT` (`@Tag("hardware")`), on a new thread that first calls `Ole32.coInitializeSta()`:
    - load the Volt: `name()=="Universal Audio Volt"`, `outputChannels()==2`, `bufferSize().preferred()>0`;
    - `canSampleRate(48000)` is true; `canSampleRate(352800)` is false; `outputChannelType(0)==18`;
    - `setSampleRate(48000)`, then `createBuffers({0,1}, preferred, counting cb)`, `start`, sleep 500 ms, `stop`. The `bufferSwitch` count must be within ±15 % of `0.5*48000/preferred`.
    - `disposeBuffers`, `close`.
- [ ] **Step 2: Run and confirm they fail.** `mvn -q test -Dtest=AsioLayoutsTest,AsioConstantsTest` (and the hardware ones with the hardware flags).
- [ ] **Step 3: Implement.** `ComAsioDriver.load`:
  1. `Ole32.coCreateInstance(clsid, clsid)`. On failure, throw `AsioException("CoCreateInstance", hr, "")`.
  2. `init(GetDesktopWindow())` (user32). A return of 0 means failure: throw `AsioException("init", 0, getErrorMessage())`.

  Callbacks are FFM upcall stubs in an `Arena.ofShared()` that `createBuffers` creates and `disposeBuffers` closes (after the driver call returns).
  - `asioMessage` answers selectorSupported (1) → 1 for values 1–7, engineVersion (2) → 2, supportsTimeInfo (7) → 0.
  - It maps 3/4/5/6/15 to `AsioEvent` and returns 1 for selectors 3–6.
  - `bufferSwitchTimeInfo` calls `bufferSwitch(half)` and returns its `params` pointer. `sampleRateDidChange` emits `SAMPLE_RATE_CHANGED`.
  - Every upcall body is wrapped in `try { … } catch (Throwable t) { errors.increment(); }`.
- [ ] **Step 4: Run and confirm they pass**, including `mvn test "-Dtest.excluded=" -Dgroups=hardware -Dtest=AsioDriverListTest,ComAsioDriverIT` with nothing else playing on the Volt.
- [ ] **Step 5: Commit.** `feat(asio): ASIO vtable binding with driver-thread upcalls`

### Task 4: Stream formats, sample types and the lossless Packer

**Files:**
- Create: `src/main/java/purist/audio/{Kind,BitOrder,StreamFormat,SampleType,Transform,Packer,NotBitPerfectException}.java`
- Test: `src/test/java/purist/audio/{StreamFormatTest,PackerTest}.java`

**Interfaces:**
- Produces:
  ```java
  public enum Kind { PCM, DSD }   public enum BitOrder { MSB_FIRST, LSB_FIRST }
  public record StreamFormat(Kind kind, int sampleRate, int bits, int channels, long lengthFrames, BitOrder dsdOrder) {
    static StreamFormat pcm(int rate, int bits, int channels, long frames);         // dsdOrder null
    static StreamFormat dsd(int bitRate, int channels, long frames, BitOrder order); // bits = 1; frame = 1 byte (8 DSD bits) per channel
    int bytesPerDecodedFrame();   // PCM: 4*channels (decoders emit int32 LE, right-aligned); DSD: channels
    double seconds(long frames);  // PCM frames/rate; DSD frames*8/rate
    String label();               // "24/96", "16/44.1", "24/88.2", "24/192", "DSD64" … "DSD512"
    boolean sameStream(StreamFormat o);   // kind, sampleRate, channels equal
  }
  public enum SampleType {   // asioCode, containerBytes, validBits, dsd
    INT16_LSB(16,2,16,false), INT24_LSB(17,3,24,false), INT32_LSB(18,4,32,false),
    INT32_LSB16(24,4,16,false), INT32_LSB18(25,4,18,false), INT32_LSB20(26,4,20,false), INT32_LSB24(27,4,24,false),
    DSD_INT8_LSB1(32,1,1,true), DSD_INT8_MSB1(33,1,1,true);
    int asioCode(); int containerBytes(); int validBits(); boolean isDsd();
    static Optional<SampleType> fromAsio(int code);  BitOrder bitOrder();   // DSD types only (LSB1 → LSB_FIRST, MSB1 → MSB_FIRST)
  }
  public record Transform(int shift, boolean bitReverse, boolean monoDuplicate) { boolean isPassThrough(); String describe(); }
  public final class NotBitPerfectException extends Exception { NotBitPerfectException(String reason); }
  public final class Packer {
    static Packer create(StreamFormat src, SampleType out) throws NotBitPerfectException;   // output is always 2 channels
    Transform transform();
    void pack(MemorySegment src, int frames, MemorySegment[] dst);   // dst[0], dst[1]: frames*containerBytes each, from offset 0
    static int reverseBits(int b);
  }
  ```

- [ ] **Step 1: Write the failing tests** (byte arrays are little-endian as written to the driver).
  - `StreamFormatTest`:
    - the labels above, including `dsd(5644800,…).label()=="DSD128"` and `dsd(22579200,…)=="DSD512"`;
    - `dsd(2822400,2,352800,MSB_FIRST).seconds(352800)==1.0`;
    - `pcm(44100,16,2,0).sameStream(pcm(44100,24,2,9))` is true.
  - `PackerTest`, PCM packing:
    - `pcm16IntoInt32LeftJustifies`: 0x1234 → `00 00 34 12`; −1 → `00 00 FF FF`.
    - `pcm24IntoInt32`: 0x7FFFFF → `00 FF FF 7F`; −8388608 → `00 00 00 80`.
    - `pcm24IntoInt32Lsb24RightAligned`: 0x123456 → `56 34 12 00`; −1 → `FF FF FF FF`.
    - `pcm16IntoInt24`: 0x1234 → `00 34 12`.
    - `pcm32IntoInt32PassThrough`: `transform().isPassThrough()`.
    - `roundTripExhaustive16`: for all 65,536 values, `(int)LE32(pack(v)) >> 16 == v`.
  - `PackerTest`, refusals (exact messages):
    - `pcm24IntoInt16Refused`: `"24-bit source does not fit INT16_LSB"`.
    - `pcmIntoDsdRefused`: `"PCM source cannot go to a DSD output type"`.
    - `dsdIntoPcmRefused`: `"DSD source needs a DSD output type (driver offered INT32_LSB)"`.
    - `sixChannelRefused`: `"multichannel (6 ch) is not supported in v1"`.
  - `PackerTest`, DSD and channels:
    - `dsdLsbIntoMsbReversesBits`: `reverseBits(0x01)==0x80`, `reverseBits(0x69)==0x96`, and `reverseBits(reverseBits(b))==b` for all 256.
    - `monoDuplicatesToBothChannels`.
- [ ] **Step 2: Run and confirm they fail.** `mvn -q test -Dtest=StreamFormatTest,PackerTest`
- [ ] **Step 3: Implement.**
  - PCM: `shift = out.validBits - src.bits` (refuse if negative). Write `v << shift` as `containerBytes` little-endian bytes.
  - DSD: copy bytes, through the 256-entry reverse table when `src.dsdOrder != out.bitOrder()`.
  - `describe()` returns `"pass-through"`, `"zero-pad 8 LSBs"` (shift n → "zero-pad n LSBs") or `"DSD bit-order reversal"`, with `", mono→both channels"` appended when mono is duplicated.
- [ ] **Step 4: Run and confirm they pass.**
- [ ] **Step 5: Commit.** `feat(audio): stream formats and lossless sample packer`

### Task 5: Lock-free planar ring buffer

**Files:**
- Create: `src/main/java/purist/audio/RingBuffer.java`
- Test: `src/test/java/purist/audio/RingBufferTest.java`

**Interfaces:**
- Produces: `RingBuffer(int channels, int capacityFrames, int bytesPerSample)` (owns an `Arena.ofShared()`), `int freeFrames()`, `int usedFrames()`, `void write(MemorySegment[] planar, int frames)` (throws `IllegalStateException` if `frames > freeFrames()`), `int read(MemorySegment[] dst, long dstOffsetBytes, int frames)` (returns the frames copied, never blocks or allocates), `void clear()`, `void close()`.

- [ ] **Step 1: Write the failing tests.**
  - `writeReadRoundTrip`.
  - `wrapAround`: capacity 10; write 7, read 7, write 7, read 7; bytes equal.
  - `partialReadWhenShort`: returns the available count.
  - `concurrentProducerConsumer`: 1,000,000 frames of an incrementing pattern through capacity 4096 on two threads. The consumer verifies the sequence, with no loss and no duplication.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.** Use `long` read/write counters accessed via `VarHandle` (`getAcquire` / `setRelease`); the producer reads `readPos` with acquire, the consumer reads `writePos` with acquire. Wrap with at most two `MemorySegment.copy` calls per channel.
- [ ] **Step 4: Run and confirm they pass.**
- [ ] **Step 5: Commit.** `feat(audio): SPSC planar ring buffer`

### Task 6: StreamCore, sink interfaces, SignalPath and CaptureSink (pipeline bit-identity)

**Files:**
- Create: `src/main/java/purist/audio/{StreamCore,AudioSink,SinkStream,SignalPath,AudioDeviceException,CaptureSink}.java`
- Test: `src/test/java/purist/audio/{StreamCoreTest,CaptureSinkBitIdentityTest,SignalPathTest}.java`

**Interfaces:**
- Consumes: Tasks 4–5.
- Produces:
  ```java
  public final class StreamCore implements AutoCloseable {
    StreamCore(SampleType outType, int maxFillFrames, int ringFrames);
    void setSource(StreamFormat src) throws NotBitPerfectException;  // producer: new Packer for outType
    Transform transform();
    void write(MemorySegment decoded, int frames) throws InterruptedException; // packs in chunks ≤4096 frames; parks 1 ms while full
    void endOfInput();  void reset();            // reset only while the consumer is stopped
    void fill(MemorySegment[] channelBuffers, int frames);  // consumer: exactly `frames` into each buffer at offset 0, no allocation
    void setPaused(boolean p);  boolean paused();
    long framesPlayed();  long dropouts();  boolean drained();  byte silenceByte();  SampleType outType();
  }
  public interface SinkStream { StreamCore core(); SignalPath signalPath(); boolean accepts(StreamFormat next);
                                void start() throws AudioDeviceException; void stop(); }
  public interface AudioSink extends AutoCloseable { String name();
    SinkStream open(StreamFormat f) throws NotBitPerfectException, AudioDeviceException;  // stops and disposes the previous stream
    void addDeviceListener(Consumer<String> onDeviceLost);  @Override void close(); }
  public record SignalPath(String deviceName, StreamFormat source, SampleType outType, double deviceRate, int bufferFrames, Transform transform) {
    boolean rateMatches();  Optional<String> notBitPerfectReason(long dropouts);  String describe(); }
  public final class AudioDeviceException extends Exception { AudioDeviceException(String msg, Throwable cause); }
  public final class CaptureSink implements AudioSink {
    CaptureSink(String name, SampleType pcmType, SampleType dsdType, int bufferFrames, Set<Integer> supportedRates);
    void pump(int callbacks);  void pumpUntilDrained(int maxCallbacks);  byte[] captured(int channel);  int opens();
  }
  ```
- Rules:
  - `silenceByte()` is `0x00` for PCM, `0x69` for `DSD_INT8_MSB1` and `(byte)0x96` for `DSD_INT8_LSB1`.
  - A dropout is a `fill` call that pads with silence while not paused and before `endOfInput`.
  - `accepts(next)` = `core.outType` fits, `next.sameStream(current source)`, and `Packer.create(next, outType)` succeeds.
  - `CaptureSink.open` refuses an unsupported rate with `"rate %d Hz not supported by %s"`.
  - `describe()` examples: `"24-bit 96 kHz → INT32_LSB @ 96000 Hz"`, `"DSD128 → native DSD (DSD_INT8_MSB1) @ 5.6448 MHz"`.
  - `notBitPerfectReason` returns, in this order: `"device runs at X Hz, source is Y Hz"`, `"N dropouts"`, or empty.

- [ ] **Step 1: Write the failing tests.**
  - `StreamCoreTest`:
    - `underflowCountsDropoutAndPadsSilence`: write 100 frames, then `fill` 256 → 100 frames of data followed by `0x00`; `dropouts()==1`; `framesPlayed()==100`.
    - `afterEndOfInputNoDropout`.
    - `pausedOutputsSilenceWithoutDropout`.
    - `dsdSilenceBytes`: `0x69` for MSB1, `0x96` for LSB1.
    - `writeBlocksWhenFullAndResumes`.
  - `CaptureSinkBitIdentityTest`: seeded random 10,000-frame stereo sources. For each pair of bits ∈ {16, 24, 32} × out ∈ {INT32_LSB, INT24_LSB, INT32_LSB24, INT16_LSB} where it fits:
    1. `open`, `setSource`, `write`, `endOfInput`, `pumpUntilDrained(10_000)`.
    2. `captured(ch)` must equal the expected packed bytes followed only by silence.
  - Also in `CaptureSinkBitIdentityTest`:
    - `dsdBothOrders`: an LSB-first source into an MSB1 output is bit-reversed; MSB into MSB is identical.
    - `gaplessJoin16then24`: two sources at the same rate, `setSource` between them; the captured data is `pack(a) ++ pack(b)` with no silence between.
  - `SignalPathTest`: the `describe()` strings above, plus `notBitPerfectReason` for a rate mismatch and for 3 dropouts.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.** Preallocate the staging segments (4096 frames × 2 channels) in the constructor. `fill` makes one `ring.read`, then a silence fill of the remainder with `MemorySegment.fill`.
- [ ] **Step 4: Run and confirm they pass.**
- [ ] **Step 5: Commit.** `feat(audio): stream core, sink API, capture sink with bit-identity tests`

### Task 7: AsioControlThread and AsioSink

**Files:**
- Create: `src/main/java/purist/asio/{AsioControlThread,AsioSink,AsioSinkStream}.java`
- Test: `src/test/java/purist/asio/{FakeAsioDriver,AsioSinkTest,AsioSinkVoltIT}.java`

**Interfaces:**
- Consumes: Task 3 (`AsioDriver`, `ComAsioDriver::load`, `AsioDriverList`) and Task 6 (`AudioSink`, `SinkStream`, `StreamCore`, `SignalPath`).
- Produces:
  - `AsioControlThread()` (a daemon thread `"asio-control"` that calls `Ole32.coInitializeSta()` first): `<T> T call(Callable<T>) throws Exception`, `void post(Runnable)`, `boolean isControlThread()`, `close()` (which calls `CoUninitialize`).
  - `AsioSink(AsioDriverInfo info, AsioControlThread ctl, Function<AsioDriverInfo, AsioDriver> loader) implements AudioSink`.
  - `static AsioSink forDriver(AsioDriverInfo info)`, `void openControlPanel() throws AudioDeviceException`, `void setFirstOutputChannel(int ch)` (default 0, so channels ch and ch+1 are used).
- `open(format)` follows §6 steps 1–7, all on `ctl`. Exact messages:
  - Load failure: `AudioDeviceException("Could not open " + name + ": " + msg + ". Close other apps using ASIO — foobar2000 may be holding the device.")`.
  - `"%s does not offer native DSD"`.
  - `"%s did not switch to a DSD sample type (reported %d)"`, after which the sink calls `setIoFormat(PCM)`.
  - `"rate %d Hz not supported by %s"`.
  - `"unsupported ASIO sample type %d"`.
- Ring size: `max(2 s of output frames, 4 × preferred)`.
- On `RESET_REQUEST`, `BUFFER_SIZE_CHANGE` or `SAMPLE_RATE_CHANGED`, post a rebuild to the control thread: `stop` → `disposeBuffers` → `createBuffers` with the new preferred size → `start`, keeping the same `StreamCore` and its ring contents. If the rebuild fails, notify the device listeners with the error text.

- [ ] **Step 1: Write `FakeAsioDriver`**, programmable:
  - settings: `rates`, `pcmType=18`, `dsdCapable`, `dsdTypeAfterSwitch` (33, or 18 to emulate the Volt), `preferred=512`, `failOnLoad`, `failOnStart`;
  - it records a call log as strings like `"setSampleRate:96000.0"`;
  - `tick()` calls `bufferSwitch` alternately on the calling thread and copies the buffers into a capture log;
  - `fire(AsioEvent)` triggers an event.
- [ ] **Step 2: Write the failing tests** (`AsioSinkTest`).
  - `opensPcmAtSourceRate`: the log contains `setIoFormat:PCM`, `setSampleRate:96000.0`, `createBuffers:[0, 1]:512`; `signalPath().outType()==INT32_LSB`.
  - `refusesUnsupportedRate`: `"rate 352800 Hz not supported by Fake"`.
  - `refusesDsdWhenDriverReportsPcmTypeAfterSwitch`: message contains `"did not switch to a DSD sample type (reported 18)"`, and the last `setIoFormat` is PCM.
  - `opensDsdWhenTypeIs33`: outType `DSD_INT8_MSB1`, `deviceRate==2822400`.
  - `switchingFormatDisposesFirst`: open at 44.1 kHz then 96 kHz; the log order is `stop` → `disposeBuffers` → … → `createBuffers`.
  - `resetRequestRebuildsAndKeepsRing`: write 3000 frames, `tick` twice, fake preferred becomes 1024, `fire(RESET_REQUEST)`, then `tick` until drained. The captured bytes equal the 3000 packed frames in order.
  - `busyDriverGivesHint`: message contains `"foobar2000 may be holding the device"`.
  - `deviceLostNotifiesListener`: `failOnStart` after a reset → the listener receives the text.
  - `callbackDeliversBitIdenticalData`.
- [ ] **Step 3: Write `AsioSinkVoltIT`** (`@Tag("hardware")`):
  1. `forDriver(Volt)`, `open(pcm(48000,24,2,48000))`, `setSource`, write 1 s of zeros, `endOfInput`, `start`, sleep 1.3 s, `stop`.
  2. Assert `framesPlayed()==48000` and `dropouts()==0`.
  3. `open(pcm(44100,…))` → `signalPath().deviceRate()==44100`.
  4. `open(pcm(352800,…))` throws `NotBitPerfectException`.
- [ ] **Step 4: Run and confirm they fail.** Then implement, and run `mvn -q test -Dtest=AsioSinkTest` (PASS) and the hardware IT (PASS with the Volt idle).
- [ ] **Step 5: Commit.** `feat(asio): AsioSink with format switching, DSD type check, reset rebuild`

### Task 8: Decoder API, FileSource, TagMap, RIFF INFO and WAV

**Files:**
- Create: `src/main/java/purist/decode/{Decoder,DecodeException,UnsupportedFormatException,TrackInfo,FormatHandler}.java`, `decode/io/FileSource.java`, `tags/{TagMap,RiffInfo}.java`, `decode/wav/{WavHandler,WavDecoder}.java`
- Test: `src/test/java/purist/testutil/WavWriter.java`, `decode/wav/WavHandlerTest.java`, `decode/io/FileSourceTest.java`, `tags/TagMapTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface Decoder extends AutoCloseable { StreamFormat format(); int read(MemorySegment dst, int maxFrames) throws IOException; // 0 = end
                                                   void seek(long frame) throws IOException; long position(); void close() throws IOException; }
  public class DecodeException extends IOException {}   public class UnsupportedFormatException extends IOException { String reason(); }
  public record TrackInfo(Path path, int subsong, String codec, StreamFormat format, TagMap tags, boolean supported, String reason) {
    static TrackInfo unsupported(Path p, String codec, String reason);  static TrackInfo cloudOnly(Path p, String codec);
    String displayTitle();   // TITLE else filename without extension
    String displayArtist();  // ARTIST else ALBUMARTIST else ""
    String displayAlbum();   // ALBUM else parent folder name
    String albumArtist();    // ALBUMARTIST else ARTIST else "Unknown artist"
    String badge(); }        // PCM: codec + " " + label() e.g. "FLAC 24/96"; DSD: label() e.g. "DSD128"
  public interface FormatHandler { List<String> extensions(); List<TrackInfo> probe(Path p) throws IOException;
    Decoder open(Path p, int subsong) throws IOException; default Optional<byte[]> embeddedArt(Path p, int subsong) throws IOException { return Optional.empty(); } }
  public final class FileSource implements AutoCloseable { static FileSource open(Path p); long size(); long position(); void seek(long pos);
    int u8(); int u16le(); int u16be(); long u32le(); long u32be(); long u64le(); long u64be(); String fourcc(); byte[] bytes(int n);
    void skip(long n); int read(byte[] b, int off, int len); }   // 64 KiB buffer over FileChannel
  public final class TagMap { void add(String key, String value); Optional<String> first(String key); List<String> all(String key);
    Map<String, List<String>> asMap(); void putAllAbsent(TagMap other); void putAll(TagMap other); }
  public final class RiffInfo { static TagMap parse(byte[] infoListPayload); }
  ```
- `TagMap` upper-cases and trims keys and ignores blank values. Aliases: `ALBUM ARTIST`→`ALBUMARTIST`, `YEAR`→`DATE`, `TRACK`→`TRACKNUMBER`.
- RIFF INFO mapping: `INAM`→TITLE, `IART`→ARTIST, `IPRD`→ALBUM, `ICRD`→DATE, `IGNR`→GENRE, `ITRK`/`IPRT`→TRACKNUMBER, `ICMT`→COMMENT. Text is UTF-8 if it decodes cleanly, otherwise windows-1252.
- WAV rules: §7 WAV row.
  - `StreamFormat.bits` = valid bits; container bytes = `blockAlign/channels`; samples are left-justified in the container, so shift right arithmetically by `container*8 - bits`.
  - Float (format 3 or the float GUID) is refused: `"32-bit float WAV is not bit-perfect to an integer DAC"`. Other compressed formats: `"compressed WAV (format 0x%04X)"`.
  - A data size beyond the file end is clamped to the whole frames that are present.
  - `WavHandler` records any `id3 `/`ID3 ` chunk offset for Task 10.

- [ ] **Step 1: Write `WavWriter`**: `write(Path dir, String name, int rate, int bits, int channels, int[] interleaved, WavOpt... opts)`, with options `RF64`, `extensible(validBits, containerBits)`, `info(Map)`, `id3(byte[])`, `floatFormat()`, `declaredDataSize(long)`.
- [ ] **Step 2: Write the failing tests** (`WavHandlerTest`).
  - `probe16bitStereo`.
  - `decodes24bitExact` (int32 values equal the source).
  - `extensible24in32`.
  - `eightBitUnsignedRecentered`: 0x00 → −128, 0x80 → 0, 0xFF → 127.
  - `rf64HeaderParsed`.
  - `floatWavRefused` (the exact message).
  - `seekThenRead`.
  - `readsInfoTags`.
  - `untaggedFallsBackToFilename`: `displayTitle()=="01 Song"` for `01 Song.wav`.
  - `monoFileFormat`.
  - `truncatedDataChunkStopsCleanly`.
  - Also `TagMapTest` (aliases, blanks ignored, multi-value) and `FileSourceTest` (endian readers, reads across the buffer boundary).
- [ ] **Step 3: Run, confirm they fail, implement, then run again and confirm they pass.** `mvn -q test -Dtest=WavHandlerTest,TagMapTest,FileSourceTest`
- [ ] **Step 4: Commit.** `feat(decode): decoder API and bit-exact WAV/RF64 reader`

### Task 9: AIFF / AIFF-C

**Files:**
- Create: `src/main/java/purist/decode/aiff/{AiffHandler,AiffDecoder,Extended80}.java`
- Test: `src/test/java/purist/testutil/AiffWriter.java`, `decode/aiff/AiffHandlerTest.java`

**Interfaces:**
- Produces: `Extended80.toDouble(byte[] tenBytes)`; `AiffHandler` (extensions `aif`, `aiff`, `aifc`).
- Rules (§7 AIFF row):
  - `COMM`: channels `i16`, frames `u32`, sample size `i16`, rate as 80-bit extended. AIFC adds a compression fourcc: `NONE`/`twos` = big-endian, `sowt` = little-endian, anything else is refused with `"compressed AIFF-C (%s)"`.
  - `SSND`: data starts at `offset+8`. Samples use `ceil(bits/8)` bytes, left-justified.
  - The `NAME`/`AUTH` chunks map to TITLE/ARTIST. Any `ID3 ` chunk offset is recorded for Task 10.

- [ ] **Step 1: Write the failing tests.**
  - `extended80Values`: `40 0E AC 44 00 00 00 00 00 00` → 44100.0; `40 0F BB 80 00 00 00 00 00 00` → 96000.0.
  - `decodesAiff16BigEndianExact`.
  - `decodesAifcSowtExact`.
  - `aiff20bitLeftJustified` (20-bit values come out exact).
  - `compressedRefused` (`"compressed AIFF-C (fl32)"`).
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.** `mvn -q test -Dtest=AiffHandlerTest`
- [ ] **Step 3: Commit.** `feat(decode): AIFF and AIFF-C reader`

### Task 10: ID3v2 tags (WAV, AIFF; later DSF/DFF)

**Files:**
- Create: `src/main/java/purist/tags/{Id3v2,Id3Result}.java`
- Modify: `decode/wav/WavHandler.java`, `decode/aiff/AiffHandler.java` (merge ID3 over INFO/NAME tags and implement `embeddedArt`)
- Test: `src/test/java/purist/testutil/Id3Writer.java`, `tags/Id3v2Test.java`; add `WavHandlerTest.wavId3ChunkOverridesInfo`

**Interfaces:**
- Produces: `record Id3Result(TagMap tags, Optional<byte[]> frontCover)`, `static Id3Result Id3v2.parse(byte[] tag)`, `static Optional<Id3Result> Id3v2.readAt(FileSource src, long offset)`.
- Rules:
  - Versions 2.3 and 2.4; 2.2 gives an empty result. The tag size is syncsafe. Frame sizes are plain in 2.3 and syncsafe in 2.4.
  - Unsynchronisation: tag-level (flag 0x80) in 2.3, frame flag 0x02 in 2.4. The extended header is skipped.
  - Encodings: 0 ISO-8859-1, 1 UTF-16 with BOM, 2 UTF-16BE, 3 UTF-8. In 2.4, values separated by NUL become multiple values.
  - Frame mapping: TIT2→TITLE, TPE1→ARTIST, TPE2→ALBUMARTIST, TALB→ALBUM, TYER/TDRC→DATE, TRCK→TRACKNUMBER, TPOS→DISCNUMBER, TCON→GENRE (`"(17)Rock"` → `"Rock"`), TCOM→COMPOSER, `TXXX:desc`→`DESC`.
  - APIC: front cover (type 3) preferred, otherwise the first picture.

- [ ] **Step 1: Write the failing tests.**
  - `v23Latin1Title`.
  - `v24Utf8MultiValueArtists` (two ARTIST values).
  - `utf16BomTitle` (`"Motörhead"`).
  - `unsynchronisedTagDecoded`.
  - `apicFrontCoverPreferred` (type 0 then type 3 → returns the type 3 bytes).
  - `txxxBecomesKey`.
  - `wavId3ChunkOverridesInfo`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.** `mvn -q test -Dtest=Id3v2Test,WavHandlerTest,AiffHandlerTest`
- [ ] **Step 3: Commit.** `feat(tags): ID3v2.3/2.4 reader wired into WAV and AIFF`

### Task 11: FLAC frame decoding

**Files:**
- Create: `src/main/java/purist/decode/flac/{BitReader,Crc,FlacStreamInfo,FlacFrameHeader,FlacFrameDecoder}.java`
- Test: `src/test/java/purist/testutil/{BitWriter,FlacWriter}.java`, `decode/flac/{CrcTest,FlacFrameDecoderTest}.java`

**Interfaces:**
- Produces:
  ```java
  record FlacStreamInfo(int minBlock, int maxBlock, int minFrame, int maxFrame, int sampleRate, int channels, int bitsPerSample, long totalSamples, byte[] md5)
  final class BitReader { BitReader(byte[] buf, int off, int len); int readUInt(int n); int readSInt(int n); long readULong(int n); long readSLong(int n);
                          int readUnary(); long readRice(int k); void alignToByte(); int bytePosition(); }   // readRice returns the zigzag-decoded signed value
  final class Crc { static int crc8(byte[] b, int off, int len); static int crc16(byte[] b, int off, int len); }   // polys 0x07 / 0x8005, init 0, no reflection
  record FlacFrameHeader(int blockSize, int sampleRate, int channelAssignment, int bitsPerSample, long codedNumber, boolean variableBlocksize, int headerLength)
  final class FlacFrameDecoder { FlacFrameDecoder(FlacStreamInfo info);
    int decode(byte[] buf, int off, int len, int[] out) throws DecodeException;  // returns bytes consumed; `out` gets interleaved samples
    FlacFrameHeader lastHeader(); }
  ```
- RFC 9639 details the implementer must get right:
  - **Header codes.**
    - Block-size code: 1 → 192; 2–5 → 576·2^(n−2); 6 → 8-bit+1 at the end; 7 → 16-bit+1; 8–15 → 256·2^(n−8).
    - Rate code: 0 = STREAMINFO; 1–11 = 88.2k, 176.4k, 192k, 8k, 16k, 22.05k, 24k, 32k, 44.1k, 48k, 96k; 12 = 8-bit kHz; 13 = 16-bit Hz; 14 = 16-bit ×10 Hz; 15 is invalid.
    - Sample size: 0 = STREAMINFO, 1 = 8, 2 = 12, 4 = 16, 5 = 20, 6 = 24, 7 = 32; 3 is reserved.
    - The coded number is UTF-8-style, 1–7 bytes.
  - **Channels.** 0–7 = n+1 independent channels. 8 = left/side (side is the 2nd channel), 9 = side/right (side is the 1st), 10 = mid/side (side is the 2nd). The side channel carries `bps+1` bits.
  - **Subframe types.** 0 constant, 1 verbatim, 8–12 fixed order 0–4, 32–63 LPC order n−31. Wasted bits: a flag, then a unary count k, giving `k+1` wasted bits; decode at `bps−wasted`, then shift left.
  - **Residual.**
    - Method 0 has 4-bit parameters with escape 15; method 1 has 5-bit parameters with escape 31. An escape is followed by a 5-bit width n, then n-bit signed raw values (n = 0 means zeros).
    - Partition 0 holds `(blockSize >> order) − predictorOrder` samples.
  - **LPC.** Precision is 4 bits + 1 (15 is invalid). The shift is 5-bit signed; a negative shift is a `DecodeException`. Prediction is `Σ coef[j]·s[i−1−j] >> shift`, done in `long`.
  - **Fixed predictors (orders 1–4):** `s1`, `2s1−s2`, `3s1−3s2+s3`, `4s1−6s2+4s3−s4`.
  - **Decorrelation.** Left/side: `R=L−S`. Side/right: `L=S+R`. Mid/side: `M=(M<<1)|(S&1); L=(M+S)>>1; R=(M−S)>>1`.
  - **CRCs.** The header CRC-8 covers the header bytes; the footer CRC-16 covers the whole frame. Either mismatch is a `DecodeException("CRC mismatch at frame " + n)`.
- `FlacWriter` (test util) writes valid frames and whole files.
  - `frame(FrameSpec)` and `writeFile(Path, name, rate, bps, channels, int[] samples, int blockSize, SubframeKind kind, ChannelMode mode, boolean seekTable, Map<String,String> tags, byte[] picture)`.
  - `SubframeKind`: `CONSTANT`, `VERBATIM`, `FIXED(order)`, `LPC(order, int[] qcoefs, precision, shift)`. Residuals are Rice-coded with a given k and partition order 0–2, with an optional escape partition.
  - It computes the correct CRCs and the STREAMINFO MD5.

- [ ] **Step 1: Write the failing tests.**
  - `CrcTest`: `crc8("123456789")==0xF4` and `crc16("123456789")==0xFEE8`.
  - `FlacFrameDecoderTest`, subframe types:
    - `decodesConstant`.
    - `decodesVerbatim16`.
    - `fixedOrders0to4`.
    - `lpcOrder8`.
    - `riceEscapePartition`.
    - `wastedBits`.
  - `FlacFrameDecoderTest`, channels and sizes:
    - `stereoModesExact` (all four channel modes).
    - `bps8_16_24Exact`.
    - `bps32SideChannel` (32-bit left/side with side values needing 33 bits).
    - `variableBlocksizeCodedNumber`.
  - `FlacFrameDecoderTest`, corruption: `corruptedCrcThrows`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.** `mvn -q test -Dtest=CrcTest,FlacFrameDecoderTest`
- [ ] **Step 3: Commit.** `feat(flac): RFC 9639 frame decoder with CRC checks`

### Task 12: FLAC stream decoder, handler and MD5 verifier

**Files:**
- Create: `src/main/java/purist/decode/flac/{FlacMetadata,SeekPoint,FlacDecoder,FlacHandler,FlacVerify}.java`, `tags/VorbisComments.java`
- Test: `src/test/java/purist/decode/flac/{FlacDecoderTest,FlacLibraryCheckTest}.java`, `tags/VorbisCommentsTest.java`

**Interfaces:**
- Consumes: Task 11.
- Produces:
  - `FlacMetadata.read(FileSource)`, providing `info()`, `firstFrameOffset()`, `seekPoints()`, `tags()` and `frontCover()`.
  - `record SeekPoint(long sample, long offset, int samples)`.
  - `FlacDecoder.open(Path) implements Decoder`.
  - `FlacHandler` (extension `flac`).
  - `VorbisComments.parse(byte[] block) -> TagMap` (little-endian lengths, `KEY=value` UTF-8).
  - `FlacVerify`: `record Result(Path path, Status status, String detail)`, `enum Status { OK, MISMATCH, NO_MD5, ERROR }`, `static Result verify(Path)`, and `main(String[] roots)`. `main` prints `"<n> OK, <n> MISMATCH, <n> NO_MD5, <n> ERROR"` and lists every non-OK file.
- Rules:
  - Metadata header = last flag (1 bit) + type (7) + length (24). STREAMINFO is 34 bytes (layout in RFC 9639 §8.2).
  - SEEKTABLE points are 18 bytes; a placeholder sample is `0xFFFFFFFFFFFFFFFF`.
  - PICTURE (big-endian): type, MIME, description, w/h/depth/colours, data.
  - Seek: start from the best seek point ≤ target, otherwise bisect the file by byte offset. Scan for sync `0xFFF8`/`0xFFF9` with a valid CRC-8, decode forward to the frame that contains the target, and discard the leading samples.
  - MD5 covers little-endian samples of `ceil(bps/8)` bytes, interleaved. An all-zero MD5 → `NO_MD5`.
  - A file that ends mid-frame: `read` returns all the good frames, and the next call throws `DecodeException("truncated frame at byte N")`.

- [ ] **Step 1: Write the failing tests** (`FlacDecoderTest`).
  - `decodeWholeFileMatchesSource` (3 s of random stereo 24/96, and `FlacVerify.verify` returns OK).
  - `seekWithSeekTable`: `seek(100000)`, then the first sample read equals `source[100000]`.
  - `seekWithoutSeekTable` (same check).
  - `vorbisTagsRead` (including two ARTIST values).
  - `pictureBlockFrontCover`.
  - `truncatedFileThrowsAfterGoodFrames`.
  - `md5MismatchDetected` (alter one VERBATIM sample and fix the CRCs → MISMATCH).
  - Also `VorbisCommentsTest`.
  - `FlacLibraryCheckTest` (`@Tag("library")`): roots come from `-Dpurist.libraryRoots`, defaulting to `E:\Music;C:\Users\me\Music;C:\Users\me\OneDrive\Music`. Assert that MISMATCH + ERROR is 0, and print the counts. The expected result is 1,204 OK (from the foobar library count on 2026-10-19).
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.** `mvn -q test -Dtest=FlacDecoderTest,VorbisCommentsTest`. Then run the library check: `mvn test "-Dtest.excluded=" -Dgroups=library -Dtest=FlacLibraryCheckTest`. Expected: `1204 OK, 0 MISMATCH, …` (record the actual line in `docs/verification/M1.md`).
- [ ] **Step 3: Commit.** `feat(flac): seekable FLAC decoder, Vorbis/PICTURE tags, MD5 verifier`

### Task 13: TrackRef and DecoderRegistry

**Files:**
- Create: `src/main/java/purist/playlist/TrackRef.java`, `src/main/java/purist/decode/DecoderRegistry.java`
- Test: `src/test/java/purist/playlist/TrackRefTest.java`, `decode/DecoderRegistryTest.java`

**Interfaces:**
- Produces:
  - `record TrackRef(Path path, int subsong)`, with `String toUri()` and `static TrackRef parse(String uri)`.
    - `toUri()` is `"file://" + path` (backslashes kept, as in foobar), plus `"#" + subsong` only when the path ends with `.iso` (case-insensitive).
    - `parse` strips `file://` and splits `#n` only when the text matches `(?i).*\.iso#\d+$`.
  - `DecoderRegistry`: `static DecoderRegistry standard()` (WAV, AIFF, FLAC; M2 adds DSF, DFF, ISO), `void register(FormatHandler)`, `Optional<FormatHandler> handlerFor(Path)`, `boolean isAudioFile(Path)`, `List<TrackInfo> probe(Path)`, `Decoder open(TrackRef)`.
    - Handlers are matched by case-insensitive extension.
    - Known-unsupported extensions (`mp3`, `m4a`, `m4b`, `aac`, `ogg`, `opus`, `wma`, `ape`, `wv`, `ts`) probe as `TrackInfo.unsupported(p, EXT, EXT + " is not supported in v1")`.
    - Any other extension returns an empty list.
    - `isAudioFile(p)` is true for handler extensions **and** the known-unsupported list, so the scanner stores MP3s etc. as greyed-out rows. `OpenFilesAction` (Task 16) keeps only files with a handler.

- [ ] **Step 1: Write the failing tests.**
  - `TrackRefTest.roundTripAwkwardPath`: `E:\Music\Laserdance - Trance Space Express (cd album '2018)-(flac)\Motörhead ö.flac`.
  - `hashInFlacFilenameNotSubsong`: `file://C:\m\Track #1.flac` → subsong 0, path intact.
  - `isoSubsong`: `file://E:\x\disc.ISO#3` → subsong 3.
  - `longPathOver260` (a 300-character path).
  - `DecoderRegistryTest.mp3ProbesAsUnsupported`: reason `"MP3 is not supported in v1"`.
  - `handlerByExtensionCaseInsensitive`.
  - `nonAudioIgnored` (`.jpg` → empty list).
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat: TrackRef URIs and decoder registry`

### Task 14: Player and PlaybackQueue

**Files:**
- Create: `src/main/java/purist/player/{Player,PlayerState,PlayerStatus,PlayerListener,PlaybackQueue,RepeatMode}.java`
- Test: `src/test/java/purist/player/{PlayerTest,PlaybackQueueTest}.java`

**Interfaces:**
- Consumes: Task 6 (`AudioSink`, `SinkStream`, `StreamCore`, `CaptureSink`) and Task 13 (`DecoderRegistry`, `TrackRef`).
- Produces:
  ```java
  public enum PlayerState { STOPPED, PLAYING, PAUSED }   public enum RepeatMode { OFF, TRACK, ALL }
  public record PlayerStatus(PlayerState state, int index, TrackInfo track, double positionSeconds, double durationSeconds,
                             SignalPath signalPath, long dropoutsThisTrack, Optional<String> notBitPerfectReason) {}
  public interface PlayerListener { default void stateChanged(PlayerStatus s) {} default void trackStarted(int index, TrackInfo t, SignalPath p) {}
    default void trackSkipped(int index, TrackRef ref, String reason) {} default void error(String message) {} default void deviceLost(String message) {} }
  public final class Player implements AutoCloseable {
    Player(AudioSink sink, DecoderRegistry registry, Executor events);
    void setSink(AudioSink sink);  void setQueue(List<TrackRef> tracks, int startIndex);  List<TrackRef> queue();
    void play(int index); void pause(); void resume(); void togglePause(); void stop(); void next(); void previous(); void seek(double seconds);
    void setRepeat(RepeatMode m); void setShuffle(boolean on); PlayerStatus status(); void addListener(PlayerListener l); }
  public final class PlaybackQueue { PlaybackQueue(int size, RepeatMode mode, boolean shuffle, long seed);
    OptionalInt next(int current); OptionalInt previous(int current); }
  ```
- Behaviour (§4, §6):
  - **Decode thread** `"purist-decode"`:
    1. Open the decoder for the current item.
    2. Reuse the current stream if `stream.accepts(format)`; otherwise `sink.open(format)`.
    3. `core.setSource(format)`. A `NotBitPerfectException` fires `trackSkipped(reason)` and the queue advances.
    4. Read chunks of 4096 frames and `core.write` them.
  - **At the end of a track:**
    - If the next item's format is accepted, keep writing (gapless) and enqueue a `Boundary(startFrame, index, info)`.
    - Otherwise call `endOfInput()`, wait for `drained()`, and open the next track.
  - **Mid-stream errors:** an `IOException`/`DecodeException` fires `error("Stopped reading <file name>: <message>")`, then the player continues as at end of track.
  - **A 50 ms ticker** pops boundaries whose `startFrame ≤ core.framesPlayed()`, fires `trackStarted` and resets the dropout baseline.
  - **Controls:**
    - pause/resume = `core.setPaused`.
    - seek, next and previous = `stream.stop()` → `core.reset()` → `decoder.seek` (or open the new index) → pre-fill at least 2× the buffer → `start()`.
    - `previous` restarts the current track if the position is over 3 s.
  - **Device lost:** the state becomes STOPPED and `deviceLost(msg)` fires.
  - Listeners always run via `events` (the EDT in the app, `Runnable::run` in tests).
- `PlayerTest` uses `CaptureSink("Capture", INT32_LSB, DSD_INT8_MSB1, 512, Set.of(44100, 48000, 96000))` and a pump thread calling `pump(1)` every 1 ms.

- [ ] **Step 1: Write the failing tests.**
  - `PlaybackQueueTest`: `repeatOffEnds`, `repeatAllWraps`, `repeatTrackRepeats`, `shuffleVisitsAllOnce` (seeded).
  - `PlayerTest`, output correctness:
    - `playsTrackBitIdentical` (16/44.1 WAV).
    - `gaplessSameFormat`: two 44.1/16 WAVs → contiguous data and `opens()==1`.
    - `rateChangeReopens`: 44.1 then 96 → `opens()==2`.
  - `PlayerTest`, failures:
    - `unsupportedRateSkipsWithReason`: the 352.8 kHz middle track gets `trackSkipped(1, …, "rate 352800 Hz not supported by Capture")`, and the third track plays.
    - `corruptFileSkipsToNext`: a truncated FLAC then a WAV → an `error` event, and the WAV data is captured.
  - `PlayerTest`, controls:
    - `pauseOutputsSilenceWithoutDropouts`.
    - `seekPositionsExactly`: `seek(1.0)` → the next data is source frame 44100.
    - `trackStartedFiresAtBoundary`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.** `mvn -q test -Dtest=PlayerTest,PlaybackQueueTest`
- [ ] **Step 3: Commit.** `feat(player): gapless player with skip-with-reason and seek`

### Task 15: App shell, theme and UI kit

**Files:**
- Create: `src/main/java/purist/app/{Profile,Settings,AppLog}.java`; modify `app/Main.java`.
- Create: `ui/theme/{PuristTheme,Palette,Fonts}.java`, `resources/purist/ui/theme/PuristTheme.properties`, `resources/fonts/{Nunito-Regular.ttf,Nunito-SemiBold.ttf,Nunito-Bold.ttf,OFL.txt}`, `ui/kit/{Pill,SectionLabel,BitPerfectIndicator,CoverView,Icons}.java`
- Test: `src/test/java/purist/app/{ProfileTest,SettingsTest}.java`, `ui/theme/{PuristThemeTest,FontsTest}.java`, `ui/kit/BitPerfectIndicatorTest.java`

**Interfaces:**
- Produces:
  - `Profile.current()`, with `dir()`, `libraryDb()`, `settings()`, `playlists()`, `thumbs()`, `log()` and `scanErrors()`. Files are `library.db`, `settings.properties`, `playlists\`, `thumbs\`, `purist.log` and `scan-errors.log`, and directories are created on demand.
  - `Settings.load(Path)`, with `get(key, def)`, `getInt(key, def)`, `put(key, value)` and `save()` (writes to a temp file, then `ATOMIC_MOVE`).
  - `AppLog.init(Path)`: a `java.util.logging` `FileHandler` (1 MB × 3, one-line format) plus a default uncaught-exception handler that logs.
  - `Palette`: the §9 hex values as `Color` constants `BG, SURFACE, ELEVATED, DIVIDER, OUTLINE, TEXT, TEXT2, DISABLED, ACCENT, ACCENT_HOVER, ACCENT_PRESSED, PLACEHOLDER`.
  - `Fonts`: `register()`, `regular(float)`, `semiBold(float)`, `bold(float)`, and `forText(String text, Font preferred)`, which returns `preferred` if `canDisplayUpTo(text)==-1`, otherwise `new Font("Dialog", style, size)`.
  - `PuristTheme extends FlatLightLaf`, with `static boolean setup()`. The properties file sets `@accentColor=#2B2B2B`, `@background=#F4F4F4`, `Table.background=#FFFFFF`, `Component.arc=8` and `Button.arc=999`. `setup` also puts `defaultFont` = `Fonts.regular(13f)`.
  - `Pill(String text, Pill.Kind kind)`, where `Kind` is `PRIMARY` or `SECONDARY`. Text is UPPERCASE, Bold 13 px, `TextAttribute.TRACKING` 0.08, fully rounded. PRIMARY is a charcoal fill with white text; SECONDARY is a 1 px `OUTLINE` border with `TEXT`.
  - `SectionLabel(String)`: 11 px SemiBold, UPPERCASE, tracking 0.18, `TEXT2`.
  - `BitPerfectIndicator.update(Optional<String> reason, boolean active)`: text `"● BIT-PERFECT"`; or `"○ NOT BIT-PERFECT"` with the reason as tooltip; or `""` when inactive.
  - `CoverView(int px)`, with `setImage(BufferedImage)` (6 px radius) and `setPlaceholder(String title)` (`PLACEHOLDER` fill, initials in `TEXT2`).
  - `Icons`: Java2D-drawn line icons `browse`, `queue`, `playback`, `settings`, `prev`, `play`, `pause`, `next`, `plus`, `repeat`, `shuffle`, each `(int size, Color c) -> Icon`.

- [ ] **Step 1: Download the fonts into the repo** (URLs verified 200 OK on 2026-10-19):
  - `curl -sL https://cdn.jsdelivr.net/fontsource/fonts/nunito@latest/latin-{400,600,700}-normal.ttf`, saved as `Nunito-Regular.ttf`, `Nunito-SemiBold.ttf` and `Nunito-Bold.ttf`.
  - `curl -sL https://raw.githubusercontent.com/google/fonts/main/ofl/nunito/OFL.txt -o src/main/resources/fonts/OFL.txt`.
- [ ] **Step 2: Write the failing tests** (headless).
  - `ProfileTest.honoursSystemProperty`.
  - `SettingsTest.roundTrip` and `atomicSaveLeavesNoTemp`.
  - `PuristThemeTest`: after `setup()`, `UIManager.getColor("Panel.background")` is `#F4F4F4`, `getColor("Component.accentColor")` is `#2B2B2B`, and the family of `UIManager.getFont("defaultFont")` is `"Nunito"`.
  - `FontsTest.registersNunito`, `keepsNunitoForLatin1` (`"Motörhead"`), and `fallsBackForCjk` (`"ジャズ"` → family `"Dialog"`).
  - `BitPerfectIndicatorTest`: the three states and the tooltip.
- [ ] **Step 3: Run, confirm they fail, implement, then run again and confirm they pass.** `Main` becomes `AppLog.init` → `Fonts.register` → `PuristTheme.setup` → EDT opens a `JFrame "Purist"` (replaced in Task 16).
- [ ] **Step 4: Commit.** `feat(ui): app profile/settings/logging and the light monotone Purist theme`

### Task 16: M1 window (nav rail, now-playing bar, Playback, Settings → Output) and live check on the Volt

**Files:**
- Create: `src/main/java/purist/ui/{MainWindow,NavRail,NowPlayingBar,PlaybackView,SettingsView,Toasts,Banner,OpenFilesAction,UiPlayerBridge}.java`, `docs/verification/M1.md`
- Modify: `app/Main.java` (open `MainWindow`)
- Test: `src/test/java/purist/ui/{NowPlayingBarTest,OpenFilesActionTest}.java`

**Interfaces:**
- Consumes: Tasks 7, 13, 14 and 15.
- Produces:
  - `MainWindow.open(Settings, Profile)`, with `void show(View v)` where `enum View { BROWSE, QUEUE, PLAYBACK, SETTINGS }` (M1 shows only PLAYBACK and SETTINGS on the rail).
  - `NowPlayingBar.update(PlayerStatus)`, plus `static String time(double seconds)`.
  - `OpenFilesAction.expand(List<Path> selection, DecoderRegistry reg) -> List<TrackRef>`: folders are walked recursively, results are sorted case-insensitively by full path, only files with a handler are kept, and an ISO expands to its subsongs from `probe`.
  - `Toasts.show(String)`.
  - `Banner.show(String message, Runnable retry)` and `Banner.hide()`.
- Layout and behaviour: §9.
  - **Nav rail:** 56 px, `ELEVATED` background, with a 3 px `ACCENT` bar on the active item.
  - **Now-playing bar:** `SURFACE` background, a top `DIVIDER` line, cover 38, title/artist via `Fonts.forText`, a round charcoal play/pause button, a 4 px seek track, the time `m:ss / m:ss`, the badge `TrackInfo.badge()`, the indicator, and `"N dropouts"` only when N > 0. It refreshes from `player.status()` on a 10 Hz Swing `Timer`.
  - **Playback view:**
    - cover up to 480, title 28 px, artist 18 px, album;
    - a signal path card (`SURFACE`, 8 px radius) with rows `SOURCE`, `OUTPUT`, `BUFFER` (`"512 samples"`) and `DEVICE`;
    - a large indicator and large transport buttons;
    - when the queue is empty, the Pills `OPEN FILES…` (primary) and `OPEN FOLDER…` (secondary).
  - **Settings → Output:**
    - a `SectionLabel("OUTPUT")` and a combo of `AsioDriverList.installed()`, persisted as `output.driver` and defaulting to the first driver whose name contains `"Topping"`, else the first driver;
    - the `OPEN DRIVER PANEL` Pill;
    - a status line.
    - Changing the driver: `player.stop()`, close the old sink, `player.setSink(AsioSink.forDriver(info))`.
  - **`UiPlayerBridge`:** `trackSkipped` → toast `"Skipped <title>: <reason>"`; `error` → toast; `deviceLost` → banner with a `RETRY` Pill that re-opens the sink and resumes playback. While the banner is up it also retries automatically every 3 s (§6 Device loss).
  - **Uncaught exceptions:** `MainWindow` installs a default uncaught-exception handler that logs (via `AppLog`) and shows the toast `"Something went wrong. Details are in purist.log."` (§10).
  - **Keys:** Ctrl+O open files, Space toggle pause, ←/→ seek ±5 s, Ctrl+←/→ prev/next, Ctrl+3 Playback, Ctrl+4 Settings.

- [ ] **Step 1: Write the failing tests.**
  - `NowPlayingBarTest.showsBadgeAndIndicator`: a status with FLAC 24/96, bit-perfect → badge `"FLAC 24/96"`, indicator `"● BIT-PERFECT"`, dropout label hidden.
  - `timeFormat`: `time(125.4)=="2:05"`, `time(3725)=="1:02:05"`.
  - `OpenFilesActionTest.expandsFoldersSortedAndSkipsNonAudio`.
  - `isoExpandsToSubsongs` (uses a fake `FormatHandler` for `iso`).
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Live check on the Volt** (record the results in `docs/verification/M1.md`):
  1. `mvn -q -DskipTests package`, then `run.cmd`.
  2. Select "Universal Audio Volt" and open a 16/44.1 FLAC album from `E:\Music`. Expected: it plays, the badge reads `FLAC 16/44.1`, the indicator shows `● BIT-PERFECT`, the signal path reads `16-bit 44.1 kHz → INT32_LSB @ 44100 Hz`, and tracks play back-to-back with no gap.
  3. Open a 24/96 file. Expected: the rate switch happens, with only a short gap.
  4. Check pause and seek; the dropout count stays at 0.
  5. Take a screenshot with the computer-use tools and send it to the user.
- [ ] **Step 4: Commit.** `feat(ui): M1 window — playback view, now-playing bar, output settings`

---

# Milestone M2 — DSD (exit: DSD bit-identity tests green; Thriller DSD128 and the Ted Nugent ISO play as native DSD on the Topping)

### Task 17: DSF

**Files:**
- Create: `src/main/java/purist/decode/dsd/{DsfHandler,DsfDecoder}.java`; modify `DecoderRegistry.standard()` to register it.
- Test: `src/test/java/purist/testutil/DsfWriter.java`, `decode/dsd/{DsfHandlerTest,DsfLibraryTest}.java`

**Interfaces:**
- Consumes: Task 8 (`FileSource`, `TrackInfo`) and Task 10 (`Id3v2.readAt`).
- Produces: `DsfHandler` (extension `dsf`). The decoder emits byte-interleaved DSD in the file's bit order.
- File layout (little-endian):
  - `"DSD "` chunk: `u64 size=28`, `u64 totalFileSize`, `u64 metadataPointer` (0 = none).
  - `"fmt "` chunk: `u64 size=52`, `u32 version=1`, `u32 formatId=0`, `u32 channelType`, `u32 channelNum`, `u32 samplingFrequency`, `u32 bitsPerSample` (1 = LSB-first, 8 = MSB-first), `u64 sampleCount` (per channel), `u32 blockSizePerChannel=4096`, `u32 reserved`.
  - `"data"` chunk: `u64 size` (including the 12-byte header). Blocks are grouped as channel 0's 4096 bytes, then channel 1's, and so on. The last group is zero-padded.
- Frames = `ceil(sampleCount/8)` bytes per channel.
- Refusals: `channelNum>2` → `"multichannel DSF (%d ch)"`; `formatId!=0` → `"unknown DSF format id %d"`.

- [ ] **Step 1: Write the failing tests.**
  - `probeDsd64Stereo`: `dsd(2822400,2,frames,LSB_FIRST)`.
  - `deinterleavesBlocksExact` (2 full blocks plus a partial last block).
  - `dsd128Label`.
  - `seekByteAligned`: `seek(5000)` → the first output bytes are `ch0[5000]`, `ch1[5000]`.
  - `id3AtMetadataPointer`.
  - `msbFirstVariant`.
  - `DsfLibraryTest` (`@Tag("library")`): every `.dsf` under `E:\Music\Michael Jackson - Thriller - 1982, DSF 128 (tracks), TAV` probes as supported with `sampleRate==5644800`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat(dsd): DSF reader`

### Task 18: DFF (DSDIFF)

**Files:**
- Create: `src/main/java/purist/decode/dsd/{DffHandler,DffDecoder}.java`; register it.
- Test: `src/test/java/purist/testutil/DffWriter.java`, `decode/dsd/DffHandlerTest.java`

**Interfaces:**
- Produces: `DffHandler` (extension `dff`). Output is MSB-first and byte-interleaved.
- File layout (big-endian; chunks padded to even length):
  - `FRM8 u64size "DSD "`, containing:
    - `FVER`;
    - `PROP "SND "`, with sub-chunks `FS  ` (`u32` rate), `CHNL` (`u16` count + IDs) and `CMPR` (fourcc `DSD ` or `DST `);
    - `DSD ` (the sound data);
    - `DIIN`, with `DITI`/`DIAR` (`u32` length + text);
    - optionally `ID3 `.
- Refusal: `CMPR=DST ` → `"DST-compressed DFF is not supported yet"`.

- [ ] **Step 1: Write the failing tests.** `probe`, `decodeExact`, `dstRefused` (exact message), `diinTitleArtist`, `id3ChunkTags`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat(dsd): DFF reader`

### Task 19: SACD image TOC

**Files:**
- Create: `src/main/java/purist/decode/sacd/{SacdImage,SacdArea,SacdTrack}.java`
- Test: `src/test/java/purist/testutil/SacdImageBuilder.java`, `decode/sacd/{SacdImageTest,SacdRealDiscTest}.java`

**Interfaces:**
- Produces:
  ```java
  public final class SacdImage implements AutoCloseable {
    static SacdImage open(Path iso) throws IOException;   // UnsupportedFormatException("not an SACD image") if no SACDMTOC
    int sectorSize(); String albumTitle(); String albumArtist(); Optional<LocalDate> date();
    Optional<SacdArea> stereoArea(); byte[] readSector(long lsn) throws IOException; }   // 2048-byte payload
  public record SacdArea(long tocSector, int fsCode, int frameFormat, int channels, long trackAreaStart, long trackAreaEnd, List<SacdTrack> tracks) {
    boolean isDst();   // frameFormat == 0
    int dsdRate(); }   // fsCode 4 → 2822400; any other code → UnsupportedFormatException at open
  public record SacdTrack(int number, long startLsn, long lengthSectors, long startTimeFrames, long lengthFrames, TagMap tags) {}
  ```
- Every offset comes from the spec's "SACD ISO layout" subsection (verified on the real disc). Time code → frames = `(min*60+sec)*75+frame`.
- `SacdImageBuilder` writes a synthetic image containing the master TOC, master text, an area TOC with `SACDTRL1`, `SACDTRL2` and `SACDTTxt`, and audio sectors. It has an option for 2064-byte sectors.

- [ ] **Step 1: Write the failing tests.**
  - `SacdImageTest`:
    - `parsesMasterTocAndText`.
    - `parsesStereoAreaAndTracks`.
    - `trackTextTitleAndPerformerWithPadding`.
    - `detects2064ByteSectors`.
    - `dstAreaFlagged`.
    - `notSacdThrows`.
  - `SacdRealDiscTest` (`@Tag("library")`; path from `-Dpurist.sacdIso`, defaulting to `E:\Music\Ted Nugent - Ted Nugent (1975) SACD-ISO+Flac 24-88.2\Ted Nugent - Ted Nugent (1975) [APO Remaster 2015] (SACD-ISO)\Analogue Productions - Ted Nugent.iso`):
    - `albumTitle()=="Ted Nugent"`, with 9 tracks;
    - track 1: `startLsn==1324`, `lengthSectors==176881`, `lengthFrames==37903`, `startTimeFrames==150`, TITLE `"Stranglehold"`, ARTIST `"Ted Nugent"`;
    - `frameFormat==2`, `channels==2`, `fsCode==4`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat(sacd): SACD ISO table-of-contents parser`

### Task 20: SACD audio demux, decoder and handler

**Files:**
- Create: `src/main/java/purist/decode/sacd/{SacdFrameReader,SacdDecoder,SacdHandler}.java`; register `SacdHandler` in `DecoderRegistry.standard()`.
- Test: `src/test/java/purist/decode/sacd/{SacdFrameReaderTest,SacdRealDiscFramesTest}.java`

**Interfaces:**
- Consumes: Task 19.
- Produces:
  - `SacdFrameReader(SacdImage, SacdArea, long startLsn, long endLsnExclusive)`, with:
    - `long nextFrame(byte[] out)`: fills `4704*channels` bytes and returns the frame's absolute time code in frames, or −1 at the end;
    - `void seekToSector(long lsn)`.
  - `SacdDecoder implements Decoder`: format `dsd(2822400, 2, lengthFrames*4704, MSB_FIRST)`.
  - `SacdHandler` (extension `iso`), whose `probe` returns one `TrackInfo` per stereo track: subsong = track number, codec `"SACD"`, and the track text merged with ALBUM/ALBUMARTIST/DATE from the master text.
    - A DST area makes every track unsupported: `"DST-compressed SACD is not supported yet"`.
    - No stereo area: `"multichannel-only SACD is not supported in v1"`.
- Demux, verified against sectors 624–628 of the real disc:
  ```
  for each sector s:
    h = s[0]; nPk = h>>>5; nFi = (h>>>2)&7; if ((h&1)==1) throw DecodeException("DST frame in plain-DSD area")
    pk[i] = u16be(s, 1+2i) for i<nPk; tc[k] = 3 bytes at 1+2*nPk+3k for k<nFi; pos = 1+2*nPk+3*nFi; k = 0
    for i in 0..nPk-1: len = pk[i]&0x7FF; type = (pk[i]>>>11)&7; start = pk[i]>>>15
      if type==2: if start==1 { begin frame with time code tc[k++] }   // a partial previous frame here → DecodeException
                  if a frame has begun: append s[pos, pos+len)
      pos += len
      if current frame length == 4704*channels: emit
  ```
- Seek: frame index `f = byteFrame/4704`. Estimated sector = `startLsn + f*lengthSectors/lengthFrames`. Call `seekToSector`, read frames until the time code equals `startTimeFrames+f`, then discard `byteFrame%4704` bytes per channel.

- [ ] **Step 1: Write the failing tests.**
  - `SacdFrameReaderTest` (on the synthetic builder):
    - `assemblesFramesAcrossSectors`: frames split 2016/2016/2016/2016/1344 with supplementary packets interleaved; the output is byte-exact.
    - `timeCodesSequential`.
    - `seekResyncsOnFrameStart`.
    - `dstSectorThrows`.
  - `SacdRealDiscFramesTest` (`@Tag("library")`):
    - the first 750 frames of track 1 are each exactly 9408 bytes, with time codes 150, 151, …;
    - reading the whole of track 9 gives a frame count within ±1 of its `lengthFrames`;
    - `SacdHandler.probe` returns 9 supported tracks, track 1 titled `"Stranglehold"`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat(sacd): plain-DSD SACD ISO playback`

### Task 21: Native DSD live verification on the Topping

**Files:**
- Create: `src/test/java/purist/asio/AsioSinkToppingIT.java` (`@Tag("topping")`), `docs/verification/M2.md`

**Interfaces:**
- Consumes: Task 7 (`AsioSink`), Task 6 (`StreamCore`).

- [ ] **Step 1: Ask the user** to plug in the Topping and close foobar2000, and wait for confirmation.
- [ ] **Step 2: Write `AsioSinkToppingIT`.**
  1. Find the driver whose name contains `"Topping"`.
  2. Open `dsd(2822400,2,705600,MSB_FIRST)`. Assert `signalPath().outType().isDsd()` and `deviceRate()==2822400`.
  3. Write 2 s of `0x69`, `endOfInput`, `start`, sleep 2.3 s, `stop`. Assert `dropouts()==0` and `framesPlayed()==705600`.
  4. Repeat for `dsd(5644800,…)`.
  5. Then `open(pcm(44100,16,2,…))` succeeds (the DSD→PCM switch).
- [ ] **Step 3: Run it.** `mvn test "-Dtest.excluded=" -Dgroups=topping`. Expected: PASS. If the driver reports buffers in bytes rather than DSD samples, the test fails on `framesPlayed`. Log the driver's buffer size and channel types, and correct the byte calculation in `AsioSink` (bytes per buffer per channel = `bufferFrames*containerBytes` for PCM, `bufferFrames/8` for DSD types).
- [ ] **Step 4: Run the manual check with the user**, recording it in `M2.md`:
  - *Thriller* track 1 (DSF DSD128): the Topping's display shows DSD128, and Purist shows `DSD128 → native DSD … @ 5.6448 MHz ● BIT-PERFECT`.
  - Ted Nugent ISO track 1: DSD64.
  - A FLAC afterwards switches back to PCM.
- [ ] **Step 5: Commit.** `test(asio): native DSD verification on the Topping`

---

# Milestone M3 — Library and full UI (exit: spec §1 success criteria)

### Task 22: Cover art and thumbnail cache

**Files:**
- Create: `src/main/java/purist/library/art/{CoverArt,ThumbnailCache,AlbumKey}.java`
- Test: `src/test/java/purist/library/art/{CoverArtTest,ThumbnailCacheTest}.java`

**Interfaces:**
- Produces:
  - `CoverArt.find(TrackInfo t, DecoderRegistry reg) -> Optional<byte[]>`: embedded art via the handler first. Otherwise the folder file, searching names `cover`, `folder`, `front` in that order, with extensions `jpg`, `jpeg`, `png` in that order, case-insensitively.
  - `CoverArt.decode(byte[]) -> Optional<BufferedImage>`.
  - `record AlbumKey(String folderPath, String album, String albumArtist)`.
  - `ThumbnailCache(Path dir, int memoryEntries, int px)`, with production values `(Profile.thumbs(), 600, 320)`:
    - `Optional<BufferedImage> get(AlbumKey)` (memory, then disk);
    - `CompletableFuture<Optional<BufferedImage>> load(AlbumKey, Supplier<Optional<byte[]>> source, long sourceMtime)` on a 2-thread daemon executor;
    - `static BufferedImage placeholder(String album, int px)`.
- The disk file is `sha1(folderPath) + ".jpg"`. It is regenerated when `sourceMtime >` the file's mtime. Scaling: centre-crop to a square, then halve progressively with bilinear interpolation down to `px`.

- [ ] **Step 1: Write the failing tests.**
  - `folderCoverFoundCaseInsensitive` (`Cover.JPG`).
  - `embeddedPreferredOverFolder`.
  - `thumbnailWrittenAndReused` (the second `load` doesn't call the supplier).
  - `staleThumbnailRegenerated`.
  - `placeholderSize` (320×320).
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat(library): cover art lookup and on-disk thumbnail cache`

### Task 23: LibraryDb

**Files:**
- Create: `src/main/java/purist/library/{LibraryDb,TrackRow,AlbumSummary,FileStamp}.java`
- Test: `src/test/java/purist/library/LibraryDbTest.java`

**Interfaces:**
- Produces:
  ```java
  public record FileStamp(long size, long mtime) {}
  public record TrackRow(long id, TrackRef ref, String codec, StreamFormat format, boolean supported, String error, TagMap tags) { TrackInfo toInfo(); }
  public record AlbumSummary(String albumArtist, String album, String year, Path folder, int trackCount, TrackRef firstTrack, String formatLabel) {}
  public final class LibraryDb implements AutoCloseable {
    static LibraryDb open(Path file) throws SQLException;
    List<Path> folders(); void addFolder(Path p); void removeFolder(Path p);   // remove deletes tracks under it
    Map<Path, FileStamp> stamps(Path folder);
    void upsert(List<TrackInfo> infos, FileStamp stamp);   // one transaction: replaces all subsongs + tags of that path
    void markError(Path p, FileStamp stamp, String error); void deleteFiles(Collection<Path> paths);
    List<TrackRow> tracks(String whereSql, List<Object> params, String orderBy);
    List<TrackRow> album(String albumArtist, String album); List<AlbumSummary> albums(); List<String> artists(); int trackCount(); }
  ```
- The schema is verbatim from §8. Pragmas: `journal_mode=WAL`, `synchronous=NORMAL`. `tracks.format` stores the codec and `kind` stores `pcm`/`dsd`. Rows with a NULL rate are cloud-only, probed at play time.
- `albums()` groups by the album artist (`ALBUMARTIST` → `ARTIST`), then ALBUM, and orders case-insensitively.

- [ ] **Step 1: Write the failing tests.**
  - `upsertReplacesSubsongsAndTags`.
  - `stampsReflectUpserts`.
  - `removeFolderDeletesTracks`.
  - `albumsGroupedWithArtistFallback`.
  - `errorRowsKept`.
  - `cloudOnlyRowHasNullFormat`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat(library): SQLite library database`

### Task 24: Scanner

**Files:**
- Create: `src/main/java/purist/library/{Scanner,ScanProgress,ScanResult,ScanErrorLog}.java`
- Test: `src/test/java/purist/library/ScannerTest.java`

**Interfaces:**
- Consumes: Tasks 13 and 23 and `FileAttributes.isCloudOnly`.
- Produces:
  - `Scanner(LibraryDb db, DecoderRegistry reg, ScanErrorLog log, Predicate<Path> cloudOnly)`.
  - `ScanResult scan(Path root, Consumer<ScanProgress> progress, BooleanSupplier cancelled)`.
  - `record ScanResult(int added, int updated, int removed, int unchanged, int errors)`.
  - `record ScanProgress(int done, int total, Path current)`.
  - `ScanErrorLog(Path file)`, with `append(Path, String)` (lines `"<ISO-8601 time> <path>: <message>"`) and `List<String> tail(int n)`.
- Rules (§8):
  - Walk with `Files.walkFileTree` (no symlink following), keeping `reg.isAudioFile` files.
  - Skip any file whose stamp equals the stored stamp.
  - A cloud-only file becomes `TrackInfo.cloudOnly` and is never opened.
  - A probe failure → `markError` plus a log line.
  - Upserts are batched 200 files per transaction. Database paths under `root` that weren't seen are deleted.

- [ ] **Step 1: Write the failing tests.**
  - `firstScanAddsAll`.
  - `rescanUnchangedDoesNotReprobe` (a registry wrapper counts probes and must see 0).
  - `modifiedFileReprobed`.
  - `deletedFileRemoved`.
  - `corruptFileLoggedAndKept`.
  - `cloudOnlyNeverOpened` (the predicate returns true and the counting handler sees no `open`/`probe`).
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat(library): incremental scanner`

### Task 25: FolderWatcher and LibraryService

**Files:**
- Create: `src/main/java/purist/library/{FolderWatcher,LibraryService,LibraryListener}.java`
- Test: `src/test/java/purist/library/{FolderWatcherTest,LibraryServiceTest}.java`

**Interfaces:**
- Produces:
  - `FolderWatcher(Consumer<Set<Path>> onChanged, Duration debounce)`, with `watch(Path root)`, `unwatch(Path root)`, `close()`. It registers `ENTRY_CREATE/DELETE/MODIFY` with `com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE`, collects the changed parent directories, and emits them `debounce` after the last event. `OVERFLOW` emits the root.
  - `LibraryListener`, with `default void scanProgress(ScanProgress p) {}` and `default void libraryChanged() {}`.
  - `LibraryService(LibraryDb db, Scanner scanner, Function<Consumer<Set<Path>>, FolderWatcher> watcherFactory)`, with `start()`, `addFolder(Path)`, `removeFolder(Path)`, `rescanAll()`, `addListener(LibraryListener)` and `close()`.
    - `start()` scans every folder in the background and then watches them.
    - Watcher events trigger `scanner.scan(dir)` for each changed directory.
    - Everything runs on one single-thread executor `"purist-library"`, so scans never overlap.
- Production uses `Duration.ofSeconds(2)`.

- [ ] **Step 1: Write the failing tests** (debounce `200ms`, timeouts 5 s).
  - `createFileEmitsParentAfterDebounce`.
  - `burstCoalesced` (10 files → 1 callback).
  - `overflowEmitsRoot` (via the package-private `handle(WatchEvent<?>, Path)`).
  - `LibraryServiceTest.addFolderScansAndNotifies`.
  - `newFileInWatchedFolderIndexed`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat(library): recursive folder watching and library service`

### Task 26: foobar-style query parser and SQL builder

**Files:**
- Create: `src/main/java/purist/library/query/{QueryParser,QueryNode,SqlBuilder,QueryException}.java`
- Test: `src/test/java/purist/library/query/{QueryParserTest,SqlBuilderTest}.java`

**Interfaces:**
- Produces:
  - `QueryNode` (sealed): `And(List<QueryNode>)`, `Or(List<QueryNode>)`, `Not(QueryNode)`, `Field(String field, Op op, String value)`, `Term(String text)`, `All()`.
  - `enum Op { HAS, IS, GREATER, LESS }`.
  - `static QueryNode QueryParser.parse(String)`, throwing `QueryException(message, position)`.
  - `record SqlBuilder.Sql(String where, List<Object> params)` and `static Sql SqlBuilder.build(QueryNode)`.
- Grammar (keywords are case-insensitive; implicit AND):
  ```
  or := and ("OR" and)* ;  and := not (["AND"] not)* ;  not := "NOT" not | primary
  primary := "(" or ")" | WORD op value | value ;  op := "HAS"|"IS"|"GREATER"|"LESS" ;  value := QUOTED | WORD
  ```
- SQL translation (bound parameters only):
  - `HAS` → `EXISTS(SELECT 1 FROM tags g WHERE g.track_id=t.id AND g.field=? AND g.value LIKE ? ESCAPE '\')`, with the value wrapped `%…%` and `% _ \` escaped.
  - `IS` → `g.value = ? COLLATE NOCASE`.
  - `GREATER`/`LESS` → `CAST(g.value AS INTEGER) > ?`.
  - Pseudo-fields map to columns: `format`→`t.format`, `samplerate`→`t.rate`, `bitdepth`→`t.bits`, `channels`, `kind`, `path`.
  - A bare `Term` → `EXISTS` over any field with `LIKE`.

- [ ] **Step 1: Write the failing tests.**
  - `QueryParserTest`:
    - `precedence`: `"a OR b c"` → `Or[a, And[b, c]]`.
    - `notBindsTightest`.
    - `quotedValue`: `artist IS "ted nugent"`.
    - `emptyIsAll`.
    - `unbalancedParenThrowsWithPosition`.
  - `SqlBuilderTest`:
    - `pseudoFieldSamplerate`: `samplerate GREATER 96000` → contains `"t.rate > ?"`, params `[96000]`.
    - `likeEscaping`: `title HAS 100%` → param `"%100\%%"`.
    - `injectionStaysInParams`.
    - `runsAgainstLibraryDb` (3 tracks; `format IS dsf` returns only the DSF row).
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat(library): foobar-style search queries`

### Task 27: Playlists and foobar2000 import

**Files:**
- Create: `src/main/java/purist/playlist/{Playlist,PlaylistStore,FoobarImport}.java`
- Test: `src/test/java/purist/playlist/{PlaylistStoreTest,FoobarImportTest}.java`

**Interfaces:**
- Produces:
  - `Playlist`: `UUID id()`, `String name()`, `rename(String)`, `List<TrackRef> items()` (unmodifiable), `add(List<TrackRef>)`, `insert(int, List<TrackRef>)`, `remove(int[])`, `move(int[] indices, int to)`, `replace(List<TrackRef>)`, `clear()`.
  - `PlaylistStore(Path dir)`, with `all()`, `create(String)`, `delete(UUID)`, `reorder(List<UUID>)`, `changed(Playlist)` (autosave debounced to 1 s), `flush()` and `close()`. On first run with no playlists it creates `"Default"`.
  - `FoobarImport`:
    - `static Path defaultProfile()` → `%APPDATA%\foobar2000-v2`;
    - `static List<Path> folders(Path profile)`;
    - `static List<ImportedPlaylist> playlists(Path profile)`;
    - `record ImportedPlaylist(String name, List<TrackRef> items)`.
- Formats:
  - Each playlist file `<uuid>.txt` (UTF-8, no BOM, atomic write) has `#name:<name>` on line 1, then one `TrackRef.toUri()` per line.
  - `index.txt` holds one UUID per line in tab order.
  - foobar's `library-v2.0\folders` is binary with length prefixes: extract with the regex `file://([^\x00-\x1F]+?)(?=[\x00-\x1F]|$)` and strip any trailing `\`.
  - foobar's `playlists-v2.0\index.txt` lines are `GUID:Name` (UTF-8 BOM tolerated). The matching `playlist-<GUID>.fplite` holds one `file://` URL per line (BOM and CRLF tolerated). An optional foobar `N+` prefix (`3+file://…iso`) maps to subsong N (§8).

- [ ] **Step 1: Write the failing tests.**
  - `PlaylistStoreTest`:
    - `saveLoadRoundTrip` (a Unicode name, and items with `#` and an ISO subsong).
    - `autosaveDebounced` (two changes within 1 s → one write).
    - `reorderPersists`.
    - `defaultCreatedOnFirstRun`.
  - `FoobarImportTest`:
    - `foldersParsed`: synthetic bytes with three entries → `C:\Users\me\Music`, `C:\Users\me\OneDrive\Music`, `E:\Music`.
    - `fplitePlaylistParsed`.
    - `realProfileReadOnly` (`@Tag("library")`): reads the real profile, gets 3 folders and a non-empty `"Default Playlist"`, and asserts that the profile files' mtimes are unchanged afterwards.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Commit.** `feat(playlist): playlist store and read-only foobar2000 import`

### Task 28: Browse view (album grid, artists, folders, search)

**Files:**
- Create: `src/main/java/purist/ui/browse/{BrowseView,AlbumGrid,ArtistList,FolderTree}.java`, `ui/kit/{PillTabs,TrackTableModel}.java`
- Modify: `ui/MainWindow.java` (add BROWSE to the rail; Ctrl+1)
- Test: `src/test/java/purist/ui/kit/TrackTableModelTest.java`, `ui/browse/AlbumGridModelTest.java`

**Interfaces:**
- Consumes: Tasks 22, 23, 25 and 26.
- Produces:
  - `TrackTableModel(List<Column> columns)`, where `enum Column { PLAYING, NUMBER, TITLE, ARTIST, ALBUM, LENGTH, FORMAT }`, with `setRows(List<TrackInfo>)`, `TrackInfo row(int)`, `setPlaying(TrackRef)` and `boolean isUnsupported(int row)`.
  - `PillTabs(String... names)`, with `onSelect(IntConsumer)`.
  - `BrowseView(LibraryDb, ThumbnailCache, LibraryService, Consumer<AlbumSummary> openAlbum, BiConsumer<List<TrackRef>, Integer> play, Consumer<List<TrackRef>> addToQueue)`.
- Behaviour (§9, view 1):
  - **Search:** a field with placeholder `artist HAS nugent`, debounced 250 ms, then `QueryParser` → `SqlBuilder` → `db.tracks`. Results show in a `TrackTableModel` table while the query is non-empty. A `QueryException` shows inline in `TEXT2`.
  - **Albums:** a `JList<AlbumSummary>` with `HORIZONTAL_WRAP` and fixed 176×230 cells. Each cell draws its thumbnail (placeholder until loaded, then repainted), the album at 13 px and the artist at 11 px in `TEXT2`. Double-click or Enter opens the album. The context menu has `PLAY` and `ADD TO QUEUE`.
  - **Artists:** a list; selecting an artist filters the grid.
  - **Folders:** a lazy `JTree` over the watched folders; selecting a directory lists its tracks.
  - **Scan progress:** a 2 px `ACCENT` line at the top while `LibraryListener.scanProgress` reports work.

- [ ] **Step 1: Write the failing tests.**
  - `TrackTableModelTest.valuesAndFormatting`: LENGTH `"4:20"`, FORMAT `"FLAC 24/96"`, `isUnsupported` true for MP3 rows.
  - `AlbumGridModelTest.sortsByAlbumArtistThenAlbumIgnoringCase`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.** Then a visual check: run the app, screenshot the Browse view with the computer-use tools, and compare it with the §9 layout.
- [ ] **Step 3: Commit.** `feat(ui): Browse view with album-art grid, artists, folders and search`

### Task 29: Album page and ambient background

**Files:**
- Create: `src/main/java/purist/ui/album/AlbumView.java`, `ui/kit/{AmbientBackground,AmbientPanel}.java`
- Modify: `ui/PlaybackView.java` (use `AmbientPanel`)
- Test: `src/test/java/purist/ui/kit/AmbientBackgroundTest.java`

**Interfaces:**
- Produces:
  - `AmbientBackground.render(BufferedImage cover, int w, int h) -> BufferedImage`:
    1. Scale to 64 px.
    2. Box blur 3× (radius 6).
    3. Convert to greyscale (luma `0.299/0.587/0.114`).
    4. Draw at `w×h` with alpha 0.18 over `#F4F4F4`.
    5. Apply a vertical gradient over the bottom 40 % to `#F4F4F4`.
  - `AmbientPanel` caches the result per cover identity and size.
  - `AlbumView(…)`, with `show(AlbumSummary, List<TrackInfo> tracks)`. Layout per §9 view 2: `SectionLabel("ALBUM")`, cover 240, title 28 px, artist 18 px, meta `"1982 · 9 tracks · DSD128"`, the Pills `PLAY`/`ADD TO QUEUE`, and a track table (NUMBER, TITLE, LENGTH, FORMAT) where double-clicking plays from that row.

- [ ] **Step 1: Write the failing tests.**
  - `ambientIsGreyscale`: a pure red cover → every pixel has `|r−g|≤1` and `|g−b|≤1`.
  - `ambientBlendsToBackgroundAtBottom`: the bottom row is within ±2 of `#F4F4F4`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.** Then screenshot the Album page.
- [ ] **Step 3: Commit.** `feat(ui): album page with greyscale ambient background`

### Task 30: Queue view (playlist tabs) wired to the player

**Files:**
- Create: `src/main/java/purist/ui/queue/{QueueView,QueueReorder}.java`
- Modify: `ui/MainWindow.java` (add QUEUE to the rail; Ctrl+2; `PLAY` = replace the current playlist and play from the index; `ADD TO QUEUE` = append), `ui/PlaybackView.java` (open files append to the current playlist)
- Test: `src/test/java/purist/ui/queue/QueueReorderTest.java`

**Interfaces:**
- Consumes: Tasks 14, 27 and 28.
- Produces:
  - `QueueReorder.move(List<T> list, int[] indices, int to) -> List<T>`.
  - `QueueReorder.adjustPlaying(int playing, int[] removed) -> int`.
  - `QueueView(PlaylistStore, Player, Settings)`.
- Behaviour (§9, view 3):
  - **Tabs:** pill tabs plus a `+` button that creates `"Playlist N"`; right-click to rename or delete.
  - **Table columns:** PLAYING (`▶`), NUMBER, TITLE, ARTIST, ALBUM, LENGTH, FORMAT. Column widths persist under `queue.columns`.
  - **Editing:** drag to reorder (through `QueueReorder`), Delete to remove.
  - **Playback controls:** a repeat Pill cycling `REPEAT OFF/TRACK/ALL` and a `SHUFFLE` toggle. Double-click plays from that row, and the selection follows the playing row.
  - The current playlist's items are kept in `Player.setQueue`.

- [ ] **Step 1: Write the failing tests.**
  - `moveIndicesReorders`: `[a,b,c,d,e]`, move `{1,3}` to 0 → `[b,d,a,c,e]`.
  - `deleteAdjustsPlayingIndex`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.** Then a visual check (screenshot).
- [ ] **Step 3: Commit.** `feat(ui): Queue view with playlist tabs`

### Task 31: Settings → Library, saved window state and first-run flow

**Files:**
- Create: `src/main/java/purist/ui/WindowState.java`
- Modify: `ui/SettingsView.java` (Library and About sections), `ui/MainWindow.java` (save and restore state), `app/Main.java` (start `LibraryService`; first-run flow)
- Test: `src/test/java/purist/ui/WindowStateTest.java`

**Interfaces:**
- Produces:
  - `WindowState.parse(String "x,y,w,h") -> Optional<Rectangle>`.
  - `WindowState.format(Rectangle)`.
  - `WindowState.clampToScreen(Rectangle, Rectangle screen)`.
- **Settings → Library:**
  - a folder list with the Pills `ADD FOLDER…` and `REMOVE`, plus `RESCAN`;
  - `IMPORT FROM FOOBAR2000`, which shows a confirm dialog listing the folders and playlist names it found before importing (the import never touches foobar's files);
  - the scan progress text;
  - the last 20 lines of `scan-errors.log`.
- **About:** `"Purist 0.1.0 · GPL-3.0-or-later · ASIO is a trademark of Steinberg Media Technologies GmbH"`.
- **Saved state:** the window bounds, last view and column widths are saved on close and restored on start.
- **First run** (no library folders): open Settings → Library with the hint `"Add your music folders, or import them from foobar2000."`.

- [ ] **Step 1: Write the failing tests.**
  - `WindowStateTest.roundTrip`.
  - `badStringEmpty`.
  - `offScreenClamped`.
- [ ] **Step 2: Run, confirm they fail, implement, then run again and confirm they pass.**
- [ ] **Step 3: Run the end-to-end check.**
  1. Start with an empty `-Dpurist.profile`.
  2. Import from foobar2000 (3 folders and the Default Playlist).
  3. The scan completes. Record the track count and elapsed time in `docs/verification/M3.md`.
  4. Restart. The incremental rescan with no changes must finish in under 10 s.
- [ ] **Step 4: Commit.** `feat(ui): library settings, foobar import, saved window state`

### Task 32: Packaging and final verification

**Files:**
- Create: `scripts/package.ps1`, `scripts/install.ps1`, `docs/verification/M3.md` (complete it)

**Interfaces:**
- Consumes: everything above.
- **`package.ps1`:**
  1. `mvn -q -DskipTests package`, then copy the jar into `target/lib`.
  2. `jpackage --type app-image --name Purist --app-version 0.1.0 --input target/lib --main-jar purist-0.1.0-SNAPSHOT.jar --main-class purist.app.Main --java-options "-XX:+UseZGC" --java-options "--enable-native-access=ALL-UNNAMED" --dest target/dist`.
- **`install.ps1`:**
  1. Copy `target/dist/Purist` to `%LOCALAPPDATA%\Programs\Purist`.
  2. Create `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Purist.lnk` via `WScript.Shell`.

  The user must run it from their own PowerShell, not one spawned by Claude: Claude-spawned processes get new `%LOCALAPPDATA%`/`%APPDATA%` folders redirected into the MSIX sandbox. The script prints that requirement at the top.

- [ ] **Step 1: Write both scripts.** Run `scripts\package.ps1`. Expected: `target\dist\Purist\Purist.exe` exists and starts.
- [ ] **Step 2: Ask the user to run `scripts\install.ps1`** from their own terminal, then confirm Purist appears in the Start menu and launches.
- [ ] **Step 3: Final verification against §1**, recorded in `M3.md`:
  1. All formats play on the Topping with the native rate shown on the DAC.
  2. `mvn test` is green; the `library` and `topping` groups are green; the FLAC check is 1,204/1,204.
  3. Gapless across an album.
  4. Library scan and search are responsive.
  5. One hour of playback while using the UI ends with the dropout counter at 0.
- [ ] **Step 4: Commit.** `build: jpackage app image, installer script, final verification`
