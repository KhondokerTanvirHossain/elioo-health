package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.ImageRefs;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;

/**
 * What every page shares: the base path, the app column and the error page in the request's language, the top bar
 * and its language toggle, the labels for a document type, a status and a clinical-context section, the image href,
 * and the language switch itself. Strings come from {@link UiCopy} in the language the {@code baymax_lang} cookie names
 * (Bangla by default). Every app page is built from the same use cases the JSON API uses, so what the browser sees is
 * exactly what the API allows.
 *
 * <p>Images are never linked by signed URL: {@code <img src>} points at {@code /app/documents/{id}/image?key=…},
 * which signs afresh on every load, so a page left open past the 15-minute signature never shows a broken image.
 */
@Component
@RequiredArgsConstructor
public class PageChrome {

    static final String BASE = "/app";
    /** The company site, linked from the landing footer (DR-15). */
    static final String ELIOO = "https://www.eliooo.org/";
    /** The collaboration address on the landing page (Tanvir, 2026-09-19). */
    static final String CONTACT_EMAIL = "k.tanvir.hossain@gmail.com";

    private final UiCopy copy;
    private final SessionAuthFilter sessions;

    /** {@code GET /lang/{code}?back=/path}: set the cookie, go back. Only a local path is followed. */
    public Mono<ServerResponse> switchLang(ServerRequest request) {
        Lang lang = Lang.parse(request.pathVariable("code"));
        String back = LocalPath.or(request.queryParam("back").orElse(null), "/");
        return ServerResponse.seeOther(URI.create(back))
                .header(HttpHeaders.SET_COOKIE, lang.cookie(sessions.cookie("x").isSecure()).toString()).build();
    }

    /** {@link ImageRefs#crop}: every crop href on every page, and in {@code /app/api}, comes from there. */
    static String imageHref(UUID documentId, String key) {
        return ImageRefs.crop(documentId, key);
    }

    // ---- shared pieces -----------------------------------------------------------------------------------

    /** An app page: the column layout, this language, the disclaimer footer, not indexed. */
    Mono<ServerResponse> app(Lang lang, String body) {
        return Html.html(HttpStatus.OK, Html.page(lang, copy.t(lang, "app.home.title"), body, 0, false, copy.t(lang, "disclaimer"), false));
    }

    /** A small error page in the app shell, in the request's language. */
    Mono<ServerResponse> errorPage(ServerRequest request, HttpStatus status, String text) {
        Lang lang = Lang.of(request);
        return Html.html(status, Html.page(lang, copy.t(lang, "app.home.title"),
                "<h1>" + copy.t(lang, "app.sorry") + "</h1><div class=\"err\">" + text + "</div>"
                        + "<p><a class=\"btn quiet\" href=\"" + BASE + "/home\">" + copy.t(lang, "app.err.return") + "</a></p>",
                0, false, copy.t(lang, "disclaimer"), false));
    }

    String text(ServerRequest request, String key, Object... args) {
        return copy.t(Lang.of(request), key, args);
    }

    /** The top bar: brand home, language toggle, and the logout button when signed in. */
    String top(Lang lang, String who, boolean signedIn) {
        String here = signedIn ? BASE + "/home" : BASE + "/";
        return top(lang, who, signedIn, here, here);
    }

    /**
     * The top bar with the page's own brand target ({@code home}) and the language switch's way back ({@code back}).
     * The sign-in pages pass their own base and step, so the {@code /app/basic} fallback keeps a visitor without
     * JavaScript on {@code /app/basic} — a switch on the code step lands back on the code step — instead of the
     * scripted sign-in at {@code /app/} (plan W3-api, S1).
     */
    String top(Lang lang, String who, boolean signedIn, String home, String back) {
        return "<div class=\"top\"><a class=\"brand\" href=\"" + home + "\">" + Html.brand(copy.t(lang, "app.title")) + "</a>"
                + "<div class=\"actions\">" + langToggle(lang, back)
                + (signedIn ? logout(lang, who) : "")
                + "</div></div>";
    }

    /** The logout button, with whose account it is when known (the top bar, and the পরিবার tab from A5). */
    String logout(Lang lang, String who) {
        return "<form method=\"post\" action=\"" + BASE + "/logout\"><button type=\"submit\" class=\"quiet\">"
                + (who == null ? "" : "<span class=\"who\">" + esc(who) + " · </span>") + copy.t(lang, "app.logout") + "</button></form>";
    }

    String langToggle(Lang lang, String back) {
        String q = "?back=" + java.net.URLEncoder.encode(back, java.nio.charset.StandardCharsets.UTF_8);
        return "<nav class=\"lang\" aria-label=\"language\">"
                + "<a href=\"/lang/bn" + q + "\" aria-current=\"" + (lang == Lang.BN) + "\" hreflang=\"bn\">" + copy.t(lang, "lang.bn") + "</a>"
                + "<a href=\"/lang/en" + q + "\" aria-current=\"" + (lang == Lang.EN) + "\" hreflang=\"en\">" + copy.t(lang, "lang.en") + "</a></nav>";
    }

    /** Label for a document type; the raw value if unknown, never a guess. */
    String documentType(Lang lang, String type) {
        if (type == null) {
            return copy.t(lang, "type.default");
        }
        String label = copy.maybe(lang, "type." + type);
        return label != null ? label : esc(type);
    }

    /**
     * Label for a clinical-context section, or null for one the UI does not show. {@code advice} is the
     * doctor's own words transcribed from the page, shown under "as written on the prescription" (PO ruling,
     * 2026-09-18): "no advice in this UI" means no Baymax-generated advice, which still holds absolutely —
     * nothing here is composed, reordered or interpreted. {@code referral} is a single field, not a list.
     */
    String contextSection(Lang lang, String key) {
        if (key == null || "referral".equals(key)) {
            return null;
        }
        String label = copy.maybe(lang, "ctx." + key);
        return label != null ? label : esc(key);
    }

    String status(Lang lang, String status) {
        if (status == null) {
            return "";
        }
        String cls = "NEEDS_RETAKE".equals(status) || "FAILED".equals(status) ? "tag retake" : "tag";
        String label = copy.maybe(lang, "status." + status);
        return "<span class=\"" + cls + "\">" + (label != null ? label : copy.t(lang, "status.PROCESSING")) + "</span>";
    }

    static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw BaymaxException.notFound("not_found", "no such page");
        }
    }
}
