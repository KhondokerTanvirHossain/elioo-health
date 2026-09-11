package com.elioo.healthcare.gcp.vision.model;

import com.google.cloud.vision.v1.BoundingPoly;
import com.google.cloud.vision.v1.Vertex;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Geometry information for text elements (bounding boxes and vertices).
 *
 * <p>This record encapsulates the spatial information for text detected in an image,
 * including bounding boxes and vertex coordinates.</p>
 *
 * <p><b>Coordinate System:</b></p>
 * <ul>
 *   <li>Origin (0,0) is at the top-left corner of the image</li>
 *   <li>X increases to the right</li>
 *   <li>Y increases downward</li>
 * </ul>
 *
 * @param boundingBox Normalized bounding box with vertices
 * @param vertices    List of vertices defining the text region (usually 4 corners)
 *
 * @since 0.1.0
 */
public record TextGeometry(
        BoundingBox boundingBox,
        List<Point> vertices
) {
    /**
     * Bounding box defined by vertices.
     *
     * @param vertices List of points defining the bounding polygon (typically 4 corners)
     */
    public record BoundingBox(List<Point> vertices) {
        /**
         * Create from Google Cloud Vision BoundingPoly.
         */
        public static BoundingBox from(BoundingPoly gcpBoundingPoly) {
            if (gcpBoundingPoly == null) {
                return null;
            }

            List<Point> points = gcpBoundingPoly.getVerticesList().stream()
                    .map(Point::from)
                    .collect(Collectors.toList());

            return new BoundingBox(points);
        }

        /**
         * Get width of bounding box.
         */
        public int getWidth() {
            if (vertices == null || vertices.size() < 2) {
                return 0;
            }
            int minX = vertices.stream().mapToInt(Point::x).min().orElse(0);
            int maxX = vertices.stream().mapToInt(Point::x).max().orElse(0);
            return maxX - minX;
        }

        /**
         * Get height of bounding box.
         */
        public int getHeight() {
            if (vertices == null || vertices.size() < 2) {
                return 0;
            }
            int minY = vertices.stream().mapToInt(Point::y).min().orElse(0);
            int maxY = vertices.stream().mapToInt(Point::y).max().orElse(0);
            return maxY - minY;
        }
    }

    /**
     * 2D point with x, y coordinates.
     *
     * @param x X-coordinate (horizontal position)
     * @param y Y-coordinate (vertical position)
     */
    public record Point(Integer x, Integer y) {
        /**
         * Create from Google Cloud Vision Vertex.
         */
        public static Point from(Vertex gcpVertex) {
            if (gcpVertex == null) {
                return new Point(0, 0);
            }
            return new Point(gcpVertex.getX(), gcpVertex.getY());
        }
    }

    /**
     * Create TextGeometry from Google Cloud Vision BoundingPoly.
     */
    public static TextGeometry from(BoundingPoly gcpPoly) {
        if (gcpPoly == null) {
            return null;
        }

        BoundingBox boundingBox = BoundingBox.from(gcpPoly);
        List<Point> vertices = gcpPoly.getVerticesList().stream()
                .map(Point::from)
                .collect(Collectors.toList());

        return new TextGeometry(boundingBox, vertices);
    }
}
