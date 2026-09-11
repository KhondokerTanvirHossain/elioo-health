package com.elioo.healthcare.aws.textract.model;

import software.amazon.awssdk.services.textract.model.FeatureType;

import java.util.List;

/**
 * Generic OCR request for Amazon Textract.
 *
 * This is a domain-agnostic model that can be used for any OCR use case.
 * It encapsulates the input parameters needed for Textract operations.
 *
 * @param imageBase64  Base64-encoded image data
 * @param featureTypes List of Textract feature types to extract (TABLES, FORMS, LAYOUT, etc.)
 * @param language     Optional language hint (e.g., "en", "es")
 */
public record OcrRequest(
        String imageBase64,
        List<FeatureType> featureTypes,
        String language
) {
    /**
     * Creates an OCR request with standard feature types (TABLES, FORMS, LAYOUT).
     */
    public static OcrRequest standard(String imageBase64) {
        return new OcrRequest(
                imageBase64,
                List.of(FeatureType.TABLES, FeatureType.FORMS, FeatureType.LAYOUT),
                "en"
        );
    }

    /**
     * Creates an OCR request for simple text detection only.
     */
    public static OcrRequest textOnly(String imageBase64) {
        return new OcrRequest(imageBase64, List.of(), "en");
    }

    /**
     * Creates an OCR request with custom feature types.
     */
    public static OcrRequest withFeatures(String imageBase64, List<FeatureType> features) {
        return new OcrRequest(imageBase64, features, "en");
    }
}
