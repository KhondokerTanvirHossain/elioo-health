package com.elioo.baymax.extraction.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The region of the page image where the model says it read a value.
 *
 * <p>Batch 2 lost 30 of 85 cropped values, every one {@code TEXT_NOT_ON_PAGE}: the locator searches the OCR
 * text, extraction reads the page IMAGE, and a table cell Vision split or missed is readable in one and
 * absent from the other. No string rule over OCR text can find text OCR never produced — the digit-boundary
 * relaxation recovered exactly zero. The region is the way out: the model points at the pixels.</p>
 *
 * <p>Coordinates are normalised 0..1 of page width and height, because the model never sees the rendered
 * pixel size — the page is downscaled before it is sent. Absolute pixels from the model would silently mean
 * a different part of the page at a different scale.</p>
 */
class SourceRegionTest {

    @Test
    void aRegionIsUsableWhenItIsAProperBoxInsideThePage() {
        assertThat(new ExtractionResult.SourceRegion(1, 0.1, 0.2, 0.5, 0.25).isUsable()).isTrue();
    }

    @Test
    void aBoxWithNoAreaIsNotUsable() {
        assertThat(new ExtractionResult.SourceRegion(1, 0.5, 0.2, 0.5, 0.25).isUsable())
                .as("zero width").isFalse();
        assertThat(new ExtractionResult.SourceRegion(1, 0.1, 0.2, 0.5, 0.2).isUsable())
                .as("zero height").isFalse();
        assertThat(new ExtractionResult.SourceRegion(1, 0.6, 0.2, 0.5, 0.25).isUsable())
                .as("right before left").isFalse();
    }

    @Test
    void aBoxOutsideTheUnitSquareIsNotUsable() {
        assertThat(new ExtractionResult.SourceRegion(1, -0.1, 0.2, 0.5, 0.25).isUsable()).isFalse();
        assertThat(new ExtractionResult.SourceRegion(1, 0.1, 0.2, 1.4, 0.25).isUsable()).isFalse();
    }

    @Test
    void aPageNumberBelowOneIsNotUsable() {
        assertThat(new ExtractionResult.SourceRegion(0, 0.1, 0.2, 0.5, 0.25).isUsable()).isFalse();
    }

    /**
     * A "region" covering most of the page is the model shrugging, not pointing. Cropping it would hand the
     * family a picture of the whole report as the source of one number — the DR-12 failure in a new costume,
     * where 116 of 137 crops showed the neighbouring words and the test only checked a file existed.
     */
    @Test
    void aRegionCoveringMostOfThePageIsNotAPointer() {
        assertThat(new ExtractionResult.SourceRegion(1, 0.0, 0.0, 1.0, 1.0).isPointer()).isFalse();
        assertThat(new ExtractionResult.SourceRegion(1, 0.0, 0.0, 1.0, 0.5).isPointer())
                .as("half the page height is still the page").isFalse();
        assertThat(new ExtractionResult.SourceRegion(1, 0.05, 0.40, 0.95, 0.44).isPointer())
                .as("one full-width row is exactly what a person would point at").isTrue();
    }

    /** Scaling to a real image is where an off-by-one becomes a crop of the wrong row. */
    @Test
    void aRegionScalesToPixelsOnThePageItNames() {
        var region = new ExtractionResult.SourceRegion(1, 0.10, 0.20, 0.60, 0.25);

        var box = region.toPixels(1000, 800);

        assertThat(box.left()).isEqualTo(100);
        assertThat(box.top()).isEqualTo(160);
        assertThat(box.right()).isEqualTo(600);
        assertThat(box.bottom()).isEqualTo(200);
    }

    @Test
    void scalingNeverEscapesTheImage() {
        var region = new ExtractionResult.SourceRegion(1, 0.0, 0.0, 1.0, 1.0);

        var box = region.toPixels(640, 480);

        assertThat(box.left()).isZero();
        assertThat(box.top()).isZero();
        assertThat(box.right()).isEqualTo(640);
        assertThat(box.bottom()).isEqualTo(480);
    }
}
