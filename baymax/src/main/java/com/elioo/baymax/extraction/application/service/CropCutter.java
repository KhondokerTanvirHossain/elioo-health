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
    /** Why a crop was or was not cut; reported per item by the BMX-5b replay and logged at debug. */
    public enum Outcome {
        /** The claimed offsets landed on words that carry the item's text. */
        CLAIMED_SPAN,
        /** The offsets were wrong; the text was found elsewhere on the page and cropped there. */
        RELOCATED,
        /** The page number is not one of the document's pages. */
        PAGE_MISSING,
        /** Offsets point past the end of the page text. */
        BEYOND_TEXT,
        /** Words at the offsets exist but Vision gave them no box, and the text was not found elsewhere. */
        NO_GEOMETRY,
        /** The item's text is not on the page, exactly or fuzzily: the model paraphrased or invented. */
        TEXT_NOT_ON_PAGE,
        /** The text was located but its words carry no box, or the box would be most of the page. */
        UNCROPPABLE
    }

    /** A crop plus how it was found, for callers that want the reason (the replay harness, the log). */
    public record Cut(Outcome outcome, Optional<byte[]> bytes) {
    }

    /** Height above which a "crop" is really the page: never widen to that (BMX-5b safety). */
    static final double MAX_CROP_PAGE_FRACTION = 0.35;

    /**
     * @deprecated since BMX-5b — a span without the item's text cannot be checked. Kept for the replay.
     */
    @Deprecated
    public Optional<byte[]> cut(ExtractionResult.SourceSpan span, Map<Integer, PageOcr> pagesByNumber) {
        return cut(span, null, pagesByNumber).bytes();
    }

    /**
     * The crop for an item: the words the model's offsets point at, if they carry {@code anchor}; otherwise
     * the words where {@code anchor} is actually found on the page; otherwise nothing. A crop is stored
     * only if its words contain the item's text — the invariant "never a value without its source" now
     * means the source really shows the value, not merely a rectangle the model pointed at.
     *
     * @param anchor the item's own text (a value, a medicine name, an instruction); null skips the text
     *               check and behaves as before BMX-5b — replay only
     */
    /**
     * A value's crop: the reading must be in it, with its name on the same line. {@code BP : 130 / 80} yields
     * one line-wide crop for both the systolic and the diastolic, which is what a person would point at.
     */
    public Cut cutValue(ExtractionResult.SourceSpan span, String name, String value, Map<Integer, PageOcr> pagesByNumber) {
        String joined = (name == null ? "" : name + " ") + (value == null ? "" : value);
        Cut direct = cut(span, joined, pagesByNumber);
        if (direct.bytes().isPresent() || value == null || value.isBlank()) {
            return direct;
        }
        PageOcr page = pagesByNumber.get(span == null ? -1 : span.page());
        if (page == null) {
            return direct;
        }
        Optional<SpanLocator.Range> line = SpanLocator.locateValueOnNamedLine(page, span, name, value);
        if (line.isEmpty()) {
            return direct;
        }
        List<PageOcr.PositionedWord> words = page.words().stream()
                .filter(w -> w.left() >= 0 && w.overlaps(line.get().start(), line.get().end())).toList();
        if (!SpanLocator.contains(words, value)) {
            return direct;
        }
        Optional<byte[]> bytes = cutWords(page, words);
        return new Cut(bytes.isPresent() ? Outcome.RELOCATED : Outcome.UNCROPPABLE, bytes);
    }

    public Cut cut(ExtractionResult.SourceSpan span, String anchor, Map<Integer, PageOcr> pagesByNumber) {
        if (span == null || span.page() < 1) {
            return new Cut(Outcome.PAGE_MISSING, Optional.empty());
        }
        PageOcr page = pagesByNumber.get(span.page());
        if (page == null) {
            return new Cut(Outcome.PAGE_MISSING, Optional.empty());
        }
        List<PageOcr.PositionedWord> claimed = span.isUsable() ? page.words().stream()
                .filter(w -> w.overlaps(span.start(), span.end())).toList() : List.of();
        List<PageOcr.PositionedWord> boxed = claimed.stream().filter(w -> w.left() >= 0).toList();

        if (!boxed.isEmpty() && (anchor == null || SpanLocator.contains(boxed, anchor))) {
            return new Cut(Outcome.CLAIMED_SPAN, cutWords(page, boxed));
        }
        if (anchor == null) {
            return new Cut(claimed.isEmpty() ? (span.start() >= page.text().length() ? Outcome.BEYOND_TEXT : Outcome.NO_GEOMETRY)
                    : Outcome.NO_GEOMETRY, Optional.empty());
        }
        Optional<SpanLocator.Range> found = SpanLocator.locate(page, span, anchor);
        if (found.isEmpty()) {
            return new Cut(Outcome.TEXT_NOT_ON_PAGE, Optional.empty());
        }
        // a relocated crop takes the whole OCR line: the model's offsets were wrong, so the exact edges it
        // implied mean nothing, and a line is what a person checking the number would want to see anyway
        SpanLocator.Range line = SpanLocator.lineAround(page, found.get());
        List<PageOcr.PositionedWord> located = page.words().stream()
                .filter(w -> w.left() >= 0 && w.overlaps(line.start(), line.end())).toList();
        if (located.isEmpty() || !SpanLocator.contains(located, anchor)) {
            return new Cut(located.isEmpty() ? Outcome.NO_GEOMETRY : Outcome.UNCROPPABLE, Optional.empty());
        }
        Optional<byte[]> bytes = cutWords(page, located);
        return new Cut(bytes.isPresent() ? Outcome.RELOCATED : Outcome.UNCROPPABLE, bytes);
    }

    private Optional<byte[]> cutWords(PageOcr page, List<PageOcr.PositionedWord> hits) {
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
                log.warn("[baymax] crop skipped: page {} image unreadable", page.pageNo());
                return Optional.empty();
            }
            int x = Math.max(0, left);
            int y = Math.max(0, top);
            int w = Math.min(image.getWidth() - x, right - x);
            int h = Math.min(image.getHeight() - y, bottom - y);
            if (w <= 0 || h <= 0) {
                return Optional.empty();
            }
            if (h > image.getHeight() * MAX_CROP_PAGE_FRACTION) {
                // a run of words spanning a third of the page is not a crop of an item, it is the page
                log.debug("[baymax] crop refused: {}px of a {}px page", h, image.getHeight());
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
            log.warn("[baymax] crop failed on page {}: {}", page.pageNo(), e.getMessage());
            return Optional.empty();
        }
    }

    public static Map<Integer, PageOcr> byPageNumber(List<PageOcr> pages) {
        return pages.stream().collect(Collectors.toMap(PageOcr::pageNo, p -> p));
    }
}
