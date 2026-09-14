package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRendererTest {

    private final PageRenderer renderer = new PageRenderer();

    private static byte[] image(String format, int w, int h, int type) throws IOException {
        BufferedImage img = new BufferedImage(w, h, type);
        var g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void aJpegIsPassedThroughUntouched() throws Exception {
        byte[] jpeg = image("jpg", 400, 300, BufferedImage.TYPE_INT_RGB);

        List<byte[]> pages = renderer.toJpegPages(jpeg, "report.jpg", 10);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0)).isSameAs(jpeg);
    }

    @Test
    void aPngIsReEncodedAsJpeg() throws Exception {
        byte[] png = image("png", 400, 300, BufferedImage.TYPE_INT_ARGB);

        List<byte[]> pages = renderer.toJpegPages(png, "report.png", 10);

        assertThat(pages).hasSize(1);
        assertThat(PageRenderer.isJpeg(pages.get(0))).isTrue();
        assertThat(ImageIO.read(new ByteArrayInputStream(pages.get(0)))).isNotNull();
    }

    @Test
    void aVeryLargeImageIsDownscaled() throws Exception {
        byte[] huge = image("png", 4000, 3000, BufferedImage.TYPE_INT_RGB);

        BufferedImage rendered = ImageIO.read(new ByteArrayInputStream(
                renderer.toJpegPages(huge, "huge.png", 10).get(0)));

        assertThat(Math.max(rendered.getWidth(), rendered.getHeight())).isEqualTo(PageRenderer.MAX_EDGE_PX);
        assertThat(rendered.getWidth()).isGreaterThan(rendered.getHeight());
    }

    @Test
    void anEmptyFileIsRejected() {
        assertThatThrownBy(() -> renderer.toJpegPages(new byte[0], "empty.jpg", 10))
                .isInstanceOf(BaymaxException.class)
                .satisfies(e -> assertThat(((BaymaxException) e).reason()).isEqualTo("empty_file"));
    }

    @Test
    void somethingThatIsNotAnImageIsRejectedWithAdvice() {
        assertThatThrownBy(() -> renderer.toJpegPages("not an image".getBytes(), "notes.txt", 10))
                .isInstanceOf(BaymaxException.class)
                .hasMessageContaining("JPEG, PNG or PDF");
    }

    @Test
    void theFilenameInAnErrorIsSanitised() {
        assertThatThrownBy(() -> renderer.toJpegPages("x".getBytes(), "../../etc/passwd", 10))
                .hasMessageNotContaining("../")
                .hasMessageContaining("_.._etc_passwd");
    }

    @Test
    void pdfDetectionLooksAtTheMagicBytes() {
        assertThat(PageRenderer.isPdf("%PDF-1.4 ...".getBytes())).isTrue();
        assertThat(PageRenderer.isPdf("PDF".getBytes())).isFalse();
        assertThat(PageRenderer.isPdf(new byte[]{1, 2})).isFalse();
    }
}
