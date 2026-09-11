package com.elioo.healthcare.llm.health.dto;

import java.util.List;

/**
 * Response containing generated educational content.
 *
 * @param title                    Title of the educational content
 * @param content                  Main educational content text
 * @param keyTakeaways             List of key takeaway points
 * @param faqs                     List of FAQs (if FAQ format requested)
 * @param additionalResources      List of additional resources/references
 */
public record EducationalContentResponse(
        String title,
        String content,
        List<String> keyTakeaways,
        List<FAQ> faqs,
        List<String> additionalResources
) {
    /**
     * Check if content is present.
     */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    /**
     * Check if key takeaways are present.
     */
    public boolean hasKeyTakeaways() {
        return keyTakeaways != null && !keyTakeaways.isEmpty();
    }

    /**
     * Check if FAQs are present.
     */
    public boolean hasFaqs() {
        return faqs != null && !faqs.isEmpty();
    }

    /**
     * Check if additional resources are present.
     */
    public boolean hasAdditionalResources() {
        return additionalResources != null && !additionalResources.isEmpty();
    }

    /**
     * Get content length.
     */
    public int getContentLength() {
        return content != null ? content.length() : 0;
    }
}
