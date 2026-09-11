package com.elioo.healthcare.llm.health.dto;

import java.util.List;
import java.util.Map;

/**
 * Response containing trend analysis results.
 *
 * @param patterns                  List of detected trend patterns
 * @param anomalies                 List of detected anomalies
 * @param predictions               Map of test name to prediction
 * @param overallTrendSummary       Overall summary of trends
 */
public record TrendAnalysisResponse(
        List<TrendPattern> patterns,
        List<Anomaly> anomalies,
        Map<String, Prediction> predictions,
        String overallTrendSummary
) {
    /**
     * Check if patterns were detected.
     */
    public boolean hasPatterns() {
        return patterns != null && !patterns.isEmpty();
    }

    /**
     * Check if anomalies were detected.
     */
    public boolean hasAnomalies() {
        return anomalies != null && !anomalies.isEmpty();
    }

    /**
     * Check if predictions are available.
     */
    public boolean hasPredictions() {
        return predictions != null && !predictions.isEmpty();
    }

    /**
     * Get prediction for a specific test.
     */
    public Prediction getPrediction(String testName) {
        return predictions != null ? predictions.get(testName) : null;
    }

    /**
     * Get number of concerning patterns.
     */
    public long getConcerningPatternCount() {
        return patterns != null ?
                patterns.stream().filter(TrendPattern::isConcerning).count() : 0;
    }
}
