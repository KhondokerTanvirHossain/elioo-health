package com.elioo.baymax.web.adapter.in;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Every image href a paper is shown with, on every HTML page and in {@code /app/api}: a crop of one stored row, or one
 * whole page. Never a signed URL: each points at a route that signs afresh on every load, so a page left open past the
 * 15-minute signature never shows a broken image. One place, so the route an image loads from can change once (D4a)
 * and every page and the JSON follow. {@code ImageHrefsTest} renders every page under a sentinel base and holds every
 * image {@code src} and {@code href} to it, so an href built by hand beside this class fails today, not only after D4a;
 * {@code ImageRefsAreTheOnlyImageHrefBuilderTest} refuses the literals anywhere else in the source.
 */
public final class ImageRefs {

    private static final String BASE = "/app/documents/";

    /**
     * A test's base, on its own thread only ({@code ImageRefsSentinel} in the test sources sets it). Unset in production,
     * where every href starts with {@link #BASE}. Thread-local, so a test rendering under the sentinel never changes the
     * hrefs another test is drawing on another thread.
     */
    private static final ThreadLocal<String> TEST_BASE = new ThreadLocal<>();

    private ImageRefs() {
    }

    /** The crop of one stored row of this document. A row with no crop has no image: asking for one is a bug (DR-12). */
    public static String crop(UUID documentId, String cropKey) {
        if (cropKey == null || cropKey.isBlank()) {
            throw new IllegalStateException("a row without its crop is never shown");
        }
        return base() + documentId + "/image?key=" + URLEncoder.encode(cropKey, StandardCharsets.UTF_8);
    }

    /** Page {@code pageNo} (1-based) of this document, whole. */
    public static String page(UUID documentId, int pageNo) {
        return base() + documentId + "/page/" + pageNo;
    }

    /** Test seam: every href built on this thread starts with {@code base} until {@link #clearBase()}. */
    static void useBase(String base) {
        TEST_BASE.set(base);
    }

    static void clearBase() {
        TEST_BASE.remove();
    }

    private static String base() {
        String base = TEST_BASE.get();
        return base == null ? BASE : base;
    }
}
