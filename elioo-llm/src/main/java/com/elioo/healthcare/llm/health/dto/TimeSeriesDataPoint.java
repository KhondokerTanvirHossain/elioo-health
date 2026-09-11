package com.elioo.healthcare.llm.health.dto;

import java.util.Map;

/**
 * Represents a single time-series data point for trend analysis.
 *
 * @param timestamp    Timestamp in ISO 8601 format (e.g., "2024-01-15T10:30:00Z")
 * @param testName     Name of the test/measurement
 * @param value        Numerical value
 * @param unit         Unit of measurement
 * @param metadata     Additional metadata for this data point
 */
public record TimeSeriesDataPoint(
        String timestamp,
        String testName,
        Double value,
        String unit,
        Map<String, Object> metadata
) {
    /**
     * Create a simple data point with minimal information.
     */
    public static TimeSeriesDataPoint simple(
            String timestamp,
            String testName,
            Double value,
            String unit
    ) {
        return new TimeSeriesDataPoint(timestamp, testName, value, unit, null);
    }

    /**
     * Validate data point.
     */
    public boolean isValid() {
        return timestamp != null && !timestamp.isBlank() &&
                testName != null && !testName.isBlank() &&
                value != null;
    }
}
