package com.elioo.healthcare.llm.health.dto;

/**
 * Represents an anomaly detected in medical data.
 *
 * @param timestamp      Timestamp of the anomaly
 * @param testName       Name of the test with anomaly
 * @param value          Anomalous value
 * @param explanation    Explanation of why this is anomalous
 */
public record Anomaly(
        String timestamp,
        String testName,
        Double value,
        String explanation
) {
}
