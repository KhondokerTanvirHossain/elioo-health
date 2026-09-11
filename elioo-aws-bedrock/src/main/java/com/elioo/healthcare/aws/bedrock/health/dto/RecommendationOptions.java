package com.elioo.healthcare.aws.bedrock.health.dto;

import java.util.List;

/**
 * Options for recommendation generation.
 *
 * @param categories                Filter recommendations by category
 * @param includeEvidenceLevels     Whether to include evidence-based medicine levels
 * @param maxRecommendations        Maximum number of recommendations to generate
 * @param priorityFilter            Priority filter: "URGENT_ONLY", "HIGH_AND_URGENT", "ALL"
 */
public record RecommendationOptions(
        List<String> categories,
        boolean includeEvidenceLevels,
        Integer maxRecommendations,
        String priorityFilter
) {
    /**
     * Create default recommendation options (all priorities, with evidence levels).
     */
    public static RecommendationOptions defaultOptions() {
        return new RecommendationOptions(
                null, // All categories
                true,
                10,
                "ALL"
        );
    }

    /**
     * Create options for urgent recommendations only.
     */
    public static RecommendationOptions urgentOnly() {
        return new RecommendationOptions(null, true, null, "URGENT_ONLY");
    }

    /**
     * Create options for high-priority recommendations.
     */
    public static RecommendationOptions highPriority() {
        return new RecommendationOptions(null, true, null, "HIGH_AND_URGENT");
    }
}
