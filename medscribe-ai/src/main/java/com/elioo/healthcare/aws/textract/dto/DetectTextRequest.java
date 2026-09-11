package com.elioo.healthcare.aws.textract.dto;

/**
 * Request DTO for Textract detect text endpoint.
 *
 * @param imageBase64 Base64-encoded image data
 */
public record DetectTextRequest(String imageBase64) {
}
