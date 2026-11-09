# Lichtkasten for Android

One stream of every photo and video on the phone. Tap to view, pinch to
resize the grid, select to share or delete. Nothing else.

*Ein Strom aller Fotos und Videos auf dem Telefon. Tippen zum Ansehen,
Pinch für die Rastergröße, Auswählen zum Teilen oder Löschen. Sonst nichts.*

The companion of the desktop [Lichtkasten](https://github.com/Draganito/lichtkasten),
which also provides the Rust core this app uses for TIFF, RAW and metadata.

## What it does

- Shows everything MediaStore indexes as image or video, across all folders
  (DCIM, Pictures, Download, SD card …), newest first. Updates itself when
  the library changes.
- Pinch on the grid changes the thumbnail size; the size is remembered.
- The grid is cut into months: a large, quiet heading ("November", "2026")
  before the first picture of each month, and while you scroll, the current
  month floats under the app bar.
- Tap opens the viewer: swipe between pictures, pinch / double-tap to zoom,
  videos play inline (Media3, the codecs the system has).
- **Crop & resize** in the viewer: free or fixed aspect (1:1, 4:3, 3:2,
  16:9, either orientation), drag corners, edges or the whole frame; output
  at original size or shrunk to 2048 / 1024 / 512 px on the long edge. The
  result is a *new* JPEG (quality 92) in `Pictures/Lichtkasten`; the
  original is never modified. The decode happens once, at the output size,
  so even a 50 MP source needs no more memory than the picture you keep.
- **Select** (or long-press) for multi-select; **All** / **None**; share and
  permanently delete the selection. Sharing asks "Share N files?" first;
  deleting goes through the system's own confirmation. There is no trash.
- **Info** shows file facts from MediaStore plus EXIF, IPTC and XMP read by
  the Rust core.
- TIFF (8/16-bit, LZW, Deflate) decodes through the Rust core. RAW files
  (NEF, CR2, CR3, ARW, DNG, RAF, …) show the JPEG preview the camera embeds,
  which is full-size on most cameras; a RAW without one is reported as
  unsupported (no demosaicing on the phone, see *Third-party software*).
  Everything else is decoded by the app itself (ImageDecoder, and a video
  keyframe for clips) into its own thumbnail cache; the viewer uses
  Android's decoders on the original file.
- English and German, following the system language.

## Privacy and safety

- No `INTERNET` permission. No accounts, no analytics, no crash reporting.
- Read access only (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`; Android 14's
  "selected photos only" is supported). Existing files are changed only
  through the system delete dialog. *Crop & resize* creates new files in the
  app's own folder `Pictures/Lichtkasten` (MediaStore insert, which needs no
  extra permission) and copies nothing from the original but its capture
  time: no location, no camera model, no serial numbers, no thumbnails.
- Backup is off. The only stored state is the grid size (SharedPreferences)
  and an app-private cache of thumbnails for every photo and video
  (`cacheDir/thumbs`, one 1024 px JPEG each, decoded by the app itself;
  MediaStore's thumbnail cache is never read). The system may clear it at
  any time, uninstalling removes it, and deleting a picture purges its
  preview at once.
- Release builds never write to logcat: Kotlin redacts what it logs, and
  the Rust library does not install its logger at all (`debug_assertions`).
  Debug builds log, including file names.
- The window is `FLAG_SECURE`: the system keeps no Recents snapshot of it,
  and screenshots, screen recording and casting of the app are blocked.
  That also means you cannot screenshot the viewer; the trade-off is
  intentional.
- The Rust side never panics across the boundary: every decoder runs under
  size limits (50 MP, 256 MB allocation, 200 MB file) and `catch_unwind`;
  the only `unsafe` is taking ownership of the file descriptor Kotlin hands over.
- Debug builds run with `StrictMode` set to crash on main-thread I/O.

## Build

Requirements: Rust stable with `aarch64-linux-android`, `cargo-ndk`, JDK 21,
Android SDK with NDK 27, and the desktop repository checked out next to this one:

```
Projekte/
  lichtkasten/           # Draganito/lichtkasten (provides crates/lichtkasten-core)
  lichtkasten-android/   # this repository
```

```bash
cd android
./gradlew assembleDebug        # builds Rust, generates UniFFI bindings, builds the APK
./gradlew testDebugUnitTest
adb install app/build/outputs/apk/debug/app-debug.apk
```

`lichtkasten-core.ref` holds the desktop commit CI checks out. The app
version is the one in `rust/lichtkasten-mobile/Cargo.toml`.

Release: `./gradlew assembleRelease` (minified, arm64-v8a only). A tag `v*`
builds it on GitHub Actions and attaches the APK to a release. With the
four `KEYSTORE_*` secrets it is signed with that key, otherwise with the
debug key.

## License

MIT, see [LICENSE](LICENSE). The shared Rust core in the desktop repository
is MIT OR Apache-2.0.

## Third-party software

Every dependency is under a permissive license (MIT, Apache-2.0, BSD, Zlib,
Unicode) or the MPL-2.0 (UniFFI, used unmodified). The Rust core is built
without its `rawler` feature, so no LGPL code is in the APK. The full list
with license texts is in [THIRD_PARTY.md](THIRD_PARTY.md) and under
*Licenses* in the app (⋮ menu). Both are generated; after a dependency
change run:

```bash
tools/third_party.py --manifest rust/lichtkasten-mobile/Cargo.toml \
    --target aarch64-linux-android --android \
    --md THIRD_PARTY.md --txt android/app/src/main/assets/licenses.txt
```
