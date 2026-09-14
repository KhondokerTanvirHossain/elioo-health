package com.elioo.baymax.extraction.domain;

import com.elioo.healthcare.gcp.vision.model.TextGeometry;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * One page after OCR: the text the model reads, and enough geometry to turn any character range of that
 * text back into a rectangle on the image. The two must stay in step, so the text is rebuilt here from the
 * same words whose boxes are recorded, rather than taken from {@code fullText}.
 *
 * @param pageNo    1-based
 * @param text      the OCR text exactly as handed to the model; source spans index into this
 * @param words     every word, in the same order, with the character range it occupies in {@code text}
 * @param imageBase64 the page JPEG, used for the vision call and for cutting crops
 */
public record PageOcr(int pageNo, String text, List<PositionedWord> words, String imageBase64,
                      double averageConfidence) {

    /** A word, where it sits in the page text, and where it sits on the page image. */
    public record PositionedWord(String text, int start, int end, int left, int top, int right, int bottom) {

        public boolean overlaps(int spanStart, int spanEnd) {
            return start < spanEnd && end > spanStart;
        }
    }

    /**
     * Flattens a Vision response into text plus per-word offsets. Words are joined with a single space and
     * blocks with a newline, which is exactly what the prompt shows the model, so the offsets it returns
     * line up with the words recorded here.
     */
    public static PageOcr from(int pageNo, String imageBase64, VisionOcrResponse response) {
        StringBuilder text = new StringBuilder();
        List<PositionedWord> words = new ArrayList<>();
        if (response.blocks() != null) {
            for (var block : response.blocks()) {
                if (block.paragraphs() == null) {
                    continue;
                }
                for (var paragraph : block.paragraphs()) {
                    if (paragraph.words() == null) {
                        continue;
                    }
                    for (var word : paragraph.words()) {
                        String value = word.text();
                        if (value == null || value.isBlank()) {
                            continue;
                        }
                        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
                            text.append(' ');
                        }
                        int start = text.length();
                        text.append(value);
                        words.add(positioned(value, start, text.length(), word.geometry()));
                    }
                    text.append('\n');
                }
            }
        }
        return new PageOcr(pageNo, text.toString(), List.copyOf(words), imageBase64,
                response.averageConfidence());
    }

    private static PositionedWord positioned(String value, int start, int end, TextGeometry geometry) {
        if (geometry == null || geometry.boundingBox() == null || geometry.boundingBox().vertices() == null
                || geometry.boundingBox().vertices().isEmpty()) {
            return new PositionedWord(value, start, end, -1, -1, -1, -1);
        }
        var vertices = geometry.boundingBox().vertices();
        int left = vertices.stream().mapToInt(TextGeometry.Point::x).min().orElse(-1);
        int right = vertices.stream().mapToInt(TextGeometry.Point::x).max().orElse(-1);
        int top = vertices.stream().mapToInt(TextGeometry.Point::y).min().orElse(-1);
        int bottom = vertices.stream().mapToInt(TextGeometry.Point::y).max().orElse(-1);
        return new PositionedWord(value, start, end, left, top, right, bottom);
    }

    /** True when at least one word carries a usable box; without geometry no crop can be cut. */
    public boolean hasGeometry() {
        return words.stream().anyMatch(w -> w.left() >= 0);
    }
}
