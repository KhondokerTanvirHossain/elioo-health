package com.elioo.healthcare.aws.bedrock.health.dto;

import java.util.List;

/**
 * Response containing generated medical data summary.
 *
 * @param summary       The generated summary text
 * @param title         Optional title for the summary
 * @param keyPoints     List of key points extracted from the data
 */
public record SummaryResponse(
        String summary,
        String title,
        List<String> keyPoints
) {
    /**
     * Check if summary has content.
     */
    public boolean hasContent() {
        return summary != null && !summary.isBlank();
    }

    /**
     * Check if key points are present.
     */
    public boolean hasKeyPoints() {
        return keyPoints != null && !keyPoints.isEmpty();
    }

    /**
     * Get summary length.
     */
    public int getSummaryLength() {
        return summary != null ? summary.length() : 0;
    }
}
