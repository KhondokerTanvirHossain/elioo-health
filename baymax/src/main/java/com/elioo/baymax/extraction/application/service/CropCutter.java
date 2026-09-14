package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.elioo.baymax.extraction.domain.PageOcr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Turns a source span into the image the family sees beside a number.
 *
 * <p>The span is a character range in a page's OCR text. Every word overlapping that range contributes its
 * bounding box; the union, padded, is cut from the page JPEG. A span that matches no word with geometry
 * yields nothing, and the caller drops that item: the rule is no number without its crop.</p>
 */
@Slf4j
@Component
public class CropCutter {

    private final BaymaxProperties properties;

    public CropCutter(BaymaxProperties properties) {
        this.properties = properties;
    }

    /** The cut crop as JPEG bytes, or empty when the span cannot be located on the page. */
    public Optional<byte[]> cut(ExtractionResult.SourceSpan span, Map<Integer, PageOcr> pagesByNumber) {
        if (span == null || !span.isUsable()) {
            return Optional.empty();
        }
        PageOcr page = pagesByNumber.get(span.page());
        if (page == null) {
            return Optional.empty();
        }
        List<PageOcr.PositionedWord> hits = page.words().stream()
                .filter(w -> w.left() >= 0 && w.overlaps(span.start(), span.end()))
                .toList();
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        int pad = properties.getExtract().getCropPaddingPx();
        int left = hits.stream().mapToInt(PageOcr.PositionedWord::left).min().orElse(0) - pad;
        int top = hits.stream().mapToInt(PageOcr.PositionedWord::top).min().orElse(0) - pad;
        int right = hits.stream().mapToInt(PageOcr.PositionedWord::right).max().orElse(0) + pad;
        int bottom = hits.stream().mapToInt(PageOcr.PositionedWord::bottom).max().orElse(0) + pad;

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                    Base64.getDecoder().decode(page.imageBase64())));
            if (image == null) {
                log.warn("[baymax] crop skipped: page {} image unreadable", span.page());
                return Optional.empty();
            }
            int x = Math.max(0, left);
            int y = Math.max(0, top);
            int w = Math.min(image.getWidth() - x, right - x);
            int h = Math.min(image.getHeight() - y, bottom - y);
            if (w <= 0 || h <= 0) {
                return Optional.empty();
            }
            BufferedImage crop = image.getSubimage(x, y, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(crop, "jpg", out)) {
                return Optional.empty();
            }
            return Optional.of(out.toByteArray());
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException covers RasterFormatException (a subclass) for an out-of-bounds cut,
            // and a malformed base64 page.
            log.warn("[baymax] crop failed on page {}: {}", span.page(), e.getMessage());
            return Optional.empty();
        }
    }

    public static Map<Integer, PageOcr> byPageNumber(List<PageOcr> pages) {
        return pages.stream().collect(Collectors.toMap(PageOcr::pageNo, p -> p));
    }
}
