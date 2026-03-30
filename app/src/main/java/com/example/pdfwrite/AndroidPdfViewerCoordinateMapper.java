package com.example.pdfwrite;

/**
 * Maps a point in overlay-view pixel coordinates to PDF MediaBox coordinates (in points, pt)
 * for a given page.
 *
 * <p>AndroidPdfViewer renders pages vertically stacked with a configurable spacing. When
 * {@code fitToWidth()} is active each page is independently scaled to fill the view width,
 * meaning pages with different sizes get different zoom factors. This mapper handles that case,
 * as well as /Rotate metadata (0 / 90 / 180 / 270 degrees).
 *
 * <h3>Coordinate systems</h3>
 * <ul>
 *   <li><b>Overlay pixels</b> – raw MotionEvent coordinates (pixels, top-left origin).
 *   <li><b>PDF points</b> – PDF MediaBox coordinates (pt, bottom-left origin in PDF spec,
 *       but PDFBox-Android uses the same convention: x-right, y-up from bottom-left).
 * </ul>
 *
 * <h3>Usage</h3>
 * After {@code PDFView.onPageChanged} (or after the first page is rendered) call
 * {@link #update(PDFView, float[], float[], float[], float[], int[])} to refresh the cached
 * geometry. Then call {@link #toDocumentCoordinates(int, float, float)} to convert a point.
 */
public class AndroidPdfViewerCoordinateMapper {

    // Page geometry in overlay pixels (supplied by the caller after layout).
    private float[] pageTopOffsets;   // Y of top edge of each page in overlay coords
    private float[] pageLeftOffsets;  // X of left edge of each page in overlay coords
    private float[] pageWidthsPx;     // rendered width  of each page in overlay pixels
    private float[] pageHeightsPx;    // rendered height of each page in overlay pixels

    // PDF MediaBox dimensions in points.
    private float[] pageWidthsPt;
    private float[] pageHeightsPt;

    // PDF /Rotate values per page (0 / 90 / 180 / 270).
    private int[] pageRotations;

    /**
     * Update cached page geometry. Must be called whenever the PDF layout changes
     * (zoom, scroll, or initial load).
     *
     * @param pageTopOffsets  Y of top edge of each page in overlay pixels.
     * @param pageLeftOffsets X of left edge of each page in overlay pixels.
     * @param pageWidthsPx    Rendered pixel width of each page.
     * @param pageHeightsPx   Rendered pixel height of each page.
     * @param pageWidthsPt    PDF MediaBox width  of each page in points.
     * @param pageHeightsPt   PDF MediaBox height of each page in points.
     * @param pageRotations   /Rotate value of each page (0, 90, 180, or 270).
     */
    public void update(float[] pageTopOffsets,
                       float[] pageLeftOffsets,
                       float[] pageWidthsPx,
                       float[] pageHeightsPx,
                       float[] pageWidthsPt,
                       float[] pageHeightsPt,
                       int[]   pageRotations) {
        this.pageTopOffsets  = pageTopOffsets;
        this.pageLeftOffsets = pageLeftOffsets;
        this.pageWidthsPx    = pageWidthsPx;
        this.pageHeightsPx   = pageHeightsPx;
        this.pageWidthsPt    = pageWidthsPt;
        this.pageHeightsPt   = pageHeightsPt;
        this.pageRotations   = pageRotations;
    }

    /**
     * Convert an overlay-view pixel coordinate to PDF MediaBox points for the given page.
     *
     * @param pageIndex 0-based page index.
     * @param overlayX  X in overlay pixels.
     * @param overlayY  Y in overlay pixels.
     * @return float[2] = { pdfX, pdfY } in PDF MediaBox points.
     *         Returns {0,0} if geometry is not available for the page.
     */
    public float[] toDocumentCoordinates(int pageIndex, float overlayX, float overlayY) {
        if (pageTopOffsets == null || pageIndex < 0 || pageIndex >= pageTopOffsets.length) {
            return new float[]{0f, 0f};
        }

        // 1. Convert overlay coords to within-page pixel coords (top-left of rendered page = 0,0).
        float localX = overlayX - pageLeftOffsets[pageIndex];
        float localY = overlayY - pageTopOffsets[pageIndex];

        float renderedW = pageWidthsPx[pageIndex];
        float renderedH = pageHeightsPx[pageIndex];

        // Clamp to page bounds.
        localX = Math.max(0f, Math.min(localX, renderedW));
        localY = Math.max(0f, Math.min(localY, renderedH));

        // 2. Normalise to [0..1] within the rendered page rectangle.
        float normX = (renderedW > 0) ? localX / renderedW : 0f;
        float normY = (renderedH > 0) ? localY / renderedH : 0f;

        // 3. Apply /Rotate: the viewer renders the page rotated; we need to undo that
        //    to map back to the canonical PDF MediaBox orientation.
        float pdfX, pdfY;
        int rotation = (pageRotations != null && pageIndex < pageRotations.length)
                ? pageRotations[pageIndex] : 0;
        float mboxW = pageWidthsPt[pageIndex];
        float mboxH = pageHeightsPt[pageIndex];

        switch (rotation) {
            case 90:
                // Rendered: width=mboxH, height=mboxW → rotated 90° CW
                // normX maps to pdfY (bottom→top), normY maps to pdfX (left→right)
                pdfX = normY * mboxW;
                pdfY = (1f - normX) * mboxH;
                break;
            case 180:
                pdfX = (1f - normX) * mboxW;
                pdfY = normY * mboxH;
                break;
            case 270:
                // Rendered: width=mboxH, height=mboxW → rotated 90° CCW
                pdfX = (1f - normY) * mboxW;
                pdfY = normX * mboxH;
                break;
            default: // 0
                // PDF Y-axis is bottom-up; screen Y is top-down.
                pdfX = normX * mboxW;
                pdfY = (1f - normY) * mboxH;
                break;
        }

        return new float[]{pdfX, pdfY};
    }
}
