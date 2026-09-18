package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.elioo.baymax.extraction.domain.PageOcr;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The BMX-5b failure mode, pinned: the model's offsets drift by the spaces Vision puts around punctuation,
 * so a span points at the neighbouring words. The locator finds the item's own text instead.
 */
class SpanLocatorTest {

    /** A page as Vision + PageOcr.from would produce it: punctuation as separate words, lines as paragraphs. */
    static PageOcr page(String... lines) {
        StringBuilder text = new StringBuilder();
        List<PageOcr.PositionedWord> words = new ArrayList<>();
        int y = 0;
        for (String line : lines) {
            int x = 0;
            for (String word : line.split(" ")) {
                if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
                    text.append(' ');
                }
                int start = text.length();
                text.append(word);
                words.add(new PageOcr.PositionedWord(word, start, text.length(), x, y, x + 10 * word.length(), y + 20));
                x += 10 * word.length() + 10;
            }
            text.append('\n');
            y += 30;
        }
        return new PageOcr(1, text.toString(), words, "", 0.9);
    }

    static final PageOcr PAGE = page(
            "Name : Demo Patient Age : 58 Y",
            "BP : 130 / 80 mmHg",
            "Rx",
            "1. Tab . Demoprol 50 mg",
            "1 + 0 + 1",
            "Follow up after 1 month");

    @Test
    void aDriftedSpanIsRelocatedToTheItemsOwnText() {
        // the model said [0,20) for the medicine; the words there are the name line
        var found = SpanLocator.locate(PAGE, new ExtractionResult.SourceSpan(1, 0, 20), "Tab. Demoprol");
        assertThat(found).isPresent();
        assertThat(PAGE.text().substring(found.get().start(), found.get().end())).isEqualTo("Tab . Demoprol");
    }

    @Test
    void anOffsetPastTheEndOfThePageStillLocates() {
        var found = SpanLocator.locate(PAGE, new ExtractionResult.SourceSpan(1, 900, 924), "Follow up after 1 month");
        assertThat(found).isPresent();
        assertThat(PAGE.text().substring(found.get().start(), found.get().end())).isEqualTo("Follow up after 1 month");
    }

    @Test
    void banglaNumeralsAndPunctuationSpacingDoNotMatter() {
        assertThat(SpanLocator.locate(PAGE, null, "১+০+১")).isPresent();
        assertThat(SpanLocator.normalize("Tab. Demoprol")).isEqualTo(SpanLocator.normalize("Tab . Demoprol"));
    }

    @Test
    void textNotOnThePageIsNotInvented() {
        assertThat(SpanLocator.locate(PAGE, null, "Tab. Zorbaflex")).isEmpty();
    }

    @Test
    void aSmallOcrErrorIsToleratedALargeOneIsNot() {
        assertThat(SpanLocator.locate(PAGE, null, "Follow up aftr 1 month")).isPresent();   // one char off
        assertThat(SpanLocator.locate(PAGE, null, "Come back next year")).isEmpty();
    }

    @Test
    void containsIsTheInvariantBehindEveryStoredCrop() {
        List<PageOcr.PositionedWord> nameLine = PAGE.words().subList(0, 8);
        assertThat(SpanLocator.contains(nameLine, "BP 130")).isFalse();      // the wrong line, as the drifted span gave
        List<PageOcr.PositionedWord> bpLine = PAGE.words().subList(8, 14);
        assertThat(SpanLocator.contains(bpLine, "BP 130")).isTrue();
        assertThat(SpanLocator.contains(bpLine, "80")).isTrue();
    }

    @Test
    void aDiastolicReadingIsFoundOnTheLineThatCarriesItsName() {
        var line = SpanLocator.locateValueOnNamedLine(PAGE, null, "BP", "80");
        assertThat(line).isPresent();
        assertThat(PAGE.text().substring(line.get().start(), line.get().end())).isEqualTo("BP : 130 / 80 mmHg");
        assertThat(SpanLocator.locateValueOnNamedLine(PAGE, null, "HbA1c", "80")).isEmpty();   // no such line
    }
}
