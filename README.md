# pdfWirte

Android PDF Ink Annotation Demo — add hand-written ink annotations to a PDF and save them as standard PDF Ink Annotations.

## Features

- **Continuous vertical PDF viewing** using [AndroidPdfViewer 2.8.2](https://github.com/barteksc/AndroidPdfViewer)
  - `swipeHorizontal(false)`, `enableDoubletap(true)`, `spacing(8)`, `pageFling(false)`, `pageSnap(false)`
  - `fitToWidth()` called after initial render
- **Hand-written ink overlay** (`InkOverlayView`)
  - Free-hand drawing in red or blue, thin or thick
  - Undo / Redo (affects both on-screen display and the saved strokes)
  - Cross-page stroke splitting when drawing across page boundaries
- **Accurate coordinate mapping** (`AndroidPdfViewerCoordinateMapper`)
  - Maps overlay-view pixels → PDF MediaBox points
  - Handles scroll offset, per-page scale (different page sizes), and `/Rotate` (0/90/180/270)
- **Standard PDF Ink Annotation** output via [PdfBox-Android 2.0.27.0](https://github.com/TomRoush/PdfBox-Android)
  - Writes `PDAnnotationInk` with `InkList`, `BorderStyle`, `Color`, and bounding `Rectangle`
  - Saves to a new PDF and reloads it in the viewer

## Requirements

- Android Studio (Flamingo or later)
- Android API 24+ (minSdk 24)
- JitPack repository in `settings.gradle` (for AndroidPdfViewer)

## Project Structure

```
app/src/main/java/com/example/pdfwrite/
  PdfWriteApp.java                       Application — initialises PDFBoxResourceLoader
  MainActivity.java                      Host activity, PDF viewer + toolbar
  InkStroke.java                         Data model for one ink stroke
  InkOverlayView.java                    Transparent drawing layer on top of the PDF
  AndroidPdfViewerCoordinateMapper.java  View coords → PDF pt coords
  PdfInkWriter.java                      PdfBox-Android ink annotation writer

app/src/main/assets/sample.pdf          Two-page demo PDF (A4 + Letter)
```

## Toolbar actions

| Button | Action |
|--------|--------|
| Undo   | Remove last stroke (on-screen + in saved list) |
| Redo   | Re-apply last undone stroke |
| Red    | Switch pen colour to red |
| Blue   | Switch pen colour to blue |
| Thin *(overflow)* | Thin stroke (6 px) |
| Thick *(overflow)* | Thick stroke (16 px) |
| Save   | Write ink annotations to PDF, reload |
