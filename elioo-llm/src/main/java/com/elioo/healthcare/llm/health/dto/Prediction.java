package com.elioo.healthcare.llm.health.dto;

/**
 * Represents a prediction for future medical test values.
 *
 * @param testName              Name of the test being predicted
 * @param predictedValue        Predicted value
 * @param unit                  Unit of measurement
 * @param confidenceInterval    Confidence interval for the prediction
 * @param timeframe             Timeframe for the prediction
 */
public record Prediction(
        String testName,
        Double predictedValue,
        String unit,
        Double confidenceInterval,
        String timeframe
) {
}
