//! The path Android takes: write a file, hand over its descriptor, get
//! pixels and metadata back.

use std::io::{Seek, SeekFrom, Write};
use std::os::fd::IntoRawFd;

use image::{ImageEncoder, Rgb, RgbImage};
use lichtkasten_mobile::{DecodeError, decode_image, read_metadata};

fn tiff_bytes(w: u32, h: u32) -> Vec<u8> {
    let mut img = RgbImage::new(w, h);
    for (x, y, p) in img.enumerate_pixels_mut() {
        *p = Rgb([x as u8, y as u8, 123]);
    }
    let mut out = std::io::Cursor::new(Vec::new());
    image::codecs::tiff::TiffEncoder::new(&mut out)
        .write_image(img.as_raw(), w, h, image::ExtendedColorType::Rgb8)
        .unwrap();
    out.into_inner()
}

fn fd_with(bytes: &[u8]) -> i32 {
    let mut f = tempfile::tempfile().unwrap();
    f.write_all(bytes).unwrap();
    f.seek(SeekFrom::Start(0)).unwrap();
    f.into_raw_fd()
}

#[test]
fn tiff_over_fd() {
    let fd = fd_with(&tiff_bytes(200, 100));
    let img = decode_image(fd, "DSC_0001.tif".into(), 50).unwrap();
    assert_eq!((img.width, img.height), (50, 25));
    assert_eq!(img.pixels.len(), 50 * 25 * 4);
    assert_eq!(img.pixels[3], 255, "alpha opaque");
}

#[test]
fn tiff_metadata_over_fd() {
    let fd = fd_with(&tiff_bytes(8, 8));
    let meta = read_metadata(fd).unwrap();
    // The encoder writes ImageWidth/ImageHeight; little_exif reports them.
    assert!(meta.exif.iter().any(|r| r.key == "ImageWidth" && r.value == "8"), "{:?}", meta.exif);
    assert!(meta.captured.is_none());
}

#[test]
fn garbage_over_fd_is_an_error() {
    let fd = fd_with(&[0u8; 1000]);
    let err = decode_image(fd, "x.tif".into(), 100).unwrap_err();
    assert!(matches!(err, DecodeError::Unsupported { .. } | DecodeError::Corrupt { .. }), "{err:?}");
    let fd = fd_with(b"II*\0\x08\0\0\0\xff\xff\xff\xff\xff\xff\xff\xff");
    assert!(decode_image(fd, "x.tif".into(), 100).is_err());
}

#[test]
fn raw_hint_on_non_raw_is_unsupported() {
    let fd = fd_with(&tiff_bytes(8, 8));
    let err = decode_image(fd, "x.nef".into(), 100).unwrap_err();
    assert!(matches!(err, DecodeError::Unsupported { .. }), "{err:?}");
}
