package com.elioo.healthcare.llm.health.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request for temporal trend analysis of medical data.
 *
 * @param historicalData     Historical time-series data points
 * @param patientContext     Patient context for personalized analysis
 * @param options            Trend analysis options
 */
public record TrendAnalysisRequest(
        @JsonProperty("historicalData")
        @JsonAlias("timeSeriesData")
        List<TimeSeriesDataPoint> historicalData,
        PatientContext patientContext,
        TrendAnalysisOptions options
) {
    /**
     * Create a simple trend analysis request with default options.
     */
    public static TrendAnalysisRequest simple(List<TimeSeriesDataPoint> historicalData) {
        return new TrendAnalysisRequest(
                historicalData,
                null,
                TrendAnalysisOptions.defaultOptions()
        );
    }

    /**
     * Validate request.
     */
    public boolean isValid() {
        return historicalData != null && !historicalData.isEmpty() &&
                historicalData.stream().allMatch(TimeSeriesDataPoint::isValid);
    }

    /**
     * Get number of data points.
     */
    public int getDataPointCount() {
        return historicalData != null ? historicalData.size() : 0;
    }
}
