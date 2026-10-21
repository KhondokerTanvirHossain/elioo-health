package com.elioo.baymax.storage.domain;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

/**
 * An image's size in pixels, read from its header and never from its raster (wave 2 plan D4b). A 6000×6000 page
 * decoded whole is about 108 MB of pixels on a container shared with MedScribe; its header is a few hundred bytes.
 *
 * <p>The stream is cached in memory, never in a temporary file: these are photos of a family's medical papers, and
 * {@link ImageIO#createImageInputStream} would spool them to disk when its file cache is on.</p>
 *
 * @param width  pixels across, always positive
 * @param height pixels down, always positive
 */
public record ImageDimensions(int width, int height) {

    public ImageDimensions {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("an image size is positive, got " + width + "x" + height);
        }
    }

    /**
     * The size the image's header declares. Empty, never zero, for bytes over {@code maxBytes} (not read at all), for
     * bytes no installed reader recognises, for a header that does not read, and for a declared size that is not
     * positive. The caller passes its upload cap ({@code baymax.upload.max-file-bytes}): this record is domain and reads
     * no configuration.
     */
    public static Optional<ImageDimensions> of(byte[] bytes, long maxBytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > maxBytes) {
            return Optional.empty();
        }
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > 0 ? Optional.of(new ImageDimensions(width, height)) : Optional.empty();
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
