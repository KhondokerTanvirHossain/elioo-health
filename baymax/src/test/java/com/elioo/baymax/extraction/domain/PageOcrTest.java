package com.elioo.baymax.extraction.domain;

import com.elioo.healthcare.gcp.vision.model.TextBlock;
import com.elioo.healthcare.gcp.vision.model.TextGeometry;
import com.elioo.healthcare.gcp.vision.model.TextParagraph;
import com.elioo.healthcare.gcp.vision.model.TextWord;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageOcrTest {

    private static TextWord word(String text, int left, int top, int right, int bottom) {
        return new TextWord(text, 0.9f, new TextGeometry(
                new TextGeometry.BoundingBox(List.of(
                        new TextGeometry.Point(left, top), new TextGeometry.Point(right, top),
                        new TextGeometry.Point(right, bottom), new TextGeometry.Point(left, bottom))),
                List.of()));
    }

    private static VisionOcrResponse response(List<List<TextWord>> paragraphs) {
        List<TextParagraph> paras = paragraphs.stream()
                .map(words -> new TextParagraph("", 0.9f, null, words))
                .toList();
        return new VisionOcrResponse("ignored", List.of(new TextBlock("", "en", 0.9f, null, paras, 0, "TEXT")),
                List.of(), 0.9, null);
    }

    @Test
    void offsetsLineUpWithTheTextHandedToTheModel() {
        PageOcr page = PageOcr.from(1, "aGk=", response(List.of(
                List.of(word("HbA1c", 100, 100, 180, 130), word("8.2", 200, 100, 240, 130)))));

        assertThat(page.text()).isEqualTo("HbA1c 8.2\n");
        assertThat(page.words()).hasSize(2);
        assertThat(page.text().substring(page.words().get(0).start(), page.words().get(0).end()))
                .isEqualTo("HbA1c");
        assertThat(page.text().substring(page.words().get(1).start(), page.words().get(1).end()))
                .isEqualTo("8.2");
    }

    @Test
    void paragraphsAreSeparatedByNewlinesWithoutATrailingSpace() {
        PageOcr page = PageOcr.from(1, "aGk=", response(List.of(
                List.of(word("Line", 0, 0, 40, 10), word("one", 50, 0, 80, 10)),
                List.of(word("Line", 0, 20, 40, 30), word("two", 50, 20, 80, 30)))));

        assertThat(page.text()).isEqualTo("Line one\nLine two\n");
        assertThat(page.words().get(2).start()).isEqualTo("Line one\n".length());
    }

    @Test
    void boundingBoxesAreTheMinMaxOfTheVertices() {
        PageOcr page = PageOcr.from(1, "aGk=", response(List.of(
                List.of(word("x", 30, 40, 90, 70)))));

        PageOcr.PositionedWord w = page.words().get(0);
        assertThat(w.left()).isEqualTo(30);
        assertThat(w.top()).isEqualTo(40);
        assertThat(w.right()).isEqualTo(90);
        assertThat(w.bottom()).isEqualTo(70);
        assertThat(page.hasGeometry()).isTrue();
    }

    @Test
    void overlapIsHalfOpenSoAdjacentWordsDoNotBleed() {
        PageOcr.PositionedWord w = new PageOcr.PositionedWord("x", 5, 10, 0, 0, 1, 1);

        assertThat(w.overlaps(0, 5)).isFalse();
        assertThat(w.overlaps(10, 20)).isFalse();
        assertThat(w.overlaps(4, 6)).isTrue();
        assertThat(w.overlaps(5, 10)).isTrue();
    }

    @Test
    void aPageWithNoGeometryReportsSo() {
        TextWord blind = new TextWord("word", 0.5f, null);
        PageOcr page = PageOcr.from(1, "aGk=", response(List.of(List.of(blind))));

        assertThat(page.hasGeometry()).isFalse();
        assertThat(page.text()).isEqualTo("word\n");
    }

    @Test
    void blankWordsAreSkipped() {
        PageOcr page = PageOcr.from(1, "aGk=", response(List.of(
                List.of(word("real", 0, 0, 10, 10), new TextWord("  ", 0.9f, null)))));

        assertThat(page.words()).hasSize(1);
        assertThat(page.text()).isEqualTo("real\n");
    }
}
