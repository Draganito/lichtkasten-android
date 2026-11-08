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
- Tap opens the viewer: swipe between pictures, pinch / double-tap to zoom,
  videos play inline (Media3, the codecs the system has).
- **Select** (or long-press) for multi-select; **All** / **None**; share and
  permanently delete the selection. Sharing asks "Share N files?" first;
  deleting goes through the system's own confirmation. There is no trash.
- **Info** shows file facts from MediaStore plus EXIF, IPTC and XMP read by
  the Rust core.
- TIFF (8/16-bit, LZW, Deflate) decodes through the Rust core. RAW files
  (NEF, CR2, CR3, ARW, DNG, RAF, …) show the JPEG preview the camera embeds,
  which is full-size on most cameras; a RAW without one is reported as
  unsupported (no demosaicing on the phone, see *Third-party software*).
  Everything else goes through Android's own hardware-backed decoders.
- English and German, following the system language.

## Privacy and safety

- No `INTERNET` permission. No accounts, no analytics, no crash reporting.
- Read access only (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`; Android 14's
  "selected photos only" is supported). Writes happen only through the
  system delete dialog.
- Backup is off. The only stored state is the grid size (SharedPreferences)
  and an app-private cache of thumbnails for TIFF/RAW, which the system may
  clear at any time.
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
builds it on GitHub Actions and attaches the APK to a release; put the
signing key into the `KEYSTORE_*` secrets, otherwise the debug key is used.

## License

MIT or Apache-2.0, at your option.

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
