package com.elioo.healthcare.aws.bedrock.health.dto;

/**
 * Represents a single action item in a clinical action plan.
 *
 * @param action        The action to be taken
 * @param priority      Priority level: "URGENT", "HIGH", "MEDIUM", "LOW"
 * @param timeframe     Timeframe for completion: "Immediate", "Within 24 hours", "1-2 weeks", etc.
 * @param category      Category of action: "DIAGNOSTIC", "MEDICATION", "LIFESTYLE", etc.
 */
public record ActionItem(
        String action,
        String priority,
        String timeframe,
        String category
) {
    /**
     * Check if action is urgent.
     */
    public boolean isUrgent() {
        return "URGENT".equalsIgnoreCase(priority);
    }

    /**
     * Check if action is immediate.
     */
    public boolean isImmediate() {
        return timeframe != null && timeframe.toLowerCase().contains("immediate");
    }
}
