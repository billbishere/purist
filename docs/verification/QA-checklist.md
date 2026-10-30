# Release QA — everything must pass before the .msi is built

The user's requirement (2026-10-26): "go through and test EVERYTHING when complete before compiling the .msi".
Record a result, the date and evidence (a screenshot, log line or test output) next to every line. A failure
blocks packaging until it is fixed and re-tested.

## 1. Automated
- [x] `mvn test` — the full default suite, green. 2026-10-27 @ 60693a1: 1231 run, 0 failures, 0 errors (log noise is
      expected warnings from failure-path tests).
- [~] `mvn test "-Dtest.excluded=" -Dgroups=hardware` — ASIO (Volt), WASAPI (Volt), the Everything IT. 2026-10-27:
      ASIO Volt, COM ASIO, driver list: pass. WASAPI Volt: pass at 48 kHz bit-perfect, 0 dropouts (c9616eb: the IT now
      follows the Volt's clock rate; the UA driver had left it at 48 kHz). Everything IT: D:\ pass (81,205 files in
      966 ms; walk = Everything on a synced folder); **E:\ FAILS — environment, not code: the user's Everything
      does not index E:** (enable it under Everything → Tools → Options → Indexes → NTFS, then re-run). Purist
      falls back to a folder walk for E: with a notice, which §5 checks.
- [x] `mvn test "-Dtest.excluded=" -Dgroups=topping` — Topping ASIO, including native DSD. The group was empty until
      c9616eb added AsioSinkToppingIT. 2026-10-27 on the D50: 16/44.1, 24/96, 24/192 → INT32_LSB at the source rate;
      DSD64 and DSD128 → native DSD_INT8_MSB1; every format played all frames, 0 dropouts, 0 callback errors.
- [x] `mvn test "-Dtest.excluded=" -Dgroups=library` — real files, read-only: the FLAC MD5 gate, DSF, SACD ISO, the
      foobar import. 2026-10-27: 9 run, 0 failures.

## 2. Playback on the Topping D50 — ASIO
All of §2–7: 2026-10-27, user at the D50, hands-on with `run.cmd` @ ea0a996 (round 1 = §2 + drives, round 2 = §3–4, round 3 = §5–7, every round reported passing). Also confirmed by hand: Space works without touching the search box, the output switches from the open list, window/view/playlist restore after a drag and restart, and closing during a rescan exits cleanly.
- [x] WAV 16/44.1, WAV 24/96 (no RF64 in the library)
- [x] AIFF (the Manowar album)
- [x] FLAC 16/44.1, 24/96 and 24/192
- [x] DSF DSD128 (Thriller), plus DSD64 if present — native DSD, BIT-PERFECT
- [~] DFF — none in the library; covered by the DFF decoder unit tests only
- [~] CUE sheets (added 2026-10-30). Automated: `CueHandlerTest` and `ScannerTest`. Real files, read-only: E:'s
      Yoshio Kimura WAV image probes as 18 tracks, and the decoded tracks together hash identical to the whole
      file (172,783,800 frames). D:'s nine per-track sheets and one orphaned sheet add no rows. Not yet heard by hand
      on the D50: play across a track boundary of the image, seek inside a track, and open a .cue with Open with.
- [x] SACD ISO (Ted Nugent) — every track starts, and the ISO#n tracks show in the queue
- [x] Gapless between tracks of one album at the same format
- [x] A rate change between albums (44.1 → 96 → DSD) — a short gap only, no glitch
- [x] Pause and resume, and seek by click and by the arrow keys — 0 dropouts
- [x] Next/previous, and previous within 3 s restarts the track
- [x] Repeat and shuffle
- [x] A damaged file (one of the 26 known-bad FLACs) is skipped with a reason, and the next track plays
- [x] An unsupported file (MP3) shows greyed out and is skipped with "MP3 is not supported in v1"

## 3. Playback — WASAPI exclusive, on the D50 and on the Volt
- [x] PCM 16/44.1 and 24/96 — BIT-PERFECT
- [x] A 32-bit file after a 24-bit one is never truncated: it reopens, or is refused with a reason
- [x] DSD with DoP off is refused with the opt-in message
- [x] DSD with DoP on, on the D50 — plays as DSD; the D50 display shows DSD
- [x] Switching outputs while playing (ASIO ↔ WASAPI) — no crash, no stuck banner

