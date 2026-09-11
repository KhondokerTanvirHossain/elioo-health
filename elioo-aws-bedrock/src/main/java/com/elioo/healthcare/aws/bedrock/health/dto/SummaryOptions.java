package com.elioo.healthcare.aws.bedrock.health.dto;

/**
 * Options for summary generation.
 *
 * @param targetAudience    Target audience for the summary
 * @param length            Desired summary length
 * @param focusArea         Optional focus area for the summary
 */
public record SummaryOptions(
        TargetAudience targetAudience,
        SummaryLength length,
        String focusArea
) {
    /**
     * Create default patient-friendly summary options.
     */
    public static SummaryOptions defaultPatient() {
        return new SummaryOptions(
                TargetAudience.PATIENT,
                SummaryLength.STANDARD,
                null
        );
    }

    /**
     * Create default provider summary options.
     */
    public static SummaryOptions defaultProvider() {
        return new SummaryOptions(
                TargetAudience.PROVIDER,
                SummaryLength.BRIEF,
                null
        );
    }

    /**
     * Create brief summary options.
     */
    public static SummaryOptions brief(TargetAudience audience) {
        return new SummaryOptions(audience, SummaryLength.BRIEF, null);
    }
}
