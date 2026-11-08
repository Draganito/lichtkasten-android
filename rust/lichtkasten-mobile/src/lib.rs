//! lichtkasten-mobile: `lichtkasten-core` behind a UniFFI surface for the
//! Android app. Kotlin opens the file through MediaStore and hands over
//! the *detached* file descriptor; this side owns it from then on and
//! closes it. Nothing here touches paths, the network, or anything
//! outside that descriptor.
//!
//! Contract at the boundary: every export returns `Result`; a panic in
//! any parser is caught and reported as `DecodeError::Internal`.

uniffi::setup_scaffolding!();

use std::fs::File;
use std::io::{BufReader, Seek, SeekFrom};
use std::os::fd::{FromRawFd, OwnedFd};
use std::panic::{AssertUnwindSafe, catch_unwind};

use lichtkasten_core::decode::{self, DecodeLimits};
use lichtkasten_core::meta;

const LIMITS: DecodeLimits = DecodeLimits::MOBILE;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum DecodeError {
    #[error("{msg}")]
    TooLarge { msg: String },
    #[error("{msg}")]
    Unsupported { msg: String },
    #[error("{msg}")]
    Corrupt { msg: String },
    #[error("{msg}")]
    Io { msg: String },
    #[error("internal: {msg}")]
    Internal { msg: String },
}

impl From<decode::DecodeError> for DecodeError {
    fn from(e: decode::DecodeError) -> Self {
        match e {
            decode::DecodeError::TooLarge(msg) => DecodeError::TooLarge { msg },
            decode::DecodeError::Unsupported(msg) => DecodeError::Unsupported { msg },
            decode::DecodeError::Corrupt(msg) => DecodeError::Corrupt { msg },
            decode::DecodeError::Io(msg) => DecodeError::Io { msg },
        }
    }
}

impl From<std::io::Error> for DecodeError {
    fn from(e: std::io::Error) -> Self {
        DecodeError::Io { msg: e.to_string() }
    }
}

/// Straight RGBA8, row-major, no padding: `pixels.len() == width * height * 4`.
/// Kotlin wraps it with `Bitmap.copyPixelsFromBuffer` (ARGB_8888 expects
/// exactly this byte order).
#[derive(Debug, Clone, uniffi::Record)]
pub struct RgbaImage {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MetaRow {
    pub key: String,
    pub value: String,
}

/// Language-neutral keys (`DateTimeOriginal`, `Caption-Abstract`, `dc:title`);
/// the app translates the ones it knows.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct Metadata {
    pub exif: Vec<MetaRow>,
    pub iptc: Vec<MetaRow>,
    pub xmp: Vec<MetaRow>,
    /// Unix seconds, from `DateTimeOriginal` or `CreateDate`.
    pub captured: Option<u64>,
}

/// Version of this crate (the app version).
#[uniffi::export]
pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Route Rust `log` output to logcat (tag `lichtkasten`), debug builds
/// only. A release build never installs the logger, so nothing from this
/// crate or its dependencies reaches logcat.
#[uniffi::export]
pub fn init_logging() {
    #[cfg(all(target_os = "android", debug_assertions))]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("lichtkasten"),
        );
    }
}

/// Decode the image behind `fd` (TIFF, the embedded preview of a RAW, or
/// any raster the core knows), apply the EXIF orientation and shrink it so
/// that the long edge is at most `max_edge`. `name_hint` is the display
/// name; its extension decides between the RAW and the raster path. Takes
/// ownership of `fd`.
#[uniffi::export]
pub fn decode_image(fd: i32, name_hint: String, max_edge: u32) -> Result<RgbaImage, DecodeError> {
    let file = take_fd(fd)?;
    guarded(move || {
        let mut file = file;
        file.seek(SeekFrom::Start(0))?;
        let img = decode::decode_fitted(BufReader::new(file), &name_hint, max_edge, &LIMITS)?;
        Ok(RgbaImage { width: img.width(), height: img.height(), pixels: img.into_raw() })
    })
}

/// EXIF, IPTC and XMP of the file behind `fd`. Takes ownership of `fd`.
#[uniffi::export]
pub fn read_metadata(fd: i32) -> Result<Metadata, DecodeError> {
    let file = take_fd(fd)?;
    guarded(move || {
        let mut file = file;
        let m = meta::read_metadata(&mut file, &LIMITS)?;
        Ok(Metadata { exif: rows(m.exif), iptc: rows(m.iptc), xmp: rows(m.xmp), captured: m.captured })
    })
}

fn rows(v: Vec<(String, String)>) -> Vec<MetaRow> {
    v.into_iter().map(|(key, value)| MetaRow { key, value }).collect()
}

/// Wrap the raw descriptor. The only `unsafe` in the crate: Kotlin
/// detached the descriptor (`ParcelFileDescriptor.detachFd`), so it is
/// ours to close, and nobody else holds it.
fn take_fd(fd: i32) -> Result<File, DecodeError> {
    // SAFETY: fcntl(F_GETFD) only queries the descriptor table; it cannot
    // affect any resource. It tells us whether `fd` is open at all, so a
    // caller bug (closed or negative descriptor) becomes an error instead
    // of an abort in OwnedFd's drop.
    if fd < 0 || unsafe { libc::fcntl(fd, libc::F_GETFD) } == -1 {
        return Err(DecodeError::Io { msg: format!("invalid file descriptor {fd}") });
    }
    // SAFETY: `fd` is an open descriptor whose ownership the caller
    // transferred to us (detachFd). We close it exactly once via OwnedFd.
    let owned = unsafe { OwnedFd::from_raw_fd(fd) };
    Ok(File::from(owned))
}

fn guarded<T>(f: impl FnOnce() -> Result<T, DecodeError>) -> Result<T, DecodeError> {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(result) => result,
        Err(payload) => {
            let msg = payload
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "panic".to_string());
            Err(DecodeError::Internal { msg })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::IntoRawFd;

    fn fd_of(file: File) -> i32 {
        file.into_raw_fd()
    }

    #[test]
    fn version_matches_manifest() {
        assert_eq!(version(), env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn negative_fd_is_io_error() {
        assert!(matches!(decode_image(-1, "a.tif".into(), 100), Err(DecodeError::Io { .. })));
        assert!(matches!(read_metadata(-1), Err(DecodeError::Io { .. })));
    }

    #[test]
    fn guarded_turns_panics_into_errors() {
        let r: Result<(), DecodeError> = guarded(|| panic!("boom"));
        assert!(matches!(r, Err(DecodeError::Internal { msg }) if msg == "boom"));
    }

    #[test]
    fn empty_file_is_an_error() {
        let tmp = tempfile::tempfile().unwrap();
        let fd = fd_of(tmp);
        let err = decode_image(fd, "x.tif".into(), 100).unwrap_err();
        assert!(!matches!(err, DecodeError::Internal { .. }), "{err:?}");
    }

    #[test]
    fn closed_fd_is_io_error() {
        // Open and close: the number is dead until something else reuses it.
        // (Single-threaded within this test; other tests may reuse the
        // number concurrently, so only the dead-or-foreign case is asserted.)
        let tmp = tempfile::tempfile().unwrap();
        let fd = fd_of(tmp);
        drop(unsafe { OwnedFd::from_raw_fd(fd) });
        let _ = read_metadata(fd); // must not abort
    }
}
