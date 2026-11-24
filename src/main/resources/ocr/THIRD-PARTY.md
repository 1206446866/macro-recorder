# Offline OCR components

This directory includes unmodified upstream Windows x64 binaries and language data:

- Tesseract 5.3.4 (`libtesseract534.dll`), from [Tess4J 5.11.0](https://github.com/nguyenq/tess4j/releases/tag/tess4j-5.11.0), Apache License 2.0.
- Leptonica 1.84.1 (`libleptonica1841.dll`), from the rebuilt Windows binaries in [Lept4J 1.19.1](https://github.com/nguyenq/lept4j/releases/tag/lept4j-1.19.1), Leptonica BSD license. The distribution project also provides its Apache License 2.0.
- `tessdata_fast` models: simplified Chinese, traditional Chinese, English, Japanese and Korean, Apache License 2.0.

See `licenses/` for the bundled license texts and `manifest.json` for exact pinned sources, byte sizes, SHA-256 checksums and Git blob identities. The application calls the native C interface using its existing JNA dependency; Tess4J and Lept4J Java libraries are not bundled as additional dependencies.

## Components in the Leptonica Windows binary

The pinned `libleptonica1841.dll` contains libpng 1.6.40 and LibTIFF 4.6.0 version identifiers. Their upstream copyright and license texts are bundled as [libpng-LICENSE.txt](licenses/libpng-LICENSE.txt) and [libtiff-LICENSE.md](licenses/libtiff-LICENSE.md).

The binary also contains references associated with JPEG, WebP and OpenJPEG. The pinned distributors' release notes and source trees do not identify the full set of components compiled into these DLLs or their exact build versions. The full third-party notice for these binaries remains pending. Obtain the build manifest and original notices from the distributor, or use a reproducible build with a verified dependency and license inventory before public redistribution.

Upstream projects:

- https://github.com/tesseract-ocr/tesseract
- https://github.com/DanBloomberg/leptonica
- https://github.com/nguyenq/tess4j
- https://github.com/nguyenq/lept4j
- https://github.com/tesseract-ocr/tessdata_fast
