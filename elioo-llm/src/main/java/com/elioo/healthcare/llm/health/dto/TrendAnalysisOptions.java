package com.elioo.healthcare.llm.health.dto;

import java.util.List;

/**
 * Options for trend analysis.
 *
 * @param testsToAnalyze         Specific tests to analyze (null = analyze all)
 * @param detectAnomalies        Whether to detect anomalies in the data
 * @param predictFutureValues    Whether to predict future values
 * @param predictionHorizon      Prediction horizon in days
 */
public record TrendAnalysisOptions(
        List<String> testsToAnalyze,
        boolean detectAnomalies,
        boolean predictFutureValues,
        Integer predictionHorizon
) {
    /**
     * Create default trend analysis options (detect anomalies, predict 30 days).
     */
    public static TrendAnalysisOptions defaultOptions() {
        return new TrendAnalysisOptions(
                null, // Analyze all tests
                true,
                true,
                30 // 30-day prediction
        );
    }

    /**
     * Create options for specific tests only.
     */
    public static TrendAnalysisOptions forTests(List<String> testNames) {
        return new TrendAnalysisOptions(testNames, true, true, 30);
    }

    /**
     * Create options without predictions (analysis only).
     */
    public static TrendAnalysisOptions analysisOnly() {
        return new TrendAnalysisOptions(null, true, false, null);
    }
}
