package com.elioo.baymax.web.adapter.in.ui;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * A return path a page may link or redirect to: "/" or a page under /app, checked raw and decoded; anything else is the
 * fallback. A browser reads "/\host" (and "/%5Chost") as "//host", another site, so a backslash anywhere is refused.
 */
final class LocalPath {

    private static final Pattern SAFE = Pattern.compile("/|/app(/[A-Za-z0-9._~%/-]*)?(\\?[A-Za-z0-9._~%=&/-]*)?");

    private LocalPath() {
    }

    static String or(String raw, String fallback) {
        if (raw == null || raw.indexOf('\\') >= 0 || !SAFE.matcher(raw).matches()) {
            return fallback;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
        return decoded.indexOf('\\') >= 0 || decoded.startsWith("//") || !SAFE.matcher(decoded).matches() ? fallback : raw;
    }
}
