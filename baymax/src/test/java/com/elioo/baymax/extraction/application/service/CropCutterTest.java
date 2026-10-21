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
        return page(200);
    }

    /** The same page on a taller image, where a two-line span is not refused as "most of the page". */
    static PageOcr page(int height) throws Exception {
        PageOcr p = SpanLocatorTest.PAGE;
        BufferedImage img = new BufferedImage(400, height, BufferedImage.TYPE_INT_RGB);
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

    /**
     * Plan B6: the OCR line a value was cut from is what pairs a blood-pressure reading. The systolic is found by its
     * text and the diastolic on its named line (two different paths through cutValue) and both name the one line, as
     * {@code <page>:<start>-<end>} of that line in the page text; a value on the next line names another.
     */
    @Test
    void theSystolicAndDiastolicOfOneLineShareItsLine() throws Exception {
        Map<Integer, PageOcr> pages = Map.of(1, page());
        String text = SpanLocatorTest.PAGE.text();
        int bpStart = text.indexOf("BP : 130 / 80 mmHg");
        String bpLine = "1:" + bpStart + "-" + (bpStart + "BP : 130 / 80 mmHg".length());

        CropCutter.Cut systolic = cutter.cutValue(new ExtractionResult.SourceSpan(1, 0, 20), "BP", "130", pages);
        CropCutter.Cut diastolic = cutter.cutValue(new ExtractionResult.SourceSpan(1, 0, 20), "BP", "80", pages);
        assertThat(systolic.bytes()).isPresent();
        assertThat(diastolic.bytes()).isPresent();
        assertThat(systolic.line()).contains(bpLine);
        assertThat(diastolic.line()).contains(bpLine);

        // the claimed span path: offsets that are right name the same line
        int at = text.indexOf("130");
        CropCutter.Cut claimed = cutter.cutValue(new ExtractionResult.SourceSpan(1, bpStart, at + 3), "BP", "130", pages);
        assertThat(claimed.outcome()).isEqualTo(CropCutter.Outcome.CLAIMED_SPAN);
        assertThat(claimed.line()).contains(bpLine);

        CropCutter.Cut nextLine = cutter.cutValue(new ExtractionResult.SourceSpan(1, 0, 20), "Demoprol", "50", pages);
        assertThat(nextLine.bytes()).isPresent();
        assertThat(nextLine.line()).isPresent();
        assertThat(nextLine.line()).isNotEqualTo(systolic.line());
    }

    /**
     * Review of #122, finding 1: the line is the OCR line that holds the value's own text, exactly that line's
     * {@code [start,end)}, never the model's span widened to every word it touches. Model offsets drift (DR-12); a drifted
     * span that still contains {@code BP 130} must name the BP line, or the systolic and the diastolic of one printed
     * reading carry two keys and never pair. Both directions: a span that starts on the line above, and one that runs on
     * into the next, still name the BP line; a value on another line still names that other line.
     */
    @Test
    void aDriftedClaimedSpanNamesTheLineThatHoldsTheValueAndNothingWider() throws Exception {
        // a 600px page, so a crop of two 20px lines is a crop and not refused as the page (MAX_CROP_PAGE_FRACTION)
        Map<Integer, PageOcr> pages = Map.of(1, page(600));
        String text = SpanLocatorTest.PAGE.text();
        int bpStart = text.indexOf("BP : 130 / 80 mmHg");
        String bpLine = "1:" + bpStart + "-" + (bpStart + "BP : 130 / 80 mmHg".length());
        assertThat(bpLine).as("the test page's BP line").isEqualTo("1:31-49");

        // (1) starts on the previous line: "58 Y\nBP : 130"
        CropCutter.Cut fromAbove = cutter.cutValue(new ExtractionResult.SourceSpan(1, 26, 39), "BP", "130", pages);
        assertThat(fromAbove.outcome()).isEqualTo(CropCutter.Outcome.CLAIMED_SPAN);
        assertThat(fromAbove.line()).contains("1:31-49");

        // (2) runs on into the Rx line: "BP : 130 / 80 mmHg\nRx"
        int rxEnd = text.indexOf("Rx") + 2;
        CropCutter.Cut intoNext = cutter.cutValue(new ExtractionResult.SourceSpan(1, bpStart, rxEnd), "BP", "130", pages);
        assertThat(intoNext.outcome()).isEqualTo(CropCutter.Outcome.CLAIMED_SPAN);
        assertThat(intoNext.line()).contains("1:31-49");

        // (3) the other direction: a value on another line names that line, exactly
        String rxLineText = "1. Tab . Demoprol 50 mg";
        int rxLine = text.indexOf(rxLineText);
        int demoprol = text.indexOf("Demoprol");
        CropCutter.Cut other = cutter.cutValue(new ExtractionResult.SourceSpan(1, demoprol, demoprol + "Demoprol 50".length()),
                "Demoprol", "50", pages);
        assertThat(other.outcome()).isEqualTo(CropCutter.Outcome.CLAIMED_SPAN);
        assertThat(other.line()).contains("1:" + rxLine + "-" + (rxLine + rxLineText.length()));
        assertThat(other.line()).isNotEqualTo(fromAbove.line());
    }

    /** A cut that failed, or one cut from a region of the image, names no OCR line. */
    @Test
    void aFailedCutNamesNoLine() throws Exception {
        Map<Integer, PageOcr> pages = Map.of(1, page());
        assertThat(cutter.cutValue(new ExtractionResult.SourceSpan(1, 0, 20), "HbA1c", "80", pages).line()).isEmpty();
        assertThat(cutter.cut(new ExtractionResult.SourceSpan(1, 10, 30), "Tab. Zorbaflex", pages).line()).isEmpty();
        assertThat(cutter.cutRegion(new ExtractionResult.SourceRegion(1, 0.1, 0.1, 0.3, 0.2), pages).line()).isEmpty();
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
