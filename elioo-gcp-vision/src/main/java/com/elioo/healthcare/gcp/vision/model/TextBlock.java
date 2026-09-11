package com.elioo.healthcare.gcp.vision.model;

import com.google.cloud.vision.v1.Block;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a block of text (typically a paragraph) extracted from an image.
 *
 * <p>A text block is a logical grouping of text, usually corresponding to a paragraph
 * or section in the document. Blocks contain paragraphs, which contain words, which
 * contain symbols.</p>
 *
 * <p><b>Hierarchy:</b></p>
 * <pre>
 * Page
 *  └── Block (this class)
 *       └── Paragraph
 *            └── Word
 *                 └── Symbol
 * </pre>
 *
 * @param text             The complete text content of the block
 * @param detectedLanguage Detected language code (ISO 639-1), e.g., "bn", "en"
 * @param confidence       Confidence score (0.0 - 1.0) for the detected text
 * @param geometry         Bounding box and vertex information
 * @param paragraphs       List of paragraphs within this block
 * @param blockIndex       Index of this block within the page
 * @param blockType        Type of block (e.g., "TEXT", "TABLE", "PICTURE")
 *
 * @since 0.1.0
 */
public record TextBlock(
        String text,
        String detectedLanguage,
        Float confidence,
        TextGeometry geometry,
        List<TextParagraph> paragraphs,
        Integer blockIndex,
        String blockType
) {
    /**
     * Create TextBlock from Google Cloud Vision Block.
     *
     * @param gcpBlock     The GCP Vision API Block object
     * @param blockIndex   Index of this block
     * @param detectedLang Detected language for the block
     * @return TextBlock instance
     */
    public static TextBlock from(Block gcpBlock, int blockIndex, String detectedLang) {
        if (gcpBlock == null) {
            return null;
        }

        // Extract text from all paragraphs
        StringBuilder textBuilder = new StringBuilder();
        gcpBlock.getParagraphsList().forEach(para -> {
            para.getWordsList().forEach(word -> {
                word.getSymbolsList().forEach(symbol -> {
                    String symbolText = symbol.getText();
                    if (symbolText != null && !symbolText.isEmpty()) {
                        textBuilder.append(symbolText);
                    }
                });
                textBuilder.append(" ");
            });
        });

        String text = textBuilder.toString().trim();

        // Calculate average confidence
        Float confidence = gcpBlock.getConfidence() > 0.0f ? gcpBlock.getConfidence() : null;

        // Extract geometry
        TextGeometry geometry = gcpBlock.hasBoundingBox()
                ? TextGeometry.from(gcpBlock.getBoundingBox())
                : null;

        // Extract paragraphs
        List<TextParagraph> paragraphs = gcpBlock.getParagraphsList().stream()
                .map(TextParagraph::from)
                .collect(Collectors.toList());

        // Determine block type
        String blockType = gcpBlock.getBlockType() != null
                ? gcpBlock.getBlockType().name()
                : "TEXT";

        return new TextBlock(
                text,
                detectedLang,
                confidence,
                geometry,
                paragraphs,
                blockIndex,
                blockType
        );
    }

    /**
     * Check if this block meets a minimum confidence threshold.
     *
     * @param threshold Minimum confidence (0.0 - 1.0)
     * @return true if confidence >= threshold, false otherwise
     */
    public boolean meetsConfidence(double threshold) {
        return confidence != null && confidence >= threshold;
    }

    /**
     * Check if this block contains text.
     *
     * @return true if text is not null and not empty
     */
    public boolean hasText() {
        return text != null && !text.isEmpty();
    }

    /**
     * Get the number of paragraphs in this block.
     *
     * @return Paragraph count
     */
    public int getParagraphCount() {
        return paragraphs != null ? paragraphs.size() : 0;
    }
}
