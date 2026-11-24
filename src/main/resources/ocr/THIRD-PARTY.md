# Offline OCR components

The OCR runtime uses unmodified upstream Windows x64 binaries and language data:

- Tesseract 5.3.4 (`libtesseract534.dll`), from [Tess4J 5.11.0](https://github.com/nguyenq/tess4j/releases/tag/tess4j-5.11.0), Apache License 2.0.
- Leptonica 1.84.1 (`libleptonica1841.dll`), from the rebuilt Windows binaries in [Lept4J 1.19.1](https://github.com/nguyenq/lept4j/releases/tag/lept4j-1.19.1), Leptonica BSD license. The distribution project also provides its Apache License 2.0.
- `tessdata_fast` models: simplified Chinese, traditional Chinese, English, Japanese and Korean, Apache License 2.0.

See `licenses/` for the license texts and `manifest.json` for pinned sources, byte sizes, SHA-256 checksums and Git blob content identities. A `sourceMember` identifies the original file inside a source archive. A `sourceLines` range identifies an excerpt containing the original attribution. Maven resolves the two upstream JARs, verifies their digests, and places only the verified DLLs in the application JAR. The application's JNA calls and offline runtime use the same native interface.

## Components in the Leptonica Windows binary

On Windows x64, the bundled DLL's `getLeptonicaVersion()` reports Leptonica 1.84.1, built on March 1, 2024 with MSVC 19.39. Its `getImagelibVersions()` reports the following seven libraries. The DLL import table does not list these codecs as separate DLLs.

| Library | Bundled upstream terms |
| --- | --- |
| GIFLIB 5.2.1 | [MIT license](licenses/giflib-LICENSE.txt) and [attribution for the upstream OpenBSD `reallocarray` compatibility file](licenses/giflib-reallocarray-NOTICE.txt) |
| IJG libjpeg 9e | [Independent JPEG Group terms](licenses/libjpeg-README.txt) |
| libpng 1.6.40 | [PNG Reference Library license](licenses/libpng-LICENSE.txt) |
| LibTIFF 4.6.0 | [LibTIFF license](licenses/libtiff-LICENSE.txt) |
| zlib 1.3 | [zlib license](licenses/zlib-LICENSE.txt) |
| libwebp 1.3.2 | [BSD license](licenses/libwebp-LICENSE.txt) and [patent grant](licenses/libwebp-PATENTS.txt) |
| OpenJPEG 2.5.0 | [BSD 2-Clause license](licenses/openjpeg-LICENSE.txt) |

This software is based in part on the work of the Independent JPEG Group.

Lept4J last updated this DLL in [its March 2024 rebuild](https://github.com/nguyenq/lept4j/commit/aff78553dd3f90889c98481e99d190d214d28bf6). Tesseract's `TessVersion()` reports 5.3.4, and its Windows import table lists the bundled Leptonica DLL, Microsoft C/C++ runtime and Windows system libraries. Tess4J last updated this DLL in [its Tesseract 5.3.4 update](https://github.com/nguyenq/tess4j/commit/105d7a48f32e1479bbc97e99abddba0af1781466).

The binary providers' source trees do not ship a build manifest or linker map for these two DLLs. The version interfaces establish the seven Leptonica components listed above. A complete inventory of optional or nested static libraries, including any separately enabled LibTIFF codecs, remains pending.

Upstream projects:

- https://github.com/tesseract-ocr/tesseract
- https://github.com/DanBloomberg/leptonica
- https://github.com/nguyenq/tess4j
- https://github.com/nguyenq/lept4j
- https://github.com/tesseract-ocr/tessdata_fast
