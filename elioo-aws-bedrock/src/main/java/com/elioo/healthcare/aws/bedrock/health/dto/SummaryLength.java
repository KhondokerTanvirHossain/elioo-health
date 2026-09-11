package com.elioo.healthcare.aws.bedrock.health.dto;

/**
 * Summary length options for clinical content.
 */
public enum SummaryLength {
    /**
     * Brief summary: 1-2 sentences.
     */
    BRIEF,

    /**
     * Standard summary: 1 paragraph.
     */
    STANDARD,

    /**
     * Detailed summary: Multiple paragraphs with comprehensive details.
     */
    DETAILED
}
