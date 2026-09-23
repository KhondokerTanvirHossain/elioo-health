package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.elioo.baymax.extraction.domain.PageOcr;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cutting a crop from the model's region instead of from OCR word boxes.
 *
 * <p>This is the path that exists because 30 of 85 values in batch 2 were lost as {@code TEXT_NOT_ON_PAGE}:
 * the value is legible in the page image and absent from the OCR text, so no word box can be found for it.
 * Cutting from pixels sidesteps OCR entirely.</p>
 *
 * <p>What is cut here is never trusted on its own — {@code CropVerifier} re-reads it before it may be
 * stored. The cutter's only jobs are to cut the right rectangle and to refuse a region that is really the
 * page.</p>
 */
class CropCutterRegionTest {

    private static final int W = 1000;
    private static final int H = 800;

    private final CropCutter cutter = new CropCutter(new BaymaxProperties());

    /** A page with no OCR words at all — exactly the case that loses values today. */
    private static PageOcr pageWithNoWords() {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.fillRect(100, 160, 500, 40);
        g.dispose();
        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return new PageOcr(1, "", List.of(),
                    Base64.getEncoder().encodeToString(out.toByteArray()), 0.9);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<Integer, PageOcr> pages() {
        return Map.of(1, pageWithNoWords());
    }

    private static BufferedImage decode(byte[] jpeg) {
        try {
            return ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The recovery case: no OCR word anywhere, and a crop is still cut from the region. */
    @Test
    void aRegionIsCutEvenWhenThePageHasNoOcrWords() {
        var region = new ExtractionResult.SourceRegion(1, 0.10, 0.20, 0.60, 0.25);

        CropCutter.Cut cut = cutter.cutRegion(region, pages());

        assertThat(cut.outcome()).isEqualTo(CropCutter.Outcome.MODEL_REGION);
        assertThat(cut.bytes()).isPresent();

        BufferedImage crop = decode(cut.bytes().orElseThrow());
        assertThat(crop.getWidth()).as("0.10-0.60 of 1000px, plus padding").isBetween(500, 560);
        assertThat(crop.getHeight()).as("0.20-0.25 of 800px, plus padding").isBetween(40, 100);
    }

    /**
     * A region covering most of the page is the model shrugging. Cutting it would hand the family a picture
     * of the whole report as the source of one number — DR-12's failure with a new cause.
     */
    @Test
    void aRegionCoveringMostOfThePageIsRefused() {
        var wholePage = new ExtractionResult.SourceRegion(1, 0.0, 0.0, 1.0, 1.0);

        CropCutter.Cut cut = cutter.cutRegion(wholePage, pages());

        assertThat(cut.outcome()).isEqualTo(CropCutter.Outcome.REGION_TOO_LARGE);
        assertThat(cut.bytes()).isEmpty();
    }

    @Test
    void anUnusableRegionIsRefused() {
        assertThat(cutter.cutRegion(null, pages()).outcome())
                .isEqualTo(CropCutter.Outcome.NO_REGION);
        assertThat(cutter.cutRegion(new ExtractionResult.SourceRegion(1, 0.5, 0.2, 0.5, 0.25), pages()).outcome())
                .as("zero width").isEqualTo(CropCutter.Outcome.NO_REGION);
    }

    @Test
    void aRegionOnAPageThatIsNotThereIsRefused() {
        var region = new ExtractionResult.SourceRegion(7, 0.1, 0.2, 0.6, 0.25);

        assertThat(cutter.cutRegion(region, pages()).outcome())
                .isEqualTo(CropCutter.Outcome.PAGE_MISSING);
    }

    /** A region flush against the page edge must not cut outside the image. */
    @Test
    void aRegionAtTheEdgeStaysInsideTheImage() {
        var edge = new ExtractionResult.SourceRegion(1, 0.0, 0.0, 0.5, 0.05);

        CropCutter.Cut cut = cutter.cutRegion(edge, pages());

        assertThat(cut.bytes()).isPresent();
        BufferedImage crop = decode(cut.bytes().orElseThrow());
        assertThat(crop.getWidth()).isLessThanOrEqualTo(W);
        assertThat(crop.getHeight()).isLessThanOrEqualTo(H);
    }
}
