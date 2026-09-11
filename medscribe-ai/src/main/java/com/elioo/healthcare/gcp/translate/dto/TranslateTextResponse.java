package com.elioo.healthcare.gcp.translate.dto;

/**
 * Response DTO for single text translation.
 *
 * <p>Contains the translated text and metadata about the translation operation.</p>
 *
 * <p><b>Example Response:</b></p>
 * <pre>{@code
 * // Successful translation
 * {
 *   "translatedText": "Blood Pressure",
 *   "detectedSourceLanguage": "bn",
 *   "targetLanguage": "en",
 *   "confidence": 1.0
 * }
 *
 * // Auto-detected language
 * {
 *   "translatedText": "Blood Glucose",
 *   "detectedSourceLanguage": "bn",
 *   "targetLanguage": "en",
 *   "confidence": 1.0
 * }
 * }</pre>
 *
 * @param translatedText         The translated text in target language
 * @param detectedSourceLanguage Auto-detected source language (null if not detected)
 * @param targetLanguage         Target language code from request
 * @param confidence             Translation confidence score (always 1.0 for GCP)
 *
 * @since 1.0.0
 */
public record TranslateTextResponse(
        String translatedText,
        String detectedSourceLanguage,
        String targetLanguage,
        double confidence
) {
}
