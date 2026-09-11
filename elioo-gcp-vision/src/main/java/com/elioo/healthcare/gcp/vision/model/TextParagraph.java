package com.elioo.healthcare.gcp.vision.model;

import com.google.cloud.vision.v1.Paragraph;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a paragraph of text within a block.
 *
 * <p>A paragraph is a logical grouping of words, usually separated by line breaks
 * or spatial gaps in the document.</p>
 *
 * @param text       The complete text content of the paragraph
 * @param confidence Confidence score (0.0 - 1.0) for the detected text
 * @param geometry   Bounding box and vertex information
 * @param words      List of words within this paragraph
 *
 * @since 0.1.0
 */
public record TextParagraph(
        String text,
        Float confidence,
        TextGeometry geometry,
        List<TextWord> words
) {
    /**
     * Create TextParagraph from Google Cloud Vision Paragraph.
     */
    public static TextParagraph from(Paragraph gcpParagraph) {
        if (gcpParagraph == null) {
            return null;
        }

        // Extract text from all words
        StringBuilder textBuilder = new StringBuilder();
        gcpParagraph.getWordsList().forEach(word -> {
            word.getSymbolsList().forEach(symbol -> {
                String symbolText = symbol.getText();
                if (symbolText != null && !symbolText.isEmpty()) {
                    textBuilder.append(symbolText);
                }
            });
            textBuilder.append(" ");
        });

        String text = textBuilder.toString().trim();

        // Extract confidence
        Float confidence = gcpParagraph.getConfidence() > 0.0f
                ? gcpParagraph.getConfidence()
                : null;

        // Extract geometry
        TextGeometry geometry = gcpParagraph.hasBoundingBox()
                ? TextGeometry.from(gcpParagraph.getBoundingBox())
                : null;

        // Extract words
        List<TextWord> words = gcpParagraph.getWordsList().stream()
                .map(TextWord::from)
                .collect(Collectors.toList());

        return new TextParagraph(text, confidence, geometry, words);
    }
}
