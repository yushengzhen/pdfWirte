package com.example.pdfwrite;

import android.graphics.Color;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationInk;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes ink strokes as standard PDF Ink Annotations (PDAnnotationInk) using PdfBox-Android.
 *
 * <p>Each {@link InkStroke} is converted from overlay-pixel coordinates to PDF-point coordinates
 * via {@link AndroidPdfViewerCoordinateMapper}, then serialised as a PDAnnotationInk with a
 * correct InkList, BorderStyle, Color, and bounding Rectangle.
 */
public class PdfInkWriter {

    private final AndroidPdfViewerCoordinateMapper mapper;

    public PdfInkWriter(AndroidPdfViewerCoordinateMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Load the source PDF from {@code inputStream}, add all strokes as ink annotations,
     * and write the result to {@code outputFile}.
     *
     * @param inputStream   source PDF input stream (will NOT be closed by this method).
     * @param outputFile    destination file for the annotated PDF.
     * @param strokes       list of strokes from {@link InkOverlayView#getCommittedStrokes()}.
     * @param pageWidthsPt  PDF MediaBox width  per page (points) – unused here, kept for API
     *                      symmetry; widths come from the document itself.
     * @param pageHeightsPt PDF MediaBox height per page (points) – same note.
     */
    public void write(InputStream inputStream,
                      File outputFile,
                      List<InkStroke> strokes,
                      float[] pageWidthsPt,
                      float[] pageHeightsPt) throws IOException {

        PDDocument doc = PDDocument.load(inputStream);
        try {
            // Group strokes by page index.
            Map<Integer, List<InkStroke>> byPage = new HashMap<>();
            for (InkStroke s : strokes) {
                int page = s.getPageIndex();
                if (!byPage.containsKey(page)) {
                    byPage.put(page, new ArrayList<InkStroke>());
                }
                byPage.get(page).add(s);
            }

            int pageCount = doc.getNumberOfPages();
            for (Map.Entry<Integer, List<InkStroke>> entry : byPage.entrySet()) {
                int pageIdx = entry.getKey();
                if (pageIdx < 0 || pageIdx >= pageCount) continue;

                PDPage pdPage = doc.getPage(pageIdx);
                for (InkStroke stroke : entry.getValue()) {
                    addInkAnnotation(pdPage, stroke, pageIdx);
                }
            }

            doc.save(outputFile);
        } finally {
            doc.close();
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Convert one InkStroke to a PDAnnotationInk and add it to the given page.
     */
    private void addInkAnnotation(PDPage pdPage, InkStroke stroke, int pageIdx)
            throws IOException {
        List<float[]> overlayPoints = stroke.getPoints();
        if (overlayPoints.isEmpty()) return;

        // Convert every point to PDF coordinates.
        float[] flatPoints = new float[overlayPoints.size() * 2];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        for (int i = 0; i < overlayPoints.size(); i++) {
            float[] pp = mapper.toDocumentCoordinates(pageIdx,
                    overlayPoints.get(i)[0], overlayPoints.get(i)[1]);
            flatPoints[i * 2]     = pp[0];
            flatPoints[i * 2 + 1] = pp[1];
            if (pp[0] < minX) minX = pp[0];
            if (pp[1] < minY) minY = pp[1];
            if (pp[0] > maxX) maxX = pp[0];
            if (pp[1] > maxY) maxY = pp[1];
        }

        // PDAnnotationInk.setInkList expects float[][] (array of ink arrays, one per sub-path).
        float[][] inkList = new float[][]{flatPoints};

        // Bounding box with a small margin so the stroke is not clipped.
        float margin = stroke.getWidth() / 2f + 1f;
        PDRectangle rect = new PDRectangle(
                minX - margin,
                minY - margin,
                (maxX - minX) + 2 * margin,
                (maxY - minY) + 2 * margin);

        // Build the annotation.
        PDAnnotationInk inkAnnot = new PDAnnotationInk();
        inkAnnot.setInkList(inkList);
        inkAnnot.setRectangle(rect);

        // Border style – width in points (rough approximation).
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(Math.max(1f, stroke.getWidth() / 2f));
        inkAnnot.setBorderStyle(border);

        // Colour (PDF colour components are 0..1 per channel).
        int argb = stroke.getColor();
        float r = Color.red(argb)   / 255f;
        float g = Color.green(argb) / 255f;
        float b = Color.blue(argb)  / 255f;
        inkAnnot.setColor(new PDColor(new float[]{r, g, b}, PDDeviceRGB.INSTANCE));

        // Append to the page's annotation list.
        pdPage.getAnnotations().add(inkAnnot);
    }
}
