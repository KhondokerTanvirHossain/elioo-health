package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.elioo.baymax.extraction.domain.PageOcr;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Where a crop comes from, and the invariant: a stored crop always contains its item's text. */
class CropCutterTest {

    private final CropCutter cutter = new CropCutter(new BaymaxProperties());

    /** SpanLocatorTest's page, with a white image big enough for its boxes. */
    static PageOcr page() throws Exception {
        PageOcr p = SpanLocatorTest.PAGE;
        BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return new PageOcr(1, p.text(), p.words(), Base64.getEncoder().encodeToString(out.toByteArray()), 0.9);
    }

    static int heightOf(byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg)).getHeight();
    }

    /** Acceptance: the follow-up whose offsets pointed past the text resolves to a crop containing it. */
    @Test
    void aFollowUpWithDriftedOffsetsIsCroppedFromItsOwnLine() throws Exception {
        Map<Integer, PageOcr> pages = Map.of(1, page());
        CropCutter.Cut cut = cutter.cut(new ExtractionResult.SourceSpan(1, 428, 452), "Follow up after 1 month", pages);
        assertThat(cut.outcome()).isEqualTo(CropCutter.Outcome.RELOCATED);
        assertThat(cut.bytes()).isPresent();
        // the crop is one line high (20px words + 12px padding each side), not the page
        assertThat(heightOf(cut.bytes().get())).isLessThan(60);
    }

    /** The regression: offsets that land on the wrong words used to yield a crop of those wrong words. */
    @Test
    void aSpanOnTheWrongWordsIsNotCroppedThereButWhereTheTextIs() throws Exception {
        Map<Integer, PageOcr> pages = Map.of(1, page());
        // [0,20) is the name line; the item is the medicine
        CropCutter.Cut cut = cutter.cut(new ExtractionResult.SourceSpan(1, 0, 20), "Tab. Demoprol", pages);
        assertThat(cut.outcome()).isEqualTo(CropCutter.Outcome.RELOCATED);
        assertThat(cut.bytes()).isPresent();
    }

    @Test
    void offsetsThatAreRightAreUsedAsIs() throws Exception {
        Map<Integer, PageOcr> pages = Map.of(1, page());
        int start = SpanLocatorTest.PAGE.text().indexOf("Tab . Demoprol");
        CropCutter.Cut cut = cutter.cut(new ExtractionResult.SourceSpan(1, start, start + 14), "Tab. Demoprol", pages);
        assertThat(cut.outcome()).isEqualTo(CropCutter.Outcome.CLAIMED_SPAN);
    }

    /** Safety: text that is not on the page gets no crop and so is never stored or surfaced. */
    @Test
    void textNotOnThePageYieldsNoCrop() throws Exception {
        CropCutter.Cut cut = cutter.cut(new ExtractionResult.SourceSpan(1, 10, 30), "Tab. Zorbaflex", Map.of(1, page()));
        assertThat(cut.outcome()).isEqualTo(CropCutter.Outcome.TEXT_NOT_ON_PAGE);
        assertThat(cut.bytes()).isEmpty();
    }

    @Test
    void theDiastolicReadingGetsTheBloodPressureLine() throws Exception {
        Map<Integer, PageOcr> pages = Map.of(1, page());
        CropCutter.Cut systolic = cutter.cutValue(new ExtractionResult.SourceSpan(1, 0, 20), "BP", "130", pages);
        CropCutter.Cut diastolic = cutter.cutValue(new ExtractionResult.SourceSpan(1, 0, 20), "BP", "80", pages);
        assertThat(systolic.bytes()).isPresent();
        assertThat(diastolic.bytes()).isPresent();
        assertThat(diastolic.outcome()).isEqualTo(CropCutter.Outcome.RELOCATED);
        // a value whose name is not on any line with it is not cropped from some other "80"
        assertThat(cutter.cutValue(new ExtractionResult.SourceSpan(1, 0, 20), "HbA1c", "80", pages).bytes()).isEmpty();
    }

    /** Safety: never widen a crop to the page. */
    @Test
    void aRunOfWordsCoveringMostOfThePageIsRefused() throws Exception {
        PageOcr p = page();
        // pretend every word sits in one giant box spanning the page height
        List<PageOcr.PositionedWord> tall = p.words().stream()
                .map(w -> new PageOcr.PositionedWord(w.text(), w.start(), w.end(), w.left(), 0, w.right(), 199)).toList();
        PageOcr tallPage = new PageOcr(1, p.text(), tall, p.imageBase64(), 0.9);
        CropCutter.Cut cut = cutter.cut(new ExtractionResult.SourceSpan(1, 0, 5), "Name", Map.of(1, tallPage));
        assertThat(cut.bytes()).isEmpty();
    }
}
