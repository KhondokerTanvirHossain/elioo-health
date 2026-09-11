package com.elioo.healthcare.llm.health.dto;

/**
 * Request for generating patient educational content.
 *
 * @param topic              Topic for educational content (e.g., "high creatinine", "diabetes management")
 * @param patientContext     Optional patient context for personalization
 * @param options            Educational content options
 */
public record EducationalContentRequest(
        String topic,
        PatientContext patientContext,
        EducationalContentOptions options
) {
    /**
     * Create a simple educational content request.
     */
    public static EducationalContentRequest simple(String topic) {
        return new EducationalContentRequest(
                topic,
                null,
                EducationalContentOptions.defaultPatient()
        );
    }

    /**
     * Create a FAQ-style request.
     */
    public static EducationalContentRequest faq(String topic, ReadingLevel readingLevel) {
        return new EducationalContentRequest(
                topic,
                null,
                EducationalContentOptions.faq(readingLevel)
        );
    }

    /**
     * Validate request.
     */
    public boolean isValid() {
        return topic != null && !topic.isBlank();
    }
}
