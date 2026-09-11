package com.elioo.healthcare.gcp.vision.model;

import com.google.cloud.vision.v1.Word;

/**
 * Represents a single word extracted from an image.
 *
 * @param text       The word text
 * @param confidence Confidence score (0.0 - 1.0) for the detected word
 * @param geometry   Bounding box and vertex information
 *
 * @since 0.1.0
 */
public record TextWord(
        String text,
        Float confidence,
        TextGeometry geometry
) {
    /**
     * Create TextWord from Google Cloud Vision Word.
     */
    public static TextWord from(Word gcpWord) {
        if (gcpWord == null) {
            return null;
        }

        // Extract text from all symbols
        StringBuilder textBuilder = new StringBuilder();
        gcpWord.getSymbolsList().forEach(symbol -> {
            String symbolText = symbol.getText();
            if (symbolText != null && !symbolText.isEmpty()) {
                textBuilder.append(symbolText);
            }
        });

        String text = textBuilder.toString();

        // Extract confidence
        Float confidence = gcpWord.getConfidence() > 0.0f
                ? gcpWord.getConfidence()
                : null;

        // Extract geometry
        TextGeometry geometry = gcpWord.hasBoundingBox()
                ? TextGeometry.from(gcpWord.getBoundingBox())
                : null;

        return new TextWord(text, confidence, geometry);
    }
}
