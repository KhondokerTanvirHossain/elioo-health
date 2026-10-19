package com.elioo.baymax.testsupport;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The shapes of the try documents (~/medioo-try-docs, fictional), built in the test and never read from disk: a
 * photographed page (JPEG), a lab's two-page PDF (t5) and a Word letter (t6). Nothing in them is a real patient.
 */
public final class TryDocuments {

    private TryDocuments() {
    }

    /** A blank JPEG of this size: the shape of a phone photo of one page. */
    public static byte[] jpeg(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    /** t5: a PDF of two A4 pages. */
    public static byte[] twoPagePdf() throws IOException {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage(PDRectangle.A4));
            pdf.addPage(new PDPage(PDRectangle.A4));
            pdf.save(out);
            return out.toByteArray();
        }
    }

    /** The shape of t6-letter.docx: a ZIP (PK\3\4) with a content-types part. Nothing in it is an image. */
    public static byte[] docx() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
