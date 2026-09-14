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
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CropCutterTest {

    private static final int PAGE_W = 600;
    private static final int PAGE_H = 400;

    private static CropCutter cutter(int padding) {
        BaymaxProperties props = new BaymaxProperties();
        props.getExtract().setCropPaddingPx(padding);
        return new CropCutter(props);
    }

    /** A page with the words "HbA1c" and "8.2" laid out at known boxes. */
    private static PageOcr page() throws IOException {
        BufferedImage image = new BufferedImage(PAGE_W, PAGE_H, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, PAGE_W, PAGE_H);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        String base64 = Base64.getEncoder().encodeToString(out.toByteArray());

        // text: "HbA1c 8.2"  →  offsets 0..5 and 6..9
        List<PageOcr.PositionedWord> words = List.of(
                new PageOcr.PositionedWord("HbA1c", 0, 5, 100, 100, 180, 130),
                new PageOcr.PositionedWord("8.2", 6, 9, 200, 100, 240, 130));
        return new PageOcr(1, "HbA1c 8.2", words, base64, 0.9);
    }

    @Test
    void cutsTheUnionOfEveryWordTouchingTheSpan() throws Exception {
        PageOcr page = page();
        Optional<byte[]> crop = cutter(10).cut(new ExtractionResult.SourceSpan(1, 0, 9),
                Map.of(1, page));

        assertThat(crop).isPresent();
        BufferedImage cut = ImageIO.read(new ByteArrayInputStream(crop.get()));
        // union is x 100..240, y 100..130, padded by 10 on every side
        assertThat(cut.getWidth()).isEqualTo(160);
        assertThat(cut.getHeight()).isEqualTo(50);
    }

    @Test
    void aSpanOverOneWordCutsOnlyThatWord() throws Exception {
        Optional<byte[]> crop = cutter(0).cut(new ExtractionResult.SourceSpan(1, 6, 9), Map.of(1, page()));

        BufferedImage cut = ImageIO.read(new ByteArrayInputStream(crop.get()));
        assertThat(cut.getWidth()).isEqualTo(40);
        assertThat(cut.getHeight()).isEqualTo(30);
    }

    @Test
    void paddingIsClampedToThePageEdges() throws Exception {
        PageOcr page = new PageOcr(1, "edge",
                List.of(new PageOcr.PositionedWord("edge", 0, 4, 0, 0, 40, 20)),
                page().imageBase64(), 0.9);

        Optional<byte[]> crop = cutter(50).cut(new ExtractionResult.SourceSpan(1, 0, 4), Map.of(1, page));

        assertThat(crop).isPresent();
        BufferedImage cut = ImageIO.read(new ByteArrayInputStream(crop.get()));
        assertThat(cut.getWidth()).isLessThanOrEqualTo(PAGE_W);
        assertThat(cut.getHeight()).isLessThanOrEqualTo(PAGE_H);
    }

    @Test
    void noCropWhenTheSpanMatchesNoWord() throws Exception {
        assertThat(cutter(10).cut(new ExtractionResult.SourceSpan(1, 50, 60), Map.of(1, page()))).isEmpty();
    }

    @Test
    void noCropWhenThePageIsUnknownOrTheSpanIsUnusable() throws Exception {
        Map<Integer, PageOcr> pages = Map.of(1, page());
        CropCutter c = cutter(10);
        assertThat(c.cut(new ExtractionResult.SourceSpan(9, 0, 5), pages)).isEmpty();
        assertThat(c.cut(new ExtractionResult.SourceSpan(1, 5, 5), pages)).isEmpty();
        assertThat(c.cut(new ExtractionResult.SourceSpan(0, 0, 5), pages)).isEmpty();
        assertThat(c.cut(null, pages)).isEmpty();
    }

    @Test
    void wordsWithoutGeometryAreIgnored() throws Exception {
        PageOcr page = new PageOcr(1, "blind",
                List.of(new PageOcr.PositionedWord("blind", 0, 5, -1, -1, -1, -1)),
                page().imageBase64(), 0.5);

        assertThat(cutter(10).cut(new ExtractionResult.SourceSpan(1, 0, 5), Map.of(1, page))).isEmpty();
    }
}
