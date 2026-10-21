package com.elioo.baymax.web.adapter.in.ui;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * A return path a page may link or redirect to: "/" or a page under /app, checked raw and decoded; anything else is the
 * fallback. A browser reads "/\host" (and "/%5Chost") as "//host", another site, so a backslash anywhere is refused; it
 * resolves "." and ".." path segments (and "%2e" spelled ones) before it requests, so "/app/../api" is not under /app
 * and is refused too. A malformed escape is the fallback, never an exception.
 */
public final class LocalPath {

    private static final Pattern SAFE = Pattern.compile("/|/app(/[A-Za-z0-9._~%/-]*)?(\\?[A-Za-z0-9._~%=&/-]*)?");

    private LocalPath() {
    }

    public static String or(String raw, String fallback) {
        if (raw == null || raw.indexOf('\\') >= 0 || !SAFE.matcher(raw).matches()) {
            return fallback;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
        return decoded.indexOf('\\') >= 0 || decoded.startsWith("//") || !SAFE.matcher(decoded).matches()
                || hasDotSegment(raw) || hasDotSegment(decoded) ? fallback : raw;
    }

    /** A whole path segment that is "." or ".." (the query is not resolved by the browser, so it is not looked at). */
    private static boolean hasDotSegment(String path) {
        int query = path.indexOf('?');
        for (String segment : (query < 0 ? path : path.substring(0, query)).split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
