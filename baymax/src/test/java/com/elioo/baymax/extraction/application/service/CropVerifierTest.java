package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.extraction.domain.ExtractionResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A crop is stored only when something OTHER than the extraction confirms what it shows.
 *
 * <p>This is DR-12's rule, and the image-side route is where it is easiest to lose. The model now points at
 * a region of the page and we cut it — but the model pointing at a box is not evidence that the box contains
 * the value. Trusting it would reproduce the exact failure DR-12 was written for: 137 crops stored, 116
 * showing the neighbouring words, and a test that only checked a file existed. The model's box is the
 * proposal; the verification is the check; they must not come from the same source.</p>
 *
 * <p>So every cut region is re-read ALONE, with no page context and no knowledge of what was expected:
 * OCR on the crop first (cheap, and usually enough once the crop is tight), and only if that yields nothing
 * does a separate model call see the crop by itself. The value must then appear in what comes back, under
 * the same digit-boundary rule as {@link SpanLocator#valueAppearsOn} — so a crop of "12.1" can never verify
 * a value of "2.1".</p>
 *
 * <p>Both directions, because a one-directional guard passes the safe case and hides the dangerous one:
 * lab10's "2.1 L" must verify and be shown, and a box pointing at the wrong row must fail and stay unshown.</p>
 */
class CropVerifierTest {

    private static final UUID DOC = UUID.randomUUID();

    /** A crop is just bytes to the verifier; the content comes from whatever re-reads it. */
    private static byte[] anyCrop() {
        try {
            BufferedImage image = new BufferedImage(120, 30, BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 120, 30);
            g.dispose();
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A verifier whose independent re-read returns exactly this text, and which records if it was asked. */
    private static CropVerifier verifierReading(String ocrText, String modelText, List<String> calls) {
        return new CropVerifier(
                (documentId, crop) -> {
                    calls.add("ocr");
                    return Mono.justOrEmpty(ocrText);
                },
                (documentId, crop) -> {
                    calls.add("model");
                    return Mono.justOrEmpty(modelText);
                });
    }

    /** lab10: one value, printed "2.1 L" in a cell, whose crop must now verify and be shown. */
    @Test
    void aCropWhoseIndependentReadingContainsTheValueIsVerified() {
        List<String> calls = new java.util.ArrayList<>();
        CropVerifier verifier = verifierReading("Uric Acid 2.1 L 3.4-7.0", null, calls);

        StepVerifier.create(verifier.verify(DOC, anyCrop(), "Uric Acid", "2.1"))
                .assertNext(outcome -> {
                    assertThat(outcome.verified()).as("the crop shows the value").isTrue();
                    assertThat(outcome.readBy()).isEqualTo(CropVerifier.ReadBy.OCR);
                })
                .verifyComplete();

        assertThat(calls).as("OCR is enough; the model is not paid for").containsExactly("ocr");
    }

    /**
     * THE DIRECTION THAT MATTERS. The model points one row too low and the crop shows a different marker's
     * number. Nothing about the box itself reveals this — only re-reading it does.
     */
    @Test
    void aCropOfTheWrongRowIsNotVerified() {
        List<String> calls = new java.util.ArrayList<>();
        CropVerifier verifier = verifierReading("Potassium 4.2 mmol/L 3.5-5.1", null, calls);

        StepVerifier.create(verifier.verify(DOC, anyCrop(), "Uric Acid", "2.1"))
                .assertNext(outcome -> assertThat(outcome.verified())
                        .as("the crop shows potassium 4.2, not uric acid 2.1")
                        .isFalse())
                .verifyComplete();
    }

    /**
     * The subtle wrong row: the neighbouring value CONTAINS the digits of ours. A substring check would
     * certify this crop, and the family would see a picture of 12.1 labelled 2.1.
     */
    @Test
    void aCropShowingTheValueInsideAnotherNumberIsNotVerified() {
        CropVerifier verifier = verifierReading("Potassium 12.1 3.5-5.1", null, new java.util.ArrayList<>());

        StepVerifier.create(verifier.verify(DOC, anyCrop(), "Potassium", "2.1"))
                .assertNext(outcome -> assertThat(outcome.verified())
                        .as("12.1 is not 2.1 — the digit-boundary rule applies to the crop too")
                        .isFalse())
                .verifyComplete();
    }

    /** When OCR reads nothing on the crop, a model that sees only the crop gets one chance. */
    @Test
    void aCropOcrCannotReadFallsBackToAModelThatSeesOnlyTheCrop() {
        List<String> calls = new java.util.ArrayList<>();
        CropVerifier verifier = verifierReading(null, "Haemoglobin 80.30 L", calls);

        StepVerifier.create(verifier.verify(DOC, anyCrop(), "Haemoglobin", "80.30"))
                .assertNext(outcome -> {
                    assertThat(outcome.verified()).isTrue();
                    assertThat(outcome.readBy()).isEqualTo(CropVerifier.ReadBy.MODEL);
                })
                .verifyComplete();

        assertThat(calls).as("OCR first, model only when OCR found nothing").containsExactly("ocr", "model");
    }

    /** Neither reader could see the value: unverified, and therefore unshown. Exactly as today. */
    @Test
    void aCropNeitherReaderCanConfirmIsNotVerified() {
        CropVerifier verifier = verifierReading(null, null, new java.util.ArrayList<>());

        StepVerifier.create(verifier.verify(DOC, anyCrop(), "Uric Acid", "2.1"))
                .assertNext(outcome -> assertThat(outcome.verified()).isFalse())
                .verifyComplete();
    }

    /**
     * A reader that fails is not a reader that disagreed. An error must leave the value unshown, never
     * verified — and must not take the document down with it.
     */
    @Test
    void aReaderThatFailsLeavesTheValueUnverified() {
        CropVerifier verifier = new CropVerifier(
                (documentId, crop) -> Mono.error(new IllegalStateException("vision is down")),
                (documentId, crop) -> Mono.error(new IllegalStateException("model is down")));

        StepVerifier.create(verifier.verify(DOC, anyCrop(), "Uric Acid", "2.1"))
                .assertNext(outcome -> assertThat(outcome.verified())
                        .as("a failed reading is not a confirmation")
                        .isFalse())
                .verifyComplete();
    }

    /** No crop, nothing to verify — and no reader is paid to look at nothing. */
    @Test
    void anAbsentCropIsNotVerified() {
        List<String> calls = new java.util.ArrayList<>();
        CropVerifier verifier = verifierReading("Uric Acid 2.1", null, calls);

        StepVerifier.create(verifier.verify(DOC, new byte[0], "Uric Acid", "2.1"))
                .assertNext(outcome -> assertThat(outcome.verified()).isFalse())
                .verifyComplete();

        assertThat(calls).as("nothing to read, nothing to pay for").isEmpty();
    }

    /**
     * The name is a hint, not a requirement. A tight crop of a table cell often holds only the number, and
     * demanding the name back would reject the best crops we cut.
     */
    @Test
    void theValueAloneIsEnoughToVerifyACrop() {
        CropVerifier verifier = verifierReading("2.1", null, new java.util.ArrayList<>());

        StepVerifier.create(verifier.verify(DOC, anyCrop(), "Uric Acid", "2.1"))
                .assertNext(outcome -> assertThat(outcome.verified()).isTrue())
                .verifyComplete();
    }

    /** Bangla digits on the page, Western in the extraction: the same folding as everywhere else. */
    @Test
    void aBanglaDigitCropVerifiesAWesternDigitValue() {
        CropVerifier verifier = verifierReading("ইউরিক অ্যাসিড ২.১", null, new java.util.ArrayList<>());

        StepVerifier.create(verifier.verify(DOC, anyCrop(), "Uric Acid", "2.1"))
                .assertNext(outcome -> assertThat(outcome.verified()).isTrue())
                .verifyComplete();
    }
}
