package com.elioo.baymax.web.adapter.in;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Every image href a paper is shown with, on every HTML page and in {@code /app/api}: a crop of one stored row, or one
 * whole page. Never a signed URL: each points at a route that signs afresh on every load, so a page left open past the
 * 15-minute signature never shows a broken image. One place, so the route an image loads from can change once (D4a)
 * and every page and the JSON follow ({@code ImageHrefsTest} holds every {@code <img src>} to it).
 */
public final class ImageRefs {

    private static final String BASE = "/app/documents/";

    private ImageRefs() {
    }

    /** The crop of one stored row of this document. A row with no crop has no image: asking for one is a bug (DR-12). */
    public static String crop(UUID documentId, String cropKey) {
        if (cropKey == null || cropKey.isBlank()) {
            throw new IllegalStateException("a row without its crop is never shown");
        }
        return BASE + documentId + "/image?key=" + URLEncoder.encode(cropKey, StandardCharsets.UTF_8);
    }

    /** Page {@code pageNo} (1-based) of this document, whole. */
    public static String page(UUID documentId, int pageNo) {
        return BASE + documentId + "/page/" + pageNo;
    }
}
