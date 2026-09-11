package com.elioo.healthcare.aws.textract.dto;

/**
 * Request DTO for Textract validate image quality endpoint.
 *
 * @param imageBase64 Base64-encoded image data
 */
public record ValidateImageRequest(String imageBase64) {
}
