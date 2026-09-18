package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * The whole UI is plain HTML rendered on the server: no framework, no build step, nothing to download but
 * the page, one stylesheet and the images it names. Every value that came from a document or a person
 * passes through {@link #esc} before it reaches a page.
 *
 * <p>Design (DR-14): the stylesheet at {@link #CSS_PATH} is the only place a colour, radius, spacing step
 * or type size is written (asserted by a test); pages use class names and tokens, never literals. Legible on
 * a 360px Android over a slow connection: no fonts, no scripts, images sized by CSS, no horizontal scroll.
 * Nothing interpretive anywhere but the one released message on the document page.
 */
final class Html {

    private Html() {
    }

    /** Where the token stylesheet is served (classpath {@code baymax/ui/baymax.css}). */
    static final String CSS_PATH = "/assets/baymax.css";

    /**
     * The stylesheet link pages emit: the path plus a version query that is the hash of the file's bytes, so a
     * browser may cache the asset for a day and still sees every new build. Without this the first restyle was
     * served stale for a day to anyone who had opened the app before it.
     */
    static final String CSS_HREF = CSS_PATH + "?v=" + version("/baymax/ui/baymax.css");
    static final String ICON_HREF = "/assets/favicon.svg?v=" + version("/baymax/ui/favicon.svg");

    /**
     * Mio, the mascot, referenced from this one place so swapping the file is one change (an original asset
     * from Tanvir — never a generated or approximated character). Until the file is in the jar the hero shows
     * a plain teal disc, not a stand-in figure.
     */
    static final String MASCOT = "/assets/mio.webp";

    /** The medioo wordmark, header of every page. Same rule: one constant, rendered only once the file is in the jar. */
    static final String WORDMARK = "/assets/medioo.webp";

    /** True when the asset behind an {@code /assets/…} path is in the jar, so a slot never shows a broken image. */
    static boolean present(String assetPath) {
        String file = assetPath.substring("/assets/".length());
        try (java.io.InputStream in = Html.class.getResourceAsStream("/baymax/ui/" + file)) {
            return in != null;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** The brand mark for a top bar: the wordmark image when present, else the dot and the name. */
    static String brand(String title) {
        return present(WORDMARK) ? "<img alt=\"" + esc(title) + "\" src=\"" + WORDMARK + "?v=" + version("/baymax/ui/medioo.webp") + "\" width=\"123\" height=\"44\">"
                : "<span class=\"dot\"></span>" + title;
    }


    /**
     * @param title          already HTML-escaped (it comes from {@link UiCopy}); escaping it again showed "&#39;" in the tab
     * @param lang           the page language, on {@code <html lang>} and in the disclaimer footer
     * @param refreshSeconds when > 0 the page reloads itself after that many seconds — the timeline while a
     *                       document is still being read. Plain HTML, no script.
     * @param wide           landing layout ({@code main.page}) rather than the app column ({@code main.app})
     * @param disclaimer     the footer text; on every page, app and landing alike (BMX-6b safety constraint)
     */
    static String page(Lang lang, String title, String body, int refreshSeconds, boolean wide, String disclaimer, boolean index) {
        return "<!doctype html><html lang=\"" + lang.code + "\"><head><meta charset=\"utf-8\">"
                + (refreshSeconds > 0 ? "<meta http-equiv=\"refresh\" content=\"" + refreshSeconds + "\">" : "")
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + (index ? "" : "<meta name=\"robots\" content=\"noindex\">")
                + "<title>" + title + "</title><link rel=\"stylesheet\" href=\"" + CSS_HREF + "\"><link rel=\"icon\" type=\"image/svg+xml\" href=\"" + ICON_HREF + "\"></head><body><main class=\""
                + (wide ? "page" : "app") + "\">" + body
                + "<footer class=\"foot\">" + disclaimer + "</footer></main></body></html>";
    }

    static String version(String resource) {
        try (java.io.InputStream in = Html.class.getResourceAsStream(resource)) {
            byte[] bytes = in == null ? new byte[0] : in.readAllBytes();
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static Mono<ServerResponse> html(HttpStatus status, String page) {
        return ServerResponse.status(status).contentType(MediaType.TEXT_HTML).bodyValue(page);
    }

    static Mono<ServerResponse> redirect(String path) {
        return ServerResponse.seeOther(URI.create(path)).build();
    }

    static String esc(Object value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(value).toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
