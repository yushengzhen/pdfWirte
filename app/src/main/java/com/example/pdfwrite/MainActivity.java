package com.example.pdfwrite;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewTreeObserver;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener;
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener;
import com.github.barteksc.pdfviewer.listener.OnRenderListener;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Main activity hosting the PDF viewer and the ink overlay.
 *
 * <p>Layout: a FrameLayout containing:
 * <ol>
 *   <li>{@link PDFView} (fills the parent)
 *   <li>{@link InkOverlayView} (fills the parent, transparent, sits on top)
 * </ol>
 *
 * <p>Toolbar menu: Undo | Redo | Red | Blue | Thin | Thick | Save
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String ASSET_PDF = "sample.pdf";
    private static final int SPACING_PX = 8;

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------

    private PDFView pdfView;
    private InkOverlayView inkOverlay;

    // -----------------------------------------------------------------------
    // Coordinate mapping
    // -----------------------------------------------------------------------

    private AndroidPdfViewerCoordinateMapper coordinateMapper;

    // -----------------------------------------------------------------------
    // PDF metadata (filled after the PDF is loaded, on the main thread)
    // -----------------------------------------------------------------------

    private int totalPages = 0;
    private float[] pageWidthsPt;
    private float[] pageHeightsPt;
    private int[]   pageRotations;

    // -----------------------------------------------------------------------
    // Ink writer
    // -----------------------------------------------------------------------

    private PdfInkWriter inkWriter;

    /** Output PDF written by Save. */
    private File outputFile;

    /** Whether we are currently showing the output PDF (after save). */
    private boolean showingOutput = false;

    // -----------------------------------------------------------------------
    // Activity lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pdfView    = findViewById(R.id.pdfView);
        inkOverlay = findViewById(R.id.inkOverlay);

        coordinateMapper = new AndroidPdfViewerCoordinateMapper();
        inkWriter        = new PdfInkWriter(coordinateMapper);
        outputFile       = new File(getCacheDir(), "annotated.pdf");

        loadAssetPdf();
    }

    // -----------------------------------------------------------------------
    // PDF loading
    // -----------------------------------------------------------------------

    private void loadAssetPdf() {
        showingOutput = false;
        pdfView.fromAsset(ASSET_PDF)
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .spacing(SPACING_PX)
                .pageFling(false)
                .pageSnap(false)
                .onLoad(new OnLoadCompleteListener() {
                    @Override
                    public void loadComplete(int nbPages) {
                        totalPages = nbPages;
                        loadPdfMetadataAsync(false);
                    }
                })
                .onRender(new OnRenderListener() {
                    @Override
                    public void onInitiallyRendered(int nbPages) {
                        pdfView.fitToWidth();
                        refreshPageGeometry();
                    }
                })
                .onPageChange(new OnPageChangeListener() {
                    @Override
                    public void onPageChanged(int page, int pageCount) {
                        refreshPageGeometry();
                    }
                })
                .scrollHandle(new DefaultScrollHandle(this))
                .load();
    }

    private void reloadOutputPdf() {
        showingOutput = true;
        pdfView.fromFile(outputFile)
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .spacing(SPACING_PX)
                .pageFling(false)
                .pageSnap(false)
                .onLoad(new OnLoadCompleteListener() {
                    @Override
                    public void loadComplete(int nbPages) {
                        totalPages = nbPages;
                        loadPdfMetadataAsync(true);
                    }
                })
                .onRender(new OnRenderListener() {
                    @Override
                    public void onInitiallyRendered(int nbPages) {
                        pdfView.fitToWidth();
                        refreshPageGeometry();
                    }
                })
                .scrollHandle(new DefaultScrollHandle(this))
                .load();

        inkOverlay.clearAll();
    }

    // -----------------------------------------------------------------------
    // PDF metadata helpers
    // -----------------------------------------------------------------------

    /** Read page MediaBox sizes and /Rotate values in a background thread. */
    private void loadPdfMetadataAsync(final boolean fromOutputFile) {
        new Thread(() -> {
            try {
                InputStream is = fromOutputFile
                        ? new FileInputStream(outputFile)
                        : getAssets().open(ASSET_PDF);
                readMetadataFromStream(is);
            } catch (IOException e) {
                Log.e(TAG, "Failed to read PDF metadata", e);
            }
        }).start();
    }

    private void readMetadataFromStream(InputStream is) throws IOException {
        com.tom_roush.pdfbox.pdmodel.PDDocument doc =
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(is);
        try {
            int count = doc.getNumberOfPages();
            final float[] wPt = new float[count];
            final float[] hPt = new float[count];
            final int[]   rot = new int[count];
            for (int i = 0; i < count; i++) {
                com.tom_roush.pdfbox.pdmodel.PDPage page = doc.getPage(i);
                com.tom_roush.pdfbox.pdmodel.common.PDRectangle mb = page.getMediaBox();
                wPt[i] = mb.getWidth();
                hPt[i] = mb.getHeight();
                rot[i] = page.getRotation();
            }
            runOnUiThread(() -> {
                pageWidthsPt  = wPt;
                pageHeightsPt = hPt;
                pageRotations = rot;
                refreshPageGeometry();
            });
        } finally {
            doc.close();
            is.close();
        }
    }

    // -----------------------------------------------------------------------
    // Page geometry (overlay pixel coords)
    // -----------------------------------------------------------------------

    /**
     * Compute the on-screen position of every page in the overlay coordinate space.
     *
     * <p>After {@code fitToWidth()} the zoom is set so that page 0 fills the view width.
     * All pages use this same scale factor. Pages with different widths are centred
     * horizontally by the library.
     *
     * <p>Screen Y of page i = accumulatedDocY(i) + pdfView.getCurrentYOffset()
     * where getCurrentYOffset() is negative (≤ 0) when scrolled down.
     */
    private void refreshPageGeometry() {
        if (totalPages == 0) return;

        if (pdfView.getWidth() == 0) {
            pdfView.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            pdfView.getViewTreeObserver()
                                    .removeOnGlobalLayoutListener(this);
                            refreshPageGeometry();
                        }
                    });
            return;
        }

        final int viewWidth = pdfView.getWidth();
        final float yOffset = pdfView.getCurrentYOffset(); // <= 0 when scrolled down
        final float xOffset = pdfView.getCurrentXOffset();

        // Derive pt-to-px scale: after fitToWidth(), page 0's rendered width fills the view.
        float vis0W = visualWidth(0);
        if (vis0W <= 0) vis0W = 595f;
        final float scale = (float) viewWidth / vis0W;

        float[] tops    = new float[totalPages];
        float[] lefts   = new float[totalPages];
        float[] widths  = new float[totalPages];
        float[] heights = new float[totalPages];

        float docY = 0f;
        for (int i = 0; i < totalPages; i++) {
            float pageW = visualWidth(i)  * scale;
            float pageH = visualHeight(i) * scale;

            // AndroidPdfViewer centres pages horizontally within the view.
            float pageLeft = (viewWidth - pageW) / 2f + xOffset;

            tops[i]    = docY + yOffset;
            lefts[i]   = pageLeft;
            widths[i]  = pageW;
            heights[i] = pageH;

            docY += pageH + SPACING_PX;
        }

        inkOverlay.setPageGeometry(tops, heights);
        coordinateMapper.update(
                tops, lefts, widths, heights,
                ensureArray(pageWidthsPt,  totalPages, 595f),
                ensureArray(pageHeightsPt, totalPages, 842f),
                ensureIntArray(pageRotations, totalPages));
    }

    /** Visual (rendered) width of page i, accounting for /Rotate. */
    private float visualWidth(int i) {
        int rot = safeRot(i);
        return (rot == 90 || rot == 270) ? safePtH(i) : safePtW(i);
    }

    /** Visual (rendered) height of page i, accounting for /Rotate. */
    private float visualHeight(int i) {
        int rot = safeRot(i);
        return (rot == 90 || rot == 270) ? safePtW(i) : safePtH(i);
    }

    private float safePtW(int i) {
        return (pageWidthsPt  != null && i < pageWidthsPt.length)  ? pageWidthsPt[i]  : 595f;
    }

    private float safePtH(int i) {
        return (pageHeightsPt != null && i < pageHeightsPt.length) ? pageHeightsPt[i] : 842f;
    }

    private int safeRot(int i) {
        return (pageRotations != null && i < pageRotations.length) ? pageRotations[i] : 0;
    }

    private float[] ensureArray(float[] arr, int len, float def) {
        if (arr != null && arr.length >= len) return arr;
        float[] r = new float[len];
        Arrays.fill(r, def);
        return r;
    }

    private int[] ensureIntArray(int[] arr, int len) {
        if (arr != null && arr.length >= len) return arr;
        return new int[len];
    }

    // -----------------------------------------------------------------------
    // Toolbar / menu
    // -----------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_undo) {
            inkOverlay.undo();
            return true;
        } else if (id == R.id.action_redo) {
            inkOverlay.redo();
            return true;
        } else if (id == R.id.action_red) {
            inkOverlay.setPenColor(Color.RED);
            return true;
        } else if (id == R.id.action_blue) {
            inkOverlay.setPenColor(Color.BLUE);
            return true;
        } else if (id == R.id.action_thin) {
            inkOverlay.setPenWidth(6f);
            return true;
        } else if (id == R.id.action_thick) {
            inkOverlay.setPenWidth(16f);
            return true;
        } else if (id == R.id.action_save) {
            savePdf();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // -----------------------------------------------------------------------
    // Save PDF
    // -----------------------------------------------------------------------

    private void savePdf() {
        if (inkOverlay.getCommittedStrokes().isEmpty()) {
            Toast.makeText(this, R.string.no_strokes_to_save, Toast.LENGTH_SHORT).show();
            return;
        }

        if (pageWidthsPt == null) {
            Toast.makeText(this, R.string.pdf_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        // Ensure geometry is up-to-date before saving.
        refreshPageGeometry();

        final float[] wPt = pageWidthsPt;
        final float[] hPt = pageHeightsPt;
        final ArrayList<InkStroke> strokesSnapshot =
                new ArrayList<>(inkOverlay.getCommittedStrokes());
        final boolean fromOutput = showingOutput;

        new Thread(() -> {
            try {
                InputStream is = fromOutput
                        ? new FileInputStream(outputFile)
                        : getAssets().open(ASSET_PDF);
                inkWriter.write(is, outputFile, strokesSnapshot, wPt, hPt);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.saved_ok, Toast.LENGTH_SHORT).show();
                    reloadOutputPdf();
                });
            } catch (IOException e) {
                Log.e(TAG, "Failed to save PDF", e);
                runOnUiThread(() ->
                        Toast.makeText(this,
                                getString(R.string.save_failed) + ": " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
