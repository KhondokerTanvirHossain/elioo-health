package com.elioo.baymax.storage.domain;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 2 plan D4b: an image's size is read from its header, never from its raster. A 6000×6000 page decoded whole is
 * about 108 MB of pixels on a 1200 MB container shared with MedScribe; its header is a few hundred bytes.
 */
class ImageDimensionsTest {

    private static final long CAP = 25L * 1024 * 1024;
    private static final long HEAP_BOUND = 16L * 1024 * 1024;

    @Test
    void aLargePageIsMeasuredWithoutDecoding() throws IOException {
        byte[] page = jpeg(6000, 6000);
        List<MemoryPoolMXBean> heap = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP && p.isValid()).toList();

        System.gc();
        long before = heap.stream().mapToLong(p -> p.getUsage().getUsed()).sum();
        heap.forEach(MemoryPoolMXBean::resetPeakUsage);
        Optional<ImageDimensions> measured = ImageDimensions.of(page, CAP);
        // the peak of every heap pool during the call, so a raster allocated and dropped inside it still counts
        long peak = heap.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();

        assertThat(measured).contains(new ImageDimensions(6000, 6000));
        assertThat(peak - before).as("heap growth while measuring a 6000×6000 page (a full decode is ~108 MB)")
                .isLessThan(HEAP_BOUND);
    }

    @Test
    void aPageThatIsNotSquareIsWidthThenHeight() throws IOException {
        assertThat(ImageDimensions.of(jpeg(130, 32), CAP)).contains(new ImageDimensions(130, 32));
    }

    @Test
    void anOversizedInputIsEmptyWithoutReading() throws IOException {
        byte[] small = jpeg(40, 20);
        assertThat(ImageDimensions.of(small, CAP)).as("the same bytes under the cap").contains(new ImageDimensions(40, 20));
        assertThat(ImageDimensions.of(small, small.length - 1)).as("one byte over the cap").isEmpty();
    }

    @Test
    void bytesThatDoNotDecodeAreEmptyNeverZero() {
        assertThat(ImageDimensions.of(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01}, CAP)).isEmpty();
        assertThat(ImageDimensions.of(new byte[0], CAP)).isEmpty();
        assertThat(ImageDimensions.of(null, CAP)).isEmpty();
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(255, 140, 0));
        g.fillRect(0, 0, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
