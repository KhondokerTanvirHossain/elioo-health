package com.elioo.healthcare.aws.bedrock.health.dto;

import java.util.List;

/**
 * Clinical action plan with prioritized and time-based actions.
 *
 * @param immediateActions   Actions requiring immediate attention
 * @param shortTermActions   Actions for 1-2 weeks
 * @param longTermActions    Actions for 1+ months
 */
public record ActionPlan(
        List<ActionItem> immediateActions,
        List<ActionItem> shortTermActions,
        List<ActionItem> longTermActions
) {
    /**
     * Check if there are any immediate actions.
     */
    public boolean hasImmediateActions() {
        return immediateActions != null && !immediateActions.isEmpty();
    }

    /**
     * Check if there are any short-term actions.
     */
    public boolean hasShortTermActions() {
        return shortTermActions != null && !shortTermActions.isEmpty();
    }

    /**
     * Check if there are any long-term actions.
     */
    public boolean hasLongTermActions() {
        return longTermActions != null && !longTermActions.isEmpty();
    }

    /**
     * Get total number of actions across all timeframes.
     */
    public int getTotalActions() {
        int count = 0;
        if (immediateActions != null) count += immediateActions.size();
        if (shortTermActions != null) count += shortTermActions.size();
        if (longTermActions != null) count += longTermActions.size();
        return count;
    }
}