## 4. Device handling
- [x] Start with the DAC off → banner and auto-retry → switch it on → playback starts
- [x] Unplug or switch off mid-track → banner → back on → it resumes
- [x] STOP while the banner is showing cancels the retry
- [x] OPEN DRIVER PANEL opens the Topping panel without freezing the window; a buffer-size change counts a dropout
- [x] The output list refreshes when Settings opens; it can't be changed by keyboard by accident

## 5. Library
- [x] Drives: tick D: then E: — they scan one at a time, with progress shown
- [x] Everything used for D:; E: (not indexed by the user's Everything) took the folder walk with its notice. The first import of E: is slow (walk + header probe of every file on a spinning disk); profiled the search SQL at 7–12 ms on 7,011 tracks, so search itself is not the cost
- [x] A rescan of an unchanged drive is fast, with no re-probing
- [x] Add, rename and delete a file in a watched folder → the library updates live
- [~] Cloud-only OneDrive files are never downloaded — not exercised by hand (no placeholder in the scanned drives); covered by ScannerTest/FileAttributesTest
- [x] Albums grid: every album is reachable by scrolling; covers load; placeholders show for missing art
- [x] Artists list, then an artist's albums; the Folders tree
- [x] Search: `jackson`, `artist:jackson`, `year:1982`, `format:dsd`, a phrase in quotes — the results update
- [x] Album panel: PLAY replaces the queue, + QUEUE appends, double-click plays from that track

## 6. Queue and playlists
- [x] Playlist tabs: add, rename, switch, delete
- [x] Drag to reorder; Delete removes; the playing marker stays correct
- [x] The foobar import (read-only) brings in the folders and Default Playlist; foobar's files are unchanged

## 7. UI and settings
- [x] The three skins (Paper LCD, Phosphor, Ink) — every view readable, switching while playing
- [~] Five more skins (Midnight, Crimson, Ocean Neon, Amethyst, Ebony & Ivory), added 2026-10-29: every skin loads,
  its dark flag matches the chassis, and text, LCD, highlight and button contrast are at least 4.5:1
  (`PuristThemeTest`). Queue, Albums, Artists and Settings were captured and look right. Switching while playing
  wasn't tried by hand.
- [x] Non-Latin tags (for example Japanese) show correctly
- [x] Tags that look like HTML show as plain text
- [x] Every keyboard shortcut in every view
- [x] Window size, position, last view and playlist restored after a restart
- [x] First run with an empty profile → the Drives hint
- [~] Toasts don't pile up (seen in use); purist.log rotation not exercised by hand — JUL FileHandler size/count rotation in AppLog

## 8. Installer (after sections 1–7 pass)
Built 2026-10-28 by `scripts\package.ps1` (jpackage app image + WiX 7, `packaging\Purist.wxs`):
`target\dist\Purist-0.1.0.msi`, 54 MB, bundled runtime.
- [x] The .msi installs per user without admin (`msiexec /qn` exit 0) into the real `%LOCALAPPDATA%\Programs\Purist`
      (not the MSIX sandbox), with a Start-menu shortcut; the installed Purist.exe launches. Start-menu launch confirmed by the
      user 2026-10-28.
- [x] Open-with for .flac/.dsf/.iso: `.flac/.wav/.aif/.aiff/.aifc/.dsf/.dff/.iso` get `OpenWithProgids` → `Purist.Audio`
      (`"Purist.exe" "%1"`); launching that command with a 24/192 FLAC opens Purist. `.iso`'s default stays Windows'
      (never made default). Explorer right-click Open with → Purist confirmed by the user 2026-10-28.
- [x] Upgrade 0.1.0 → 0.1.1 (test build): exit 0, one Purist product left at 0.1.1, `~\.purist` settings and library
      kept.
- [x] App icon (2026-10-29, test build 0.1.3): `AppIcon` draws it, `scripts\package.ps1` writes `target\purist.ico`
      for jpackage. The installed Purist.exe carries it (the shortcut and Add/Remove Programs read it from there),
      and the window title bar shows it rather than Java's cup. Upgrade 0.1.2 → 0.1.3: exit 0.
- [x] Uninstall: exit 0; the install folder, shortcut, ProgId, Open-with values and `HKCU\Software\Purist` are all
      gone; `~\.purist` (library.db, settings) is kept. 0.1.0 reinstalled afterwards.
