package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Intake: whatever the family sent becomes an ordered list of JPEG pages, because storage is JPEG-only
 * (BMX-3) and the OCR and vision calls both want one page at a time.
 *
 * <p>PDFs are rendered at {@value #PDF_DPI} DPI, which is enough for Vision to read a phone-photographed
 * lab report without making the image too large for the model's image budget. PNG, WebP and HEIC-as-JPEG
 * are re-encoded; a JPEG that is already a JPEG is passed through untouched so nothing is re-compressed
 * twice.</p>
 */
@Slf4j
@Component
public class PageRenderer {

    static final int PDF_DPI = 200;
    static final int MAX_EDGE_PX = 2200;
    /**
     * JPEG quality for re-encoded pages. ImageIO's default is about 0.75, which smears the thin strokes of
     * handwritten Bangla and of a doctor's hand — exactly the pixels OCR needs most. 0.95 costs a larger
     * file and buys back that detail; storage is cheap next to a misread dose.
     */
    static final float JPEG_QUALITY = 0.95f;
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    /** @return page JPEGs in document order */
    public List<byte[]> toJpegPages(byte[] uploaded, String filename, int maxPages) {
        if (uploaded == null || uploaded.length == 0) {
            throw BaymaxException.badRequest("empty_file", "uploaded file " + safe(filename) + " is empty");
        }
        return isPdf(uploaded) ? renderPdf(uploaded, filename, maxPages) : List.of(toJpeg(uploaded, filename));
    }

    static boolean isPdf(byte[] bytes) {
        if (bytes.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private List<byte[]> renderPdf(byte[] pdf, String filename, int maxPages) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw BaymaxException.badRequest("empty_pdf", "PDF " + safe(filename) + " has no pages");
            }
            if (pages > maxPages) {
                throw BaymaxException.badRequest("too_many_pages",
                        "PDF has " + pages + " pages; the limit is " + maxPages);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            List<byte[]> out = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) {
                out.add(encodeJpeg(downscale(renderer.renderImageWithDPI(i, PDF_DPI, ImageType.RGB))));
            }
            log.info("[baymax] rendered {} PDF page(s) to JPEG", out.size());
            return out;
        } catch (BaymaxException e) {
            throw e;
        } catch (IOException e) {
            throw BaymaxException.badRequest("unreadable_pdf", "could not read the PDF: " + e.getMessage());
        }
    }

    /** JPEG in, JPEG out untouched; anything else is decoded and re-encoded as JPEG. */
    private byte[] toJpeg(byte[] image, String filename) {
        if (isJpeg(image)) {
            return image;
        }
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));
            if (decoded == null) {
                throw BaymaxException.badRequest("unsupported_image",
                        "could not read " + safe(filename) + "; send a JPEG, PNG or PDF");
            }
            return encodeJpeg(downscale(flatten(decoded)));
        } catch (IOException e) {
            throw BaymaxException.badRequest("unsupported_image",
                    "could not read " + safe(filename) + ": " + e.getMessage());
        }
    }

    static boolean isJpeg(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    /** JPEG cannot carry alpha; a PNG with transparency must be composited onto white first. */
    private static BufferedImage flatten(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage flat = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = flat.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return flat;
    }

    /** Keeps the longest edge within {@value #MAX_EDGE_PX}: past that, OCR gains nothing and cost rises. */
    private static BufferedImage downscale(BufferedImage source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= MAX_EDGE_PX) {
            return source;
        }
        double scale = (double) MAX_EDGE_PX / longest;
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), params);
            stream.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw BaymaxException.badRequest("encode_failed", "could not encode the page as JPEG");
        } finally {
            writer.dispose();
        }
    }

    /** Filenames come from the caller; keep them out of logs and errors unless they are plainly safe. */
    private static String safe(String filename) {
        if (filename == null || filename.isBlank()) {
            return "(unnamed)";
        }
        String trimmed = filename.length() > 40 ? filename.substring(0, 40) : filename;
        return trimmed.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
