package com.elioo.baymax.web.adapter.in;

/**
 * Renders under a sentinel image base ({@link ImageRefs#useBase}): every href {@link ImageRefs} builds on this thread
 * starts with {@link #BASE} until {@link #close()}. An href built by hand still starts with the real route, so it can no
 * longer pass for one of ImageRefs' own.
 */
public final class ImageRefsSentinel implements AutoCloseable {

    public static final String BASE = "/__imagerefs__/";

    private ImageRefsSentinel() {
        ImageRefs.useBase(BASE);
    }

    public static ImageRefsSentinel install() {
        return new ImageRefsSentinel();
    }

    @Override
    public void close() {
        ImageRefs.clearBase();
    }
}
