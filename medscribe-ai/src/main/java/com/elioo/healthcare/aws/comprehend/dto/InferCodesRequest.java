package com.elioo.healthcare.aws.comprehend.dto;

/**
 * Request DTO for Comprehend Medical infer medical codes endpoints.
 *
 * @param text Medical text to extract codes from
 */
public record InferCodesRequest(String text) {
}
