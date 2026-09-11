package com.elioo.healthcare.aws.textract.model;

/**
 * Serializable representation of block geometry (bounding box).
 * This replaces the AWS SDK's Geometry class for JSON serialization.
 *
 * @param boundingBox The bounding box containing the block
 * @param polygon     The polygon points (optional, for more precise boundaries)
 */
public record BlockGeometry(
        BoundingBox boundingBox,
        Polygon polygon
) {
    /**
     * Creates a BlockGeometry from AWS SDK Geometry.
     */
    public static BlockGeometry from(software.amazon.awssdk.services.textract.model.Geometry awsGeometry) {
        if (awsGeometry == null) {
            return null;
        }
        return new BlockGeometry(
                BoundingBox.from(awsGeometry.boundingBox()),
                Polygon.from(awsGeometry.polygon())
        );
    }

    /**
     * Bounding box coordinates (normalized 0-1).
     */
    public record BoundingBox(
            Float width,
            Float height,
            Float left,
            Float top
    ) {
        public static BoundingBox from(software.amazon.awssdk.services.textract.model.BoundingBox awsBBox) {
            if (awsBBox == null) {
                return null;
            }
            return new BoundingBox(
                    awsBBox.width(),
                    awsBBox.height(),
                    awsBBox.left(),
                    awsBBox.top()
            );
        }
    }

    /**
     * Polygon points for precise boundaries.
     */
    public record Polygon(
            java.util.List<Point> points
    ) {
        public static Polygon from(java.util.List<software.amazon.awssdk.services.textract.model.Point> awsPoints) {
            if (awsPoints == null || awsPoints.isEmpty()) {
                return null;
            }
            return new Polygon(
                    awsPoints.stream()
                            .map(Point::from)
                            .toList()
            );
        }

        public record Point(
                Float x,
                Float y
        ) {
            public static Point from(software.amazon.awssdk.services.textract.model.Point awsPoint) {
                if (awsPoint == null) {
                    return null;
                }
                return new Point(awsPoint.x(), awsPoint.y());
            }
        }
    }
}
