package com.elioo.healthcare.llm.health.dto;

import java.util.List;

/**
 * Response containing clinical recommendations.
 *
 * @param recommendations    List of clinical recommendations
 * @param summary            Summary of recommendations
 */
public record RecommendationResponse(
        List<ClinicalRecommendation> recommendations,
        String summary
) {
    /**
     * Check if recommendations are present.
     */
    public boolean hasRecommendations() {
        return recommendations != null && !recommendations.isEmpty();
    }

    /**
     * Get number of recommendations.
     */
    public int getRecommendationCount() {
        return recommendations != null ? recommendations.size() : 0;
    }

    /**
     * Get number of urgent recommendations.
     */
    public long getUrgentCount() {
        return recommendations != null ?
                recommendations.stream().filter(ClinicalRecommendation::isUrgent).count() : 0;
    }

    /**
     * Check if there are any urgent recommendations.
     */
    public boolean hasUrgentRecommendations() {
        return getUrgentCount() > 0;
    }
}
