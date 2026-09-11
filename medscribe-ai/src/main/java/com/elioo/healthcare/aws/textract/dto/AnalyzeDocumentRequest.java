package com.elioo.healthcare.aws.textract.dto;

import java.util.List;

/**
 * Request DTO for Textract analyze document endpoint.
 *
 * @param imageBase64 Base64-encoded image data
 * @param featureTypes Features to extract (TABLES, FORMS, LAYOUT). Defaults to TABLES and FORMS if null.
 */
public record AnalyzeDocumentRequest(
        String imageBase64,
        List<String> featureTypes
) {
    public static AnalyzeDocumentRequest simple(String imageBase64) {
        return new AnalyzeDocumentRequest(imageBase64, List.of("TABLES", "FORMS"));
    }
}
