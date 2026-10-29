# Offline OCR components

This directory includes unmodified upstream Windows x64 binaries and language data:

- Tesseract 5.3.4 (`libtesseract534.dll`), distributed by Tess4J, Apache License 2.0.
- Leptonica 1.84.1 (`libleptonica1841.dll`), distributed by Lept4J, Leptonica BSD license; the distribution project also provides its Apache License 2.0.
- `tessdata_fast` models: simplified Chinese, traditional Chinese, English, Japanese and Korean, Apache License 2.0.

See `licenses/` for the bundled license texts and `manifest.json` for exact pinned sources, byte sizes, SHA-256 checksums and Git blob identities. The application calls the native C interface using its existing JNA dependency; Tess4J and Lept4J Java libraries are not bundled as additional dependencies.

Upstream projects:

- https://github.com/tesseract-ocr/tesseract
- https://github.com/DanBloomberg/leptonica
- https://github.com/nguyenq/tess4j
- https://github.com/nguyenq/lept4j
- https://github.com/tesseract-ocr/tessdata_fast
