package com.example.pdfwrite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Transparent overlay that sits on top of the PDFView and captures hand-writing.
 *
 * Features:
 *  - Free-hand drawing with configurable color and stroke width.
 *  - Undo / Redo (both affect on-screen drawing AND the committed strokes list).
 *  - Cross-page stroke splitting: a single finger drag that crosses a page boundary
 *    is automatically cut into one stroke per page.
 *
 * The overlay works in "overlay pixel coordinates" (same coordinate space as the
 * raw MotionEvent). The coordinate mapper is used only when saving, not for drawing.
 */
public class InkOverlayView extends View {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /** All committed (finished) strokes. */
    private final List<InkStroke> committedStrokes = new ArrayList<>();

    /** Redo stack – strokes popped from committed on undo. */
    private final Deque<InkStroke> redoStack = new ArrayDeque<>();

    /** Currently-being-drawn (in-progress) stroke. May span multiple segments
     *  before being split at page boundaries on ACTION_UP. */
    private InkStroke currentStroke;

    // -----------------------------------------------------------------------
    // Painting
    // -----------------------------------------------------------------------

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Current pen color (ARGB). */
    private int penColor = Color.RED;

    /** Current pen width in pixels. */
    private float penWidth = 8f;

    // -----------------------------------------------------------------------
    // Page geometry – supplied by MainActivity after each PDFView layout.
    // -----------------------------------------------------------------------

    /**
     * Page offsets in overlay coordinates.
     * pageOffsets[i] = Y-pixel position (top edge) of page i inside the overlay.
     */
    private float[] pageOffsets;
    private float[] pageHeights;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public InkOverlayView(Context context) {
        super(context);
        init();
    }

    public InkOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public InkOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void setPenColor(int color) {
        this.penColor = color;
    }

    public void setPenWidth(float width) {
        this.penWidth = width;
    }

    /**
     * Supply page layout geometry so cross-page splitting works correctly.
     *
     * @param offsets Y-coordinate (overlay pixels) of the top edge of each page.
     * @param heights Height (overlay pixels) of each page.
     */
    public void setPageGeometry(float[] offsets, float[] heights) {
        this.pageOffsets = offsets;
        this.pageHeights = heights;
    }

    /** Undo the last committed stroke. */
    public void undo() {
        if (!committedStrokes.isEmpty()) {
            InkStroke last = committedStrokes.remove(committedStrokes.size() - 1);
            redoStack.push(last);
            invalidate();
        }
    }

    /** Redo the last undone stroke. */
    public void redo() {
        if (!redoStack.isEmpty()) {
            committedStrokes.add(redoStack.pop());
            invalidate();
        }
    }

    /** Return all committed strokes (used by PdfInkWriter). */
    public List<InkStroke> getCommittedStrokes() {
        return committedStrokes;
    }

    /** Clear everything (called after save). */
    public void clearAll() {
        committedStrokes.clear();
        redoStack.clear();
        currentStroke = null;
        invalidate();
    }

    // -----------------------------------------------------------------------
    // Touch handling
    // -----------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // Start a new stroke on the page at this Y position.
                int page = pageIndexAt(y);
                currentStroke = new InkStroke(page, penColor, penWidth);
                currentStroke.addPoint(x, y);
                // Clear redo stack on new drawing action.
                redoStack.clear();
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (currentStroke == null) return true;

                // Process all historical points for smooth curves.
                int historySize = event.getHistorySize();
                for (int h = 0; h < historySize; h++) {
                    addPointWithPageSplit(event.getHistoricalX(h), event.getHistoricalY(h));
                }
                addPointWithPageSplit(x, y);
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (currentStroke != null && !currentStroke.isEmpty()) {
                    committedStrokes.add(currentStroke);
                }
                currentStroke = null;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    /**
     * Add a point to the current stroke, splitting it at page boundaries if necessary.
     * When crossing from page N to page N+1, the current stroke is committed and a new
     * stroke (on the new page) is started at the crossing point.
     */
    private void addPointWithPageSplit(float x, float y) {
        if (currentStroke == null) return;

        int newPage = pageIndexAt(y);
        if (newPage != currentStroke.getPageIndex()) {
            // Commit the current stroke fragment (if it has points).
            if (!currentStroke.isEmpty()) {
                committedStrokes.add(currentStroke);
            }
            // Begin a new stroke on the new page, starting at the boundary crossing point.
            currentStroke = new InkStroke(newPage, penColor, penWidth);
        }
        currentStroke.addPoint(x, y);
    }

    /**
     * Determine which page (0-based) a given Y overlay-coordinate falls on.
     * Returns 0 if page geometry is not yet available or the point is above all pages.
     * Returns the last page index if the point is below all pages.
     */
    private int pageIndexAt(float y) {
        if (pageOffsets == null || pageOffsets.length == 0) return 0;
        for (int i = pageOffsets.length - 1; i >= 0; i--) {
            if (y >= pageOffsets[i]) return i;
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw committed strokes.
        for (InkStroke stroke : committedStrokes) {
            drawStroke(canvas, stroke);
        }

        // Draw current in-progress stroke.
        if (currentStroke != null) {
            drawStroke(canvas, currentStroke);
        }
    }

    private void drawStroke(Canvas canvas, InkStroke stroke) {
        strokePaint.setColor(stroke.getColor());
        strokePaint.setStrokeWidth(stroke.getWidth());
        canvas.drawPath(stroke.toPath(), strokePaint);
    }
}
