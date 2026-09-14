package com.elioo.healthcare.llm.model;

import java.util.Base64;

/**
 * One image sent alongside a prompt. Providers that cannot accept images ignore these
 * (see {@link com.elioo.healthcare.llm.api.LlmClient#supportsImages()}).
 *
 * @param mediaType IANA type, e.g. {@code image/jpeg}; providers accept jpeg, png, gif and webp
 * @param base64    the image bytes, base64-encoded, without a {@code data:} prefix
 */
public record LlmImage(String mediaType, String base64) {

    public static final String JPEG = "image/jpeg";

    public LlmImage {
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType is required");
        }
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("base64 is required");
        }
    }

    public static LlmImage jpeg(byte[] bytes) {
        return new LlmImage(JPEG, Base64.getEncoder().encodeToString(bytes));
    }

    public static LlmImage jpeg(String base64) {
        return new LlmImage(JPEG, base64);
    }

    /** {@code data:<mediaType>;base64,<data>}, the form OpenAI-compatible APIs expect. */
    public String asDataUri() {
        return "data:" + mediaType + ";base64," + base64;
    }

    /** Approximate decoded size, for logging and limit checks; never logs the bytes themselves. */
    public int approximateBytes() {
        return base64.length() / 4 * 3;
    }
}
