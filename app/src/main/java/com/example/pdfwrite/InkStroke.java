package com.example.pdfwrite;

import android.graphics.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single ink stroke drawn by the user.
 * Stores the raw points (in view/overlay coordinates), the associated PDF page,
 * the paint color, and the stroke width.
 */
public class InkStroke {

    /** Points in overlay-view pixel coordinates: [x0,y0, x1,y1, ...] */
    private final List<float[]> points = new ArrayList<>();

    /** The 0-based PDF page index this stroke belongs to. */
    private final int pageIndex;

    /** Paint color (ARGB). */
    private int color;

    /** Stroke width in pixels (overlay coordinates). */
    private float width;

    public InkStroke(int pageIndex, int color, float width) {
        this.pageIndex = pageIndex;
        this.color = color;
        this.width = width;
    }

    /** Add a point (overlay-view pixels). */
    public void addPoint(float x, float y) {
        points.add(new float[]{x, y});
    }

    public List<float[]> getPoints() {
        return points;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public int getColor() {
        return color;
    }

    public float getWidth() {
        return width;
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    /**
     * Build an Android Path from the stored points for canvas drawing.
     */
    public Path toPath() {
        Path path = new Path();
        boolean first = true;
        for (float[] pt : points) {
            if (first) {
                path.moveTo(pt[0], pt[1]);
                first = false;
            } else {
                path.lineTo(pt[0], pt[1]);
            }
        }
        return path;
    }
}
