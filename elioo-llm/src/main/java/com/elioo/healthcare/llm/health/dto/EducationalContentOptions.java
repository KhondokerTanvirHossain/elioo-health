package com.elioo.healthcare.llm.health.dto;

/**
 * Options for educational content generation.
 *
 * @param readingLevel          Target reading level
 * @param format                Content format
 * @param maxLength             Maximum length in words/characters
 * @param includeDiagrams       Whether to include diagram descriptions
 */
public record EducationalContentOptions(
        ReadingLevel readingLevel,
        ContentFormat format,
        Integer maxLength,
        boolean includeDiagrams
) {
    /**
     * Create default patient education options (simple, text format).
     */
    public static EducationalContentOptions defaultPatient() {
        return new EducationalContentOptions(
                ReadingLevel.SIMPLE,
                ContentFormat.TEXT,
                500, // 500 words
                false
        );
    }

    /**
     * Create FAQ-style options.
     */
    public static EducationalContentOptions faq(ReadingLevel readingLevel) {
        return new EducationalContentOptions(
                readingLevel,
                ContentFormat.FAQ,
                null,
                false
        );
    }

    /**
     * Create bullet-point options.
     */
    public static EducationalContentOptions bulletPoints(ReadingLevel readingLevel) {
        return new EducationalContentOptions(
                readingLevel,
                ContentFormat.BULLET_POINTS,
                null,
                false
        );
    }
}
